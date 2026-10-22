package io.github.yuroyami.kitepdf.epub

import io.github.yuroyami.kitepdf.core.KiteLocation
import io.github.yuroyami.kitepdf.core.render.KiteBitmapCache
import io.github.yuroyami.kitepdf.core.render.KiteImageData
import io.github.yuroyami.kitepdf.core.render.KiteMatrix
import io.github.yuroyami.kitepdf.core.render.RecordingCanvas
import io.github.yuroyami.kitepdf.core.render.imageSampling
import io.github.yuroyami.kitepdf.core.render.toRgbaBytes
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotSame
import kotlin.test.assertTrue

/** Immutable resource identities survive re-layout, while independent books remain distinct (#371). */
class ImageIdentityTest {

    private fun book(): ByteArray = EpubFixtures.epubFoldered(
        bodies = listOf("<img src=\"../pic.bmp\"/>", "<p>Second chapter</p>"),
        extraEntries = listOf("OEBPS/pic.bmp" to EpubFixtures.bmp2x1()),
    )

    private fun image(document: EpubDocument): KiteImageData = RecordingCanvas().also {
        document.page(KiteLocation(0, 0)).renderTo(it, KiteMatrix.IDENTITY)
    }.calls.filterIsInstance<RecordingCanvas.Call.Image>().single().image

    private class Bitmaps {
        private val cache = KiteBitmapCache<ByteArray>()
        var conversions = 0
            private set

        fun convert(image: KiteImageData) = cache.getOrPut(
            image, imageSampling(image.width, image.height, KiteMatrix.IDENTITY, false), { it.size.toLong() },
        ) { conversions++; image.toRgbaBytes() }
    }

    @Test
    fun chapter_eviction_and_settings_changes_reuse_converted_resource_pixels() {
        val document = EpubDocument.open(book(), EpubSettings(layoutCacheBytes = 0L))
        val bitmaps = Bitmaps()
        val first = image(document)
        bitmaps.convert(first)
        document.page(KiteLocation(1, 0)).renderTo(RecordingCanvas(), KiteMatrix.IDENTITY)
        assertFalse(document.isChapterLive(0), "the image chapter must really leave the layout cache")
        val restored = image(document)
        assertNotSame(first, restored)
        bitmaps.convert(restored)
        val reflowed = image(document.withFontSize(document.fontSize + 2))
        assertNotSame(restored, reflowed)
        bitmaps.convert(reflowed)
        assertEquals(1, bitmaps.conversions)
    }

    @Test
    fun independent_books_with_the_same_resource_path_do_not_alias() {
        val bytes = book()
        val bitmaps = Bitmaps()
        bitmaps.convert(image(EpubDocument.open(bytes)))
        bitmaps.convert(image(EpubDocument.open(bytes)))
        assertEquals(2, bitmaps.conversions)
    }

    @Test
    fun one_file_that_many_elements_name_decodes_once_per_layout() {
        // An ornament or a bullet picture used all through a chapter: each use held its own
        // decoded pixels, so a large picture named a thousand times filled the heap.
        val document = EpubDocument.open(
            EpubFixtures.epubFoldered(
                bodies = listOf("<p>" + "<img src=\"../pic.bmp\" width=\"10\" height=\"10\" alt=\"\"/>".repeat(30) + "</p>"),
                extraEntries = listOf("OEBPS/pic.bmp" to EpubFixtures.bmp2x1()),
            ),
        )
        val drawn = RecordingCanvas().also { document.page(KiteLocation(0, 0)).renderTo(it, KiteMatrix.IDENTITY) }
            .calls.filterIsInstance<RecordingCanvas.Call.Image>().map { it.image }
        assertEquals(30, drawn.size)
        assertTrue(drawn.all { it === drawn.first() }, "every use draws the one decoded image")
    }
}
