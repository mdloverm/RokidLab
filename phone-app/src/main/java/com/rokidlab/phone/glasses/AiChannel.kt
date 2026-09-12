package com.rokidlab.phone.glasses

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

    // ── 眼镜 WiFi IP 上行通道（眼镜端 → 手机端）──
    // 眼镜连上 WiFi 后自动上报自身 IP，手机端据此免手动输入，
    // 自动填充到投屏 / 手机镜像 / 文件管理 / ADB 工具共用的单一数据源。
    const val TOPIC_GLASSES_IP = "rokidlab_glasses_ip"
    const val CMD_GLASSES_IP = "glasses_ip"

    /** 编码 glasses_ip 载荷：[cmd, version, ip] */
    fun encodeGlassesIp(ip: String): List<String> =
        listOf(CMD_GLASSES_IP, SCHEMA_VERSION.toString(), ip)

    /**
     * 解析 glasses_ip 载荷。
     * @return null = 载荷非法/版本不支持；否则返回眼镜 WiFi IPv4 地址。
     */
    fun decodeGlassesIp(fields: List<String?>): String? {
        if (fields.size < 2 || fields[0] != CMD_GLASSES_IP) return null
        return when (val v = fields[1]?.toIntOrNull()) {
            SCHEMA_VERSION -> if (fields.size >= 3 && !fields[2].isNullOrBlank()) fields[2] else null
            else -> null
        }
    }

    // ── 停止手机投屏指令（手机端 → 眼镜端）──
    // 手机端按「停止投屏」时下发，眼镜端据此关闭 PhoneMirrorActivity：
    // 该页面设计为 socket 断开后保持前台等待重连（避免重连后画面更新在后台不可见），
    // 故停止投屏必须显式下发关闭指令，否则最后一帧画面会残留在眼镜上。
    // 历史实现靠 stopApp 整包杀 RokidLink，会与前台自动保活冲突（被立刻重新拉起），已废弃。
    // 无载荷：收到即关。
    const val TOPIC_STOP_PHONE_MIRROR = "rokidlab_stop_phone_mirror"
    const val CMD_STOP_PHONE_MIRROR = "stop_phone_mirror"

    // ── 图片下发（手机端 → 眼镜端）──
    // 手机端对话气泡里的图片（show_image 工具展示的图 / 当前播放歌曲封面）同步显示到眼镜端
    // 悬浮图片层。载荷直接带 Base64 JPEG，不依赖眼镜端自身上网去下载 URL
    // （眼镜端网络能力不保证，投屏/ASR 等都是手机端供数据）。
    // 体积：手机端已压到长边 ≤480px / JPEG q80，Base64 后约 50~150KB，远低于 Binder 单事务上限。
    const val TOPIC_SHOW_IMAGE = "rokidlab_show_image"
    const val CMD_SHOW_IMAGE = "show_image"

    /** 编码 show_image：[cmd, version, base64Jpeg, caption] */
    fun encodeShowImage(base64Jpeg: String, caption: String): List<String> =
        listOf(CMD_SHOW_IMAGE, SCHEMA_VERSION.toString(), base64Jpeg, caption)

    /**
     * 解析 show_image 载荷。
     * @return (base64Jpeg, caption)；null = cmd 不符 / 版本不受支持 / 图片数据缺失（接收端整体丢弃）
     */
    fun decodeShowImage(fields: List<String?>): Pair<String, String>? {
        if (fields.size < 3 || fields[0] != CMD_SHOW_IMAGE) return null
        if (fields[1]?.toIntOrNull() != SCHEMA_VERSION) return null
        val b64 = fields[2]?.takeIf { it.isNotBlank() } ?: return null
        return b64 to fields.getOrNull(3).orEmpty()
    }

    // ── 打开页面指令（手机端 → 眼镜端）──
    // 手机端需要把眼镜上某个 Activity 拉到前台时下发（典型：说「显示歌词」→ 拉起系统音乐页
    // com.rokid.os.sprite.launcher/.page.music.MusicPageActivity）。
    // 眼镜端收到后直接 startActivity(ComponentName(pkg, activity))；目标 Activity 必须 exported=true。
    // 纯信令、无状态：收到即拉起，不做去重（重复拉起由目标 Activity 的 launchMode 兜底）。
    const val TOPIC_OPEN_APP = "rokidlab_open_app"
    const val CMD_OPEN_APP = "open_app"

    /** 编码 open_app：[cmd, version, pkg, activity]（activity 为全限定类名） */
    fun encodeOpenApp(pkg: String, activity: String): List<String> =
        listOf(CMD_OPEN_APP, SCHEMA_VERSION.toString(), pkg, activity)

    /**
     * 解析 open_app 载荷。
     * @return (pkg, activity)；null = cmd 不符 / 版本不受支持 / 包名或 Activity 缺失（接收端整体丢弃）
     */
    fun decodeOpenApp(fields: List<String?>): Pair<String, String>? {
        if (fields.size < 4 || fields[0] != CMD_OPEN_APP) return null
        if (fields[1]?.toIntOrNull() != SCHEMA_VERSION) return null
        val pkg = fields[2]?.takeIf { it.isNotBlank() } ?: return null
        val activity = fields[3]?.takeIf { it.isNotBlank() } ?: return null
        return pkg to activity
    }
}
