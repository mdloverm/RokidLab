// Rokid SDK 的 sendCustomCmd 自带「注意 Caps 体积」的废弃标记，
// 但这是当前唯一的下行通道，短期内不可能替换，故整文件抑制该告警。
@file:Suppress("DEPRECATION")

package com.rokidlab.phone.glasses

import android.content.Context
import android.util.Log
import com.rokid.cxr.Caps
import com.rokid.cxr.link.CXRLink
import com.rokidlab.phone.app.LabApplication
import com.rokidlab.phone.connection.ConnectionRouteManager
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

/**
 * AIUI 微前端控制器（从 CxrLHiRokidSession 拆出）。
 *
 * 职责：眼镜端 AIUI（.aix）微前端全链路——
 *  - AgentStore 渠道：Jsai_AddNativeAgent 安装 / Ai_RenderPayload 打开 / 目录地址下发；
 *  - 直启渠道：Sys_AIUI_Start / Sys_AIUI_Stop 直启眼镜 cxr 目录里已存在的 .aix；
 *  - 自托管宿主：推 .aix 到 RokidLink（run-as 分块落盘主通道 + 7658 socket 兜底）
 *    并经 rokidlab_aiui_host 主题驱动 AiuiLinkActivity 渲染。
 *
 * 协议细节（逆向自眼镜 AssistServer/SysCmdHelper/JsaiCmdHelper）见各方法注释。
 */
internal class AiuiFrontendController(
    private val appContext: Context,
    private val appScope: CoroutineScope,
    private val routeManager: ConnectionRouteManager,
    private val linkProvider: () -> CXRLink?,
    /** 与下行主链路共用的按条串行锁（同会话 aiCmdLock） */
    private val cmdLock: Any,
    /** 绕过 CXR-L SDK cmd 黑名单的直发函数（同会话 rawSendCustomCmd） */
    private val rawSendCmd: (CXRLink, String, Caps) -> Int,
    /** 常驻 ADB shell 客户端获取（同会话 getAdbShellClient，带隧道重试语义） */
    private val adbClientProvider: () -> com.rokidlab.phone.adb.AdbShellClient?,
) {
    companion object {
        private const val TAG = "AiuiFrontendController"

        /** 宿主控制通道名（双端同源，勿改） */
        private const val AIUI_HOST_TOPIC = "rokidlab_aiui_host"
        /** 眼镜端 App 包名（宿主 Activity 与其私有目录都挂在它下面） */
        private const val GLASSES_PKG = "com.rokidlab.rokidlink"

        /**
         * 「.aix 已落盘、但宿主没能拉起」的返回串（区别于 null=推送失败、OK=已落盘且拉起指令已下发）。
         * 上层消费方只认 "OK"，故任何其它取值都会如实显示为失败。
         */
        const val PUSHED_OPEN_FAILED = "PUSHED_OPEN_FAILED"
    }

    @Volatile
    private var agentListPushWindowJob: Job? = null

    fun openAiuiAgent(
        agentId: String,
        agentName: String,
        nativeVersion: String = "0.0.74",
        pageName: String = "pages/index/index",
    ): Int {
        val link = linkProvider() ?: return -2
        return try {
            // 内层 jsui 描述（tools 的 function.name 即 .aix 内的页面入口路径）
            val layout = JSONObject().put("width", 480).put("height", 168)
            val funcParams = JSONObject()
                .put("type", "object")
                .put("properties", JSONObject())
                .put("required", JSONArray())
            val func = JSONObject()
                .put("name", pageName)
                .put("description", agentName)
                .put("parameters", funcParams)
            val tool = JSONObject()
                .put("type", "function")
                .put("target", "_current")
                .put("layout", layout)
                .put("function", func)
                .put("ink_version", ">=0.14.0")
            val inner = JSONObject()
                .put("agentId", agentId)
                .put("nativeVersion", nativeVersion)
                .put("tools", JSONArray().put(tool))
            // 外层包装：jsui 字段是内层 JSON 的字符串（与官方下行载荷一致）
            val payload = JSONObject()
                .put("type", "jsui")
                .put("jsui", inner.toString())

            val caps = Caps()
            caps.write(LinkProtocol.CXR_CHANNEL_AI_RENDER)
            caps.write(payload.toString())
            val result = synchronized(cmdLock) {
                link.sendCustomCmd(LinkProtocol.CXR_CHANNEL_AI, caps)
            }
            Log.i(TAG, "openAiuiAgent(agentId=$agentId) sendCustomCmd(Ai/Ai_RenderPayload) -> $result")
            result ?: -3
        } catch (e: Exception) {
            Log.e(TAG, "openAiuiAgent(agentId=$agentId) failed", e)
            -1
        }
    }

    /**
     * 直启眼镜上已存在于 cxr 目录的 .aix（Sys_AIUI_Start，不经过 AgentStore/目录同步）。
     *
     * 协议（逆向自 AssistServer SysCmdHelper + AiuiPackageManager，真机验证）：
     *  - CXR 通道 "Sys"，caps[0] = "Sys_AIUI_Start"（SysCmdHelper received 分派键，取 caps[0]），
     *    caps[1] = packageName（.aix 文件名去 .aix 后缀）。AssistServer 收到后调
     *    AiuiPackageManager.startAiui(context, packageName)：在 device-protected
     *    filesDir/aiui/package/cxr/<packageName>.aix 查找文件，存在则
     *    AiuiActivity.launch(context, 文件路径) 直接渲染该 .aix（不入 AgentStore、
     *    不被 syncAgentList purge、无需网络下载）。
     *
     * @param packageName .aix 文件名（不含 .aix），须与上传到 cxr 目录的文件一致
     * @return 发送结果码（0=成功下发，非 0=失败；不代表渲染成功）
     */
    fun startAiuiPackage(packageName: String): Int {
        val link = linkProvider() ?: return -2
        return try {
            val caps = Caps()
            caps.write("Sys_AIUI_Start")
            caps.write(packageName)
            val result = synchronized(cmdLock) { rawSendCmd(link, LinkProtocol.CXR_CHANNEL_SYS, caps) }
            Log.i(TAG, "startAiuiPackage(packageName=$packageName) rawSendCustomCmd(Sys/Sys_AIUI_Start) -> $result")
            result
        } catch (e: Exception) {
            Log.e(TAG, "startAiuiPackage(packageName=$packageName) failed", e)
            -1
        }
    }

    /**
     * 关闭眼镜上正在渲染的 .aix（Sys_AIUI_Stop）。
     *
     * 协议（逆向自 AssistServer SysCmdHelper + AiuiPackageManager）：CXR 通道 "Sys"，
     * caps[0] = "Sys_AIUI_Stop"，caps[1] = packageName。眼镜端比对当前正在运行的
     * AIUI 包，若一致则 AiuiActivity.finishIfRunning() 关闭渲染（不删除 cxr 目录文件）。
     *
     * @param packageName 要关闭的包名（.aix 文件名去 .aix），通常传正在渲染的那个
     * @return 发送结果码（0=成功下发；不代表已关闭，需眼镜日志/画面确认）
     */
    fun stopAiuiPackage(packageName: String): Int {
        val link = linkProvider() ?: return -2
        return try {
            val caps = Caps()
            caps.write("Sys_AIUI_Stop")
            caps.write(packageName)
            val result = synchronized(cmdLock) { rawSendCmd(link, LinkProtocol.CXR_CHANNEL_SYS, caps) }
            Log.i(TAG, "stopAiuiPackage(packageName=$packageName) rawSendCustomCmd(Sys/Sys_AIUI_Stop) -> $result")
            result
        } catch (e: Exception) {
            Log.e(TAG, "stopAiuiPackage(packageName=$packageName) failed", e)
            -1
        }
    }

    // ─────────────────────────────────────────────────────────────
    //  AIUI 自托管宿主（RokidLink Web 宿主链路：推 .aix → 7658 端口 → AiuiLinkActivity）
    // ─────────────────────────────────────────────────────────────

    /** 打开宿主渲染本地已推送的 .aix（fileName 不含 .aix 亦可） */
    /**
     * 打开宿主渲染指定 .aix。
     *
     * @param launchParams 启动参数（JSON 对象字符串），由眼镜端在页面 boot 完成后
     *   作为第一条 hostMessage 下发（type="launch"）。**不能在 open 之后立刻用
     *   msg 下发**：页面 WebView 尚未解包/渲染，hostMessage 会被静默丢弃。
     */
    fun openAiuiHost(fileName: String? = null, launchParams: String? = null): Int =
        sendAiuiHostCmd("open", fileName, launchParams)

    /** 关闭正在渲染的宿主 */
    fun closeAiuiHost(): Int = sendAiuiHostCmd("close", null)

    /** 以 onMessage 协议向宿主页面注入消息（伪交互补充通道） */
    fun sendAiuiHostMessage(json: String): Int = sendAiuiHostCmd("msg", json)

    /**
     * 拉起宿主。
     *
     * 宿主 Activity 是 exported=false，**只有同进程的 KeyButtonService 能拉起它** ——
     * ADB `am start` 以 shell(uid 2000) 身份启非导出 Activity，会被 AMS 以
     * `Permission Denial: ... not exported from uid` 拒绝。故唯一有效通道是
     * [AIUI_HOST_TOPIC]：由眼镜端 KeyButtonService 在进程内 startActivity。
     * 旧实现的 `am start` 兜底自 exported=false 起已必然失败，已移除。
     *
     * [balExempt] 仅用于诊断日志：后台启动限制(BAL)会拦「后台进程启动 Activity」，
     * 授权 SYSTEM_ALERT_WINDOW 可豁免；但 KeyButtonBridgeActivity 常驻保持进程可见时
     * 通常不受限，所以这里不再据此跳过通道，而是统一走 topic 试一次。
     *
     * @return true = 拉起指令已成功下发（链路在、CXR send 成功）；**不代表宿主一定已渲染**
     */
    private fun openHostBestEffort(
        fileName: String,
        launchParams: String?,
        balExempt: Boolean,
    ): Boolean {
        val r = openAiuiHost(fileName, launchParams)
        if (r == 0) return true
        Log.w(TAG, "openHostBestEffort: openAiuiHost returned $r (balExempt=$balExempt) -> 宿主未能拉起")
        return false
    }

    private fun sendAiuiHostCmd(cmd: String, arg: String?, arg2: String? = null): Int {
        val link = linkProvider() ?: return -2
        return try {
            val caps = Caps()
            caps.write(cmd)
            if (arg != null) caps.write(arg)
            if (arg2 != null) caps.write(arg2)
            val result = synchronized(cmdLock) { rawSendCmd(link, AIUI_HOST_TOPIC, caps) }
            Log.i(TAG, "sendAiuiHostCmd($cmd) -> $result")
            result
        } catch (e: Exception) {
            Log.e(TAG, "sendAiuiHostCmd($cmd) failed", e)
            -1
        }
    }

    /**
     * 把本地 .aix 推到 RokidLink 的 aiui_host 目录并自动拉起宿主渲染。
     *
     * 传输通道说明：眼镜端 adbd 拒绝任意 tcp 转发（`open tcp:7658` 实测报
     * "adbd does not support arbitrary tcp connections"），且同一眼镜同一时刻仅允许
     * 一条 RFCOMM（adb 常驻隧道占满，BT 7659→7658 隧道会 "BT RFCOMM connect failed"）。
     * 因此 7658 socket 通道在 Rokid 眼镜上不可靠，主通道改为**复用常驻 adb shell**：
     * run-as 分块 base64 落盘到 RokidLink filesDir/aiui_host/<name>.aix
     * （RokidLink 为 debug 构建，run-as 可写私有目录；AiuiPackageServer 目录同名，
     * handleAiuiHost open 直接命中）。BT/adb socket 仅作兜底。
     *
     * @return null=推送失败；[PUSHED_OPEN_FAILED]=已落盘但宿主没能拉起；"OK"=已落盘且拉起指令已下发
     */
    fun pushAixToRokidLinkHost(
        aixFile: File,
        openAfter: Boolean = true,
        launchParams: String? = null,
    ): String? {
        if (!aixFile.isFile) {
            Log.w(TAG, "pushAixToRokidLinkHost: file missing ${aixFile.absolutePath}")
            return null
        }
        val app = appContext as LabApplication
        // BT 隧道空闲 60s 会自动断开，断后再推首连常失败（resolve 尚未恢复）：
        // adb client 获取失败时短退避重试，覆盖隧道重连窗口，避免 AIUI 首推报失败。
        var client = adbClientProvider()
        var attempt = 0
        while (client == null && attempt < 3) {
            attempt++
            Thread.sleep(1500L * attempt)
            Log.w(TAG, "pushAixToRokidLinkHost: no adb client, retry $attempt/3...")
            runCatching { routeManager.clearRouteCache() }
            client = adbClientProvider()
        }
        val name = aixFile.name
        val body = aixFile.readBytes()

        // Android 12+ 后台启动限制(BAL)：RokidLink 常驻前台服务，拉起 AiuiLinkActivity 会被系统
        // 以 allowBackgroundActivityStart=false 静默拒绝（用户侧=「眼镜没权限 / 没反应」）。
        // 先授权并**复核**，仅用于诊断日志 —— 宿主唯一可用的拉起通道是同进程 topic
        // （见 openHostBestEffort），AM 直启路径已随 exported=false 失效。
        var balExempt = false
        if (client != null) {
            com.rokidlab.phone.platform.ShellOps.grantSystemAlertWindow(client, GLASSES_PKG)
            balExempt =
                (com.rokidlab.phone.platform.ShellOps.isSystemAlertWindowAllowed(client, GLASSES_PKG)
                    as? com.rokidlab.phone.platform.Capability.Available)?.value == true
            if (!balExempt) {
                Log.w(TAG, "glasses BAL exemption NOT confirmed (host launch may be blocked by BAL)")
            }
            // 通道 1（主）：run-as 分块 base64 落盘（统一收口到 L0 ShellOps）。
            when (val push = com.rokidlab.phone.platform.ShellOps.pushFileRunAs(
                client, GLASSES_PKG, "files/aiui_host", name, body)) {
                is com.rokidlab.phone.platform.Capability.Available -> {
                    // 落盘成功 ≠ 拉起成功：拉起失败必须如实上报，避免上层把「点了没反应」
                    // 显示成「打开成功」（旧实现无条件 return "OK"）。
                    val opened = !openAfter || openHostBestEffort(name, launchParams, balExempt)
                    return if (opened) "OK" else PUSHED_OPEN_FAILED
                }
                is com.rokidlab.phone.platform.Capability.Unavailable ->
                    Log.w(TAG, "pushAixToRokidLinkHost($name): run-as write failed (${push.reason}), fallback socket push...")
            }
        }
        // 通道 2（兜底）：7658 socket —— **WiFi 直连优先，失败降级蓝牙隧道**，最后再试 adb smart socket。
        // 说明：WiFi 直连是否可用由 routeManager 判定（adbd 可达即认 WiFi 可用，见 ConnectionRouteManager）；
        // 由于 AiuiPackageServer(7658) 在 RokidLink 内按需启动，WiFi 上可能 ECONNREFUSED，故任何
        // 连接异常都必须降级到蓝牙隧道重试，而不是直接判定失败（否则表现就是「切了 WiFi 反而推不进去」）。
        val nameB = name.toByteArray(Charsets.UTF_8)
        val frame = java.io.ByteArrayOutputStream(body.size + nameB.size + 6).apply {
            write((nameB.size shr 8) and 0xFF); write(nameB.size and 0xFF)
            write(nameB)
            write((body.size ushr 24) and 0xFF); write((body.size ushr 16) and 0xFF)
            write((body.size ushr 8) and 0xFF); write(body.size and 0xFF)
            write(body)
        }.toByteArray()
        var ack: String? = null
        // 2a. WiFi 直连
        val wifiIp = app.glassesIp
        if (wifiIp.isNotBlank() && routeManager.isWifiReachable(wifiIp)) {
            Log.i(TAG, "pushAixToRokidLinkHost: WiFi direct $wifiIp:7658")
            ack = pushFrameOverTcp(wifiIp, 7658, frame)
            // WiFi 首选失败 → 记账，使后续 resolve 不再把新操作导向已死的 WiFi
            if (ack == null) routeManager.noteWifiFailure()
        }
        // 2b. 蓝牙隧道兜底（WiFi 不可达 / WiFi 连接失败）
        if (ack == null) {
            routeManager.tunnelTo(7658)?.let { localPort ->
                Log.i(TAG, "pushAixToRokidLinkHost: BT tunnel 127.0.0.1:$localPort → :7658")
                ack = pushFrameOverTcp("127.0.0.1", localPort, frame)
            }
        }
        // 2c. adb smart socket（最末兜底：要求 adbd 放行 tcp 转发，多数环境不可用）
        if (ack == null) {
            try {
                client?.let { ack = it.sendTcpStream(7658, frame) }
            } catch (e: Exception) {
                Log.e(TAG, "pushAixToRokidLinkHost: adb push failed: ${e.message}")
            }
        }
        Log.i(TAG, "pushAixToRokidLinkHost($name) fallback ack=${ack?.take(16)}")
        if (ack?.trim() == "OK" && openAfter && !openHostBestEffort(name, launchParams, balExempt)) {
            // 推送成功但宿主没能拉起：如实返回「已推送未打开」，不再让上层显示「打开成功」
            return PUSHED_OPEN_FAILED
        }
        return ack
    }

    /**
     * 将 .aix 帧（名字长度+名字+体长+体）写入指定 TCP 端点并读回 ACK。
     *
     * WiFi 直连与蓝牙隧道共用同一帧格式，仅目标地址不同，故抽出复用。
     *
     * @return 服务端 ACK 文本（trim 后）；连接/读失败返回 null
     */
    private fun pushFrameOverTcp(host: String, port: Int, frame: ByteArray): String? = try {
        java.net.Socket().apply {
            connect(java.net.InetSocketAddress(host, port), 5_000)
            tcpNoDelay = true
            soTimeout = 20_000
        }.use { sock ->
            val out = sock.getOutputStream()
            out.write(frame)
            out.flush()
            val resp = ByteArray(64)
            val n = sock.getInputStream().read(resp)
            if (n <= 0) null else String(resp, 0, n, Charsets.UTF_8).trim()
        }
    } catch (e: Exception) {
        Log.w(TAG, "pushAixToRokidLinkHost: tcp push $host:$port failed: ${e.message}")
        null
    }

    /**
     * 向眼镜端 AssistServer 下发「安装 AIUI agent」指令（Jsai_AddNativeAgent）。
     *
     * 协议（逆向自 AssistServer + 真机日志验证触发）：CXR 通道 "Jsai"，
     * caps[0] = "Jsai_AddNativeAgent"（JsaiCmdHelper 分派键），caps[1] = JSON 载荷：
     * {agentId, agentName, url, fileMd5, nativeVersion, inkVersion, ...}。
     * AssistServer 收到后走 AIUI_JsaiAgentDownload 官方下载链路：眼镜经手机 NetProxy
     * 代理拉取 url 指定的 .aix（本机用 AiuiProject.hostAix 托管，127.0.0.1 由代理解析到手机），
     * 校验 fileMd5 后写入 agents_index.json（PACKAGE_INDEX），之后即可用 Ai_RenderPayload 打开。
     *
     * @param agentId agent UUID（.aix 内 VERSION 内容，须与打包时一致）
     * @param agentName 展示名
     * @param url .aix 下载地址（手机本地托管，眼镜经代理访问）
     * @param fileMd5 .aix 文件 MD5（眼镜端下载后校验）
     * @return 发送结果码（0=成功下发，非 0=失败；不代表眼镜端下载/安装完成）
     */
    fun installAiuiAgent(
        agentId: String,
        agentName: String,
        url: String,
        fileMd5: String,
        nativeVersion: String = "0.0.74",
        agentDesc: String = "",
    ): Int {
        val link = linkProvider() ?: return -2
        return try {
            val payload = JSONObject()
                .put("agentId", agentId)
                .put("agentName", agentName)
                .put("url", url)
                .put("fileMd5", fileMd5)
                .put("nativeVersion", nativeVersion)
                .put("inkVersion", "")
                .put("agentDesc", agentDesc)
                .put("agentLogo", "")
            val caps = Caps()
            caps.write("Jsai_AddNativeAgent")
            caps.write(payload.toString())
            val result = synchronized(cmdLock) {
                link.sendCustomCmd(LinkProtocol.CXR_CHANNEL_JSAI, caps)
            }
            Log.i(TAG, "installAiuiAgent(agentId=$agentId url=$url md5=$fileMd5) -> $result")
            result ?: -3
        } catch (e: Exception) {
            Log.e(TAG, "installAiuiAgent(agentId=$agentId) failed", e)
            -1
        }
    }

    /**
     * 直装 .aix 并在下载完成后自动打开一次（满足「安装完唤醒一次」）。
     *
     * 实测结论（2026-09-05）：目录注入路线不可行——眼镜只认领「自己发起的
     * phone_request_info 询问」（仅在官方 AI 会话连接时触发，走官方 App 链路），
     * 手机主动 push 的 Jsai_GetRequestInfo 全部被眼镜记为
     * "onMobileRequestInfo ignored: no active request flight"，agent 永不进入目录，
     * AgentResolver 报 RECORD_NOT_FOUND → OPEN_FAIL。
     *
     * 因此改回 Jsai_AddNativeAgent 直装：眼镜自行按 url（本机 AixHttpServer 托管，
     * 127.0.0.1 经 NetProxy 中继到手机）下载 .aix 并写入 PACKAGE_INDEX。
     * 该包按 REMOTE_SYNC 落盘，可能被后续周期 sync purge，但下载+登记只需 1~2s；
     * 安装完成后 [delay] 内自动发起 Ai_RenderPayload 打开一次并重试，
     * 在 purge 发生前完成本次运行即可（用户只需要这一次唤醒）。
     *
     * @param agentId agent UUID（.aix 内 VERSION，须与打包一致）
     * @param agentName 展示名
     * @param url .aix 下载地址（手机本地托管）
     * @param fileMd5 .aix 文件 MD5（眼镜下载后校验）
     * @param openDelayMs 下发安装后等待眼镜下载完成的毫秒数
     * @param openRetryMs 打开失败（包尚未就绪）时的重试间隔
     * @param openTimeoutMs 打开重试总超时
     * @return 安装指令发送结果码（0=已下发；不等于渲染成功）
     */
    fun installAndOpenAiuiAgentOnce(
        agentId: String,
        agentName: String,
        url: String,
        fileMd5: String,
        openDelayMs: Long = 3000L,
        openRetryMs: Long = 2000L,
        openTimeoutMs: Long = 25_000L,
    ): Int {
        stopAgentListPushWindow()
        val install = installAiuiAgent(
            agentId = agentId,
            agentName = agentName,
            url = url,
            fileMd5 = fileMd5,
            nativeVersion = "0.0.74",
            agentDesc = agentName,
        )
        Log.i(TAG, "installAndOpenAiuiAgentOnce: AddNativeAgent -> $install")
        if (install != 0) return install
        appScope.launch(Dispatchers.IO) {
            delay(openDelayMs)
            val deadline = System.currentTimeMillis() + openTimeoutMs
            var attempt = 0
            while (System.currentTimeMillis() < deadline && isActive) {
                attempt++
                val r = openAiuiAgent(agentId, agentName)
                Log.i(TAG, "installAndOpenAiuiAgentOnce: open attempt#$attempt -> $r")
                if (r == 0) break
                delay(openRetryMs)
            }
        }
        return 0
    }

    /**
     * 向眼镜下发「native agent 目录地址」（Jsai_GetRequestInfo）。
     *
     * 逆向依据：眼镜 JsaiAiuiHostProvider 周期 fetchAndSyncNativeAgentList 时使用
     * JsaiAuthStore.agentListUrl 拉取目录；该 URL 由手机通过 Jsai 命令
     * caps[0]="Jsai_GetRequestInfo"、caps[1]=JSON{agentListUrl,tokenKey,tokenValue,env}
     * 下发（onMobileRequestInfo → JsaiAuthStore.update）。若不下发，眼镜沿用官方云端
     * 目录，其中不含我方私有 agent → syncAgentList 取消下载(code=499)并 purge。
     *
     * 下发后眼镜即把 [agentListUrl] 当作目录：我方在该地址返回含目标 agent 的
     * data 数组（见 AiuiProject/AixHttpServer /agents.json），眼镜据此合法下载安装，
     * 不再取消。
     *
     * ⚠️ 实测（2026-09-05）：此主动 push 通道对眼镜无效——眼镜一律忽略为
     * "no active request flight"，仅剩注册表回复兜底与记录价值。
     *
     * @return 发送结果码（0=成功下发）
     */
    fun pushAiuiAgentListUrl(agentListUrl: String): Int {
        val link = linkProvider() ?: return -2
        return try {
            val payload = JSONObject()
                .put("agentListUrl", agentListUrl)
                .put("tokenKey", "")
                .put("tokenValue", "")
                .put("env", 2)
            val caps = Caps()
            caps.write("Jsai_GetRequestInfo")
            caps.write(payload.toString())
            val result = synchronized(cmdLock) {
                link.sendCustomCmd(LinkProtocol.CXR_CHANNEL_JSAI, caps)
            }
            Log.i(TAG, "pushAiuiAgentListUrl(url=$agentListUrl) -> $result")
            result ?: -3
        } catch (e: Exception) {
            Log.e(TAG, "pushAiuiAgentListUrl failed", e)
            -1
        }
    }

    /**
     * 在时间窗口内周期下发目录配置（Jsai_GetRequestInfo+JSON）。
     *
     * 背景：眼镜只在 phone_request_info 飞行激活期间认领手机回复（60s 窗口、首个生效）。
     * 该飞行由眼镜 AI 会话连接（glassAppConnectChange=true）或空配置时的
     * NotifyGlassGetList 触发。若本地未及时观察到询问（询问可能只发给官方 App 链路），
     * 用周期推送兜底：一旦窗口打开，我们窗口内的下一条推送即被认领。
     * 未被认领的推送眼镜侧忽略（"duplicate or stale"/"no active flight"），无副作用。
     */
    fun startAgentListPushWindow(
        catalogUrl: String,
        durationMs: Long = 60_000L,
        intervalMs: Long = 1_500L,
    ) {
        stopAgentListPushWindow()
        agentListPushWindowJob = appScope.launch(Dispatchers.IO) {
            val deadline = System.currentTimeMillis() + durationMs
            var n = 0
            while (System.currentTimeMillis() < deadline && isActive) {
                n++
                val r = pushAiuiAgentListUrl(catalogUrl)
                Log.i(TAG, "agentListPushWindow #$n url=$catalogUrl -> $r")
                delay(intervalMs)
            }
            Log.i(TAG, "agentListPushWindow done ($n pushes)")
        }
    }

    fun stopAgentListPushWindow() {
        agentListPushWindowJob?.cancel()
        agentListPushWindowJob = null
    }

    /**
     * 通知眼镜立即重新拉取 native agent 目录（Jsai_NotifyGlassGetList）。
     *
     * 眼镜收到后 notifyGlassGetList → fetchAndSyncNativeAgentList（用刚下发的
     * agentListUrl），从而立刻同步并下载我方目录中的 .aix，无需等周期同步。
     * 该命令无载荷（caps 仅一项）。
     *
     * @return 发送结果码（0=成功下发）
     */
    fun notifyGlassGetAgentList(): Int {
        val link = linkProvider() ?: return -2
        return try {
            val caps = Caps()
            caps.write("Jsai_NotifyGlassGetList")
            val result = synchronized(cmdLock) {
                link.sendCustomCmd(LinkProtocol.CXR_CHANNEL_JSAI, caps)
            }
            Log.i(TAG, "notifyGlassGetAgentList() -> $result")
            result ?: -3
        } catch (e: Exception) {
            Log.e(TAG, "notifyGlassGetAgentList failed", e)
            -1
        }
    }
}
