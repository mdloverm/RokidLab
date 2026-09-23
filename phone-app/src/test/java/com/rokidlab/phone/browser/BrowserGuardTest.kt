package com.rokidlab.phone.browser

import com.rokidlab.phone.ai.ToolRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 网页操作的**动作风险判定**回归测试。
 *
 * 这一层是 `browser_click` / `browser_type` 唯一的"要不要人过目"闸门：它们按本机副作用登记，
 * 不过审批闸门，所以判定一旦失灵，就是**静默**地把提交动作执行出去。两个方向都要钉住：
 *  - 漏报（该确认的没确认）：提交/支付/密码类必须命中；
 *  - 误报（不该确认的确认了）：登录、搜索、翻页这类高频动作必须放过 —— 若每步都弹确认，
 *    模型会去找绕过办法，反而更危险。
 */
class BrowserGuardTest {

    // ═══════════ 必须确认 ═══════════

    @Test
    fun `提交类按钮文案命中确认`() {
        listOf("发送", "提交", "发布", "立即购买", "确认支付", "删除", "Send", "Submit", "Sign Up")
            .forEach { label ->
                assertTrue(
                    "「$label」应当要求确认",
                    BrowserGuard.needsConfirm("https://example.com/post", "button", "", label),
                )
            }
    }

    @Test
    fun `原生提交控件即使没有文案也命中`() {
        assertTrue(BrowserGuard.needsConfirm("https://example.com", "input", "submit", ""))
        assertTrue(BrowserGuard.needsConfirm("https://example.com", "input", "image", ""))
    }

    @Test
    fun `密码框命中确认`() {
        assertNotNull(BrowserGuard.confirmReason("https://example.com", "input", "password", ""))
    }

    @Test
    fun `支付与银行类站点整站命中_不看按钮文案`() {
        listOf(
            "https://www.alipay.com/x",
            "https://pay.example.com/order",
            "https://www.icbc.com.cn/icbc/",
            "https://shop.example.com/checkout/step2",
            "https://example.com/pay/confirm",
        ).forEach { url ->
            assertTrue("$url 应当要求确认", BrowserGuard.needsConfirm(url, "a", "", "下一步"))
        }
    }

    // ═══════════ 不该确认（误报会让正常流程每步一问） ═══════════

    @Test
    fun `登录搜索翻页这类高频动作不确认`() {
        listOf("登录", "搜索", "下一页", "关注", "查看更多", "Log in", "Search", "Next")
            .forEach { label ->
                assertFalse(
                    "「$label」不该要求确认",
                    BrowserGuard.needsConfirm("https://example.com/list", "a", "", label),
                )
            }
    }

    @Test
    fun `普通链接不确认`() {
        assertNull(BrowserGuard.confirmReason("https://example.com/a", "a", "", "某篇文章"))
    }

    @Test
    fun `URL 为空时不误判为敏感站点`() {
        assertFalse(BrowserGuard.isSensitiveTarget(null))
        assertFalse(BrowserGuard.isSensitiveTarget(""))
        assertNull(BrowserGuard.confirmReason(null, "button", "", "登录"))
    }

    // ═══════════ 话术与模型侧的耦合 ═══════════

    @Test
    fun `确认文案以约定前缀开头并带上站点`() {
        val msg = BrowserGuard.confirmMessage(
            "https://www.example.com/post/new",
            action = "点击「发布」",
            why = "它的文案是「发布」，属于提交类动作",
        )
        assertTrue("必须以 NEED_CONFIRM_PREFIX 开头，模型按它分岔", msg.startsWith(BrowserGuard.NEED_CONFIRM_PREFIX))
        assertTrue("要让用户看出是哪个站", msg.contains("www.example.com"))
        assertTrue("要说明为什么", msg.contains("提交类动作"))
    }

    /**
     * 前缀是模型侧的分岔信号，而"看到这个前缀该怎么办"写在工具 schema 的 description 里
     * （见 [com.rokidlab.phone.ai.tools.BrowserToolProvider]）。两处不同步 = 模型看到信号却不知道
     * 该先问用户，会直接重试或换元素绕过 —— 这个测试把两者钉在一起。
     */
    @Test
    fun `会触发确认的两个工具_description 都写明了该前缀的分岔规则`() {
        listOf("browser_click", "browser_type").forEach { name ->
            val meta = ToolRegistry.toolList.firstOrNull { it.name == name }
            assertNotNull("工具 $name 应已注册", meta)
            val desc = ToolRegistry.buildSchema(meta!!)
                .getJSONObject("function")
                .getString("description")
            assertTrue(
                "「$name」的描述里必须出现前缀「${BrowserGuard.NEED_CONFIRM_PREFIX}」，否则模型不知道该怎么处理",
                desc.contains(BrowserGuard.NEED_CONFIRM_PREFIX),
            )
            assertTrue(
                "「$name」的描述里必须告诉模型得到用户同意后要带 confirmed=true 重调",
                desc.contains("confirmed=true"),
            )
        }
    }

    @Test
    fun `浏览器工具都登记在 browser 域`() {
        val names = listOf(
            "browser_open", "browser_snapshot", "browser_click",
            "browser_type", "browser_scroll", "browser_back",
        )
        names.forEach { name ->
            val meta = ToolRegistry.toolList.firstOrNull { it.name == name }
            assertNotNull("工具 $name 应已注册", meta)
            assertEquals("$name 的域不对", ToolRegistry.DOMAIN_BROWSER, meta!!.group)
        }
    }
}
