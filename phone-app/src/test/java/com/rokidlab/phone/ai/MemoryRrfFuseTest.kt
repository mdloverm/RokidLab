package com.rokidlab.phone.ai

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 长期记忆 RRF 名次融合金标（纯 JVM）。
 *
 * 融合决定「词法命中」与「语义命中」谁进注入窗口：公式、并列序一旦改变，
 * 检索行为会整体漂移，测试把语义钉死。RRF 贡献 = 1/(60+rank+1)，rank 从 0 起。
 */
class MemoryRrfFuseTest {

    @Test
    fun `两路名次完全一致时顺序不变`() {
        assertEquals(listOf(0, 1, 2), LongTermMemoryManager.rrfFuse(listOf(0, 1, 2), listOf(0, 1, 2)))
    }

    @Test
    fun `两路都沾边的条目胜过单路头名`() {
        // 0：词法第2(1/62) + 语义第1(1/61)；5：仅词法第1(1/61)；1：仅语义第2(1/62)
        // 0 ≈ 0.0325 > 5 ≈ 0.01639 > 1 ≈ 0.01613 —— 语义路把词法漏掉的改述顶进窗口
        val fused = LongTermMemoryManager.rrfFuse(lexRanked = listOf(5, 0), vecRanked = listOf(0, 1))
        assertEquals(listOf(0, 5, 1), fused)
    }

    @Test
    fun `单路缺席时另一路原序保留`() {
        assertEquals(listOf(3, 1), LongTermMemoryManager.rrfFuse(emptyList(), listOf(3, 1)))
        assertEquals(listOf(2, 7), LongTermMemoryManager.rrfFuse(listOf(2, 7), emptyList()))
    }

    @Test
    fun `双空输入返回空`() {
        assertEquals(emptyList<Int>(), LongTermMemoryManager.rrfFuse(emptyList(), emptyList()))
    }

    @Test
    fun `总分相同时保持词法先入的稳定序`() {
        // 0 与 1 都拿到 1/61 + 1/62：0 在词法路先插入，并列时排前 —— 输出必须确定可测
        val fused = LongTermMemoryManager.rrfFuse(lexRanked = listOf(0, 1), vecRanked = listOf(1, 0))
        assertEquals(listOf(0, 1), fused)
    }

    @Test
    fun `DB 版本为 v3 且带向量列迁移承诺`() {
        // 版本号变更必须成对修改 onCreate/onUpgrade（见 MemoryDb）；
        // 钉住当前版本，误改版本号而漏迁移（drop 重建丢记忆）时第一时间暴露
        assertEquals(3, LongTermMemoryManager.DB_VERSION)
    }
}
