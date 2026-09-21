package com.rokidlab.rokidlink

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.util.Log
import com.rokid.cxr.Caps
import com.rokid.cxr.CXRServiceBridge

/**
 * KeyButtonService 的 CXR 桥接协调器（v3.9 拆分自 KeyButtonService）。
 *
 * 职责：
 *  1. 创建/销毁 [CXRServiceBridge]，注册连接状态监听
 *  2. 注册全部下行订阅（config/tts/tool_confirm/quiz/continue/ping/hello/Ai/AIUI/...）
 *  3. 协议握手（LinkProtocol v2）：就绪时主动通告 + 应答 HELLO_REQ
 *  4. 断线自愈：断线武装 → 重连成功启动观察窗口 → 窗口内无下行判定路由 stale →
 *     经 Alarm 自杀重启进程（cxr-service 分发路由 stale 只能靠进程重启恢复）
 */
internal class CxrBridgeCoordinator(
    private val service: KeyButtonService,
    private val core: KeyServiceCore,
    private val aiTakeover: AiTakeoverCoordinator,
    private val keyRoutes: KeyRouteCoordinator,
    private val toolConfirm: ToolConfirmController,
    private val overlays: GlassesOverlayController,
    private val ipReporter: GlassesIpReporter,
    private val aiuiHost: AiuiHostController,
    private val configHandlers: ConfigHandlers,
) {
    companion object {
        private const val TAG = KeyButtonService.TAG
        /** 断线重连后的下行观察窗口：手机端 ping 周期 60s，观察 150s 覆盖 ≥2 个周期，避免正常空闲误判 */
        private const val SELF_HEAL_CHECK_DELAY_MS = 150_000L
        /** Alarm 拉活与自杀之间的延迟：留给系统注册 alarm */
        private const val SELF_HEAL_RESTART_DELAY_MS = 3_000L
        /** 自杀后兜底拉起的延迟：给 START_STICKY 留出重启时间，超时未起则由兜底闹钟显式拉起 */
        private const val SELF_HEAL_BOOTSTRAP_DELAY_MS = 20_000L
    }

    /** 是否处于「断线武装」状态：onDisconnected 置 true，重连成功后启动观察窗口 */
    private var reconnectArmed = false

    /** 重连成功时刻：观察窗口起点，窗口内无下行则判定路由失效 */
    private var reconnectAtMs = 0L

    /** 自愈检查任务引用（主线程 Handler），cancel 用 */
    private var selfHealCheck: Runnable? = null

    /** B1：去重断连重建任务，避免堆叠导致订阅倍发 */
    private var pendingReconnect: Runnable? = null

    fun init() {
        try {
            core.bridge = CXRServiceBridge()
            core.bridge?.setStatusListener(object : CXRServiceBridge.StatusListener {
                override fun onConnected(name: String, address: String, type: Int) {
                    Log.i(TAG, "CXR connected: name=$name, address=$address, type=$type")
                    core.bridgeConnected = true
                    // 连接建立后若已连 WiFi，立即上行眼镜 IP（首次/重连后让手机端尽快拿到）
                    ipReporter.sendGlassesIp()
                    // 断线重连成功：cxr-service 分发路由可能在重连后 stale（订阅返回 0 但实际不投递，
                    // 实测 ai_config/tts/show_main 全部静默丢失，仅进程重启可恢复）。
                    // 启动观察窗口：期间收到任何下行（ping/config/tts）即健康，否则判定路由失效自愈。
                    if (reconnectArmed) {
                        reconnectArmed = false
                        reconnectAtMs = System.currentTimeMillis()
                        scheduleSelfHealCheck()
                    }
                }
                override fun onDisconnected() {
                    core.bridgeConnected = false
                    // IMU 数据流随链路一起停：手机不在时上行必失败，继续采样纯属耗电
                    runCatching { service.headImu.stop() }
                    // 断线期不做路由判定：取消观察并武装，待重连成功后重新启动观察
                    reconnectArmed = true
                    cancelSelfHealCheck()
                    Log.i(TAG, "CXR disconnected, will re-init in 3s")
                    // 断线期间清空下行过滤窗口与累积 ASR，避免重连后误吞用户提问/误拦文本
                    core.downlinkUntilMs = 0L
                    aiTakeover.cancelPendingContinueDialog()
                    // 断线自愈：cxr-service 重启/蓝牙闪断后重建桥接并重订阅，避免永久失联
                    // B1 修复：先取消旧任务去重；若 3s 内已自动重连成功则跳过，
                    //          避免丢弃刚连好的 bridge 重建、造成同一条 config/tts/ai 被重复下发
                    pendingReconnect?.let { core.mainHandler.removeCallbacks(it) }
                    val reconnectTask = Runnable {
                        pendingReconnect = null
                        if (core.bridgeConnected) {
                            Log.i(TAG, "CXR already reconnected before 3s, skip re-init")
                            return@Runnable
                        }
                        runCatching { core.bridge?.disconnectCXRDevice() }
                        runCatching {
                            core.bridge = null
                            init()
                        }.onFailure { Log.e(TAG, "re-init CXR bridge failed", it) }
                    }
                    pendingReconnect = reconnectTask
                    core.mainHandler.postDelayed(reconnectTask, 3000)
                }
                override fun onConnecting(name: String, address: String, type: Int) {
                    Log.i(TAG, "CXR connecting: name=$name, address=$address, type=$type")
                }
                override fun onARTCStatus(health: Float, reset: Boolean) {}
                override fun onRokidAccountChanged(account: String) {
                    Log.i("CXRServiceBridge", "Rokid account changed: $account")
                }
            })

            val result = core.bridge?.subscribe(KeyButtonService.TOPIC, CXRServiceBridge.MsgCallback { _, args, _ ->
                configHandlers.handleKeyConfig(args)
            })
            Log.i(TAG, "subscribe(${KeyButtonService.TOPIC}) -> $result")

            // 历史兜底订阅（wifi_config 已废弃，仅保留兼容旧版手机端；WiFi 配置主通道为官方 Wifi 频道）
            @Suppress("DEPRECATION")
            run {
                val wifiResult = core.bridge?.subscribe(KeyButtonService.WIFI_TOPIC, CXRServiceBridge.MsgCallback { _, args, _ ->
                    configHandlers.handleWifiConfig(args)
                })
                Log.i(TAG, "subscribe(${KeyButtonService.WIFI_TOPIC}) -> $wifiResult")
            }

            val ttsResult = core.bridge?.subscribe(KeyButtonService.TTS_TOPIC, CXRServiceBridge.MsgCallback { _, args, _ ->
                aiTakeover.handleTtsPlay(args)
            })
            Log.i(TAG, "subscribe(${KeyButtonService.TTS_TOPIC}) -> $ttsResult")

            val ttsStopResult = core.bridge?.subscribe(KeyButtonService.TTS_STOP_TOPIC, CXRServiceBridge.MsgCallback { _, _, _ ->
                core.markDownlink()
                Log.i(TAG, "Received tts_stop, stopping local TTS")
                TtsPlaybackHelper.stop()
            })
            Log.i(TAG, "subscribe(${KeyButtonService.TTS_STOP_TOPIC}) -> $ttsStopResult")

            // 工具确认请求（手机端 → 眼镜端）：call_phone 等副作用工具执行前的用户确认
            val toolConfirmResult = core.bridge?.subscribe(
                LinkProtocol.TOPIC_TOOL_CONFIRM,
                CXRServiceBridge.MsgCallback { _, args, _ ->
                    core.markDownlink()
                    toolConfirm.handleRequest(args)
                }
            )
            Log.i(TAG, "subscribe(${LinkProtocol.TOPIC_TOOL_CONFIRM}) -> $toolConfirmResult")

            val quizResult = core.bridge?.subscribe(KeyButtonService.QUIZ_TOPIC, CXRServiceBridge.MsgCallback { _, args, _ ->
                configHandlers.handleQuizConfig(args)
            })
            Log.i(TAG, "subscribe(${KeyButtonService.QUIZ_TOPIC}) -> $quizResult")

            // 连续对话（多轮免唤醒）开关：手机端设置页切换时下发，落 prefs 后立即改变
            // isContinueDialogEnabled() 的返回值（无需重连/重启服务）。
            val continueResult = core.bridge?.subscribe(KeyButtonService.CONTINUE_TOPIC, CXRServiceBridge.MsgCallback { _, args, _ ->
                core.markDownlink()
                configHandlers.handleContinueDialogConfig(args)
            })
            Log.i(TAG, "subscribe(${KeyButtonService.CONTINUE_TOPIC}) -> $continueResult")

            // 推送通道远程踢活：手机端检测到 RFCOMM 推送监听连续秒断（监听假死）时经 CXR
            // 频道下发（CXR 由系统 cxr-service 托管，推送死了它仍可达），收到后重建 AsrPushServer。
            val pushRestartResult = core.bridge?.subscribe(AiChannel.TOPIC_PUSH_RESTART, CXRServiceBridge.MsgCallback { _, _, _ ->
                core.markDownlink()
                Log.w(TAG, "push restart requested by phone, recreating AsrPushServer")
                AsrPushServer.restart()
            })
            Log.i(TAG, "subscribe(${AiChannel.TOPIC_PUSH_RESTART}) -> $pushRestartResult")

            // 接收 Lab 下发的 AI 配置（baseUrl/apiKey/model），供眼镜端直接调用模型
            val aiCfgResult = core.bridge?.subscribe(KeyButtonService.AI_CONFIG_TOPIC, CXRServiceBridge.MsgCallback { _, args, _ ->
                configHandlers.handleAiConfig(args)
            })
            Log.i(TAG, "subscribe(${KeyButtonService.AI_CONFIG_TOPIC}) -> $aiCfgResult")

            // 订阅 "Ai" 频道：拦截官方 AI 链路的 ASR 文字，改用 Lab 模型回复。
            // 广播式路由，与 AssistServer 的订阅不冲突（已真机验证）。
            val aiResult = core.bridge?.subscribe(KeyButtonService.AI_TOPIC, CXRServiceBridge.MsgCallback { _, args, _ ->
                aiTakeover.handleAiChannel(args)
            })
            Log.i(TAG, "subscribe(${KeyButtonService.AI_TOPIC}) -> $aiResult")
            // 订阅手机端轮询拉取通道（请求-响应，绕过 AI App 上行过滤）
            subscribeAiAsrPoll()
            // 订阅显示状态页指令：手机端「打开 RokidLink」时带 EXTRA_SHOW_UI 显示 IP 状态页
            val showMainResult = core.bridge?.subscribe(KeyButtonService.SHOW_MAIN_TOPIC, CXRServiceBridge.MsgCallback { _, _, _ ->
                core.markDownlink()
                configHandlers.showMainActivity()
            })
            Log.i(TAG, "subscribe(${KeyButtonService.SHOW_MAIN_TOPIC}) -> $showMainResult")

            // AIUI 自托管宿主控制：open / close / msg（onMessage 伪交互通道）
            val aiuiHostResult = core.bridge?.subscribe(KeyButtonService.AIUI_HOST_TOPIC, CXRServiceBridge.MsgCallback { _, args, _ ->
                core.markDownlink()
                aiuiHost.handleAiuiHost(args)
            })
            Log.i(TAG, "subscribe(${KeyButtonService.AIUI_HOST_TOPIC}) -> $aiuiHostResult")

            // 下行存活探测订阅：手机端连接期间每 60s 下发一条空消息。
            // 断线重连后 cxr-service 分发路由可能 stale（订阅返回 0 但实际不投递，
            // 实测 ai_config/tts/show_main 全部静默丢失，仅进程重启可恢复）。
            // ping 一旦到达即证明路由健康；观察窗口到期仍收不到则 Alarm 重启进程自愈。
            val pingResult = core.bridge?.subscribe(AiChannel.TOPIC_PING, CXRServiceBridge.MsgCallback { _, _, _ ->
                core.markDownlink()
                Log.i(TAG, "Received ping — downlink route healthy")
            })
            Log.i(TAG, "subscribe(${AiChannel.TOPIC_PING}) -> $pingResult")

            // 协议握手（插播 B · LinkProtocol v2）：手机端主动询问时回报版本与能力，
            // 覆盖「眼镜端后启动 / 手机端重连」场景。
            val helloResult = core.bridge?.subscribe(LinkProtocol.TOPIC_HELLO_REQ, CXRServiceBridge.MsgCallback { _, _, _ ->
                core.markDownlink()
                announceHello()
            })
            Log.i(TAG, "subscribe(${LinkProtocol.TOPIC_HELLO_REQ}) -> $helloResult")

            // IMU 头动采集控制（v1 查询层）：手机端 CUSTOMAPP 会话就绪后下发 imu_start 开流，
            // 断连/销毁时停。默认关 —— 眼镜端不连接就不采样，零常驻开销。
            val imuCtrlResult = core.bridge?.subscribe(AiChannel.TOPIC_IMU_CTRL, CXRServiceBridge.MsgCallback { _, args, _ ->
                core.markDownlink()
                service.headImu.handleControl(args)
            })
            Log.i(TAG, "subscribe(${AiChannel.TOPIC_IMU_CTRL}) -> $imuCtrlResult")

            // 停止手机投屏指令：手机端按「停止投屏」时下发。
            // PhoneMirrorActivity 设计为 socket 断开后保持前台等重连（避免重连后画面更新在
            // 后台不可见），因此必须显式关闭，否则最后一帧画面会残留在眼镜上。
            // 复用页面既有的 ACTION_FINISH_MIRROR 广播（与 ScreenMirrorIntentActivity 同一通道）。
            val stopMirrorResult = core.bridge?.subscribe(
                AiChannel.TOPIC_STOP_PHONE_MIRROR,
                CXRServiceBridge.MsgCallback { _, _, _ ->
                    core.markDownlink()
                    Log.i(TAG, "Received stop_phone_mirror — closing PhoneMirrorActivity")
                    core.mainHandler.post {
                        runCatching {
                            service.sendBroadcast(
                                Intent(PhoneMirrorActivity.ACTION_FINISH_MIRROR)
                                    .setPackage(service.packageName)
                            )
                        }
                    }
                }
            )
            Log.i(TAG, "subscribe(${AiChannel.TOPIC_STOP_PHONE_MIRROR}) -> $stopMirrorResult")

            // 图片下发：把手机端对话气泡里的图片显示到眼镜端（悬浮图片层，12s 后自动隐藏）。
            // 与歌词/工具确认复用同一套悬浮层授权（SYSTEM_ALERT_WINDOW）。
            val showImageResult = core.bridge?.subscribe(
                AiChannel.TOPIC_SHOW_IMAGE,
                CXRServiceBridge.MsgCallback { _, args, _ ->
                    core.markDownlink()
                    overlays.handleShowImage(args)
                }
            )
            Log.i(TAG, "subscribe(${AiChannel.TOPIC_SHOW_IMAGE}) -> $showImageResult")

            // 打开页面：手机端要求把某个眼镜端 Activity 拉到前台（如说「显示歌词」→ 系统音乐页）。
            val openAppResult = core.bridge?.subscribe(
                AiChannel.TOPIC_OPEN_APP,
                CXRServiceBridge.MsgCallback { _, args, _ ->
                    core.markDownlink()
                    configHandlers.handleOpenApp(args)
                }
            )
            Log.i(TAG, "subscribe(${AiChannel.TOPIC_OPEN_APP}) -> $openAppResult")

            // 服务就绪：主动握手通告一次（手机端据此免探测获知眼镜端能力）
            announceHello()
        } catch (e: Exception) {
            Log.e(TAG, "initCxrBridge failed", e)
        }
    }

    // ──────────────────────────────────────────────
    //  协议握手（插播 B · LinkProtocol v2）
    // ──────────────────────────────────────────────

    /**
     * 上报本端协议版本与能力位（caps = [version, capsBitmask, linkVersion]）。
     *
     * 手机端据此免探测获知眼镜端能力；旧版手机端会忽略未知 topic，无副作用。
     * 服务就绪时主动调用一次，并对 [LinkProtocol.TOPIC_HELLO_REQ] 应答。
     */
    internal fun announceHello() {
        val b = core.bridge ?: run {
            Log.w(TAG, "announceHello skipped: no bridge")
            return
        }
        runCatching {
            val linkVersion = runCatching {
                service.packageManager.getPackageInfo(service.packageName, 0).versionName
            }.getOrNull() ?: "-"
            val caps = Caps()
            caps.write(LinkProtocol.PROTOCOL_VERSION.toString())
            caps.write(LinkProtocol.Cap.ALL.toString())
            caps.write(linkVersion)
            val r = b.sendMessage(LinkProtocol.TOPIC_HELLO, caps)
            Log.i(TAG, "hello sent: version=${LinkProtocol.PROTOCOL_VERSION} caps=0x${LinkProtocol.Cap.ALL.toString(16)} linkVersion=$linkVersion -> $r")
        }.onFailure { Log.e(TAG, "announceHello failed", it) }
    }

    // ──────────────────────────────────────────────
    //  断线自愈（cxr-service 分发路由 stale 探测）
    // ──────────────────────────────────────────────

    /** 重连成功（此前断线过）后启动观察：SELF_HEAL_CHECK_DELAY_MS 后检查
     *  期间是否收到过下行。路由 stale 时 cxr-service 不再投递任何订阅消息，
     *  进程内重建 bridge 无法恢复（实测仅进程重启有效），故经 Alarm 自杀重启。 */
    private fun scheduleSelfHealCheck() {
        cancelSelfHealCheck()
        val healCheck = Runnable {
            selfHealCheck = null
            runSelfHealCheck()
        }
        selfHealCheck = healCheck
        core.mainHandler.postDelayed(healCheck, SELF_HEAL_CHECK_DELAY_MS)
        Log.i(TAG, "Self-heal check armed in ${SELF_HEAL_CHECK_DELAY_MS}ms (reconnectAt=$reconnectAtMs)")
    }

    private fun cancelSelfHealCheck() {
        selfHealCheck?.let { core.mainHandler.removeCallbacks(it) }
        selfHealCheck = null
    }

    /** 观察窗口到期判定：bridge 仍连接 且 窗口内无任何下行 → 路由 stale，重启进程 */
    private fun runSelfHealCheck() {
        selfHealCheck = null
        if (!core.bridgeConnected) {
            Log.i(TAG, "Self-heal check: bridge disconnected, skip (will re-arm on reconnect)")
            return
        }
        if (core.lastDownlinkMs >= reconnectAtMs) {
            Log.i(TAG, "Self-heal check: downlink healthy (last=${core.lastDownlinkMs} reconnectAt=$reconnectAtMs)")
            return
        }
        Log.w(TAG, "Self-heal check: NO downlink since reconnect — cxr-service route stale, restarting process")
        selfHealRestart()
    }

    /** AlarmManager 延迟 SELF_HEAL_RESTART_DELAY_MS 后触发 SelfRestartReceiver：
     *  Handler.postDelayed 在进程自杀后不存活，必须用系统级 Alarm（RTC_WAKEUP）确保广播可投递。
     *  双闹钟设计：① 自杀闹钟（3s）→ 接收器杀进程，由 START_STICKY/显式启动重建订阅；
     *  ② 兜底拉起闹钟（+20s）→ 若粘性重启失败，Alarm 唤醒新进程显式启动两个常驻服务。 */
    private fun selfHealRestart() {
        try {
            val am = service.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val now = System.currentTimeMillis()
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            val killPi = PendingIntent.getBroadcast(service, 0,
                Intent(service, SelfRestartReceiver::class.java)
                    .setAction(KeyButtonService.ACTION_SELF_HEAL_RESTART), flags)
            am.set(AlarmManager.RTC_WAKEUP, now + SELF_HEAL_RESTART_DELAY_MS, killPi)
            val bootPi = PendingIntent.getBroadcast(service, 1,
                Intent(service, SelfRestartReceiver::class.java)
                    .setAction(KeyButtonService.ACTION_SELF_HEAL_BOOTSTRAP), flags)
            am.set(AlarmManager.RTC_WAKEUP,
                now + SELF_HEAL_RESTART_DELAY_MS + SELF_HEAL_BOOTSTRAP_DELAY_MS, bootPi)
            Log.w(TAG, "Self-heal: process restart scheduled (kill+${SELF_HEAL_RESTART_DELAY_MS}ms, " +
                "bootstrap+${SELF_HEAL_RESTART_DELAY_MS + SELF_HEAL_BOOTSTRAP_DELAY_MS}ms)")
        } catch (e: Exception) {
            Log.e(TAG, "selfHealRestart failed", e)
        }
    }

    /** 手机端轮询拉取 ASR 文字（可回复订阅，请求-响应机制）。
     *  文字主通道为 RFCOMM 长连接（AsrPushServer），本通道保留空响应占位。 */
    private fun subscribeAiAsrPoll() {
        val b = core.bridge ?: return
        try {
            val r = b.subscribe(KeyButtonService.AI_ASR_POLL_TOPIC, CXRServiceBridge.MsgReplyCallback { _, _, _, reply ->
                core.markDownlink()
                reply.end(Caps())
                Log.d(TAG, "AI poll reply: empty")
            })
            Log.i(TAG, "subscribe(${KeyButtonService.AI_ASR_POLL_TOPIC}) -> $r")
        } catch (e: Exception) {
            Log.e(TAG, "subscribeAiAsrPoll error", e)
        }
    }

    /** 服务销毁：断开 CXR 连接并清空桥接 */
    fun onDestroy() {
        runCatching { core.bridge?.disconnectCXRDevice() }
        core.bridge = null
    }
}
