package com.rokidlab.phone.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * 备份层（`writeTextFileWithBackup` / [FileBackupNaming] / `pruneBackups`）测试。
 *
 * ## 锁的是哪个能力
 * 2026-10-06 对标 Codex 的结论：Lab 的 agent **能写文件但改不动**——
 * `edit_text_file` 只有 append/overwrite，改 1 行要先读全文再整份写回，
 * 而 [AtomicFileWrite] KDoc 里那句「本工具没有撤销」意味着
 * **一次错误覆写 = 整个文件永久丢失**。
 * 补上备份层，是引入 `apply_patch` 式增量编辑的**安全前提**：
 * 改错了要能退回去，否则局部编辑的风险比整份覆盖更高。
 *
 * 📌 这类契约靠"读代码"看不出来（`writeText` 本身没错，错在**没有第二步**），
 * 只能靠可断言的不变量锁住。
 */
class FileBackupTest {

    @get:Rule
    val tmp = TemporaryFolder()

    // ── 命名格式：往返一致 ──────────────────────────────────────

    @Test
    fun `备份名 - 点开头且bak结尾 - 不污染目录列表`() {
        val name = FileBackupNaming.backupName("index.ink", 1757048000000L)

        assertTrue("必须点开头，否则 list_files 会把备份混进工程文件清单：$name", name.startsWith("."))
        assertTrue("必须 .bak 结尾以便与 .tmp 中间态区分：$name", name.endsWith(".bak"))
        assertEquals(".index.ink.1757048000000.bak", name)
    }

    @Test
    fun `备份名往返 - 能还原出原文件名与时间戳`() {
        val name = FileBackupNaming.backupName("index.ink", 1757048000000L)

        assertEquals("index.ink", FileBackupNaming.originalNameOf(name))
        assertEquals(1757048000000L, FileBackupNaming.timestampOf(name))
    }

    @Test
    fun `非备份名 - 一律识别为 null - 绝不当成备份误删`() {
        // ⚠️ 这批输入里 ".bak" 只有 4 字符：任何"先 substring 再判空"的实现都会在这里
        // 抛 StringIndexOutOfBoundsException。而 originalNameOf 被 pruneBackups 用来
        // 过滤目录里的**每一个**文件名 —— 抛异常等于整个备份清理流程崩掉。
        listOf("index.ink", ".index.ink", "index.ink.bak", ".bak", "..bak", ".a.b.c", ".a.x.bak", ".")
            .forEach { bad ->
                assertNull("不该被当成备份名：$bad", FileBackupNaming.originalNameOf(bad))
                assertTrue("isBackup 应为 false：$bad", !FileBackupNaming.isBackup(bad))
            }
    }

    // ── 备份创建 ────────────────────────────────────────────────

    @Test
    fun `覆盖已有文件 - 产生备份且备份内容等于原内容`() {
        val dir = tmp.newFolder("p")
        val target = File(dir, "app.js")
        target.writeText("原始内容")

        val backupName = writeTextFileWithBackup(target, "新内容")

        assertNotNull("覆盖已有文件必须产生备份", backupName)
        val backup = File(dir, backupName!!)
        assertTrue("备份文件应真实存在", backup.isFile)
        assertEquals("备份里必须是**修改前**的内容", "原始内容", backup.readText())
        assertEquals("新内容", target.readText())
    }

    @Test
    fun `新建文件 - 不产生备份 - 返回 null`() {
        val dir = tmp.newFolder("p")
        val target = File(dir, "brand-new.js")

        val backupName = writeTextFileWithBackup(target, "内容")

        assertNull("原文件不存在时无可备份内容，必须返回 null", backupName)
        assertEquals("内容", target.readText())
        assertTrue("目录里不该凭空多出备份", (dir.listFiles() ?: emptyArray()).size == 1)
    }

    @Test
    fun `反复覆盖 - 每次都留一份且内容递进`() {
        val dir = tmp.newFolder("p")
        val target = File(dir, "v.txt")
        target.writeText("v0")

        val names = (1..3).map { i ->
            val n = writeTextFileWithBackup(target, "v$i")
            assertNotNull(n)
            n!!
        }

        assertEquals("三次覆盖应产生三份备份", 3, names.toSet().size)
        // 最新的备份是 v2（写 v3 之前的那个状态）
        val latest = listBackups(dir, "v.txt").first()
        assertEquals("v2", latest.readText())
        assertEquals("v3", target.readText())
    }

    // ── 数量上限（资源类不变量）──────────────────────────────────

    @Test
    fun `超过上限 - 旧的备份被清掉且保留最新的N个`() {
        val dir = tmp.newFolder("p")
        val target = File(dir, "many.txt")
        target.writeText("v0")

        repeat(MAX_BACKUPS_PER_FILE + 3) { i -> writeTextFileWithBackup(target, "v${i + 1}") }

        val left = listBackups(dir, "many.txt")
        assertEquals("备份数必须被上限约束，否则 AI 反复试错会吃满用户存储",
            MAX_BACKUPS_PER_FILE, left.size)
        // listBackups 是「新 → 旧」，第一个应是最后一次写入前的状态
        assertEquals("v${MAX_BACKUPS_PER_FILE + 2}", left.first().readText())
    }

    @Test
    fun `清理只影响本文件 - 别的文件的备份不受影响`() {
        val dir = tmp.newFolder("p")
        val a = File(dir, "a.txt").apply { writeText("a0") }
        val b = File(dir, "b.txt").apply { writeText("b0") }

        repeat(MAX_BACKUPS_PER_FILE + 2) { i ->
            writeTextFileWithBackup(a, "a${i + 1}")
            writeTextFileWithBackup(b, "b${i + 1}")
        }

        assertEquals(MAX_BACKUPS_PER_FILE, listBackups(dir, "a.txt").size)
        assertEquals(MAX_BACKUPS_PER_FILE, listBackups(dir, "b.txt").size)
    }

    // ── 可恢复性：端到端把文件退回去 ────────────────────────────

    @Test
    fun `从备份恢复 - 内容回到修改前`() {
        val dir = tmp.newFolder("p")
        val target = File(dir, "restore-me.txt")
        target.writeText("正确内容")

        val backupName = writeTextFileWithBackup(target, "被改坏了")!!
        val backup = File(dir, backupName)
        target.writeText(backup.readText())

        assertEquals("正确内容", target.readText())
    }

    @Test
    fun `恢复动作本身也备份 - 恢复错了还能再退回来`() {
        val dir = tmp.newFolder("p")
        val target = File(dir, "chain.txt")
        target.writeText("v0")
        writeTextFileWithBackup(target, "v1")   // 备份里是 v0
        writeTextFileWithBackup(target, "v2")   // 备份里是 v1

        // 模拟"恢复到最近备份"（把 v1 写回去）——这次恢复本身也该留后路
        val restoreBackup = File(dir, listBackups(dir, "chain.txt").first().name)
        writeTextFileWithBackup(target, restoreBackup.readText())
        assertEquals("v1", target.readText())

        // 现在应当还能退回 v0
        val older = listBackups(dir, "chain.txt").map { it.readText() }
        assertTrue("恢复后仍应能拿到更早的版本，实际可选项：$older", older.contains("v0"))
    }

    @Test
    fun `同毫秒连续覆盖 - 备份名不撞车`() {
        // 真实场景：writeTextFileWithBackup 的读写都在毫秒内完成，AI 快速试错时
        // 两次覆盖会落在**同一毫秒**。撞名的备份互相覆盖 = 「改了三次只剩一份备份」，
        // 而这是「改错了能退回去」唯一的后路。这里用固定时间戳 + 显式占用表把该行为钉死，
        // 不依赖真实时钟（原先那两条依赖机器快慢，在快机器上必失败）。
        val taken = mutableSetOf<String>()
        val names = (1..3).map {
            FileBackupNaming.backupName("v.txt", 1_760_000_000_000L) { taken.contains(it) }
                .also { n -> taken.add(n) }
        }
        assertEquals("同毫秒三次覆盖必须产生三个不同的备份名", 3, names.toSet().size)
        assertEquals("首个备份用原始时间戳", ".v.txt.1760000000000.bak", names[0])
        assertEquals("撞车时按 +1ms 递增，仍保持名字排序=时间排序", ".v.txt.1760000000002.bak", names[2])
        // 递增后的名字仍能被解析回原名与时间戳
        names.forEach { assertEquals("v.txt", FileBackupNaming.originalNameOf(it)) }
    }

    @Test
    fun `文件名含点 - 往返仍能正确切出原名与时间戳`() {
        // ⚠️ 真实文件名大量带点（index.ink / app.config.json）；lastIndexOf 切错就会把
        // "index" 当原名，清理时连备份带真文件一起判断错
        listOf("app.config.json", "index.ink", "a.b.c.d.txt", "noext").forEach { name ->
            val backupName = FileBackupNaming.backupName(name, 1700000000000L)
            assertEquals("原名还原错误：$name", name, FileBackupNaming.originalNameOf(backupName))
            assertEquals(1700000000000L, FileBackupNaming.timestampOf(backupName))
        }
    }
}
