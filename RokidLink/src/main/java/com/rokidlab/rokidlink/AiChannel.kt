package com.rokidlab.rokidlink

/**
 * Lab(手机端) ↔ RokidLink(眼镜端) 配置通道协议（双端同源副本，必须同步修改）。
 *
 * 版本化背景：此前 ai_config / key_config / quiz_enabled 三类配置走 CXR 自定义指令，
 * Caps 载荷按「位置顺序」解析且无版本号——任一端将来增删字段都会让另一端错位读取
 * （把本应是 baseUrl 的字段写进 model、把 mode 当 apiKey 等），且无法察觉。
 *
 * 本协议收敛规则：
 *  - 载荷第一字段固定为 cmd（动作名），第二字段固定为 schemaVersion（十进制字符串）；
 *  - 接收端先校验 cmd，再校验版本：未知版本(>当前)直接返回 null 丢弃并告警，
 *    绝不按错位偏移解析写入 prefs；
 *  - v0 旧载荷（历史版本手机端下发，无版本字段）按历史偏移兼容解析。
 *
 * 本文件只含纯 Kotlin 编解码（List<String?>），不依赖 Caps/SDK/Android，
 * 双端各自用薄适配层把 Caps 转 List 后调用，保证解析逻辑可单测且两端一致。
 */
object AiChannel {
    /** 载荷 schema 版本：任何字段结构变化都必须 +1 并同步双端；旧端对未知新版本返回 null */
    const val SCHEMA_VERSION = 1

    // ── 配置类通道 topic（sendCustomCmd / subscribe 通道名）──
    const val TOPIC_AI_CONFIG = "rokidlab_ai_config"
    /** 下行存活探测（手机端 → 眼镜端）：手机端连接期间每 60s 下发空消息，RokidLink 收到即证明
     *  cxr-service 到本 App 的分发路由健康（断线重连后路由可能 stale 丢失，依赖此探测自愈）。 */
    const val TOPIC_PING = "rokidlab_ping"
    const val TOPIC_KEY_CONFIG = "rokidlab_key_config"
    const val TOPIC_KEY_QUIZ = "rokidlab_key_quiz"
    const val TOPIC_TTS_PLAY = "tts_play"
    const val TOPIC_TTS_STOP = "tts_stop"

    // ── cmd（载荷[0]，与 topic 呼应）──
    const val CMD_AI_CONFIG = "ai_config"
    const val CMD_KEY_CONFIG = "key_config"
    const val CMD_QUIZ_ENABLED = "quiz_enabled"
    const val CMD_TTS_PLAY = "tts_play"
    const val CMD_TTS_STOP = "tts_stop"

    // ── AI 对话模式取值（契约值，双端一致）──
    const val AI_MODE_OFFICIAL = "official"
    const val AI_MODE_CUSTOM = "custom"

    /** ai_config 载荷：手机端 → 眼镜端，眼镜端持久化后本地直调模型 */
    data class AiConfigFields(
        val baseUrl: String,
        val apiKey: String,
        val model: String,
        val mode: String,
    )

    /** key_config 载荷：短按/长按 按键动作映射（包名 + Activity） */
    data class KeyConfigFields(
        val shortPkg: String,
        val shortActivity: String,
        val longPkg: String,
        val longActivity: String,
    )

    /** 编码 ai_config：[cmd, version, baseUrl, apiKey, model, mode] */
    fun encodeAiConfig(baseUrl: String, apiKey: String, model: String, mode: String): List<String> =
        listOf(CMD_AI_CONFIG, SCHEMA_VERSION.toString(), baseUrl, apiKey, model, mode)

    /** 编码 key_config：[cmd, version, shortPkg, shortAct, longPkg, longAct] */
    fun encodeKeyConfig(
        shortPkg: String,
        shortActivity: String,
        longPkg: String,
        longActivity: String,
    ): List<String> =
        listOf(CMD_KEY_CONFIG, SCHEMA_VERSION.toString(), shortPkg, shortActivity, longPkg, longActivity)

    /** 编码 quiz_enabled：[cmd, version, "true"/"false"] */
    fun encodeQuizConfig(enabled: Boolean): List<String> =
        listOf(CMD_QUIZ_ENABLED, SCHEMA_VERSION.toString(), enabled.toString())

    /**
     * 解析 ai_config 载荷。
     * @return null = cmd 不符 / 长度不足 / 版本不受支持（接收端必须整体丢弃，禁止部分写入）
     */
    fun decodeAiConfig(fields: List<String?>): AiConfigFields? {
        if (fields.size < 2 || fields[0] != CMD_AI_CONFIG) return null
        return when (val v = fields[1]?.toIntOrNull()) {
            SCHEMA_VERSION -> {
                if (fields.size < 6) return null
                val f = fields.map { it.orEmpty() }
                AiConfigFields(f[2], f[3], f[4], f[5].ifBlank { AI_MODE_CUSTOM })
            }
            // v0 历史载荷：[cmd, baseUrl, apiKey, model, (mode)]，mode 缺省 custom
            null -> {
                if (fields.size < 4) return null
                val f = fields.map { it.orEmpty() }
                AiConfigFields(
                    f[1], f[2], f[3],
                    if (fields.size >= 5 && f[4].isNotBlank()) f[4] else AI_MODE_CUSTOM,
                )
            }
            else -> null // 未来版本：显式拒绝，防止错位解析
        }
    }

    /** 解析 key_config 载荷（v1：[cmd, version, shortPkg, shortAct, longPkg, longAct]；v0 无 version） */
    fun decodeKeyConfig(fields: List<String?>): KeyConfigFields? {
        if (fields.size < 2 || fields[0] != CMD_KEY_CONFIG) return null
        return when (val v = fields[1]?.toIntOrNull()) {
            SCHEMA_VERSION -> {
                if (fields.size < 6) return null
                val f = fields.map { it.orEmpty() }
                KeyConfigFields(f[2], f[3], f[4], f[5])
            }
            null -> {
                if (fields.size < 5) return null
                val f = fields.map { it.orEmpty() }
                KeyConfigFields(f[1], f[2], f[3], f[4])
            }
            else -> null
        }
    }

    /**
     * 编码 tts_play（手机端 → 眼镜端本地 TTS 播报）：[cmd, version, text]
     * 注意：v0 载荷为 [cmd, text]，text 是自由文本。为避免 v1 的 version 字段
     * 与纯数字文本（如播报"1"）歧义，v1 编码恒为 3 字段，
     * 解码规则见 decodeTtsPlay。
     */
    fun encodeTtsPlay(text: String): List<String> =
        listOf(CMD_TTS_PLAY, SCHEMA_VERSION.toString(), text)

    /**
     * 解析 tts_play 载荷，返回待播报文本。
     * 歧义规则：v1 载荷必为 3 字段，故 size==2 一律按 v0 文本解析
     * （历史任意文本均可播报，含纯数字"1"）；size>=3 时才按版本号判定。
     * @return null = cmd 不符 / 长度不足 / 文本字段缺失 / 未知未来版本（整体丢弃）
     */
    fun decodeTtsPlay(fields: List<String?>): String? {
        if (fields.size < 2 || fields[0] != CMD_TTS_PLAY) return null
        return when {
            // v0：[cmd, text]，文本可为任意字符串（含纯数字，如播报"1"）
            fields.size == 2 -> fields[1]
            // v1：[cmd, version, text]，文本必须为字符串
            fields[1]?.toIntOrNull() == SCHEMA_VERSION && fields[2] != null -> fields[2]
            // 文本字段缺失（非字符串）或未知未来版本：显式拒绝，防止错位解析
            else -> null
        }
    }

    /** 解析 quiz_enabled 载荷。
     * @return null = 载荷非法/版本不支持；否则返回开关状态。
     */
    fun decodeQuizConfig(fields: List<String?>): Boolean? {
        if (fields.size < 2 || fields[0] != CMD_QUIZ_ENABLED) return null
        return when (val v = fields[1]?.toIntOrNull()) {
            SCHEMA_VERSION -> if (fields.size >= 3) fields[2] == "true" else null
            null -> fields[1] == "true" // v0：[cmd, enabled]
            else -> null
        }
    }
}
