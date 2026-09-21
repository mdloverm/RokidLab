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
    /** 推送通道远程踢活（手机端 → 眼镜端）：手机端检测到 RFCOMM 推送通道连续秒断、
     *  监听疑似死亡时下发，眼镜端收到后 stop+start 整个 AsrPushServer 释放蓝牙栈资源重建监听。
     *  走 CXR 自定义频道（系统 cxr-service 托管，独立于本推送通道，推送死了它仍可达）。 */
    const val TOPIC_PUSH_RESTART = "rokidlab_push_restart"

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

    // ── 连续对话（多轮免唤醒）开关（手机端 → 眼镜端）──
    // 开启后：眼镜端把 Lab 回复的本地 TTS 播完作为「一轮结束」信号，自动重新开启官方
    // ai_assist 会话（等价于再喊一次唤醒词），用户可直接接着说下一句。
    // 决策点放在眼镜端是刻意的 —— 只有它知道 TTS 的真实播放结束时刻（ITtsListener.onTtsStop）；
    // 手机端下发 tts_play 后拿不到播放进度，按文本长度估算会提前开麦而把播报尾音收进麦克风。
    const val TOPIC_CONTINUE_DIALOG = "rokidlab_chat_continue"
    /** 与 TOPIC_CONTINUE_DIALOG 呼应的载荷动作名（注意与同名的 topic 字符串分开维护） */
    const val CMD_CONTINUE_DIALOG = "continue_dialog"

    /** 编码 continue_dialog：[cmd, version, "true"/"false"] */
    fun encodeContinueDialog(enabled: Boolean): List<String> =
        listOf(CMD_CONTINUE_DIALOG, SCHEMA_VERSION.toString(), enabled.toString())

    /**
     * 解析 continue_dialog 载荷。
     * @return null = cmd 不符 / 长度不足 / 未知未来版本（接收端必须整体丢弃，保持原状态）
     */
    fun decodeContinueDialog(fields: List<String?>): Boolean? {
        if (fields.size < 2 || fields[0] != CMD_CONTINUE_DIALOG) return null
        return when (fields[1]?.toIntOrNull()) {
            SCHEMA_VERSION -> if (fields.size >= 3) fields[2] == "true" else null
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

    // ── IMU 头动数据通道（v1 查询层，双端同源）──
    // 眼镜端按手机指令启停（默认关）：~20Hz 采样加速度计 + 陀螺仪 + 游戏旋转向量，
    // 500ms 批量打包上行；手机端写入 MotionBuffer 环形缓冲（60s 窗口），
    // 供 get_head_pose / get_motion_history 工具与 v2 规则引擎消费。
    // 样本字段：t(毫秒 epoch) + 加速度 ax/ay/az(m/s²，含重力) + 角速度 gx/gy/gz(rad/s)
    // + 姿态四元数 qw/qx/qy/qz（游戏旋转向量，不含地磁 → 无绝对朝向，只有相对变化）。
    // float 以字符串承载：延续本文件「纯 Kotlin 编解码（List<String?>）」的可单测设计。
    const val TOPIC_IMU_DATA = "rokidlab_imu"
    const val CMD_IMU_DATA = "imu_data"
    /** 采集控制（手机端 → 眼镜端）：载荷 [imu_start | imu_stop, version] */
    const val TOPIC_IMU_CTRL = "rokidlab_imu_ctrl"
    const val CMD_IMU_START = "imu_start"
    const val CMD_IMU_STOP = "imu_stop"

    /** 单个 IMU 采样点（时间戳 + 3 轴加速度 + 3 轴角速度 + 姿态四元数） */
    data class ImuSample(
        val t: Long,
        val ax: Float, val ay: Float, val az: Float,
        val gx: Float, val gy: Float, val gz: Float,
        val qw: Float, val qx: Float, val qy: Float, val qz: Float,
    )

    /** 编码 imu_data：[cmd, version, sampleCount, 每样本 11 字段…] */
    fun encodeImuData(samples: List<ImuSample>): List<String> {
        val out = ArrayList<String>(3 + samples.size * 11)
        out.add(CMD_IMU_DATA)
        out.add(SCHEMA_VERSION.toString())
        out.add(samples.size.toString())
        for (s in samples) {
            out.add(s.t.toString())
            out.add(s.ax.toString()); out.add(s.ay.toString()); out.add(s.az.toString())
            out.add(s.gx.toString()); out.add(s.gy.toString()); out.add(s.gz.toString())
            out.add(s.qw.toString()); out.add(s.qx.toString()); out.add(s.qy.toString()); out.add(s.qz.toString())
        }
        return out
    }

    /**
     * 解析 imu_data 载荷。
     * @return null = cmd 不符 / 版本不受支持 / 字段数不符（接收端整体丢弃）；个别非数值样本跳过
     */
    fun decodeImuData(fields: List<String?>): List<ImuSample>? {
        if (fields.size < 3 || fields[0] != CMD_IMU_DATA) return null
        if (fields[1]?.toIntOrNull() != SCHEMA_VERSION) return null
        val count = fields[2]?.toIntOrNull() ?: return null
        if (count < 0 || fields.size < 3 + count * 11) return null
        val samples = ArrayList<ImuSample>(count)
        var i = 3
        fun fl(idx: Int) = fields[idx]?.toFloatOrNull()
        repeat(count) {
            val t = fields[i]?.toLongOrNull()
            val ax = fl(i + 1); val ay = fl(i + 2); val az = fl(i + 3)
            val gx = fl(i + 4); val gy = fl(i + 5); val gz = fl(i + 6)
            val qw = fl(i + 7); val qx = fl(i + 8); val qy = fl(i + 9); val qz = fl(i + 10)
            if (t != null && ax != null && ay != null && az != null &&
                gx != null && gy != null && gz != null &&
                qw != null && qx != null && qy != null && qz != null) {
                samples.add(ImuSample(t, ax, ay, az, gx, gy, gz, qw, qx, qy, qz))
            }
            i += 11
        }
        return samples
    }

    /** 编码 IMU 采集控制：[imu_start | imu_stop, version] */
    fun encodeImuControl(start: Boolean): List<String> =
        listOf(if (start) CMD_IMU_START else CMD_IMU_STOP, SCHEMA_VERSION.toString())

    /**
     * 解析 IMU 采集控制载荷。
     * @return true=开始采集 false=停止采集；null = cmd 不符 / 版本不受支持（忽略）
     */
    fun decodeImuControl(fields: List<String?>): Boolean? {
        if (fields.size < 2 || fields[1]?.toIntOrNull() != SCHEMA_VERSION) return null
        return when (fields[0]) {
            CMD_IMU_START -> true
            CMD_IMU_STOP -> false
            else -> null
        }
    }
}
