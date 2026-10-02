package com.rokidlab.phone.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 长期记忆**冲突消解**判据（纯 JVM）。
 *
 * 锁定的行为（mem0 的 update/dedup 语义，见 [LongTermMemoryManager.conflictMerge]）：
 *  - 改述与否定翻转 ⇒ 同一事实的版本，写入时应替换、注入时应去重；
 *  - 仅前半段不同（猫/狗、北京/杭州）⇒ **两条不同的事实**，绝不能合并 ——
 *    误合并丢用户数据，比留冗余严重得多，这是判据保守侧的回归锁。
 */
class MemoryConflictTest {

    // ── 词法相似度 ──

    @Test
    fun `完全相同的文本相似度为 1`() {
        assertEquals(1.0, LongTermMemoryManager.lexicalSimilarity("我喜欢猫", "我喜欢猫"), 1e-9)
    }

    @Test
    fun `毫不相关的文本相似度为 0`() {
        assertEquals(0.0, LongTermMemoryManager.lexicalSimilarity("我喜欢猫", "明天有会议"), 1e-9)
    }

    @Test
    fun `相似度对称`() {
        val a = "我每天9点上班"
        val b = "我每天9点才上班"
        assertEquals(
            LongTermMemoryManager.lexicalSimilarity(a, b),
            LongTermMemoryManager.lexicalSimilarity(b, a),
            1e-9,
        )
    }

    // ── 改述合并（高 Jaccard）──

    @Test
    fun `同一句话的改述应合并`() {
        // 2-gram 的 Jaccard 很苛刻：中间插字会打断两个 gram（J 掉到 ~0.5），
        // 能过 MERGE_JACCARD 的改述基本只有「尾部扩展/差异」形态（0.75+）；
        // 中间插字与深度改述靠否定判据与注入时的嵌入余弦兜底
        assertTrue(LongTermMemoryManager.conflictMerge("我喜欢猫", "我喜欢猫咪"))
        assertTrue(LongTermMemoryManager.conflictMerge("我每天9点上班", "我每天9点上班了"))
        // 细化换词（歌→电影、深圳→南山）J 在 0.66 上下，不过阈值 —— 是另一条信息，不算改述
    }

    // ── 否定翻转（Jaccard 抓不住，靠「包含 + 否定字」判据）──

    @Test
    fun `否定翻转应合并 - 喜欢变不喜欢`() {
        assertTrue(LongTermMemoryManager.conflictMerge("我喜欢猫", "我不喜欢猫"))
    }

    @Test
    fun `否定翻转应合并 - 长版本带语气词`() {
        assertTrue(LongTermMemoryManager.conflictMerge("我叫小乐", "我不叫小乐"))
        assertTrue(LongTermMemoryManager.conflictMerge("我喜欢周杰伦", "我不喜欢周杰伦了"))
    }

    @Test
    fun `否定翻转判据对参数顺序对称`() {
        // 翻转方向两个方向都可能发生（旧短新长 / 旧长新短），判据必须对称
        assertTrue(LongTermMemoryManager.conflictMerge("我不喜欢猫", "我喜欢猫"))
    }

    // ── 不同事实绝不合并（保守侧回归锁）──

    @Test
    fun `仅宾语不同的两条事实不合并`() {
        // 「喜欢猫 → 不喜欢猫」是更新；「喜欢猫 vs 喜欢狗」是两个偏好 —— 前者合并后者共存
        assertFalse(LongTermMemoryManager.conflictMerge("我喜欢猫", "我喜欢狗"))
    }

    @Test
    fun `仅宾语不同的细化不合并`() {
        // 「喜欢歌 vs 喜欢电影」：词法高度重叠但无否定差异，是两条事实
        assertFalse(LongTermMemoryManager.conflictMerge("我喜欢周杰伦的歌", "我喜欢周杰伦的电影"))
    }

    @Test
    fun `地点不同的两条事实不合并`() {
        assertFalse(LongTermMemoryManager.conflictMerge("我住在北京", "我住在杭州"))
        assertFalse(LongTermMemoryManager.conflictMerge("我明天有空", "我明天没空"))
    }

    @Test
    fun `长度差过大的文本不做否定判定`() {
        // 两条完全不同的长内容即使一方包含另一方的大部分 gram，也不是同一句话
        assertFalse(
            LongTermMemoryManager.conflictMerge(
                "我喜欢猫",
                "我喜欢猫不喜欢狗，每天要遛狗三次，周末还要去宠物店买猫粮",
            ),
        )
    }

    @Test
    fun `空文本不参与合并`() {
        assertFalse(LongTermMemoryManager.conflictMerge("", "我喜欢猫"))
        assertFalse(LongTermMemoryManager.conflictMerge("我喜欢猫", ""))
    }
}
