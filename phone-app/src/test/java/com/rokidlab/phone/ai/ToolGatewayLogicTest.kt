package com.rokidlab.phone.ai

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * AIUI 页面工具网关的**纯逻辑**回归测试（[ToolGateway.precheckToolCall] / [ToolGateway.truncateResult]）。
 *
 * 为什么值得锁：
 * 1. 拒绝文案被页面侧按**字面**分支处理（Precheck doc 注明"勿改动"）——
 *    文案一旦漂移，页面上表现为无信息的超时/解析失败，极难排查。
 * 2. 域白名单 + 黑名单是 AIUI 页面的安全边界（isEnabled 总开关之外的第二道闸），
 *    任何重构（如换工具注册方式）都不得放行 open_aiui_app 自指递归。
 * 3. 截断若从 UTF-16 代理对中间切断，下行 JSON 变非法串，页面 JSON.parse 直接失败。
 */
class ToolGatewayLogicTest {

    /** 显式注入的工具域映射，隔离 ToolRegistry 变动对白名单语义测试的影响 */
    private val domainOf: (String) -> String? = { n ->
        mapOf(
            "play_song" to ToolRegistry.DOMAIN_MEDIA,
            "get_weather" to ToolRegistry.DOMAIN_WEB,
            "get_cover_image" to ToolRegistry.DOMAIN_DISPLAY,
            "open_aiui_app" to ToolRegistry.DOMAIN_AIUI,
        )[n]
    }
    private val known = setOf("play_song", "get_weather", "get_cover_image", "open_aiui_app")

    // ── precheckToolCall：拒绝文案逐字一致 ──

    @Test
    fun `空工具名被拒绝且文案逐字一致`() {
        val r = ToolGateway.precheckToolCall("", "{}", knownTools = known, toolDomain = domainOf)
        assertEquals(ToolGateway.Precheck.Reject("empty tool name"), r)
    }

    @Test
    fun `黑名单工具被拒绝且文案逐字一致`() {
        val r = ToolGateway.precheckToolCall(
            "open_aiui_app", "{}", knownTools = known, toolDomain = domainOf,
        )
        assertEquals(
            ToolGateway.Precheck.Reject("tool 'open_aiui_app' is not allowed in AIUI pages"),
            r,
        )
    }

    @Test
    fun `未知工具被拒绝且文案逐字一致`() {
        val r = ToolGateway.precheckToolCall(
            "no_such_tool", "{}", knownTools = known, toolDomain = domainOf,
        )
        assertEquals(ToolGateway.Precheck.Reject("unknown tool: no_such_tool"), r)
    }

    @Test
    fun `域不在白名单时被拒绝且文案逐字一致`() {
        val r = ToolGateway.precheckToolCall(
            "get_weather", "{}",
            allowedDomains = setOf(ToolRegistry.DOMAIN_MEDIA),
            knownTools = known, toolDomain = domainOf,
        )
        assertEquals(
            ToolGateway.Precheck.Reject(
                "domain '${ToolRegistry.DOMAIN_WEB}' of tool 'get_weather' is not allowed in AIUI pages",
            ),
            r,
        )
    }

    @Test
    fun `工具名在knownTools但域映射缺失时按未知工具拒绝`() {
        // 防御性分支：注册表与域映射不一致时不得放行
        val r = ToolGateway.precheckToolCall(
            "play_song", "{}",
            knownTools = setOf("play_song"),
            toolDomain = { null },
        )
        assertEquals(ToolGateway.Precheck.Reject("unknown tool: play_song"), r)
    }

    // ── precheckToolCall：参数归一化 ──

    @Test
    fun `空参数归一化为空对象`() {
        for (blank in listOf("", "   ", "\n\t")) {
            val r = ToolGateway.precheckToolCall("play_song", blank, knownTools = known, toolDomain = domainOf)
            assertEquals(ToolGateway.Precheck.Ok("{}"), r)
        }
    }

    @Test
    fun `合法JSON对象参数被原样归一化`() {
        val args = "{\"b\":2,\"a\":1}"
        val r = ToolGateway.precheckToolCall("play_song", args, knownTools = known, toolDomain = domainOf)
        assertEquals(ToolGateway.Precheck.Ok(JSONObject(args).toString()), r)
    }

    @Test
    fun `非法JSON参数被拒绝且错误带原因`() {
        val r = ToolGateway.precheckToolCall("play_song", "{bad", knownTools = known, toolDomain = domainOf)
        assertTrue(
            "错误必须以固定前缀开头（页面按前缀感知）",
            r is ToolGateway.Precheck.Reject && r.error.startsWith("arguments is not valid JSON: "),
        )
    }

    @Test
    fun `JSON数组不是合法参数对象`() {
        // ToolRegistry.execute 内部按 JSONObject 解析，数组会导致无信息异常，必须在网关拦截
        val r = ToolGateway.precheckToolCall("play_song", "[1,2]", knownTools = known, toolDomain = domainOf)
        assertTrue(r is ToolGateway.Precheck.Reject && r.error.startsWith("arguments is not valid JSON: "))
    }

    // ── 真实注册表联动（默认参数走 ToolRegistry） ──

    @Test
    fun `真实注册表下 open_aiui_app 仍被拒绝（防自指递归回归）`() {
        val r = ToolGateway.precheckToolCall("open_aiui_app", "{}")
        assertEquals(
            ToolGateway.Precheck.Reject("tool 'open_aiui_app' is not allowed in AIUI pages"),
            r,
        )
    }

    @Test
    fun `真实注册表下已知工具带合法参数可通过`() {
        val r = ToolGateway.precheckToolCall("play_song", "{\"songName\":\"西厢\"}")
        assertTrue("真实注册表中的工具应通过前置校验", r is ToolGateway.Precheck.Ok)
    }

    @Test
    fun `真实注册表下未知工具仍被拒绝`() {
        assertEquals(
            ToolGateway.Precheck.Reject("unknown tool: definitely_not_a_tool"),
            ToolGateway.precheckToolCall("definitely_not_a_tool", "{}"),
        )
    }

    // ── truncateResult ──

    private val MARK = "…(truncated)"

    @Test
    fun `未超限结果原样返回`() {
        val s = "短结果"
        assertEquals(s, ToolGateway.truncateResult("play_song", s))
    }

    @Test
    fun `恰好等于上限的结果原样返回`() {
        val s = "a".repeat(8000)
        assertEquals(s, ToolGateway.truncateResult("play_song", s))
    }

    @Test
    fun `超限结果截断到上限并带截断标记`() {
        val s = "a".repeat(9000)
        val out = ToolGateway.truncateResult("play_song", s)
        assertEquals("a".repeat(8000) + MARK, out)
    }

    @Test
    fun `切点落在代理对中间时回退一字保证代理对完整`() {
        // 7999 个 'a' 后跟一个 emoji（占 2 个 char：index 7999 高代理 + 8000 低代理）+ 尾巴
        val s = "a".repeat(7999) + "\uD83D\uDE00" + "b".repeat(10)
        val out = ToolGateway.truncateResult("play_song", s)
        // 高代理项落在 index 7999（切点前一位）→ end 回退到 7999，emoji 完整落在截断之外
        assertEquals("a".repeat(7999) + MARK, out)
        // 输出不含孤立代理项（JSON 序列化不会产生非法串）
        assertEquals(false, Character.isHighSurrogate(out.dropLast(MARK.length).last()))
    }

    @Test
    fun `代理对完整落在切点之前时无需回退`() {
        // 7998 个 'a' + emoji（index 7998/7999）+ 'b'：index 7999 是低代理，不是高代理
        val s = "a".repeat(7998) + "\uD83D\uDE00" + "b".repeat(10)
        val out = ToolGateway.truncateResult("play_song", s)
        assertEquals("a".repeat(7998) + "\uD83D\uDE00" + MARK, out)
    }

    @Test
    fun `get_cover_image 使用放宽的截断上限`() {
        val s = "x".repeat(40_500)
        val out = ToolGateway.truncateResult("get_cover_image", s)
        assertEquals("封面 data URL 上限 40000，不得按默认 8000 截断", "x".repeat(40_000) + MARK, out)
    }

    @Test
    fun `普通工具同长度结果仍按默认上限截断`() {
        val s = "x".repeat(40_500)
        val out = ToolGateway.truncateResult("play_song", s)
        assertEquals("x".repeat(8000) + MARK, out)
    }
}
