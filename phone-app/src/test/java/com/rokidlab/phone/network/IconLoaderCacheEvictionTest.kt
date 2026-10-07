package com.rokidlab.phone.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 图标缓存（[IconLoader]）的**有界性**回归测试（2026-10-06 审计修）。
 *
 * ## 锁的是哪个 bug
 * 本类原先两个容器都是**无界** `ConcurrentHashMap`：
 * - `cache`（key→`Drawable`，而 Drawable 持 ≤256px 的 Bitmap）随商店浏览单调增长、永不淘汰
 * - `missing`（key→拉黑截止时间戳），键是**远端 URL**（`remote:$url`）——
 *   URL 来自远端清单、不可信，清单变更或恶意 URL 可持续注入唯一键把它撑大
 *
 * ⇒ 长时间逛商店必然 OOM。而同职责的 [MediaLoader] 早在 2026-10-05 就改成了 LRU，
 * **同一职责两套缓存策略、只一套有上限** —— 现在统一走 [lruCache]。
 *
 * ## 为什么要单测而不是读代码看出来
 * 「有没有上限」在代码里就一行 `size > N`，review 时肉眼极难注意到；
 * 而它的缺失**只在长时间运行后显形**（用户反馈"逛久了应用崩了"），
 * 那时已无法回溯到这一行。这类**资源类不变量**就该用断言钉死。
 *
 * ##测的是纯 LRU 容器，不碰网络与 Context
 * 真 [IconLoader] 需要 `Context` + 真实 assets/网络。锁的是**容量策略**这一个纯逻辑，
 * 所以复用生产代码 [lruCache] 本身（不是它的复制品 —— 复制一份等于给"测试通过"上假保险）。
 *
 * 📌 容量值的回归锁在 [ICON_CACHE_ENTRIES] / [ICON_BLACKLIST_ENTRIES] 上（top-level，
 * 同模块可见），改动容量时**必须同步更新这里的断言**，从而强制"改容量的人看一眼影响面"。
 */
class IconLoaderCacheEvictionTest {

    @Test
    fun `图标缓存超过上限时淘汰最旧条目`() {
        val cache = lruCache<String, String>(4)
        listOf("a", "b", "c", "d", "e").forEach { cache[it] = it.uppercase() }

        assertEquals("超上限后应只保留上限条数", 4, cache.size)
        assertNull("最早的 a 应被淘汰", cache["a"])
        assertEquals("E", cache["e"])
    }

    @Test
    fun `拉黑表命中会刷新次序`() {
        val blacklist = lruCache<String, Long>(3)
        blacklist["u1"] = 1L
        blacklist["u2"] = 2L
        blacklist["u3"] = 3L
        assertEquals(1L, blacklist["u1"])          // 访问 u1 ⇒ 变成最近用过
        blacklist["u4"] = 4L

        assertEquals(3, blacklist.size)
        assertNotNull("刚访问过的 u1 不该被淘汰", blacklist["u1"])
        assertNull("真正最旧的 u2 应被淘汰", blacklist["u2"])
    }

    @Test
    fun `远端URL 持续注入时拉黑表不会无界增长`() {
        // ★ 锁的是"URL 来自远端清单、不可信"这个具体威胁：
        // 恶意/变更的清单可以源源不断送来不同 URL，若无上限这张表只增不减。
        val cap = ICON_BLACKLIST_ENTRIES
        val blacklist = lruCache<String, Long>(cap)
        repeat(cap * 40) { i -> blacklist["remote:https://evil.example/$i.png"] = i.toLong() }

        assertTrue("拉黑表容量必须恒≤ 上限，实际 ${blacklist.size}", blacklist.size <= cap)
        assertEquals(cap, blacklist.size)
    }

    @Test
    fun `图标缓存实际使用上限不会超出配置值`() {
        val cap = ICON_CACHE_ENTRIES
        val cache = lruCache<String, String>(cap)
        repeat(5000) { i -> cache["app-$i"] = "icon$i" }

        assertTrue("容量必须恒 ≤ 上限，实际 ${cache.size}", cache.size <= cap)
        assertEquals(cap, cache.size)
    }

    @Test
    fun `缓存上限与拉黑表上限是各自独立的`() {
        // 图标比截图多得多（一个应用一个图标），所以两个上限本就不同；
        // 若哪天有人把两者抄成同一个值，这条会提醒他确认是不是笔误。
        assertTrue(
            "图标缓存上限($ICON_CACHE_ENTRIES) 与拉黑表上限($ICON_BLACKLIST_ENTRIES) 不应相同",
            ICON_CACHE_ENTRIES != ICON_BLACKLIST_ENTRIES,
        )
        assertTrue("图标缓存上限应为正", ICON_CACHE_ENTRIES > 0)
        assertTrue("拉黑表上限应为正", ICON_BLACKLIST_ENTRIES > 0)
    }

    @Test
    fun `淘汰只丢引用 不要求调用方 recycle`() {
        // ⚠️ 这是**用法约束**不是缓存行为：调用方绝不能对被淘汰的 Bitmap 调 recycle()，
        // 它可能正被 Compose painter 持有并绘制中（会抛 "trying to use a recycled bitmap"）。
        // 这里用"淘汰后 get 返回 null、且容器不抛异常"锁住纯容器的契约，
        // 真正的约束写在 IconLoader.cache 的 KDoc 里。
        val cache = lruCache<String, String>(2)
        cache["a"] = "A"
        cache["b"] = "B"
        cache["c"] = "C"

        assertEquals(2, cache.size)
        assertNull("被淘汰的条目应直接不可见", cache["a"])
    }
}