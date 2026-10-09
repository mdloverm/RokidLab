package com.rokidlab.phone.ai.approval

import com.rokidlab.phone.ai.approval.ShellStageAnalyzer.StageResolution
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [ShellStageAnalyzer] 单测 —— 复合 shell 命令的分阶段风险判定。
 *
 * 锁住三组语义：
 *
 * **A. 靶子是「真实用户数据」而不是「危险命令行」**
 *    `rm -rf /mnt/lab` 必须拦，`rm -rf /tmp/build` 必须放 —— 后者在可丢弃容器里完全无害，
 *    按动词拦会大量误伤日常命令（用户与模型都在容器里频繁删临时文件）。
 *    这组是本类存在的**唯一**理由，也是与「按关键词拦命令」那种做法的分界线。
 *
 * **B. 静态看不透 ⇒ 绝不自动放行**（Muse 的 `argv_complete = false` 语义）
 *    命令替换 / 变量前缀 / 子 shell / 前序 cd 改变了语义 —— 一律交审批。
 *    代价是多问一次，反面是漏掉一次真实删除。两者不对等。
 *
 * **C. 正常用法不受影响**（否则这个特性会被关掉）
 *    往 `/mnt/lab` 写项目、容器内折腾、只读查询真实目录 —— 全部自动放行。
 *    一条"安全但啰嗦"的审批设计最终会被用户整体关掉，那等于没有。
 */
class ShellStageAnalyzerTest {

    private fun stages(cmd: String) = ShellStageAnalyzer.analyze(cmd)

    private fun needsReview(cmd: String) = ShellStageAnalyzer.needsReview(stages(cmd))

    // ══════════════════════════════════════════════════════════════════
    // A. 靶子 = 真实用户数据
    // ══════════════════════════════════════════════════════════════════

    @Test
    fun `A1 删除真实挂载点上的文件必须拦`() {
        assertTrue("rm 用户的真实文件必须审批", needsReview("rm -rf /mnt/lab/报告.docx"))
        assertTrue("rmdir 同理", needsReview("rmdir /mnt/lab/old"))
        assertTrue("mv 覆盖也是破坏", needsReview("mv /mnt/lab/a.txt /mnt/lab/b.txt"))
        assertTrue("truncate 会清空真实文件", needsReview("truncate -s 0 /mnt/lab/data.csv"))
    }

    @Test
    fun `A2 容器内的删除不拦`() {
        // ★ 这条是误伤防线：按"命令含 rm -rf"拦的实现在这里会红。
        //   容器可丢弃是 run_shell 定档 LOCAL_SIDE_EFFECT 的原判据，
        //   这条判据在容器范围内仍然成立，不能被新规则推翻。
        assertFalse("容器内的临时文件随便删", needsReview("rm -rf /tmp/build"))
        assertFalse("删容器内的 rootfs 也只是重下", needsReview("rm -rf /var/cache/apt"))
        assertFalse("相对路径在容器默认目录下", needsReview("rm -f out.log"))
    }

    @Test
    fun `A3 三阶段命令只在破坏阶段要求审批`() {
        val s = stages("wc -l /mnt/lab/report.log && echo cleaning && rm -rf /mnt/lab/report.log")
        assertEquals("必须拆成三个阶段", 3, s.size)
        assertEquals("只读阶段自动放行", StageResolution.AUTO_SAFE, s[0].resolution)
        assertEquals("echo 自动放行", StageResolution.AUTO_SAFE, s[1].resolution)
        assertEquals(
            "破坏真实文件的阶段必须 held",
            StageResolution.REVIEW_DANGEROUS,
            s[2].resolution,
        )
        // 审批文案只列需要点头的那一步 —— 自动放行的阶段对用户是噪音
        val summary = ShellStageAnalyzer.reviewSummary(s)
        assertTrue("摘要要含被拦的那条命令：$summary", summary.contains("rm -rf /mnt/lab/report.log"))
        assertFalse("摘要不该列已自动放行的阶段：$summary", summary.contains("wc -l"))
    }

    @Test
    fun `A4 往真实挂载点写文件不拦（生成项目的主力用法）`() {
        // ⚠️ 这是**有意的取舍**：把所有写都拦会把功能废掉
        //（生成项目落到「下载/Lab」是本项目最常见的用法）。
        // 只有删除/覆盖类才拦 —— 见类注释。
        assertFalse("重定向新建文件", needsReview("echo hello > /mnt/lab/note.txt"))
        assertFalse("mkdir 是新建不是破坏", needsReview("mkdir -p /mnt/lab/myproj"))
        assertFalse("cp 进去是新增", needsReview("cp /tmp/a.py /mnt/lab/myproj/"))
        assertFalse("只读镜像目录可以读", needsReview("cat /mnt/download/note.txt"))
    }

    @Test
    fun `A5 覆写只读挂载点要拦`() {
        assertTrue(
            "试图写只读挂载点不是'写不进去'的问题，是意图可疑",
            needsReview("echo x > /mnt/download/evil.txt"),
        )
    }

    // ══════════════════════════════════════════════════════════════════
    // B. 静态看不透 ⇒ 不自动放行（argv_complete = false）
    // ══════════════════════════════════════════════════════════════════

    @Test
    fun `B1 命令替换不得自动放行`() {
        // `rm -rf $(cat list)` 的目标完全不可知 —— 最坏可能删掉真实文件
        assertTrue(needsReview("rm -rf \$(cat list.txt)"))
        assertTrue(needsReview("rm -rf `cat list.txt`"))
        val s = stages("rm -rf \$(cat list.txt)")
        assertFalse("含替换的阶段必须标 argv_complete=false", s[0].argvComplete)
        assertEquals("且判定为看不透", StageResolution.REVIEW_UNRESOLVED, s[0].resolution)
    }

    @Test
    fun `B2 变量赋值前缀不得自动放行`() {
        // 经典绕过：先赋值再引用，静态看不到值
        assertTrue(needsReview("TARGET=/mnt/lab; rm -rf \$TARGET"))
        assertTrue(needsReview("PATH2=/mnt/lab/secret rm -rf \$PATH2"))
        assertTrue(needsReview("export D=/mnt/lab && rm -rf \$D"))
    }

    @Test
    fun `B3 前序 cd 进真实目录后相对路径删除要拦`() {
        // ★ 最隐蔽的一条：第二阶段的 argv 里根本没有 /mnt/lab 字样
        //（`rm -rf *`），只看字面路径的实现在这里会漏放。
        assertTrue(needsReview("cd /mnt/lab && rm -rf *"))
        assertTrue(needsReview("cd /mnt/lab/\nrm -rf ."))
        val s = stages("cd /mnt/lab && rm -rf *")
        assertEquals("cd 阶段本身不是破坏", StageResolution.AUTO_SAFE, s[0].resolution)
        assertEquals(
            "删除阶段必须因「前序 cd」被判看不透",
            StageResolution.REVIEW_UNRESOLVED,
            s[1].resolution,
        )
    }

    @Test
    fun `B4 子 shell 与命令组不透明`() {
        assertTrue(needsReview("(cd /mnt/lab && rm -rf x)"))
        assertTrue(needsReview("{ cd /mnt/lab; rm -rf x; }"))
    }

    @Test
    fun `B5 cd 进容器目录不影响后续判定`() {
        // 与 B3 对称：前序 cd 的目标是容器内 ⇒ 后续相对删除仍无害
        assertFalse("容器内 cd 之后删相对路径仍安全", needsReview("cd /tmp && rm -rf work"))
    }

    // ══════════════════════════════════════════════════════════════════
    // C. 两义命令按**参数**判定，不按名字
    // ══════════════════════════════════════════════════════════════════

    @Test
    fun `C1 find 只读查询不拦 删除要拦`() {
        assertFalse("最常见的只读查询不能误伤", needsReview("find /mnt/lab -name '*.txt'"))
        assertTrue("find -delete 是删除", needsReview("find /mnt/lab -name '*.tmp' -delete"))
        assertTrue("find -exec rm 也是删除", needsReview("find /mnt/lab -exec rm {} \\;"))
    }

    @Test
    fun `C2 sed 就地改写要拦`() {
        assertFalse("sed 输出到 stdout 是只读", needsReview("sed 's/a/b/' /mnt/lab/x.txt"))
        assertTrue("sed -i 会改写原文件", needsReview("sed -i 's/a/b/' /mnt/lab/x.txt"))
    }

    @Test
    fun `C3 git 破坏性子命令`() {
        assertTrue(needsReview("git -C /mnt/lab clean -fd"))
        assertTrue(needsReview("git -C /mnt/lab reset --hard"))
    }

    // ══════════════════════════════════════════════════════════════════
    // D. 分词与切分
    // ══════════════════════════════════════════════════════════════════

    @Test
    fun `D1 引号内的分隔符不拆阶段`() {
        val s = stages("echo \"a && b\"")
        assertEquals("引号里的 && 不是分隔符", 1, s.size)
    }

    @Test
    fun `D2 单条命令也返回一个阶段`() {
        assertEquals(1, stages("ls -la").size)
    }

    @Test
    fun `D3 空命令返回空列表`() {
        assertEquals(0, stages("").size)
        assertEquals(0, stages("   ").size)
    }

    @Test
    fun `D4 管道与分号都拆`() {
        assertEquals(2, stages("ls /mnt/lab | head -5").size)
        assertEquals(2, stages("echo a; echo b").size)
        assertEquals(2, stages("cat /mnt/lab/x.txt\nwc -l /mnt/lab/x.txt").size)
    }

    @Test
    fun `D5 只读管道到真实目录不拦`() {
        assertFalse(needsReview("grep -r TODO /mnt/lab | head -20"))
        assertFalse(needsReview("ls -la /mnt/lab | wc -l"))
    }

    @Test
    fun `D6 全自动放行时 needsReview 为 false 且摘要为空`() {
        val s = stages("ls /tmp && echo done")
        assertFalse(ShellStageAnalyzer.needsReview(s))
        assertEquals("", ShellStageAnalyzer.reviewSummary(s))
    }

    /**
     * 📌 这条锁的是**判据本身**，不是某个用例：
     * 确认"按路径而不是按动词"这条设计确实生效 —— 同一个动词在两种目标下结论相反。
     * 若将来有人把实现改成"看到 rm -rf 就拦"或"看到 /mnt/lab 就拦"，这里会红。
     */
    @Test
    fun `D7 同一动词因目标不同而结论相反`() {
        assertFalse("容器内 => 放", needsReview("rm -rf /tmp/x"))
        assertTrue("真实文件 => 拦", needsReview("rm -rf /mnt/lab/x"))
        assertFalse("碰真实目录但只是读 => 放", needsReview("ls /mnt/lab"))
    }
}
