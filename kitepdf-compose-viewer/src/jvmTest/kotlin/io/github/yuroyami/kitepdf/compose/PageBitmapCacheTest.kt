package io.github.yuroyami.kitepdf.compose

import androidx.compose.ui.graphics.ImageBitmap
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** T-15: the page-bitmap LRU's hit, eviction and budget behaviour. */
class PageBitmapCacheTest {

    private fun key(id: Any, w: Int = 100, h: Int = 100) =
        PageBitmapCache.Key(id, w, h, bgArgb = -1, themeId = 0, hairlineBits = 0)

    @Test
    fun second_lookup_returns_the_same_instance_and_produces_once() {
        val cache = PageBitmapCache(maxBytes = 10L * 1024 * 1024)
        var produced = 0
        val k = key("page0")
        val first = cache.getOrPut(k) { produced++; ImageBitmap(100, 100) }
        val second = cache.getOrPut(k) { produced++; ImageBitmap(100, 100) }
        assertSame(first, second, "cache hit returns the identical bitmap")
        assertEquals(1, produced, "the producer ran exactly once")
    }

    @Test
    fun eviction_is_lru_and_bytes_stay_under_budget() {
        // 100x100x4 = 40,000 bytes per entry; budget fits 2.
        val cache = PageBitmapCache(maxBytes = 90_000)
        val a = key("a")
        val b = key("b")
        val c = key("c")
        cache.getOrPut(a) { ImageBitmap(100, 100) }
        cache.getOrPut(b) { ImageBitmap(100, 100) }
        cache.getOrPut(a) { error("a is cached") } // refresh a's recency
        cache.getOrPut(c) { ImageBitmap(100, 100) } // evicts b (eldest), not a
        assertTrue(cache.contains(a), "recently-used entry survives")
        assertFalse(cache.contains(b), "least-recently-used entry evicted")
        assertTrue(cache.contains(c))
        assertTrue(cache.trackedBytes <= 90_000, "tracked bytes ${cache.trackedBytes} within budget")
        assertEquals(2, cache.size)
    }

    @Test
    fun zero_budget_is_a_pass_through() {
        val cache = PageBitmapCache(maxBytes = 0)
        var produced = 0
        val k = key("p")
        cache.getOrPut(k) { produced++; ImageBitmap(10, 10) }
        cache.getOrPut(k) { produced++; ImageBitmap(10, 10) }
        assertEquals(2, produced, "no caching at budget 0")
        assertEquals(0, cache.size)
    }

    @Test
    fun an_entry_larger_than_the_whole_budget_is_still_served() {
        val cache = PageBitmapCache(maxBytes = 1000)
        val k = key("huge", w = 200, h = 200)
        var produced = 0
        val bmp = cache.getOrPut(k) { produced++; ImageBitmap(200, 200) }
        assertEquals(1, produced)
        assertEquals(200, bmp.width, "the oversized bitmap is returned regardless")
    }
}
