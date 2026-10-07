package com.rokidlab.phone.ai

/**
 * ToolIntentRouter —— 任务意图路由（2026-09-29，结构性省 token 的第二刀）。
 *
 * ## 存在的理由
 *
 * 主 Agent 每轮全量下发 ~83 个工具 Schema（≈ 1.5~2.5 万 input token）。闲聊快路
 * （[ChatFastPath]）已把「纯闲聊轮」砍到 0 个工具，但「现在这里的天气」这类**任务轮**
 * 仍然全价 —— 而它实际只需要 `get_weather` + `get_current_time` 共 2 个 Schema
 * （查天气无需 get_location：`get_weather` 省略 city 时内部自动定位，2026-09-29 起）。
 * 本路由把这类高置信意图从 83 砍到个位数。
 *
 * ## 判据：白名单关键词 → 工具名集合（不按域）
 *
 * 域是粗粒度的（`get_weather` 在 web 域、`get_location` 在 phone 域），按域装配会捎带
 * 一堆无关 Schema。所以路由结果直接给**工具名集合**，由主循环从全量装配里按名截取。
 *
 * ## 三层防线（与闲聊快路同一套哲学：宁可漏放、不可误放）
 *
 *  1. **逐规则冲突排除**（[TASK_SHAPED_CONFLICTS]）：写代码/生成页面/翻译/截图这类**任务形状**
 *     只否决与它冲突的那条意图（「写个天气 app」否决 weather），不再像早期实现那样一个词
 *     否决全部意图 —— 后者把「打开音乐」「查一下快递」这类日常轮次也误杀成全价；
 *  2. 关键词只收高置信说法，拿不准的一概不收 —— 漏放的代价只是「这轮多花两万 token」（维持原状）；
 *  3. 运行时逃生舱（主循环接线，与本文件约定配套）：路由轮的人设/上下文里注入 [escapeNote]，
 *     模型发现手头的子集不够用时只回一行 `[NEED_TOOLS]`（[ChatFastPath.NEED_TOOLS_MARKER]），
 *     主循环立即挂全量重答。子集贯穿本轮**不自动扩全量**（2026-09-29 实测：扩全量让工具循环
 *     每轮背全价 83 个 Schema，一条天气消息烧出 42.4k）；多步意图由多规则并集覆盖。
 */
internal object ToolIntentRouter {

    /** 路由输入长度上限：超长多半是粘贴的任务描述，绝不路由 */
    private const val MAX_LEN = 64

    /**
     * 菜单探测轮直接挂载的**引导工具集**（2026-09-29，用户提议）。
     *
     * 探测轮不再零工具空手探测：模型手头有联网搜索就能先查证（「怎么查快递」→ 搜出
     * 网页可查 → fetch_webpage 直接办了），有时间锚点才能解析「明天/后天」。这让
     * 「清单不够用 → [NEED_TOOLS] → 全量 83」的兜底大幅变稀 —— 大量信息类任务
     * 靠引导集 + 菜单点名就能闭环，全量只剩「模型坚持要一个清单里没有的能力」这一种触发。
     */
    val BOOTSTRAP_TOOLS: Set<String> = setOf("search_web", "fetch_webpage", "get_current_time")

    /**
     * 单条意图规则：关键词/正则命中 → 只下发 [tools] 里这些工具。
     * 工具名必须与各 `*ToolProvider.tools()` 里的注册名逐字一致（改工具名要同步这里）。
     */
    private class Rule(
        val name: String,
        val keywords: Set<String> = emptySet(),
        val patterns: List<Regex> = emptyList(),
        val tools: Set<String>,
        /**
         * **独占优先级**：非空时，只有当**没有任何更高优先级规则命中**（见 [SPECIFIC_RULES]），
         * 本规则才参与并集。
         *
         * ## 为什么需要它：通用词并集膨胀（2026-10-07 实测）
         *
         * `app` 规则的关键词是「打开/启动」，`search` 是「查一下/帮我查」—— 这些是
         * **通用动作词**，任何句子都可能出现。而 music/timer/weather 规则的关键词
         * （音乐/闹钟/天气）是**领域词**，指向性明确。
         *
         * 两类同时命中时并集会白白变大：
         *  - 「打开音乐」→ music(2) ∪ app(2) = **4**（多带的 open_phone_app 纯浪费）
         *  - 「帮我打开闹钟」→ timer(3) ∪ app(2) = **5**
         *  - 「查一下明天天气」→ weather(2) ∪ search(2) = **4**
         *
         * 命中率涨了但token 被回吐 —— 这是「子集不自动扩全量」的**反面**：
         * 不是自动扩全量，而是规则之间自动并集。
         *
         * ## 判据：用**领域词优先**，不用否决词
         *
         * 「打开音乐」该走 music（「音乐」比「打开」更具体）。所以 `app`/`search`
         * 声明 [priority] = 1，凡有领域规则（priority 0）命中就自动让位。
         * **不是把「打开」从 music 里否决掉** —— 那样「查一下快递」这类纯动作句
         * 会连search 一起失效。
         */
        val priority: Int = 0,
    )

    /** 优先级 0 = 领域规则（音乐/天气/闹钟…），命中即压过通用动作规则。 */
    private val SPECIFIC_PRIORITY = 0

    /** 优先级 1 = 通用动作规则（app 打开/启动、search 查一下），仅在无领域规则时生效。 */
    private val GENERIC_PRIORITY = 1

    /** 算式意图：消息里含「数字 运算符」形状（378*56、(128+64)×12…） */
    private val CALC_EXPR = Regex("\\d\\s*[+\\-*/×÷^]")

    /** 折扣意图：「85折」「打8折」 */
    private val DISCOUNT = Regex("\\d\\s*折")

    private val RULES = listOf(
        Rule(
            "weather",
            keywords = setOf(
                "天气", "气温", "下雨", "下雪", "降雨", "降温", "冷不冷", "热不热", "冷吗", "热吗",
                "穿什么", "带伞", "雨伞", "台风", "雾霾", "空气质量", "湿度",
            ),
            tools = setOf("get_weather", "get_current_time"),
        ),
        Rule(
            "time",
            keywords = setOf("几点", "几号", "多少号", "星期", "礼拜", "日期", "现在时间", "哪一天", "哪天"),
            tools = setOf("get_current_time"),
        ),
        Rule(
            "location",
            keywords = setOf("我在哪", "我在哪儿", "这是哪里", "这是哪儿", "我的位置", "我现在在", "定位", "附近", "周边"),
            tools = setOf("get_location", "search_web"),
        ),
        Rule(
            "calc",
            keywords = setOf("等于多少", "等于几", "计算", "算一下", "算算", "打几折", "打折", "百分之", "百分比", "次方", "开方", "开根"),
            patterns = listOf(CALC_EXPR, DISCOUNT),
            tools = setOf("calculate", "get_current_time"),
        ),
        Rule(
            "music",
            keywords = setOf("放一首", "放首歌", "来首歌", "来首", "播放", "听歌", "放音乐", "下一首", "上一首", "暂停", "音乐", "歌"),
            tools = setOf("control_music", "get_now_playing"),
        ),
        Rule(
            "timer",
            keywords = setOf("闹钟", "定时", "提醒", "倒计时", "计时", "叫我", "叫醒"),
            tools = setOf("set_phone_alarm", "manage_timer", "schedule_agent_task"),
        ),
        Rule(
            "call",
            keywords = setOf("打电话", "打给", "拨打", "呼叫"),
            tools = setOf("search_contacts", "call_phone"),
        ),
        Rule(
            "calendar",
            keywords = setOf("日程", "日历", "行程"),
            tools = setOf("manage_calendar", "get_current_time"),
        ),
        Rule(
            "runs",
            // 后台/交互任务的状态问询（「跑完了吗」「还在跑吗」「刚才输出是什么」）。
            // ⚠️ 只收"查状态"的短句；带执行意图的（「帮我跑」「后台下载」）不在此列 ——
            // 那些句子里往往还有别的诉求，宁可落回全量。
            keywords = setOf("跑完了吗", "还在跑吗", "还在运行吗", "运行状态", "后台任务", "任务输出", "刚才的输出"),
            tools = setOf("list_runs", "read_run_output"),
        ),
        // ── 以下三条2026-10-07 按覆盖率探针（ToolRouteCoverageProbe）补 ──
        // 之前这些高频轮次全价背105 个 Schema，原因是压根没规则，不是被误杀。
        Rule(
            "search",
            // 「查快递/查订单/搜一下 X」这类**信息查询** ⇒联网检索即可解决。
            // ⚠️ 只收「查询/搜索」语义；「订外卖」「叫车」是**下单动作**，本工具办不了，
            // 交location 也不对 —— 让它们落全量（无对应工具，属能力缺口）。
            keywords = setOf("查快递", "快递到哪", "物流", "查订单", "查物流", "搜索结果", "帮我查", "查一下"),
            tools = setOf("search_web", "fetch_webpage"),
            // 通用动作词：「查一下明天天气」已有 weather 领域规则，不该再捎带 search
            priority = GENERIC_PRIORITY,
        ),
        Rule(
            "app",
            // 「打开空调」这类**开/关手机应用**。open_phone_app 同时能列应用与启动，
            // 带上 list_phone_app 让模型先确认应用名存在，不必在全量里摸索。
            // ⚠️ 高危动作（发短信/卸载/安装）**刻意不收** —— 它们在 ApprovalGate 里
            // 必然 Deny，路由给它们等于让用户以为能直接办。
            keywords = setOf("打开", "启动", "开一下", "帮我打开", "帮我启动"),
            tools = setOf("open_phone_app", "list_phone_apps"),
            // 通用动作词：「打开音乐」让位 music、「帮我打开闹钟」让位 timer
            priority = GENERIC_PRIORITY,
        ),
        Rule(
            "nearby",
            // 「附近有什么好吃的」这类**本地生活检索** ⇒ 搜索为主、GPS 只做定位参考。
            // ⚠️ 「搜索/搜一下/查一下」对**这条**规则是否决（见 TASK_SHAPED_CONFLICTS）——
            // 用户说「搜一下附近的超市」时真正要的是搜索工具，不该顺手把 GPS 带进来；
            // 只说「附近有什么」不带搜索词才给 get_location。
            keywords = setOf("附近有什么", "周边有什么", "附近有什么好吃的", "附近推荐", "周边推荐", "附近有哪些"),
            tools = setOf("search_web", "get_location"),
        ),
    )

    /**
     * 任务形状词 → **被它否决的规则名**集合。
     *
     * ## 为什么是「逐规则」而不是早期的一刀切
     *
     * 早期实现是一份全局 `STRUCTURAL_HINTS` 列表，句子里出现任意一个词就整体不路由。
     * 意图本意是拦「看着像意图、实际是任务」的句子（写个天气 app ≠ 查天气），但它把
     * 「打开音乐」「查一下快递」「应用商店」这类**日常高频轮次**一起误杀了 —— 那批词
     * 恰恰不是任务形状，只是恰好带了「动作感」。结果是路由只覆盖 14/105 个工具，
     * 而这些轮次白白全价背105 个 Schema。
     *
     * ## 三类归属
     *
     *  - `ALL`：真任务形状，对任何意图都否决（写/代码/页面/翻译/截图…）。保留原语义。
     *  - 具体规则名：只否决那一条。「帮我搜一下附近的超市」里 `搜索` 对 location 是
     *    「别顺手把读GPS 的 get_location 也带进来」的意思（搜超市不需要定位），
     *    而对 weather 完全无害 —— 逐规则之后，一句话里的多个意图能各走各的。
     *  - 不出现：不否决。「打开」之于 music 是**正向信号**（「打开音乐」= 放歌），
     *    早期把它当结构排除词，等于把音乐轮次全价背105 个 Schema。
     *
     * ⚠️ 锁 `ToolIntentRouterTest.task-shaped sentences are never routed` 与
     * `true task shapes still block every rule` —— 加词前先想清楚它在哪条规则上是反向信号。
     */
    private const val ALL = "*"

    private val TASK_SHAPED_CONFLICTS: Map<String, Set<String>> = mapOf(
        // 真任务形状：写/做/生成一个东西出来，与任何意图都不冲突地属于「任务」
        ALL to setOf(
            "写", "做一", "做个", "生成", "页面", "代码", "网页", "网站", "http", "www.", ".com", ".cn",
            "aiui", "翻译", "知识库", "文件", "视频", "图片", "画", "截图", "截屏", "拍照", "眼镜",
            "安装", "卸载",
        ),
        // 「写个天气 app」「做一个天气页面」—— 对天气是造物，不是查询
        //（只挂 weather：time 无此歧义，「天气app几点更新」该照常路由）
        "weather" to setOf("应用", "app"),
        // 搜索是独立任务，不抢 location 路由（否则「搜下附近的超市」会把 GPS 工具带进来）
        "location" to setOf("搜索", "搜一下", "查一下", "查查"),
        // 同理对 nearby：用户明说「搜一下」就只给搜索工具，别顺手把 get_location 塞进来
        "nearby" to setOf("搜索", "搜一下", "查一下", "查查"),
        // ⚠️ 「打开」对 app 规则是**正向信号**，但对音乐是替代说法（「打开音乐」）。
        // 不否决 music ⇒ 打开音乐仍只带2 个工具（2026-10-07 探针 B 组必须保持 100%）。
    )

    /**
     * 是否路由 + 路由到哪些工具。返回 null = 不路由（主循环照旧装配全量）。
     * 多意图取并集（「查下天气然后明早七点提醒我」→ 天气∪提醒）。
     */
    fun route(text: String): Set<String>? {
        val t = text.trim()
        if (t.isEmpty() || t.length > MAX_LEN) return null
        // 多行：粘贴的任务描述，不路由
        if (t.contains('\n') || t.contains('\r')) return null
        val lower = t.lowercase()
        // 全局任务形状：一眼就是「要造个东西出来」，任何意图都不路由
        if (hasGlobalTaskShape(lower)) return null
        val matched = RULES.filter { rule ->
            val hit = rule.keywords.any { lower.contains(it) } ||
                rule.patterns.any { it.containsMatchIn(t) }
            hit && !hasRuleLevelConflict(lower, rule.name)
        }
        if (matched.isEmpty()) return null
        // 通用动作规则让位给领域规则（「打开音乐」走 music，不捎带 open_phone_app）
        val specific = matched.filter { it.priority == SPECIFIC_PRIORITY }
        val effective = if (specific.isNotEmpty()) specific else matched
        return effective.flatMap { it.tools }.toSet()
    }

    /** 命中全局任务形状词表（写/代码/页面/翻译/截图…）。 */
    private fun hasGlobalTaskShape(lower: String): Boolean =
        TASK_SHAPED_CONFLICTS[ALL]!!.any { lower.contains(it) }

    /**
     * 该规则是否被**自己那一份**任务形状词表否决。
     *
     * 注意是**否决单条规则**而非整体落回全量 —— 这正是与早期一刀切的区别：
     * 「帮我搜一下附近的超市」里的「搜一下」只否决 location（避免顺手把读 GPS 的
     * `get_location` 带进来），而同一句若还有 weather 意图，weather 规则不受影响。
     */
    private fun hasRuleLevelConflict(lower: String, ruleName: String): Boolean =
        TASK_SHAPED_CONFLICTS[ruleName]?.any { lower.contains(it) } == true

    /**
     * 菜单探测轮的点名标记：模型从工具菜单里选中工具时按约定只回复这一行 +
     * 工具名清单（分隔符随意，解析按「注册表已知名字是否出现在回复里」宽匹配）。
     */
    const val TOOLS_PICK_MARKER = "[TOOLS]"

    /**
     * 解析菜单探测轮的点名回复。
     *
     * 返回值三态：
     *  - `null` = 回复里没有 [TOOLS_PICK_MARKER]（模型直接作答了 —— 调用方按最终回复收尾；
     *    注意它对 `[NEED_TOOLS]` 也返回 null，那个标记由 [ChatFastPath.needsUpgrade] 判定）；
     *  - 空集 = 有标记但一个已知工具名都没对上（调用方应全量兜底，不能让模型空手）；
     *  - 非空 = 点中的已知工具名。只认注册表里的名字 —— 模型拼错、编造的名字自动丢弃。
     *
     * 匹配是「名字是否出现在回复里」的宽松策略：不依赖分隔符/格式，模型用顿号、
     * 逗号、换行列名单甚至带句前缀都解析得出来。
     */
    fun parseToolPick(content: String?, known: Set<String>): Set<String>? {
        val c = content?.trim().orEmpty()
        if (c.isEmpty() || c.length > 2000) return null
        if (!c.contains(TOOLS_PICK_MARKER, ignoreCase = true)) return null
        return known.filter { c.contains(it, ignoreCase = true) }.toSet()
    }

    /**
     * 路由轮注入上下文尾部的逃生舱说明：告知模型本轮只有子集、不够用时按约定喊 [NEED_TOOLS]。
     * 与 [ChatFastPath.needsUpgrade] 共用同一个标记 —— 升级判定只有一份。
     */
    fun escapeNote(names: Set<String>): String =
        "【工具子集提示】本轮只下发了与用户这条请求直接相关的少量工具（${names.sorted().joinToString("、")}）。" +
            "如果你判断必须使用其他工具才能正确完成请求，请只回复一行 " + ChatFastPath.NEED_TOOLS_MARKER +
            "（不要输出任何其他内容），系统会挂载全量工具让你重新作答。"

    /**
     * 路由子集轮的回复是否是「约定标记开头」—— 子集轮里模型正常作答不会以 `[` 开头，
     * 以它开头基本只可能是约定标记（含模型把 [NEED_TOOLS]/[TOOLS] 写变形的情况）。
     * 调用方据此升级全量，防止把标记当正文播给用户。
     */
    fun isMarkerLike(content: String?): Boolean =
        content?.trim().orEmpty().startsWith("[")
}
