package com.rokidlab.phone.ai

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.util.Log
import com.rokidlab.phone.util.HttpClient
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * 本地大模型管理：通过 Termux 内运行的 Ollama，为眼镜对话提供完全离线的本机推理。
 *
 * 交互链路：
 *   Lab(本 App) ──RUN_COMMAND──▶ Termux(bash) ──启动──▶ ollama serve (127.0.0.1:11434)
 *   Lab ◀───────────── HTTP /api/ ───────────── ollama
 *
 * 使用前提（用户在 Termux 内一次性准备，页面内提供引导）：
 *   1. 安装 Termux（F-Droid / GitHub 版，勿用 Play 版）
 *   2. pkg install ollama
 *   3. Termux 设置中开启「允许外部应用执行命令」
 *
 * 说明：Android 端 Ollama 的 Vulkan GPU 后端在部分机型（Adreno 等）驱动不兼容，
 * 推理进程会在创建 compute pipeline 时崩溃并反复自动重启，故统一强制纯 CPU
 * 推理（export OLLAMA_VULKAN=0），不再提供 GPU 模式入口。
 *
 * 注意：本类方法均为阻塞式，须在 IO 线程调用（页面层用 withContext(Dispatchers.IO)）。
 */
object LocalOllamaManager {
    private const val TAG = "LocalOllama"

    /** Termux 包名 */
    const val TERMUX_PKG = "com.termux"

    /** Termux RUN_COMMAND 权限（需用户在系统设置中授予本 App，见「附加权限」） */
    const val RUN_CMD_PERMISSION = "com.termux.permission.RUN_COMMAND"

    /** Ollama 服务默认地址（手机本机回环，跨 App 可达） */
    const val OLLAMA_BASE = "http://127.0.0.1:11434"

    /** 接入眼镜对话时使用的 OpenAI 兼容端点（baseUrl 填写规则与设置页一致） */
    const val CHAT_BASE = "$OLLAMA_BASE/v1"

    private const val TERMUX_PREFIX = "/data/data/com.termux/files/usr"

    // ═══════════════════ Termux 检测 ═══════════════════

    /** Termux 是否已安装 */
    fun termuxInstalled(ctx: Context): Boolean =
        runCatching { ctx.packageManager.getPackageInfo(TERMUX_PKG, 0) }.isSuccess

    /** 本 App 是否已被授予 RUN_COMMAND 权限（决定能否驱动 Termux 执行命令） */
    fun runCommandGranted(ctx: Context): Boolean =
        ctx.checkSelfPermission(RUN_CMD_PERMISSION) == PackageManager.PERMISSION_GRANTED

    // ═══════════════════ 服务状态（HTTP）═══════════════════

    /** 获取 ollama 服务版本；服务未启动返回 null（2s 快速探测） */
    fun serverVersion(): String? = runCatching {
        val body = HttpClient.getString(
            "$OLLAMA_BASE/api/version",
            connectTimeout = 2000,
            readTimeout = 3000,
        )
        JSONObject(body).optString("version").ifBlank { "?" }
    }.onFailure { Log.d(TAG, "serverVersion: not reachable: ${it.message}") }
        .getOrNull()

    fun isServerUp(): Boolean = serverVersion() != null

    // ═══════════════════ Termux 命令执行 ═══════════════════

    /**
     * 在 Termux 后台执行一段 shell（通过 RUN_COMMAND）。
     * 执行结果不会回传（RUN_COMMAND 为单向），成败以轮询 HTTP 判断。
     *
     * @throws SecurityException 或启动异常 —— 通常表示未开启「允许外部应用」；
     *         调用方据此引导用户到 Termux 设置打开开关。
     */
    fun runInTermux(ctx: Context, command: String) {
        val intent = Intent("com.termux.RUN_COMMAND").apply {
            setPackage(TERMUX_PKG)
            putExtra("com.termux.RUN_COMMAND_PATH", "$TERMUX_PREFIX/bin/bash")
            putExtra("com.termux.RUN_COMMAND_ARGUMENTS", arrayOf("-c", command))
            putExtra("com.termux.RUN_COMMAND_WORKDIR", "$TERMUX_PREFIX/home")
            putExtra("com.termux.RUN_COMMAND_BACKGROUND", true)
        }
        // 新版 Termux 由 RunCommandService 处理该 Action（旧版才是 Activity），
        // 需用 startForegroundService 启动；调用方已声明 RUN_COMMAND 权限。
        if (ctx.packageManager.resolveService(intent, 0) != null) {
            ctx.startForegroundService(intent)
        } else {
            // 兼容旧版 Termux（Activity 方式）
            val legacy = Intent(intent).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
            if (ctx.packageManager.resolveActivity(legacy, 0) == null) {
                throw IOException("Termux RUN_COMMAND not resolvable")
            }
            ctx.startActivity(legacy)
        }
    }

    /** 组装启动 ollama 服务的 shell（强制纯 CPU + 绑定地址，避免受用户 profile 影响）。
     *  serve 输出重定向到 $HOME/.rokid-ollama.log —— RUN_COMMAND 不回传 stdout/stderr，
     *  启动失败时可 cat 该文件定位原因（ollama 绑定端口失败/库缺失等都会写在这里）。 */
    private fun serveCommand(): String = buildString {
        // Vulkan 后端驱动不兼容易崩溃自重启，固定关闭；termux 默认 CPU 构建会忽略该变量
        append("export OLLAMA_VULKAN=0; ")
        append("export OLLAMA_HOST=127.0.0.1:11434; ")
        append("command -v ollama >/dev/null 2>&1 || { echo 'ollama not installed'; exit 1; }; ")
        append("printf '\\n=== serve start %s ===\\n' \"\$(date '+%F %T')\" >> \"\$HOME/.rokid-ollama.log\"; ")
        append("exec ollama serve >> \"\$HOME/.rokid-ollama.log\" 2>&1")
    }

    // ═══════════════════ 启动 / 停止 ═══════════════════

    sealed class StartResult {
        /** 已在运行（无需启动） */
        object AlreadyRunning : StartResult()

        /** 启动成功（已探测到 HTTP 响应） */
        object Started : StartResult()

        /** Termux 未安装 */
        object TermuxMissing : StartResult()

        /** 启动被拒绝（RUN_COMMAND 抛错，大概率未开「允许外部应用」） */
        object LaunchDenied : StartResult()

        /** 超时仍未就绪（可能未安装 ollama / 启动慢 / 被拒绝但未抛错） */
        object Timeout : StartResult()
    }

    /**
     * 启动 Ollama 服务（自动跳过已在运行的情况）。
     *
     * 自愈流程：先直接拉起并等待 [FIRST_WAIT_MS]；仍未就绪说明可能被残留实例占用端口
     * 或首次拉起即退出，清理残留 ollama 进程后重试一次；总预算 [START_TIMEOUT_MS]。
     * @Synchronized 防止页面连点 / 多入口并发触发多条 serve 命令互相干扰。
     */
    @Synchronized
    fun startServer(ctx: Context): StartResult {
        if (isServerUp()) return StartResult.AlreadyRunning
        if (!termuxInstalled(ctx)) return StartResult.TermuxMissing
        val startedAt = System.currentTimeMillis()
        // 第一段：直接拉起，等待 FIRST_WAIT_MS
        if (!launch(ctx)) return StartResult.LaunchDenied
        if (waitUp(startedAt + FIRST_WAIT_MS)) return StartResult.Started
        // 第二段：仍未就绪 —— 大概率端口被半死实例占用 / 上次拉起即退出；清残留后重启一次
        Log.w(TAG, "startServer: not up in ${FIRST_WAIT_MS / 1000}s, cleaning stale ollama and retrying")
        runCatching { runInTermux(ctx, "pkill -f ollama 2>/dev/null; sleep 2; true") }
            .onFailure { Log.e(TAG, "startServer: cleanup cmd failed: ${it.message}") }
        if (!launch(ctx)) return StartResult.LaunchDenied
        if (waitUp(startedAt + START_TIMEOUT_MS)) return StartResult.Started
        Log.w(TAG, "startServer: timeout waiting for ollama (check ~/.rokid-ollama.log in Termux)")
        return StartResult.Timeout
    }

    /** 向 Termux 发送一条 serve 命令；RUN_COMMAND 抛错（大概率未开「允许外部应用」）返回 false */
    private fun launch(ctx: Context): Boolean = try {
        runInTermux(ctx, serveCommand())
        true
    } catch (e: Exception) {
        Log.e(TAG, "startServer: RUN_COMMAND denied: ${e.message}")
        false
    }

    /** 轮询 HTTP 直到 [deadline]；服务就绪返回 true */
    private fun waitUp(deadline: Long): Boolean {
        while (System.currentTimeMillis() < deadline) {
            if (isServerUp()) return true
            Thread.sleep(1500)
        }
        return false
    }

    /** 停止 Ollama 服务与正在运行的模型进程；等待服务端口关闭（最多 8s） */
    @Synchronized
    fun stopServer(ctx: Context) {
        runCatching {
            runInTermux(ctx, "pkill -f ollama 2>/dev/null; true")
        }.onFailure { Log.e(TAG, "stopServer: ${it.message}") }
        val deadline = System.currentTimeMillis() + 8000
        while (System.currentTimeMillis() < deadline) {
            if (!isServerUp()) return
            Thread.sleep(1000)
        }
    }

    // ═══════════════════ 模型管理（HTTP）═══════════════════

    /** 已安装模型条目（来自 GET /api/tags） */
    data class OllamaModel(
        val name: String,
        val size: Long,
        val family: String,
        val parameterSize: String,
        val quantization: String,
        /** 模型能力（如 ["completion","tools","thinking"]；无该字段时为空） */
        val capabilities: List<String> = emptyList(),
    ) {
        /** 是否支持深度思考（reasoning） */
        val supportsThinking: Boolean get() = capabilities.contains("thinking")
    }

    /** 思考能力缓存：listModels() 时写入；供对话层免查询快速判断模型能否传 think 参数 */
    private val thinkingCache = mutableMapOf<String, Boolean>()

    /**
     * 模型名是否支持深度思考（think 参数是否可下发）。
     * 优先用 /api/tags 返回的 capabilities；缓存未命中（如尚未列过模型）时按命名兜底。
     */
    fun supportsThinking(name: String): Boolean {
        thinkingCache[name]?.let { return it }
        // 兜底：qwen3 / qwq 系列为 ollama 常见思考型家族
        val guess = name.contains("qwen3") || name.contains("qwq")
        thinkingCache[name] = guess
        return guess
    }

    /** 获取已安装模型列表；服务未启动抛异常 */
    fun listModels(): List<OllamaModel> {
        val body = HttpClient.getString("$OLLAMA_BASE/api/tags", readTimeout = 8000)
        val arr = JSONObject(body).optJSONArray("models") ?: return emptyList()
        val out = mutableListOf<OllamaModel>()
        for (i in 0 until arr.length()) {
            val m = arr.optJSONObject(i) ?: continue
            val details = m.optJSONObject("details")
            val caps = m.optJSONArray("capabilities")
            val capList = if (caps == null) emptyList()
            else (0 until caps.length()).map { caps.optString(it) }.filter { it.isNotBlank() }
            val model = OllamaModel(
                name = m.optString("name"),
                size = m.optLong("size"),
                family = details?.optString("family").orEmpty(),
                parameterSize = details?.optString("parameter_size").orEmpty(),
                quantization = details?.optString("quantization_level").orEmpty(),
                capabilities = capList,
            )
            thinkingCache[model.name] = model.supportsThinking
            out.add(model)
        }
        return out.sortedBy { it.name }
    }

    /**
     * 拉取模型（POST /api/pull，stream=true）。
     * 通过 [onProgress] 回调下载进度：percent < 0 表示仅状态文案（如 pulling manifest）。
     * 成功时回调 status="success"、percent=100 后返回；失败抛异常。
     * [isCancelled] 返回 true 时立即中止（真实取消：断开连接停止下载，不等整个流跑完）。
     * [onTotal] 在得知下载总字节数时回调一次（供调用方做磁盘空间预检）。
     */
    fun pullModel(
        name: String,
        onProgress: (status: String, percent: Int) -> Unit,
        isCancelled: () -> Boolean = { false },
        onTotal: (Long) -> Unit = {},
    ) {
        val conn = (URL("$OLLAMA_BASE/api/pull").openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            useCaches = false
            connectTimeout = 10000
            // 模型下载可能很慢（数百 MB~数 GB），行间有定期进度，给足时间
            readTimeout = 300000
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
        }
        try {
            val body = JSONObject().put("name", name).put("stream", true).toString()
            conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            if (code !in 200..299) {
                val err = conn.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty()
                throw IOException("HTTP $code: ${err.take(200)}")
            }
            val reader = conn.inputStream.bufferedReader()
            var totalReported = false
            while (true) {
                if (isCancelled()) {
                    // 主动断开连接：Ollama 服务端会中止拉取，部分分片由服务端处理
                    throw kotlinx.coroutines.CancellationException("pull cancelled")
                }
                val line = reader.readLine() ?: break
                val json = runCatching { JSONObject(line) }.getOrNull() ?: continue
                val status = json.optString("status")
                val completed = json.optLong("completed")
                val total = json.optLong("total")
                val percent = if (total > 0) ((completed * 100) / total).toInt() else -1
                if (total > 0 && !totalReported) {
                    totalReported = true
                    onTotal(total)
                }
                if (status == "success") {
                    onProgress(status, 100)
                    return
                }
                onProgress(status, percent)
            }
            // 流被服务端提前关闭且无 success：按失败处理
            throw IOException("pull stream closed without success")
        } finally {
            conn.disconnect()
        }
    }

    /** 删除本地模型（DELETE /api/delete），释放磁盘空间 */
    fun deleteModel(name: String) {
        val conn = (URL("$OLLAMA_BASE/api/delete").openConnection() as HttpURLConnection).apply {
            requestMethod = "DELETE"
            doOutput = true
            useCaches = false
            connectTimeout = 6000
            readTimeout = 15000
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
        }
        try {
            val body = JSONObject().put("name", name).toString()
            conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            if (code !in 200..299) {
                val err = conn.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty()
                throw IOException("HTTP $code: ${err.take(200)}")
            }
        } finally {
            conn.disconnect()
        }
    }

    // ═══════════════════ 模型驻留管理（切换模型时清理）════════════════

    /** 当前已加载（驻留）的模型名列表（GET /api/ps）；服务不可达返回空 */
    fun loadedModels(): List<String> = runCatching {
        val body = HttpClient.getString(
            "$OLLAMA_BASE/api/ps",
            connectTimeout = 3000,
            readTimeout = 5000,
        )
        val arr = JSONObject(body).optJSONArray("models") ?: return emptyList()
        (0 until arr.length()).mapNotNull { arr.optJSONObject(it)?.optString("name").orEmpty().ifBlank { null } }
    }.onFailure { Log.d(TAG, "loadedModels: ${it.message}") }
        .getOrDefault(emptyList())

    /**
     * 立即卸载指定模型（keep_alive=0）。
     * 模型未加载 / 服务未启动时无副作用；同一 ollama 内不同模型各自占一个
     * llama-server 进程，切换后不卸载会长期双进程抢内存与 CPU。
     */
    fun unloadModel(name: String) {
        val conn = (URL("$OLLAMA_BASE/api/generate").openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            useCaches = false
            connectTimeout = 5000
            readTimeout = 10000
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
        }
        try {
            val body = JSONObject().put("model", name).put("keep_alive", 0).toString()
            conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            if (code !in 200..299) {
                val err = conn.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty()
                Log.w(TAG, "unloadModel($name) HTTP $code: ${err.take(200)}")
            }
        } finally {
            conn.disconnect()
        }
    }

    /**
     * 切换对话模型后调用：仅保留 [keep] 目标模型，其余驻留模型立即卸载，
     * 避免 ollama 默认 keep_alive（约 5 分钟）内新旧模型双 llama-server 并存。
     * 阻塞式，须在后台线程调用。
     */
    fun unloadOtherModels(keep: String) {
        val loaded = runCatching { loadedModels() }.getOrElse { return }
        val others = loaded.filter { it.isNotBlank() && it != keep }
        if (others.isEmpty()) return
        Log.i(TAG, "unloadOtherModels: keep=$keep, unloading=${others.joinToString()}")
        others.forEach { model ->
            runCatching { unloadModel(model) }
                .onFailure { Log.w(TAG, "unloadModel($model) failed: ${it.message}") }
        }
    }

    // ═══════════════════ 工具 ═══════════════════

    /** 启动服务最长等待时间 */
    const val START_TIMEOUT_MS = 45_000L

    /** 自愈重试前第一段等待时间（超时后清残留并重启一次） */
    private const val FIRST_WAIT_MS = 15_000L

    /** 字节数转可读大小（如 1.9 GB） */
    fun humanSize(bytes: Long): String {
        if (bytes <= 0) return ""
        val units = arrayOf("B", "KB", "MB", "GB", "TB")
        var v = bytes.toDouble()
        var i = 0
        while (v >= 1024 && i < units.lastIndex) {
            v /= 1024
            i++
        }
        return if (i == 0) "${v.toLong()} ${units[i]}" else String.format(java.util.Locale.US, "%.1f %s", v, units[i])
    }

    /** 打开本 App 的系统应用信息页（引导用户在「权限 → 附加权限」中授予 RUN_COMMAND） */
    fun openAppPermissionSettings(ctx: Context) {
        val intent = Intent(
            android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            android.net.Uri.parse("package:${ctx.packageName}"),
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        ctx.startActivity(intent)
    }

    /** 打开 Termux 设置页（引导开启「允许外部应用」）；失败返回 false */
    fun openTermuxSettings(ctx: Context): Boolean = runCatching {
        val intent = Intent().apply {
            setClassName(TERMUX_PKG, "com.termux.app.TermuxSettings")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        if (ctx.packageManager.resolveActivity(intent, 0) == null) return@runCatching false
        ctx.startActivity(intent)
        true
    }.getOrDefault(false)
}
