package com.rokidlab.phone.platform

import android.content.Context
import android.util.Log
import com.rokidlab.phone.adb.AdbShellClient
import com.rokidlab.phone.util.LogCollector

/**
 * ADB 端点（IP + 端口）。用简单数据类而非 L1 的 ConnectionRoute，
 * 让 L0 不反向依赖连接层——路由决策由调用方在 provider lambda 里完成。
 *
 * @param viaBluetooth 该端点是否经蓝牙隧道（`127.0.0.1:本地端口` → RFCOMM → 眼镜）。
 *   显式标注而非靠 `ip == "127.0.0.1"` 猜 —— 线路升级判定需要区分「已在 WiFi 上」与
 *   「还挂在蓝牙隧道上」，这是升级逻辑的唯一依据。
 */
data class AdbEndpoint(val ip: String, val port: Int, val viaBluetooth: Boolean = false)

/**
 * L0 platform/AdbTransport —— 全 App 共享 ADB shell 会话的**唯一所有者**（Phase 2 收尾）。
 *
 * **为什么必须唯一**：手机侧蓝牙栈对「同一设备 + 同一 SCN」只允许一条客户端 RFCOMM 通道。
 * 若各处自行建链，第二条会话会把正在服务的那条挤断（表现为"用着用着突然不行了"）。
 *
 * **为什么要能主动释放**：屏幕镜像 / 手机投屏 / 文件浏览需要长时间独占隧道；
 * 共享会话若还占着通道，它们的建链会被栈直接拒绝。长连接消费者上场前必须 [release]，
 * 之后 [get] 会在下次调用时自动重建，调用方无感知。
 *
 * **线程安全**：多个后台消费者（AI 工具 + ASR 兜底轮询 + 定时任务）可能同时来取，
 * 不加锁会并发建链并触发通道争抢 —— 所有入口 `@Synchronized`。
 *
 * **与 L1 `connection/ChannelArbiter` 的分工**：本类只负责「会话所有权 + 串行化」——
 * 同一条 socket 谁在用、何时重建/释放；「优先级让路」（长连接占用 RFCOMM 时常规消费者退避）
 * 由 `ChannelArbiter` 负责。二者互补且不重叠：本类不感知优先级，仲裁器不感知 socket 生命周期。
 * 长连接上场前的完整动作是：取 `LONG_LIVED` 租约 **并** 调用 [release] 腾出通道
 * （现由 `domain/MirrorCoordinator` 统一执行）。
 *
 * @param contextProvider 应用 Context（传 Application，勿持 Activity）
 * @param endpointProvider 解析当前可用 ADB 端点（WiFi 直连优先，失败回落蓝牙隧道）；
 *   返回 null 表示当前无可用路径。由调用方注入（内部可含路由缓存/阻塞解析）。
 * @param onRouteFailure 线路缓存失效回调（建链失败、或后台线路升级成功后调用，
 *   由调用方清理缓存使下一次探测重新决策）。默认空实现。
 * @param preferredEndpointProvider 首选（WiFi 直连）端点的**只读**探测，用于后台线路升级；
 *   必须无副作用、不建蓝牙隧道、可快速失败；返回 null 表示首选线路当前不可用。
 *   为 null 表示不做升级（会话一旦建立就固定在该端点上，即旧行为）。
 * @param onWifiFailure WiFi 端点建链失败回调（记录 WiFi 断线信号，使线路缓存中的 WiFi 立即失效）。
 *   仅当失败端点**未经蓝牙隧道**（即 WiFi 直连）时触发。
 */
class AdbTransport(
    private val contextProvider: () -> Context,
    private val endpointProvider: () -> AdbEndpoint?,
    private val onRouteFailure: () -> Unit = {},
    private val preferredEndpointProvider: (() -> AdbEndpoint?)? = null,
    private val onWifiFailure: () -> Unit = {},
) {
    private companion object {
        const val TAG = "AdbTransport"

        /**
         * 后台线路升级的探测间隔。
         *
         * 会话一旦建在蓝牙隧道上就会被无限复用（`isConnected()` 为真直接返回），
         * 于是「先连蓝牙、后连 WiFi」时永远享受不到 WiFi 带宽。这里每 30s 探一次 WiFi，
         * 就绪即拆掉重建。间隔不能太短：每次探测是 2s 超时的 TCP connect，
         * 而 `get()` 是 AI 工具的高频入口。
         */
        const val UPGRADE_PROBE_INTERVAL_MS = 30_000L

        /**
         * 会话空闲超过该时长后，取用前对 WiFi 端点做一次快速存活校验。
         *
         * 取 5s：高频连续调用（<5s 间隔）不应为每次取会话都付一次 TCP 往返；
         * 而「刚断网后用户再发指令」这类场景，间隔基本都大于 5s，正好被拦下。
         */
        const val WIFI_LIVENESS_IDLE_MS = 5_000L

        /**
         * 存活校验的 TCP 超时。必须远小于命令期超时（10~15s）：
         * 校验的意义就是「宁可花 2s 提前发现线路死了改走蓝牙，也不要让调用方等 10s 后失败」。
         */
        const val WIFI_LIVENESS_TIMEOUT_MS = 2_000
    }

    /**
     * 建链专锁：把「TCP 建链 + ADB 握手」（最长 TCP 超时 10s + 握手超时 15s）与类锁 [this] 分离。
     *
     * **为什么必须分离**：类锁同时保护 [release] / [shutdown]，而这两个方法会在**主线程**被调用
     * —— `CxrLHiRokidSession.cleanup()` → `adbTransport.shutdown()`，而 `cleanup()` 是
     * 「引导页跳过/发送按钮」「主页自动拉起 RokidLink」等同步路径的第一段。旧实现把建链放在类锁内，
     * 眼镜不在线或 WiFi 失联时一次建链要等满 TCP 超时 + 握手超时，期间主线程只能死等这把锁，
     * 5s 内无法响应触摸 → 系统弹「无响应」（2026-09-14 真机三次 ANR 全是这一条栈）。
     *
     * 本锁只串行化建链本身（并发建出两条 RFCOMM 会话会把彼此挤断），**不与类锁同时持有**。
     */
    private val connectLock = Any()

    /**
     * 会话代数：每次 [release] / [shutdown] 自增。
     *
     * 建链是锁外的长耗时操作，期间调用方可能已经显式释放会话（主线程 `cleanup()`）。
     * 建链完成后必须比对代数，把「释放期间建成」的会话丢弃 —— 否则会复活一个
     * 调用方已明确要拆掉的会话，在眼镜侧留下一条无人持有的 RFCOMM 通道。
     */
    @Volatile
    private var generation = 0L

    @Volatile
    private var client: AdbShellClient? = null

    /** 当前会话实际建在哪个端点上（供升级判定；null = 无常驻会话） */
    @Volatile
    private var currentEndpoint: AdbEndpoint? = null

    /** 上次后台升级探测时间戳（节流用） */
    @Volatile
    private var lastUpgradeProbeAt = 0L

    /** 升级探测进行中标志：防止探测/重建叠加 */
    @Volatile
    private var upgrading = false

    /**
     * 首选（WiFi）线路已探测可用，等待切换到它的标志。
     *
     * 探测线程**只置位、不释放会话**：真正的拆建发生在 [get] 里、且必须确认会话空闲
     * （见 [AdbShellClient.isBusy]）。旧实现由探测线程直接 `release()`，会在调用方
     * 正在传输时把会话掐断（8.7MB 提取撞上过）。
     */
    @Volatile
    private var upgradeReady = false

    /** 上次取用会话的时刻（WiFi 存活校验的空闲判据） */
    @Volatile
    private var lastUsedAt = 0L

    /** 当前共享客户端（可能为 null）；仅用于诊断/日志。 */
    val currentOrNull: AdbShellClient? get() = client

    /**
     * 取共享 ADB shell 会话：已连接则直接复用；断开/缺失则按当前端点重建。
     * @return null = 无可用路径或建链失败（调用方应降级，**不要**自行新建会话）
     *
     * **锁的边界**：类锁只保护**状态读写**（[client] / [currentEndpoint] / [upgradeReady] /
     * [lastUsedAt] / [generation]），建链用的 [connectLock] 是独立的一把、且绝不在持有类锁时获取。
     * 两类阻塞操作被刻意留在锁外 —— ① [cachedWifiRouteIfDead] 的 TCP 存活探测（最长 2s）；
     * ② `endpointProvider()` 内部的 `runBlocking { resolve }`；③ [AdbShellClient.connect]
     * （TCP 10s + 握手 15s）。旧实现把整个方法 `@Synchronized` / 把建链留在类锁内，于是
     * 主线程的 [release] / [shutdown] 会被这些阻塞操作堵死（ANR 根因，见 [connectLock]）。
     */
    fun get(): AdbShellClient? {
        // ── 锁外①：WiFi 缓存会话的空闲存活校验（只读 volatile 快照 + 一次 TCP 探测）──
        val deadEndpoint = cachedWifiRouteIfDead()
        if (deadEndpoint != null) {
            synchronized(this) {
                // 复核：探测期间会话可能已被其它线程重建，仅在端点未被替换时释放
                if (currentEndpoint == deadEndpoint) {
                    Log.i(TAG, "WiFi 缓存线路已死，释放共享 ADB 会话等待重建")
                    release()
                }
            }
        }

        // ── 锁内①：快路径，命中可用会话直接返回（无阻塞 IO）──
        synchronized(this) {
            client?.let { cached ->
                if (cached.isConnected()) {
                    lastUsedAt = System.currentTimeMillis()
                    // 会话挂在蓝牙隧道、首选（WiFi）线路已探测就绪、且当前没有命令/传输在跑：
                    // 「本次」就拆掉重建成 WiFi，而不是先交出蓝牙会话、后台再升级 ——
                    // 后者会让 WiFi 刚通后的第一次操作（往往就是用户那次提取）仍旧跑在蓝牙上。
                    if (currentEndpoint?.viaBluetooth == true && upgradeReady && !cached.isBusy()) {
                        Log.i(TAG, "WiFi 已就绪且会话空闲，本次直接重建共享 ADB 会话到 WiFi")
                        upgradeReady = false
                        // 顺序要紧：先清线路缓存再释放会话（否则重建会命中缓存里仍是蓝牙的旧线路）
                        onRouteFailure()
                        release()
                    } else {
                        // 命中缓存会话时才值得考虑升级：会话断开本就会走下面的重新解析。
                        maybeUpgradeRoute()
                        return cached
                    }
                } else {
                    runCatching { cached.disconnect() }
                    client = null
                    currentEndpoint = null
                }
            }
        }

        // ── 锁外②：解析端点（内部 runBlocking resolve，同样可能阻塞）──
        // 并发解析最多造成重复探测，不会重复建链 —— 由下面的二次确认兜住。
        val endpoint = endpointProvider() ?: return null

        // ── 建链段：串行化用 [connectLock]，建链本身在类锁外（见 [connectLock] 注释）──
        synchronized(connectLock) {
            // 二次确认：并发取用者可能已被前一个线程建好会话，直接复用，
            // 避免并发建出两条会话（第二条 RFCOMM 会把第一条挤断）。
            synchronized(this) {
                client?.let { cached -> if (cached.isConnected()) return cached }
            }

            // 建链前的代数快照：期间若发生 release()/shutdown()（主线程 cleanup 等），
            // 本次建成的会话必须丢弃，不能复活调用方已明确要拆掉的会话。
            val genAtStart = generation

            val established: AdbShellClient? = try {
                val c = AdbShellClient(contextProvider(), endpoint.ip, endpoint.port)
                if (c.connect()) c else {
                    runCatching { c.disconnect() }
                    null
                }
            } catch (e: Exception) {
                // 原先 runCatching{...}.getOrNull() 把异常整个吞掉：connect() 抛出的真实原因
                // （RFCOMM 被栈拒绝 / 握手超时 / 协议不匹配）在 App 内日志里完全看不到，
                // 上层只看到「无可用路径」。此处补落面板。
                LogCollector.e(TAG, "shared adb session 建链异常 -> ${endpoint.ip}:${endpoint.port}", e)
                null
            }

            // ── 锁内③：结算（只写状态，无阻塞 IO）──
            synchronized(this) {
                if (established == null) {
                    // 连接失败（含蓝牙隧道 RFCOMM 卡顿/半开）时通知调用方清理线路缓存
                    onRouteFailure()
                    // WiFi 端点失败额外记账：让线路缓存里的 WiFi 线路立即失效，
                    // 下一次 resolve 重新探测并优先落到蓝牙隧道
                    if (!endpoint.viaBluetooth) onWifiFailure()
                    Log.w(TAG, "shared adb session connect failed -> ${endpoint.ip}:${endpoint.port}")
                    return null
                }
                if (generation != genAtStart) {
                    Log.i(TAG, "建链期间会话已被释放，丢弃本次新建的 ADB 会话")
                    runCatching { established.disconnect() }
                    return null
                }
                client = established
                currentEndpoint = endpoint
                upgradeReady = false
                lastUsedAt = System.currentTimeMillis()
                Log.i(TAG, "shared adb session established -> ${endpoint.ip}:${endpoint.port}")
                return established
            }
        }
    }

    /**
     * 缓存会话的线路存活快速校验（仅 WiFi 直连线路）。
     *
     * @return 被判死的端点（仅 WiFi 直连线路，且空闲超过阈值后探测失败）；无需校验或线路存活时返回 null。
     *
     * 背景：WiFi 骤断时那条已建立的 TCP socket 在本机侧**仍被标记为 connected**
     * （要等一次写失败或心跳才发现），于是 [get] 会把这条「看起来健康」的死会话交出去，
     * 调用方的第一个命令必然超时失败。这里在会话空闲超过 [WIFI_LIVENESS_IDLE_MS] 时，
     * 先用一次 [WIFI_LIVENESS_TIMEOUT_MS] 超时的 TCP 探测提前拦掉：失败即上报 WiFi 失败
     * （清线路缓存）并由 [get] 释放会话 —— 下一次建链自然落到蓝牙隧道。
     *
     * 蓝牙线路不做此校验：那是 RFCOMM，无 TCP 语义；且已有心跳/RFCOMM 失败驱动。
     *
     * **本方法在类锁外调用**，返回端点而非直接释放会话，是为了把「释放」的裁决权交回
     * 持锁方，并允许其在端点已被替换时放弃释放（见 [get]）。
     */
    private fun cachedWifiRouteIfDead(): AdbEndpoint? {
        val ep = currentEndpoint ?: return null
        if (ep.viaBluetooth) return null
        val idle = System.currentTimeMillis() - lastUsedAt
        if (lastUsedAt > 0 && idle < WIFI_LIVENESS_IDLE_MS) return null
        if (probeTcp(ep.ip, ep.port, WIFI_LIVENESS_TIMEOUT_MS)) return null
        Log.w(TAG, "WiFi 会话存活校验失败（空闲 ${idle}ms），判定线路已死 -> ${ep.ip}:${ep.port}")
        LogCollector.w(TAG, "WiFi 线路失联，共享 ADB 会话将改走蓝牙隧道", null)
        onWifiFailure()
        return ep
    }

    /** 单次可达性探测（不建链、无副作用） */
    private fun probeTcp(ip: String, port: Int, timeoutMs: Int): Boolean = try {
        java.net.Socket().use { s ->
            s.connect(java.net.InetSocketAddress(ip, port), timeoutMs)
            true
        }
    } catch (_: Exception) {
        false // catch-ok: 探测失败即视为不可达
    }

    /**
     * 后台线路升级探测：会话建在蓝牙隧道上时，探测 WiFi 直连是否已就绪，就绪则置
     * [upgradeReady]，由下一次 [get] 在会话空闲时完成切换。
     *
     * 为什么必须这么做 —— 蓝牙隧道单条 RFCOMM 实测吞吐只有 WiFi 直连的零头，
     * 而「先连蓝牙、眼镜随后接入同一 WiFi」是最常见的现场：不升级的话，
     * 用户即使把设置里的 IP 填对了、WiFi 确实通了，这条共享 ADB 会话（AI 工具主通道）
     * 仍然一辈子跑在蓝牙上（`get()` 见 `isConnected()` 即返回，永不重新 resolve）。
     *
     * 探测放在独立线程：`get()` 是 AI 工具高频入口，TCP 探测最长要等 2s 超时，不能阻塞它。
     * **本方法自身绝不释放会话** —— 释放交给 [get]，那里能确认会话空闲，不会掐断进行中的传输。
     */
    private fun maybeUpgradeRoute() {
        val probe = preferredEndpointProvider ?: return
        val current = currentEndpoint ?: return
        if (!current.viaBluetooth) return // 已在 WiFi 直连上，无需升级
        val now = System.currentTimeMillis()
        if (upgrading || now - lastUpgradeProbeAt < UPGRADE_PROBE_INTERVAL_MS) return
        lastUpgradeProbeAt = now
        upgrading = true
        Thread {
            try {
                val better = runCatching { probe() }.getOrNull() ?: return@Thread
                if (better == current) return@Thread
                upgradeReady = true
                Log.i(TAG, "WiFi 就绪，共享 ADB 会话将在下次空闲取用时切换到 ${better.ip}:${better.port}")
                LogCollector.i(TAG, "检测到 WiFi 直连可用，ADB 会话将在下次空闲取用时从蓝牙升级")
            } finally {
                upgrading = false
            }
        }.apply { name = "adb-route-upgrade"; isDaemon = true }.start()
    }

    /**
     * 首选（WiFi）线路可能刚刚可用 —— 由网络回调（手机侧 WiFi 重新接入）触发。
     *
     * 立即重探一次（跳过 30s 节流），探通则置 [upgradeReady]，下一次 [get] 即可切换。
     * 没有这个入口时，「WiFi 刚连上」要等满一个节流窗口、且期间必须恰好有 `get()` 调用
     * 才会被探测到 —— 这正是「WiFi 已经连了却还在走蓝牙」的现场。
     *
     * 非阻塞：探测在独立线程；不在蓝牙会话上时直接返回。
     */
    fun onPreferredRouteMaybeAvailable() {
        lastUpgradeProbeAt = 0L
        maybeUpgradeRoute()
    }

    /** 主动释放共享会话（长连接消费者上场前调用），下次 [get] 自动重建。 */
    @Synchronized
    fun release() {
        generation++
        client?.let { runCatching { it.disconnect() } }
        client = null
        currentEndpoint = null
        upgradeReady = false
        Log.i(TAG, "shared adb session released")
    }

    /** 彻底关闭（Session 销毁时调用；语义同 [release]，仅便于日志区分）。 */
    @Synchronized
    fun shutdown() {
        generation++
        client?.let { runCatching { it.disconnect() } }
        client = null
        currentEndpoint = null
        upgradeReady = false
    }
}
