package com.rokidlab.phone.connection

import android.util.Log
import java.util.concurrent.atomic.AtomicLong

/**
 * 眼镜蓝牙通道优先级（数值越大优先级越高）。
 */
enum class ChannelPriority(val weight: Int) {
    /** ASR 兜底轮询（断连期间积压文件的补读）：任何更高优先级占用时都应让路 */
    BACKGROUND(0),

    /**
     * ADB 工具页、AI 工具查询、AIUI 工具、定时任务：常规控制面。
     *
     * 与 [LONG_LIVED] 冲突时经 `CxrLHiRokidSession.getAdbShellClient()` 的 [ChannelArbiter.shouldYield]
     * 守卫让路；NORMAL 消费者之间**共享同一条 ADB 会话**（`platform/AdbTransport` 唯一所有者），
     * 彼此互不让路，因此不持有常驻租约 —— 只有长连接才需要 [acquire]。
     */
    NORMAL(1),

    /** 屏幕镜像 / 手机投屏 / 文件浏览：需长时间独占 RFCOMM SCN */
    LONG_LIVED(2),
}

/**
 * 通道租约。[close] 幂等。
 */
interface ChannelLease : AutoCloseable {
    val owner: String
    val priority: ChannelPriority
    val isActive: Boolean
    override fun close()
}

/**
 * 眼镜蓝牙通道仲裁器。
 *
 * 背景：手机侧蓝牙栈对「同一设备 + 同一 SCN」只允许一条客户端 RFCOMM 通道。
 * 这条约束此前由各消费方各自 `reserveTunnel() / releaseTunnel()`（一个 AtomicInteger 计数）
 * 手工保证 —— 分散在 3 个 Activity/Service + 1 个兜底轮询里，漏调用 `releaseTunnel()`
 * 就会永久泄漏通道，且没有任何地方能观测到。
 *
 * 本类把它收敛为「按优先级持有租约」：
 *  - 消费方 [acquire] 取得 [ChannelLease]，离场 `close()`（幂等，重复调用安全）；
 *  - 低优先级消费方用 [shouldYield] 判断是否该让路（存在**严格更高**优先级持有者时为 true）；
 *  - [snapshot] 暴露当前持有者；LONG_LIVED 持有超 [LEAK_WARN_MS] 会打点告警，
 *    让「忘记释放」从不可见变成可观测。
 *
 * 设计取舍：这是**协作式闸门**，不是互斥锁 —— [acquire] 永不阻塞、永不失败，
 * 让路由调用方按 [shouldYield] 自行退避（与既有 AsrBridgeCoordinator 的退避语义一致，
 * 也避免长连接建链期被锁阻塞）。真正的「结构化释放」由后续的 AdbSession 引用计数租约补齐。
 *
 * 与 L0 `platform/AdbTransport` 的分工：本类只回答「谁该让路」——纯内存优先级判定，无 IO、
 * 不碰 socket；ADB 会话的复用 / 重建 / 释放由 `AdbTransport` 作为唯一所有者统一持有。
 * 二者互补且不重叠：本类不感知会话生命周期，`AdbTransport` 不感知优先级。长连接上场前的
 * 完整动作是：`domain/MirrorCoordinator` 取 [ChannelPriority.LONG_LIVED] 租约 **并** 调用
 * `CxrLHiRokidSession.releaseAdbShellClient()` 腾出 RFCOMM；常规消费者（[ChannelPriority.NORMAL]）
 * 则通过 `CxrLHiRokidSession.getAdbShellClient()` 内的 [shouldYield] 守卫接入本类。
 */
class ChannelArbiter {
    companion object {
        private const val TAG = "ChannelArbiter"

        /** 长连接租约持有超过该时长即视为疑似泄漏并打点（仅告警，不强制回收） */
        private const val LEAK_WARN_MS = 10 * 60_000L
    }

    private data class Holder(
        val id: Long,
        val owner: String,
        val priority: ChannelPriority,
        val acquiredAt: Long,
    )

    private val seq = AtomicLong(0)
    private val lock = Any()
    private val holders = LinkedHashMap<Long, Holder>()

    /** 当前持有通道的最高优先级；无持有者返回 null */
    val topPriority: ChannelPriority?
        get() = synchronized(lock) { holders.values.maxByOrNull { it.priority.weight }?.priority }

    /** 是否已有长连接消费者（LONG_LIVED）占用通道 */
    val isLongLivedHeld: Boolean
        get() = synchronized(lock) {
            holders.values.any { it.priority == ChannelPriority.LONG_LIVED }
        }

    /**
     * 指定优先级此刻是否应让路：存在**严格更高**优先级的持有者时为 true。
     *
     * 例：BACKGROUND 兜底轮询在 LONG_LIVED 长连接持有期间应让路；
     * 同级（NORMAL vs NORMAL）互不让路。
     */
    fun shouldYield(priority: ChannelPriority): Boolean =
        synchronized(lock) { holders.values.any { it.priority.weight > priority.weight } }

    /** 取一份租约（非阻塞、必成功）。 */
    fun acquire(owner: String, priority: ChannelPriority): ChannelLease = synchronized(lock) {
        val id = seq.incrementAndGet()
        holders[id] = Holder(id, owner, priority, System.currentTimeMillis())
        Log.i(TAG, "acquire #$id $owner p=${priority.name} active=${holders.size}")
        Lease(id, owner, priority)
    }

    private fun release(id: Long) {
        synchronized(lock) {
            val h = holders.remove(id) ?: return
            val heldMs = System.currentTimeMillis() - h.acquiredAt
            Log.i(TAG, "release #$id ${h.owner} held=${heldMs}ms active=${holders.size}")
            if (h.priority == ChannelPriority.LONG_LIVED && heldMs > LEAK_WARN_MS) {
                Log.w(TAG, "long-lived lease held ${heldMs / 1000}s (${h.owner}) — 疑似未释放")
            }
        }
    }

    /** 当前持有者快照（调试用）：`owner:优先级:已持有时长` */
    fun snapshot(): List<String> = synchronized(lock) {
        val now = System.currentTimeMillis()
        holders.values.map { "${it.owner}:${it.priority.name}:${now - it.acquiredAt}ms" }
    }

    private inner class Lease(
        private val id: Long,
        override val owner: String,
        override val priority: ChannelPriority,
    ) : ChannelLease {
        @Volatile
        private var active = true

        override val isActive: Boolean get() = active

        override fun close() {
            if (!active) return // 幂等：重复 close 不重复释放
            active = false
            release(id)
        }
    }
}
