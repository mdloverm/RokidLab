package com.rokidlab.rokidlink

import android.content.Intent
import android.net.wifi.WifiConfiguration
import android.net.wifi.WifiManager
import android.util.Log
import com.rokid.cxr.Caps

/**
 * KeyButtonService 的配置处理与杂项指令（v3.9 拆分自 KeyButtonService）。
 *
 * 职责：
 *  1. 手机端下发的各类配置持久化：按键映射 / AI 配置 / 按键答题开关 / 连续对话开关
 *  2. 「打开 RokidLink」状态页显示指令（带防抖）
 *  3. open_app：显式 ComponentName 拉起眼镜端目标页面
 *  4. WiFi 使能/连接（wifi_config 历史兜底，已废弃通道仅保留兼容）
 */
internal class ConfigHandlers(
    private val service: KeyButtonService,
    private val core: KeyServiceCore,
) {
    companion object {
        private const val TAG = KeyButtonService.TAG
    }

    /** 显示状态页指令防抖：手机端指令可能广播式重复到达，500ms 内只响应一次 */
    private var lastShowMainMs = 0L

    /** 接收手机端下发的按键映射配置（AiChannel 版本化编解码） */
    fun handleKeyConfig(args: Caps) {
        try {
            val cfg = AiChannel.decodeKeyConfig(capsToStrings(args)) ?: run {
                Log.w(TAG, "handleConfig: rejected invalid/unsupported payload (size=${args.size()})")
                return
            }
            service.getSharedPreferences(KeyButtonService.PREFS_NAME, 0).edit()
                .putString(KeyButtonService.KEY_SHORT_PKG, cfg.shortPkg)
                .putString(KeyButtonService.KEY_SHORT_ACT, cfg.shortActivity)
                .putString(KeyButtonService.KEY_LONG_PKG, cfg.longPkg)
                .putString(KeyButtonService.KEY_LONG_ACT, cfg.longActivity)
                .apply()

            Log.i(TAG, "Config saved: short=${cfg.shortPkg}/${cfg.shortActivity}, " +
                "long=${cfg.longPkg}/${cfg.longActivity}")
        } catch (e: Exception) {
            Log.e(TAG, "handleConfig error", e)
        }
    }

    /** 接收手机端下发的 AI 配置（baseUrl/apiKey/model/mode）并持久化。
     *  载荷经 AiChannel 版本化编解码：cmd 不符/版本不支持/长度不足时整体丢弃（防错位写入）。 */
    fun handleAiConfig(args: Caps) {
        try {
            core.markDownlink()
            val cfg = AiChannel.decodeAiConfig(capsToStrings(args)) ?: run {
                Log.w(TAG, "handleAiConfig: rejected invalid/unsupported payload (size=${args.size()})")
                return
            }
            val prefs = service.getSharedPreferences(KeyButtonService.PREFS_NAME, 0)
            prefs.edit()
                .putString(KeyButtonService.KEY_AI_BASE_URL, cfg.baseUrl)
                .putString(KeyButtonService.KEY_AI_MODEL, cfg.model)
                .putString(KeyButtonService.KEY_AI_MODE, cfg.mode)
                .apply()
            // API Key 走 Keystore 加密落盘（prefs 里只有密文，防止眼镜端被读取后拿到明文凭据）
            SecretStore.put(prefs, KeyButtonService.KEY_AI_API_KEY, cfg.apiKey)
            Log.i(TAG, "AI config saved: baseUrl=${cfg.baseUrl} model=${cfg.model} " +
                "keyLen=${cfg.apiKey.length} mode=${cfg.mode}")
        } catch (e: Exception) {
            Log.e(TAG, "handleAiConfig error", e)
        }
    }

    /** 接收手机端下发的「按键答题」开关状态并持久化（AiChannel 版本化编解码） */
    fun handleQuizConfig(args: Caps) {
        try {
            core.markDownlink()
            val enabled = AiChannel.decodeQuizConfig(capsToStrings(args)) ?: run {
                Log.w(TAG, "handleQuizConfig: rejected invalid/unsupported payload (size=${args.size()})")
                return
            }
            service.getSharedPreferences(KeyButtonService.PREFS_NAME, 0).edit()
                .putBoolean(KeyButtonService.KEY_QUIZ_ENABLED, enabled)
                .apply()
            Log.i(TAG, "Quiz config saved: enabled=$enabled")
        } catch (e: Exception) {
            Log.e(TAG, "handleQuizConfig error", e)
        }
    }

    /**
     * 保存手机端下发的「连续对话（多轮免唤醒）」开关。
     *
     * 落 prefs 后 [KeyButtonService.isContinueDialogEnabled] 立即生效（无需重连/重启服务，行为类开关即时生效）。
     * 关闭时同时撤销排队中的自动续听 —— 用户可能恰好在播报结束的瞬间把开关关掉。
     */
    fun handleContinueDialogConfig(args: Caps) {
        try {
            val enabled = AiChannel.decodeContinueDialog(capsToStrings(args)) ?: run {
                Log.w(TAG, "handleContinueDialogConfig: rejected invalid/unsupported payload (size=${args.size()})")
                return
            }
            service.getSharedPreferences(KeyButtonService.PREFS_NAME, 0).edit()
                .putBoolean(KeyButtonService.KEY_CONTINUE_DIALOG, enabled)
                .apply()
            if (!enabled) {
                // 开关关闭时撤销排队中的自动续听（由 AiTakeoverCoordinator 执行）
                core.mainHandler.post { continueDialogCanceller?.invoke() }
            }
            Log.i(TAG, "Continue dialog config saved: enabled=$enabled")
        } catch (e: Exception) {
            Log.e(TAG, "handleContinueDialogConfig error", e)
        }
    }

    /** 连续对话撤销回调（KeyButtonService 装配时注入 AiTakeoverCoordinator 的方法） */
    internal var continueDialogCanceller: (() -> Unit)? = null

    /** 手机端「打开 RokidLink」触发：带 EXTRA_SHOW_UI 显示状态页（含 WiFi IP），供用户查看连接信息 */
    fun showMainActivity() {
        // 防抖：手机端 appStart 后发的指令可能广播式重复到达，500ms 内只响应一次
        val now = System.currentTimeMillis()
        if (now - lastShowMainMs < 500L) {
            Log.i(TAG, "showMainActivity: debounced (repeat)")
            return
        }
        lastShowMainMs = now
        try {
            val intent = Intent(service, MainActivity::class.java).apply {
                // 必须用 CLEAR_TOP 而非 SINGLE_TOP：appStart 刚启动的隐形实例（无 EXTRA_SHOW_UI）
                // 在任务栈顶，SINGLE_TOP 会复用该实例，onCreate 不重跑 → showUi 仍为 false，
                // 导致隐形实例获得焦点后 300ms 执行 finish() 把刚显示的 IP 状态页关掉。
                // CLEAR_TOP 清掉旧实例并新建 showUi=true 实例，保证 onCreate 重新读取标志。
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                putExtra(MainActivity.EXTRA_SHOW_UI, true)
            }
            service.startActivity(intent)
            Log.i(TAG, "showMainActivity: showing status page (WiFi IP)")
        } catch (e: Exception) {
            Log.e(TAG, "showMainActivity failed: ${e.message}")
        }
    }

    /**
     * 手机端要求打开某个眼镜端页面（[AiChannel.TOPIC_OPEN_APP]）：直接 startActivity 拉起目标
     * Activity（须 exported=true）。典型用途：说「显示歌词」→ 拉起系统音乐页
     * `com.rokid.os.sprite.launcher/.page.music.MusicPageActivity`（该页会随 AVRCP 元数据逐行显示歌词）。
     *
     * 注意：**不能复用按键启动逻辑（launchTarget）** —— 它优先用 `getLaunchIntentForPackage(pkg)`，
     * 对 launcher 这类包会返回 HOME 意图（拉起桌面而非目标页），必须用显式 ComponentName 直启。
     *
     * 本服务持有 SYSTEM_ALERT_WINDOW（BAL 法定豁免，手机端经 ADB appops 授予），
     * 从后台 startActivity 不受 Android 12+ 限制。
     */
    fun handleOpenApp(args: Caps?) {
        try {
            val decoded = AiChannel.decodeOpenApp(capsToStrings(args))
            if (decoded == null) {
                Log.w(TAG, "handleOpenApp: rejected invalid payload (size=${args?.size()})")
                return
            }
            val (pkg, activity) = decoded
            val fullAct = if (activity.startsWith(".")) "$pkg$activity" else activity
            Log.i(TAG, "Received open_app: $pkg/$fullAct")
            core.mainHandler.post {
                runCatching {
                    val intent = Intent().apply {
                        component = android.content.ComponentName(pkg, fullAct)
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    service.startActivity(intent)
                    Log.i(TAG, "open_app launched: $pkg/$fullAct")
                }.onFailure {
                    Log.e(TAG, "open_app launch failed: ${it::class.simpleName}: ${it.message}")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "handleOpenApp error", e)
        }
    }

    // ──────────────────────────────────────────────
    //  WiFi 使能 / 连接（wifi_config 历史兜底）
    // ──────────────────────────────────────────────

    fun handleWifiConfig(args: Caps) {
        try {
            if (args.size() < 3) {
                Log.w(TAG, "Invalid wifi config size: ${args.size()}")
                return
            }
            val action = args.at(0).getString()
            if (action != "wifi_connect") return

            val ssid = args.at(1).getString()
            val password = args.at(2).getString()
            Log.i(TAG, "Received wifi config: ssid=$ssid, password_length=${password?.length ?: 0}, caps_size=${args.size()}")

            connectToWifi(ssid, password)
        } catch (e: Exception) {
            Log.e(TAG, "handleWifiConfig error", e)
        }
    }

    private fun connectToWifi(ssid: String?, password: String?) {
        try {
            val wifiManager = service.getSystemService(android.content.Context.WIFI_SERVICE) as? WifiManager ?: run {
                Log.e(TAG, "WifiManager not available")
                return
            }

            val currentSsid = wifiManager.connectionInfo?.ssid?.trim('"')
            if (currentSsid == ssid) {
                Log.i(TAG, "Already connected to $ssid, skipping")
                return
            }

            connectToWifiLegacy(wifiManager, ssid, password)
        } catch (e: Exception) {
            Log.e(TAG, "connectToWifi error", e)
        }
    }

    @Suppress("DEPRECATION")
    private fun connectToWifiLegacy(wifiManager: WifiManager, ssid: String?, password: String?) {
        if (!wifiManager.isWifiEnabled) {
            Log.i(TAG, "WiFi is disabled, enabling...")
            val enabled = wifiManager.setWifiEnabled(true)
            Log.i(TAG, "setWifiEnabled(true) -> $enabled")
            if (!enabled) {
                Log.e(TAG, "Failed to enable WiFi")
                return
            }
            var waitCount = 0
            while (!wifiManager.isWifiEnabled && waitCount < 30) {
                Thread.sleep(100)
                waitCount++
            }
            if (!wifiManager.isWifiEnabled) {
                Log.e(TAG, "WiFi enable timeout")
                return
            }
            Log.i(TAG, "WiFi enabled successfully")
        }

        val config = WifiConfiguration().apply {
            SSID = "\"$ssid\""
            preSharedKey = "\"$password\""
            status = WifiConfiguration.Status.ENABLED
            allowedKeyManagement.set(WifiConfiguration.KeyMgmt.WPA_PSK)
        }

        val netId = wifiManager.addNetwork(config)
        if (netId == -1) {
            Log.e(TAG, "Failed to add wifi network $ssid")
            return
        }

        wifiManager.disconnect()
        Thread.sleep(500)

        val enabled = wifiManager.enableNetwork(netId, true)
        Log.i(TAG, "enableNetwork($netId) -> $enabled")
        wifiManager.reconnect()

        Log.i(TAG, "WiFi config applied (legacy): ssid=$ssid")
    }
}
