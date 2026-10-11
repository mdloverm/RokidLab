package com.rokidlab.phone.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 代码生成回合的回复收敛（[finalizeCodeGenReply] / [isCodeGenStatusLine]）回归测试。
 *
 * 背景（2026-10-10 真机反馈）："最后生成的气泡里有部分内容不该在回复的气泡里" ——
 * 正文气泡已有模型流式打出的自然表述，收尾时框架又追加了一句「已生成 3 个文件，
 * 保存在手机下载目录的"html-test"项目。」（机器口吻的状态复述，过程卡片 + 文件卡已承载）。
 * [isCodeGenStatusLine] 就是给 UI 侧（`ChatStateHolder.preserveOnReplace`）判断
 * "这句是纯状态句、不该再追加"的依据，本测试锁住它的边界：**只认整句**。
 */
class CodeGenReplyConvergenceTest {

    @Test
    fun `框架的权威结论句被认作状态句`() {
        assertTrue(isCodeGenStatusLine("已生成 3 个文件，保存在手机下载目录的“html-test”项目。"))
    }

    @Test
    fun `模型按提示词示例写出的状态句（无手机前缀无句号）也认`() {
        assertTrue(isCodeGenStatusLine("已生成 4 个文件，保存在下载目录的 xxx 项目"))
    }

    @Test
    fun `正文里顺带提到生成文件不认作纯状态句`() {
        assertFalse(
            isCodeGenStatusLine("已生成 3 个文件，保存在手机下载目录的“html-test”项目。你可以在预览里点开看看"),
        )
    }

    @Test
    fun `普通正文不认作状态句`() {
        assertFalse(isCodeGenStatusLine("页面已经跑起来了，你在预览入口点开就能看到。"))
    }

    @Test
    fun `超长且含代码的回复用权威结论兜底`() {
        val raw = "好的，我帮你写一版：\n```kotlin\nfun main() { println(\"hi\") }\n```\n以上。"
        assertEquals(
            "已生成 1 个文件，保存在手机下载目录的“demo”项目。",
            finalizeCodeGenReply(raw, "已生成 1 个文件，保存在手机下载目录的“demo”项目。"),
        )
    }

    @Test
    fun `短小的自然结论句原样保留`() {
        assertEquals("好的，页面已经生成好了。", finalizeCodeGenReply("好的，页面已经生成好了。", "已生成 1 个文件。"))
    }
}
