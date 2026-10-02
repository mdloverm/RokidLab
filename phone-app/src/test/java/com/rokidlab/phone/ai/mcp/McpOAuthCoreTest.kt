package com.rokidlab.phone.ai.mcp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64
import java.security.MessageDigest

/**
 * McpOAuth 的纯函数层契约（零 Android 依赖，见 McpOAuth 的"纯函数层"分区）。
 *
 * OAuth 最危险的失败方式都是**静默放行**类：state 不校验、challenge 算错导致所有
 * server 拒绝、回调解析把 error 当成功。这里把这几个点钉死。
 */
class McpOAuthCoreTest {

    // ═══════════ 元数据发现 ═══════════

    @Test
    fun `wellKnown 候选：路径感知变体优先，根变体兜底`() {
        val candidates = McpOAuth.wellKnownCandidates("https://mcp.example.com/api/v1/mcp")
        assertEquals(
            listOf(
                "https://mcp.example.com/.well-known/oauth-authorization-server/api/v1/mcp",
                "https://mcp.example.com/.well-known/oauth-authorization-server",
            ),
            candidates,
        )
    }

    @Test
    fun `wellKnown 候选：单段路径端点同样产出路径感知变体`() {
        // /mcp 也是路径：RFC 8414 路径感知变体在前，根变体兜底在后
        assertEquals(
            listOf(
                "https://mcp.example.com/.well-known/oauth-authorization-server/mcp",
                "https://mcp.example.com/.well-known/oauth-authorization-server",
            ),
            McpOAuth.wellKnownCandidates("https://mcp.example.com/mcp"),
        )
        // 只有 origin（无路径）⇒ 只有根变体一份
        assertEquals(
            listOf("https://mcp.example.com/.well-known/oauth-authorization-server"),
            McpOAuth.wellKnownCandidates("https://mcp.example.com"),
        )
    }

    @Test
    fun `元数据解析：缺 authorization 或 token 端点都不算有效元数据`() {
        val full = "{\"authorization_endpoint\":\"https://a/auth\"," +
            "\"token_endpoint\":\"https://a/token\"," +
            "\"registration_endpoint\":\"https://a/register\"," +
            "\"scopes_supported\":[\"mcp:read\",\"mcp:write\"]}"
        val m = McpOAuth.parseMetadata(full)
        assertNotNull(m)
        assertEquals("https://a/auth", m!!.authorizationEndpoint)
        assertEquals("https://a/token", m.tokenEndpoint)
        assertEquals("https://a/register", m.registrationEndpoint)
        assertEquals(listOf("mcp:read", "mcp:write"), m.scopesSupported)

        assertNull(McpOAuth.parseMetadata("{\"token_endpoint\":\"https://a/token\"}"))
        assertNull(McpOAuth.parseMetadata("{\"authorization_endpoint\":\"https://a/auth\"}"))
        assertNull(McpOAuth.parseMetadata("not json at all"))
    }

    // ═══════════ 授权 URL 与回调 ═══════════

    @Test
    fun `授权 URL：参数齐全、resource 带上、无 scope 时不带 scope 参数`() {
        val url = McpOAuth.buildAuthorizeUrl(
            authorizationEndpoint = "https://a.example.com/authorize",
            clientId = "client-1",
            redirectUri = McpOAuth.REDIRECT_URI,
            state = "st-123",
            codeChallenge = "challenge-abc",
            scope = "",
            resource = "https://mcp.example.com/mcp",
        )
        assertTrue(url.startsWith("https://a.example.com/authorize?"))
        assertTrue(url.contains("response_type=code"))
        assertTrue(url.contains("client_id=client-1"))
        assertTrue(url.contains("redirect_uri="))
        assertTrue(url.contains("state=st-123"))
        assertTrue(url.contains("code_challenge=challenge-abc"))
        assertTrue(url.contains("code_challenge_method=S256"))
        assertTrue(url.contains("resource="))
        // 无 scope ⇒ 不出 scope 参数（空参数会误导某些 server）
        assertFalse(url.contains("scope"))
        // 深链里的 "://" 必须被编码，否则 URL 解析会把它当 scheme
        assertFalse(url.contains("redirect_uri=rokidlab://"))
    }

    @Test
    fun `授权 URL：端点自带 query 时用 & 追加`() {
        val url = McpOAuth.buildAuthorizeUrl(
            authorizationEndpoint = "https://a/auth?tenant=x",
            clientId = "c", redirectUri = "r", state = "s",
            codeChallenge = "cc", scope = "mcp", resource = "res",
        )
        assertTrue(url.contains("?tenant=x&"))
    }

    @Test
    fun `回调解析：标准 query 与 fragment 形态都能取参，error 优先暴露`() {
        val ok = McpOAuth.parseCallback("rokidlab://mcp/callback?code=xyz&state=st-9")
        assertEquals("xyz", ok!!.code)
        assertEquals("st-9", ok.state)
        assertNull(ok.error)

        // 部分实现把参数放 fragment
        val frag = McpOAuth.parseCallback("rokidlab://mcp/callback#code=f1&state=st-9")
        assertEquals("f1", frag!!.code)

        val denied = McpOAuth.parseCallback(
            "rokidlab://mcp/callback?error=access_denied&error_description=user%20said%20no",
        )
        assertEquals("access_denied", denied!!.error)
        assertEquals("user said no", denied.errorDescription)

        assertNull(McpOAuth.parseCallback("rokidlab://mcp/callback"))
        assertNull(McpOAuth.parseCallback("::::"))
    }

    // ═══════════ PKCE ═══════════

    @Test
    fun `PKCE：verifier 在 RFC 长度区间内且不含 URL 不安全字符，challenge 可复算`() {
        val v = McpOAuth.generateVerifier()
        // 32 字节 → base64url = 43 字符（RFC 7636 下限，合规）
        assertEquals(43, v.length)
        assertTrue(v.matches(Regex("[A-Za-z0-9_-]+")))

        val challenge = McpOAuth.s256Challenge(v)
        assertEquals(43, challenge.length)
        // S256 正确性：按定义复算一遍
        val expect = Base64.getUrlEncoder().withoutPadding()
            .encodeToString(MessageDigest.getInstance("SHA-256").digest(v.toByteArray(Charsets.US_ASCII)))
        assertEquals(expect, challenge)
        // 两个 verifier 不得碰撞（随机源活跃性）
        assertTrue(McpOAuth.generateVerifier() != McpOAuth.generateVerifier())
    }

    // ═══════════ token 响应 ═══════════

    @Test
    fun `token 响应：缺 access_token 判失败，refresh 可选，expires_in 换算毫秒`() {
        val r = McpOAuth.parseTokenResponse(
            "{\"access_token\":\"at\",\"refresh_token\":\"rt\",\"token_type\":\"Bearer\",\"expires_in\":3600}",
        )
        assertNotNull(r)
        assertEquals("at", r!!.accessToken)
        assertEquals("rt", r.refreshToken)
        assertEquals(3_600_000L, r.expiresInMs)

        assertNull(McpOAuth.parseTokenResponse("{\"error\":\"invalid_grant\"}"))
        assertNull(McpOAuth.parseTokenResponse("plain text"))

        // expires_in 缺失 → -1（上层落到 MAX_VALUE，靠 401 兜底）
        val noExp = McpOAuth.parseTokenResponse("{\"access_token\":\"at\"}")
        assertEquals(-1L, noExp!!.expiresInMs)
    }

    @Test
    fun `错误信息提取：error_description 优先，非 JSON 退回原文截断`() {
        assertEquals(
            "token revoked",
            McpOAuth.oauthErrorMessage("{\"error\":\"invalid_grant\",\"error_description\":\"token revoked\"}"),
        )
        assertEquals("invalid_grant", McpOAuth.oauthErrorMessage("{\"error\":\"invalid_grant\"}"))
        assertEquals("boom body", McpOAuth.oauthErrorMessage("boom body"))
    }

    // ═══════════ 待办授权流的序列化 ═══════════

    @Test
    fun `pending 流 JSON 往返无损，缺关键字段判无效`() {
        val flow = McpOAuth.PendingFlow(
            serverId = "mcp-1",
            serverName = "Example",
            verifier = "v".repeat(43),
            state = "s".repeat(22),
            clientId = "client-1",
            tokenEndpoint = "https://a/token",
            resource = "https://mcp.example.com/mcp",
        )
        val round = McpOAuth.flowFromJson(McpOAuth.flowJson(flow))
        assertEquals(flow, round)

        // 缺任意关键字段（serverId/verifier/state/clientId/tokenEndpoint）⇒ null，
        // 防止半截数据进 state 校验逻辑造成误放行
        val broken = org.json.JSONObject(McpOAuth.flowJson(flow).toString()).remove("verifier")
        assertNull(McpOAuth.flowFromJson(broken as? org.json.JSONObject))
        assertNull(McpOAuth.flowFromJson(null))
    }
}
