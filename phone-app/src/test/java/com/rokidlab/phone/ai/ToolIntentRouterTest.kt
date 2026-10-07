package com.rokidlab.phone.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [ToolIntentRouter] 判定单测：白名单命中 / 结构排除 / 多意图并集 / 逃生舱说明。
 * 纯函数测试，不依赖 ToolRegistry 与 Android 环境。
 */
class ToolIntentRouterTest {

    // ── 天气：用户点名的场景 ──────────────────────────────────────────────

    @Test
    fun `weather intent routes to the minimal weather toolset`() {
        // 「现在这里的天气」= 天气 + 时间（解析「后天」用）。不含 get_location ——
        // get_weather 省略 city 时内部自动定位（2026-09-29），多带一个 Schema 就是白花钱
        val routed = ToolIntentRouter.route("现在这里的天气")!!
        assertEquals(setOf("get_weather", "get_current_time"), routed)

        assertEquals(
            setOf("get_weather", "get_current_time"),
            ToolIntentRouter.route("明天会下雨吗"),
        )
        assertEquals(
            setOf("get_weather", "get_current_time"),
            ToolIntentRouter.route("杭州冷不冷"),
        )
        assertEquals(
            setOf("get_weather", "get_current_time"),
            ToolIntentRouter.route("今天需要带伞吗"),
        )
    }

    // ── 其他单意图 ────────────────────────────────────────────────────────

    @Test
    fun `time intent only carries get_current_time`() {
        assertEquals(setOf("get_current_time"), ToolIntentRouter.route("现在几点"))
        assertEquals(setOf("get_current_time"), ToolIntentRouter.route("今天星期几"))
    }

    @Test
    fun `location intent carries location plus web search`() {
        assertEquals(
            setOf("get_location", "search_web"),
            ToolIntentRouter.route("我附近有什么好吃的"),
        )
    }

    @Test
    fun `calc intent via keywords and expression shapes`() {
        // 关键词
        assertTrue(ToolIntentRouter.route("378乘以56等于多少")!!.contains("calculate"))
        // 算式形状（数字+运算符）
        assertTrue(ToolIntentRouter.route("128+64再乘12是多少")!!.contains("calculate"))
        // 折扣形状
        assertTrue(ToolIntentRouter.route("599打85折是多少钱")!!.contains("calculate"))
    }

    @Test
    fun `music timer call calendar intents route`() {
        assertTrue(ToolIntentRouter.route("放一首晴天")!!.contains("control_music"))
        assertTrue(ToolIntentRouter.route("明早七点叫我起床")!!.contains("set_phone_alarm"))
        assertTrue(ToolIntentRouter.route("给妈妈打电话")!!.contains("call_phone"))
        assertTrue(ToolIntentRouter.route("我今天日程有什么")!!.contains("manage_calendar"))
    }

    // ── 多意图并集 ────────────────────────────────────────────────────────

    @Test
    fun `compound intent unions both toolsets`() {
        val routed = ToolIntentRouter.route("查下天气然后明早七点提醒我")!!
        assertTrue(routed.contains("get_weather"))
        assertTrue(routed.containsAll(setOf("set_phone_alarm", "manage_timer")))
    }

    // ── 结构排除：看着像意图、实际是任务 → 不路由（落回全量） ──────────

    @Test
    fun `task-shaped sentences are never routed`() {
        assertNull(ToolIntentRouter.route("写个天气app"))
        assertNull(ToolIntentRouter.route("做一个天气页面"))
        assertNull(ToolIntentRouter.route("把今天的天气生成一张图片"))
        assertNull(ToolIntentRouter.route("帮我搜一下附近的超市")) // 搜索是独立任务，不抢 location 路由
    }

    // ── 逐规则冲突（2026-10-07）：动作感词不是任务形状 ──────────────────
    //
    // 早期是一份全局 STRUCTURAL_HINTS 一票否决，把「打开音乐」「查一下快递」这类
    // 日常高频轮次一起误杀成全价（路由只覆盖 14/105）。现改为逐规则否决。

    @Test
    fun `action verbs no longer kill unrelated intents`() {
        // 「打开」不再否决 music —— 这类轮次该省成2 个 Schema，而不是背 105 个
        assertTrue(ToolIntentRouter.route("打开音乐")!!.contains("control_music"))
        assertTrue(ToolIntentRouter.route("启动音乐")!!.contains("control_music"))
        // 「查一下」对 weather 无害：查天气就该路由
        assertTrue(ToolIntentRouter.route("查一下明天的天气")!!.contains("get_weather"))
        assertTrue(ToolIntentRouter.route("帮我打开闹钟")!!.contains("set_phone_alarm"))
    }

    @Test
    fun `rule level conflict only blocks its own rule`() {
        // location 被「搜一下」否决 → 无其他命中 ⇒ null（保住task-shaped 那条语义）
        assertNull(ToolIntentRouter.route("帮我搜一下附近的超市"))
        // 同一句里 weather 规则不受「搜一下」影响：取并集后仍能路由
        val mixed = ToolIntentRouter.route("搜一下附近的超市顺便看天气")
        assertTrue(mixed!!.contains("get_weather"))
        // 「应用/app」只否决 weather+time：写个天气 app 落回全量，但同句的 time 仍成立
        assertNull(ToolIntentRouter.route("写个天气app"))
        assertTrue(ToolIntentRouter.route("天气app几点更新")!!.contains("get_current_time"))
    }

    @Test
    fun `true task shapes still block every rule`() {
        // 全局表里的词必须仍然一票否决 —— 这是路由的安全底线，不能被「逐规则」改松
        for (shape in listOf("写", "生成", "页面", "代码", "翻译", "截图", "视频", "文件")) {
            assertNull(
                "「$shape」应全局否决",
                ToolIntentRouter.route("${shape}一个查天气的东西"),
            )
        }
    }

    // ── 并集收敛（2026-10-07）：通用动作词必须给领域词让位 ────────────
    //
    // app/search 的关键词（打开/启动/查一下）是**通用动作词**，任何句子都可能出现；
    // music/timer/weather 的关键词（音乐/闹钟/天气）是**领域词**，指向性明确。
    // 两者同时命中若直接取并集 ⇒ 子集白白变大（打开音乐 2→4、帮我打开闹钟 3→5）。
    //
    // ⚠️ 下面的**工具数量**是与实现绑定的断言，不只是「路由成功」：
    // 一旦有人把优先级机制删掉、或给别的规则也标 GENERIC_PRIORITY，这里会红。

    @Test
    fun `generic verbs yield to domain rules`() {
        assertEquals(
            setOf("control_music", "get_now_playing"),
            ToolIntentRouter.route("打开音乐"),
        )
        assertEquals(
            setOf("set_phone_alarm", "manage_timer", "schedule_agent_task"),
            ToolIntentRouter.route("帮我打开闹钟"),
        )
        assertEquals(
            setOf("get_weather", "get_current_time"),
            ToolIntentRouter.route("查一下明天天气"),
        )
        assertEquals(
            setOf("manage_calendar", "get_current_time"),
            ToolIntentRouter.route("查一下明天的日程"),
        )
    }

    @Test
    fun `generic verbs still work when no domain rule matched`() {
        // 让位不是失效 —— 纯动作句仍要路由到通用规则
        assertTrue(ToolIntentRouter.route("查一下快递")!!.contains("search_web"))
        assertTrue(ToolIntentRouter.route("打开空调")!!.contains("open_phone_app"))
        // 无领域规则的纯动作句，工具数应等于通用规则自身的宽度（不被别的规则撑大）
        assertEquals(2, ToolIntentRouter.route("打开空调")!!.size)
        assertEquals(2, ToolIntentRouter.route("查一下快递")!!.size)
    }

    // ── 不该路由的形状 ────────────────────────────────────────────────────

    @Test
    fun `non-intent long and multiline inputs return null`() {
        assertNull(ToolIntentRouter.route("帮我推荐一部好看的电影")) // 无规则命中 → 全量
        assertNull(ToolIntentRouter.route("第一行\n第二行天气")) // 多行
        assertNull(ToolIntentRouter.route("这是一段很长的粘贴内容，详细描述了一个复杂的多步骤任务，需要很多工具配合才能完成，请认真处理每一个环节不要遗漏。")) // >64 字
        assertNull(ToolIntentRouter.route(""))
    }

    // ── 逃生舱说明 ────────────────────────────────────────────────────────

    @Test
    fun `escape note carries the shared NEED_TOOLS marker`() {
        val note = ToolIntentRouter.escapeNote(setOf("get_weather", "get_location"))
        assertTrue(note.contains(ChatFastPath.NEED_TOOLS_MARKER))
        assertTrue(note.contains("get_weather"))
        // 与快路升级判定共用同一标记：needsUpgrade 必须认得说明里的标记
        assertTrue(ChatFastPath.needsUpgrade(ChatFastPath.NEED_TOOLS_MARKER))
        assertFalse(ChatFastPath.needsUpgrade("今天杭州 28 度，适合出行"))
    }

    // ── 菜单探测轮的点名解析（第三层：83 个工具全量进按需装配） ────────────

    private val known = setOf(
        "get_weather", "get_location", "get_current_time", "control_music",
        "set_phone_alarm", "search_web", "save_code_file",
    )

    @Test
    fun `menu pick parses names regardless of separator`() {
        assertEquals(
            setOf("get_weather", "get_location"),
            ToolIntentRouter.parseToolPick("[TOOLS] get_weather,get_location", known),
        )
        assertEquals(
            setOf("get_current_time", "set_phone_alarm"),
            ToolIntentRouter.parseToolPick("[TOOLS]\nget_current_time、set_phone_alarm", known),
        )
        // 模型顺手带了一句解释也解析得出来
        assertEquals(
            setOf("control_music"),
            ToolIntentRouter.parseToolPick("[TOOLS] control_music（用户想听歌）", known),
        )
    }

    @Test
    fun `menu pick returns null for plain answers and empty for unknown names`() {
        // 直接作答（无标记）→ null，调用方按最终回复收尾
        assertNull(ToolIntentRouter.parseToolPick("电影推荐：《流浪地球》", known))
        // [NEED_TOOLS] 不是点名标记 → null（由 needsUpgrade 判定为要全量）
        assertNull(ToolIntentRouter.parseToolPick(ChatFastPath.NEED_TOOLS_MARKER, known))
        // 有标记但全是不认识的名字 → 空集，调用方全量兜底
        assertEquals(emptySet<String>(), ToolIntentRouter.parseToolPick("[TOOLS] make_coffee", known))
        assertEquals(emptySet<String>(), ToolIntentRouter.parseToolPick("[TOOLS]", known))
    }

    @Test
    fun `marker-like replies are detected for subset rounds`() {
        assertTrue(ToolIntentRouter.isMarkerLike("[NEED_TOOLS]"))
        assertTrue(ToolIntentRouter.isMarkerLike(" [TOOLS] get_weather"))
        assertFalse(ToolIntentRouter.isMarkerLike("今天杭州 28 度，适合出行"))
        assertFalse(ToolIntentRouter.isMarkerLike(null))
    }

    @Test
    fun `bootstrap toolset is search + time anchored`() {
        // 锁定引导集契约：探测轮靠它们查证/解析相对日期；改名必须同步 providers
        assertEquals(
            setOf("search_web", "fetch_webpage", "get_current_time"),
            ToolIntentRouter.BOOTSTRAP_TOOLS,
        )
    }
}
