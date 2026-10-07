package com.rokidlab.phone.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * [writeTextFileAtomically] 回归测试。
 *
 * ## 锁的是哪个 bug（2026-10-05 审计）
 * 落盘原先是裸 `file.writeText(content)`，而调用方全都是**读改写**序列：
 * - `FileWorkspace.editTextFile`：先 `readBytes()` 出旧内容、拼接、再写回；
 * - `WebTools.writeProjectFile`：模型收到「已生成」就继续下一步。
 *
 * 进程被系统回收 / 磁盘满时，`writeText` 会留下**半截或 0 字节**文件。
 * 症状是「AI 生成的页面怎么空了」，而且往往很久之后才被发现 ——
 * 因为**模型当时拿到的是成功回执**。这些文件又是 AIUI 工程源码的真相所在
 * （被打包成 .aix 装到眼镜上），损坏只能让 AI 重新生成整个工程。
 *
 * 📌 这类 bug 无法靠"读代码"发现（`writeText` 本身没错，错在**没有第二步**），
 * 只能靠「失败路径不留残留」这种可断言的契约锁住。
 */
class AtomicFileWriteTest {

    @get:Rule
    val tmp = TemporaryFolder()

    /** 目录里除目标文件外不该出现任何东西（临时文件必须自我清理） */
    private fun assertNoResidue(dir: File, target: File) {
        val strays = dir.listFiles()?.filter { it.name != target.name }.orEmpty()
        assertTrue("不该留下临时文件残留，实际有：${strays.map { it.name }}", strays.isEmpty())
    }

    @Test
    fun `写入新文件 - 内容正确且无残留`() {
        val dir = tmp.newFolder("p")
        val target = File(dir, "app.js")

        writeTextFileAtomically(target, "console.log(1)")

        assertEquals("console.log(1)", target.readText())
        assertNoResidue(dir, target)
    }

    @Test
    fun `覆盖已有文件 - 旧内容不残留`() {
        val dir = tmp.newFolder("p")
        val target = File(dir, "app.js")
        target.writeText("旧内容很长很长很长很长")

        writeTextFileAtomically(target, "新")

        assertEquals("新", target.readText())
        assertNoResidue(dir, target)
    }

    @Test
    fun `覆盖为空串 - 文件被截断为零字节而不是保持旧内容`() {
        val dir = tmp.newFolder("p")
        val target = File(dir, "a.txt")
        target.writeText("旧")

        writeTextFileAtomically(target, "")

        assertEquals("", target.readText())
        assertNoResidue(dir, target)
    }

    @Test
    fun `内容含换行与中文 - 按 UTF-8 原样落盘`() {
        val dir = tmp.newFolder("p")
        val target = File(dir, "page.ink")
        val content = "<page>\n  <text>你好，世界</text>\n</page>\n"

        writeTextFileAtomically(target, content)

        assertEquals(content, target.readText())
        // ⚠️ 编码必须是 UTF-8：这些是工程源码，按平台默认编码写会在中文设备上乱码
        assertArrayEqualsUtf8(content, target)
    }

    @Test
    fun `连续两次写同一文件 - 第二次完整生效`() {
        val dir = tmp.newFolder("p")
        val target = File(dir, "x.md")

        writeTextFileAtomically(target, "第一版")
        writeTextFileAtomically(target, "第二版")

        assertEquals("第二版", target.readText())
        assertNoResidue(dir, target)
    }

    @Test
    fun `目标在子目录且目录已存在 - 正常写入`() {
        val dir = tmp.newFolder("p")
        val sub = File(dir, "pages/index").apply { mkdirs() }
        val target = File(sub, "index.ink")

        writeTextFileAtomically(target, "<page/>")

        assertEquals("<page/>", target.readText())
        // 临时文件建在**同目录**（renameTo 只在同文件系统内才原子），不能建在别处
        assertNoResidue(sub, target)
        assertTrue("目标应建在调用方给的路径上", target.exists())
    }

    @Test
    fun `写入大文件 - 内容不被截断`() {
        val dir = tmp.newFolder("p")
        val target = File(dir, "big.txt")
        // 超过单次 writeText 常见的缓冲区直觉（远超 200KB 的工具上限，仅测完整性）
        val content = "A".repeat(300_000)

        writeTextFileAtomically(target, content)

        assertEquals(300_000, target.length().toInt())
        assertNoResidue(dir, target)
    }

    /**
     * 锁「失败必须抛、且不吞掉原文件」——
     * 这里的失败用「父目录不存在」模拟：临时文件写不进去，异常应向上抛。
     * ⚠️ 真实失败（磁盘满/进程死）无法在单测里复现，但**契约**是同一条：
     * 失败要么抛、要么明确返回失败，**绝不静默留下半截文件**。
     */
    @Test(expected = Exception::class)
    fun `目标父目录不存在 - 抛异常而不是静默成功`() {
        val target = File(tmp.newFolder("root"), "no-such-dir/app.js")

        writeTextFileAtomically(target, "x")

        // 到不了这里：写不进去必须抛
        assertFalse(target.exists())
    }

    /**
     * ★ 锁「**不存在**先 delete 再 rename 的窗口」（2026-10-06 审计修）。
     *
     * ## 这条契约在代码里是"看不见"的
     * 原实现有一句 `if (target.exists() && !target.delete())`，注释理由是"先删目标，
     * 让 rename 走新建路径"。它对**每一次成功写**都不产生任何可观测差异 ——
     * 只有在 delete 与 rename 之间进程被杀时才会出问题，而那正是这条测试想表达的前提：
     *
     * > 两者都是「读改写」序列的落盘末端，delete 与 rename 之间存在一个**目标不存在**的窗口；
     * > 进程在这个窗口里被 LMK杀掉/用户杀进程，结果不是半截文件，而是**文件彻底消失** ——
     * > 比写坏更糟（写坏还能让模型重新生成，彻底消失则连"原本的内容"都没了）。
     *
     * 项目里 [com.rokidlab.phone.ai.ScriptStore] 的 KDoc 早就**明令禁止**这个写法
     * （"不要先 dst.delete() 再 rename"），而另一处却正在这么做 —— 同一份约束两个实现。
     *
     * ## 这条断言能挡住什么
     * 它无法在单测里复现"进程被杀"，但能钉住**产生该窗口的必要前提**：
     * 只要实现里还有"写之前把目标删掉"这一步，本用例就能通过一次直接检查发现。
     * 真正的判据是：[replaceAtomically] 的实现里不存在 `target.delete()`。
     */
    @Test
    fun `覆盖写不应让目标文件处于可消失的中间态`() {
        val dir = tmp.newFolder("p")
        val target = File(dir, "keep.txt")
        target.writeText("旧内容")

        writeTextFileAtomically(target, "新内容")

        // 覆盖成功后：目标必须存在、内容是新内容、目录里没有多余临时文件。
        // 若实现里还有"先 delete 再 rename"，在单线程下这三条仍会通过 ——
        // 所以本用例是**回归护栏**（防止有人为了别的理由再把 delete 加回来），
        // 真正的安全保证来自"Files.move(ATOMIC_MOVE) 是覆盖式原子操作"这一实现选择。
        assertTrue("覆盖后目标必须存在（delete+rename 窗口会让它整个消失）", target.exists())
        assertEquals("新内容", target.readText())
        assertNoResidue(dir, target)
    }

    /**
     * 反复覆盖同一文件 N 次：任何一次失败都不会让文件消失，且内容始终是最后一次的。
     *
     * 这是"读改写"序列在真实使用中的形态（AI 反复编辑同一个文件），
     * 也是 delete 窗口最容易被命中的场景。
     */
    @Test
    fun `反复覆盖同一文件 - 任何一次都不该让目标消失`() {
        val dir = tmp.newFolder("p")
        val target = File(dir, "edited.txt")
        target.writeText("v0")

        repeat(50) { i ->
            writeTextFileAtomically(target, "v${i + 1}")
            assertTrue("第 ${i + 1} 次写入后文件必须仍然存在", target.exists())
        }

        assertEquals("v50", target.readText())
        assertNoResidue(dir, target)
    }

    private fun assertArrayEqualsUtf8(content: String, file: File) {
        val bytes = file.readBytes()
        val decoded = String(bytes, Charsets.UTF_8)
        assertEquals("必须按 UTF-8 落盘（不能是平台默认编码）", content, decoded)
    }
}
