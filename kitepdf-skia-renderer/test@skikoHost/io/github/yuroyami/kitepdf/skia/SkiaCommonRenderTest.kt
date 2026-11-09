package io.github.yuroyami.kitepdf.skia

import io.github.yuroyami.kitepdf.PdfDocument
import io.github.yuroyami.kitepdf.core.font.KiteFontFamily
import io.github.yuroyami.kitepdf.writer.PdfBuilder
import io.github.yuroyami.kitepdf.writer.StandardFont
import org.jetbrains.skia.FontStyle
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The Skia renderer on every target it ships for, not the JVM alone (#768): a small page through
 * [PdfPageRasterizer] and [SkiaCanvas] paints its shapes and text where the page puts them, and a
 * lookup of the host's faces through [SkiaSystemFonts] answers without failing, whatever fonts
 * the host has.
 */
class SkiaCommonRenderTest {

    private fun page() = PdfDocument.open(
        PdfBuilder()
            .page(width = 200.0, height = 100.0) {
                setFillRgb(1.0, 0.0, 0.0)
                rectangle(10.0, 10.0, 60.0, 40.0)
                fill()
                text(StandardFont.Helvetica, 20.0, 100.0, 60.0, "Kite")
            }
            .build(),
    ).pages[0]

    @Test
    fun a_page_paints_its_shapes_and_text_where_it_puts_them() {
        val image = PdfPageRasterizer.renderToImage(page(), scale = 1.0)
        try {
            assertEquals(200, image.width)
            assertEquals(100, image.height)
            val pixels = image.peekPixels() ?: error("no pixels")
            // The rectangle spans x 10..70 and y 10..50 in PDF space, so 50..90 from the top.
            assertEquals(0xFFFF0000.toInt(), pixels.getColor(40, 70), "the red rectangle")
            assertEquals(-1, pixels.getColor(40, 20), "white above the rectangle")
            assertEquals(-1, pixels.getColor(190, 95), "white in the corner")
            // "Kite" sits on the baseline at y 60, x from 100: its letters ink the band above it.
            var ink = 0
            for (y in 20 until 45) for (x in 100 until 150) if (pixels.getColor(x, y) and 0xFF < 128) ink++
            assertTrue(ink > 40, "the text inked $ink pixels")
        } finally {
            image.close()
        }
    }

    @Test
    fun a_lookup_of_the_host_faces_answers_on_any_host() {
        for (family in KiteFontFamily.entries) {
            val face = SkiaSystemFonts.resolve(family, FontStyle.NORMAL)
            // A host with no faces, a browser or a bare container, answers null; one with faces names it.
            if (face != null) assertTrue(face.familyName.isNotEmpty(), "$family resolved to a face with no name")
        }
    }
}
