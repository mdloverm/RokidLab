package com.rokidlab.phone.ai.tools

import com.rokidlab.phone.ai.ToolContentTrust
import com.rokidlab.phone.ai.ToolConfirmPolicy
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
import com.rokidlab.phone.ai.ScreenCaptureTools
import com.rokidlab.phone.ai.ToolRegistry
import com.rokidlab.phone.ai.WeatherTools
import com.rokidlab.phone.ai.WebTools
import com.rokidlab.phone.R
import com.rokidlab.phone.app.LabApplication
import com.rokidlab.phone.adb.ui.TimerAction
import com.rokidlab.phone.adb.ui.TimerSchedule
import com.rokidlab.phone.adb.ui.TimerTask
import com.rokidlab.phone.permission.AppPermission
import com.rokidlab.phone.permission.PermissionBridge
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.UUID

/**
 * PhoneToolProvider —— 手机域（通讯录/拨号/短信/剪贴板/闹钟/应用/状态/音量/日历）。
 * Phase 4 从 `ToolRegistry.execute` 迁出的该域工具执行分支（逐字搬运，行为不变）。
 */
internal object PhoneToolProvider : ToolProvider {
    private const val TAG = "PhoneToolProvider"

    override val toolNames = setOf(
        "search_contacts",
        "call_phone",
        "send_sms",
        "read_clipboard",
        "write_clipboard",
        "capture_screen",
        "set_phone_alarm",
        "open_phone_app",
        "list_phone_apps",
        "get_phone_status",
        "set_phone_volume",
        "manage_calendar",
        "delete_calendar_event",
        "update_calendar_event",
        "open_navigation",
        "uninstall_phone_app",
        "set_flashlight",
        "get_step_count",
    )

    override fun tools(): List<ToolEntry> = listOf(
        ToolEntry(
            name = "search_contacts",
            group = ToolRegistry.DOMAIN_PHONE,
            displayNameRes = R.string.ai_tool_search_contacts_name,
            descriptionRes = R.string.ai_tool_search_contacts_desc,
            risk = ToolRisk.READ_ONLY,
            statusText = "正在查找联系人…",
            schema = toolSchema(
                name = "search_contacts",
                description = "在手机通讯录中按姓名查找联系人及其电话号码。当用户问「XX 的电话是多少」「XX 手机号」「我存的 XX 的号码」时调用。",
                parameters = mapOf(
                    "type" to "object",
                    "properties" to mapOf(
                        "name" to mapOf("type" to "string", "description" to "联系人姓名，如「张三」"),
                    ),
                    "required" to listOf("name"),
                ),
            ),
        ),
        ToolEntry(
            name = "call_phone",
            group = ToolRegistry.DOMAIN_PHONE,
            displayNameRes = R.string.ai_tool_call_phone_name,
            descriptionRes = R.string.ai_tool_call_phone_desc,
            risk = ToolRisk.LOCAL_SIDE_EFFECT,
            sideEffect = true,
            statusText = "正在拨号…",
            summarize = { args ->
                            val who = args.optString("name").takeIf { it.isNotBlank() }
                                ?: args.optString("number").takeIf { it.isNotBlank() }
                                ?: args.optString("phone_number").takeIf { it.isNotBlank() }
                            if (!who.isNullOrBlank()) "拨打电话给 $who" else "拨打电话"
            },
            schema = toolSchema(
                name = "call_phone",
                description = "用手机**直接拨出**电话（已授电话权限即刻拨号，无需用户二次确认；未授权时退化为打开拨号盘）。参数可以是联系人姓名（自动查通讯录）或手机号。当用户说「给张三打电话」「拨打 138xxxx」「打电话给妈妈」时调用。",
                parameters = mapOf(
                    "type" to "object",
                    "properties" to mapOf(
                        "contact" to mapOf("type" to "string", "description" to "联系人姓名（如「张三」）或完整手机号（如「13800138000」）"),
                    ),
                    "required" to listOf("contact"),
                ),
            ),
        ),
        // 短信登记成 EXTERNAL_SIDE_EFFECT + confirmPolicy = BLOCK：内容一旦发出就收不回，
        // 且触达的是第三方号码 —— 不是「本机状态可撤销」。
        //
        // ⚠️ BLOCK 是本工具**唯一**的确认语义，显式写出来（虽然 EXTERNAL 档的默认值就是它），
        // 因为它是全项目最容易被误降级的那个：它的 schema 自述"已授短信权限即刻发出，
        // 无需再打开短信 App"，也就是**没有**「未获确认时只做无副作用动作」这条退路。
        // 改造前闸门按 fail-open 静默放行它，等于"AI 在没人点头的情况下真的把短信发出去"。
        // 若将来为了"功能可用"想把它放宽成 PROCEED，请先回答：发错的短信怎么收回？
        ToolEntry(
            name = "send_sms",
            group = ToolRegistry.DOMAIN_PHONE,
            displayNameRes = R.string.ai_tool_send_sms_name,
            descriptionRes = R.string.ai_tool_send_sms_desc,
            risk = ToolRisk.EXTERNAL_SIDE_EFFECT,
            confirmPolicy = ToolConfirmPolicy.BLOCK,
            sideEffect = true,
            statusText = "正在发送短信…",
            summarize = { args ->
                val who = args.optString("to").takeIf { it.isNotBlank() }
                val body = args.optString("message").replace("\n", " ").take(30)
                if (who.isNullOrBlank()) "发送短信" else "发短信给 $who：$body"
            },
            schema = toolSchema(
                name = "send_sms",
                description = "用手机**直接发出**短信（已授短信权限即刻发出，无需再打开短信 App；未授权时自动拉起系统授权框）。" +
                    "收件人可以是联系人姓名（自动查通讯录）或手机号。当用户说「给张三发条短信说…」「发短信 138xxxx 告诉他…」时调用。" +
                    "⚠️ 短信不可撤回，正文要尽量用用户的原话；用户没说清发给谁或说什么时先问清楚，不要自己编内容。",
                parameters = mapOf(
                    "type" to "object",
                    "properties" to mapOf(
                        "to" to mapOf("type" to "string", "description" to "收件人：联系人姓名（如「张三」）或手机号（如「13800138000」）"),
                        "message" to mapOf("type" to "string", "description" to "短信正文（必填），用用户的原话，不要自行发挥"),
                    ),
                    "required" to listOf("to", "message"),
                ),
            ),
        ),
        // 读剪贴板：不动任何状态，且内容是别的 App 写进去的文本（可能是被人恶意复制的注入话术）→ UNTRUSTED_EXTERNAL
        ToolEntry(
            name = "read_clipboard",
            group = ToolRegistry.DOMAIN_PHONE,
            displayNameRes = R.string.ai_tool_read_clipboard_name,
            descriptionRes = R.string.ai_tool_read_clipboard_desc,
            risk = ToolRisk.READ_ONLY,
            contentTrust = ToolContentTrust.UNTRUSTED_EXTERNAL,
            statusText = "正在读剪贴板…",
            schema = toolSchema(
                name = "read_clipboard",
                description = "读取手机剪贴板里的文字。当用户说「我刚复制的那段」「把剪贴板里的内容…」时调用。" +
                    "⚠️ Android 10 起后台 App 读不到剪贴板：本工具在 App 不在前台时会明确回报读不到，" +
                    "此时要如实告诉用户「需要把乐奇实验室切到前台」，**绝对不要**编造剪贴板内容。",
                parameters = mapOf("type" to "object", "properties" to emptyMap<String, Any>()),
            ),
        ),
        // 写剪贴板：改了系统共享状态（别的 App 下一次粘贴会拿到它）→ LOCAL_SIDE_EFFECT
        ToolEntry(
            name = "write_clipboard",
            group = ToolRegistry.DOMAIN_PHONE,
            displayNameRes = R.string.ai_tool_write_clipboard_name,
            descriptionRes = R.string.ai_tool_write_clipboard_desc,
            risk = ToolRisk.LOCAL_SIDE_EFFECT,
            sideEffect = true,
            statusText = "正在写入剪贴板…",
            summarize = { args ->
                "复制到剪贴板：" + args.optString("text").replace("\n", " ").take(24)
            },
            schema = toolSchema(
                name = "write_clipboard",
                description = "把一段文字复制到手机剪贴板（之后用户在任何 App 里都能粘贴）。" +
                    "当用户说「复制这段」「把这个记到剪贴板」「我要粘贴到微信里」时调用。" +
                    "⚠️ 不要靠它保存资料 —— 剪贴板只有一份、会被下一次复制覆盖，需要留存请用 save_summary_txt。",
                parameters = mapOf(
                    "type" to "object",
                    "properties" to mapOf(
                        "text" to mapOf("type" to "string", "description" to "要复制到剪贴板的完整文字"),
                    ),
                    "required" to listOf("text"),
                ),
            ),
        ),
        // 截屏：抓的是**整个手机屏幕**（别人的聊天内容、余额、验证码都在里面），
        // 所以只在用户明确要求看屏幕时用；隐私级别最高的一次读取，宁可多问一句也别乱调。
        // 风险档跟 look_at_view 一致（读本机画面、不改任何状态），但不进只读档：
        // 它会弹系统授权框 / 占用一次 MediaProjection 会话，无人值守的定时任务不该碰。
        ToolEntry(
            name = "capture_screen",
            group = ToolRegistry.DOMAIN_PHONE,
            displayNameRes = R.string.ai_tool_capture_screen_name,
            descriptionRes = R.string.ai_tool_capture_screen_desc,
            risk = ToolRisk.LOCAL_SIDE_EFFECT,
            // sideEffect=true：瞬时失败不重试 —— 重试会让系统授权框弹第二次
            sideEffect = true,
            // 屏幕上的文字可能来自任意 App（别人发来的消息、网页），可能夹带注入话术
            contentTrust = ToolContentTrust.UNTRUSTED_EXTERNAL,
            statusText = "正在截取手机屏幕…",
            schema = toolSchema(
                name = "capture_screen",
                description = "截取**用户当前手机屏幕**并看到上面的内容。" +
                    "当用户说「看看我屏幕上写的什么」「屏幕上这个你看一下」「帮我看看这条消息/这个报错」" +
                    "「把屏幕上的内容读一下」时调用。" +
                    "⚠️ 与 look_at_view 的区别：那个是眼镜摄像头看到的**现实世界**，这个是**手机屏幕**；" +
                    "先按措辞自己判断：「屏幕上/这个页面/界面/这条消息/这个报错」→ 用本工具；" +
                    "「我面前/我手上/手里拿的/这个实物/这个东西是什么牌子」→ 用 look_at_view；" +
                    "**真的判不出来时才问一句**（不要一律问，这类请求多数自带端线索）。" +
                    "⚠️ 取图有三条路，代价从低到高：开过无障碍服务时**直接读、不弹任何框**" +
                    "（推荐先让用户开一次，之后连截图都变快）；投屏正在跑时复用投屏画面（零弹窗但分辨率低）；" +
                    "两者都不行才会弹一次系统「开始截屏」授权框，用户点了同意才拿得到画面。" +
                    "被拒绝或界面不在前台时会明确回报原因，此时要如实告诉用户，**绝对不要**编造屏幕内容。",
                parameters = mapOf("type" to "object", "properties" to emptyMap<String, Any>()),
            ),
        ),
        ToolEntry(
            name = "set_phone_alarm",
            group = ToolRegistry.DOMAIN_PHONE,
            displayNameRes = R.string.ai_tool_set_phone_alarm_name,
            descriptionRes = R.string.ai_tool_set_phone_alarm_desc,
            risk = ToolRisk.LOCAL_SIDE_EFFECT,
            sideEffect = true,
            statusText = "正在设置手机闹钟…",
            summarize = { args -> "设置手机闹钟" },
            schema = toolSchema(
                name = "set_phone_alarm",
                description = "在手机上设置闹钟或倒计时（**响铃在手机上**，区别于眼镜端的定时提醒 manage_timer）。当用户说「明早 7 点叫我起床」「30 分钟后手机闹我」「设个手机闹钟」时调用。⚠️ 用户没说是**手机响**还是**眼镜提醒**时，**先问一句**「让手机响还是眼镜提醒你？」，**不要默认任何一端** —— 两者体验不同（手机闹钟响在手机上、眼镜提醒在眼镜上），替他选经常不是他要的。",
                parameters = mapOf(
                    "type" to "object",
                    "properties" to mapOf(
                        "message" to mapOf("type" to "string", "description" to "闹钟标签/提醒内容，如「起床」「该出发了」"),
                        "hour" to mapOf("type" to "integer", "description" to "24 小时制小时（绝对时间闹钟必填，0-23），如 7"),
                        "minute" to mapOf("type" to "integer", "description" to "分钟（绝对时间闹钟必填，0-59），如 30"),
                        "minutesFromNow" to mapOf("type" to "integer", "description" to "相对分钟数（倒计时用，如「30分钟后」传 30；与 hour/minute 二选一）"),
                    ),
                ),
            ),
        ),
        ToolEntry(
            name = "open_phone_app",
            group = ToolRegistry.DOMAIN_PHONE,
            displayNameRes = R.string.ai_tool_open_phone_app_name,
            descriptionRes = R.string.ai_tool_open_phone_app_desc,
            risk = ToolRisk.LOCAL_SIDE_EFFECT,
            sideEffect = true,
            statusText = "正在打开手机应用…",
            summarize = { args -> "打开手机应用 ${args.optString("app_name").takeIf { it.isNotBlank() } ?: ""}".trim() },
            schema = toolSchema(
                name = "open_phone_app",
                description = "打开**手机上**安装的应用（微信/支付宝/相机等）。当用户说「打开手机上的微信」「帮我打开支付宝」且对象是手机应用时调用；打开**眼镜上**的应用请用 launch_glasses_app。⚠️「打开 XX」手机与眼镜都有对应工具，用户**没说哪一端时先问一句**「是在手机上打开还是眼镜上打开？」，不要自行选一端。",
                parameters = mapOf(
                    "type" to "object",
                    "properties" to mapOf(
                        "appName" to mapOf("type" to "string", "description" to "要打开的手机应用名称，原样转述，如「微信」「支付宝」「设置」"),
                    ),
                    "required" to listOf("appName"),
                ),
            ),
        ),
        ToolEntry(
            name = "list_phone_apps",
            group = ToolRegistry.DOMAIN_PHONE,
            displayNameRes = R.string.ai_tool_list_phone_apps_name,
            descriptionRes = R.string.ai_tool_list_phone_apps_desc,
            risk = ToolRisk.READ_ONLY,
            statusText = "正在读取应用清单…",
            schema = toolSchema(
                name = "list_phone_apps",
                description = "列出**手机上**安装的应用（名称/包名/版本），默认只列用户自己装的应用。" +
                    "当用户问「我手机装了哪些应用」「有没有装 XX 这个 App」时调用；" +
                    "当 open_phone_app 反馈没找到应用、需要先确认到底装没装时也调用（传 query 精确查更快）。" +
                    "⚠️ 列出**眼镜上**的应用请用 list_glasses_apps。用户**没说哪一端时先问一句**「要看哪一边的应用列表？」。",
                parameters = mapOf(
                    "type" to "object",
                    "properties" to mapOf(
                        "query" to mapOf("type" to "string", "description" to "关键词，匹配应用名或包名，如「地图」「taobao」；不传 = 列出全部"),
                        "includeSystem" to mapOf("type" to "boolean", "description" to "是否包含系统预装应用（默认 false）；用户问「相机/设置」这类系统应用时才传 true"),
                    ),
                ),
            ),
        ),
        ToolEntry(
            name = "get_phone_status",
            group = ToolRegistry.DOMAIN_PHONE,
            displayNameRes = R.string.ai_tool_get_phone_status_name,
            descriptionRes = R.string.ai_tool_get_phone_status_desc,
            risk = ToolRisk.READ_ONLY,
            statusText = "正在查询手机状态…",
            schema = toolSchema(
                name = "get_phone_status",
                description = "查询**手机**当前状态：电量百分比、是否在充电、媒体音量、屏幕亮度。当用户问「手机还有多少电」「在充电吗」「音量多大」「手机什么状态」时调用。⚠️ 查**眼镜**的电量/存储/系统信息用 get_glasses_status。用户**没说哪一端时先问一句**「你是问手机还是眼镜？」（注意：手机与眼镜是两台独立设备，电量不通用）。",
                parameters = mapOf(
                    "type" to "object",
                    "properties" to mapOf<String, Any>(),
                ),
            ),
        ),
        ToolEntry(
            name = "set_phone_volume",
            group = ToolRegistry.DOMAIN_PHONE,
            displayNameRes = R.string.ai_tool_set_phone_volume_name,
            descriptionRes = R.string.ai_tool_set_phone_volume_desc,
            risk = ToolRisk.LOCAL_SIDE_EFFECT,
            sideEffect = true,
            statusText = "正在调节手机音量…",
            schema = toolSchema(
                name = "set_phone_volume",
                description = "调节手机媒体音量（百分比）。当用户说「手机音量调到 50」「音量小一点/大一点」（对象是手机时）调用；「大一点/小一点」换算为比当前值高/低 20% 左右的具体数字。",
                parameters = mapOf(
                    "type" to "object",
                    "properties" to mapOf(
                        "volume" to mapOf("type" to "integer", "description" to "目标音量百分比 0~100，如 50 表示一半"),
                    ),
                    "required" to listOf("volume"),
                ),
            ),
        ),
        ToolEntry(
            name = "manage_calendar",
            group = ToolRegistry.DOMAIN_PHONE,
            displayNameRes = R.string.ai_tool_manage_calendar_name,
            descriptionRes = R.string.ai_tool_manage_calendar_desc,
            risk = ToolRisk.LOCAL_SIDE_EFFECT,
            sideEffect = true,
            statusText = "正在处理日程…",
            summarize = { args -> if (args.optString("action").equals("create", true)) "添加日历日程" else "查询日历日程" },
            schema = toolSchema(
                name = "manage_calendar",
                description = "管理手机日历，一个工具管两种意图。action=\"query\"：查询某天的日程安排（「我明天有什么安排」「今天有什么日程」）。action=\"create\"：创建日程（「记一下明天下午3点开会」「加个日程：周五19点健身」）——title 与 startTime 必填，开始时间要具体到 HH:mm（用户只说「下午」按 15:00 估算并告知用户）。",
                parameters = mapOf(
                    "type" to "object",
                    "properties" to mapOf(
                        "action" to mapOf("type" to "string", "enum" to listOf("query", "create"), "description" to "query=查询日程；create=创建日程"),
                        "date" to mapOf("type" to "string", "description" to "日期：today/tomorrow/今天/明天，或 YYYY-MM-DD；不传默认今天（create 时已过时刻自动顺延到明天）"),
                        "title" to mapOf("type" to "string", "description" to "action=create 时的日程标题，如「团队会议」"),
                        "startTime" to mapOf("type" to "string", "description" to "action=create 时的开始时间，24 小时制 HH:mm，如 14:30"),
                        "durationMinutes" to mapOf("type" to "integer", "description" to "action=create 时的时长（分钟），默认 60"),
                        "note" to mapOf("type" to "string", "description" to "action=create 时的备注（可选），如地点、参会人"),
                    ),
                    "required" to listOf("action"),
                ),
            ),
        ),
        // 删日程：删除不可恢复 ⇒ EXTERNAL + BLOCK（对齐 delete_knowledge_doc 先例）。
        // 刻意不并进 manage_calendar：那会把整个工具升到 EXTERNAL，"记个日程"也得弹确认。
        ToolEntry(
            name = "delete_calendar_event",
            group = ToolRegistry.DOMAIN_PHONE,
            displayNameRes = R.string.ai_tool_delete_calendar_event_name,
            descriptionRes = R.string.ai_tool_delete_calendar_event_desc,
            risk = ToolRisk.EXTERNAL_SIDE_EFFECT,
            confirmPolicy = ToolConfirmPolicy.BLOCK,
            sideEffect = true,
            statusText = "正在删除日程…",
            summarize = { args ->
                val t = args.optString("title").takeIf { it.isNotBlank() }
                val id = args.optLong("eventId", 0L).takeIf { it > 0 }?.let { "id=$it" }
                val obj = listOfNotNull(t, id).joinToString(" ")
                if (obj.isBlank()) "删除日历日程" else "删除日历日程：$obj"
            },
            schema = toolSchema(
                name = "delete_calendar_event",
                description = "删除手机日历上的一个日程（**不可恢复**，会先向用户确认）。eventId 优先（从 manage_calendar query 或删除/修改的候选列表里拿，最精确）；没有 id 时传 date+title 关键词，关键词命中多个日程时会把候选连 eventId 列出来，此时不要猜，向用户确认后带 eventId 重调。",
                parameters = mapOf(
                    "type" to "object",
                    "properties" to mapOf(
                        "eventId" to mapOf("type" to "integer", "description" to "日程 id（精确句柄，优先使用）"),
                        "date" to mapOf("type" to "string", "description" to "日期：today/tomorrow/YYYY-MM-DD/MM-DD；用关键词定位时必传（限定搜索范围）"),
                        "title" to mapOf("type" to "string", "description" to "日程标题关键词（模糊匹配）；没有 eventId 时必传"),
                    ),
                ),
            ),
        ),
        // 改日程：可再次编辑、本机可撤销 ⇒ LOCAL 档静默执行（对齐 edit_text_file 的口径）
        ToolEntry(
            name = "update_calendar_event",
            group = ToolRegistry.DOMAIN_PHONE,
            displayNameRes = R.string.ai_tool_update_calendar_event_name,
            descriptionRes = R.string.ai_tool_update_calendar_event_desc,
            risk = ToolRisk.LOCAL_SIDE_EFFECT,
            sideEffect = true,
            statusText = "正在修改日程…",
            summarize = { args -> "修改日历日程${args.optString("title").takeIf { it.isNotBlank() }?.let { "：$it" } ?: ""}" },
            schema = toolSchema(
                name = "update_calendar_event",
                description = "修改手机日历上的一个日程（改标题/改时间/改备注）。eventId 优先；没有 id 时传 date+title 关键词（命中多个会列出候选，此时带 eventId 重调）。只更新给出的字段：newTitle 改标题；改时间时 newDate（新日期）与 newStartTime（HH:mm）至少给一个、只给其一则另一项沿用原值；newDurationMinutes 改时长（分钟）；newNote 改备注。",
                parameters = mapOf(
                    "type" to "object",
                    "properties" to mapOf(
                        "eventId" to mapOf("type" to "integer", "description" to "日程 id（精确句柄，优先使用）"),
                        "date" to mapOf("type" to "string", "description" to "原日程日期（today/tomorrow/YYYY-MM-DD/MM-DD）；用关键词定位时必传"),
                        "title" to mapOf("type" to "string", "description" to "原日程标题关键词；没有 eventId 时必传"),
                        "newTitle" to mapOf("type" to "string", "description" to "新标题（可选）"),
                        "newDate" to mapOf("type" to "string", "description" to "新日期（改期时给；today/tomorrow/YYYY-MM-DD）"),
                        "newStartTime" to mapOf("type" to "string", "description" to "新开始时间，24 小时制 HH:mm"),
                        "newDurationMinutes" to mapOf("type" to "integer", "description" to "新时长（分钟）"),
                        "newNote" to mapOf("type" to "string", "description" to "新备注（给出即覆盖，包括覆盖为空）"),
                    ),
                ),
            ),
        ),
        // 打开导航：与 open_phone_app 同档 —— 只是拉起本机地图应用，动作本机可重做 ⇒ LOCAL
        ToolEntry(
            name = "open_navigation",
            group = ToolRegistry.DOMAIN_PHONE,
            displayNameRes = R.string.ai_tool_open_navigation_name,
            descriptionRes = R.string.ai_tool_open_navigation_desc,
            risk = ToolRisk.LOCAL_SIDE_EFFECT,
            sideEffect = true,
            statusText = "正在打开导航…",
            summarize = { args ->
                val dest = args.optString("destination").takeIf { it.isNotBlank() }
                if (dest != null) "打开导航：$dest" else "打开地图"
            },
            schema = toolSchema(
                name = "open_navigation",
                description = "在手机上打开地图 / 发起导航。目的地可选：给了就在地图里定位到目的地（由地图应用接手导航），不给就只打开地图 App。当用户说「导航去 XX」「带我去 XX」「打开地图」时调用。出行方式只是偏好，部分地图应用可能忽略。",
                parameters = mapOf(
                    "type" to "object",
                    "properties" to mapOf(
                        "destination" to mapOf("type" to "string", "description" to "目的地名称或地址，如「杭州东站」「西湖」；不传 = 只打开地图"),
                        "mode" to mapOf("type" to "string", "enum" to listOf("drive", "walk", "bike", "transit"), "description" to "出行方式：drive=驾车（默认）/walk=步行/bike=骑行/transit=公交"),
                    ),
                ),
            ),
        ),
        // 卸载应用：一旦确认，应用与其数据全部清空、不可恢复 ⇒ EXTERNAL + BLOCK
        //（对齐 delete_calendar_event 先例）。系统卸载确认框只是第二道保险，
        // 模型侧必须先过确认闸门 —— 无人值守时不许背着用户卸应用。
        ToolEntry(
            name = "uninstall_phone_app",
            group = ToolRegistry.DOMAIN_PHONE,
            displayNameRes = R.string.ai_tool_uninstall_phone_app_name,
            descriptionRes = R.string.ai_tool_uninstall_phone_app_desc,
            risk = ToolRisk.EXTERNAL_SIDE_EFFECT,
            confirmPolicy = ToolConfirmPolicy.BLOCK,
            sideEffect = true,
            statusText = "正在请求卸载应用…",
            summarize = { args ->
                val who = args.optString("packageName").takeIf { it.isNotBlank() }
                    ?: args.optString("appName").takeIf { it.isNotBlank() }
                if (who != null) "卸载应用 $who" else "卸载应用"
            },
            schema = toolSchema(
                name = "uninstall_phone_app",
                description = "卸载手机上的一个应用（**不可恢复**，应用数据一并清空；会先向用户确认，之后还会弹系统卸载确认框）。packageName 优先（从 list_phone_apps 结果里拿最精确）；只有应用名时传 appName。用户明确说「卸载/删除 XX 应用」时才调用；「清理一下不用的应用」这类模糊指令要先列出候选、向用户确认后再调。",
                parameters = mapOf(
                    "type" to "object",
                    "properties" to mapOf(
                        "packageName" to mapOf("type" to "string", "description" to "应用包名（精确句柄，优先使用），如 com.tencent.mm"),
                        "appName" to mapOf("type" to "string", "description" to "应用名称（没有包名时用），如「微信」；拿不准包名时先用 list_phone_apps 查"),
                    ),
                ),
            ),
        ),
        ToolEntry(
            name = "set_flashlight",
            group = ToolRegistry.DOMAIN_PHONE,
            displayNameRes = R.string.ai_tool_set_flashlight_name,
            descriptionRes = R.string.ai_tool_set_flashlight_desc,
            risk = ToolRisk.LOCAL_SIDE_EFFECT,
            sideEffect = true,
            statusText = "正在设置手电筒…",
            summarize = { args -> if (args.optBoolean("enabled", false)) "打开手电筒" else "关闭手电筒" },
            schema = toolSchema(
                name = "set_flashlight",
                description = "开关手机手电筒（闪光灯补光）。当用户说「打开手电筒」「把灯关了」且指手机闪光灯时调用。enabled 必填：true=开，false=关。",
                parameters = mapOf(
                    "type" to "object",
                    "properties" to mapOf(
                        "enabled" to mapOf("type" to "boolean", "description" to "true=打开手电筒；false=关闭"),
                    ),
                    "required" to listOf("enabled"),
                ),
            ),
        ),
        // 计步：只读传感器数据。口径必须如实 —— TYPE_STEP_COUNTER 是**自开机累计**步数
        //（重启清零），schema 与设置页描述都写明，禁止模型谎称"今日步数"。
        ToolEntry(
            name = "get_step_count",
            group = ToolRegistry.DOMAIN_PHONE,
            displayNameRes = R.string.ai_tool_get_step_count_name,
            descriptionRes = R.string.ai_tool_get_step_count_desc,
            risk = ToolRisk.READ_ONLY,
            statusText = "正在读取计步数据…",
            schema = toolSchema(
                name = "get_step_count",
                description = "读取手机计步传感器：自开机以来的**累计步数**（重启清零），可作今日活动量参考，不是精确的「今日步数」。当用户问「我今天走了多少步」「看看步数」时调用；回答时要如实说明这个口径。",
                parameters = mapOf("type" to "object", "properties" to emptyMap<String, Any>()),
            ),
        ),
    )

    override fun execute(context: Context, name: String, args: JSONObject): String {
        return when (name) {
            "search_contacts" -> {
                val name = args.optString("name").trim()
                if (name.isEmpty()) return "请提供要查找的联系人姓名"
                // 缺权限不再只回一句"请去设置里开"：统一交给 PermissionBridge 自动拉起系统授权界面
                // （前台直接弹窗；后台有悬浮窗也能拉起；都不可用时退通知栏提醒并如实回报）
                PermissionBridge.ensure(
                    context,
                    context.getString(R.string.permission_reason_contacts),
                    AppPermission.CONTACTS,
                )?.let { return it }
                val hits = PhoneTools.searchContacts(context, name)
                if (hits.isEmpty()) {
                    "通讯录中没有找到「$name」"
                } else {
                    hits.mapIndexed { i, c -> "[${i + 1}] ${c.name}：${c.number}" }.joinToString("\n")
                }
            }

            "call_phone" -> PhoneTools.dialPhone(context, args.optString("contact"))

            "send_sms" -> PhoneTools.sendSms(
                context,
                to = args.optString("to"),
                message = args.optString("message"),
            )

            "read_clipboard" -> PhoneTools.readClipboard(context)

            "write_clipboard" -> PhoneTools.writeClipboard(context, args.optString("text"))

            // 截屏：取图（复用投屏 / 独立授权）交给 ScreenCaptureTools，出图判定交给
            // VisionToolProvider.deliverImage —— 与眼镜相机共用同一套"视觉优先、否则 OCR"的规则
            "capture_screen" -> when (val shot = ScreenCaptureTools.capture(context)) {
                is ScreenCaptureTools.Shot.Fail -> shot.reason
                is ScreenCaptureTools.Shot.Ok -> VisionToolProvider.deliverImage(
                    context = context,
                    jpeg = shot.jpeg,
                    source = shot.source,
                    // 截图里没识别到文字时，别让模型去建议"开图像理解"以外的东西 —— 那就是唯一原因
                    noTextHint = "换一个支持看图的模型，或在聊天设置里开启「图像理解」。**不要**编造屏幕内容。",
                )
            }

            "set_phone_alarm" -> PhoneTools.setPhoneAlarm(
                context,
                message = args.optString("message", "闹钟"),
                hour = if (args.has("hour") && !args.isNull("hour")) args.optInt("hour") else null,
                minute = if (args.has("minute") && !args.isNull("minute")) args.optInt("minute") else null,
                minutesFromNow = if (args.has("minutesFromNow") && !args.isNull("minutesFromNow")) args.optLong("minutesFromNow") else null,
            )

            "open_phone_app" -> PhoneTools.openPhoneApp(context, args.optString("appName"))

            "list_phone_apps" -> PhoneTools.listPhoneApps(
                context,
                query = args.optString("query").trim().ifBlank { null },
                includeSystem = args.optBoolean("includeSystem", false),
            )

            "get_phone_status" -> PhoneTools.getPhoneStatus(context)

            // schema 虽标 volume 必填，但闸门不校验 required：模型漏传/传错类型时
            // optInt 默认值会静默把音量设成 50% —— 必须显式拒绝而非猜一个默认值
            "set_phone_volume" -> {
                val vol = if (args.has("volume") && !args.isNull("volume")) args.optInt("volume", -1) else -1
                if (vol in 0..100) PhoneTools.setPhoneVolume(context, vol)
                else "请提供 0~100 的音量值（volume）"
            }

            // 查询 / 创建二合一（原 query_calendar + add_calendar_event）：同一能力域，
            // 合并后模型在「改到明天吧」这类来回对话里不必两组 schema 反复挑。
            // 删 / 改走独立工具（delete_calendar_event / update_calendar_event）——
            // 删除是 EXTERNAL+BLOCK 走确认闸门，不能并进这个 LOCAL 档工具。
            "manage_calendar" -> when (args.optString("action").trim().lowercase()) {
                "create" -> PhoneTools.addCalendarEvent(
                    context,
                    title = args.optString("title"),
                    date = args.optString("date"),
                    startTime = args.optString("startTime"),
                    durationMinutes = args.optInt("durationMinutes", 60),
                    note = args.optString("note").trim().ifBlank { null },
                )
                "query" -> PhoneTools.queryCalendar(context, args.optString("date"))
                // 未知 action 不能静默按 query 处理：模型想删日程传错 action 时，
                // 拿到查询结果会误以为删除已生效
                else -> "action 只支持 query/create"
            }

            "delete_calendar_event" -> PhoneTools.deleteCalendarEvent(
                context,
                eventId = args.optLong("eventId", 0L),
                date = args.optString("date").trim().ifBlank { null },
                title = args.optString("title").trim().ifBlank { null },
            )

            "update_calendar_event" -> PhoneTools.updateCalendarEvent(
                context,
                eventId = args.optLong("eventId", 0L),
                date = args.optString("date").trim().ifBlank { null },
                title = args.optString("title").trim().ifBlank { null },
                newTitle = args.optString("newTitle").trim().ifBlank { null },
                newDate = args.optString("newDate").trim().ifBlank { null },
                newStartTime = args.optString("newStartTime").trim().ifBlank { null },
                newDurationMinutes = args.optInt("newDurationMinutes", 0).takeIf { it > 0 },
                newNote = if (args.has("newNote")) args.optString("newNote") else null,
            )

            "open_navigation" -> PhoneTools.openNavigation(
                context,
                destination = args.optString("destination"),
                mode = args.optString("mode", "drive"),
            )

            "uninstall_phone_app" -> PhoneTools.uninstallPhoneApp(
                context,
                packageName = args.optString("packageName"),
                appName = args.optString("appName"),
            )

            // schema 虽标 enabled 必填，但闸门不校验 required：模型漏传时 optBoolean
            // 会静默按 false 处理（想开灯却执行"关灯"）—— 必须显式拒绝而非猜默认值
            "set_flashlight" -> {
                if (!args.has("enabled") || args.isNull("enabled")) {
                    "请说明要开还是关手电筒（enabled 必填）"
                } else {
                    PhoneTools.setFlashlight(context, args.optBoolean("enabled"))
                }
            }

            "get_step_count" -> PhoneTools.readStepCount(context)

        else -> throw IllegalArgumentException("未知工具: $name")
        }
    }
}
