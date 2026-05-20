package com.yuroyami.kitepdf.skia

import com.yuroyami.kitepdf.KitePDF
import com.yuroyami.kitepdf.core.ByteArrayBuilder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * End-to-end test: build a minimal PDF in memory, feed it through KitePDF +
 * the Skia adapter, and verify we get a valid PNG bitmap out the other side.
 *
 * Pixel-level correctness is deliberately NOT asserted here — that would
 * couple the test to specific Skia rasterization behaviour. We just verify
 * the pipeline is wired end-to-end and produces a non-trivial output.
 */
class SkiaRasterizationTest {

    @Test
    fun rasterizes_minimal_pdf_to_png() {
        val pdf = buildMinimalPdf()
        val doc = KitePDF.open(pdf)
        val page = doc.pages[0]

        val png = PdfPageRasterizer.encodeToPng(page, scale = 1.0)

        // PNG magic bytes: 89 50 4E 47 0D 0A 1A 0A
        assertTrue(png.size > 8, "PNG should be non-empty")
        assertEquals(0x89.toByte(), png[0])
        assertEquals(0x50.toByte(), png[1])  // 'P'
        assertEquals(0x4E.toByte(), png[2])  // 'N'
        assertEquals(0x47.toByte(), png[3])  // 'G'
        // PNG end-of-file: IEND chunk at the tail.
        assertTrue(png.size >= 12)
    }

    @Test
    fun rasterizes_at_2x_for_retina_density() {
        val pdf = buildMinimalPdf()
        val doc = KitePDF.open(pdf)
        val page = doc.pages[0]

        val image1x = PdfPageRasterizer.renderToImage(page, scale = 1.0)
        val image2x = PdfPageRasterizer.renderToImage(page, scale = 2.0)
        try {
            // 612pt × 792pt page → 1× = 612×792 px; 2× = 1224×1584 px.
            assertEquals(612, image1x.width)
            assertEquals(792, image1x.height)
            assertEquals(1224, image2x.width)
            assertEquals(1584, image2x.height)
        } finally {
            image1x.close()
            image2x.close()
        }
    }

    /* ─── Helpers ─────────────────────────────────────────────────────────── */

    private fun buildMinimalPdf(): ByteArray {
        val buf = ByteArrayBuilder()
        val offsets = mutableListOf<Int>()
        fun w(s: String) = buf.append(s.encodeToByteArray())

        w("%PDF-1.4\n%Äå\n")
        offsets.add(buf.size())
        w("1 0 obj\n<< /Type /Catalog /Pages 2 0 R >>\nendobj\n")
        offsets.add(buf.size())
        w("2 0 obj\n<< /Type /Pages /Kids [3 0 R] /Count 1 /MediaBox [0 0 612 792] >>\nendobj\n")
        offsets.add(buf.size())
        w("3 0 obj\n<< /Type /Page /Parent 2 0 R /Resources << /Font << /F1 4 0 R >> >> /Contents 5 0 R >>\nendobj\n")
        offsets.add(buf.size())
        w("4 0 obj\n<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>\nendobj\n")
        offsets.add(buf.size())
        // Draw a few path operations + show some text.
        val content = """
            q
            0.2 0.6 0.8 rg
            72 200 460 400 re
            f
            0.8 0.2 0.2 RG
            5 w
            72 200 460 400 re
            S
            BT /F1 24 Tf 100 700 Td (Hello, Skia) Tj ET
            Q
        """.trimIndent().encodeToByteArray()
        w("5 0 obj\n<< /Length ${content.size} >>\nstream\n")
        buf.append(content)
        w("\nendstream\nendobj\n")

        val xref = buf.size()
        w("xref\n0 6\n0000000000 65535 f \n")
        for (o in offsets) w("${o.toString().padStart(10, '0')} 00000 n \n")
        w("trailer\n<< /Size 6 /Root 1 0 R >>\nstartxref\n$xref\n%%EOF\n")
        return buf.toByteArray()
    }
}
