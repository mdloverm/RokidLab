package com.rokidlab.phone.glasses

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * AiChannel 双端配置通道协议的单测。
 *
 * 锁定契约：
 *  1. 编码 = [cmd, schemaVersion, ...字段]，schemaVersion 恒为十进制字符串；
 *  2. v1 载荷 roundtrip 无损（mode 空串时回退 custom）；
 *  3. 未知版本（>当前）必须整体拒绝返回 null，防止旧端按错位偏移解析写入；
 *  4. v0 历史载荷（无版本字段）按旧偏移兼容解析；
 *  5. cmd 不符 / 字段缺失 → null。
 */
class AiChannelTest {

    // ── ai_config ──

    @Test
    fun `encodeAiConfig 格式为 cmd 版本 baseUrl apiKey model mode`() {
        val list = AiChannel.encodeAiConfig("https://api.deepseek.com", "sk-123", "deepseek-chat", "custom")
        assertEquals(listOf(
            AiChannel.CMD_AI_CONFIG,
            AiChannel.SCHEMA_VERSION.toString(),
            "https://api.deepseek.com",
            "sk-123",
            "deepseek-chat",
            "custom",
        ), list)
    }

    @Test
    fun `v1 ai_config roundtrip 无损`() {
        val encoded = AiChannel.encodeAiConfig("https://a.b", "key", "m1", AiChannel.AI_MODE_OFFICIAL)
        val cfg = AiChannel.decodeAiConfig(encoded)
        assertEquals(AiChannel.AiConfigFields("https://a.b", "key", "m1", AiChannel.AI_MODE_OFFICIAL), cfg)
    }

    @Test
    fun `v1 ai_config mode 为空时回退 custom`() {
        val cfg = AiChannel.decodeAiConfig(
            listOf(AiChannel.CMD_AI_CONFIG, "1", "https://a.b", "key", "m1", ""),
        )
        assertEquals(AiChannel.AiConfigFields("https://a.b", "key", "m1", AiChannel.AI_MODE_CUSTOM), cfg)
    }

    @Test
    fun `ai_config 未知 schema 版本返回 null 拒绝解析`() {
        val future = listOf(AiChannel.CMD_AI_CONFIG, "2", "https://a.b", "key", "m1", "official")
        assertNull("未来版本载荷必须整体丢弃，禁止按错位偏移解析", AiChannel.decodeAiConfig(future))
    }

    @Test
    fun `v0 ai_config 历史载荷按旧偏移兼容解析`() {
        // 历史版本手机端下发：[cmd, baseUrl, apiKey, model]，无版本字段
        val legacy = listOf(AiChannel.CMD_AI_CONFIG, "https://old.b", "old-key", "old-model")
        assertEquals(
            AiChannel.AiConfigFields("https://old.b", "old-key", "old-model", AiChannel.AI_MODE_CUSTOM),
            AiChannel.decodeAiConfig(legacy),
        )
    }

    @Test
    fun `v0 ai_config 携带 mode 时解析 mode`() {
        val legacy = listOf(AiChannel.CMD_AI_CONFIG, "https://old.b", "old-key", "old-model", "official")
        val cfg = AiChannel.decodeAiConfig(legacy)
        assertEquals(AiChannel.AI_MODE_OFFICIAL, cfg?.mode)
    }

    @Test
    fun `ai_config cmd 不符或字段缺失返回 null`() {
        assertNull(AiChannel.decodeAiConfig(listOf("other_cmd", "1", "url", "k", "m")))
        assertNull(AiChannel.decodeAiConfig(listOf(AiChannel.CMD_AI_CONFIG, "1", "url", "k"))) // 长度不足
        assertNull(AiChannel.decodeAiConfig(emptyList()))
        assertNull(AiChannel.decodeAiConfig(listOf(null, "1")))
        // v0 载荷长度不足 4
        assertNull(AiChannel.decodeAiConfig(listOf(AiChannel.CMD_AI_CONFIG, "url", "key")))
    }

    // ── key_config ──

    @Test
    fun `key_config v1 roundtrip 与 v0 兼容`() {
        val encoded = AiChannel.encodeKeyConfig("p1", "a1", "p2", "a2")
        assertEquals(listOf(AiChannel.CMD_KEY_CONFIG, "1", "p1", "a1", "p2", "a2"), encoded)
        assertEquals(
            AiChannel.KeyConfigFields("p1", "a1", "p2", "a2"),
            AiChannel.decodeKeyConfig(encoded),
        )
        // v0：[cmd, shortPkg, shortAct, longPkg, longAct]
        val legacy = listOf(AiChannel.CMD_KEY_CONFIG, "p1", "a1", "p2", "a2")
        assertEquals(
            AiChannel.KeyConfigFields("p1", "a1", "p2", "a2"),
            AiChannel.decodeKeyConfig(legacy),
        )
    }

    @Test
    fun `key_config 未知版本与非法载荷返回 null`() {
        assertNull(AiChannel.decodeKeyConfig(listOf(AiChannel.CMD_KEY_CONFIG, "2", "a", "b", "c", "d")))
        assertNull(AiChannel.decodeKeyConfig(listOf("wrong", "1", "a", "b", "c", "d")))
        assertNull(AiChannel.decodeKeyConfig(listOf(AiChannel.CMD_KEY_CONFIG, "1", "a", "b", "c"))) // 长度不足
    }

    // ── quiz_enabled ──

    @Test
    fun `quiz v1 与 v0 编码解析`() {
        assertEquals(listOf(AiChannel.CMD_QUIZ_ENABLED, "1", "true"), AiChannel.encodeQuizConfig(true))
        assertEquals(true, AiChannel.decodeQuizConfig(listOf(AiChannel.CMD_QUIZ_ENABLED, "1", "true")))
        assertEquals(false, AiChannel.decodeQuizConfig(listOf(AiChannel.CMD_QUIZ_ENABLED, "1", "false")))
        // v0：[cmd, "true"/"false"]
        assertEquals(true, AiChannel.decodeQuizConfig(listOf(AiChannel.CMD_QUIZ_ENABLED, "true")))
        assertEquals(false, AiChannel.decodeQuizConfig(listOf(AiChannel.CMD_QUIZ_ENABLED, "false")))
    }

    @Test
    fun `quiz 未知版本与非法载荷返回 null`() {
        assertNull(AiChannel.decodeQuizConfig(listOf(AiChannel.CMD_QUIZ_ENABLED, "2", "true")))
        assertNull(AiChannel.decodeQuizConfig(listOf(AiChannel.CMD_QUIZ_ENABLED, "1")))
        assertNull(AiChannel.decodeQuizConfig(listOf("wrong", "1", "true")))
        // v1 中 "TRUE" 等非标准写法不视为开启
        assertEquals(false, AiChannel.decodeQuizConfig(listOf(AiChannel.CMD_QUIZ_ENABLED, "1", "TRUE")))
    }

    // ── tts_play ──

    @Test
    fun `tts_play v1 编码为 cmd 版本 text`() {
        val encoded = AiChannel.encodeTtsPlay("你好，世界")
        assertEquals(listOf(AiChannel.CMD_TTS_PLAY, "1", "你好，世界"), encoded)
    }

    @Test
    fun `tts_play v1 roundtrip 无损`() {
        assertEquals("你好，世界", AiChannel.decodeTtsPlay(AiChannel.encodeTtsPlay("你好，世界")))
        // v1 明文载荷
        assertEquals("播报内容", AiChannel.decodeTtsPlay(listOf(AiChannel.CMD_TTS_PLAY, "1", "播报内容")))
    }

    @Test
    fun `tts_play v0 历史载荷按旧偏移兼容解析`() {
        // v0：[cmd, text]，无版本字段
        assertEquals("历史文本", AiChannel.decodeTtsPlay(listOf(AiChannel.CMD_TTS_PLAY, "历史文本")))
    }

    @Test
    fun `tts_play 纯数字文本歧义防护`() {
        // v0 载荷文本恰为 "1"（纯数字）：size==2 必须按 v0 文本解析，不得当作缺文本的 v1 丢弃
        assertEquals("1", AiChannel.decodeTtsPlay(listOf(AiChannel.CMD_TTS_PLAY, "1")))
        // v1 载荷：[cmd, version="1", text]
        assertEquals("2", AiChannel.decodeTtsPlay(listOf(AiChannel.CMD_TTS_PLAY, "1", "2")))
        // v0 文本为其他纯数字同样按 v0 解析
        assertEquals("42", AiChannel.decodeTtsPlay(listOf(AiChannel.CMD_TTS_PLAY, "42")))
    }

    @Test
    fun `tts_play 非法载荷与未知版本返回 null`() {
        // 未知未来版本
        assertNull(AiChannel.decodeTtsPlay(listOf(AiChannel.CMD_TTS_PLAY, "2", "text")))
        // v1 文本字段缺失（非字符串）
        assertNull(AiChannel.decodeTtsPlay(listOf(AiChannel.CMD_TTS_PLAY, "1", null)))
        // cmd 不符 / 长度不足
        assertNull(AiChannel.decodeTtsPlay(listOf("other", "1", "text")))
        assertNull(AiChannel.decodeTtsPlay(listOf(AiChannel.CMD_TTS_PLAY)))
        assertNull(AiChannel.decodeTtsPlay(emptyList()))
    }

    // ── 版本契约总检 ──

    @Test
    fun `当前 SCHEMA_VERSION 为 1 且双端命令值稳定`() {
        assertEquals(1, AiChannel.SCHEMA_VERSION)
        assertEquals("ai_config", AiChannel.CMD_AI_CONFIG)
        assertEquals("key_config", AiChannel.CMD_KEY_CONFIG)
        assertEquals("quiz_enabled", AiChannel.CMD_QUIZ_ENABLED)
        assertTrue(AiChannel.decodeAiConfig(AiChannel.encodeAiConfig("u", "k", "m", "custom")) != null)
    }
}
