package com.rokidlab.phone.ai.mcp

import android.content.Context
import android.util.Log
import com.rokidlab.phone.R
import com.rokidlab.phone.ai.ToolRegistry
import com.rokidlab.phone.ai.ToolRegistry.ToolCategory
import com.rokidlab.phone.ai.ToolRisk
import com.rokidlab.phone.ai.ToolSchemaValidator
import com.rokidlab.phone.ai.tools.McpToolProvider
import com.rokidlab.phone.ai.tools.ToolEntry
import org.json.JSONObject

/**
 * MCP server 的**运行期注册表**：连接管理 + 工具目录 + 命名映射 + 调用分发。
 *
 * 它是「动态工具集」与「静态工具表」之间的桥：把运行时才存在的 MCP 工具，
 * 包装成项目既有的 [ToolEntry]，交给 [McpToolProvider] 暴露给 `ToolRegistry`。
 *
 * ## 与 `ToolRegistry` 的分工
 *  - 本类：知道**有哪些 MCP 工具**、怎么调、连不上时是什么状态；
 *  - `ToolRegistry`：只管聚合（谁声明了什么），不关心工具从哪来。
 *
 * 每次工具集变化都会调 [ToolRegistry.setDynamicProviders]，使派生表与 schema 缓存失效 ——
 * **漏调的表现是"设置页看得见新工具、模型却永远不调"，且不报错**。
 *
 * ## 三条准入约束（方案 §4.4：安全靠准入，不靠审批闸门）
 * 因为 `ApprovalGate` 对**内置工具**是 fail-open（无确认通道/超时即按声明降级放行，RULES §12.10），
 * 而 MCP 工具的调用方是**第三方页面**、动作本身也无法保证"未确认时只做无副作用的事"，
 * 所以第三方工具的安全不指望闸门兜底，必须落在这里：
 *  ⚠️ 反过来说，**不要**因为"MCP 工具都走 BLOCK"就推断闸门是空的 —— 内置侧已登记
 *  `delete_file` / `delete_script` / `install_packages` / `send_sms` / `delete_knowledge_doc` /
 *  `manage_glasses_app` / `delete_calendar_event` 等 `EXTERNAL_SIDE_EFFECT` 工具，名单由
 *  `ApprovalGateTest` 的 **E1** 钉住（新增必须同步）。两条路的安全目标不同，别互相替代。
 *  1. **必须 https**（release 包禁止明文流量，http 地址直接拒绝并给出可读原因）；
 *  2. **默认关**：工具首次出现时把开关显式写成 false（[ToolRegistry.ensureDisabledByDefault]），
 *     因为 `isEnabled` 的兜底是 `true`，"连上即全开"不可接受；
 *  3. **schema 不合规的工具直接丢弃**，绝不注册（理由见 [buildTools]）。
 */
internal object McpRegistry {

    private const val TAG = "McpRegistry"

    /**
     * 单个 server 最多注册多少工具。
     *
     * 为什么必须设闸：每个工具的 schema 会进**每一轮**请求的 `tools` 参数，
     * 一个工具约 200–400 token —— 30 个就是每轮多烧 6k–12k input token，
     * 直接拖慢首字延迟。项目此前为省 token 专门做过按需装配（`SESSION_AIUI_DOMAINS` 每轮少发 14 个），
     * 这里不能反向破功。超出部分会被截断并在 server 状态里报告。
     */
    const val MAX_TOOLS_PER_SERVER = 30

    /** 下发给模型的 function name 长度上限（OpenAI 兼容端点的约定） */
    private const val MAX_WIRE_NAME = 64

    /**
     * 一个 server 的运行期快照（**不可变**，整体替换）。
     *
     * 做成不可变是为了让读侧（设置页 / schema 构建）永远看到一致的一组值 ——
     * 否则"工具列表更新到一半被读到"会出现工具名与 schema 不匹配。
     */
    internal class ServerState(
        val config: McpServerConfig,
        val connected: Boolean,
        val sessionId: String?,
        val tools: List<RegisteredTool>,
        /** 连接/列工具失败原因；null = 正常 */
        val error: String?,
        /** 被丢弃的工具及原因（schema 不合规 / 撞名），供设置页展示 */
        val rejected: List<String>,
        /**
         * 实际通信端点。普通 server = config.url；stdio server = 桥动态分配的回环地址
         * （config.url 为空 —— 端口每次连接才定）。调用分发**只看这里**，别回头读 config.url。
         */
        val endpoint: String = config.url,
    ) {
        /** 这个 server 的工具当前是否应该下发给模型 */
        val usable: Boolean get() = connected && error == null
    }

    /** 一个**已通过严格校验**、可以下发给模型的 MCP 工具 */
    internal class RegisteredTool(
        val wireName: String,
        val originalName: String,
        val description: String,
        /** 已过 [ToolSchemaValidator] 的 OpenAI `tools[]` 声明 */
        val schema: JSONObject,
    )

    /**
     * 一致性快照（R14）：states（server 状态表）与 origin（wireName 反查表）**原子发布**。
     *
     * 原先两个独立 @Volatile，「先 states 后 origin」的发布窗口内会出现：
     * 新 server 的状态可见、但它的工具反查不到 ⇒ 调用白耗一轮（虽然自愈，但不必要的错误反馈
     * 会诱使页面/模型重试）。读侧永远读**同一个** snapshot，窗口消失。
     */
    private class Snapshot(
        val states: Map<String, ServerState> = emptyMap(),
        val origin: Map<String, Pair<String, String>> = emptyMap(),
    )

    @Volatile
    private var snapshot = Snapshot()

    private fun sortedUsable(states: Map<String, ServerState>): List<ServerState> =
        states.values.filter { it.config.enabled && it.usable }.sortedBy { it.config.addedAt }

    // ═══════════════════ 供 McpToolProvider 读取（动态 getter，不能是快照） ═══════════════════

    fun activeToolNames(): Set<String> = snapshot.origin.keys.toSet()

    fun activeEntries(): List<ToolEntry> =
        sortedUsable(snapshot.states).flatMap { st -> st.tools.map { toEntry(st.config, it) } }

    /**
     * 执行一次 MCP 工具调用。
     *
     * **永不抛异常** —— 这是工具回填路径，抛出去会让主循环把整轮判失败
     * （与 `ReadOnlySubagent.run` 同一约定）。失败原因写进文本交给模型。
     */
    fun call(ctx: Context, name: String, args: JSONObject): String {
        val snap = snapshot
        val mapped = snap.origin[name]
            ?: return "未知的 MCP 工具：$name（对端可能已断开，请刷新服务器列表）"
        val (serverId, originalName) = mapped
        val st = snap.states[serverId] ?: return "MCP server 未连接"
        if (!st.usable) {
            return "MCP server「${st.config.name}」当前不可用：${st.error ?: "已断开"}"
        }
        // 与 connect 同一套头装配：OAuth Bearer 优先，静态 Authorization 头让位
        val headers = buildMap {
            putAll(st.config.headers.filterKeys { !it.equals("Authorization", ignoreCase = true) })
            McpOAuth.authHeader(ctx, serverId)?.let { put("Authorization", it) }
        }
        val result = McpClient.callTool(st.endpoint, st.sessionId, headers, originalName, args)
        // 调用期 401：多半是 access token 在连接后过期了 —— 静默刷一层并原样重试一次
        // （McpClient 永不抛异常 ⇒ 401 以带标记的文本回来，见 McpClient.callTool）
        if (result.contains("未授权（401）") && McpOAuth.hasTokens(ctx, serverId)) {
            val refreshError = McpOAuth.refreshIfNeeded(ctx, serverId, st.config.url, force = true)
            if (refreshError == null) {
                val retryHeaders = buildMap {
                    putAll(st.config.headers.filterKeys { !it.equals("Authorization", ignoreCase = true) })
                    McpOAuth.authHeader(ctx, serverId)?.let { put("Authorization", it) }
                }
                return McpClient.callTool(st.endpoint, st.sessionId, retryHeaders, originalName, args)
            }
        }
        return result
    }

    // ═══════════════════ 状态查询（设置页） ═══════════════════

    /** 全部 server 状态，按添加时间排序（稳定展示顺序） */
    fun allStates(): List<ServerState> = snapshot.states.values.sortedBy { it.config.addedAt }

    fun stateOf(serverId: String): ServerState? = snapshot.states[serverId]

    // ═══════════════════ 生命周期 ═══════════════════

    /**
     * 连接一个 server 并注册它的工具。
     *
     * ⚠️ **同步阻塞**（stdio server 还要先拉桥 + 健康检查，最多再等 ~10s）
     * ⇒ 必须在后台线程调用（UI 侧用 `Dispatchers.IO`），不要在 Compose 组合里直接调。
     *
     * OAuth：若该 server 已有授权 token（[McpOAuth]），自动附加 `Authorization: Bearer` 头；
     * 连接中遇到 401（[McpUnauthorizedException]）且存在 refresh_token 时静默续期一次并重试。
     *
     * @return **null = 成功**；非 null = 失败原因（已面向用户措辞，可直接展示）
     */
    fun connect(ctx: Context, config: McpServerConfig): String? {
        // stdio server：先把桥拉起来，拿到动态回环端口；后续 HTTP 流程与普通 server 完全同一条路
        var endpoint = config.url
        if (config.isStdio) {
            val started = McpStdioBridge.ensureStarted(ctx, config)
            val port = started.getOrNull()
            if (port == null) {
                val reason = started.exceptionOrNull()?.message ?: "stdio 桥启动失败"
                put(config, ServerState(config, false, null, emptyList(), reason, emptyList()))
                return reason
            }
            endpoint = "http://127.0.0.1:$port"
        }
        validateUrl(endpoint)?.let { reason ->
            put(config, ServerState(config, false, null, emptyList(), reason, emptyList(), endpoint))
            return reason
        }
        val reason = connectWithAuth(ctx, config, endpoint, forcedRefresh = false)
        if (reason == null) return null
        put(config, ServerState(config, false, null, emptyList(), reason, emptyList(), endpoint))
        return reason
    }

    /**
     * 带鉴权的连接尝试：initialize + listTools。
     *
     * 401 处理口径（只重试一层，避免循环）：
     *  - 有 refresh_token 且还没试过强刷 ⇒ 静默续期后**原地重试一次**；
     *  - 续期失败 / 重试仍 401 ⇒ 返回可读原因；若 server 曾授权过（有 token），
     *    原因文案提示"重新授权"，设置页据此亮出授权按钮。
     */
    private fun connectWithAuth(
        ctx: Context,
        config: McpServerConfig,
        endpoint: String,
        forcedRefresh: Boolean,
    ): String? {
        // OAuth Bearer 优先于静态头：它是"最新的凭证"；用户手填的其它头照常透传
        // （Bearer 与手填 Authorization 冲突时以 Bearer 为准 —— 静态 token 必然过期，没有保留价值）
        val headers = buildMap {
            putAll(config.headers.filterKeys { !it.equals("Authorization", ignoreCase = true) })
            McpOAuth.authHeader(ctx, config.id)?.let { put("Authorization", it) }
        }
        val sessionId = try {
            McpClient.initialize(endpoint, headers)
        } catch (e: McpUnauthorizedException) {
            return onUnauthorized(ctx, config, endpoint, forcedRefresh, "连接被拒绝（401）")
        } catch (e: Exception) {
            // ⚠️ 只打 endpoint 不打 headers —— headers 可能含 token
            Log.w(TAG, "connect $endpoint failed: ${e.message}")
            return "连接失败：${e.message ?: e.javaClass.simpleName}"
        }
        val defs = try {
            McpClient.listTools(endpoint, sessionId, headers)
        } catch (e: McpUnauthorizedException) {
            return onUnauthorized(ctx, config, endpoint, forcedRefresh, "获取工具列表被拒绝（401）")
        } catch (e: Exception) {
            Log.w(TAG, "listTools $endpoint failed: ${e.message}")
            return "获取工具列表失败：${e.message ?: e.javaClass.simpleName}"
        }
        val (registered, rejected) = buildTools(ctx, config, defs)
        put(
            config,
            ServerState(
                config = config,
                connected = true,
                sessionId = sessionId,
                tools = registered,
                error = null,
                rejected = rejected,
                endpoint = endpoint,
            ),
        )
        Log.i(TAG, "connected ${config.name}: ${registered.size} tools, ${rejected.size} rejected")
        return null
    }

    /** 401 的统一处置：能刷则刷一层再重试；不能刷则给带行动指引的原因文案 */
    private fun onUnauthorized(
        ctx: Context,
        config: McpServerConfig,
        endpoint: String,
        forcedRefresh: Boolean,
        prefix: String,
    ): String {
        if (!forcedRefresh && McpOAuth.hasTokens(ctx, config.id)) {
            val refreshError = McpOAuth.refreshIfNeeded(ctx, config.id, config.url, force = true)
            if (refreshError == null) {
                Log.i(TAG, "token refreshed, retrying connect ${config.name}")
                return connectWithAuth(ctx, config, endpoint, forcedRefresh = true)
                    ?: prefix // 成功路径理论上不会走到这里（成功时 connect() 直接返回）
            }
            Log.w(TAG, "refresh failed for ${config.name}: $refreshError")
        }
        val authorizedBefore = McpOAuth.hasTokens(ctx, config.id)
        return "$prefix：" + if (authorizedBefore) {
            "登录凭证已失效且自动续期未成功，请点「授权登录」重新授权"
        } else {
            "该 server 需要 OAuth 授权，请点「授权登录」"
        }
    }

    /** 断开并移除某个 server（它的工具随即从模型可见清单消失；stdio 桥一并停掉） */
    fun disconnect(ctx: Context, serverId: String) {
        publish(snapshot.states - serverId)
        McpStdioBridge.stop(ctx, serverId)
    }

    /**
     * 按持久化配置重建全部连接（App 启动 / 用户点「刷新」时）。
     *
     * **串行**连接所有启用的 server，每个 2 个请求 ⇒ 必须在后台线程调用。
     * 单个 server 失败不影响其它（各自把错误记进自己的 [ServerState]）。
     */
    fun syncAll(ctx: Context) {
        val configs = McpServerStore.load(ctx)
        val keptIds = configs.map { it.id }.toSet()
        // 配置里已删除的 server：状态与 stdio 桥一并清掉（桥是常驻进程，不能跟着配置变成孤儿）
        val stale = snapshot.states.keys.filter { it !in keptIds }
        stale.forEach { McpStdioBridge.stop(ctx, it) }
        publish(snapshot.states.filterKeys { it in keptIds })
        configs.filter { it.enabled }.forEach { cfg ->
            runCatching { connect(ctx, cfg) }
                .onFailure { Log.w(TAG, "syncAll ${cfg.name} threw: ${it.message}") }
        }
    }

    /** 配置变更（改地址/开关/信任标记）后：断开旧的、按新配置重连 */
    fun reload(ctx: Context, config: McpServerConfig) {
        if (!config.enabled) {
            disconnect(ctx, config.id)
            return
        }
        connect(ctx, config)
    }

    // ═══════════════════ 内部 ═══════════════════

    /** 发布新快照：由 states 整表重建 origin，原子替换并通知 `ToolRegistry` 失效派生表 */
    private fun publish(states: Map<String, ServerState>) {
        val map = LinkedHashMap<String, Pair<String, String>>()
        sortedUsable(states).forEach { st ->
            st.tools.forEach { map[it.wireName] = st.config.id to it.originalName }
        }
        snapshot = Snapshot(states, map)
        ToolRegistry.setDynamicProviders(listOf(McpToolProvider))
    }

    /**
     * 地址准入：**只允许 https；明文 http 仅限回环地址**。
     *
     * 为什么必须硬性：`res/xml/network_security_config.xml` 的 `base-config` 是
     * `cleartextTrafficPermitted="false"`，白名单只有本机回环、眼镜局域网与 ip-api.com。
     * 非回环 http 地址在 **release 包上一定连不上**（debug 变体被 `src/debug/` 的同名文件
     * 整体覆盖成 `true`，所以"调试能跑、发布才挂"——眼镜 WebServer 8848 当年踩的就是这个坑）。
     * 与其让它连不上再报一个看不懂的 SSL 错，不如在入口直接拒绝。
     *
     * 为什么偏偏给回环开口子：回环在**两份变体的白名单里都有**，
     * 所以回环 http 在 debug / release 上**行为完全一致**，不构成上面那个坑，
     * 却能让"自建本地 MCP"这条最常用的调试路径跑通。
     * ⚠️ 反过来说：**不要**顺手按 `BuildConfig.DEBUG` 整体放开 http —— 那恰好复现该坑。
     */
    private fun validateUrl(url: String): String? = when {
        url.isBlank() -> "地址不能为空"
        url.startsWith("https://", ignoreCase = true) -> null
        url.startsWith("http://", ignoreCase = true) && isLoopbackHost(url) -> null
        else -> "只支持 https 地址（明文 http 仅回环地址可用）"
    }

    /**
     * 供 UI 复用同一判定。
     *
     * 页面若自己再写一份 `startsWith("https://")`，迟早与这里分叉 ⇒
     * 「输入框放行、点连接被拒」（或反过来）这种鬼故事。**判定只有这一处**。
     */
    internal fun isUrlAllowed(url: String): Boolean = validateUrl(url) == null

    /** 回环主机判定：`localhost` / `127.0.0.1` / `::1`（容忍端口、路径、IPv6 方括号）。 */
    private fun isLoopbackHost(url: String): Boolean {
        val authority = url.substringAfter("://", "").substringBefore('/').substringBefore('?')
        val host = if (authority.startsWith("[")) {
            authority.substringAfter('[').substringBefore(']')
        } else {
            authority.substringBefore(':')
        }
        return host.equals("localhost", ignoreCase = true) || host == "127.0.0.1" || host == "::1"
    }

    /**
     * `tools/list` 的原始定义 → 可注册工具；被丢弃的连同原因一起返回。
     *
     * ⚠️ **schema 不合规的工具必须整个丢弃，绝不能"修一修再用"**：
     * 服务端的 schema 校验是**整请求级**的 —— 几十个工具里只要 1 个嵌套节点形状不合规，
     * 服务端直接 400，**所有工具一起失效**，用户看到的是「AI 突然不会说话了」
     * （2026-09-15 真机事故，见 [ToolSchemaValidator] 的 KDoc）。
     * 而 MCP 的 schema 来自**第三方**，什么形状都可能有 ⇒ 必须逐个过本地校验。
     */
    private fun buildTools(
        ctx: Context,
        config: McpServerConfig,
        defs: List<McpToolDef>,
    ): Pair<List<RegisteredTool>, List<String>> {
        val accepted = mutableListOf<RegisteredTool>()
        val rejected = mutableListOf<String>()
        val taken = mutableSetOf<String>()

        // 上限在**校验之后**才生效（R14）：原先 `defs.take(30)` 先截断再校验，
        // 排在前面的不合规工具会白占名额，把后面 schema 合法、本来能用的工具挤出清单。
        defs.forEach { def ->
            if (accepted.size >= MAX_TOOLS_PER_SERVER) {
                rejected += "${def.name}（超出单 server 上限 $MAX_TOOLS_PER_SERVER，未注册）"
                return@forEach
            }
            val wire = wireNameOf(config.id, def.name)
            // 撞名检测：同一 server 内、以及跨 server（快照 origin 里已被别人占）
            val otherOwner = snapshot.origin[wire]?.first
            if (wire in taken || (otherOwner != null && otherOwner != config.id)) {
                rejected += "${def.name}（工具名与已有工具冲突）"
                return@forEach
            }
            val desc = def.description.ifBlank { "MCP 工具 ${def.name}（对端未提供描述）" }
            val schema = JSONObject()
                .put("type", "function")
                .put(
                    "function",
                    JSONObject()
                        .put("name", wire)
                        .put("description", desc)
                        .put("parameters", sanitizeParams(def.inputSchema)),
                )
            val problems = ToolSchemaValidator.validate(schema, wire)
            if (problems.isNotEmpty()) {
                // 不注册 = 不进模型的工具清单，也就拖不垮整轮请求
                rejected += "${def.name}（参数声明不被接受：${problems.first()}）"
                return@forEach
            }
            taken += wire
            accepted += RegisteredTool(wire, def.name, desc, schema)
        }

        // 上限溢出已在上面逐条记 rejected（含被挤出的具体工具名），这里不再重复汇总
        // 准入控制：首次见到的工具默认**关**（第三方能力默认不信任）
        accepted.forEach { ToolRegistry.ensureDisabledByDefault(ctx, it.wireName) }
        return accepted to rejected
    }

    /**
     * MCP 的 `inputSchema` → OpenAI 的 `parameters`。只做三件**语义无损**的事：
     *  ① 剥 `$schema`（OpenAI 端点不认这个键）；
     *  ② 强制 `type=object`（MCP 允许省略 type，OpenAI 要求必须声明）；
     *  ③ 补空 `properties`（无参工具）。
     *
     * ⚠️ 刻意**不**去"修"更深层的问题（例如 `type=array` 缺 `items`）：那种改法会让
     * schema 语义失真、模型按错的参数调用。交回给 [buildTools] 的校验器判定 ——
     * 不合规就整个工具不注册，而不是给它编一个假 schema。
     */
    private fun sanitizeParams(raw: JSONObject?): JSONObject {
        val p = if (raw == null) JSONObject() else JSONObject(raw.toString())
        p.remove("\$schema")
        if (p.optString("type") != "object") p.put("type", "object")
        if (!p.has("properties")) p.put("properties", JSONObject())
        return p
    }

    /**
     * 生成下发给模型的 function name：`mcp__<serverId>__<工具名>`。
     *
     * 为什么要改名而不是直接用对端工具名：
     *  ① OpenAI 的 function name 只允许 `[A-Za-z0-9_-]` 且有长度上限，而 MCP 的工具名
     *     相对自由（可能出现 `.` `/` 空格等）；
     *  ② 加 serverId 前缀才能区分「两个 server 各有一个同名工具」。
     *
     * 超长时**截断 + 8 位哈希**：只截断会让长名工具互相撞车（前 64 字符相同的全冲突）。
     */
    private fun wireNameOf(serverId: String, toolName: String): String {
        val safeTool = toolName.map { ch ->
            if (ch.isLetterOrDigit() || ch == '_' || ch == '-') ch else '_'
        }.joinToString("").ifBlank { "tool" }
        val base = "${ToolRegistry.MCP_TOOL_PREFIX}${sanitizeId(serverId)}__$safeTool"
        return if (base.length <= MAX_WIRE_NAME) {
            base
        } else {
            base.take(MAX_WIRE_NAME - 9) + "_" + Integer.toHexString(base.hashCode()).take(8)
        }
    }

    private fun sanitizeId(id: String): String =
        id.map { if (it.isLetterOrDigit() || it == '_' || it == '-') it else '_' }
            .joinToString("").ifBlank { "s" }

    private fun put(config: McpServerConfig, state: ServerState) {
        publish(snapshot.states + (config.id to state))
    }

    private fun toEntry(cfg: McpServerConfig, tool: RegisteredTool): ToolEntry = ToolEntry(
        name = tool.wireName,
        group = ToolRegistry.DOMAIN_MCP,
        // 占位资源：动态工具没有编译期资源 ID。显示一律走 dynamicName / dynamicDescription，
        // 这两个值**永远不会被展示**，仅为满足结构上的必填字段（见 ToolMeta.displayName 的取值）。
        displayNameRes = R.string.ai_tool_cat_mcp,
        descriptionRes = R.string.ai_tool_cat_mcp,
        dynamicName = "${cfg.name} · ${tool.originalName}",
        dynamicDescription = tool.description,
        category = ToolCategory.MCP,
        // 确认降级策略（confirmPolicy）：**刻意不显式声明**，走 defaultFor(risk) 的派生 ——
        //   未信任（EXTERNAL）⇒ BLOCK：问不到用户必须拒绝。第三方远端动作无法保证
        //     "未确认时只做无副作用动作"，因此不能像内置的删文件/装包那样 fail-open；
        //   已信任（LOCAL）⇒ PROCEED：此时 RiskApprovalGuard 根本不产生 Ask，取值不被读。
        // 这条派生关系是"第三方工具不再依赖准入单点"的关键，见 ToolConfirmPolicy 类注释。
        risk = if (cfg.trusted) ToolRisk.LOCAL_SIDE_EFFECT else ToolRisk.EXTERNAL_SIDE_EFFECT,
        // 第三方工具的幂等性不可知 ⇒ 失败不重试（避免重复副作用）
        sideEffect = true,
        // 无论用户是否信任该 server，其返回内容都是第三方作者写的 ——
        // 「信任」授权的是调用动作，不代表返回文本可以当指令
        contentTrust = com.rokidlab.phone.ai.ToolContentTrust.UNTRUSTED_EXTERNAL,
        requiresGlasses = false, // MCP 走网络，与眼镜无关，本机模式下应保留
        statusText = "正在调用 ${tool.originalName}…",
        schema = tool.schema,
    )
}
