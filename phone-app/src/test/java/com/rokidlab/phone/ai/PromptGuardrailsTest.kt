package com.rokidlab.phone.ai

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [PromptGuardrails] 单测 —— 提示词护栏文件不可被模型删除。
 *
 * ## 锁住的是什么
 *
 * `下载/RokidLab规则.md` 会被逐轮注入 system prompt。允许模型删它 = 允许它**移除自己的约束**；
 * 往里面写就是**给自己扩权**（下一轮那段文本就成了"用户规则"）。
 * 这是本项目里最直接的一条"agent 改写自己运行规则"的路径，对应 Muse 的同类保护。
 *
 * ## 这组测试的判据
 *
 * 1. **真的拦得住**：所有会让它命中的写法（路径前缀、子目录形式、大小写、尾随空格）都要判为护栏；
 * 2. **不误伤**：用户自己的文件、名字相似的文件必须放行 ——
 *    一个连普通文件都删不掉的防护会被用户当成 bug。
 */
class PromptGuardrailsTest {

    @Test
    fun `裸文件名命中`() {
        assertTrue(PromptGuardrails.isGuardedDownloadPath("RokidLab规则.md"))
    }

    @Test
    fun `带前缀斜杠或反斜杠也命中`() {
        // delete_file 的调用方可能传 "/RokidLab规则.md" 或 Windows 风格
        assertTrue(PromptGuardrails.isGuardedDownloadPath("/RokidLab规则.md"))
        assertTrue("反斜杠要归一化（同一个文件不该因分隔符逃过）", PromptGuardrails.isGuardedDownloadPath("\\RokidLab规则.md"))
    }

    @Test
    fun `尾随空格命中`() {
        // ⚠️ 用户从资源管理器新建文件常产生尾随空格；`==` 比对会在这里漏掉
        assertTrue(PromptGuardrails.isGuardedDownloadPath("RokidLab规则.md "))
        assertTrue(PromptGuardrails.isGuardedDownloadPath("RokidLab规则.md  "))
    }

    @Test
    fun `大小写不敏感`() {
        // MediaStore 的 DisplayName 大小写与输入法规范化不保证一致
        assertTrue(PromptGuardrails.isGuardedDownloadPath("rokidlab规则.md"))
        assertTrue(PromptGuardrails.isGuardedDownloadPath("ROKIDLAB规则.MD"))
    }

    @Test
    fun `用户自己的文件不拦`() {
        assertFalse("普通文件", PromptGuardrails.isGuardedDownloadPath("报告.docx"))
        assertFalse("项目目录里的文件", PromptGuardrails.isGuardedDownloadPath("myproj/index.ink"))
        assertFalse("空路径", PromptGuardrails.isGuardedDownloadPath(""))
        assertFalse("白空格", PromptGuardrails.isGuardedDownloadPath("   "))
    }

    @Test
    fun `名字相似但不相同的文件不拦`() {
        // ★ 防误伤：护栏清单是**精确集合**比对，不是前缀/包含匹配。
        //   若实现写成 contains("规则")，下面这些会被误伤。
        assertFalse(PromptGuardrails.isGuardedDownloadPath("RokidLab规则备份.md"))
        assertFalse(PromptGuardrails.isGuardedDownloadPath("规则.md"))
        assertFalse(PromptGuardrails.isGuardedDownloadPath("RokidLab规则.md.bak"))
        assertFalse(PromptGuardrails.isGuardedDownloadPath("我的RokidLab规则.md"))
    }

    @Test
    fun `子目录里同名文件不算护栏（护栏只在下载根）`() {
        // 用户在自己项目里放一个同名文件是他的自由；规则文件只从下载根读取
        assertFalse(PromptGuardrails.isGuardedDownloadPath("myproj/RokidLab规则.md"))
    }

    @Test
    fun `拒绝文案给出原因与出路`() {
        val msg = PromptGuardrails.refuseDeleteMessage("RokidLab规则.md")
        assertTrue("要点明这是什么文件", msg.contains("用户手写的规则文件"))
        assertTrue("要说明为什么不能动（改它=改自己的准则）", msg.contains("行为准则"))
        assertTrue("要给用户侧的出路，否则模型只会原样重试", msg.contains("自行编辑"))
        assertFalse("不能只说'被拒绝'", msg.contains("失败："))
    }

    @Test
    fun `规则文件名常量与读取端同源`() {
        // 📌 这条钉的是"唯一产地"本身：读取端（AiConversationService.PROJECT_RULES_RELPATH）
        //   与防护端引用同一个常量。若有人把任一处改回字面量，功能的两个半边会各说一套。
        assertTrue(
            "常量必须是非空文件名",
            PromptGuardrails.PROJECT_RULES_FILE.endsWith(".md"),
        )
        assertTrue(
            "常量值本身必须被自己的判定认定为护栏 —— 否则读取端与防护端对不上",
            PromptGuardrails.isGuardedDownloadPath(PromptGuardrails.PROJECT_RULES_FILE),
        )
    }
}
