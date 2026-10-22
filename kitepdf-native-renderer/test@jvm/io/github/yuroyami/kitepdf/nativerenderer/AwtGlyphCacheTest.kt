package io.github.yuroyami.kitepdf.nativerenderer

import io.github.yuroyami.kitepdf.PdfDocument
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The glyph cache of [AwtCanvas] draws the same pixels as filling each glyph (#306). Text in
 * Multiply over white has the colour of text in Normal, but Multiply skips the cache and
 * fills each glyph, on the same subpixel grid.
 */
class AwtGlyphCacheTest {

    private fun page(blend: String): ByteArray {
        val content = ("/GS0 gs BT /F1 9 Tf 10 170 Td (The quick brown fox jumps over the lazy dog) Tj " +
            "/F1 17 Tf 0 -30 Td (Sphinx of black quartz, judge my vow) Tj " +
            "/F1 30 Tf 0 -45 Td (Glyph cache 0123) Tj " +
            "0.8 0.1 0.2 rg /F1 60 Tf 0 -75 Td (Wide) Tj ET").encodeToByteArray()
        val objects = listOf(
            "<< /Type /Catalog /Pages 2 0 R >>",
            "<< /Type /Pages /Kids [3 0 R] /Count 1 >>",
            "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 400 200] /Contents 4 0 R " +
                "/Resources << /Font << /F1 << /Type /Font /Subtype /Type1 /BaseFont /Helvetica >> >> " +
                "/ExtGState << /GS0 << /BM /$blend >> >> >> >>",
            "<< /Length ${content.size} >>\nstream\n${content.decodeToString()}\nendstream",
        )
        val out = StringBuilder("%PDF-1.7\n")
        val offsets = objects.mapIndexed { i, body -> out.length.also { out.append("${i + 1} 0 obj\n$body\nendobj\n") } }
        val xref = out.length
        out.append("xref\n0 ${objects.size + 1}\n0000000000 65535 f \n")
        for (o in offsets) out.append("${o.toString().padStart(10, '0')} 00000 n \n")
        out.append("trailer\n<< /Size ${objects.size + 1} /Root 1 0 R >>\nstartxref\n$xref\n%%EOF\n")
        return out.toString().encodeToByteArray()
    }

    @Test
    fun cached_glyphs_have_the_pixels_of_filled_glyphs() {
        val cached = AwtPdfRasterizer.renderToImage(PdfDocument.open(page("Normal")).pages[0], scale = 96 / 72.0)
        val filled = AwtPdfRasterizer.renderToImage(PdfDocument.open(page("Multiply")).pages[0], scale = 96 / 72.0)
        var worst = 0
        var ink = 0
        for (y in 0 until cached.height) for (x in 0 until cached.width) {
            val a = cached.getRGB(x, y)
            val b = filled.getRGB(x, y)
            if ((a and 0xFFFFFF) != 0xFFFFFF) ink++
            for (shift in listOf(16, 8, 0)) worst = maxOf(worst, abs(((a ushr shift) and 0xFF) - ((b ushr shift) and 0xFF)))
        }
        assertTrue(ink > 2000, "the page has text, $ink pixels of ink")
        assertTrue(worst <= 2, "the cached and the filled glyphs differ by $worst levels")
    }
}
