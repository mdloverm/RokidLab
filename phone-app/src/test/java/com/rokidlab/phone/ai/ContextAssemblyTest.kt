package com.rokidlab.phone.ai

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [ContextAssembly]（轮变上下文装配）单测。
 *
 * ## 为什么这些不变量需要断言
 * 这段逻辑原先内联在 `AiConversationService`（3100 行、**零单测**）里，
 * 2026-10-07 审计出的两条高危有两条都发生在它附近 —— 不是巧合，
 * 是「编排层没有测试」必然产出的盲区。抽成纯函数后才钉得住。
 */
class ContextAssemblyTest {

    // ═══════════ 知识库片段 ═══════════

    @Test
    fun `no docs means nothing to inject`() {
        // 📌 库里一篇文档都没有 ⇒ 返回 null（不注入空段）。
        // 反过来（有文档但没命中）**必须**给目录，见下一例。
        assertTrue(
            "空库不该产出注入文本",
            ContextAssembly.knowledgeContext(emptyList(), docCount = 0, docNames = emptyList()) == null,
        )
    }

    @Test
    fun `miss still tells the model which docs exist`() {
        // ⚠️ 这是"问文档里的内容答不出来"的根因修复：
        // 改造前只有"命中才注入"，用户换个说法（或问「这个文档讲了什么」这种
        // 没有可命中关键词的泛问）时，模型连"存在知识库"都不知道
        // ⇒ 既没有资料、也不会去调 search_knowledge_base。
        val out = ContextAssembly.knowledgeContext(
            hitLines = emptyList(),
            docCount = 3,
            docNames = listOf("合同.pdf", "说明书", "会议纪要"),
        )
        assertTrue("未命中时必须仍产出注入文本（目录）", out!!.contains("3 份文档"))
        assertTrue("必须列出文档名", out.contains("《合同.pdf》"))
        assertTrue("必须指引模型改用工具再检索", out.contains("search_knowledge_base"))
        assertTrue("必须给出可忽略的出口（否则模型会硬答）", out.contains("若无关则忽略"))
    }

    @Test
    fun `hit lines are joined and kept verbatim`() {
        val out = ContextAssembly.knowledgeContext(
            hitLines = listOf(
                ContextAssembly.formatHit("合同.pdf", 0, "甲方应于30日内付款。"),
                ContextAssembly.formatHit("合同.pdf", 3, "违约责任见第8条。"),
            ),
            docCount = 1,
            docNames = listOf("合同.pdf"),
        )!!
        assertTrue("命中正文必须保留", out.contains("甲方应于30日内付款"))
        assertTrue("第二块也要进去", out.contains("违约责任见第8条"))
        // 块号：0 起 ⇒ 渲染成「第1块」「第4块」，来源标注要能对上「过程」里报的名字
        assertTrue(out.contains("《合同.pdf》第1块"))
        assertTrue(out.contains("《合同.pdf》第4块"))
    }

    @Test
    fun `oversized hits are truncated but the model is told`() {
        // ⚠️ **不静默截**是这里的关键：模型知道内容被截了才不会把残缺段落当完整依据。
        // 静默截比不截更糟 —— 它会让模型自信地基于半截内容作答。
        val huge = "甲".repeat(ContextAssembly.KB_HIT_MAX_CHARS * 3)
        val out = ContextAssembly.knowledgeContext(listOf(huge), 1, listOf("大文件"))!!
        assertTrue("必须明确告知已截断", out.contains("检索片段过长已截断"))
        assertTrue(
            "注入文本必须被限制在上限内（+截断提示）",
            out.length <= ContextAssembly.KB_HIT_MAX_CHARS + 32,
        )
    }

    @Test
    fun `doc name list is capped`() {
        val many = (1..40).map { "文档$it" }
        val out = ContextAssembly.knowledgeContext(emptyList(), 40, many)!!
        assertTrue("文档名列表必须封顶，否则目录本身就变成本大头", many.size > ContextAssembly.KB_DOC_NAMES_MAX)
        assertTrue("只列前若干个", out.contains("《文档1》"))
        assertFalse("超出的不应出现", out.contains("《文档40》"))
    }

    // ═══════════ 多来源合并 ═══════════

    @Test
    fun `merge skips blank sources`() {
        assertTrue(
            "全空⇒不注入",
            ContextAssembly.mergeSources(null, "", "   ") == null,
        )
        assertTrue(
            "单个有效值不该被改写",
            ContextAssembly.mergeSources(null, "只有这个") == "只有这个",
        )
        assertTrue(
            "两个有效值按顺序合并",
            ContextAssembly.mergeSources("A", "B") == "A\n\nB",
        )
    }

    // ═══════════ 预算文案 ═══════════

    @Test
    fun `budget note reports all three round budgets and the continuation`() {
        val out = ContextAssembly.budgetNote(
            maxTotalRounds = 22,
            maxReadonlyRounds = 10,
            maxActionRounds = 12,
            maxAutoContinues = 2,
            contextWindow = 0,
        )
        assertTrue("总轮", out.contains("22"))
        assertTrue("只读轮", out.contains("10"))
        assertTrue("动作轮", out.contains("12"))
        assertTrue("续做次数", out.contains("2"))
        assertTrue(
            "必须说明触顶会自动追加额度 —— 否则模型会为省额度而跳过必要步骤",
            out.contains("自动追加一段额度"),
        )
        assertTrue("必须给「继续」这个出口", out.contains("继续"))
    }

    @Test
    fun `token ceiling is omitted when the window is unknown`() {
        // 📌 窗口未知时**不给** token 上限：给一个猜的数会让模型按错误假设分配步骤。
        // 口径与 TokenBudget.forWindow 一致（>0 才给）。
        val unknown = ContextAssembly.budgetNote(22, 10, 12, 2, contextWindow = 0)
        assertFalse("窗口未知时不得出现 token 上限", unknown.contains("token 为上限"))
        val known = ContextAssembly.budgetNote(22, 10, 12, 2, contextWindow = 64000)
        assertTrue("窗口已知时必须给 token 上限", known.contains("token 为上限"))
    }

    @Test
    fun `task note passes through only non-blank`() {
        assertTrue("空任务状态不注入", ContextAssembly.taskNote(null) == null)
        assertTrue("空白任务状态不注入", ContextAssembly.taskNote("  ") == null)
        assertTrue(ContextAssembly.taskNote("上次的计划") == "上次的计划")
    }
}