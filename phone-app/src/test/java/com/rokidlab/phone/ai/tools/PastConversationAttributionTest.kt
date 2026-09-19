package com.rokidlab.phone.ai.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 跨会话检索的**归属渲染**测试 —— 2026-09-19 真机 bug 的哨兵。
 *
 * 那个 bug 的表现是「新建一个对话，它还记得上一个对话说过什么」，看上去像**会话隔离坏了**。
 * 实际上事件流与会话记忆都是对的（`agent_sessions/<id>.jsonl` 各是各的、`bindSession` 也切对了）——
 * 坏的是**检索结果的归属**：`searchPastConversations` 把全部会话拼成一条扁平列表，
 * 又渲染成"一条对话的最近 N 轮"，模型于是把别的会话当成了"刚才"。
 *
 * ⇒ 这条不变式（"每条结果都必须带会话身份、且必须声明其它会话不等于当前对话"）
 *   **没有任何编译期或运行期检查**：渲染层再被"简化"一次就会重犯。
 *   所以它必须由测试钉住，而不是只写在注释里。
 */
class PastConversationAttributionTest {

    private fun located(
        sid: String,
        title: String,
        question: String,
        updatedAt: Long = 0L,
        score: Double = 0.0,
    ) = StatusToolProvider.LocatedTurn(
        sessionId = sid,
        sessionTitle = title,
        sessionUpdatedAt = updatedAt,
        turn = StatusToolProvider.Turn(time = "10:00", question = question, answer = "答：$question"),
    ) to score

    @Test
    fun `当前会话与其它会话分别打标且不合并`() {
        val out = StatusToolProvider.renderGrouped(
            listOf(
                located("cur", "今天问的", "当前会话里那句"),
                located("old", "上周那个", "别的会话里那句"),
            ),
            currentSessionId = "cur",
        )
        assertTrue("当前会话必须有标记\n$out", out.contains("【当前会话】今天问的"))
        assertTrue("其它会话必须有标记\n$out", out.contains("【其他会话】上周那个"))
        assertTrue(
            "当前会话应排在前面（用户问「我们最近聊了什么」指的就是它）",
            out.indexOf("【当前会话】") < out.indexOf("【其他会话】"),
        )
        assertTrue("两条轮次都要在", out.contains("当前会话里那句") && out.contains("别的会话里那句"))
    }

    @Test
    fun `同一个会话的多轮归在同一个标题下`() {
        val out = StatusToolProvider.renderGrouped(
            listOf(
                located("old", "上周那个", "第一轮"),
                located("old", "上周那个", "第二轮"),
                located("cur", "今天问的", "当前一句"),
            ),
            currentSessionId = "cur",
        )
        assertEquals("上周那个 只应出现一次标题", 1, Regex("【其他会话】上周那个").findAll(out).count())
        assertEquals("今天问的 只应出现一次标题", 1, Regex("【当前会话】今天问的").findAll(out).count())
    }

    @Test
    fun `每条检索结果都必须带归属声明`() {
        val notice = StatusToolProvider.ATTRIBUTION_NOTICE
        assertTrue("声明里必须点名「其他会话」", notice.contains("其他会话"))
        assertTrue("必须明确禁止把它当成\"刚才\"", notice.contains("刚才"))

        // 两条分支（关键词检索 / 最近若干轮）都用同一个出口，出口里必须带声明
        listOf("找到 2 条相关历史对话（按相关度排序，来自 2 个会话）：", "最近 2 轮对话（共 2 个会话 5 轮可检索）：")
            .forEach { header ->
                val full = StatusToolProvider.renderSearchResult(
                    header = header,
                    picked = listOf(located("cur", "今天问的", "甲"), located("old", "上周那个", "乙")),
                    currentSessionId = "cur",
                )
                assertTrue("声明必须出现在结果里：$header", full.contains(notice))
                assertTrue("标题也要在", full.contains(header))
            }
    }

    @Test
    fun `其它会话带上相对时间而当前会话不带`() {
        val now = System.currentTimeMillis()
        val out = StatusToolProvider.renderGrouped(
            listOf(
                located("cur", "今天问的", "甲", updatedAt = now),
                located("old", "上周那个", "乙", updatedAt = now - 3 * 60_000L),
            ),
            currentSessionId = "cur",
        )
        assertTrue("其它会话要标出多久以前\n$out", out.contains("【其他会话】上周那个（3 分钟前）"))
        assertTrue("当前会话不标时间", out.contains("【当前会话】今天问的\n"))
    }
}
