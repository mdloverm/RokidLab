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

    // ── 结构排除：看着像意图、实际是任务 → 一律不路由（落回全量） ──────────

    @Test
    fun `task-shaped sentences are never routed`() {
        assertNull(ToolIntentRouter.route("写个天气app"))
        assertNull(ToolIntentRouter.route("做一个天气页面"))
        assertNull(ToolIntentRouter.route("把今天的天气生成一张图片"))
        assertNull(ToolIntentRouter.route("帮我搜一下附近的超市")) // 搜索是独立任务，不抢 location 路由
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
