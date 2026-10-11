package com.rokidlab.phone.proactive

import android.content.Context
import com.rokidlab.phone.ai.LongTermMemoryManager
import java.time.DayOfWeek
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * 主动开口前的「记忆联想」装配（Muse 式："你周一说要去成都，今天那边下雨，记得带伞"）。
 *
 * 为什么需要：主动轮走的也是通用记忆注入（[LongTermMemoryManager.memoriesContext]，
 * query = 本轮 prompt），但宿主 prompt 是固定话术（"用户已经 N 个多小时没和我交流了"），
 * 与时间相关记忆（"下周三去成都""每周六打球"）词法不相交，检索永远退化为
 * 「最近 K 条」兜底 —— 主动侧等于"有记忆但想不起来"。
 *
 * 这里在宿主侧补一块**显式召回**：
 *  1. 检索 query 用「场景词 + 今天日期/星期 + 明天星期 + 计划类通用词」构造 ——
 *     计划类记忆里的星期/日期词（周三、10月、明天）与 query 词法必中，
 *     混合检索的语义向量路再兜一层「换个说法」的召回；
 *  2. 时间锚让模型不调工具就知道现在几号星期几点（[SKIP] 判定与早晚措辞的基准）；
 *  3. 指令把通用注入的「可据此个性化回答」升级为「此刻相关才自然提起」——
 *     相关才说、无关不硬扯，配合各宿主已有的 [SKIP] 静默收回语义。
 *
 * 分层：[timeAnchor] / [recallQuery] / [recallBlock(String)] 是纯逻辑（JVM 单测）；
 * [recallBlock(Context, String, long)] 的 Android 壳负责开关判断与检索委托 ——
 * 刻意复用通用检索（去重/冲突消解/访问保鲜/UntrustedContent 隔离包装全套现成），
 * 不自建注入通道，避免两套记忆注入语义漂移。
 */
object ProactiveRecall {

    /** 星期中文名（计划类记忆通常写作「周三/周日」，与 [DayOfWeek] 序对齐） */
    private val WEEKDAY_CN = arrayOf("周一", "周二", "周三", "周四", "周五", "周六", "周日")

    fun weekdayCn(day: DayOfWeek): String = WEEKDAY_CN[day.value - 1]

    /** 时间锚（"10月11日 周六 09:30"）：宿主 prompt 开场的事实行，也是检索的时间信号 */
    fun timeAnchor(now: ZonedDateTime): String =
        "%d月%d日 %s %02d:%02d".format(
            now.monthValue,
            now.dayOfMonth,
            weekdayCn(now.dayOfWeek),
            now.hour,
            now.minute,
        )

    fun timeAnchor(nowMs: Long, zone: ZoneId = ZoneId.systemDefault()): String =
        timeAnchor(Instant.ofEpochMilli(nowMs).atZone(zone))

    /**
     * 记忆检索 query（纯函数）：场景词 + 时间锚 + 明天星期 + 计划类通用词。
     * 「明天周X」是刻意的一手：约定常以「明天/周X去…」的形态躺在库里，
     * 而主动轮最容易联想的正是这类时间临近的计划。
     * 分词按 2-gram 与 ASCII 词元切，词序无关，空格拼接即可。
     */
    fun recallQuery(sceneHint: String, now: ZonedDateTime): String {
        val tomorrowWeekday = weekdayCn(now.toLocalDate().plusDays(1).dayOfWeek)
        return listOf(
            sceneHint,
            timeAnchor(now),
            "明天$tomorrowWeekday",
            "约定 计划 行程 想去 提醒",
        ).joinToString(" ")
    }

    /**
     * 装配「记忆联想」块（纯函数）：[recalled] 是通用检索返回的 `<memories>` 包裹块
     * （数据已在来源层隔离），指令行在包裹层**之外** —— 框架指令不能进不可信内容。
     * 无记忆返回 null，宿主按「无联想」拼接。
     */
    fun recallBlock(recalled: String?): String? {
        if (recalled.isNullOrBlank()) return null
        return "（记忆联想）看看下面的长期记忆：若恰有与此刻时间或近期安排相关的" +
            "（约定、行程、说过想去的地方、天气敏感的偏好），可以像随口想起一样自然提一句；" +
            "没有相关的就当没看见，不要硬扯、不要逐条复述、不要提到「记忆」这类字眼。\n" +
            recalled
    }

    /**
     * Android 壳：记忆总开关关 / 无事实记忆时返回 null，其余委托通用检索。
     * 在宿主 prompt 装配处调用（保活服务线程，非主线程）；语义路可能有一次 ≤5s 的
     * 查询嵌入网络请求 —— 与对话轮注入同口径，检索失败由通用层兜底，这里不另兜。
     */
    fun recallBlock(context: Context, sceneHint: String, nowMs: Long = System.currentTimeMillis()): String? {
        if (!LongTermMemoryManager.isEnabled(context)) return null
        val now = Instant.ofEpochMilli(nowMs).atZone(ZoneId.systemDefault())
        val recalled = LongTermMemoryManager.memoriesContext(context, recallQuery(sceneHint, now))
        return recallBlock(recalled)
    }
}
