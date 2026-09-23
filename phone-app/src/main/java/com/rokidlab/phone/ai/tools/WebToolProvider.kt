package com.rokidlab.phone.ai.tools

import com.rokidlab.phone.R
import com.rokidlab.phone.ai.ToolContentTrust
import com.rokidlab.phone.ai.ToolRisk

import android.content.Context
import android.util.Log
import com.rokidlab.phone.ai.AiuiAppRegistry
import com.rokidlab.phone.ai.AiuiProject
import com.rokidlab.phone.ai.Calculator
import com.rokidlab.phone.ai.KnowledgeBase
import com.rokidlab.phone.ai.KuwoMusicApi
import com.rokidlab.phone.ai.LocationTools
import com.rokidlab.phone.ai.MusicPlayerController
import com.rokidlab.phone.ai.PhoneTools
import com.rokidlab.phone.ai.ToolRegistry
import com.rokidlab.phone.ai.WeatherTools
import com.rokidlab.phone.ai.WebTools
import com.rokidlab.phone.app.LabApplication
import com.rokidlab.phone.platform.ProotShell
import com.rokidlab.phone.adb.ui.TimerAction
import com.rokidlab.phone.adb.ui.TimerSchedule
import com.rokidlab.phone.adb.ui.TimerTask
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.UUID

/**
 * WebToolProvider —— 联网域（搜索 / 读网页 / 调接口 / 下载文件）。
 * Phase 4 从 `ToolRegistry.execute` 迁出的该域工具执行分支（逐字搬运，行为不变）。
 *
 * ## 为什么 `http_request` / `download_file` 是 [ToolRisk.LOCAL_SIDE_EFFECT] 而不是 EXTERNAL
 * 这两个工具能 POST 到第三方、能往用户存储写文件，直觉上像"外部副作用"。判据是
 * **「与已有的同类能力相比有没有变宽」**：`run_shell`（[ToolRisk.LOCAL_SIDE_EFFECT]）里的
 * `curl -X POST` / `curl -o` 早就能做同样的事，风险面**严格更窄**（固定 method 白名单、
 * 响应体上限、下载体积上限、不落 shell）。反过来若按 EXTERNAL 登记，**每次查一个公开接口
 * 都要用户确认**——眼镜在线时打断、本机模式下在手机上弹窗——这正是 `save_code_file`
 * 当年那个事故的形状（**用错档位本身造出来的故障**）。
 * 真正需要用户确认的第三方不可逆动作（拨号、发短信）仍按 EXTERNAL 登记。
 *
 * ⚠️ **不要**写成"本机模式下会被 `GlassesDependencyGuard` 直接拒"（旧注释如此，是错的）：
 * 那个 guard 的判据是 `localOnly && requiresGlasses`，只管**眼镜依赖**的工具；
 * 这两个工具不是眼镜依赖工具，本机模式下**照样会走到确认闸门**（由手机通道问）。
 * 所以"会被直接拒"从来不是这两个工具留在 LOCAL 的理由，理由只有上面那条"风险面更窄"。
 */
internal object WebToolProvider : ToolProvider {
    private const val TAG = "WebToolProvider"

    override val toolNames = setOf(
        "search_web",
        "fetch_webpage",
        "http_request",
        "download_file",
    )

    override fun tools(): List<ToolEntry> = listOf(
        ToolEntry(
            name = "search_web",
            group = ToolRegistry.DOMAIN_WEB,
            displayNameRes = R.string.ai_tool_search_web_name,
            descriptionRes = R.string.ai_tool_search_web_desc,
            risk = ToolRisk.READ_ONLY,
            // 搜索摘要由外部站点作者撰写，可能夹带提示词注入 → 回填走结构隔离
            contentTrust = ToolContentTrust.UNTRUSTED_EXTERNAL,
            statusText = "正在搜索网页…",
            schema = toolSchema(
                name = "search_web",
                description = "联网搜索网页信息并返回候选列表（标题+链接+摘要）。当用户要求搜索某主题的最新内容、资讯、攻略、评测等，或要求“搜一下/查一下/搜索”时先调用本工具（例如“搜一下 Rokid 眼镜的评测”）。拿到结果后，若需要深入了解详情，再对选中的链接调用 fetch_webpage 读取正文。",
                parameters = mapOf(
                    "type" to "object",
                    "properties" to mapOf(
                        "query" to mapOf("type" to "string", "description" to "搜索关键词，用能代表主题的 2~6 个词，如“Rokid 眼镜 评测”"),
                    ),
                    "required" to listOf("query"),
                ),
            ),
        ),
        ToolEntry(
            name = "fetch_webpage",
            group = ToolRegistry.DOMAIN_WEB,
            displayNameRes = R.string.ai_tool_fetch_webpage_name,
            descriptionRes = R.string.ai_tool_fetch_webpage_desc,
            risk = ToolRisk.READ_ONLY,
            // 网页正文是典型的不可信外部内容（间接提示词注入的首要攻击面）
            contentTrust = ToolContentTrust.UNTRUSTED_EXTERNAL,
            statusText = "正在读取网页内容…",
            schema = toolSchema(
                name = "fetch_webpage",
                description = "读取一个具体网页链接的正文内容并转为纯文本（含网页标题）。配合 search_web 使用：当用户想了解某网页的详细内容、或需要总结某个搜索结果页面时，传入该网页完整链接调用本工具。返回的正文供你阅读后进行总结。",
                parameters = mapOf(
                    "type" to "object",
                    "properties" to mapOf(
                        "url" to mapOf("type" to "string", "description" to "要读取的网页完整链接，须以 http:// 或 https:// 开头，如 https://example.com/article"),
                    ),
                    "required" to listOf("url"),
                ),
            ),
        ),
        ToolEntry(
            name = "http_request",
            group = ToolRegistry.DOMAIN_WEB,
            displayNameRes = R.string.ai_tool_http_request_name,
            descriptionRes = R.string.ai_tool_http_request_desc,
            risk = ToolRisk.LOCAL_SIDE_EFFECT,
            // 非幂等（POST/PUT/DELETE）：瞬时失败不自动重放，交给模型决定是否重来
            sideEffect = true,
            contentTrust = ToolContentTrust.UNTRUSTED_EXTERNAL,
            statusText = "正在请求网络接口…",
            schema = toolSchema(
                name = "http_request",
                description = "发起一次 HTTP 请求访问网络接口（method 支持 GET/POST/PUT/PATCH/DELETE/HEAD，可自定义请求头与请求体），返回状态码、关键响应头与响应体文本。当用户要求「调一下某个接口」「查某个 API」「获取实时数据（汇率、公开数据、行情）」「提交表单/上传数据到某个接口」时用本工具——比 fetch_webpage 更通用（它能发 POST、带自定义头）。⚠️ 只支持 http/https 链接，优先 https（明文 http 在本 App 只对少数白名单域名放行，被拒时错误信息会提示）；GET/HEAD 不要传 body；响应体超过约 8000 字符会被截断，需要整份文件请改用 download_file。返回里带 HTTP 状态码：非 2xx 也照样把响应体给你，请据此判断是参数错了还是权限/额度问题，并把真实结果告诉用户，不要编造。",
                parameters = mapOf(
                    "type" to "object",
                    "properties" to mapOf(
                        "url" to mapOf("type" to "string", "description" to "完整链接，须以 http:// 或 https:// 开头，如 https://api.example.com/v1/quote?symbol=000001"),
                        "method" to mapOf(
                            "type" to "string",
                            "enum" to listOf("GET", "POST", "PUT", "PATCH", "DELETE", "HEAD"),
                            "description" to "请求方法，默认 GET；读取用 GET，提交数据用 POST",
                        ),
                        "headers" to mapOf(
                            "type" to "object",
                            "additionalProperties" to mapOf("type" to "string"),
                            "description" to "可选，自定义请求头（键值都是字符串），如 {\"Authorization\":\"Bearer xxx\", \"Accept\":\"application/json\"}",
                        ),
                        "body" to mapOf("type" to "string", "description" to "可选，请求体原文（POST/PUT/PATCH 用）。JSON 直接写 JSON 字符串，如 {\"name\":\"乐奇\"}；表单写 a=1&b=2"),
                        "contentType" to mapOf("type" to "string", "description" to "可选，请求体类型，默认 application/json; charset=utf-8；表单传 application/x-www-form-urlencoded"),
                    ),
                    "required" to listOf("url"),
                ),
            ),
        ),
        ToolEntry(
            name = "download_file",
            group = ToolRegistry.DOMAIN_WEB,
            displayNameRes = R.string.ai_tool_download_file_name,
            descriptionRes = R.string.ai_tool_download_file_desc,
            risk = ToolRisk.LOCAL_SIDE_EFFECT,
            // 会往用户下载目录写文件（同名覆盖），不是幂等查询
            sideEffect = true,
            contentTrust = ToolContentTrust.UNTRUSTED_EXTERNAL,
            statusText = "正在下载文件…",
            schema = toolSchema(
                name = "download_file",
                description = "把一个链接指向的文件下载到手机「下载」目录（大文件边下边写、不会占满内存；单个文件上限 200MB；同名文件会覆盖）。当用户说「下载这个文件/这个安装包/这首歌/这份 PDF」「把刚才那个链接存下来」，或需要把某个网络上的文件拿到本机时用它。⚠️ 只能下载 http/https 直链（需要登录或带鉴权头的地址会失败，那种情况改用 http_request 拿到内容后再用写文件工具落盘）。下完可配合 list_files(scope=\"downloads\") 查看、解压工具解包；不要用它下载网页正文（那用 fetch_webpage）。",
                parameters = mapOf(
                    "type" to "object",
                    "properties" to mapOf(
                        "url" to mapOf("type" to "string", "description" to "文件直链，须以 http:// 或 https:// 开头，如 https://example.com/app.apk"),
                        "fileName" to mapOf("type" to "string", "description" to "保存的文件名（含扩展名，如 report.pdf）。不传则从链接末尾自动推断"),
                        "folder" to mapOf("type" to "string", "description" to "可选，下载目录下的子文件夹名（如 资料）。**不传则放「下载/${ProotShell.SHARE_DIR_NAME}」**——那正是本机 Linux 环境里 /mnt/lab 对应的目录，下载完可以直接在 run_shell 里读它/解压/赋权"),
                    ),
                    "required" to listOf("url"),
                ),
            ),
        ),
    )

    override fun execute(context: Context, name: String, args: JSONObject): String = when (name) {
            "search_web" -> WebTools.search(args.optString("query"))

            "fetch_webpage" -> WebTools.fetchPage(args.optString("url"))

            "http_request" -> WebTools.httpRequest(
                method = args.optString("method"),
                url = args.optString("url"),
                headers = stringMap(args.optJSONObject("headers")),
                body = args.optString("body").takeIf { it.isNotEmpty() },
                contentType = args.optString("contentType").takeIf { it.isNotEmpty() },
            )

            "download_file" -> WebTools.downloadFile(
                context = context,
                url = args.optString("url"),
                fileName = args.optString("fileName"),
                // 不传 folder 时落「下载/Lab」而不是「下载」根目录：那是容器里 /mnt/lab 对应的
                // 目录（见 ProotShell.SHARE_DIR_NAME），系统提示里"下完可在 run_shell 里直接用"
                // 才是真的 —— 直接落根目录的话容器根本看不到它。
                folder = args.optString("folder").ifBlank { ProotShell.SHARE_DIR_NAME },
            )

        else -> throw IllegalArgumentException("未知工具: $name")
    }

    /** JSON 对象 → Map（请求头这种"键值都是字符串"的结构用；非字符串值转成文本，避免整条调用失败） */
    private fun stringMap(json: JSONObject?): Map<String, String> {
        if (json == null || json.length() == 0) return emptyMap()
        val out = LinkedHashMap<String, String>()
        json.keys().forEach { k -> out[k] = json.optString(k) }
        return out
    }
}
