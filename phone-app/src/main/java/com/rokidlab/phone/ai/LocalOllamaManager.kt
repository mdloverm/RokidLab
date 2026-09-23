package com.rokidlab.phone.ai

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.util.Log
import com.rokidlab.phone.util.HttpClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
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

    /**
     * 在线探测超时（1s 连接 / 2s 读）。
     *
     * 为什么敢这么激进：11434 是**本机回环**服务，健康时应答在亚毫秒级；连接 1s 都没结果，
     * 只可能是「进程僵死」或「被 ROM 冻结」，而不是「忙」。
     *
     * 历史教训（2026-09-10 实测）：这里曾为「避免把被冻结实例误判成离线」放大到 5s/8s。
     * 方向是错的——Termux 被 HyperOS 冻结时 TCP 握手会**挂住**而不是被拒绝，于是每次探测
     * 都吃满超时；叠加 startServer 在启动前的多轮确认，冷启动被拖到 ~50s。
     * 正确做法是「探测要快 + 恢复动作要无害」：一次探测失败不做任何破坏性操作，
     * 先敲一次 Termux 解冻再判断（见 [wakeTermux]）。
     */
    private const val PROBE_CONNECT_TIMEOUT_MS = 1000

    private const val PROBE_READ_TIMEOUT_MS = 2000

    /** 获取 ollama 服务版本；服务未启动返回 null */
    fun serverVersion(): String? = runCatching {
        val body = HttpClient.getString(
            "$OLLAMA_BASE/api/version",
            connectTimeout = PROBE_CONNECT_TIMEOUT_MS,
            readTimeout = PROBE_READ_TIMEOUT_MS,
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
        // Phase 2：RUN_COMMAND 广播/服务调用收口到 L0 ShellOps（含新版服务 / 旧版 Activity 双路径）
        com.rokidlab.phone.platform.ShellOps.runTermuxCommand(ctx, command)
    }

    /**
     * 清理 ollama 相关进程（僵死实例 / 残留 runner）的 shell 片段。
     *
     * **必须用 `pkill -x`（精确匹配进程名），不能用 `pkill -f ollama`**：
     * 这段脚本本身是被 `bash -c "<脚本>"` 执行的，命令行里就带着 "ollama" 字样，
     * `pkill -f ollama` 会把承载它的 shell 一起杀掉 → 后面的命令（包括 `exec ollama serve`）
     * 永远不会执行。实测本机 `/proc/<pid>/comm`：服务主进程为 `ollama`，模型推理进程为
     * `llama-server`，**两者都要清**——只杀前者会留下仍占着模型的孤儿 runner。
     */
    private fun killStaleCommand(): String =
        "if command -v pkill >/dev/null 2>&1; then " +
            "pkill -x ollama 2>/dev/null; pkill -x llama-server 2>/dev/null; " +
            "fi; "

    /**
     * 向 Termux 发一条 no-op 命令，"敲醒"被 ROM 冻结的 Termux 进程组。
     *
     * 为什么能治本：RUN_COMMAND 是一次跨进程服务调用，系统投递前必须先让目标进程可运行
     * （也就是解冻）。而 ollama 是 Termux `fork/exec` 出来的子进程、与它共用同一个 cgroup，
     * 所以 Termux 一解冻，**冻结但仍在监听的 ollama 会立刻恢复 accept 并应答 HTTP**。
     * → 这种"被冻住但没死"的情况完全不必重启服务，也就省掉了模型重载。
     *
     * 同时顺手补一次 `termux-wake-lock`（Termux:API 未装时静默跳过）。
     *
     * @return false 表示 RUN_COMMAND 被拒绝（未开「允许外部应用」/ 权限被撤）
     */
    fun wakeTermux(ctx: Context): Boolean = try {
        runInTermux(ctx, "command -v termux-wake-lock >/dev/null 2>&1 && termux-wake-lock; true")
        true
    } catch (e: Exception) {
        Log.e(TAG, "wakeTermux: RUN_COMMAND denied: ${e.message}")
        false
    }

    /**
     * 11434 端口是否仍被进程占着（TCP 能建连）。
     *
     * 用来区分两种"探不到服务"的形态，从而决定要不要清残留 —— 这是"不误杀"的关键闸门：
     *  - 端口空闲 → 服务压根没在跑，直接 `ollama serve` 就行，**不做任何杀进程动作**；
     *  - 端口被占 → 有僵死/被冻的实例卡在端口上，不清掉的话新 serve 会 bind 失败秒退。
     *
     * 与 HTTP 探测的区别：这里只问"有没有人在 LISTEN"，不问"答不答"，所以只需很短的超时。
     */
    private fun portHeld(): Boolean = runCatching {
        java.net.Socket().use { s ->
            s.connect(java.net.InetSocketAddress("127.0.0.1", 11434), PORT_PROBE_TIMEOUT_MS)
            true
        }
    }.onFailure { Log.d(TAG, "portHeld: ${it.message}") }.getOrDefault(false)

    /** 组装启动 ollama 服务的 shell（强制纯 CPU + 绑定地址，避免受用户 profile 影响）。
     *  serve 输出重定向到 $HOME/.rokid-ollama.log —— RUN_COMMAND 不回传 stdout/stderr，
     *  启动失败时可 cat 该文件定位原因（ollama 绑定端口失败/库缺失等都会写在这里）。
     *
     *  启动前先持 termux-wake-lock：Termux 不持锁时熄屏 / Doze 会让 CPU 挂起，
     *  ollama 无法及时应答 HTTP 探测，会被误判为离线。
     *
     *  [killFirst] = 端口已确认被占（僵死实例）时为 true：先清残留再 serve，
     *  否则 bind 冲突会让 serve 秒退（旧版表现为"启动总要试两次"）。 */
    private fun serveCommand(killFirst: Boolean): String = buildString {
        // 长驻唤醒锁（Termux:API 提供；未安装时静默跳过，不影响启动）
        append("command -v termux-wake-lock >/dev/null 2>&1 && termux-wake-lock; ")
        // Vulkan 后端驱动不兼容易崩溃自重启，固定关闭；termux 默认 CPU 构建会忽略该变量
        append("export OLLAMA_VULKAN=0; ")
        append("export OLLAMA_HOST=127.0.0.1:11434; ")
        // ollama 走**绝对路径**，不靠 PATH 解析：RUN_COMMAND 起的进程继承的是 Android 应用的
        // 环境（PATH 里没有 $PREFIX/bin，而 ollama 只装在那里）。裸命令名时 `command -v ollama`
        // 直接返回空，于是这条链"静默判自己没装 ollama"、exit 1，且输出无处可去。
        // $P 由 ShellOps 的环境前缀注入；这里再留一个裸名兜底，保证脱离该前缀也能跑。
        append("O=\"\$P/bin/ollama\"; [ -x \"\$O\" ] || O=ollama; ")
        append("command -v \"\$O\" >/dev/null 2>&1 || { echo 'ollama not installed'; exit 1; }; ")
        if (killFirst) {
            append(killStaleCommand())
            append("sleep 1; ")
        }
        append("printf '\\n=== serve start %s ===\\n' \"\$(date '+%F %T')\" >> \"\$HOME/.rokid-ollama.log\"; ")
        append("exec \"\$O\" serve >> \"\$HOME/.rokid-ollama.log\" 2>&1")
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
     * 启动 Ollama 服务（已在运行则直接返回）。
     *
     * 三段式，任何一段成功即返回 —— 顺序刻意"先轻后重"：
     *  1. **先敲醒**（[wakeTermux]）：Termux 被 ROM 冻结是最常见的形态，解冻即恢复，
     *     不重启服务、不重载模型（健康时全程 1s 内结束）；
     *  2. 仍未应答 → 下发 serve（[serveCommand] 内部先清残留再启动），等 [SERVE_WAIT_MS]；
     *  3. 兜底：显式清理后再拉起一次。
     *
     * 旧版在启动前要先做 3 轮「确认离线」：Termux 被冻结时每次探测都吃满 5s 超时，
     * 3 轮就要 17s —— 而这 17s 换来的信息对"要不要启动"毫无帮助，冷启动因此被拖到 ~50s。
     * 它真正想防的（误杀仍在服务的实例）已由「先解冻、给它一次自证机会」彻底解决：
     * 如果实例还活着，第 1 段就把我们送回家了，根本走不到清理分支。
     *
     * @Synchronized 防止页面连点 / 保活服务与页面并发触发多条 serve 互相干扰。
     */
    @Synchronized
    fun startServer(ctx: Context): StartResult {
        if (isServerUp()) return StartResult.AlreadyRunning
        if (!termuxInstalled(ctx)) return StartResult.TermuxMissing

        // ① 敲醒 Termux（RUN_COMMAND 会强制解冻目标进程组），给被冻实例一次自证机会
        if (!wakeTermux(ctx)) return StartResult.LaunchDenied
        repeat(UNFREEZE_PROBE_ATTEMPTS) {
            Thread.sleep(WAKE_SETTLE_MS)
            if (isServerUp()) {
                Log.i(TAG, "startServer: recovered by unfreezing Termux (no restart needed)")
                return StartResult.Started
            }
        }

        // ② 确认不应答。先看端口是否仍被占着，据此决定要不要先清残留 ——
        //    端口空闲就绝不杀进程（可能是被冻但还活着的实例，见 ① 已给过自证机会）；
        //    端口被占说明有僵死实例卡着，必须先清否则新 serve 会 bind 失败秒退。
        val held = portHeld()
        Log.i(TAG, "startServer: not answering, portHeld=$held")
        if (!launch(ctx, killFirst = held)) return StartResult.LaunchDenied
        if (waitUp(System.currentTimeMillis() + SERVE_WAIT_MS)) return StartResult.Started

        // ③ 兜底：显式清理 + 再拉起一次（清理已经做过，这次不必重复杀）
        Log.w(TAG, "startServer: still down, cleaning stale ollama and retrying")
        runCatching { runInTermux(ctx, killStaleCommand() + "sleep 2; true") }
            .onFailure { Log.e(TAG, "startServer: cleanup cmd failed: ${it.message}") }
        if (!launch(ctx, killFirst = false)) return StartResult.LaunchDenied
        if (waitUp(System.currentTimeMillis() + SERVE_WAIT_MS)) return StartResult.Started
        Log.w(TAG, "startServer: timeout waiting for ollama (check ~/.rokid-ollama.log in Termux)")
        return StartResult.Timeout
    }

    /** 向 Termux 发送一条 serve 命令；RUN_COMMAND 抛错（大概率未开「允许外部应用」）返回 false */
    private fun launch(ctx: Context, killFirst: Boolean): Boolean = try {
        runInTermux(ctx, serveCommand(killFirst))
        true
    } catch (e: Exception) {
        Log.e(TAG, "startServer: RUN_COMMAND denied: ${e.message}")
        false
    }

    /** 轮询 HTTP 直到 [deadline]；服务就绪返回 true */
    private fun waitUp(deadline: Long): Boolean {
        while (System.currentTimeMillis() < deadline) {
            if (isServerUp()) return true
            Thread.sleep(POLL_INTERVAL_MS)
        }
        return false
    }

    /** 停止 Ollama 服务与正在运行的模型进程；等待服务端口关闭（最多 8s） */
    @Synchronized
    fun stopServer(ctx: Context) {
        runCatching {
            // 一并释放启动时持有的唤醒锁，避免服务已停仍占着 CPU 不让休眠。
            // 注意用的是 killStaleCommand()（pkill -x）：旧的 `pkill -f ollama` 会把承载本脚本的
            // shell 一起杀掉，导致后面的 termux-wake-unlock 永远执行不到。
            runInTermux(
                ctx,
                killStaleCommand() +
                    "command -v termux-wake-unlock >/dev/null 2>&1 && termux-wake-unlock; true",
            )
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

    /**
     * 模型**自报**能力（本地 Ollama 的权威答案，见 `llm` 接缝的 [com.rokidlab.phone.ai.llm.ModelCapabilities]）。
     *
     * 为什么本地模型不需要"探测"就有准确结论：Ollama 从 0.5 起在 `/api/tags`、`/api/show`
     * 里直接返回 `capabilities`（`tools` / `vision` / `thinking`），并且 `/api/show` 的
     * `model_info.<arch>.context_length` 给出**真实上下文窗口**。
     * 这两样对我们都是免费且准确的 —— 比发一次真实推理请求去试探（本地可能是几 GB 模型加载）
     * 便宜得多，所以本地端点优先信自报、不必探测。
     */
    data class OllamaCapabilities(
        /** 原始能力表；**为空 = 服务端没给**（旧版 Ollama），不是"没有能力" */
        val capabilities: List<String>,
        /** 真实上下文窗口（token）；0 = 没读到（旧版没有 model_info） */
        val contextLength: Int = 0,
    ) {
        val supportsTools: Boolean get() = capabilities.contains("tools")
        val supportsVision: Boolean get() = capabilities.contains("vision")
    }

    /** 能力缓存：`listModels()` / [refreshCapabilities] 时写入；供上层免网络、可在主线程读取 */
    private val capsCache = mutableMapOf<String, OllamaCapabilities>()

    /**
     * 读**已缓存**的自报能力（**绝不发网络请求**，因此可在主线程调用）。
     *
     * 缓存为空说明本次进程还没列过模型 / 没显式刷新过 —— 调用方应回落到内置模型表，
     * 而不是在这里等一个 HTTP 往返：本方法会被设置页的 Compose 重组链路上调用。
     */
    fun cachedCapabilitiesOf(model: String): OllamaCapabilities? = capsCache[model]

    /**
     * 主动拉取某个模型的自报能力（POST /api/show），成功则写入缓存。
     *
     * 阻塞方法，须在 IO 线程调用。服务未启动/版本过旧/网络失败一律返回 null
     * （调用方回落内置表）—— 拉不到能力信息不该让任何功能失败。
     */
    fun refreshCapabilities(model: String): OllamaCapabilities? {
        if (model.isBlank()) return null
        return runCatching {
            val body = HttpClient.postString(
                "$OLLAMA_BASE/api/show",
                body = JSONObject().put("model", model).toString(),
                readTimeout = 5000,
            )
            val json = JSONObject(body)
            val capsArr = json.optJSONArray("capabilities")
            val caps = if (capsArr == null) emptyList()
            else (0 until capsArr.length()).map { capsArr.optString(it) }.filter { it.isNotBlank() }
            val ctx = parseContextLength(json)
            val info = OllamaCapabilities(caps, ctx)
            capsCache[model] = info
            Log.i(TAG, "refreshCapabilities($model): caps=$caps ctx=$ctx")
            info
        }.onFailure { Log.d(TAG, "refreshCapabilities($model) failed: ${it.message}") }
            .getOrNull()
    }

    /**
     * 从 `/api/show` 响应里取上下文窗口。
     *
     * 两个来源按可信度排序：`model_info.<arch>.context_length`（新版的准确值）→
     * `parameters` 文本里的 `num_ctx N`（用户/模型自带的默认值）。
     * 都没有就返回 0 = 未知 —— **不要瞎填一个默认值**：这个数字要驱动会话压缩阈值，
     * 猜大了会让上下文真的溢出（整轮请求 400，用户直接拿不到回复）。
     */
    private fun parseContextLength(showJson: JSONObject): Int {
        val info = showJson.optJSONObject("model_info")
        if (info != null) {
            val keys = info.keys()
            while (keys.hasNext()) {
                val k = keys.next()
                if (k.endsWith(".context_length") || k == "context_length") {
                    val v = info.optInt(k, 0)
                    if (v > 0) return v
                }
            }
        }
        val params = showJson.optString("parameters")
        if (params.isNotBlank()) {
            val m = Regex("num_ctx\\s+(\\d+)").find(params)
            val v = m?.groupValues?.getOrNull(1)?.toIntOrNull() ?: 0
            if (v > 0) return v
        }
        return 0
    }

    /**
     * 思考能力缓存：listModels() 时写入；供对话层免查询快速判断模型能否传 think 参数。
     * （与 [capsCache] 同源，[supportsThinking] 只是它的一个便捷视图）
     */
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
            // 顺手把自报能力灌进缓存：能力表在 tags 里就有，不必再为每个模型发一次 /api/show。
            // 上下文窗口这里拿不到（tags 不返回 model_info），留给「检测」时用 /api/show 补。
            capsCache[model.name] = OllamaCapabilities(capList, contextLength = 0)
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

    // ═══════════════════ 防冻结保活（HyperOS / MIUI）═══════════════════

    /** 保活相关持久化（独立 prefs，避免与其它配置混在一起） */
    private const val KEEPALIVE_PREFS = "local_ollama"
    private const val KEY_KEEPALIVE_ENABLED = "keepalive_enabled"

    /**
     * 守护探测间隔：**只做 HTTP 回环探测，完全不触碰 Termux**，因此全程静默、不产生任何通知。
     *
     * 为什么不是"每隔 N 秒敲一次 Termux 让它别被冻"（旧方案，已被推翻）——2026-09-10 实测：
     *  1. 没有任何外部接触时，Termux 约 3 分钟后就被 HyperOS 冻结（cgroup.freeze 0→1），
     *     即使它自己持着 `termux:service-wakelock`、且处于前台服务状态也照冻不误
     *     → 所以"定期唤醒"不能省，否则冻结期间 ollama 完全不应答；
     *  2. 但每次唤醒都要向 Termux 投递一条 RUN_COMMAND，而 Termux 收到后**必然重发它的
     *     前台服务通知**，MIUI 会把这条通知当横幅弹出（`AlertCoordinator: onViewBound, buzzBeep`，
     *     实测 15s 心跳 = 状态栏被弹个不停，系统甚至开始 `Muting recently noisy`）
     *     → 高频唤醒是不可接受的副作用；
     *  3. 而"被冻"是可以**事后修复**的：冻结期间 ollama 端口仍在 LISTEN，敲一次 Termux
     *     就地解冻后**同一个 pid 立刻恢复应答**（无需重启、无需重载模型）。
     *
     * 于是把"持续敲打"换成"静默探测 + 按需唤醒"：健康时零通知，真掉线时才唤醒一次。
     * 若连这一条恢复通知也不想要，页面上可一键关闭 Termux 通知（不影响 Termux/ollama 运行）。
     */
    private const val KEEPALIVE_PROBE_INTERVAL_MS = 10_000L

    /**
     * 连续多少拍探测失败才判定掉线（1 拍 = [KEEPALIVE_PROBE_INTERVAL_MS]）。
     *
     * 不取 1：模型首次加载、长回复生成时 ollama 偶有短暂不应答，一次失败就唤醒会平白多弹一次通知。
     * 取 2 仍能在 20s 内发现真正的冻结/掉线。
     */
    private const val KEEPALIVE_DOWN_TICKS = 2

    /** 两次自愈之间的最短间隔：Termux 反复被冻时，避免把"唤醒"打成通知风暴 */
    private const val KEEPALIVE_REPAIR_COOLDOWN_MS = 60_000L

    /** 眼镜对话配置的 prefs（键名与 CxrLHiRokidSession 保持一致，只读） */
    private const val CHAT_PREFS = "chat_prefs"
    private const val KEY_AI_USE_LOCAL = "ai_use_local"
    private const val KEY_AI_LOCAL_MODEL = "ai_local_model"

    private var keepAliveJob: Job? = null

    private fun prefs(ctx: Context) =
        ctx.applicationContext.getSharedPreferences(KEEPALIVE_PREFS, Context.MODE_PRIVATE)

    /**
     * 是否需要保活。
     *
     * 以用户在本地模型页的显式决定为准（[setKeepAliveEnabled]）：
     * 该键**存在**时直接采用其值，因此用户点过「停止服务」后不会被自动拉起；
     * 该键**不存在**（旧版本用户，或已把眼镜对话切到本地模型但没进过本页）时，
     * 退回「对话配置是否指向本机 ollama」来判断 —— 这种用户的意图本来就明确。
     */
    fun isKeepAliveWanted(ctx: Context): Boolean {
        val p = prefs(ctx)
        if (p.contains(KEY_KEEPALIVE_ENABLED)) return p.getBoolean(KEY_KEEPALIVE_ENABLED, false)
        return runCatching {
            val chat = ctx.applicationContext.getSharedPreferences(CHAT_PREFS, Context.MODE_PRIVATE)
            chat.getBoolean(KEY_AI_USE_LOCAL, false) &&
                chat.getString(KEY_AI_LOCAL_MODEL, "").orEmpty().isNotBlank()
        }.getOrDefault(false)
    }

    /** 记录"用户希望服务保持运行"（启动成功 / 设为本地对话模型时调用） */
    fun setKeepAliveEnabled(ctx: Context, enabled: Boolean) {
        prefs(ctx).edit().putBoolean(KEY_KEEPALIVE_ENABLED, enabled).apply()
        if (!enabled) stopKeepAlive()
    }

    /**
     * 启动防冻结守护循环（幂等）。由 [com.rokidlab.phone.keepalive.LabKeepAliveService]
     * 在服务创建时调用，因此进程重建后会自动恢复。
     *
     * 设计要点（2026-09-10 实测后收敛，推导见 [KEEPALIVE_PROBE_INTERVAL_MS]）：
     *  1. 每一拍只做一次 HTTP 回环探测 —— **不触碰 Termux，因此不产生任何通知**；
     *  2. 只有连续 [KEEPALIVE_DOWN_TICKS] 拍探测失败（服务被冻/被杀）才 [startServer]：
     *     那一次唤醒会给 Termux 带来一条通知，但它对应"服务真的掉线了"，是恢复必需的动作，
     *     且受 [KEEPALIVE_REPAIR_COOLDOWN_MS] 限流，不会变成通知风暴；
     *  3. 用户点「停止服务」后 [isKeepAliveWanted] 为 false，本循环不会把服务拉回来。
     */
    fun startKeepAlive(ctx: Context, scope: CoroutineScope) {
        if (keepAliveJob?.isActive == true) return
        val app = ctx.applicationContext
        if (!isKeepAliveWanted(app) || !termuxInstalled(app)) return
        keepAliveJob = scope.launch(Dispatchers.IO) {
            Log.i(TAG, "keep-alive started (silent probe every ${KEEPALIVE_PROBE_INTERVAL_MS}ms)")
            var downStreak = 0
            var lastRepairAt = 0L
            while (isActive) {
                delay(KEEPALIVE_PROBE_INTERVAL_MS)
                // 健康路径：纯 HTTP 探测，不碰 Termux → 状态栏不会因为保活而出现任何通知
                if (isServerUp()) {
                    downStreak = 0
                    continue
                }
                if (++downStreak < KEEPALIVE_DOWN_TICKS) continue
                downStreak = 0
                // 再确认一次用户意愿：用户可能刚在本页点了「停止服务」，
                // 而这一拍已经进入体内（协程取消打断不了阻塞中的 startServer）
                if (!isKeepAliveWanted(app)) {
                    Log.i(TAG, "keep-alive: disabled by user, stop auto-restart")
                    return@launch
                }
                val now = System.currentTimeMillis()
                if (now - lastRepairAt < KEEPALIVE_REPAIR_COOLDOWN_MS) {
                    Log.d(TAG, "keep-alive: repair skipped (cooldown)")
                    continue
                }
                lastRepairAt = now
                // 走到这里说明服务确实掉线：这一次唤醒会让 Termux 重发一条通知（恢复的必要代价）
                Log.w(TAG, "keep-alive: ollama unreachable, repairing (wakes Termux once)")
                runCatching { startServer(app) }
                    .onFailure { Log.w(TAG, "keep-alive repair failed: ${it.message}") }
            }
        }
    }

    /** 停止保活循环 */
    fun stopKeepAlive() {
        keepAliveJob?.cancel()
        keepAliveJob = null
    }

    // ═══════════════════ 工具 ═══════════════════

    /** 解冻后等待 accept 恢复的间隔（配合 [UNFREEZE_PROBE_ATTEMPTS] 给实例两次自证机会） */
    private const val WAKE_SETTLE_MS = 700L

    /** 解冻后的自证探测次数 */
    private const val UNFREEZE_PROBE_ATTEMPTS = 2

    /** 下发 serve 后的等待时间（足够清残留 + 进程启动，不包含加载模型） */
    private const val SERVE_WAIT_MS = 15_000L

    /** 就绪轮询间隔 */
    private const val POLL_INTERVAL_MS = 700L

    /** 回环端口占用探测超时（只判断"有没有人在 LISTEN"，不问应答，故可很短） */
    private const val PORT_PROBE_TIMEOUT_MS = 500

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

    /**
     * 打开 Termux 的通知设置页（一键关掉它的通知）。
     *
     * 为什么需要：守护循环在**服务掉线时**必须唤醒 Termux，而 Termux 每被唤醒一次都会重发它的
     * 前台服务通知，MIUI 会把这条通知当横幅弹出来 —— 这是 ROM 冻结策略导致的固有代价，
     * App 侧无法静默投递（Termux 只暴露 RunCommandService，它必定发前台通知）。
     * 用户在此页把 Termux 通知关掉后，Termux 与 ollama 照常运行，只是不再有横幅打扰。
     *
     * @return false 表示系统没有该设置页，调用方应退回应用详情页
     */
    fun openTermuxNotificationSettings(ctx: Context): Boolean = runCatching {
        val intent = Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
            putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, TERMUX_PKG)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        if (ctx.packageManager.resolveActivity(intent, 0) == null) return@runCatching false
        ctx.startActivity(intent)
        true
    }.getOrDefault(false)

    /** 打开 Termux 的应用详情页（部分 ROM 没有通知设置页时的兜底入口）；失败返回 false */
    fun openTermuxAppInfo(ctx: Context): Boolean = runCatching {
        val intent = Intent(
            android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            android.net.Uri.parse("package:$TERMUX_PKG"),
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (ctx.packageManager.resolveActivity(intent, 0) == null) return@runCatching false
        ctx.startActivity(intent)
        true
    }.getOrDefault(false)
}
