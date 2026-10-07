package com.rokidlab.phone.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [PatchApplier] 回归测试 —— 局部编辑引擎。
 *
 * ## 锁的是哪个能力（2026-10-06 对标 Codex 的头号缺口）
 * 原来只有 `edit_text_file(mode=overwrite)`：改 1 行要先读全文再整份写回，
 * 成本 O(文件) 且有截断风险。补 [PatchApplier] 后改片段是 O(变更量)。
 *
 * ## 但真正要锁的不是"能改"，而是"**不该改的时候绝不改**"
 * 整份覆盖的失败模式是「整份文件变乱」，用户一眼看得出；
 * 局部编辑的失败模式是「**看起来写成功了，但改错了地方**」——
 * 最坏情况是抹掉了不该动的行，而模型拿到的是**成功回执**。
 * 所以下面一半以上的用例都在锁"拒绝"路径。
 *
 * 📌 **本文件的所有补丁字面量都不用 `trimIndent()`**（这是个真实的踩坑点）：
 * `trimIndent` 会把每行的前导空格一起吃掉，而补丁里「行首空格 = 上下文行」
 * 恰恰是**语义本身**。用它写补丁会静默变成"每行都是删除/新增"，测试变成在测别的东西。
 * 统一用 [p] 拼（显式 `joinToString("\n")`），前导空格写不写、写在第几位都一眼可见。
 */
class PatchApplierTest {

    /** 拼补丁：显式列出每行，保留行首空格（`'  val a = 1'` 的那个空格就是"上下文"标记） */
    private fun p(vararg lines: String) = lines.joinToString("\n")

    private val sample = p(
        "fun main() {",
        "    val a = 1",
        "    val b = 2",
        "    println(a + b)",
        "}",
    ) + "\n"

    private fun apply(original: String, patch: String, hint: Int? = null) =
        PatchApplier.apply(original, patch, hint)

    // ── 基本替换 ────────────────────────────────────────────────

    @Test
    fun `替换一行 - 只有目标行变化 - 其余逐字不动`() {
        val patch = p(
            "@@",
            "     val a = 1",
            "-    val b = 2",
            "+    val b = 99",
            "     println(a + b)",
        )

        val out = apply(sample, patch).content

        assertEquals(
            p(
                "fun main() {",
                "    val a = 1",
                "    val b = 99",
                "    println(a + b)",
                "}",
            ) + "\n",
            out,
        )
    }

    @Test
    fun `新增多行 - 顺序与内容保持`() {
        val patch = p(
            "@@",
            "     val a = 1",
            "+    val a2 = 10",
            "+    val a3 = 11",
            "     val b = 2",
        )

        val out = apply(sample, patch).content

        assertTrue(out.contains("val a = 1\n    val a2 = 10\n    val a3 = 11\n    val b = 2"))
    }

    @Test
    fun `删除一行 - 该行消失且不留空行`() {
        val patch = p(
            "@@",
            "     val a = 1",
            "-    val b = 2",
            "     println(a + b)",
        )

        val out = apply(sample, patch).content

        assertTrue(out.contains("val a = 1\n    println"))
        assertTrue("不该残留被删行的内容", !out.contains("val b = 2"))
    }

    @Test
    fun `增删与上下文交错 - 顺序必须被保持`() {
        // ⚠️ 这是本引擎最容易踩的实现坑：若把 ops 拆成「context 段 + 删除段」再拼，
        // old 序列会变成 [3, 2]（上下文明明在删除行之后），定位必然失败。
        val original = p("one", "two", "three", "four") + "\n"
        val patch = p(
            "@@",
            " one",
            "-two",
            "+TWO",
            " three",
            "-four",
            "+FOUR",
        )

        val out = apply(original, patch).content

        assertEquals(p("one", "TWO", "three", "FOUR") + "\n", out)
    }

    @Test
    fun `多片段 - 从后往前应用不影响前面的定位`() {
        val patch = p(
            "@@",
            "-    val a = 1",
            "+    val a = 111",
            "@@",
            "-    println(a + b)",
            "+    println(\"done\")",
        )

        val out = apply(sample, patch).content

        assertTrue(out.contains("val a = 111"))
        assertTrue(out.contains("println(\"done\")"))
        // 两个片段各改一行 ⇒ 行数不应变
        assertEquals(sample.lines().size, out.lines().size)
    }

    // ── 定位失败必须拒绝（最关键的一组）─────────────────────────

    @Test
    fun `定位不到 - 抛异常而不是乱改`() {
        val patch = p(
            "@@",
            "-根本不存在的行",
            "+替换它",
        )

        val e = runCatching { apply(sample, patch) }.exceptionOrNull()
        assertTrue("定位失败必须抛异常（由调用方保证不落盘）", e is PatchApplier.PatchApplyException)
        assertTrue("报错文案必须告诉模型下一步：${e?.message}",
            e?.message?.contains("read_text_file") == true)
        assertTrue("必须明说本次没有改动：${e?.message}",
            e?.message?.contains("没有改动任何内容") == true)
    }

    @Test
    fun `定位到多处且无行号提示 - 拒绝并报出所有候选行`() {
        val dup = p("target", "keep", "target") + "\n"
        val patch = p(
            "@@",
            "-target",
            "+changed",
        )

        val e = runCatching { apply(dup, patch) }.exceptionOrNull()
        assertTrue("多处命中不得猜测", e is PatchApplier.PatchApplyException)
        assertTrue("应报出候选行号：${e?.message}", e?.message?.contains("第 1、3 行") == true)
    }

    @Test
    fun `定位到多处但给了行号 - 只改靠近该行号的那一处`() {
        val dup = p("target", "keep", "target") + "\n"
        val patch = p(
            "@@",
            "-target",
            "+changed",
        )

        val out = apply(dup, patch, hint = 3).content

        assertEquals("第 1 行不该被动", p("target", "keep", "changed") + "\n", out)
    }

    @Test
    fun `给了行号但该处内容对不上 - 拒绝而不是按行号硬改`() {
        // ⚠️ 这是最容易造成"改坏别人代码"的路径：行号能对上位置，内容却完全不同，
        // 仍被强行替换 = 用行号覆盖了用户没让改的东西。
        val patch = p(
            "@@",
            "-压根不存在的行",
            "+新内容",
        )

        val e = runCatching { apply(sample, patch, hint = 2) }.exceptionOrNull()
        assertTrue("内容不匹配时必须拒绝：${e?.message}", e is PatchApplier.PatchApplyException)
    }

    @Test
    fun `纯新增且行号越界 - 拒绝而不是插到无关位置`() {
        // 没有删除行 ⇒ 行号退路对纯新增不适用（插到 hint 处可能完全无关）
        val patch = p("@@", "+插入的一行")

        val e = runCatching { apply(sample, patch, hint = 999) }.exceptionOrNull()
        assertTrue(e is PatchApplier.PatchApplyException)
    }

    @Test
    fun `空补丁 - 拒绝并解释格式`() {
        val e = runCatching { apply(sample, "   ") }.exceptionOrNull()
        assertTrue(e is PatchApplier.PatchApplyException)
        assertTrue("应解释片段写法：${e?.message}", e?.message?.contains("@@") == true)
    }

    // ── 格式宽容度：模型不守规矩时不能全线崩 ────────────────────

    @Test
    fun `空行写成裸空行 - 当作上下文空行而不是片段结束`() {
        val original = p("a", "", "b") + "\n"
        val patch = p("@@", "-a", "+A", "", " b")

        val out = PatchApplier.apply(original, patch, null).content

        assertEquals(p("A", "", "b") + "\n", out)
    }

    @Test
    fun `新增一个空行 - 写加号即可`() {
        val original = p("a", "b") + "\n"
        val patch = p("@@", " a", "+", " b")

        assertEquals(p("a", "", "b") + "\n", PatchApplier.apply(original, patch, null).content)
    }

    @Test
    fun `行尾空白差异 - 放宽匹配仍能定位`() {
        val original = p("value = 1   ", "other") + "\n"
        val patch = p("@@", "-value = 1", "+value = 2")

        // ⚠️ 尾随空格属于**被替换掉的那一行**，不该被带到新行上 ——
        // 保留它就等于补丁没真的替换掉这一行。
        assertEquals(p("value = 2", "other") + "\n", apply(original, patch).content)
    }

    @Test
    fun `忘了写行首前缀 - 当作上下文行而不是失败`() {
        val original = p("alpha", "beta") + "\n"
        val patch = p("@@", "alpha", "-beta", "+BETA")

        assertEquals(p("alpha", "BETA") + "\n", apply(original, patch).content)
    }

    @Test
    fun `带格式头 - 忽略不报错`() {
        val patch = p(
            "*** Begin Patch",
            "@@",
            "-    val b = 2",
            "+    val b = 7",
            "*** End Patch",
        )

        assertTrue(apply(sample, patch).content.contains("val b = 7"))
    }

    @Test
    fun `CRLF 行尾 - 不因末尾回车找不到行`() {
        val original = "one\r\ntwo\r\nthree\r\n"
        val patch = "@@\r\n-two\r\n+TWO\r\n three"

        assertTrue(PatchApplier.apply(original, patch, null).content.contains("TWO"))
    }

    // ── 末尾换行必须保住（否则每次编辑都改文件结尾）───────────────

    @Test
    fun `改最后一行 - 末尾换行不被吃掉`() {
        val original = "a\nb\n"
        val patch = p("@@", "-b")

        val out = PatchApplier.apply(original, patch, null).content

        assertEquals("末尾换行必须原样保留：${out.replace("\n", "\\n")}", "a\n", out)
    }

    @Test
    fun `split与join往返 - 恒等`() {
        listOf("", "a", "a\n", "a\nb", "a\n\n", "a\nb\nc\n").forEach { s ->
            assertEquals("往返不一致：${s.replace("\n", "\\n")}",
                s, PatchApplier.joinLines(PatchApplier.splitLines(s)))
        }
    }

    @Test
    fun `无末尾换行的文件 - 改完也不凭空加换行`() {
        val out = PatchApplier.apply("a\nb", p("@@", "-b", "+B"), null).content
        assertEquals("a\nB", out)
    }

    // ── 新建（create 路径：原文为空）────────────────────────────

    @Test
    fun `原文为空时应用纯新增补丁 - 得到新增内容`() {
        val patch = p("@@", "+line1", "+line2")

        val out = apply("", patch).content

        assertTrue(out.contains("line1"))
        assertTrue(out.contains("line2"))
    }

    // ── 防御 ────────────────────────────────────────────────────

    @Test
    fun `超长补丁 - 拒绝而不是吃内存`() {
        val huge = "@@\n" + "+x\n".repeat(5_000)
        val e = runCatching { apply(sample, huge) }.exceptionOrNull()
        assertTrue("超长补丁必须拒绝：${e?.message}", e is PatchApplier.PatchApplyException)
    }

    @Test
    fun `回执里的行号 - 指向应用后的真实位置`() {
        val patch = p(
            "@@",
            "     val a = 1",
            "+    val inserted = 0",
            "     val b = 2",
        )

        val r = apply(sample, patch)

        val h = r.hunks.single()
        assertEquals(1, h.added)
        assertEquals(0, h.removed)
        // sample 第 2 行是 "    val a = 1"；插入发生在它之后 ⇒ 新行落在第 3 行
        assertEquals(3, h.line)
        assertTrue("回执行号应能定位到实际内容：${r.content.lines()[2]}",
            r.content.lines()[2].contains("inserted"))
    }
}
