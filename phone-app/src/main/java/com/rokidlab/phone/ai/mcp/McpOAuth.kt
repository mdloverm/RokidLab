package com.rokidlab.phone.ai.mcp

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import com.rokidlab.phone.util.HttpClient
import com.rokidlab.phone.util.HttpStatusException
import com.rokidlab.phone.util.SecretStore
import org.json.JSONArray
import org.json.JSONObject
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

/**
 * MCP server 的 OAuth 2.0 授权（MCP 规范 2025-06-18 的 Authorization Code + PKCE 流）。
 *
 * ## 与既有鉴权方式的分工
 * `McpServerConfig.headers` 里的静态 `Authorization` 头仍然有效 —— OAuth 是它的**升级项**：
 * 静态 token 不会过期、配置简单，但过期就要手动换；OAuth 让 server 自己发短期 token 并提供
 * refresh_token，App 侧在 401 时自动续期。两者可以并存（OAuth 的 Bearer 头优先级更高，
 * 因为它是"当前会话最新的凭证"，见 [McpRegistry.connect] 的头装配顺序）。
 *
 * ## 流程（全部按 MCP 规范对 RFC 的引用实现）
 *  1. **元数据发现**（RFC 8414）：GET `{origin}/.well-known/oauth-authorization-server{path}`
 *     （路径感知变体优先，这是 MCP 规范的要求），再退 `{origin}/.well-known/oauth-authorization-server`。
 *  2. **动态客户端注册**（RFC 7591）：POST `registration_endpoint` 拿 `client_id`。
 *     没有 registration_endpoint 的 server 本版不支持（提示用户退回静态 token 头）。
 *  3. **PKCE**（RFC 7636，S256）：verifier 32 字节随机 → base64url；challenge = SHA-256(verifier)。
 *  4. **浏览器授权**：`ACTION_VIEW` 打开 authorization_endpoint；回调走深链
 *     `rokidlab://mcp/callback`（由 [McpCallbackActivity] 接收）。
 *  5. **code 换 token**：表单 POST token_endpoint（`HttpClient.postFormRaw`，见其 KDoc）。
 *  6. **刷新**：连接时发现 401 / token 过期 → 用 refresh_token 静默换新 → 重试一次；
 *     刷新也被拒 ⇒ 清掉 token，提示用户重新授权。
 *
 * ## 安全边界
 *  - token 与待办授权流（含 PKCE verifier）都用 [SecretStore] 加密落盘（Keystore AES-GCM），
 *    `mcp_servers.json` 配置里**不含**任何 token；
 *  - state 全程校验（防 CSRF：回调里的 state 必须匹配发起时存的）；
 *  - `resource` 参数（RFC 8707）按 MCP 规范带上 = server 端点本身，防 token 被混用授权给别的服务；
 *  - client_secret 不支持（公共客户端，`token_endpoint_auth_method: none`）——
 *    需要 secret 的 server 说明它不是给原生 App 用的，别硬凑。
 */
internal object McpOAuth {

    private const val TAG = "McpOAuth"

    /** OAuth 回调深链（Manifest 里 [McpCallbackActivity] 的 intent-filter 与此对应，两处必须同步改） */
    const val REDIRECT_URI = "rokidlab://mcp/callback"

    private const val PREFS = "mcp_oauth_store"
    private const val KEY_PENDING = "pending_flow"
    private const val KEY_ACCESS = "access_"
    private const val KEY_REFRESH = "refresh_"
    private const val KEY_EXPIRES_AT = "expires_at_"
    private const val KEY_CLIENT_ID = "client_id_"

    /**
     * 刷新单飞锁（2026-10-02 体检）：工具调用 401 触发的续期与连接期续期可能并发进入，
     * 两个请求若同时带同一个 refresh_token，在服务端**轮换** refresh token 的实现下后到者
     * 必然 invalid_grant —— 结果是全部 token 被清光、用户被迫重新授权。整个
     * 「查过期 → 刷新 → 落盘」流程收口到这把锁：后到者进锁后重读过期时间，
     * 先到者已刷新落盘，它直接走「未过期」快路径返回，不会再发第二个刷新请求。
     */
    private val refreshLock = Any()

    // ═══════════════════ 数据 ═══════════════════

    /** server 的 OAuth 元数据（RFC 8414 的关键字段子集） */
    internal data class Metadata(
        val authorizationEndpoint: String,
        val tokenEndpoint: String,
        /** RFC 7591 动态注册端点；null = 不支持自动注册 */
        val registrationEndpoint: String?,
        val scopesSupported: List<String>,
    )

    /** 待完成的授权流（发起时存，回调时取回校验） */
    internal data class PendingFlow(
        val serverId: String,
        val serverName: String,
        val verifier: String,
        val state: String,
        val clientId: String,
        val tokenEndpoint: String,
        val resource: String,
    )

    /** 一次成功换取的 token 集 */
    internal data class Tokens(
        val accessToken: String,
        val refreshToken: String?,
        /** 绝对过期时刻（epoch ms）；服务器没给 expires_in 则为 Long.MAX_VALUE（只靠 401 兜底） */
        val expiresAtMs: Long,
        val clientId: String,
    )

    /** code 换 token 的原始响应（校验过 access_token 存在） */
    internal data class TokenResponse(
        val accessToken: String,
        val refreshToken: String?,
        /** 相对寿命（ms）；server 未给 expires_in 则 -1 */
        val expiresInMs: Long,
    )

    // ═══════════════════ 发起授权 ═══════════════════

    /**
     * 对一个 https server 发起 OAuth 授权：发现元数据 → 注册客户端 → 打开浏览器。
     *
     * @return null = 已打开浏览器、等待回调；非 null = 失败原因（可直接展示）。
     * ⚠️ **同步阻塞**（两个网络请求）—— 调用方必须在后台线程。
     */
    fun beginAuthorization(ctx: Context, config: McpServerConfig): String? {
        if (config.isStdio || !config.url.startsWith("https://", ignoreCase = true)) {
            return "OAuth 仅支持 https 远程 server（本地/stdio server 无需授权）"
        }
        val meta = try {
            discover(config.url)
        } catch (e: Exception) {
            Log.w(TAG, "discover failed for ${config.url}: ${e.message}")
            return "OAuth 元数据发现失败：${e.message}"
        }
        if (meta == null) {
            return "该 server 未提供 OAuth 元数据；请在编辑页里改用静态 Token"
        }
        val registration = meta.registrationEndpoint
            ?: return "该 server 未提供动态注册端点，本版无法自动授权；请在编辑页里改用静态 Token"
        val clientId = try {
            registerClient(registration, config.name.ifBlank { config.url })
        } catch (e: Exception) {
            Log.w(TAG, "dynamic client registration failed: ${e.message}")
            return "动态客户端注册失败：${e.message}"
        }

        val verifier = generateVerifier()
        val flow = PendingFlow(
            serverId = config.id,
            serverName = config.name,
            verifier = verifier,
            state = randomToken(16),
            clientId = clientId,
            tokenEndpoint = meta.tokenEndpoint,
            resource = config.url,
        )
        // pending 流含 PKCE verifier（拿它可换 token）⇒ 必须加密存储
        SecretStore.put(
            prefs(ctx),
            KEY_PENDING,
            flowJson(flow).toString(),
        )

        val url = buildAuthorizeUrl(
            authorizationEndpoint = meta.authorizationEndpoint,
            clientId = clientId,
            redirectUri = REDIRECT_URI,
            state = flow.state,
            codeChallenge = s256Challenge(verifier),
            scope = meta.scopesSupported.joinToString(" "),
            resource = flow.resource,
        )
        return try {
            ctx.startActivity(
                Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
            null
        } catch (e: Exception) {
            Log.w(TAG, "open browser failed: ${e.message}")
            clearPending(ctx)
            "无法打开浏览器：${e.message}"
        }
    }

    /**
     * 处理浏览器回调（由 [McpCallbackActivity] 调用）。
     *
     * @return null = 授权成功（token 已落盘；成功 serverId 写入 [lastAuthorizedServerId]）；
     *   非 null = 失败原因。
     * ⚠️ **同步阻塞**（一个网络请求）—— 调用方必须在后台线程。
     */
    fun handleCallback(ctx: Context, callbackUrl: String): String? {
        val cb = parseCallback(callbackUrl) ?: return "无法解析回调参数"
        if (cb.error != null) {
            clearPending(ctx)
            return "授权被拒绝：${cb.error}${cb.errorDescription?.let { "（$it）" } ?: ""}"
        }
        val flow = loadPending(ctx) ?: return "没有进行中的授权流程（可能 App 已重启）"
        if (cb.state != flow.state) {
            clearPending(ctx)
            return "state 校验失败（疑似 CSRF 或回调串台），已放弃本次授权"
        }
        val code = cb.code
        if (code.isNullOrBlank()) return "回调缺少 code 参数"

        val raw = HttpClient.postFormRaw(
            flow.tokenEndpoint,
            buildMap {
                put("grant_type", "authorization_code")
                put("code", code)
                put("redirect_uri", REDIRECT_URI)
                put("client_id", flow.clientId)
                put("code_verifier", flow.verifier)
                put("resource", flow.resource)
            },
        )
        if (!raw.ok) {
            clearPending(ctx)
            Log.w(TAG, "token exchange failed: HTTP ${raw.code} ${raw.body.take(200)}")
            return "换取 token 失败（HTTP ${raw.code}）：${oauthErrorMessage(raw.body)}"
        }
        val resp = parseTokenResponse(raw.body)
            ?: run {
                clearPending(ctx)
                return "token 响应缺少 access_token：${raw.body.take(200)}"
            }
        storeTokens(ctx, flow.serverId, resp.toTokens(flow.clientId))
        clearPending(ctx)
        lastAuthorizedServerId = flow.serverId
        Log.i(TAG, "oauth completed for server ${flow.serverId} (${flow.serverName})")
        return null
    }

    /** 最近一次成功授权的 serverId（[McpCallbackActivity] 用它触发重连；回调是单次事件，无需并发保护） */
    @Volatile
    var lastAuthorizedServerId: String? = null
        private set

    // ═══════════════════ 读取 / 刷新（McpRegistry 调用面） ═══════════════════

    /** server 当前是否有 token（不论是否过期；刷新由连接流程统一处理） */
    fun hasTokens(ctx: Context, serverId: String): Boolean =
        !accessToken(ctx, serverId).isNullOrBlank()

    /**
     * 当前应附加的 Bearer 头；无 token 或已过期返回 null
     * （过期走 [refreshIfNeeded]，别在这里偷偷刷新 —— 调用方需要明确的成功/失败语义）。
     */
    fun authHeader(ctx: Context, serverId: String): String? {
        val token = accessToken(ctx, serverId) ?: return null
        val expiresAt = prefs(ctx).getLong(KEY_EXPIRES_AT + serverId, Long.MAX_VALUE)
        return if (System.currentTimeMillis() < expiresAt) "Bearer $token" else null
    }

    /**
     * token 过期（或 [force] 指明 401）时用 refresh_token 静默续期。
     *
     * @return null = 无需刷新或刷新成功；非 null = 失败原因（调用方应提示重新授权）
     * ⚠️ 同步阻塞（重新发现元数据 + 一个网络请求）—— 后台线程调用。
     */
    fun refreshIfNeeded(
        ctx: Context,
        serverId: String,
        serverUrl: String,
        force: Boolean = false,
    ): String? = synchronized(refreshLock) {
        val access = accessToken(ctx, serverId) ?: return null
        val expired = force || System.currentTimeMillis() >=
            prefs(ctx).getLong(KEY_EXPIRES_AT + serverId, Long.MAX_VALUE)
        if (!expired) return null
        val refresh = SecretStore.get(prefs(ctx), KEY_REFRESH + serverId)
        if (refresh.isNullOrBlank()) return "登录已过期且无法自动续期，请重新授权"
        val clientId = prefs(ctx).getString(KEY_CLIENT_ID + serverId, null).orEmpty()
        if (clientId.isBlank()) return "登录信息不完整，请重新授权"
        // token 端点未持久化（随 pending 清除）：刷新前重新发现一次 —— 元数据是稳定事实
        val tokenEndpoint = try {
            discover(serverUrl)?.tokenEndpoint
        } catch (e: Exception) {
            Log.w(TAG, "re-discover for refresh failed: ${e.message}")
            null
        } ?: return "无法重新发现 OAuth 元数据，请稍后重试或重新授权"

        val raw = HttpClient.postFormRaw(
            tokenEndpoint,
            buildMap {
                put("grant_type", "refresh_token")
                put("refresh_token", refresh)
                put("client_id", clientId)
                put("resource", serverUrl)
            },
        )
        if (!raw.ok) {
            // 刷新被拒（invalid_grant 等）⇒ 旧 token 全部作废，必须重新走授权
            Log.w(TAG, "refresh failed: HTTP ${raw.code} ${raw.body.take(200)}")
            clearTokens(ctx, serverId)
            return "自动续期被拒绝（${oauthErrorMessage(raw.body)}），请重新授权"
        }
        val resp = parseTokenResponse(raw.body) ?: return "续期响应异常，请重新授权"
        storeTokens(ctx, serverId, resp.toTokens(clientId))
        Log.i(TAG, "token refreshed for server $serverId")
        return null
    }

    /** 清掉某 server 的全部授权痕迹（删除 server 时调用） */
    fun clear(ctx: Context, serverId: String) = clearTokens(ctx, serverId)

    /** 授权状态（设置页展示用；文案由 UI 层按 locale 出，这里只给状态）：null = 未授权 */
    internal enum class OauthState { AUTHORIZED, EXPIRED }

    fun oauthState(ctx: Context, serverId: String): OauthState? = when {
        accessToken(ctx, serverId) == null -> null
        System.currentTimeMillis() < prefs(ctx).getLong(KEY_EXPIRES_AT + serverId, Long.MAX_VALUE) ->
            OauthState.AUTHORIZED
        else -> OauthState.EXPIRED
    }

    // ═══════════════════ 元数据发现与注册（协议层） ═══════════════════

    /**
     * 发现 OAuth 元数据。
     *
     * @return null = 所有候选地址都返回 404/405（= server 不提供元数据，**不是错误**）；
     *   其它 HTTP 状态（如 500）与网络异常照常抛 —— 那是真故障，吞掉会把
     *   "server 挂了"误报成"不支持 OAuth"。
     */
    internal fun discover(endpoint: String): Metadata? {
        var sawNotFound = false
        for (url in wellKnownCandidates(endpoint)) {
            val body = try {
                HttpClient.getString(url, connectTimeout = 8000, readTimeout = 8000)
            } catch (e: HttpStatusException) {
                if (e.code == 404 || e.code == 405) {
                    sawNotFound = true
                    continue // 这个位置没有，试下一个候选
                }
                throw RuntimeException("元数据探测失败：HTTP ${e.code}", e)
            }
            parseMetadata(body)?.let { return it }
        }
        if (sawNotFound) return null
        throw RuntimeException("元数据探测失败：所有候选地址均无有效响应")
    }

    /** RFC 7591 动态客户端注册，返回 client_id */
    private fun registerClient(registrationEndpoint: String, serverName: String): String {
        val body = JSONObject()
            .put("client_name", "RokidLab · $serverName")
            .put("redirect_uris", JSONArray().put(REDIRECT_URI))
            .put("grant_types", JSONArray().put("authorization_code").put("refresh_token"))
            .put("response_types", JSONArray().put("code"))
            .put("token_endpoint_auth_method", "none")
        val raw = HttpClient.postStringRaw(registrationEndpoint, body.toString())
        if (!raw.ok) throw RuntimeException("HTTP ${raw.code}: ${oauthErrorMessage(raw.body)}")
        val clientId = runCatching { JSONObject(raw.body).optString("client_id") }.getOrNull()
        if (clientId.isNullOrBlank()) throw RuntimeException("注册响应缺少 client_id")
        return clientId
    }

    // ═══════════════════ 纯函数层（单测直接打这里，零 Android 依赖） ═══════════════════

    /**
     * well-known 候选地址（按优先级）：
     * 路径感知变体（MCP 规范：`https://host/a/b` → `https://host/.well-known/…/a/b`），
     * 再退根路径变体。
     */
    internal fun wellKnownCandidates(endpoint: String): List<String> {
        val base = endpoint.substringAfter("://") // host[:port]/path...
        val slash = base.indexOf('/')
        val origin = if (slash < 0) base else base.substring(0, slash)
        val path = if (slash < 0) "" else base.substring(slash) // 含开头的 /
        val out = mutableListOf<String>()
        if (path.isNotBlank() && path != "/") {
            out += "https://$origin/.well-known/oauth-authorization-server$path"
        }
        out += "https://$origin/.well-known/oauth-authorization-server"
        return out
    }

    internal fun parseMetadata(body: String): Metadata? {
        val o = runCatching { JSONObject(body) }.getOrNull() ?: return null
        val auth = o.optString("authorization_endpoint")
        val token = o.optString("token_endpoint")
        if (auth.isBlank() || token.isBlank()) return null
        return Metadata(
            authorizationEndpoint = auth,
            tokenEndpoint = token,
            registrationEndpoint = o.optString("registration_endpoint").takeIf { it.isNotBlank() },
            scopesSupported = o.optJSONArray("scopes_supported")?.let { arr ->
                (0 until arr.length()).mapNotNull { arr.optString(it).takeIf { s -> s.isNotBlank() } }
            } ?: emptyList(),
        )
    }

    internal fun buildAuthorizeUrl(
        authorizationEndpoint: String,
        clientId: String,
        redirectUri: String,
        state: String,
        codeChallenge: String,
        scope: String,
        resource: String,
    ): String {
        val params = listOf(
            "response_type" to "code",
            "client_id" to clientId,
            "redirect_uri" to redirectUri,
            "state" to state,
            "code_challenge" to codeChallenge,
            "code_challenge_method" to "S256",
            "resource" to resource,
        ) + (if (scope.isNotBlank()) listOf("scope" to scope) else emptyList())
        val sep = if (authorizationEndpoint.contains('?')) "&" else "?"
        return authorizationEndpoint + sep + params.joinToString("&") { (k, v) ->
            "${urlEncode(k)}=${urlEncode(v)}"
        }
    }

    internal data class CallbackData(
        val code: String?,
        val state: String?,
        val error: String?,
        val errorDescription: String?,
    )

    /** 解析回调 URL 的 query 参数（兼容 `#fragment` 形态 —— 部分实现把参数放 fragment 里） */
    internal fun parseCallback(url: String): CallbackData? {
        val raw: String = try {
            val uri = URI(url)
            uri.rawQuery ?: uri.fragment ?: return null
        } catch (_: Exception) {
            return null
        }
        val params = raw.split('&').mapNotNull {
            val i = it.indexOf('=')
            if (i <= 0) null else
                URLDecoder.decode(it.substring(0, i), "UTF-8") to
                    URLDecoder.decode(it.substring(i + 1), "UTF-8")
        }.toMap()
        return CallbackData(
            code = params["code"],
            state = params["state"],
            error = params["error"],
            errorDescription = params["error_description"],
        )
    }

    /** code/refresh 换 token 的响应解析；缺 access_token 返回 null */
    internal fun parseTokenResponse(body: String): TokenResponse? {
        val o = runCatching { JSONObject(body) }.getOrNull() ?: return null
        val access = o.optString("access_token")
        if (access.isBlank()) return null
        val expiresIn = o.optLong("expires_in", -1L)
        return TokenResponse(
            accessToken = access,
            refreshToken = o.optString("refresh_token").takeIf { it.isNotBlank() },
            expiresInMs = if (expiresIn > 0) expiresIn * 1000L else -1L,
        )
    }

    /** OAuth 错误响应里的 `error_description` / `error` 提取；解析不出退回正文前 200 字符 */
    internal fun oauthErrorMessage(body: String): String = try {
        val o = JSONObject(body)
        o.optString("error_description").ifBlank {
            o.optString("error").ifBlank { body.take(200) }
        }
    } catch (_: Exception) {
        body.take(200)
    }

    /** PKCE verifier：32 字节随机 → base64url（43 字符，RFC 7636 允许 43~128） */
    internal fun generateVerifier(): String = randomToken(32)

    /** S256 challenge = base64url(sha256(verifier))，无 padding */
    internal fun s256Challenge(verifier: String): String =
        Base64.getUrlEncoder().withoutPadding()
            .encodeToString(
                MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII)),
            )

    internal fun flowJson(flow: PendingFlow): JSONObject = JSONObject()
        .put("serverId", flow.serverId)
        .put("serverName", flow.serverName)
        .put("verifier", flow.verifier)
        .put("state", flow.state)
        .put("clientId", flow.clientId)
        .put("tokenEndpoint", flow.tokenEndpoint)
        .put("resource", flow.resource)

    internal fun flowFromJson(o: JSONObject?): PendingFlow? {
        if (o == null) return null
        val serverId = o.optString("serverId")
        val verifier = o.optString("verifier")
        val state = o.optString("state")
        val clientId = o.optString("clientId")
        val tokenEndpoint = o.optString("tokenEndpoint")
        if (serverId.isBlank() || verifier.isBlank() || state.isBlank() ||
            clientId.isBlank() || tokenEndpoint.isBlank()
        ) return null
        return PendingFlow(
            serverId = serverId,
            serverName = o.optString("serverName"),
            verifier = verifier,
            state = state,
            clientId = clientId,
            tokenEndpoint = tokenEndpoint,
            resource = o.optString("resource"),
        )
    }

    private fun TokenResponse.toTokens(clientId: String): Tokens = Tokens(
        accessToken = accessToken,
        refreshToken = refreshToken,
        expiresAtMs = if (expiresInMs > 0) System.currentTimeMillis() + expiresInMs else Long.MAX_VALUE,
        clientId = clientId,
    )

    // ═══════════════════ 内部 ═══════════════════

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun accessToken(ctx: Context, serverId: String): String? =
        SecretStore.get(prefs(ctx), KEY_ACCESS + serverId)

    private fun storeTokens(ctx: Context, serverId: String, tokens: Tokens) {
        SecretStore.put(prefs(ctx), KEY_ACCESS + serverId, tokens.accessToken)
        tokens.refreshToken?.let { SecretStore.put(prefs(ctx), KEY_REFRESH + serverId, it) }
        prefs(ctx).edit()
            .putLong(KEY_EXPIRES_AT + serverId, tokens.expiresAtMs)
            .putString(KEY_CLIENT_ID + serverId, tokens.clientId)
            .apply()
    }

    private fun clearTokens(ctx: Context, serverId: String) {
        prefs(ctx).edit()
            .remove(KEY_ACCESS + serverId)
            .remove(KEY_REFRESH + serverId)
            .remove(KEY_EXPIRES_AT + serverId)
            .apply()
    }

    private fun clearPending(ctx: Context) {
        SecretStore.put(prefs(ctx), KEY_PENDING, null)
    }

    private fun loadPending(ctx: Context): PendingFlow? {
        val raw = SecretStore.get(prefs(ctx), KEY_PENDING) ?: return null
        return flowFromJson(runCatching { JSONObject(raw) }.getOrNull())
    }

    private fun randomToken(bytes: Int): String {
        val buf = ByteArray(bytes)
        SecureRandom().nextBytes(buf)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(buf)
    }

    private fun urlEncode(s: String): String = URLEncoder.encode(s, "UTF-8")
}
