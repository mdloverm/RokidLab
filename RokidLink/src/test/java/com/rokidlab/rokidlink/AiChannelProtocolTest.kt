package com.rokidlab.rokidlink

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 眼镜端 AiChannel 协议冒烟测试。
 *
 * 说明：AiChannel.kt 在 phone-app 与 RokidLink 各持一份同源副本
 * （phone-app 的 preBuild 有 checkAiChannelSynced 守护，双端逐字一致），
 * 完整的协议契约单测在 phone-app 的 AiChannelTest（17 用例）。
 * 本文件只做眼镜端核心解析路径的冒烟锁定，避免测试重复维护；
 * 眼镜端自身纯逻辑（非同源代码）请在此追加测试。
 */
class AiChannelProtocolTest {

    @Test
    fun `v1 tts_play roundtrip`() {
        val encoded = AiChannel.encodeTtsPlay("眼镜本地播报")
        assertEquals(listOf(AiChannel.CMD_TTS_PLAY, "1", "眼镜本地播报"), encoded)
        assertEquals("眼镜本地播报", AiChannel.decodeTtsPlay(encoded))
    }

    @Test
    fun `v0 tts_play 历史文本与纯数字歧义兼容`() {
        // v0：[cmd, text]，无版本字段
        assertEquals("历史文本", AiChannel.decodeTtsPlay(listOf(AiChannel.CMD_TTS_PLAY, "历史文本")))
        // 文本恰为 "1"（size==2 恒按 v0 文本解析，不当作缺文本的 v1）
        assertEquals("1", AiChannel.decodeTtsPlay(listOf(AiChannel.CMD_TTS_PLAY, "1")))
    }

    @Test
    fun `非法或未知版本 tts_play 整体丢弃`() {
        assertNull(AiChannel.decodeTtsPlay(listOf(AiChannel.CMD_TTS_PLAY, "2", "text"))) // 未来版本
        assertNull(AiChannel.decodeTtsPlay(listOf(AiChannel.CMD_TTS_PLAY, "1", null))) // 文本缺失
        assertNull(AiChannel.decodeTtsPlay(listOf("other", "1", "text")))
        assertNull(AiChannel.decodeTtsPlay(emptyList()))
    }

    @Test
    fun `config 类通道 v1 与 v0 解析契约`() {
        assertEquals(
            AiChannel.AiConfigFields("https://a.b", "k", "m", AiChannel.AI_MODE_CUSTOM),
            AiChannel.decodeAiConfig(listOf(AiChannel.CMD_AI_CONFIG, "1", "https://a.b", "k", "m", "")),
        )
        // v0 key_config：[cmd, pkg...] 无版本字段
        assertEquals(
            AiChannel.KeyConfigFields("p1", "a1", "p2", "a2"),
            AiChannel.decodeKeyConfig(listOf(AiChannel.CMD_KEY_CONFIG, "p1", "a1", "p2", "a2")),
        )
        // v0 quiz：[cmd, "true"]
        assertEquals(true, AiChannel.decodeQuizConfig(listOf(AiChannel.CMD_QUIZ_ENABLED, "true")))
        // 未知版本全部拒绝
        assertNull(AiChannel.decodeAiConfig(listOf(AiChannel.CMD_AI_CONFIG, "9", "u", "k", "m", "c")))
    }

    @Test
    fun `v1 新增下行通道 roundtrip`() {
        // 眼镜端只需能解析出手机端下发的图片/跳转指令；边界矩阵在 phone-app 的 AiChannelTest
        assertEquals("192.168.1.23", AiChannel.decodeGlassesIp(AiChannel.encodeGlassesIp("192.168.1.23")))
        assertEquals(
            "BASE64JPEG" to "一只猫",
            AiChannel.decodeShowImage(AiChannel.encodeShowImage("BASE64JPEG", "一只猫")),
        )
        assertEquals(
            "com.rokid.os.sprite.launcher" to ".page.music.MusicPageActivity",
            AiChannel.decodeOpenApp(AiChannel.encodeOpenApp("com.rokid.os.sprite.launcher", ".page.music.MusicPageActivity")),
        )
        // 未知版本一律整体丢弃
        assertNull(AiChannel.decodeOpenApp(listOf(AiChannel.CMD_OPEN_APP, "9", "p", "a")))
        assertNull(AiChannel.decodeShowImage(listOf(AiChannel.CMD_SHOW_IMAGE, "9", "x", "c")))
    }
}
