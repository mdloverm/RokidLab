package com.rokidlab.rokidlink

import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log

/**
 * 眼镜端按键映射常驻后台服务（门面层，v3.9 重构）。
 *
 * 本类只负责 Service 生命周期装配，业务逻辑已拆分到以下协调器：
 *  - [KeepAliveManager]：前台服务、WakeLock、心跳、自愈重启、权限兜底
 *  - [KeyRouteCoordinator]：按键广播分发、拍照答题、答题屏幕保亮
 *  - [CxrBridgeCoordinator]：CXR-S 桥接、订阅注册、断线自愈、协议握手
 *  - [AiTakeoverCoordinator]：Ai 频道消息处理、ASR 拦截、连续对话
 *  - [ToolConfirmController]：工具执行前的用户确认窗口
 *  - [GlassesOverlayController]：歌词/图片悬浮层
 *  - [GlassesIpReporter]：眼镜 WiFi IP 上行
 *  - [AiuiHostController]：AIUI 自托管宿主接收与控制
 *  - [ConfigHandlers]：各类配置持久化与杂项指令
 *
 * 职责历史（v3.9 前全部集中在 2344 行的本类内，现已拆完）：
 *  1. 作为 Foreground Service（START_STICKY）保持进程常驻
 *  2. 动态注册按键广播接收器（priority=100 + abortBroadcast）
 *  3. 收到按键事件后，直接启动目标应用
 *  4. 通过 CXR-S SDK 订阅手机端下发的按键配置
 *  5. 持有 PARTIAL_WAKE_LOCK 防止休眠后按键失效
 *  6. 监听 SCREEN_ON 广播，屏幕亮起时校验 receiver 存活状态
 *
 * ★ 不持有任何常驻可见 Activity（历史上有 KeyButtonBridgeActivity 透明保活层，已移除）：
 * 透明 Activity 会成为眼镜的顶层 resumed/焦点应用，导致官方 Launcher 退居后台，
 * 表现为「双端一连上，眼镜自身的触摸板/官方乐奇就失灵，必须在眼镜上做一次退出操作
 * （HOME）把透明层任务踢到后台才能恢复」（2026-09-15 真机复现：mFocusedApp=KeyButtonBridge）。
 * 后台 startActivity 改由 SYSTEM_ALERT_WINDOW（BAL 法定豁免，手机端经 ADB appops 授予）
 * + FGS 保证；保活靠本服务 + WakeLock + 电池优化白名单。
 */
class KeyButtonService : Service() {

    /** 共享上下文（各协调器依赖） */
    private lateinit var core: KeyServiceCore
    private lateinit var keepAlive: KeepAliveManager
    private lateinit var overlays: GlassesOverlayController
    private lateinit var toolConfirm: ToolConfirmController
    private lateinit var keyRoutes: KeyRouteCoordinator
    private lateinit var aiTakeover: AiTakeoverCoordinator
    private lateinit var ipReporter: GlassesIpReporter
    private lateinit var aiuiHost: AiuiHostController
    private lateinit var configHandlers: ConfigHandlers
    private lateinit var cxrBridge: CxrBridgeCoordinator

    override fun onCreate() {
        super.onCreate()
        // 按依赖顺序装配协调器（见类注释拆分说明）
        core = KeyServiceCore(this)
        overlays = GlassesOverlayController(this, core)
        toolConfirm = ToolConfirmController(this, core, overlays)
        keyRoutes = KeyRouteCoordinator(this, core, toolConfirm)
        aiTakeover = AiTakeoverCoordinator(this, core)
        ipReporter = GlassesIpReporter(this, core)
        aiuiHost = AiuiHostController(this, core)
        configHandlers = ConfigHandlers(this, core)
        cxrBridge = CxrBridgeCoordinator(this, core, aiTakeover, keyRoutes, toolConfirm, overlays, ipReporter, aiuiHost, configHandlers)
        // 装配连续对话开关关闭时撤销回调
        configHandlers.continueDialogCanceller = { aiTakeover.cancelPendingContinueDialog() }

        keepAlive = KeepAliveManager(this, core, keyRoutes)
        keepAlive.markStart()
        Log.i(TAG, "Service creating")
        keepAlive.startForegroundService()
        // 拉起蓝牙隧道服务（ADB / 文件 / 投屏的服务端宿主）。两者互不依赖，只有 MainActivity 会同时启动，
        // 而眼镜端被官方 AssistServer 以 ThirdAppScene 强杀后，手机端的自愈路径是
        // `am start-foreground-service KeyButtonService`（phone-app ToolRegistry.ensureGlassesLinkRunning），
        // 此处若不补拉起，BtTunnelServer 的 RFCOMM SCN 无人注册，隧道就再也回不来——表现为
        // 提取/ADB 工具全部卡在「建链」（2026-09-13 真机坐实）。BtTunnelService.start 幂等。
        runCatching { BtTunnelService.start(this) }
            .onFailure { Log.e(TAG, "Failed to start BtTunnelService", it) }
        // 不启动任何常驻可见 Activity（透明保活层会抢走眼镜顶层 resumed 身份，导致官方控制
        // 失灵，见类注释）。后台启动页面由 SYSTEM_ALERT_WINDOW 豁免 BAL。
        keyRoutes.registerKeyReceiver()
        // 蓝牙运行时权限缺失（典型：CXR-L 重装眼镜端后授权被清空）时拉起 MainActivity 申请。
        // Service 自己不能弹运行时权限框；只在本进程存活期内未提示过时尝试一次，避免反复跳页。
        keepAlive.ensureBluetoothPermissionOrPrompt()
        keepAlive.registerScreenOnReceiver()
        keepAlive.acquireWakeLock()
        keepAlive.startHeartbeat()
        cxrBridge.init()
        // AIUI .aix 接收服务常驻：等手机端推包即可拉起自托管宿主渲染
        aiuiHost.startPackageServer()
        // 预热绑定系统 TTS 服务，避免首次「拍照问 AI」回复要等异步绑定
        TtsPlaybackHelper.ensureBound(this)
        // 监听 WiFi 变化并上行眼镜 IP，手机端免手动输入自动填充
        ipReporter.register()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // 手机端或本应用组件可通过 startService(ACTION_QUIZ_PHOTO_ASK) 触发拍照答题上行
        if (intent?.action == ACTION_QUIZ_PHOTO_ASK) {
            Log.i(TAG, "Quiz photo ask triggered via startService")
            keyRoutes.sendPhotoAskToPhone()
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        Log.i(TAG, "Service destroying")
        core.mainHandler.removeCallbacksAndMessages(null)
        // 显式置空待执行的自动续听引用（removeCallbacksAndMessages 已撤掉队列，这里同步清状态）
        aiTakeover.onDestroy()
        // 停 AIUI 接收服务（自愈重启后会在新实例 onCreate 重新拉起）
        aiuiHost.onDestroy()
        // 解绑系统 TtsService：避免 ServiceConnection 泄漏（服务重建时旧连接残留）
        runCatching { TtsPlaybackHelper.unbind(this) }
        // 停止本地接管执行器（openAiSession 等含 sleep 的任务不再继续）
        core.shutdown()
        keyRoutes.unregisterKeyReceiver()
        keepAlive.unregisterScreenOnReceiver()
        keepAlive.releaseWakeLock()
        keyRoutes.releaseQuizWakeLocks()
        overlays.release()
        ipReporter.unregister()
        cxrBridge.onDestroy()
        // 崩溃/异常销毁自愈：延迟检查，若服务未恢复则重新拉起。
        // START_STICKY 在 startRequested=false（服务被 stop）时不生效，需要主动重启。
        // 眼镜 ROM 在 app idle 时可能停服务，且后台 FGS 启动受限，因此多次重试直到成功。
        //
        // ⚠️ 短命销毁防护：若服务启动后 <60s 就被销毁，说明是「启动即失败」（典型如 bridge
        // JNI 初始化崩溃），此时反复重启只会形成无限循环、持续空转耗尽电量与系统资源。
        // 计数必须用 SharedPreferences 持久化——重试计数是方法局部的，每轮 onDestroy 都会
        // 从 0 重新开始，永远触发不到上限。连续 5 次短命销毁后放弃自愈，等待用户手动拉起。
        keepAlive.handleDestroyed()
        core.mainHandler.post { toolConfirm.clear() }
        super.onDestroy()
    }

    companion object {
        internal const val TAG = "KeyButtonService"

        /** 短命销毁计数持久化键：服务启动后短时间内反复被销毁时累加 */
        private const val KEY_SHORT_LIVED_DESTROY_COUNT = "short_lived_destroy_count"

        /** 下行过滤窗口：收到 Lab 下行标志后，窗口内 ASR 视为重发忽略 */
        internal const val DOWNLINK_FILTER_MS = 2_000L

        /** ASR 文字 logcat 输出 tag：手机端经 ADB（蓝牙隧道）读取（CXR 上行被 AI App 过滤，改用 logcat） */
        internal const val AI_ASR_BRIDGE_TAG = "AiAsrBridge"
        internal const val PREFS_NAME = "key_button_config"
        internal const val KEY_SHORT_PKG = "short_pkg"
        internal const val KEY_SHORT_ACT = "short_act"
        internal const val KEY_LONG_PKG = "long_pkg"
        internal const val KEY_LONG_ACT = "long_act"
        internal const val TOPIC = "rokidlab_key_config"
        /**
         * 已废弃：WiFi 配置实际走官方 "Wifi" 频道（Wifi_Connect），手机端已无发送方，
         * 本订阅仅作历史兜底保留，勿再新增发送侧。
         */
        @Deprecated("wifi_config 通道已废弃：WiFi 配置走官方 Wifi 频道（Wifi_Connect）")
        internal const val WIFI_TOPIC = "wifi_config"
        internal const val TTS_TOPIC = "tts_play"
        /** 停止 TTS 播报下行通道（手机端 → 眼镜端）：退出对话/打断播报时手机端主动下发，眼镜端立即停本地 TTS */
        internal const val TTS_STOP_TOPIC = "tts_stop"
        /** 「按键答题」开关下发通道（手机端 → 眼镜端） */
        internal const val QUIZ_TOPIC = "rokidlab_key_quiz"
        /** 「连续对话（多轮免唤醒）」开关下发通道（手机端 → 眼镜端）：见 [KEY_CONTINUE_DIALOG] */
        internal const val CONTINUE_TOPIC = "rokidlab_chat_continue"
        /** 拍照问AI 指令上行通道（眼镜端 → 手机端） */
        internal const val PHOTO_ASK_TOPIC = "rokidlab_photo_ask"
        /** 语音转文字结果上行通道（眼镜端 → 手机端）：唤醒词+语音的 ASR 文字转给 Lab 回复 */
        internal const val AI_ASR_TOPIC = "rokidlab_ai_asr"
        /** 双击退出对话窗口时经 RFCOMM 推送通道上行的音乐停止标记（与手机端保持一致） */
        internal const val MUSIC_STOP_MARKER = LinkProtocol.MARKER_MUSIC_STOP
        /**
         * 用户关闭助手/退出对话时经 RFCOMM 推送通道上行的中止标记（与手机端保持一致）：
         * 手机收到后停音乐 + 下发 tts_stop 停眼镜播报 + 取消运行中的 Lab 模型请求
         */
        internal const val ABORT_AI_MARKER = LinkProtocol.MARKER_ABORT_AI
        /**
         * AI 文字轮询通道（手机端 → 眼镜端）：RokidLab 定时 sendCustomCmd 轮询，
         * 眼镜端可回复订阅返回 ASR 文字。采用请求-响应机制以绕过 AI App 对未知上行指令的过滤。
         */
        internal const val AI_ASR_POLL_TOPIC = "rokidlab_ai_asr_poll"
        /** AI 频道（手机端 → 眼镜端，AssistServer 全局订阅） */
        internal const val AI_TOPIC = LinkProtocol.CXR_CHANNEL_AI
        /** 显示状态页指令通道（手机端 → 眼镜端）：用户点「打开 RokidLink」后，MainActivity 带 EXTRA_SHOW_UI 显示 IP 状态页 */
        internal const val SHOW_MAIN_TOPIC = "rokidlab_show_main"
        /** AI 配置下发通道（手机端 → 眼镜端）：baseUrl/apiKey/model，供眼镜端直接调用模型 */
        internal const val AI_CONFIG_TOPIC = "rokidlab_ai_config"
        /**
         * AIUI 自托管宿主控制通道（手机端 → 眼镜端）：
         * 载荷 [cmd, arg]，cmd ∈ open / close / msg
         *  - open [open, fileName?]：打开 filesDir/aiui_host/<fileName>.aix（缺省用最近推送包）渲染于本宿主
         *  - close：关闭正在渲染的 AIUI 宿主
         *  - msg  [msg, json]：把 JSON 以 onMessage 协议注入页面（伪交互补充通道）
         */
        internal const val AIUI_HOST_TOPIC = "rokidlab_aiui_host"
        /** AI 配置持久化 key */
        internal const val KEY_AI_BASE_URL = "ai_base_url"
        internal const val KEY_AI_API_KEY = "ai_api_key"
        internal const val KEY_AI_MODEL = "ai_model"
        /** 对话模型模式持久化 key：custom = Lab 拦截回复；official = 官方乐奇 */
        internal const val KEY_AI_MODE = "ai_mode"
        // 模型模式值统一引用 AiChannel.AI_MODE_OFFICIAL / AiChannel.AI_MODE_CUSTOM（协议规范单源）
        /** 「按键答题」开关存储 key */
        internal const val KEY_QUIZ_ENABLED = "key_quiz_enabled"
        /** 「连续对话（多轮免唤醒）」开关存储 key：true = 每轮 Lab 回复的本地 TTS 播完后自动重开官方 AI 会话 */
        internal const val KEY_CONTINUE_DIALOG = "chat_continue_dialog"
        /** 外部（如手机端 ADB am startservice）触发拍照答题时通知 Service 的 action */
        internal const val ACTION_QUIZ_PHOTO_ASK = "rokidlab.action.QUIZ_PHOTO_ASK"
        /** 断线重连后下行路由 stale 的自愈：Alarm 拉活广播 action（SelfRestartReceiver 处理） */
        internal const val ACTION_SELF_HEAL_RESTART = "com.rokidlab.rokidlink.SELF_HEAL_RESTART"
        /** 自愈兜底拉起广播 action：自杀后若粘性重启失败，Alarm 在新进程显式拉起服务 */
        internal const val ACTION_SELF_HEAL_BOOTSTRAP = "com.rokidlab.rokidlink.SELF_HEAL_BOOTSTRAP"

        /** 按键按下时间戳 */
        @Volatile
        internal var downTimeMs: Long = 0L

        /** 「按键答题」开关是否开启（供 BridgeActivity 与 Service 共用） */
        @JvmStatic
        fun isKeyQuizEnabled(ctx: Context): Boolean =
            ctx.getSharedPreferences(PREFS_NAME, 0).getBoolean(KEY_QUIZ_ENABLED, false)

        /**
         * 「连续对话（多轮免唤醒）」开关是否开启，默认 **true**。
         *
         * 开启时：每轮 Lab 回复的本地 TTS 播完后，[KeyButtonService] 自动重开一次官方
         * ai_assist 会话（等价于用户再喊一次唤醒词），用户可以直接接着说下一句。
         * 用户可在手机端「乐奇聊天 → 设置 → 连续对话」关闭。
         */
        @JvmStatic
        fun isContinueDialogEnabled(ctx: Context): Boolean =
            ctx.getSharedPreferences(PREFS_NAME, 0).getBoolean(KEY_CONTINUE_DIALOG, true)

        /** 启动此服务 */
        fun start(ctx: Context) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                ctx.startForegroundService(Intent(ctx, KeyButtonService::class.java))
            } else {
                ctx.startService(Intent(ctx, KeyButtonService::class.java))
            }
        }
    }
}
