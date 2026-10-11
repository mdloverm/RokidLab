package com.rokidlab.phone.proactive

/**
 * 消息摘要的**纯判定层**：通知本身是第三方 App 写进来的自由文本，所以手机端只做
 * 「这条要不要收 / 收哪部分」的机械过滤与聚合，**说什么一律交给模型**——
 * 与 [CareReminder] 同一分工（端上只提特征，不预判话术）。
 *
 * 全部为纯函数 / 无 Android 依赖，便于 JVM 单测（见 `NotificationDigestPolicyTest`）。
 */
object NotificationDigestPolicy {

    /**
     * 验证码/密码类通知的识别词。
     *
     * ⚠️ **两个坑，都是实测出来的**：
     *  1. `\b`（词边界）在中文里**永不命中** —— Java 的 `\w` 默认只含 `[a-zA-Z_0-9]`，
     *     「支付密码」四个字全是**非**词字符，两侧都没有边界，所以 `\b密码\b` 是死代码，
     *     写完看着有保护、实际一条都拦不住。中文模式一律**不加** `\b`。
     *  2. 光匹配「密码」二字会把正常对话误杀（"我明天改密码，到时候发你"），
     *     而漏判的代价（念出验证码）远大于误杀。判据落在「**后面跟着值**」上：
     *     敏感 = 关键词 + 分隔 + 4~8 位数字。单纯的"提到密码"不算。
     *
     * 分隔符覆盖 `是/为/:：/空格`，实测各家 App 的写法差异很大（"验证码是1234"、"验证码：1234"、
     * "验证码 1234" 都见过），少一个就漏判。
     */
    private val SENSITIVE_PATTERNS = listOf(
        Regex("(验证码|校验码|动态码|短信码)\\s*(是|为|:|：)?\\s*\\d{4,8}"),
        Regex("\\b(otp|verification\\s*code|verify\\s*code|one[-\\s]?time\\s*(code|password)|2fa\\s*code)\\b\\s*(is|:|：)?\\s*\\d{4,8}", RegexOption.IGNORE_CASE),
        Regex("(密码|口令)\\s*(是|为|:|：)?\\s*\\d{4,8}"),
        Regex("\\b(cvv|cvc|security\\s*code)\\b\\s*(is|:|：)?\\s*\\d{3,4}", RegexOption.IGNORE_CASE),
    )

    /**
     * 金融/医疗类**包名**片段。
     *
     * ⚠️ 这一条只能当**快速通道**，不能当保证：包名不可枚举 ——
     * 工行是 `com.icbc`、招行是 `cmbchina`、建行是 `ccb`，都不含 "bank"；
     * 外国的更没法猜。所以真正的兜底是下面的 [FINANCE_CONTENT_PATTERNS]（按**正文**判），
     * 这一条只是让已知支付类 App 连正文都不用看就丢掉。
     */
    private val HIGH_SENSITIVITY_PKG_HINTS = listOf(
        "alipay", "unionpay", "tenpay", "paypal", "wallet", "payment",
    )

    /**
     * 金融交易类**正文**关键词（真正的兜底）：交易/转账/扣款/到账/账单等。
     *
     * 单独列出来是因为包名靠不住（见上），而交易类通知的共性在**正文**里很稳定。
     * 注意只收"交易事实"类词，**不收**「账单」这种在日常对话里也常用的歧义词 ——
     * 误杀一条"账单寄到了"没有价值，漏判一条扣款播报才有。
     */
    private val FINANCE_CONTENT_PATTERNS = listOf(
        Regex("(交易|转账|汇款|扣款|扣费|到账|还款|提现|退款)[^。！？]{0,12}\\d"),
    )

    /**
     * 医疗健康类**正文**关键词：体检/就诊/化验/诊断等。
     *
     * 与金融同理按正文判（包名同样不可枚举）。医疗通知一律不进摘要 ——
     * 「你体检查出点什么」这种事不该由眼镜主动念出来，该由用户自己看 App。
     */
    private val MEDICAL_CONTENT_PATTERNS = listOf(
        Regex("(体检|就诊|化验|检查报告|诊断|处方|住院|挂号)"),
    )

    /** 一条通知正文最多取多少字进 prompt（宁缺勿滥，且防塞爆上下文） */
    const val MAX_TEXT_CHARS = 80

    /** 单次摘要最多覆盖多少条通知 */
    const val MAX_BATCH = 12

    /**
     * 这条通知该不该进入摘要缓冲。
     *
     * 过滤顺序是**安全优先**：先判敏感（宁可不摘要，也不该把验证码念出来），
     * 再判自身/系统噪声，最后才判内容是否可用 —— 顺序反了会让高敏内容
     * 先经过「有内容」这一关才被拦，白做一次字符串处理。
     *
     * @param pkg 通知所属包名（本 App 自身传自身包名）
     * @param isOngoing 进度条类常驻通知（下载/上传/同步），不是"消息"
     * @param isGroupSummary 分组折叠摘要行，与子通知重复
     * @param text 已 join 好的正文（标题 + 文本）
     */
    fun shouldIgnore(
        pkg: String,
        isOngoing: Boolean,
        isGroupSummary: Boolean,
        text: String,
        selfPackage: String,
    ): IgnoreReason? {
        if (pkg == selfPackage) return IgnoreReason.SELF
        if (pkg == "android" || pkg.startsWith("com.android.")) return IgnoreReason.SYSTEM
        if (isGroupSummary) return IgnoreReason.GROUP_SUMMARY
        if (isOngoing) return IgnoreReason.ONGOING
        if (isSensitive(pkg, text)) return IgnoreReason.SENSITIVE
        if (sanitize(text).isEmpty()) return IgnoreReason.EMPTY
        return null
    }

    /**
     * 高敏内容识别：命中任一即整条丢弃（不做"脱敏后保留"——半截信息更容易误导）。
     *
     * 判据顺序：**正文关键词 → 包名快速通道**。正文在前是因为包名枚举不可靠
     * （见 [HIGH_SENSITIVITY_PKG_HINTS]），真正兜底的是内容。
     */
    fun isSensitive(pkg: String, text: String): Boolean {
        if (SENSITIVE_PATTERNS.any { it.containsMatchIn(text) }) return true
        if (FINANCE_CONTENT_PATTERNS.any { it.containsMatchIn(text) }) return true
        if (MEDICAL_CONTENT_PATTERNS.any { it.containsMatchIn(text) }) return true
        val p = pkg.lowercase()
        return HIGH_SENSITIVITY_PKG_HINTS.any { p.contains(it) }
    }

    /**
     * 清洗通知正文：剥控制字符（防注入换行伪造指令行）+ 截断 + 压空白。
     *
     * ⚠️ 清洗**不能**当防线用 —— 真正的防线是 [com.rokidlab.phone.ai.UntrustedContent]
     * 的结构隔离。这里只保证不破坏 prompt 结构（一个换行就能伪造出"指令行"）。
     */
    fun sanitize(raw: String): String =
        raw.replace(Regex("\\p{Cntrl}"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
            .take(MAX_TEXT_CHARS)

    /**
     * 该不该在这次自检里开口播报这一批。
     *
     * 只有 1 条时**不播报**：单条通知的"急迫性"几乎全是噪声（验证码、广告、系统更新），
     * 而开口一次的信任成本很高 —— 攒到 [MIN_BATCH_TO_SPEAK] 条说明这期间确实有事，
     * 播报"你收到了 3 条消息"才有信息量。
     */
    fun shouldSpeak(count: Int): Boolean = count >= MIN_BATCH_TO_SPEAK

    /** 攒够几条才值得开口（见 [shouldSpeak]） */
    const val MIN_BATCH_TO_SPEAK = 2

    /**
     * 应用白名单过滤（Muse 式「只听指定 App」）：白名单为空 = 收全部（默认），
     * 非空 = 只收集合内的包名。
     *
     * ⚠️ 空集必须解释为「收全部」而不是「一个都不收」—— 白名单语义是**许可扩展**
     * 而非屏蔽，否则用户没配置过时默认行为就静默变了（一条都收不到还找不到原因）。
     */
    fun passesWhitelist(pkg: String, whitelist: Set<String>): Boolean =
        whitelist.isEmpty() || pkg in whitelist

    /**
     * 一条通知在缓冲里最多能待多久（毫秒），超过即**丢弃、不再播报**。
     *
     * ## 为什么必须有这条（2026-10-11 补）
     * 冻结的实证场景：23:30 收到 2 条微信 → 进缓冲；00:00 自检被免打扰拦下 → **保留**；
     * 次日 08:00 后第一次自检 → 播报「你收到 2 条消息」——那是 **8.5 小时前**的事。
     *
     * 事件驱动的**全部价值在时效性**（当时发生、当时提）。没有上限的缓冲会把它退化成
     * 「延迟很久的定时器」—— 定时器的缺点（时机不对）和事件驱动的成本都占，却拿不到好处。
     *
     * ## 取值理由：2 × 自检间隔（30 分钟）= 60 分钟
     * 正常情况事件最多滞后一个自检周期。给到两倍是为了**容忍被拦一次**
     * （冷却期/刚好跨免打扰边界），下一轮还有机会说；再往后就不值得说了。
     *
     * ⚠️ **跨夜的通知会被整体丢弃，这是刻意的**：半夜的消息早上再念是打扰 ——
     * 用户醒来第一件事就是看手机，早就知道了。「宁可不说，也不说陈旧的事」。
     */
    const val MAX_AGE_MS = 60 * 60 * 1000L

    /**
     * 这条通知是否已经过期（收集于 [collectedAtMs]，现在 [nowMs]）。
     *
     * 抽成纯函数以便单测 —— 「不说过期的事」是事件驱动体感的核心，必须有测试盯着。
     */
    fun isStale(
        collectedAtMs: Long,
        nowMs: Long,
        maxAgeMs: Long = MAX_AGE_MS,
    ): Boolean = nowMs - collectedAtMs > maxAgeMs

    /** 摘要里一条通知的展示形态：`（微信）张三：今晚一起吃饭吗` */
    fun formatLine(appLabel: String, text: String): String {
        val clean = sanitize(text)
        val label = appLabel.trim().ifEmpty { "某个 App" }
        return if (clean.isEmpty()) "· $label 来了一条消息" else "· $label：$clean"
    }

    /** 丢弃原因（写日志用；不面向用户） */
    enum class IgnoreReason {
        /** 本 App 自己发的通知 —— 不排除会与自己触发的主动轮形成回环 */
        SELF,

        /** 系统通知（`android` / `com.android.*`） */
        SYSTEM,

        /** 分组折叠摘要行，与子通知内容重复 */
        GROUP_SUMMARY,

        /** 进度条类常驻通知，不是"消息" */
        ONGOING,

        /** 高敏内容（验证码 / 密码 / 金融医疗） */
        SENSITIVE,

        /** 清洗后没有有效内容 */
        EMPTY,
    }
}