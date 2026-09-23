package com.rokidlab.phone.ai.tools

import android.content.Context
import android.util.Log
import com.rokidlab.phone.R
import com.rokidlab.phone.ai.ToolContentTrust
import com.rokidlab.phone.ai.ToolRegistry
import com.rokidlab.phone.ai.ToolRisk
import com.rokidlab.phone.browser.BrowserGuard
import com.rokidlab.phone.browser.BrowserScript
import com.rokidlab.phone.browser.BrowserSession
import org.json.JSONObject

/**
 * 网页操作域：在**手机上的可见浏览器**里替用户操作真实网站。
 *
 * ## 与 `search_web` / `fetch_webpage` 的关系
 * [WebToolProvider] 那套是无状态 HTTP —— 拿得到正文，但**拿不到登录态、点不了按钮、填不了表单**，
 * 于是"去论坛发个帖""把这个表单提交了"这类请求它做不到。本域补的正是这一段：一个真实的
 * WebView 页面（Cookie / localStorage 持久），配上"读页面 → 点元素 → 填内容"的循环。
 *
 * ## 为什么每一步都要先 `browser_snapshot`
 * 页面元素编号（`[1] [2] …`）只存在于**最近一次快照**的映射表里。SPA 换页、异步刷新后
 * 旧编号立即失效 —— 所以 [TOOL_CLICK] / [TOOL_TYPE] 都会先在页面上校验该编号还在不在，
 * 不在了就如实说"页面已变化，请重新读取"，而不是点到一个看起来对、实则无关的元素。
 *
 * ## 确认粒度：只拦"提交类"动作
 * [TOOL_CLICK] / [TOOL_TYPE] 按**本机副作用**登记（不逐次过审批闸门），把"这一步要不要人命"的
 * 判定收在 [BrowserGuard]：命中提交类文案或落在支付/银行类站点时**不执行**，返回一句
 * 「需要你确认：…」，由模型如实复述给用户、拿到同意后带 `confirmed=true` 重调。
 * 启发式的代价与三层兜底写在该类的注释里，改判定规则时请一并读。
 *
 * ⚠️ 返回值里那句「需要你确认：」是给模型的**分岔信号**，模型侧的处理规则写在下面两个工具的
 * `description` 里（那段是可信指令，而工具返回值会被包进 `untrusted_source` 当数据看）。
 * 改 [BrowserGuard.NEED_CONFIRM_PREFIX] 必须同步改这两处 description。
 */
internal object BrowserToolProvider : ToolProvider {

    private const val TAG = "BrowserToolProvider"

    private const val TOOL_OPEN = "browser_open"
    private const val TOOL_SNAPSHOT = "browser_snapshot"
    private const val TOOL_CLICK = "browser_click"
    private const val TOOL_TYPE = "browser_type"
    private const val TOOL_SCROLL = "browser_scroll"
    private const val TOOL_BACK = "browser_back"

    /** 浏览器没打开时的统一文案（四个"作用于当前页面"的工具共用） */
    private const val NOT_OPEN = "手机浏览器还没打开：先用 browser_open 打开一个网址"

    private val SCROLL_DIRECTIONS = setOf("up", "down", "top", "bottom")

    override val toolNames = setOf(
        TOOL_OPEN, TOOL_SNAPSHOT, TOOL_CLICK, TOOL_TYPE, TOOL_SCROLL, TOOL_BACK,
    )

    override fun tools(): List<ToolEntry> = listOf(
        ToolEntry(
            name = TOOL_OPEN,
            group = ToolRegistry.DOMAIN_BROWSER,
            displayNameRes = R.string.ai_tool_browser_open_name,
            descriptionRes = R.string.ai_tool_browser_open_desc,
            risk = ToolRisk.LOCAL_SIDE_EFFECT,
            statusText = "正在打开网页…",
            schema = toolSchema(
                name = TOOL_OPEN,
                description = "在**手机上的可见浏览器**里打开一个网址（用户能亲眼看到这个页面，也能随时自己接手）。" +
                    "用户说「打开某网站」「去某网页看看」「帮我在某站上…」时用它；" +
                    "只是想读一篇文章的正文、不需要登录或点击，用 fetch_webpage 更省事。" +
                    "⚠️ 需要登录的站点：打开后请让**用户自己在手机上登录**（输账号密码、验证码都由用户完成），" +
                    "不要向用户索要账号密码。登录状态会保存在本机，之后的操作不用重新登录。" +
                    "浏览器已经开着别的页面时会直接在当前窗口导航过去（不会叠出第二个页面）。",
                parameters = mapOf(
                    "type" to "object",
                    "properties" to mapOf(
                        "url" to mapOf(
                            "type" to "string",
                            "description" to "要打开的网址，如 https://example.com（没写 http/https 会自动补 https）",
                        ),
                    ),
                    "required" to listOf("url"),
                ),
            ),
        ),
        ToolEntry(
            name = TOOL_SNAPSHOT,
            group = ToolRegistry.DOMAIN_BROWSER,
            displayNameRes = R.string.ai_tool_browser_snapshot_name,
            descriptionRes = R.string.ai_tool_browser_snapshot_desc,
            risk = ToolRisk.READ_ONLY,
            // 网页正文是典型的不可信外部内容（间接提示词注入的首要攻击面）
            contentTrust = ToolContentTrust.UNTRUSTED_EXTERNAL,
            statusText = "正在读取页面…",
            schema = toolSchema(
                name = TOOL_SNAPSHOT,
                description = "读取**手机浏览器当前页面**的正文和可交互元素（带编号）。" +
                    "看网页内容、以及准备点击/输入之前都要先调它 —— 编号只对最近一次读取有效，页面一变就要重新读。" +
                    "返回里 `[n]` 是可点击/可输入元素的编号，browser_click / browser_type 用这些编号。" +
                    "返回末尾的滚动位置能告诉你页面还有没有没看完的内容（有就继续 browser_scroll 再读一次）。",
                parameters = mapOf(
                    "type" to "object",
                    "properties" to emptyMap<String, Any>(),
                ),
            ),
        ),
        ToolEntry(
            name = TOOL_CLICK,
            group = ToolRegistry.DOMAIN_BROWSER,
            displayNameRes = R.string.ai_tool_browser_click_name,
            descriptionRes = R.string.ai_tool_browser_click_desc,
            risk = ToolRisk.LOCAL_SIDE_EFFECT,
            // 点击可能触发不可逆动作（提交/下单）：瞬时失败不自动重放，由模型决定是否重来
            sideEffect = true,
            // 返回值里带页面提供的元素文案（外部作者可写）
            contentTrust = ToolContentTrust.UNTRUSTED_EXTERNAL,
            statusText = "正在点击网页元素…",
            schema = toolSchema(
                name = TOOL_CLICK,
                description = "点击页面上的一个元素（编号来自最近一次 browser_snapshot）。" +
                    "⚠️ **必须遵守的分岔规则**：若返回值以「需要你确认：」开头，表示这一步可能让人付出代价" +
                    "（提交、发送、支付等），**此时我没有执行这次点击**。你要把这个意思如实转述给用户并停下，" +
                    "等他明确说同意后，再用**完全相同的参数**加上 `confirmed=true` 重新调用一次；" +
                    "用户没同意就不要重试，也不要换个说法或换个元素绕过去。" +
                    "点击后页面可能跳转，继续操作前请重新 browser_snapshot 拿最新编号。",
                parameters = mapOf(
                    "type" to "object",
                    "properties" to mapOf(
                        "ref" to mapOf(
                            "type" to "integer",
                            "description" to "元素编号（browser_snapshot 里方括号中的数字）",
                        ),
                        "confirmed" to mapOf(
                            "type" to "boolean",
                            "description" to "仅当上一次返回以「需要你确认：」开头、且用户已明确同意时才传 true",
                        ),
                    ),
                    "required" to listOf("ref"),
                ),
            ),
        ),
        ToolEntry(
            name = TOOL_TYPE,
            group = ToolRegistry.DOMAIN_BROWSER,
            displayNameRes = R.string.ai_tool_browser_type_name,
            descriptionRes = R.string.ai_tool_browser_type_desc,
            risk = ToolRisk.LOCAL_SIDE_EFFECT,
            // clear=false 时是追加，不幂等
            sideEffect = true,
            contentTrust = ToolContentTrust.UNTRUSTED_EXTERNAL,
            statusText = "正在填写网页…",
            schema = toolSchema(
                name = TOOL_TYPE,
                description = "在网页的输入框里输入文字（编号来自最近一次 browser_snapshot）。" +
                    "默认清空原内容再填；要接在原有内容后面就传 clear=false。" +
                    "输入**不会自动提交**，需要提交时再用 browser_click 点提交按钮。" +
                    "⚠️ 与 browser_click 相同的分岔规则：返回值以「需要你确认：」开头时（例如往密码框输入）" +
                    "说明我没有执行，先把这句话如实告诉用户，得到明确同意后再带 `confirmed=true` 重调。" +
                    "需要登录时账号密码请让用户自己在页面上输入，不要向用户索要。",
                parameters = mapOf(
                    "type" to "object",
                    "properties" to mapOf(
                        "ref" to mapOf(
                            "type" to "integer",
                            "description" to "输入框编号（browser_snapshot 里方括号中的数字）",
                        ),
                        "text" to mapOf(
                            "type" to "string",
                            "description" to "要输入的文字。用户没给具体内容时先问他，不要自己编。",
                        ),
                        "clear" to mapOf(
                            "type" to "boolean",
                            "description" to "true（默认）= 清空后重填；false = 追加到原有内容后面",
                        ),
                        "confirmed" to mapOf(
                            "type" to "boolean",
                            "description" to "仅当上一次返回以「需要你确认：」开头、且用户已明确同意时才传 true",
                        ),
                    ),
                    "required" to listOf("ref", "text"),
                ),
            ),
        ),
        ToolEntry(
            name = TOOL_SCROLL,
            group = ToolRegistry.DOMAIN_BROWSER,
            displayNameRes = R.string.ai_tool_browser_scroll_name,
            descriptionRes = R.string.ai_tool_browser_scroll_desc,
            risk = ToolRisk.LOCAL_SIDE_EFFECT,
            statusText = "正在滚动网页…",
            schema = toolSchema(
                name = TOOL_SCROLL,
                description = "上下滚动当前网页，用来读没显示完的内容。" +
                    "返回新的滚动位置：到底了就别再往下滚，改用 browser_snapshot 读当前可见内容。" +
                    "滚动不改动页面，也不影响已读到的编号，可以连续调用。",
                parameters = mapOf(
                    "type" to "object",
                    "properties" to mapOf(
                        "direction" to mapOf(
                            "type" to "string",
                            "enum" to listOf("up", "down", "top", "bottom"),
                            "description" to "滚动方向：down 往下翻一屏、up 往上翻一屏、top 回到页首、bottom 到页尾",
                        ),
                    ),
                    "required" to listOf("direction"),
                ),
            ),
        ),
        ToolEntry(
            name = TOOL_BACK,
            group = ToolRegistry.DOMAIN_BROWSER,
            displayNameRes = R.string.ai_tool_browser_back_name,
            descriptionRes = R.string.ai_tool_browser_back_desc,
            risk = ToolRisk.LOCAL_SIDE_EFFECT,
            statusText = "正在返回上一页…",
            schema = toolSchema(
                name = TOOL_BACK,
                description = "让手机浏览器返回上一页（等价于在页面上按返回）。" +
                    "用户说「退回去」「回上一页」「刚才那个页面」时用它。" +
                    "已经在第一页时会如实告诉你没有上一页 —— 此时不要改用 browser_open 重新打开来" +
                    "假装后退成功。",
                parameters = mapOf(
                    "type" to "object",
                    "properties" to emptyMap<String, Any>(),
                ),
            ),
        ),
    )

    override fun execute(context: Context, name: String, args: JSONObject): String {
        return when (name) {
            TOOL_OPEN -> open(context, args)
            TOOL_SNAPSHOT -> snapshot()
            TOOL_CLICK -> click(args)
            TOOL_TYPE -> type(args)
            TOOL_SCROLL -> scroll(args)
            TOOL_BACK -> back()
            else -> throw IllegalArgumentException("未知工具: $name")
        }
    }

    // ═══════════════════ 各工具实现 ═══════════════════

    /** 打开网址：规范化 → 拉起页面 → 等稳定 → 如实报最终地址 */
    private fun open(context: Context, args: JSONObject): String {
        val raw = args.optString("url").trim()
        if (raw.isEmpty()) return "请提供要打开的网址"
        val url = normalizeUrl(raw) ?: return "网址不合法（只支持 http/https）：$raw"
        if (!BrowserSession.open(context, url)) {
            return "没能打开手机上的浏览器页面：请确认乐奇 App 正在运行、能切到前台"
        }
        BrowserSession.waitForSettled(600L)
        val now = BrowserSession.currentUrl().orEmpty().ifBlank { url }
        return "已在手机浏览器打开：$now。接下来用 browser_snapshot 读页面内容；" +
            "若页面要求登录，请让用户自己在手机上登录。"
    }

    /** 读取当前页面（正文 + 编号） */
    private fun snapshot(): String {
        if (!BrowserSession.isOpen()) return NOT_OPEN
        val raw = BrowserSession.evalBlocking(BrowserScript.snapshot())
            ?: return "读取页面失败：页面可能还在加载或已跳转。稍等一下再调 browser_snapshot"
        return BrowserScript.format(raw)
    }

    private fun click(args: JSONObject): String {
        val ref = args.optInt("ref", -1)
        if (ref <= 0) return "请提供要点击的元素编号（就是 browser_snapshot 里的 [n]）"
        if (!BrowserSession.isOpen()) return NOT_OPEN
        val info = inspect(ref) ?: return refGone(ref)
        val url = BrowserSession.currentUrl()
        if (!args.optBoolean("confirmed", false)) {
            val why = BrowserGuard.confirmReason(url, info.tag, info.type, info.label)
            if (why != null) {
                return BrowserGuard.confirmMessage(url, "点击「${info.label.ifBlank { "该元素" }}」", why)
            }
        }
        val raw = BrowserSession.evalBlocking(BrowserScript.click(ref))
        val out = runCatching { JSONObject(raw.orEmpty()) }.getOrNull()
        if (out == null || !out.optBoolean("ok")) return refGone(ref)
        BrowserSession.waitForSettled(400L)
        val label = out.optString("text").ifBlank { info.label }
        return "已点击「$label」。页面可能已经跳转，继续操作前请先 browser_snapshot 重新读一遍。"
    }

    private fun type(args: JSONObject): String {
        val ref = args.optInt("ref", -1)
        if (ref <= 0) return "请提供要输入的编号（就是 browser_snapshot 里的 [n]）"
        val text = args.optString("text")
        if (text.isEmpty()) return "请提供要输入的内容"
        if (!BrowserSession.isOpen()) return NOT_OPEN
        val info = inspect(ref) ?: return refGone(ref)
        val url = BrowserSession.currentUrl()
        if (!args.optBoolean("confirmed", false)) {
            val why = BrowserGuard.confirmReason(url, info.tag, info.type, info.label)
            if (why != null) {
                val where = info.label.ifBlank { "该输入框" }
                return BrowserGuard.confirmMessage(url, "在「$where」里输入内容", why)
            }
        }
        val clear = args.optBoolean("clear", true)
        val raw = BrowserSession.evalBlocking(BrowserScript.type(ref, text, clear))
        val out = runCatching { JSONObject(raw.orEmpty()) }.getOrNull()
        if (out == null || !out.optBoolean("ok")) return refGone(ref)
        val where = info.label.ifBlank { "该输入框" }
        // 密码内容一律不回显：工具结果会进对话历史、日志与模型上下文
        if (info.type == "password") {
            return "已在「$where」输入密码（内容不回显）。输入不会自动提交，需要提交时用 browser_click 点提交按钮。"
        }
        val filled = out.optString("value").ifBlank { text }
        return "已在「$where」输入：$filled。输入不会自动提交，需要提交时用 browser_click 点提交按钮。"
    }

    private fun scroll(args: JSONObject): String {
        val dir = args.optString("direction").trim().lowercase()
        if (dir !in SCROLL_DIRECTIONS) return "direction 只能是 up / down / top / bottom"
        if (!BrowserSession.isOpen()) return NOT_OPEN
        val raw = BrowserSession.evalBlocking(BrowserScript.scroll(dir))
        val out = runCatching { JSONObject(raw.orEmpty()) }.getOrNull()
            ?: return "滚动失败：读不到页面位置，稍等一下再试"
        val y = out.optInt("y")
        val max = (out.optInt("h") - out.optInt("vh")).coerceAtLeast(0)
        val state = when {
            max <= 0 -> "这一页不需要滚动"
            y <= 4 -> "已经在页面顶部（位置 $y/$max）"
            y >= max - 4 -> "已经到了页面底部（位置 $y/$max）"
            else -> "已滚动，位置 $y/$max（上下都还有内容）"
        }
        return "$state。用 browser_snapshot 读当前可见的内容。"
    }

    private fun back(): String {
        if (!BrowserSession.isOpen()) return NOT_OPEN
        if (!BrowserSession.goBack()) return "已经是第一页了，没有上一页可退"
        BrowserSession.waitForSettled(400L)
        val url = BrowserSession.currentUrl()
        return "已返回上一页" + (if (url.isNullOrBlank()) "" else "：$url") +
            "。需要看内容请调 browser_snapshot。"
    }

    // ═══════════════════ 内部辅助 ═══════════════════

    /** 编号对应的元素信息（点击/输入前的风险判定要用） */
    private data class RefInfo(val tag: String, val type: String, val label: String)

    /** 只读探察该编号还在不在、是什么；null = 读不到（页面没就绪或编号已失效） */
    private fun inspect(ref: Int): RefInfo? {
        val raw = BrowserSession.evalBlocking(BrowserScript.inspect(ref)) ?: return null
        val obj = runCatching { JSONObject(raw) }.getOrNull() ?: return null
        if (!obj.optBoolean("ok")) return null
        return RefInfo(obj.optString("tag"), obj.optString("type"), obj.optString("text"))
    }

    private fun refGone(ref: Int): String =
        "页面上找不到编号 $ref 的元素：页面可能已经变化，请重新调 browser_snapshot 拿最新编号再试"

    /**
     * 把用户/模型给的网址规范成可加载的 http(s) URL；不合法返回 null。
     *
     * 只放行 http/https：`file://` 能读到本机文件、`javascript:` 能直接执行脚本，
     * 都不该由"打开一个网址"顺带获得（[com.rokidlab.phone.browser.BrowserActivity] 的
     * 导航闸门同样只放行 http/about —— 两处是同一策略的两道）。
     */
    private fun normalizeUrl(raw: String): String? {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return null
        val hasScheme = trimmed.startsWith("http://", ignoreCase = true) ||
            trimmed.startsWith("https://", ignoreCase = true)
        val candidate = if (hasScheme) trimmed else "https://" + trimmed.trimStart('/')
        val uri = runCatching { java.net.URI(candidate) }.getOrNull() ?: return null
        val scheme = uri.scheme?.lowercase()
        if (scheme != "http" && scheme != "https") return null
        if (uri.host.isNullOrBlank()) return null
        Log.i(TAG, "normalizeUrl: $raw -> $candidate")
        return candidate
    }
}
