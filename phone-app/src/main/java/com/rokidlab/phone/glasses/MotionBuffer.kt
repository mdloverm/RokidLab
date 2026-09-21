package com.rokidlab.phone.glasses

/**
 * IMU 头动数据环形缓冲（v1 查询层）。
 *
 * 眼镜端 ~20Hz 批量上行（500ms/批）→ CxrLHiRokidSession 收到后写入这里；
 * get_head_pose / get_motion_history 工具与 v2 MotionRuleEngine 从这里取数。
 * 只保留最近 [WINDOW_MS]（60s）≈1200 个样本，过期/超容即丢弃（环形语义）。
 *
 * 线程安全：写入来自 CXR binder 线程，读取来自 ai-turn-worker / 规则引擎线程，统一锁保护。
 * 内存量级：1300 × 11 float ≈ 57KB 常驻，可忽略。
 */
object MotionBuffer {
    /** 时间窗口：60s（规则引擎的动作判定窗口足够；更早的数据没有消费者） */
    private const val WINDOW_MS = 60_000L
    /** 容量上限：60s × 20Hz 冗余 8%，防短时高频事件撑爆内存 */
    private const val MAX_SAMPLES = 1300

    private val lock = Any()
    private val samples = ArrayDeque<AiChannel.ImuSample>(MAX_SAMPLES)

    /** 追加一批样本并按时间窗裁剪 */
    fun append(batch: List<AiChannel.ImuSample>) {
        if (batch.isEmpty()) return
        val now = System.currentTimeMillis()
        synchronized(lock) {
            samples.addAll(batch)
            while (samples.isNotEmpty() &&
                (now - samples.first().t > WINDOW_MS || samples.size > MAX_SAMPLES)
            ) {
                samples.removeFirst()
            }
        }
    }

    /** 最新样本；缓冲为空返回 null */
    fun latest(): AiChannel.ImuSample? = synchronized(lock) { samples.lastOrNull() }

    /** 最新样本距今的毫秒数；无数据返回 [Long.MAX_VALUE]（调用方据此判定「流断了」） */
    fun newestAgeMs(): Long {
        val l = latest() ?: return Long.MAX_VALUE
        return System.currentTimeMillis() - l.t
    }

    /** 取最近 [windowMs] 毫秒内的样本（时间升序快照） */
    fun recent(windowMs: Long): List<AiChannel.ImuSample> {
        val now = System.currentTimeMillis()
        return synchronized(lock) { samples.filter { now - it.t <= windowMs } }
    }

    /** 清空（断连时调用，防陈旧数据混入新会话的判定窗口） */
    fun clear() = synchronized(lock) { samples.clear() }
}
