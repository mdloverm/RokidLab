package com.rokidlab.phone.ai

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 产物回报通道的不变量（2026-10-13 审计修配套）。
 *
 * 这些断言指向**生产代码本身**（[ProducedFileSink]），不是测试里的复制品 ——
 * 它守的是"过程行里的产物芯片不会指向错的东西"这条底线：
 *  - 只收绝对路径（相对路径无法定位 ⇒ 宁可少显示，与 AgentStep.artifactOf 同判据）；
 *  - 取走即清 / begin 清残留 / clear 兜底 ⇒ 上一件产物不会错挂到下一个工具行上
 *    （这些工具跑在同一根泳道线程上，残留会被后来者读到 —— 与 ThreadLocal 图片通道同类风险）。
 *
 * ⚠️ 路径必须用**跨平台**的绝对路径构造（`java.io.tmpdir` 拼接）：
 *   `/sdcard/Download/x.zip` 在 Android 上是绝对路径，但在 Windows 的 JVM 上
 *   `File.isAbsolute` 判 false（缺盘符）—— 直接用会让断言在开发机上假失败。
 */
class ProducedFileSinkTest {

    /** 跨平台绝对路径（Android 单测跑在 JVM 上，`/sdcard/...` 不是绝对路径） */
    private fun abs(name: String): String = File(System.getProperty("java.io.tmpdir"), name).absolutePath

    @After
    fun tearDown() {
        ProducedFileSink.clear()
    }

    @Test
    fun `只收绝对路径，相对路径被忽略`() {
        ProducedFileSink.begin()
        ProducedFileSink.record("relative/foo.zip")
        ProducedFileSink.record(abs("ok.zip"))

        val out = ProducedFileSink.take()
        assertEquals("相对路径必须被丢弃（无法定位 ⇒ 不给一个打不开的芯片）", 1, out.size)
        assertEquals(abs("ok.zip"), out[0].path)
    }

    @Test
    fun `展示名缺省取文件名`() {
        ProducedFileSink.begin()
        ProducedFileSink.record(abs("report.zip"))

        assertEquals("report.zip", ProducedFileSink.take()[0].label)
    }

    @Test
    fun `同路径去重`() {
        ProducedFileSink.begin()
        ProducedFileSink.record(abs("a.zip"), "a")
        ProducedFileSink.record(abs("a.zip"), "a")

        assertEquals(1, ProducedFileSink.take().size)
    }

    @Test
    fun `take 取走即清 - 第二次为空`() {
        ProducedFileSink.begin()
        ProducedFileSink.record(abs("a.zip"))

        assertEquals(1, ProducedFileSink.take().size)
        assertTrue("取走后必须清空槽位（否则下一件产物会错挂到下一个工具行）", ProducedFileSink.take().isEmpty())
    }

    @Test
    fun `begin 清掉上次残留`() {
        ProducedFileSink.record(abs("stale.zip"))
        ProducedFileSink.begin()

        assertTrue(
            "begin 必须清残留：同一根泳道线程被复用时，上一次的产物绝不能挂到本次调用上",
            ProducedFileSink.take().isEmpty(),
        )
    }

    @Test
    fun `clear 兜底清空`() {
        ProducedFileSink.begin()
        ProducedFileSink.record(abs("a.zip"))
        ProducedFileSink.clear()

        assertTrue(ProducedFileSink.take().isEmpty())
    }
}
