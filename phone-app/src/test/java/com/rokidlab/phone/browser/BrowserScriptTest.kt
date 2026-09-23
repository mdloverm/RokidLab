package com.rokidlab.phone.browser

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 页面快照**给模型看的那段文本**的格式契约。
 *
 * 模型靠这段文本决定"点哪个编号、还要不要继续往下滚"。它一旦漂移（编号丢了、正文空了、
 * 滚动提示没了），真机上的表现是"点了没反应/内容只读了一半"，而**不报任何错** ——
 * 所以用一个纯函数测试把它钉住。
 */
class BrowserScriptTest {

    private fun snapshot(
        title: String = "示例页",
        url: String = "https://example.com/a",
        text: String = "这是正文",
        items: JSONArray = JSONArray(),
        y: Int = 0,
        h: Int = 800,
        vh: Int = 800,
    ): String = JSONObject()
        .put("url", url)
        .put("title", title)
        .put("text", text)
        .put("items", items)
        .put("y", y)
        .put("h", h)
        .put("vh", vh)
        .toString()

    private fun item(ref: Int, tag: String, type: String, text: String, href: String = ""): JSONObject =
        JSONObject().put("ref", ref).put("tag", tag).put("type", type).put("text", text).put("href", href)

    @Test
    fun `快照包含标题_网址_正文与带编号的可交互元素`() {
        val items = JSONArray()
            .put(item(1, "a", "", "更多", "https://example.com/b"))
            .put(item(2, "input", "password", ""))
            .put(item(3, "button", "", "提交"))
        val out = BrowserScript.format(snapshot(items = items))

        assertTrue(out.contains("标题：示例页"))
        assertTrue(out.contains("网址：https://example.com/a"))
        assertTrue(out.contains("这是正文"))
        // 编号必须原样透出（模型就是按它调 browser_click）
        assertTrue(out.contains("[1] 链接「更多」 → https://example.com/b"))
        assertTrue(out.contains("[2] 密码框"))
        assertTrue(out.contains("[3] 按钮「提交」"))
    }

    @Test
    fun `没有可交互元素时如实说明`() {
        val out = BrowserScript.format(snapshot())
        assertTrue(out.contains("没有找到可点击/可输入的元素"))
    }

    @Test
    fun `正文为空时不留下一个空洞`() {
        val out = BrowserScript.format(snapshot(text = ""))
        assertTrue(out.contains("（正文为空）"))
    }

    @Test
    fun `页面还有下文时提示可以继续滚动`() {
        val out = BrowserScript.format(snapshot(y = 0, h = 3000, vh = 800))
        assertTrue(out.contains("滚动位置：0/2200"))
        assertTrue(out.contains("下面还有内容"))
    }

    @Test
    fun `滚到底时明确告知已到底`() {
        val out = BrowserScript.format(snapshot(y = 2200, h = 3000, vh = 800))
        assertTrue(out.contains("（已到底）"))
    }

    @Test
    fun `页面不需要滚动时不输出滚动位置`() {
        val out = BrowserScript.format(snapshot(y = 0, h = 800, vh = 800))
        assertTrue(!out.contains("滚动位置"))
    }

    @Test
    fun `读不到或解析失败时给出可行动的话_而不是空串`() {
        listOf(null, "null", "不是 JSON").forEach { raw ->
            val out = BrowserScript.format(raw)
            assertTrue("raw=$raw 时应有如实说明", out.contains("读取页面内容失败"))
            assertTrue("要告诉模型下一步怎么做", out.contains("browser_snapshot"))
        }
    }

    @Test
    fun `内容过长时截断并注明`() {
        val out = BrowserScript.format(snapshot(text = "长".repeat(20000)))
        assertTrue(out.endsWith("（内容过长已截断）"))
        assertTrue("截断后应显著短于原文", out.length < 6000)
    }

    // ═══════════ 注入到页面的 JS（拼装正确性） ═══════════

    @Test
    fun `每段脚本都注入 LIB 并用 JSON_stringify 收回结构化结果`() {
        val scripts = listOf(
            BrowserScript.snapshot(),
            BrowserScript.click(3),
            BrowserScript.type(3, "hello", true),
            BrowserScript.scroll("down"),
        )
        scripts.forEach { js ->
            assertTrue("必须以幂等定义 window__lab 开头", js.startsWith("(function(){"))
            assertTrue("必须用 JSON.stringify 把结果变成字符串回传", js.contains("JSON.stringify("))
        }
        assertTrue(BrowserScript.click(3).contains("window.__lab.click(3)"))
        assertTrue(BrowserScript.scroll("down").contains("window.__lab.scroll('down')"))
    }

    @Test
    fun `输入文本会被转义_不会破坏 JS 源码`() {
        val js = BrowserScript.type(1, "he said \"hi\"\n'ok'", true)
        assertTrue("引号与换行必须被转义", js.contains("\\\"") && js.contains("\\n"))
        assertTrue(js.contains("window.__lab.type(1,"))
    }
}
