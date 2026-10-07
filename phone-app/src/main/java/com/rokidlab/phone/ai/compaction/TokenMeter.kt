package com.rokidlab.phone.ai.compaction

import com.knuddels.jtokkit.Encodings
import com.knuddels.jtokkit.api.EncodingType

/**
 * Token 计量器（2026-10-02 起替代「字符数 ≈ token」的经验换算）。
 *
 * 为什么是 cl100k_base：它是 OpenAI tiktoken 的通用编码，DeepSeek / GPT 系对中文的
 * 切分粒度与它同量级（常用汉字 ≈1 token/字），拿它做**预算口径**足够准 ——
 * 这里要的不是"和服务端账单分毫不差"，而是把历史预算从「字符」这个跟 token
 * 脱节的单位（英文 4 字符/token、中文 1 字/token，同一个 6000 字符的预算
 * 在两种语言下差 4 倍）换到模型真正消费的单位上。
 *
 * ★ 带界缓存的存在理由：`countTokens` 是 BPE 全量编码，对几 KB 文本要几毫秒；
 *   调用方有两个高频场景 —— 压缩检查每轮对话都跑、`contextUsage` 在消息列表每次
 *   变化时重算（Compose `remember` 主线程）。而历史是**追加为主**的：同一条消息
 *   在两次刷新之间内容不变，缓存命中后重复刷新几乎零开销。
 *   容量 256 条按访问顺序淘汰，内存上界 ≈ 256 × 单条消息（几 KB）≈ 1MB 量级。
 *
 * 线程安全：全部访问收口到本对象的锁上（编码调用也在锁内）—— jtokkit 的
 * Encoding 实例虽是无状态读，但统一串行化省得逐处论证；单次调用毫秒级，无争用风险。
 *
 * ⚠️ 为什么必须用 `newLazyEncodingRegistry()` 而不是 `newDefaultEncodingRegistry()`
 *   （2026-10-05，为配合 build.gradle.kts 排除未用编码表而改）：
 *   反编译 jtokkit 1.1.0 确认，DefaultEncodingRegistry 构造函数会调
 *   `initializeDefaultEncodings()`，**遍历 EncodingType 全部 5 个枚举值逐个
 *   computeIfAbsent 建表** —— 即「拿一个编码」也会把5 张表全部读进内存。
 *   而本项目只用 CL100K_BASE（见下），另外 4 张表（o200k/p50k/r50k …）是纯白带：
 *   实测 3 张表合计 5.04 MB raw（APK 内约 2.8 MB），已在
 *   `phone-app/build.gradle.kts` 的 `packaging.resources.excludes` 里排掉。
 *   ⚠️ **若改回 newDefaultEncodingRegistry()，那些表被排除后构造期就会抛
 *   资源缺失**（且 `count()` 的 runCatching 兜底会静默降级成「1 字 1 token」，
 *   预算口径悄悄失真却不报错 —— 那种坑比崩掉更难查）。改这一行前先看这段注释。
 */
internal object TokenMeter {

    /** 缓存容量：历史里活跃消息数远小于它（预算上限 120 条），足够覆盖整个工作集 */
    private const val CACHE_MAX = 256

    private val encoding by lazy {
        // lazy registry 只在 getEncoding 时才建表 ⇒ 实际只读 cl100k_base 一张
        Encodings.newLazyEncodingRegistry().getEncoding(EncodingType.CL100K_BASE)
    }

    private val cache = object : LinkedHashMap<String, Int>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Int>): Boolean =
            size > CACHE_MAX
    }

    /** 文本的 token 数；空串恒为 0 */
    fun count(text: String): Int {
        if (text.isEmpty()) return 0
        synchronized(cache) {
            cache[text]?.let { return it }
            // 兜底：首轮 encoding 懒加载时要读 cl100k_base 表资源，万一读不到
            // （表被误排除 / 资源损坏）就按 1 字 1 token 保守计——
            // 宁可早压（多压几轮）也不要低估导致整轮请求溢出 400
            val n = runCatching { encoding.countTokens(text) }.getOrDefault(text.length)
            cache[text] = n
            return n
        }
    }
}
