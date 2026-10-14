package com.rokidlab.phone.ai.tools

import com.rokidlab.phone.platform.ExecResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「超时 ≠ 环境没起来」这条判据的护栏。
 *
 * ## 为什么值得单钉一条
 * `ExecResult.failure` 同时承载两种完全不同的成因 —— *根本没起来*（二进制缺失 / SELinux 挡住）
 * 与 *起来了但超时被强杀*。两者在 `failure` 上长得一模一样，上层一旦不区分，就会把超时报成
 * 「命令没被拉起来（环境本身有问题）」。
 *
 * 2026-10-12 真机实锤的代价：模型写的 `node /tmp/mt2.cjs` 事件循环不退出 → 超时；
 * 结果文字把「环境坏了」写给了模型和用户，**双方都被带偏去查容器**，而容器自检一切正常。
 * 这条测试就是那次事故的钉子：分流判据（[ExecResult.timedOut]）被改坏时立刻红。
 */
class ShellTimeoutResultTest {

    /** 超时：进程**起来过**，只是没结束 —— `launched` 必须为 true，`ok` 必须为 false */
    @Test
    fun `超时结果算已拉起但不算成功`() {
        val r = ExecResult(-1, "已产生的一点输出", "", "超时 180s（已强杀）", timedOut = true)
        assertTrue("超时说明进程跑过，不该等同于'没拉起'", r.launched)
        assertFalse("超时不是成功", r.ok)
        assertTrue("日志摘要要能一眼看出是超时", r.summary().contains("超时中断"))
    }

    /** 真正没起来（二进制不在位 / IOException）：保持原语义，别被 timeout 分流吃掉 */
    @Test
    fun `未拉起的结果仍然算未拉起`() {
        val r = ExecResult(-1, "", "", "二进制不在位：/data/app/.../libproot.so")
        assertFalse("没起来就是没起来", r.launched)
        assertFalse(r.ok)
        assertTrue(r.summary().contains("未拉起"))
    }

    /** 正常退出码 0 不受影响（防止改字段时把常规路径也带歪） */
    @Test
    fun `正常成功结果判据不变`() {
        val r = ExecResult(0, "ok", "")
        assertTrue(r.launched && r.ok)
        assertEquals("OK（退出码 0）", r.summary())
    }

    /**
     * 回填给模型的文字：超时**不得**出现「环境本身有问题」这套说法，
     * 且必须给出可行动的方向（timeout 包一层 / 后台跑）。
     */
    @Test
    fun `超时回填文字不得说环境坏了`() {
        val text = ShellToolProvider.format(
            ExecResult(-1, "启动中…", "", "超时 180s（已强杀）", timedOut = true),
        )
        assertFalse(
            "超时被说成环境故障 ＝ 那次事故的原样复现",
            text.contains("环境本身有问题") || text.contains("没被拉起来"),
        )
        assertTrue("要明确否认环境故障", text.contains("不是环境故障"))
        assertTrue("要给出可行办法", text.contains("timeout") || text.contains("background"))
        assertTrue("超时前的输出不能丢（那是排查线索）", text.contains("启动中…"))
    }

    /** 对照组：真正没起来时仍要引导去跑自检 —— 分流不能把这条正常诊断也砍掉 */
    @Test
    fun `未拉起时仍引导用户跑自检`() {
        val text = ShellToolProvider.format(ExecResult(-1, "", "", "启动失败：IOException: error=13"))
        assertTrue(text.contains("环境本身有问题"))
        assertTrue(text.contains("自检"))
    }
}
