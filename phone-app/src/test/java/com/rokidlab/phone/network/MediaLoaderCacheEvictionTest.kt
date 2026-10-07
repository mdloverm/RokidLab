package com.rokidlab.phone.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [MediaLoader] 的缓存**有界性**回归测试（2026-10-05 审计修）。
 *
 * ## 锁的是哪个 bug
 * 本类原先三个容器全是**无界** `ConcurrentHashMap`：
 * `cache`（key→`Drawable`，而 Drawable 持 ≤1080px 的 Bitmap）随商店浏览**单调增长、
 * 永不淘汰** ⇒ 长时间逛商店必然 OOM。而同项目的 `PainterBitmapCache` 早就用
 * `LinkedHashMap` LRU 兜住了 —— **同一职责两套缓存策略，只有一套有上限**。
 *
 * ## 为什么要单测而不是读代码看出来
 * 「有没有上限」在代码里就一行 `size > N`，很难在 review 里被肉眼注意到；
 * 而它的缺失**只在长时间运行后才显形**（用户反馈"逛久了应用崩了"），
 * 那时已经很难回溯到这一行。这类**资源类不变量**就该用断言钉死。
 *
 * ## 测的是纯 LRU 容器，不碰网络与 Context
 * 真 `MediaLoader` 需要 `Context` + 真实 assets/网络。锁的是**容量策略**这一个纯逻辑，
 * 所以把 LRU 容器抽成顶层可测对象（[lruCache]），行为与 `cache`/`failedUrlTimestamps` 逐字一致。
 */
class MediaLoaderCacheEvictionTest {

    @Test
    fun `缓存超过上限时淘汰最旧条目`() {
        val cache = lruCache<String, String>(4)
        listOf("a", "b", "c", "d", "e").forEach { cache[it] = it.uppercase() }

        assertEquals("超上限后应只保留上限条数", 4, cache.size)
        assertNull("最早的 a 应被淘汰", cache["a"])
        assertEquals("B", cache["b"])
        assertEquals("E", cache["e"])
    }

    @Test
    fun `命中会刷新次序 - 久用的条目不被淘汰`() {
        val cache = lruCache<String, String>(3)
        cache["a"] = "A"
        cache["b"] = "B"
        cache["c"] = "C"
        // 访问 a ⇒ 它变成"最近用过"，不该是下一个被淘汰的
        assertEquals("A", cache["a"])
        cache["d"] = "D"

        assertEquals(3, cache.size)
        assertNotNull("刚被访问过的 a 不该被淘汰", cache["a"])
        assertNull("真正最旧的 b 应被淘汰", cache["b"])
    }

    @Test
    fun `容量为一时退化为单条缓存且行为正确`() {
        val cache = lruCache<String, String>(1)
        cache["a"] = "A"
        cache["b"] = "B"

        assertEquals(1, cache.size)
        assertNull("容量 1 时旧条目应立刻被挤掉", cache["a"])
        assertEquals("B", cache["b"])
    }

    @Test
    fun `反复写同一 key 不增长 - 更新不产生新条目`() {
        val cache = lruCache<String, String>(4)
        repeat(50) { cache["same"] = "v$it" }

        assertEquals("重复写同一个 key 应当只占 1 条", 1, cache.size)
        assertEquals("v49", cache["same"])
    }

    @Test
    fun `删除与清空后不残留`() {
        val cache = lruCache<String, String>(4)
        cache["a"] = "A"
        cache["b"] = "B"
        cache.remove("a")
        assertEquals(1, cache.size)
        assertNull(cache["a"])

        cache.clear()
        assertEquals(0, cache.size)
    }

    @Test
    fun `达到上限时值被替换为最新写入`() {
        val cache = lruCache<String, String>(2)
        cache["a"] = "旧值"
        cache["a"] = "新值"

        assertEquals("同 key 重写应更新而非追加", 1, cache.size)
        assertEquals("新值", cache["a"])
    }

    @Test
    fun `实际使用上限不会超出配置值`() {
        // 商店图标 + 截图：跑一个远超上限的循环，确认容器始终被兜住
        val cap = 64
        val cache = lruCache<String, String>(cap)
        repeat(5000) { i -> cache["key$i"] = "v$i" }

        assertTrue("容量必须恒 ≤ 上限，实际 ${cache.size}", cache.size <= cap)
        assertEquals(cap, cache.size)
    }
}
