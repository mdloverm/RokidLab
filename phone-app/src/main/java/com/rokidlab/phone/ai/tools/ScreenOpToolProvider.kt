package com.rokidlab.phone.ai.tools

import android.content.Context
import android.graphics.Rect
import android.os.Build
import android.util.Log
import android.view.WindowManager
import com.rokidlab.phone.R
import com.rokidlab.phone.access.LabAccessibility
import com.rokidlab.phone.access.ScreenGuard
import com.rokidlab.phone.access.ScreenTree
import com.rokidlab.phone.ai.ToolContentTrust
import com.rokidlab.phone.ai.ToolRegistry
import com.rokidlab.phone.ai.ToolRisk
import com.rokidlab.phone.permission.AppPermission
import com.rokidlab.phone.permission.PermissionBridge
import org.json.JSONObject

/**
 * 屏幕操作域：**用无障碍能力在手机真实界面上替用户操作**（读屏 → 点击/输入/滑动/按键）。
 *
 * ## 与 `capture_screen`、`browser_*` 的分工
 *  - `capture_screen`：把屏幕**当图片**看（读通知、看报错、看聊天记录）。走无障碍截屏（零弹窗），
 *    服务没开时退到投屏/系统授权框。它只"看"，不动手。
 *  - `browser_*`：只在**本 App 自己那个 WebView** 里操作网页，能拿到 DOM 编号，最稳。
 *  - 本域：操作**任意 App** 的真实界面。看不到 DOM，靠 UI 树 + 无障碍手势，
 *    因此每一步都要先 `read_screen`（拿到编号与文案），点完再读一次确认。
 *
 * ## 编号协议与网页域一致
 * `read_screen` 给出 `[1] [2] …`，`tap_screen` 用 `ref` 引用 —— 编号只对最近一次读取有效，
 * 界面一变就作废（`read_screen` / `clear*` 的失效判定见 [ScreenTree]）。
 *
 * ## 确认粒度：只拦"提交类"与"裸坐标"
 * 五个工具全部按**本机副作用**登记（`read_screen` 也按本机档，理由见它的注释）——
 * 逐次过审批闸门会让「打开设置 → 进 Wi-Fi → 点进去」变成十几次打断。
 * "这一下要不要人命"收在 [ScreenGuard]：命中提交类文案、或**不给文案的坐标点击**时，
 * 工具不执行、返回「需要你确认：…」，模型必须如实复述、拿到用户同意后带 `confirmed=true` 重调。
 *
 * ⚠️ `confirmed=true` 是**提示词级**约定：它靠"模型会守规矩"成立，挡不住页面/第三方构造的直连调用
 * （AIUI 页面域已整域从 [com.rokidlab.phone.ai.approval.PageScope.ALLOWED_DOMAINS] 摘除，见那里的注释）。
 */
internal object ScreenOpToolProvider : ToolProvider {

    private const val TAG = "ScreenOpToolProvider"

    private const val TOOL_READ = "read_screen"
    private const val TOOL_TAP = "tap_screen"
    private const val TOOL_SWIPE = "swipe_screen"
    private const val TOOL_KEY = "press_key"
    private const val TOOL_TYPE = "type_text"

    /** 动作做完给模型的一句"下一步" —— 无障碍操作没有返回值可看，只能靠再读一次界面确认 */
    private const val VERIFY_HINT = "接着调用 read_screen 确认界面确实按预期变了。"

    override val toolNames = setOf(TOOL_READ, TOOL_TAP, TOOL_SWIPE, TOOL_KEY, TOOL_TYPE)

    override fun tools(): List<ToolEntry> = listOf(
        // ⚠️ read_screen 是**只读动作**，但风险档登记为 LOCAL_SIDE_EFFECT（不进只读档），
        // 与 capture_screen 同一个理由：它读的是**整个手机屏幕**（别人的消息、余额、验证码都在里面），
        // 而只读档会自动进入「无人值守的定时自主任务」——用户不在场时静默读屏不是产品意图。
        // 这不是风险分级的笔误，改它之前先读 ToolRiskMapTest 里那条不变式。
        ToolEntry(
            name = TOOL_READ,
            group = ToolRegistry.DOMAIN_SCREEN,
            displayNameRes = R.string.ai_tool_read_screen_name,
            descriptionRes = R.string.ai_tool_read_screen_desc,
            risk = ToolRisk.LOCAL_SIDE_EFFECT,
            // 界面上的文字来自任意 App（别人发来的消息、网页、广告），可能夹带注入话术
            contentTrust = ToolContentTrust.UNTRUSTED_EXTERNAL,
            statusText = "正在读取手机界面…",
            schema = toolSchema(
                name = TOOL_READ,
                description = "读取**手机当前界面**：前台是哪个 App、有哪些可点/可输入的元素（带编号 [n] 与坐标）、" +
                    "以及界面上的文字。用它代替「截图猜位置」：拿到编号后 tap_screen 就能精确点。" +
                    "⚠️ 编号只对**最近一次**读取有效，界面一变（点过/跳过页/刷新过）就要重新读一次再点。" +
                    "纯自绘界面（网页/游戏/部分小程序）可能读不到元素，这时改用 capture_screen 看图片。",
                parameters = mapOf("type" to "object", "properties" to emptyMap<String, Any>()),
            ),
        ),
        ToolEntry(
            name = TOOL_TAP,
            group = ToolRegistry.DOMAIN_SCREEN,
            displayNameRes = R.string.ai_tool_tap_screen_name,
            descriptionRes = R.string.ai_tool_tap_screen_desc,
            risk = ToolRisk.LOCAL_SIDE_EFFECT,
            // 点击可能触发不可逆动作（转账/发送/删除），存在"命令已送达、回执丢失"的形态 ⇒ 失败不自动重放
            sideEffect = true,
            // 返回值里带界面上的文案（外部作者可写）
            contentTrust = ToolContentTrust.UNTRUSTED_EXTERNAL,
            statusText = "正在点击手机屏幕…",
            schema = toolSchema(
                name = TOOL_TAP,
                description = "在**手机当前界面**上点击一个控件。三种给目标的方式，优先用前两种：" +
                    "①text：按文案点（如「发送」「设置」）；②ref：用最近一次 read_screen 的编号；" +
                    "③x/y：坐标（最不推荐，因为你不知道那个位置是什么）。" +
                    "⚠️ **必须遵守的分岔规则**：若返回值以「需要你确认：」开头，表示这一步可能让用户付出代价" +
                    "（按钮是发送/支付/删除类，或你给的是没有文案的坐标），**动作没有执行**；" +
                    "请把这一步如实告诉用户、等他说同意，之后才能带 confirmed=true 重新调用。" +
                    "⚠️ 点完记得 read_screen 确认界面变了；没变就重新读、换目标，不要反复点同一个位置。",
                parameters = mapOf(
                    "type" to "object",
                    "properties" to mapOf(
                        "text" to mapOf("type" to "string", "description" to "要点击的控件文案（推荐），如「发送」「Wi-Fi」"),
                        "ref" to mapOf("type" to "integer", "description" to "read_screen 给出的编号 [n]"),
                        "x" to mapOf("type" to "integer", "description" to "横坐标（仅在没有文案可用时；需要用户确认）"),
                        "y" to mapOf("type" to "integer", "description" to "纵坐标（仅在没有文案可用时；需要用户确认）"),
                        "confirmed" to mapOf("type" to "boolean", "description" to "用户已明确同意这一步时传 true；默认 false"),
                    ),
                ),
            ),
        ),
        ToolEntry(
            name = TOOL_SWIPE,
            group = ToolRegistry.DOMAIN_SCREEN,
            displayNameRes = R.string.ai_tool_swipe_screen_name,
            descriptionRes = R.string.ai_tool_swipe_screen_desc,
            risk = ToolRisk.LOCAL_SIDE_EFFECT,
            // 滑动基本等于滚动/翻页，重放一次的代价很小 ⇒ 允许瞬时失败重试
            statusText = "正在滑动手机屏幕…",
            schema = toolSchema(
                name = TOOL_SWIPE,
                description = "在手机当前界面上滑动（滚动列表、翻页、下拉刷新都靠它）。" +
                    "direction 是**手指的滑动方向**：up = 手指由下往上滑（内容向上滚，看更下面的内容），" +
                    "down = 手指由上往下滑（相当于下拉，常见于刷新）。也可以给 x1/y1/x2/y2 做精确滑动。" +
                    "⚠️ 滑动后要 read_screen 看结果；到列表底部再滑不会有新内容，这时如实告诉用户已经到底了。",
                parameters = mapOf(
                    "type" to "object",
                    "properties" to mapOf(
                        "direction" to mapOf("type" to "string", "description" to "滑动方向：up / down / left / right"),
                        "duration_ms" to mapOf("type" to "integer", "description" to "滑动时长毫秒（默认 300，越长越慢）"),
                        "x1" to mapOf("type" to "integer", "description" to "起点横坐标（与 y1/x2/y2 一起给，替代 direction）"),
                        "y1" to mapOf("type" to "integer", "description" to "起点纵坐标"),
                        "x2" to mapOf("type" to "integer", "description" to "终点横坐标"),
                        "y2" to mapOf("type" to "integer", "description" to "终点纵坐标"),
                    ),
                ),
            ),
        ),
        ToolEntry(
            name = TOOL_KEY,
            group = ToolRegistry.DOMAIN_SCREEN,
            displayNameRes = R.string.ai_tool_press_key_name,
            descriptionRes = R.string.ai_tool_press_key_desc,
            risk = ToolRisk.LOCAL_SIDE_EFFECT,
            statusText = "正在操作手机按键…",
            schema = toolSchema(
                name = TOOL_KEY,
                description = "按手机的系统按键：back（返回上一页）、home（回桌面）、recents（最近任务）、" +
                    "notifications（下拉通知栏）、quick_settings（下拉快捷开关，常用于开关 Wi-Fi/蓝牙）。" +
                    "⚠️ 返回上一页请优先用它，而不是去界面上找返回箭头 —— 各 App 的返回位置不统一。" +
                    "⚠️ 不支持锁屏/关机（锁了之后我解不开），用户要锁屏请让他自己按电源键。",
                parameters = mapOf(
                    "type" to "object",
                    "properties" to mapOf(
                        "key" to mapOf(
                            "type" to "string",
                            "description" to "按键名：back / home / recents / notifications / quick_settings",
                        ),
                    ),
                    "required" to listOf("key"),
                ),
            ),
        ),
        ToolEntry(
            name = TOOL_TYPE,
            group = ToolRegistry.DOMAIN_SCREEN,
            displayNameRes = R.string.ai_tool_type_text_name,
            descriptionRes = R.string.ai_tool_type_text_desc,
            risk = ToolRisk.LOCAL_SIDE_EFFECT,
            // 与拨号同理：存在"内容已写进去、回执丢失"的形态，重放会变成两段文字
            sideEffect = true,
            contentTrust = ToolContentTrust.UNTRUSTED_EXTERNAL,
            statusText = "正在输入文字…",
            schema = toolSchema(
                name = TOOL_TYPE,
                description = "往**当前已聚焦的输入框**里填文字（比如搜索框、聊天输入框）。" +
                    "⚠️ 必须先用 tap_screen 点一下那个输入框（或 read_screen 找到它再点），否则没有聚焦的框、会失败。" +
                    "⚠️ 输入框里原本的内容会被**整段替换**，不是追加。" +
                    "⚠️ 密码框我**不会**代填：会返回一句拒绝，请如实转告用户「请你自己输入密码」，不要向用户索要密码。" +
                    "⚠️ 填完不会自动提交，需要提交另行点击（发送/搜索类按钮会先要用户确认）。",
                parameters = mapOf(
                    "type" to "object",
                    "properties" to mapOf(
                        "text" to mapOf("type" to "string", "description" to "要填入的内容（原样填入，不要加引号或说明）"),
                        "confirmed" to mapOf("type" to "boolean", "description" to "用户已明确同意这次输入时传 true；默认 false"),
                    ),
                    "required" to listOf("text"),
                ),
            ),
        ),
    )

    override fun execute(context: Context, name: String, args: JSONObject): String = when (name) {
        TOOL_READ -> readScreen(context)
        TOOL_TAP -> tapScreen(context, args)
        TOOL_SWIPE -> swipeScreen(context, args)
        TOOL_KEY -> pressKey(context, args)
        TOOL_TYPE -> typeText(context, args)
        else -> throw IllegalArgumentException("未知工具: $name")
    }

    // ═══════════════════ 入口闸门 ═══════════════════

    /**
     * 无障碍能力就绪检查（每个工具的第一步）。
     *
     * @return `null` = 可以执行；非 `null` = **必须原样返回给模型并放弃本次动作**的说明。
     *
     * 缺"无障碍服务"这件事走 [PermissionBridge]（本项目唯一的权限申请入口）——它会把系统
     * 无障碍设置页拉起来；后台拉不起界面时退通知栏。**不要**在这里自己拼
     * "请去设置里开启无障碍服务" 的文案（RULES §12.17）。
     */
    private fun ensureReady(context: Context): String? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            return "这台手机是 Android ${Build.VERSION.RELEASE}，低于 Android 11：系统不支持无障碍读屏与手势注入。" +
                "只能用 capture_screen 看屏幕（会更慢，且每次要用户点一次系统授权框），也无法替用户点击。"
        }
        if (LabAccessibility.isConnected()) return null
        val pending = PermissionBridge.ensure(
            context,
            "读取并操作手机界面",
            AppPermission.ACCESSIBILITY,
        )
        if (pending != null) return pending
        // 开关开着但服务实例还没连上（刚打开设置页返回时会有一两秒）
        return "无障碍服务已经开启，系统还没把它连上。请过一两秒重试一次；若一直失败，让用户到系统设置里把「乐奇实验室」无障碍服务关掉再重新打开。"
    }

    // ═══════════════════ 读屏 ═══════════════════

    private fun readScreen(context: Context): String {
        ensureReady(context)?.let { return it }
        return when (val result = ScreenTree.read()) {
            is ScreenTree.Read.Fail -> result.reason
            is ScreenTree.Read.Ok -> format(context, result.screen)
        }
    }

    /** 把读屏结果排成给模型的文本（编号清单 + 正文，超出部分如实标注已截断） */
    private fun format(context: Context, screen: ScreenTree.Screen): String {
        val appLabel = appLabelOf(context, screen.packageName)
        val builder = StringBuilder()
        builder.append("当前界面：")
            .append(appLabel ?: screen.packageName.ifBlank { "（未知）" })
            .append("（").append(screen.packageName.ifBlank { "?" }).append("）\n")

        if (screen.refs.isEmpty()) {
            builder.append("可交互元素：这个界面没读到可点/可输入的控件（可能是自绘界面）——")
                .append("可以考虑 capture_screen 看图片，或用 press_key 的 back 退出。\n")
        } else {
            builder.append("可交互元素（编号只对这次读取有效）：\n")
            screen.refs.forEach { builder.append("  ").append(it.describe()).append('\n') }
            if (screen.refs.size >= ScreenTree.MAX_REFS) {
                builder.append("  （元素过多，只列了最靠前的 ${ScreenTree.MAX_REFS} 个，可先滚动再读）\n")
            }
        }

        if (screen.texts.isNotEmpty()) {
            builder.append("界面文字：\n")
            screen.texts.forEach { builder.append("  ").append(it).append('\n') }
        }
        if (screen.truncated) {
            builder.append("（内容较多，已截断；需要更全的内容可以先滚动再读一次）\n")
        }
        return builder.toString().trimEnd()
    }

    /** 包名 → 应用名（查不到就返回 null，由调用方回落到包名） */
    private fun appLabelOf(context: Context, packageName: String): String? {
        if (packageName.isBlank()) return null
        return runCatching {
            val pm = context.packageManager
            pm.getApplicationLabel(pm.getApplicationInfo(packageName, 0)).toString()
        }.getOrNull()
    }

    // ═══════════════════ 点击 ═══════════════════

    private fun tapScreen(context: Context, args: JSONObject): String {
        ensureReady(context)?.let { return it }
        val confirmed = args.optBoolean("confirmed", false)
        val ref = args.optInt("ref", 0)
        val text = args.optString("text").trim()
        val hasCoords = args.has("x") && args.has("y")

        // 三种目标按"模型知不知道点的是什么"排序，与 schema 里的推荐顺序一致
        return when {
            ref > 0 -> tapRef(ref, confirmed)
            text.isNotEmpty() -> tapText(text, confirmed)
            hasCoords -> tapCoords(args.optInt("x"), args.optInt("y"), confirmed)
            else -> "没给点击目标：请传 text（控件文案）、ref（read_screen 的编号）或 x/y（坐标）"
        }
    }

    private fun tapRef(ref: Int, confirmed: Boolean): String {
        if (!ScreenTree.refExists(ref)) {
            return "编号 $ref 不在最近一次 read_screen 的结果里。请先 read_screen 拿到当前界面的编号再点"
        }
        val label = ScreenTree.labelOfRef(ref)
        if (!confirmed) {
            if (label.isBlank()) {
                return ScreenGuard.message("点击编号 $ref", ScreenGuard.BLIND_TAP_REASON)
            }
            ScreenGuard.confirmReason(label)?.let { why ->
                return ScreenGuard.message("点击「$label」（编号 $ref）", why)
            }
        }
        return when (val click = ScreenTree.clickRef(ref)) {
            is ScreenTree.Click.Fail -> click.reason
            is ScreenTree.Click.Ok -> "已${click.via}。$VERIFY_HINT"
        }
    }

    private fun tapText(text: String, confirmed: Boolean): String {
        if (!confirmed) {
            ScreenGuard.confirmReason(text)?.let { why ->
                return ScreenGuard.message("点击「$text」", why)
            }
        }
        return when (val click = ScreenTree.clickByLabel(text, exact = false)) {
            is ScreenTree.Click.Fail -> click.reason
            is ScreenTree.Click.Ok -> "已${click.via}。$VERIFY_HINT"
        }
    }

    private fun tapCoords(x: Int, y: Int, confirmed: Boolean): String {
        if (!confirmed) {
            return ScreenGuard.message("在 ($x, $y) 点一下", ScreenGuard.BLIND_TAP_REASON)
        }
        return if (LabAccessibility.tap(x, y)) {
            "已在 ($x, $y) 点了一下。$VERIFY_HINT"
        } else {
            "坐标点击被系统拒绝（通常是有另一次手势还在执行），请稍后重试"
        }
    }

    // ═══════════════════ 滑动 ═══════════════════

    private fun swipeScreen(context: Context, args: JSONObject): String {
        ensureReady(context)?.let { return it }
        val duration = args.optLong("duration_ms", 300L).coerceIn(60L, 3_000L)
        if (args.has("x1") && args.has("y1") && args.has("x2") && args.has("y2")) {
            val ok = LabAccessibility.swipe(
                args.optInt("x1"), args.optInt("y1"), args.optInt("x2"), args.optInt("y2"), duration,
            )
            return if (ok) "已按给定坐标滑动。$VERIFY_HINT" else "滑动被系统拒绝（通常是有另一次手势还在执行），请稍后重试"
        }

        val bounds = screenBounds(context)
        if (bounds.width() <= 0 || bounds.height() <= 0) return "拿不到屏幕尺寸，无法按方向滑动；请改用 x1/y1/x2/y2"
        val cx = bounds.width() / 2
        val cy = bounds.height() / 2
        // 起止点取 30%~70%，避开状态栏/导航栏与边缘手势区（贴边的滑动会被系统当成返回手势）
        val near = (bounds.height() * 0.7f).toInt()
        val far = (bounds.height() * 0.3f).toInt()
        val left = (bounds.width() * 0.3f).toInt()
        val right = (bounds.width() * 0.7f).toInt()

        val (x1, y1, x2, y2) = when (args.optString("direction").trim().lowercase()) {
            "up" -> listOf(cx, near, cx, far)
            "down" -> listOf(cx, far, cx, near)
            "left" -> listOf(right, cy, left, cy)
            "right" -> listOf(left, cy, right, cy)
            else -> return "不认识的 direction（可用：up / down / left / right），或给 x1/y1/x2/y2 做精确滑动"
        }
        val ok = LabAccessibility.swipe(x1, y1, x2, y2, duration)
        return if (ok) "已向 $x1,$y1 → $x2,$y2 滑动。$VERIFY_HINT" else "滑动被系统拒绝（通常是有另一次手势还在执行），请稍后重试"
    }

    /**
     * 屏幕尺寸（方向滑动的坐标换算用）。
     *
     * 用 `currentWindowMetrics` 而不是 `Resources.displayMetrics`：分屏/多窗口下后者给的是
     * 整块显示屏的尺寸，滑动起点可能落在本窗口之外，手势会被系统丢弃。
     * 本函数只在 [ensureReady] 通过后调用（那条路径已经保证 API ≥ 30）。
     */
    @android.annotation.TargetApi(Build.VERSION_CODES.R)
    private fun screenBounds(context: Context): Rect {
        val wm = context.getSystemService(Context.WINDOW_SERVICE) as? WindowManager
        val bounds = wm?.currentWindowMetrics?.bounds
        if (bounds != null && bounds.width() > 0 && bounds.height() > 0) return bounds
        Log.w(TAG, "currentWindowMetrics unavailable, fall back to display metrics")
        val metrics = context.resources.displayMetrics
        return Rect(0, 0, metrics.widthPixels, metrics.heightPixels)
    }

    // ═══════════════════ 按键 ═══════════════════

    private fun pressKey(context: Context, args: JSONObject): String {
        ensureReady(context)?.let { return it }
        val raw = args.optString("key").trim()
        val key = LabAccessibility.Key.parse(raw)
            ?: return "不支持的按键「$raw」（可用：${LabAccessibility.Key.supported}）"
        return if (LabAccessibility.pressKey(key)) {
            "已执行「${key.label}」。$VERIFY_HINT"
        } else {
            "系统拒绝了「${key.label}」（无障碍服务可能刚被关掉），请重试或让用户手动操作"
        }
    }

    // ═══════════════════ 输入文字 ═══════════════════

    private fun typeText(context: Context, args: JSONObject): String {
        ensureReady(context)?.let { return it }
        val text = args.optString("text")
        if (text.isEmpty()) return "没有给要输入的内容（text）"
        val confirmed = args.optBoolean("confirmed", false)

        val field = ScreenTree.focusedField()
            ?: return "现在没有聚焦的输入框：请先用 tap_screen 点一下要输入的位置（或用 read_screen 找到输入框再点），" +
                "然后再调 type_text"
        if (!field.editable) {
            return "当前聚焦的不是输入框（${field.className.substringAfterLast('.').ifBlank { "未知控件" }}），" +
                "请先点一下真正要输入的位置"
        }
        if (field.password) {
            // 与「网页登录让用户自己输」同一条口径：本 App 不做密码代填（见 LabAccessibility.typeText）
            return "这是密码输入框，我不会代填密码。请如实告诉用户「请你自己在手机上输入密码」，不要向用户索要密码。"
        }
        if (!confirmed) {
            ScreenGuard.confirmReason(field.label)?.let { why ->
                return ScreenGuard.message("在输入框「${field.label}」里填入内容", why)
            }
        }

        return when (val typing = LabAccessibility.typeText(text)) {
            LabAccessibility.Typing.Ok -> "已填入 ${text.length} 个字符（原内容已被替换）。$VERIFY_HINT"
            LabAccessibility.Typing.NoFocus -> "输入框失去了焦点（可能界面变了），请重新读屏并重新点一下输入框"
            LabAccessibility.Typing.NotEditable -> "当前聚焦的位置不能输入文字，请重新点一下要输入的框"
            LabAccessibility.Typing.Password ->
                "这是密码输入框，我不会代填密码。请如实告诉用户「请你自己在手机上输入密码」。"
            is LabAccessibility.Typing.Fail -> typing.reason
        }
    }
}
