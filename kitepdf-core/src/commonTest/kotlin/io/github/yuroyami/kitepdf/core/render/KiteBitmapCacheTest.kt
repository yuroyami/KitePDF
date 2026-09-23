package io.github.yuroyami.kitepdf.core.render

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame

/** [KiteBitmapCache] converts an image once per sampling and keeps its bitmaps within a byte budget. */
class KiteBitmapCacheTest {

    private fun image(): KiteImageData = KiteImageData(
        width = 64, height = 64, bitsPerComponent = 8, colorSpace = "test", kind = KiteImageData.Kind.RAW,
        encodedBytes = ByteArray(0), pixelBytes = ByteArray(64 * 64), resolvedColorSpace = KiteColorSpace.DeviceGray,
    )

    /** A bitmap that stands for [bytes] bytes. */
    private class Bitmap(val bytes: Long)

    private val atItsSize = KiteMatrix(64.0, 0.0, 0.0, 64.0, 0.0, 0.0)

    @Test
    fun an_image_drawn_many_times_converts_once() {
        val cache = KiteBitmapCache<Bitmap>()
        val image = image()
        val sampling = imageSampling(64, 64, atItsSize, false)
        var builds = 0
        val first = cache.getOrPut(image, sampling, { it.bytes }) { builds++; Bitmap(100) }
        repeat(31) { assertSame(first, cache.getOrPut(image, sampling, { it.bytes }) { builds++; Bitmap(100) }) }
        assertEquals(1, builds)
    }

    @Test
    fun another_size_or_another_image_converts_again() {
        val cache = KiteBitmapCache<Bitmap>()
        val image = image()
        var builds = 0
        cache.getOrPut(image, imageSampling(64, 64, atItsSize, false), { it.bytes }) { builds++; Bitmap(100) }
        // A quarter of the size averages the image down, which needs another bitmap.
        cache.getOrPut(image, imageSampling(64, 64, KiteMatrix(16.0, 0.0, 0.0, 16.0, 0.0, 0.0), false), { it.bytes }) { builds++; Bitmap(100) }
        // An equal image that is another instance has its own bitmap.
        cache.getOrPut(image(), imageSampling(64, 64, atItsSize, false), { it.bytes }) { builds++; Bitmap(100) }
        assertEquals(3, builds)
    }

    @Test
    fun the_bitmap_used_least_recently_leaves_first() {
        val cache = KiteBitmapCache<Bitmap>(budgetBytes = 250)
        val sampling = imageSampling(64, 64, atItsSize, false)
        val (a, b, c) = listOf(image(), image(), image())
        var builds = 0
        for (i in listOf(a, b, a, c)) cache.getOrPut(i, sampling, { it.bytes }) { builds++; Bitmap(100) }
        assertEquals(3, builds)
        assertEquals(200L, cache.heldBytes)
        // c pushed out b, the bitmap used least recently, and kept a.
        cache.getOrPut(a, sampling, { it.bytes }) { builds++; Bitmap(100) }
        assertEquals(3, builds)
        cache.getOrPut(b, sampling, { it.bytes }) { builds++; Bitmap(100) }
        assertEquals(4, builds)
    }

    @Test
    fun a_bitmap_larger_than_the_budget_or_a_failed_build_is_not_kept() {
        val cache = KiteBitmapCache<Bitmap>(budgetBytes = 50)
        val image = image()
        val sampling = imageSampling(64, 64, atItsSize, false)
        var builds = 0
        repeat(2) { cache.getOrPut(image, sampling, { it.bytes }) { builds++; Bitmap(100) } }
        assertEquals(2, builds)
        assertEquals(0L, cache.heldBytes)
        repeat(2) { assertNull(cache.getOrPut(image(), sampling, { it.bytes }) { builds++; null }) }
        assertEquals(4, builds)
    }
}
