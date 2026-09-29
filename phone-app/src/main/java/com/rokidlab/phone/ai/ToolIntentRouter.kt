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
 *  1. [STRUCTURAL_HINTS] 结构排除：写代码/生成页面/翻译/文件/截图等任务性字眼，
 *     哪怕同时含意图关键词（「写个天气 app」）也一律不路由 → 落回全量，绝不因省 token 丢能力；
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
    )

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
    )

    /**
     * 结构排除：命中任何一个就不路由（落回全量）。专门拦「看着像意图、实际是任务」的句子 ——
     * 「写个天气 app」「做一个天气页面」「把天气翻译成英文」都不能只带天气工具。
     */
    private val STRUCTURAL_HINTS = listOf(
        "写", "做一", "做个", "生成", "页面", "代码", "网页", "网站", "http", "www.", ".com", ".cn",
        "aiui", "翻译", "知识库", "文件", "视频", "图片", "画", "截图", "截屏", "拍照", "眼镜",
        "打开", "启动", "安装", "卸载", "应用", "app", "搜索", "搜一下", "查一下", "查查",
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
        if (STRUCTURAL_HINTS.any { lower.contains(it) }) return null
        val hits = RULES.filter { rule ->
            rule.keywords.any { lower.contains(it) } || rule.patterns.any { it.containsMatchIn(t) }
        }
        if (hits.isEmpty()) return null
        return hits.flatMap { it.tools }.toSet()
    }

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
