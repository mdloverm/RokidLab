package com.rokidlab.phone.ai

import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 宿主机须知文档（`aiui-dev/lab-runtime.md`）的**体积闸门**。
 *
 * 为什么值得一条测试：文档体积与 `load_skill` 的行为是**隐式耦合**的 ——
 * 一旦它超过内联预算，`load_skill` 会从"整份内联"**静默降级**成"章节目录 + 按需取章"，
 * 而没有任何报错、没有任何日志。2026-09-19 触发这次调整的就是一次**擦边**：
 * 该文档 5994 字符、当时上限 6000，任何一次内容补充都会把它顶过去 ——
 * 而它偏偏是所有页面开发的**公共前置**（拿不到它，模型就不知道"本宿主没有事件循环、
 * 工具必须回调式"这种最高频翻车点）。
 *
 * ⇒ 与其靠人记得"别再写了"，不如让超限直接变红：**这条测试就是那个哨兵**。
 */
class AiuiRuntimeDocSizeTest {

    /** 文档同时以"正文"和"完整 SKILL.md（含 frontmatter）"两种口径被消费，取更严的那个 */
    private fun runtimeDocFile(): File {
        val candidates = listOf(
            File("src/main/assets/skills/aiui-dev/lab-runtime.md"),
            File("phone-app/src/main/assets/skills/aiui-dev/lab-runtime.md"),
        )
        return candidates.firstOrNull { it.exists() }
            ?: throw AssertionError(
                "找不到 lab-runtime.md（试过 ${candidates.joinToString { it.path }}）。" +
                    "单测的工作目录变了，请更新这里 —— 不要因此删掉这条断言。"
            )
    }

    @Test
    fun `宿主须知必须整份内联，不得超过它的专属预算`() {
        val text = runtimeDocFile().readText()
        assertNotNull(text)
        assertTrue("文档不该是空的", text.isNotBlank())
        val body = SkillMarkdown.parse(text)?.body ?: text
        assertTrue(
            "lab-runtime.md 正文 ${body.length} 字符 > 内联预算 ${SkillMarkdown.MAX_RUNTIME_INLINE_CHARS}：" +
                "load_skill 会静默降级成章节目录，模型很有可能拿不到运行须知。" +
                "要么精简文档，要么在 SkillMarkdown 里上调 MAX_RUNTIME_INLINE_CHARS。",
            body.length <= SkillMarkdown.MAX_RUNTIME_INLINE_CHARS,
        )
    }

    @Test
    fun `两个阈值的关系符合设计（专属预算更宽，通用阈值有下限）`() {
        assertTrue(
            "宿主须知的预算必须**宽于**通用阈值，否则加这条独立预算没有意义",
            SkillMarkdown.MAX_RUNTIME_INLINE_CHARS > SkillMarkdown.MAX_INLINE_CHARS,
        )
        assertTrue(
            "通用阈值不得低于 12000 —— 6000 时代正是被宿主须知（5994）顶到过上限",
            SkillMarkdown.MAX_INLINE_CHARS >= 12000,
        )
    }
}
