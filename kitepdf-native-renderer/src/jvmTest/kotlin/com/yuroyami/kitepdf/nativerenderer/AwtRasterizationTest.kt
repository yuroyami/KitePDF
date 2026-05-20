package com.yuroyami.kitepdf.nativerenderer

import com.yuroyami.kitepdf.KitePDF
import com.yuroyami.kitepdf.core.ByteArrayBuilder
import java.awt.Color
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Round-trip test: build a tiny PDF in memory, render it through KitePDF
 * core → AwtCanvas → BufferedImage → PNG bytes, then verify the result is
 * a valid PNG of the expected size.
 *
 * No display required — works headlessly in CI.
 */
class AwtRasterizationTest {

    @Test
    fun rasterizes_pdf_to_png_with_awt() {
        val pdf = buildPdf()
        val doc = KitePDF.open(pdf)
        val png = AwtPdfRasterizer.encodeToPng(doc.pages[0], scale = 1.0)
        assertTrue(png.size > 8, "PNG output should be non-empty")
        // PNG file signature: 89 50 4E 47 0D 0A 1A 0A
        assertEquals(0x89.toByte(), png[0])
        assertEquals(0x50.toByte(), png[1])
        assertEquals(0x4E.toByte(), png[2])
        assertEquals(0x47.toByte(), png[3])
    }

    @Test
    fun rasterizes_at_2x_density() {
        val pdf = buildPdf()
        val doc = KitePDF.open(pdf)
        val img1 = AwtPdfRasterizer.renderToImage(doc.pages[0], scale = 1.0)
        val img2 = AwtPdfRasterizer.renderToImage(doc.pages[0], scale = 2.0)
        assertEquals(612, img1.width)
        assertEquals(792, img1.height)
        assertEquals(1224, img2.width)
        assertEquals(1584, img2.height)
    }

    @Test
    fun custom_background_colour_is_applied() {
        val pdf = buildPdf()
        val doc = KitePDF.open(pdf)
        val img = AwtPdfRasterizer.renderToImage(doc.pages[0], scale = 1.0, background = Color(0, 200, 0))
        // Sample a far corner that no path covers — should be the background.
        val argb = img.getRGB(0, 0)
        val r = (argb shr 16) and 0xFF
        val g = (argb shr 8) and 0xFF
        val b = argb and 0xFF
        assertEquals(0, r)
        assertEquals(200, g)
        assertEquals(0, b)
    }

    /* ─── Helper ──────────────────────────────────────────────────────────── */

    private fun buildPdf(): ByteArray {
        val buf = ByteArrayBuilder()
        val offsets = mutableListOf<Int>()
        fun w(s: String) = buf.append(s.encodeToByteArray())

        w("%PDF-1.4\n%Äå\n")
        offsets.add(buf.size())
        w("1 0 obj\n<< /Type /Catalog /Pages 2 0 R >>\nendobj\n")
        offsets.add(buf.size())
        w("2 0 obj\n<< /Type /Pages /Kids [3 0 R] /Count 1 /MediaBox [0 0 612 792] >>\nendobj\n")
        offsets.add(buf.size())
        w("3 0 obj\n<< /Type /Page /Parent 2 0 R /Resources << >> /Contents 4 0 R >>\nendobj\n")
        offsets.add(buf.size())
        val content = """
            q
            0.2 0.6 0.8 rg
            100 200 400 400 re
            f
            0.8 0.2 0.2 RG
            3 w
            100 200 400 400 re
            S
            Q
        """.trimIndent().encodeToByteArray()
        w("4 0 obj\n<< /Length ${content.size} >>\nstream\n")
        buf.append(content)
        w("\nendstream\nendobj\n")

        val xref = buf.size()
        w("xref\n0 5\n0000000000 65535 f \n")
        for (o in offsets) w("${o.toString().padStart(10, '0')} 00000 n \n")
        w("trailer\n<< /Size 5 /Root 1 0 R >>\nstartxref\n$xref\n%%EOF\n")
        return buf.toByteArray()
    }
}
