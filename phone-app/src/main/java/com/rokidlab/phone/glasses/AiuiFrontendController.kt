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
            caps.write("Ai_RenderPayload")
            caps.write(payload.toString())
            val result = synchronized(cmdLock) {
                link.sendCustomCmd("Ai", caps)
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
            val result = synchronized(cmdLock) { rawSendCmd(link, "Sys", caps) }
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
            val result = synchronized(cmdLock) { rawSendCmd(link, "Sys", caps) }
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
     * @return null=失败；"OK"=已落盘（openAfter=true 会自动拉起宿主渲染）
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

        if (client != null) {
            try {
                // Android 12+ 后台启动限制(BAL)：需先确保 RokidLink 拥有 SYSTEM_ALERT_WINDOW
                // 授权，否则纯后台服务拉起 AiuiLinkActivity 会被系统静默拒绝。
                runCatching {
                    val r = client.executeShellCommand(
                        "appops set com.rokidlab.rokidlink android:system_alert_window allow 2>&1",
                        10_000,
                    )
                    if (r.isNotBlank() && !r.contains("Unknown", ignoreCase = true)) {
                        Log.i(TAG, "grant system_alert_window on glasses: $r")
                    }
                }.onFailure { Log.w(TAG, "grant system_alert_window failed: ${it.message}") }
                // 通道 1（主）：run-as 分块 base64 落盘（覆盖 AIUI 包常见大小，块 60K base64≈45KB）。
                // 注意：整个 shell 逻辑必须包进 run-as 的 sh -c —— run-as 只作用于其后第一个
                // 可执行程序；`run-as pkg A && rm …`/`wc -c < file` 的 rm/重定向若放在外层，
                // 会由 adbd 的 shell 用户执行，无权操作 app 私有目录（实测 rm 静默失败导致
                // 文件残留叠加、wc 输出为空）。
                val pkgDir = "com.rokidlab.rokidlink"
                client.executeShellCommand(
                    "run-as $pkgDir sh -c 'mkdir -p files/aiui_host && rm -f files/aiui_host/$name'",
                    10_000,
                )
                val b64 = android.util.Base64.encodeToString(body, android.util.Base64.NO_WRAP)
                var off = 0
                var wroteOk = true
                // 命令本身无 stdout（echo|base64 -d 静默写盘），不能以输出判成败，
                // 统一靠最后 wc -c 校验字节数兜底。
                val step = 60000
                while (off < b64.length) {
                    val end = minOf(off + step, b64.length)
                    val chunk = b64.substring(off, end)
                    off = end
                    try {
                        client.executeShellCommand(
                            "run-as $pkgDir sh -c 'echo $chunk | base64 -d >> files/aiui_host/$name'",
                            30_000,
                        )
                    } catch (e: Exception) {
                        wroteOk = false
                        Log.w(TAG, "pushAixToRokidLinkHost($name): chunk write failed at $off: ${e.message}")
                        break
                    }
                }
                if (wroteOk) {
                    val wc = client.executeShellCommand(
                        "run-as $pkgDir sh -c 'wc -c < files/aiui_host/$name'",
                        10_000,
                    )
                    val written = wc?.trim()?.toLongOrNull()
                    Log.i(TAG, "pushAixToRokidLinkHost($name) written=$written expect=${body.size}")
                    if (written == body.size.toLong()) {
                        if (openAfter) openAiuiHost(name, launchParams)
                        return "OK"
                    }
                }
                Log.w(TAG, "pushAixToRokidLinkHost($name): run-as write failed, fallback socket push...")
            } catch (e: Exception) {
                Log.e(TAG, "pushAixToRokidLinkHost run-as failed: ${e.message}")
            }
        }
        // 通道 2（兜底）：7658 socket —— 蓝牙隧道直连 AiuiPackageServer 或 adb smart socket。
        // 前者要求 adb 未占用唯一 RFCOMM，后者要求 adbd 放行 tcp 转发，多数环境不可用，
        // 仅保底（如 Wi-Fi 直连 adb 且隧道空闲）。
        val nameB = name.toByteArray(Charsets.UTF_8)
        val frame = java.io.ByteArrayOutputStream(body.size + nameB.size + 6).apply {
            write((nameB.size shr 8) and 0xFF); write(nameB.size and 0xFF)
            write(nameB)
            write((body.size ushr 24) and 0xFF); write((body.size ushr 16) and 0xFF)
            write((body.size ushr 8) and 0xFF); write(body.size and 0xFF)
            write(body)
        }.toByteArray()
        var ack: String? = null
        routeManager.tunnelTo(7658)?.let { localPort ->
            Log.i(TAG, "pushAixToRokidLinkHost: BT tunnel 127.0.0.1:$localPort → :7658")
            ack = try {
                java.net.Socket().apply {
                    connect(java.net.InetSocketAddress("127.0.0.1", localPort), 5_000)
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
                Log.e(TAG, "pushAixToRokidLinkHost: BT push failed: ${e.message}")
                null
            }
        }
        if (ack == null) {
            try {
                client?.let { ack = it.sendTcpStream(7658, frame) }
            } catch (e: Exception) {
                Log.e(TAG, "pushAixToRokidLinkHost: adb push failed: ${e.message}")
            }
        }
        Log.i(TAG, "pushAixToRokidLinkHost($name) fallback ack=${ack?.take(16)}")
        if (ack?.trim() == "OK" && openAfter) {
            openAiuiHost(name, launchParams)
        }
        return ack
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
                link.sendCustomCmd("Jsai", caps)
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
                link.sendCustomCmd("Jsai", caps)
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
                link.sendCustomCmd("Jsai", caps)
            }
            Log.i(TAG, "notifyGlassGetAgentList() -> $result")
            result ?: -3
        } catch (e: Exception) {
            Log.e(TAG, "notifyGlassGetAgentList failed", e)
            -1
        }
    }
}
