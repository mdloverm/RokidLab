package com.rokidlab.phone.ai.llm

import android.content.Context
import android.util.Log

/**
 * 能力探测结果的落盘缓存（`llm` 接缝的记忆）。
 *
 * 为什么必须持久化：探测一次 = 一次真实计费请求。不缓存的话，用户每进一次设置页、
 * 或（更糟）每拍一张照都会重探一次。
 *
 * 键 = `baseUrl|model`（见 [ModelRoute.keyOf]），**故意不带服务商名**：
 * 换服务商但模型名相同（比如从自建代理切到官方）本就应该重新探。
 *
 * ⚠️ 缓存的是**结论**而不是"探测过了"：值为 `false` 同样要落盘 ——
 * "确认不支持"和"尚未探测"必须能区分开，否则每次打开设置页都要重探一遍否定结论。
 */
internal object CapabilityCache {
    private const val TAG = "LlmCapCache"
    private const val PREFS = "llm_capability"
    private const val KEY_IMAGE_PREFIX = "img_"

    /**
     * 结论有效期。
     *
     * 取 7 天而不是永久：服务商会在模型名不变的情况下**换掉背后的模型**
     * （`gpt-4o` 指向的版本、聚合站把 `deepseek-chat` 改接到别家），
     * 一个墓碑式的永久否定结论会让用户永久失去一个能力，且无从自查。
     * 过期后回落到内置表/未知（=允许尝试），不会突然把能力锁死。
     */
    private const val TTL_MS = 7L * 24 * 60 * 60 * 1000

    data class Entry(val image: Boolean, val atMs: Long)

    private fun prefs(ctx: Context) =
        ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun read(ctx: Context?, baseUrl: String, model: String): Entry? {
        if (ctx == null) return null
        val raw = runCatching { prefs(ctx).getString(key(baseUrl, model), null) }.getOrNull() ?: return null
        // 格式 "1|1699999999999"（紧凑，避免为一条布尔值引入 JSON）
        val parts = raw.split('|')
        if (parts.size != 2) return null
        val image = parts[0] == "1"
        val at = parts[1].toLongOrNull() ?: return null
        if (System.currentTimeMillis() - at > TTL_MS) {
            Log.i(TAG, "read: expired entry for $model, ignoring")
            return null
        }
        return Entry(image, at)
    }

    fun write(ctx: Context?, baseUrl: String, model: String, image: Boolean) {
        if (ctx == null) return
        runCatching {
            prefs(ctx).edit()
                .putString(key(baseUrl, model), (if (image) "1" else "0") + "|" + System.currentTimeMillis())
                .apply()
            Log.i(TAG, "write: image=$image model=$model")
        }
    }

    /**
     * 失效一个模型的能力结论（用户点「检测」时先清再探）。
     *
     * 为什么"检测"要先清：见 [TTL_MS] 的注释 —— 检测的意义正是"我怀疑旧结论已经不对了"，
     * 不清掉的话 7 天内的旧结论会盖住新探测结果。
     */
    fun clear(ctx: Context?, baseUrl: String, model: String) {
        if (ctx == null) return
        runCatching { prefs(ctx).edit().remove(key(baseUrl, model)).apply() }
    }

    /**
     * prefs 键 = 前缀 + `baseUrl|model` **原文**。
     *
     * 刻意不做 hash/截断：这里存的是"某个端点的某个模型支不支持看图"的**事实**，
     * 一旦两个不同的端点/模型撞到同一个键，就会把 A 的结论当成 B 的 ——
     * 这类错误表现为"莫名其妙地模型不支持图像"，而排查时看键名又完全正常。
     * SharedPreferences 的键可以是任意字符串，没有理由为省几个字节引入碰撞风险。
     */
    private fun key(baseUrl: String, model: String): String =
        KEY_IMAGE_PREFIX + ModelRoute.keyOf(baseUrl, model)
}
