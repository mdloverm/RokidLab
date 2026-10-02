package com.rokidlab.phone.ai.subagent

import java.util.concurrent.Semaphore

/**
 * 子代理并发闸门（2026-10-02，放宽评估 C 档 #6「并发 1」）。
 *
 * 原实现 `ReadOnlySubagent` 用单个 AtomicBoolean，同一时间只允许 1 个子任务。
 * 放宽到 **2**（DSH §4.3.2 的「并发 2-3」取下限）：调研/执行类子任务常是
 * 「A 查着、B 也想派」的形态，2 路并发能把串行等待砍半，同时仍守住
 * 「并发子代理 = 并发模型请求」的代价上限（token 翻倍、限流易触顶、真机排查难）。
 *
 * 读写两种子代理**共用**这一个槽位池：它们都是主循环里的同步阻塞嵌套调用，
 * 混用同一上限才能保证「同时最多 2 个模型请求在跑子任务」这一全局承诺，
 * 不会出现「读 2 个 + 写 2 个 = 4 路」的叠加。
 *
 * 为什么仍是进程内 Semaphore 而不落盘：它只影响「要不要等一等」，
 * 丢了大不了模型收到"满员"提示后自己串行重派。
 */
internal object SubagentSlots {

    /** 同时运行的子代理上限（读写合计） */
    private const val MAX_CONCURRENT = 2

    private val slots = Semaphore(MAX_CONCURRENT)

    /** 当前空闲槽位数（供工具结果文案/日志使用） */
    val available: Int get() = slots.availablePermits()

    /**
     * 尝试占一个槽位。占到了就执行 [block]，结束自动归还；
     * 满员时直接返回 [onFull] 的文案（不排队 —— 排队会让主循环把用户晾更久，
     * 不如让主代理自己决定串行重派还是直接自己查）。
     */
    inline fun <T> tryWithSlot(onFull: () -> T, block: () -> T): T {
        if (!slots.tryAcquire()) return onFull()
        return try {
            block()
        } finally {
            slots.release()
        }
    }
}
