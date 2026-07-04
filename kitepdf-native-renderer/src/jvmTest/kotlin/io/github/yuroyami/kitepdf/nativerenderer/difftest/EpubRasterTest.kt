package io.github.yuroyami.kitepdf.nativerenderer.difftest

import io.github.yuroyami.kitepdf.epub.EpubDocument
import io.github.yuroyami.kitepdf.epub.EpubPage
import java.awt.image.BufferedImage
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Rasterises EpubPage through AwtCanvas and asserts on the actual pixels. The
 * ~130 EPUB unit tests record the Canvas call stream but never paint; this is the
 * first gate that exercises the real raster path (coordinate flip, paint order,
 * glyph outlines, image blitting, fillPath backgrounds/borders).
 */
class EpubRasterTest {

    private fun raster(page: EpubPage): BufferedImage = EpubCorpus.rasterize(page)

    private fun firstPage(body: String): EpubPage {
        val doc = EpubDocument.open(EpubCorpus.epub(body))
        assertNotNull(doc, "epub should open")
        return doc.pages.first()
    }

    // ---- pixel predicates ---------------------------------------------------

    private fun luminance(p: Int) =
        ((p shr 16) and 0xFF) * 0.299 + ((p shr 8) and 0xFF) * 0.587 + (p and 0xFF) * 0.114

    private fun darkPixels(img: BufferedImage): Int {
        var n = 0
        for (y in 0 until img.height) for (x in 0 until img.width) if (luminance(img.getRGB(x, y)) < 128) n++
        return n
    }

    private fun countColor(img: BufferedImage, r: Int, g: Int, b: Int, tol: Int = 40): Int {
        var n = 0
        for (y in 0 until img.height) for (x in 0 until img.width) {
            val p = img.getRGB(x, y)
            if (kotlin.math.abs(((p shr 16) and 0xFF) - r) <= tol &&
                kotlin.math.abs(((p shr 8) and 0xFF) - g) <= tol &&
                kotlin.math.abs((p and 0xFF) - b) <= tol
            ) n++
        }
        return n
    }

    private fun topInkRow(img: BufferedImage): Int {
        for (y in 0 until img.height) for (x in 0 until img.width) if (luminance(img.getRGB(x, y)) < 128) return y
        return -1
    }

    // ---- tests --------------------------------------------------------------

    @Test
    fun text_paints_dark_ink() {
        assertTrue(darkPixels(raster(firstPage("<p>The quick brown fox jumps over the lazy dog.</p>"))) > 200, "body text should leave ink")
    }

    @Test
    fun heading_ink_sits_near_the_top() {
        val heading = topInkRow(raster(firstPage("<h1>Title</h1><p>body</p>")))
        assertTrue(heading in 0 until 200, "heading ink near the top of the page (row $heading)")
    }

    @Test
    fun background_color_fills_a_region() {
        assertTrue(countColor(raster(firstPage("""<p style="background-color:#ff0000">solid red band behind this text</p>""")), 255, 0, 0) > 500, "red background band painted")
    }

    @Test
    fun border_paints_colored_edges() {
        assertTrue(countColor(raster(firstPage("""<div style="border:4px solid #0000ff"><p>boxed</p></div>""")), 0, 0, 255) > 200, "blue border edges painted")
    }

    @Test
    fun background_is_behind_text_not_over_it() {
        val img = raster(firstPage("""<p style="background-color:#00ff00">text on green</p>"""))
        assertTrue(countColor(img, 0, 255, 0) > 500, "green background present")
        assertTrue(darkPixels(img) > 50, "black text still visible over the background")
    }

    @Test
    fun image_pixels_reach_the_raster() {
        val doc = EpubDocument.open(EpubCorpus.epub("<p>see:</p><img src=\"pic.png\"/>", listOf("OEBPS/pic.png" to EpubCorpus.redPng())))
        assertNotNull(doc)
        assertTrue(countColor(raster(doc.pages.first()), 255, 0, 0) > 1000, "the red image should blit a large red area")
    }

    @Test
    fun text_page_is_not_blank() {
        assertTrue(darkPixels(raster(firstPage("<p>hello world this is real text</p>"))) > 100, "text page not blank")
    }
}
