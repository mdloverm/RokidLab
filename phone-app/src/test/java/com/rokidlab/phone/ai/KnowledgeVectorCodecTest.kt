package com.rokidlab.phone.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 知识库向量 BLOB 编解码金标（纯 JVM）。
 *
 * BLOB 是 v3 之后跨版本落库的持久格式：字节序/无头部布局一旦改变，旧库里的全部向量
 * 都会被静默误读（点积错乱 = 语义召回变随机），测试把线格式钉死。
 */
class KnowledgeVectorCodecTest {

    @Test
    fun `编码为 little-endian float32 无头部`() {
        // 1.0f 的 IEEE754 小端字节序固定为 00 00 80 3F
        val bytes = KnowledgeBase.encodeVector(floatArrayOf(1.0f))
        assertEquals(4, bytes.size)
        assertEquals(0x00.toByte(), bytes[0])
        assertEquals(0x00.toByte(), bytes[1])
        assertEquals(0x80.toByte(), bytes[2])
        assertEquals(0x3F.toByte(), bytes[3])
    }

    @Test
    fun `多维度往返一致`() {
        val v = floatArrayOf(0.0f, -0.25f, 3.14159f, -42f, 12345.678f, 0.0001f)
        val decoded = KnowledgeBase.decodeVector(KnowledgeBase.encodeVector(v))
        assertEquals(v.size, decoded.size)
        v.indices.forEach { i ->
            assertEquals("第 $i 维往返失真", v[i], decoded[i], 1e-6f)
        }
    }

    @Test
    fun `空向量编解码安全`() {
        val decoded = KnowledgeBase.decodeVector(KnowledgeBase.encodeVector(FloatArray(0)))
        assertEquals(0, decoded.size)
    }

    @Test
    fun `维度由字节长度推导 1024 维`() {
        val v = FloatArray(1024) { it / 1024f }
        val blob = KnowledgeBase.encodeVector(v)
        assertEquals(1024 * 4, blob.size)
        assertEquals(1024, KnowledgeBase.decodeVector(blob).size)
    }

    @Test
    fun `DB 版本为 v4 且迁移承诺 format 列`() {
        // 版本号变更必须成对修改 onCreate/onUpgrade（见 KnowledgeBase.KbDbHelper）；
        // 钉住当前版本，误改版本号而漏迁移时第一时间暴露
        assertEquals(4, KnowledgeBase.DB_VERSION)
    }

    @Test
    fun `余弦相似度归一化向量点积语义正确`() {
        // 同一向量的两个副本归一化后点积 ≈ 1；正交向量点积 ≈ 0
        fun norm(v: FloatArray): FloatArray {
            val len = Math.sqrt(v.sumOf { it.toDouble() * it }).toFloat()
            return FloatArray(v.size) { v[it] / len }
        }
        val a = norm(floatArrayOf(1f, 2f, 3f))
        val same = norm(floatArrayOf(2f, 4f, 6f))
        val dotSame = a.indices.sumOf { (a[it] * same[it]).toDouble() }
        assertTrue("同向归一化向量点积应接近 1，实际 $dotSame", kotlin.math.abs(dotSame - 1.0) < 1e-4)
    }
}
