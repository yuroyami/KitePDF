package io.github.yuroyami.kitepdf

import io.github.yuroyami.kitepdf.core.ByteArrayBuilder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AnnotationTest {

    @Test
    fun parses_link_annotation_with_uri() {
        val pdf = buildPdfWithLinkAnnotation("https://example.com")
        val doc = KitePDF.open(pdf)
        val annots = doc.pages[0].annotations
        assertEquals(1, annots.size)
        val annot = annots[0]
        assertEquals(PdfAnnotation.Subtype.Link, annot.subtype)
        assertEquals("https://example.com", annot.uri)
        // Rect [100 700 300 720]
        assertEquals(100.0, annot.rect.left)
        assertEquals(700.0, annot.rect.bottom)
        assertEquals(300.0, annot.rect.right)
        assertEquals(720.0, annot.rect.top)
    }

    @Test
    fun parses_highlight_annotation_color() {
        val pdf = buildPdfWithHighlightAnnotation()
        val doc = KitePDF.open(pdf)
        val annots = doc.pages[0].annotations
        assertEquals(1, annots.size)
        val annot = annots[0]
        assertEquals(PdfAnnotation.Subtype.Highlight, annot.subtype)
        assertTrue(annot.color != null, "Highlight should have a parsed colour")
        assertEquals(1.0, annot.color!!.r)
        assertEquals(1.0, annot.color.g)
        assertEquals(0.0, annot.color.b)
    }

    /**
     * ISO 32000-1 §7.9.5: `/Rect` holds two diagonally opposite corners **in any order**, and the
     * consumer must normalise. Read positionally, `[x2 y2 x1 y1]` produces an inside-out box whose
     * width and height are negative, so the synthesized border paints nothing and the viewer's
     * containment test (`y < bottom || y > top`) cannot be satisfied by any point. Every link on
     * such a page was therefore both invisible and untappable.
     *
     * The only fixture here used a well-ordered rect, which is why nothing caught it.
     */
    @Test
    fun normalizes_reversed_rect_corners() {
        val doc = KitePDF.open(
            buildPdfWithLinkAnnotation("https://example.com", rect = "[300 720 100 700]"),
        )
        val rect = doc.pages[0].annotations.single().rect

        assertEquals(100.0, rect.left)
        assertEquals(700.0, rect.bottom)
        assertEquals(300.0, rect.right)
        assertEquals(720.0, rect.top)
        // The properties the hit test and the border renderer actually consume.
        assertEquals(200.0, rect.width)
        assertEquals(20.0, rect.height)
    }

    /**
     * §12.5.4: `/Border [0 0 0]` asks for no visible frame, which is what a link styled as coloured
     * text wants. Ignoring it drew a box around every one of them. `/BS /W` supersedes `/Border`.
     */
    @Test
    fun reads_declared_border_width() {
        fun widthOf(extra: String): Double? = KitePDF
            .open(buildPdfWithLinkAnnotation("https://example.com", extra = extra))
            .pages[0].annotations.single().borderWidth

        assertEquals(0.0, widthOf("/Border [0 0 0]"))
        assertEquals(3.0, widthOf("/Border [0 0 3]"))
        assertEquals(2.0, widthOf("/BS << /W 2 /S /S >>"))
        // /BS wins over /Border when both are present.
        assertEquals(2.0, widthOf("/Border [0 0 7] /BS << /W 2 /S /S >>"))
        // Undeclared stays null so the caller can apply the spec default of 1.
        assertEquals(null, widthOf(""))
    }

    private fun buildPdfWithLinkAnnotation(
        uri: String,
        rect: String = "[100 700 300 720]",
        extra: String = "",
    ): ByteArray {
        val buf = ByteArrayBuilder()
        val offsets = mutableListOf<Int>()
        fun w(s: String) = buf.append(s.encodeToByteArray())
        w("%PDF-1.4\n%Äå\n")
        offsets.add(buf.size())
        w("1 0 obj\n<< /Type /Catalog /Pages 2 0 R >>\nendobj\n")
        offsets.add(buf.size())
        w("2 0 obj\n<< /Type /Pages /Kids [3 0 R] /Count 1 >>\nendobj\n")
        offsets.add(buf.size())
        w("3 0 obj\n<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] " +
            "/Resources << >> /Annots [5 0 R] /Contents 4 0 R >>\nendobj\n")
        offsets.add(buf.size())
        val content = "BT ET".encodeToByteArray()
        w("4 0 obj\n<< /Length ${content.size} >>\nstream\n")
        buf.append(content); w("\nendstream\nendobj\n")
        offsets.add(buf.size())
        w("5 0 obj\n<< /Type /Annot /Subtype /Link /Rect $rect $extra " +
            "/A << /Type /Action /S /URI /URI ($uri) >> >>\nendobj\n")
        val xref = buf.size()
        w("xref\n0 6\n0000000000 65535 f \n")
        for (o in offsets) w("${o.toString().padStart(10, '0')} 00000 n \n")
        w("trailer\n<< /Size 6 /Root 1 0 R >>\nstartxref\n$xref\n%%EOF\n")
        return buf.toByteArray()
    }

    private fun buildPdfWithHighlightAnnotation(): ByteArray {
        val buf = ByteArrayBuilder()
        val offsets = mutableListOf<Int>()
        fun w(s: String) = buf.append(s.encodeToByteArray())
        w("%PDF-1.4\n%Äå\n")
        offsets.add(buf.size())
        w("1 0 obj\n<< /Type /Catalog /Pages 2 0 R >>\nendobj\n")
        offsets.add(buf.size())
        w("2 0 obj\n<< /Type /Pages /Kids [3 0 R] /Count 1 >>\nendobj\n")
        offsets.add(buf.size())
        w("3 0 obj\n<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] " +
            "/Resources << >> /Annots [5 0 R] /Contents 4 0 R >>\nendobj\n")
        offsets.add(buf.size())
        val content = "BT ET".encodeToByteArray()
        w("4 0 obj\n<< /Length ${content.size} >>\nstream\n")
        buf.append(content); w("\nendstream\nendobj\n")
        offsets.add(buf.size())
        w("5 0 obj\n<< /Type /Annot /Subtype /Highlight /Rect [50 600 200 620] " +
            "/C [1 1 0] /Contents (yellow highlight) >>\nendobj\n")
        val xref = buf.size()
        w("xref\n0 6\n0000000000 65535 f \n")
        for (o in offsets) w("${o.toString().padStart(10, '0')} 00000 n \n")
        w("trailer\n<< /Size 6 /Root 1 0 R >>\nstartxref\n$xref\n%%EOF\n")
        return buf.toByteArray()
    }
}
