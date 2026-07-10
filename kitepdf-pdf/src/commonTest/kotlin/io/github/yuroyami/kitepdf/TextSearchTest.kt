package io.github.yuroyami.kitepdf

import io.github.yuroyami.kitepdf.text.search
import io.github.yuroyami.kitepdf.writer.PdfBuilder
import io.github.yuroyami.kitepdf.writer.StandardFont
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * T-81/T-30: PDF `textContent()` (the display-space adapter over
 * PdfStructuredText) and the search API built on the shared core walker.
 */
class TextSearchTest {

    /** One page, one block: a sentence split across two lines with a hyphenated break. */
    private fun hyphenatedDoc(): PdfDocument = KitePDF.open(
        PdfBuilder()
            .page(width = 612.0, height = 792.0) {
                text(StandardFont.Helvetica, 12.0, 72.0, 700.0, "The map shows the compres-")
                text(StandardFont.Helvetica, 12.0, 72.0, 686.0, "sion region very clearly")
            }
            .build(compress = false),
    )

    @Test
    fun text_content_lines_match_structured_text_and_edges_are_sane() {
        val page = hyphenatedDoc().pages[0]
        val kite = page.textContent()
        assertNotNull(kite)
        val lines = kite.blocks.flatMap { it.lines }
        assertEquals(
            listOf("The map shows the compres-", "sion region very clearly"),
            lines.map { it.text },
        )
        for (line in lines) {
            assertEquals(line.text.length + 1, line.charEdges.size)
            for (i in 1 until line.charEdges.size) {
                assertTrue(line.charEdges[i] >= line.charEdges[i - 1], "edges monotonic")
            }
            assertTrue(line.charEdges.first() >= line.bounds.left - 0.5)
            assertTrue(line.charEdges.last() <= line.bounds.right + 0.5)
            // Display space: y-down, inside the page box.
            assertTrue(line.bounds.left >= 0 && line.bounds.right <= 612.0)
            assertTrue(line.bounds.bottom >= 0 && line.bounds.top <= 792.0)
            // The text sits near the top of the page, so in display space its
            // y values are small.
            assertTrue(line.bounds.top < 120.0, "y-down display space (top=${line.bounds.top})")
        }
    }

    @Test
    fun exact_match_yields_one_quad_within_the_page() {
        val page = hyphenatedDoc().pages[0]
        val hits = page.search("map shows")
        assertEquals(1, hits.size)
        val quad = hits.single().quads.single()
        assertTrue(quad.width > 0 && quad.height > 0, "non-empty quad")
        assertTrue(quad.left >= 0 && quad.right <= 612.0 && quad.bottom >= 0 && quad.top <= 792.0)
    }

    @Test
    fun hyphenated_break_joins_and_produces_one_quad_per_line() {
        val page = hyphenatedDoc().pages[0]
        val hits = page.search("compression region")
        assertEquals(1, hits.size, "compres-/sion joins with the hyphen dropped")
        val quads = hits.single().quads
        assertEquals(2, quads.size, "one quad per line touched")
        for (q in quads) {
            assertTrue(q.width > 0 && q.height > 0)
            assertTrue(q.left >= 0 && q.right <= 612.0 && q.bottom >= 0 && q.top <= 792.0)
        }
        // The first-line quad starts at "compres", i.e. right of the line start.
        assertTrue(quads[0].left > 72.0)
    }

    @Test
    fun case_insensitive_by_default_case_sensitive_on_request() {
        val page = hyphenatedDoc().pages[0]
        assertEquals(1, page.search("COMPRESSION REGION").size)
        assertTrue(page.search("COMPRESSION REGION", ignoreCase = false).isEmpty())
    }

    @Test
    fun document_search_is_lazy_and_stamps_page_indices() {
        val doc = KitePDF.open(
            PdfBuilder()
                .page { text(StandardFont.Helvetica, 12.0, 72.0, 700.0, "nothing here") }
                .page { text(StandardFont.Helvetica, 12.0, 72.0, 700.0, "the needle lives here") }
                .build(compress = false),
        )
        val hits = doc.search("needle").toList()
        assertEquals(1, hits.size)
        assertEquals(1, hits.single().pageIndex)
    }

    /* ─── /Rotate 90 ─────────────────────────────────────────────────────── */

    private fun rotatedPdf(): ByteArray {
        val content = "BT /F1 12 Tf 72 700 Td (Rotated hello world) Tj ET"
        val sb = StringBuilder()
        val offsets = ArrayList<Int>()
        fun add(s: String) {
            offsets.add(sb.length)
            sb.append(s)
        }
        sb.append("%PDF-1.4\n")
        add("1 0 obj\n<< /Type /Catalog /Pages 2 0 R >>\nendobj\n")
        add("2 0 obj\n<< /Type /Pages /Kids [3 0 R] /Count 1 >>\nendobj\n")
        add(
            "3 0 obj\n<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] /Rotate 90 " +
                "/Resources << /Font << /F1 4 0 R >> >> /Contents 5 0 R >>\nendobj\n",
        )
        add("4 0 obj\n<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>\nendobj\n")
        add("5 0 obj\n<< /Length ${content.length} >>\nstream\n$content\nendstream\nendobj\n")
        val xref = sb.length
        sb.append("xref\n0 6\n0000000000 65535 f \n")
        for (o in offsets) sb.append("${o.toString().padStart(10, '0')} 00000 n \n")
        sb.append("trailer\n<< /Size 6 /Root 1 0 R >>\nstartxref\n$xref\n%%EOF\n")
        return sb.toString().encodeToByteArray()
    }

    @Test
    fun rotated_page_quads_land_inside_the_rotated_display_box() {
        val page = KitePDF.open(rotatedPdf()).pages[0]
        assertEquals(792.0, page.displayWidth, "90-degree rotation swaps the display box")
        assertEquals(612.0, page.displayHeight)

        val kite = page.textContent()
        assertNotNull(kite)
        val line = kite.blocks.flatMap { it.lines }.single()
        assertEquals("Rotated hello world", line.text)
        assertTrue(
            line.bounds.left >= 0 && line.bounds.right <= 792.0 &&
                line.bounds.bottom >= 0 && line.bounds.top <= 612.0,
            "line bounds inside the rotated display box: ${line.bounds}",
        )

        val hits = page.search("hello")
        assertEquals(1, hits.size)
        for (q in hits.single().quads) {
            assertTrue(
                q.left >= 0 && q.right <= 792.0 && q.bottom >= 0 && q.top <= 612.0,
                "quad inside the rotated display box: $q",
            )
            assertTrue(q.width > 0 && q.height > 0)
        }
    }
}
