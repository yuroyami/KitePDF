package io.github.yuroyami.kitepdf.cbz

import io.github.yuroyami.kitepdf.core.render.KiteBitmapCache
import io.github.yuroyami.kitepdf.core.render.KiteImageData
import io.github.yuroyami.kitepdf.core.render.KiteMatrix
import io.github.yuroyami.kitepdf.core.render.RecordingCanvas
import io.github.yuroyami.kitepdf.core.render.imageSampling
import io.github.yuroyami.kitepdf.core.render.toRgbaBytes
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A comic page reads its size from the image header, without the rest of the scan, and it
 * decodes its scan once for several renders. It read and checked the whole entry for its size,
 * and it decoded the scan again for every render (#386).
 */
class CbzPageReadsTest {

    private fun images(canvas: RecordingCanvas) = canvas.calls.filterIsInstance<RecordingCanvas.Call.Image>()

    @Test
    fun the_size_comes_from_the_header_alone() {
        var fullReads = 0
        val png = CbzFixtures.pngHeader320x200()
        val page = CbzPage({ fullReads++; png }, "p.png", readHeader = { png.copyOf(32) })
        assertEquals(320.0, page.displayWidth)
        assertEquals(200.0, page.displayHeight)
        assertEquals(0, fullReads, "the size read the whole entry")
    }

    @Test
    fun a_header_that_does_not_give_a_size_falls_back_to_the_whole_entry() {
        var fullReads = 0
        val bmp = CbzFixtures.bmp2x1()
        val page = CbzPage({ fullReads++; bmp }, "p.bmp", readHeader = { bmp.copyOf(4) })
        assertEquals(2.0, page.displayWidth)
        assertEquals(1, fullReads)
    }

    @Test
    fun a_page_decodes_its_scan_once_for_two_renders() {
        var fullReads = 0
        val bmp = CbzFixtures.bmp2x1()
        val page = CbzPage({ fullReads++; bmp }, "p.bmp", readHeader = { bmp }, decoded = CbzImageCache(1_000_000))
        val first = RecordingCanvas().also { page.renderTo(it) }
        val second = RecordingCanvas().also { page.renderTo(it) }
        assertEquals(1, fullReads, "the second render read and decoded the scan again")
        assertEquals(1, images(first).size)
        assertEquals(1, images(second).size)
    }

    @Test
    fun a_cancelled_render_stops_before_the_decode() {
        val bmp = CbzFixtures.bmp2x1()
        val cache = CbzImageCache(1_000_000)
        val page = CbzPage({ bmp }, "p.bmp", readHeader = { bmp }, decoded = cache)
        val canvas = RecordingCanvas()
        page.renderTo(canvas, KiteMatrix.IDENTITY) { true }
        assertTrue(images(canvas).isEmpty(), "a cancelled render drew the image")
        assertNull(cache.get("p.bmp"), "a cancelled render decoded the scan")
    }

    @Test
    fun the_cache_keeps_the_newest_scans_within_its_budget() {
        val image = assertNotNull(KiteImageData.fromEncodedImage(CbzFixtures.bmp2x1()))
        val size = image.pixelBytes!!.size.toLong()
        val cache = CbzImageCache(2 * size)
        cache.put("a", image)
        cache.put("b", image)
        cache.get("a")
        // A third scan pushes out the one used least recently, b.
        cache.put("c", image)
        assertNotNull(cache.get("a"))
        assertNull(cache.get("b"))
        assertNotNull(cache.get("c"))
    }

    @Test
    fun a_comic_reads_its_page_sizes_and_draws_its_pages() {
        val doc = CbzDocument.open(CbzFixtures.comic("1.png" to CbzFixtures.pngHeader320x200(), "2.bmp" to CbzFixtures.bmp2x1()))
        assertEquals(320.0, doc.pages[0].displayWidth)
        assertEquals(2.0, doc.pages[1].displayWidth)
        val canvas = RecordingCanvas()
        doc.pages[1].renderTo(canvas)
        assertEquals(1, images(canvas).size)
    }

    @Test
    fun converted_pixels_survive_a_scan_eviction_and_a_zero_decode_budget() {
        val bmp = CbzFixtures.bmp2x1()
        val imageBytes = assertNotNull(KiteImageData.fromEncodedImage(bmp)).pixelBytes!!.size.toLong()
        for (budget in listOf(0L, imageBytes)) {
            val decoded = CbzImageCache(budget)
            val page = CbzPage({ bmp }, "p.bmp", readHeader = { bmp }, decoded = decoded)
            val bitmaps = KiteBitmapCache<ByteArray>()
            var conversions = 0
            fun convert(image: KiteImageData) = bitmaps.getOrPut(
                image, imageSampling(image.width, image.height, KiteMatrix.IDENTITY, false), { it.size.toLong() },
            ) { conversions++; image.toRgbaBytes() }
            val first = images(RecordingCanvas().also { page.renderTo(it) }).single().image
            assertNotNull(convert(first))
            // Evict p.bmp when the budget can hold one scan; zero already retained nothing.
            decoded.put("other.bmp", assertNotNull(KiteImageData.fromEncodedImage(bmp)))
            assertNull(decoded.get("p.bmp"))
            val second = images(RecordingCanvas().also { page.renderTo(it) }).single().image
            assertNotSame(first, second)
            assertNotNull(convert(second))
            assertEquals(1, conversions, "budget $budget must not invalidate converted pixels")
        }
    }

    @Test
    fun the_same_entry_name_in_different_comics_keeps_distinct_pixels() {
        val bytes = CbzFixtures.comic("page.bmp" to CbzFixtures.bmp2x1())
        val bitmaps = KiteBitmapCache<ByteArray>()
        var conversions = 0
        repeat(2) {
            val document = CbzDocument.open(bytes)
            val image = images(RecordingCanvas().also { document.pages[0].renderTo(it) }).single().image
            bitmaps.getOrPut(
                image, imageSampling(image.width, image.height, KiteMatrix.IDENTITY, false), { it.size.toLong() },
            ) { conversions++; image.toRgbaBytes() }
        }
        assertEquals(2, conversions, "identical paths from independent documents must not alias")
    }
}
