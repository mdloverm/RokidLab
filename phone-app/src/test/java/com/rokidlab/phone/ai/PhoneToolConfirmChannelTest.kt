package com.rokidlab.phone.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * 手机端确认通道（[PhoneToolConfirmChannel]）的**纯 JVM 可测部分**回归测试。
 *
 * ## 为什么只能测这一小块
 * 这个通道真正的行为（拉 `Activity`、等 `CountDownLatch`、读链路/悬浮窗状态）必须要有真实
 * 的 `Context` 与主线程消息循环，本项目单测**没有** Robolectric（`build.gradle.kts` 只有
 * `unitTests.isReturnDefaultValues = true` + 真实 `org.json`）。因此这里只锁两件事：
 *  1. **审计标识**：两条通道的 `channelId` 必须不同（否则日志里分不清"问到谁了"，
 *     而"这次到底问的是眼镜还是手机"正是排查确认链路的第一个问题）；
 *  2. **未初始化时的诚实**：`isAvailable()` / `confirm()` 必须返回 false，**绝不假装问过**。
 *
 * ★ 第 2 条不是形式主义：如果未注入 Context 时 `confirm()` 返回 true，闸门会认为
 * "用户同意了"，于是一个需要确认的动作被**静默执行** —— 而用户什么都没看到。
 * 那是比"拒绝"坏得多的一种失败（用户以为没人问、系统以为问过了）。
 *
 * ⚠️ 通道**选择**逻辑（眼镜优先、手机兜底）不在这里测 —— 它在
 * [com.rokidlab.phone.ai.approval.ApprovalGate.resolveAsk] 里，用例是
 * `ApprovalGateTest.F9` / `F10`。这里不重复造一份"通道选择"的假实现。
 */
class PhoneToolConfirmChannelTest {

    @Test
    fun `通道标识与眼镜通道不同 便于日志归因`() {
        assertEquals("phone", PhoneToolConfirmChannel.channelId)
        assertEquals("glass", GlassToolConfirmChannel.global.channelId)
        assertNotEquals(
            "两个通道的 channelId 若相同，审计日志里就无法区分「问到谁了」",
            GlassToolConfirmChannel.global.channelId,
            PhoneToolConfirmChannel.channelId,
        )
    }

    @Test
    fun `未注入 Context 时如实报不可用 不假装能拉起界面`() {
        // 单测环境从不调 init()，appContext 恒为 null ⇒ 必须走"不可用"分支。
        // 若这里返回 true，闸门会把一个注定拉不起来的通道当成可用，白等 40s 才超时。
        assertFalse(PhoneToolConfirmChannel.isAvailable())
    }

    @Test
    fun `未注入 Context 时 confirm 返回 false 不假装问过`() {
        // ★ 安全关键：返回 true = "用户同意了"。未初始化时必须 false，
        // 让闸门按「问不到」处理（再由工具声明的 ToolConfirmPolicy 分流），
        // 而不是让一个需要点头的动作被静默执行。
        assertFalse(PhoneToolConfirmChannel.confirm("send_sms", "发送短信给 10086"))
        assertFalse("未问到 ⇒ 也不能声称用户取消了", PhoneToolConfirmChannel.wasCancelled())
    }

    @Test
    fun `abortAll 在无等待者时是幂等的空操作`() {
        // 连接流程重启 / 被切到后台时会无差别调用它，不能因为"没有等待者"抛异常，
        // 也不能把通道状态改成别的（它只该清空等待者，不该影响可用性判定）
        PhoneToolConfirmChannel.abortAll()
        PhoneToolConfirmChannel.abortAll()
        assertFalse("abortAll 不该把通道变成「可用」", PhoneToolConfirmChannel.isAvailable())
    }
}
