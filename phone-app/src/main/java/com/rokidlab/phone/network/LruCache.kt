package com.rokidlab.phone.network

/**
 * 构造一个**有界、访问序**的 LRU 映射（超过 [capacity] 条时淘汰最久未访问的）。
 *
 * ## 抽出来是为了能被单测直接锁住
 * 审计发现 [MediaLoader] 的媒体缓存曾是**无界** `ConcurrentHashMap`（Drawable 持 Bitmap，
 * 随浏览单调增长、永不淘汰 ⇒ 长时间浏览必然 OOM），而同项目 `PainterBitmapCache`
 * 早就用 LRU 兜住了。**同一职责两套策略、只一套有上限** —— 两边现在统一走本函数。
 *
 * 📌 「有没有上限」在代码里就一行 `size > N`，review 时肉眼很难注意到；
 * 而它的缺失**只在长时间运行后显形**（用户反馈"逛久了应用崩了"），那时已无法回溯到这一行。
 * 故把容量策略提成可测的纯函数，`MediaLoaderCacheEvictionTest` 直接锁它 ——
 * 测的就是生产代码本身，不是它的复制品。
 *
 * ## 为什么是 `LinkedHashMap` + `accessOrder=true` 而不是 `ConcurrentHashMap`
 * 淘汰需要在**写**路径上判断容量，CHM 不支持；改用 `LinkedHashMap` 后
 * 读写必须靠调用方加锁（[MediaLoader] 统一用一把 `lock`）。
 * ⚠️ 淘汰**只丢引用**，绝不 `recycle()` 值里的 Bitmap ——
 * 它可能正被某个 Compose painter 持有并绘制中，recycle 会让那次绘制抛
 * "Canvas: trying to use a recycled bitmap"，等 GC 自然回收即可。
 *
 * @param capacity 上限条数（必须 > 0）
 */
internal fun <K, V> lruCache(capacity: Int): LinkedHashMap<K, V> {
    require(capacity > 0) { "LRU 容量必须为正，收到 $capacity" }
    return object : LinkedHashMap<K, V>(capacity, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<K, V>?): Boolean =
            size > capacity
    }
}
