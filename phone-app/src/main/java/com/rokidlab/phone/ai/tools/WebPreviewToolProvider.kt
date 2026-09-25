package com.rokidlab.phone.ai.tools

import com.rokidlab.phone.R
import com.rokidlab.phone.ai.ToolContentTrust
import com.rokidlab.phone.ai.ToolRisk

import android.content.Context
import android.util.Log
import com.rokidlab.phone.ai.ToolRegistry
import com.rokidlab.phone.platform.ProotInstaller
import com.rokidlab.phone.platform.WebPreviewManager
import org.json.JSONObject

/**
 * WebPreviewToolProvider —— 本机执行域的**常驻网页预览**工具。
 *
 * ## 为什么独立成工具而不是让 `run_shell` 顺带做
 * `run_shell` 是「等结果」的模型：600s 超时 + `--kill-on-exit`（proot 退出连带清掉整棵
 * 进程树）。网页服务恰恰要**一直活着** —— 起完就被杀，用户永远打不开页面。
 * 预览走 [WebPreviewManager] 的常驻通道（不占执行槽位、不设超时、自动分配端口注入
 * `$PREVIEW_PORT`），停止入口另给 [TOOL_STOP]，模型自己起的服务自己停得掉。
 *
 * ## 风险档与 `shell` 域一致的边界
 * 服务跑在容器里（可丢弃 rootfs、不提权、端口只绑回环），最坏后果与 `run_shell` 同量级
 * ⇒ [ToolRisk.LOCAL_SIDE_EFFECT]。本 provider 归属 [ToolRegistry.DOMAIN_SHELL]，
 * 该域**不在** `PageScope.ALLOWED_DOMAINS` 里 —— AIUI 页面（第三方制品）拿不到
 * 「起常驻服务」的能力，与 `run_shell` / `install_packages` 同一条边界。
 *
 * ## 结果为什么按不可信内容回填
 * 服务日志里可能出现外部内容（HTTP 请求行带路径、页面自身注入的文本），与 `run_shell`
 * 同取保守档 [ToolContentTrust.UNTRUSTED_EXTERNAL]。
 */
internal object WebPreviewToolProvider : ToolProvider {
    private const val TAG = "WebPreviewToolProvider"

    const val TOOL_START = "start_web_preview"
    const val TOOL_STOP = "stop_web_preview"

    override val toolNames = setOf(TOOL_START, TOOL_STOP)

    override fun tools(): List<ToolEntry> = listOf(
        ToolEntry(
            name = TOOL_START,
            group = ToolRegistry.DOMAIN_SHELL,
            displayNameRes = R.string.ai_tool_start_web_preview_name,
            descriptionRes = R.string.ai_tool_start_web_preview_desc,
            risk = ToolRisk.LOCAL_SIDE_EFFECT,
            sideEffect = true,
            contentTrust = ToolContentTrust.UNTRUSTED_EXTERNAL,
            statusText = "正在启动网页预览…",
            schema = toolSchema(
                name = TOOL_START,
                description = "在本机 Linux 环境（Ubuntu 容器）里启动一个**持续运行**的网页服务，" +
                    "并在聊天窗口给用户一个实时预览入口（用户点开就能看到页面效果）。" +
                    "适用于：静态网页/前端项目、Flask/FastAPI 应用、plotly/pyecharts 等生成的交互式图表、" +
                    "任何需要常驻进程才能看的东西。⚠️ **监听端口必须用环境变量 \$PREVIEW_PORT**" +
                    "（已自动分配并注入，不要自己挑端口、不要写死端口），绑定 0.0.0.0 或 127.0.0.1。" +
                    "例：python3 -m http.server \$PREVIEW_PORT --directory /mnt/lab/site；" +
                    "python3 app.py（app 内读 os.environ[\"PREVIEW_PORT\"]）；node server.js。" +
                    "⚠️ 前提要自己备好：页面文件先写好（/mnt/lab 用户文件交换口）；缺依赖先用 install_packages 装（如 flask）。" +
                    "⚠️ 命令必须**前台运行**：不要加 & 或 nohup（本工具就是要它一直活着；命令退出 = 服务结束）。" +
                    "⚠️ 同时最多 ${WebPreviewManager.MAX_PREVIEWS} 个预览。成功后把地址告诉用户；" +
                    "要停止时调 stop_web_preview。本工具返回的是启动状态与服务日志，不是页面内容；" +
                    "要抓取页面内容请用 run_shell + curl。",
                parameters = mapOf(
                    "type" to "object",
                    "properties" to mapOf(
                        "command" to mapOf(
                            "type" to "string",
                            "description" to "服务启动命令（前台运行，监听端口用 \$PREVIEW_PORT 变量）。" +
                                "例如：python3 -m http.server \$PREVIEW_PORT --directory /mnt/lab",
                        ),
                        "cwd" to mapOf(
                            "type" to "string",
                            "description" to "命令的工作目录（容器内路径）。默认 /（容器根）",
                        ),
                    ),
                    "required" to listOf("command"),
                ),
            ),
        ),
        ToolEntry(
            name = TOOL_STOP,
            group = ToolRegistry.DOMAIN_SHELL,
            displayNameRes = R.string.ai_tool_stop_web_preview_name,
            descriptionRes = R.string.ai_tool_stop_web_preview_desc,
            risk = ToolRisk.LOCAL_SIDE_EFFECT,
            sideEffect = true,
            contentTrust = ToolContentTrust.TRUSTED,
            statusText = "正在停止网页预览…",
            schema = toolSchema(
                name = TOOL_STOP,
                description = "停止正在运行的网页预览服务。" +
                    "用户说「关掉预览/停掉服务」或页面不再需要时调用。" +
                    "不传 id 时停止全部预览；id 来自 start_web_preview 的返回。",
                parameters = mapOf(
                    "type" to "object",
                    "properties" to mapOf(
                        "id" to mapOf(
                            "type" to "string",
                            "description" to "要停止的预览 id（start_web_preview 返回里有）。不传 = 停止全部",
                        ),
                    ),
                    "required" to emptyList<String>(),
                ),
            ),
        ),
    )

    override fun execute(context: Context, name: String, args: JSONObject): String = when (name) {
        TOOL_START -> startPreview(context, args)
        TOOL_STOP -> WebPreviewManager.stop(context, args.optString("id").trim().ifEmpty { null })
        else -> throw IllegalArgumentException("未知工具: $name")
    }

    private fun startPreview(context: Context, args: JSONObject): String {
        // 与 run_shell 同一条纪律：28.5 MB 的 rootfs 下载只能由用户决定，模型不得自动触发
        if (!ProotInstaller.isInstalled(context)) {
            return "本机执行环境还没安装（缺少 Ubuntu 容器），没有启动任何服务。" +
                "请如实告诉用户：需要到「设置 → 本机执行环境」点一下安装（约 28 MB，一次性下载），" +
                "装好之后才能启动网页预览。现在不要假装服务已经启动。"
        }
        val command = args.optString("command").trim()
        if (command.isEmpty()) {
            return "命令为空，没有启动任何服务。请把启动命令放在 command 参数里（监听端口用 \$PREVIEW_PORT 变量）。"
        }
        val cwd = args.optString("cwd").trim().ifEmpty { "/" }
        Log.i(TAG, "start_web_preview: ${command.take(80)}（cwd=$cwd）")
        return when (val r = WebPreviewManager.start(context, command, cwd)) {
            is WebPreviewManager.StartResult.Started -> buildString {
                append("网页预览已启动：${r.info.url}（id=${r.info.id}）")
                append("\n聊天窗口已出现预览入口，用户点开即可看到页面 —— 请把地址与用法一并告诉用户。")
                r.note?.let { append("\n注意：$it") }
                if (r.logTail.isNotBlank()) append("\n\n[服务日志（最近几行）]\n${r.logTail}")
            }
            is WebPreviewManager.StartResult.Failed -> r.message
        }
    }
}
