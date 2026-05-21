package com.yuroyami.kitepdf

import com.yuroyami.kitepdf.content.ContentStreamParser
import com.yuroyami.kitepdf.writer.ContentStreamWriter
import com.yuroyami.kitepdf.writer.PdfBuilder
import com.yuroyami.kitepdf.writer.StandardFont
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Tests page-content editing: the [ContentStreamWriter] serializer (round-trips
 * through [ContentStreamParser], including inline images) and the
 * [com.yuroyami.kitepdf.writer.PdfEditor] page operations (stamp, remove text),
 * verified by re-opening through KitePDF.
 */
class ContentEditTest {

    /* ─── Content-stream round-trip ──────────────────────────────────────── */

    @Test fun operations_round_trip_through_serializer() {
        val src = "q 1 0 0 1 0 0 cm BT /F1 12 Tf 72 720 Td (Hello) Tj " +
            "[(A) -20 (B)] TJ ET 0.5 0.25 0.75 rg 10 10 100 50 re f Q"
        val ops = ContentStreamParser.parse(src.encodeToByteArray())
        val reparsed = ContentStreamParser.parse(ContentStreamWriter.serialize(ops))
        assertEquals(ops, reparsed)
        // Spot-check a couple of operators survived with operands intact.
        assertTrue(ops.any { it.operator == "Tj" })
        assertTrue(ops.any { it.operator == "re" && it.operands.size == 4 })
    }

    @Test fun inline_image_survives_round_trip() {
        val data = ByteArray(12) { it.toByte() } // 2x2 RGB @ 8bpc, no 0x20/'E'/'I'
        val content = "q\nBI /W 2 /H 2 /CS /RGB /BPC 8 ID ".encodeToByteArray() +
            data + " EI\nQ\n".encodeToByteArray()

        val ops = ContentStreamParser.parse(content)
        val bi = ops.firstOrNull { it.operator == "BI" }
        assertNotNull(bi, "inline image not captured as a BI operation")
        assertNotNull(bi.inlineImage, "inline image bytes not retained")

        // Re-serializing and re-parsing preserves the inline image exactly.
        val reparsed = ContentStreamParser.parse(ContentStreamWriter.serialize(ops))
        assertEquals(ops, reparsed)
    }

    /* ─── Page editing ───────────────────────────────────────────────────── */

    private fun onePagePdf(text: String): ByteArray =
        PdfBuilder().page { text(StandardFont.Helvetica, 18.0, 72.0, 700.0, text) }.build()

    @Test fun stamp_page_overlays_text_keeping_original() {
        val base = onePagePdf("Original body text")
        val doc = KitePDF.open(base)

        val out = doc.edit().apply {
            stampPage(doc.pages[0]) {
                setFillRgb(1.0, 0.0, 0.0)
                text(StandardFont.HelveticaBold, 36.0, 150.0, 400.0, "DRAFT")
            }
        }.saveIncremental()

        val reopened = KitePDF.open(out)
        assertEquals(1, reopened.pageCount)
        val pageText = reopened.pages[0].extractText()
        assertContains(pageText, "Original body text")
        assertContains(pageText, "DRAFT")
        // Original bytes preserved verbatim (incremental-update invariant).
        assertTrue(base.contentEquals(out.copyOf(base.size)))
    }

    @Test fun stamp_page_with_new_font_merges_resources() {
        val base = onePagePdf("Body in Helvetica")
        val doc = KitePDF.open(base)
        // Stamp uses Courier — a font not in the original page resources.
        val out = doc.edit().apply {
            stampPage(doc.pages[0]) { text(StandardFont.Courier, 10.0, 72.0, 100.0, "stamped courier") }
        }.saveIncremental()

        val reopened = KitePDF.open(out)
        val pageText = reopened.pages[0].extractText()
        assertContains(pageText, "Body in Helvetica")
        assertContains(pageText, "stamped courier")
    }

    @Test fun remove_all_text_strips_text_but_keeps_page() {
        val base = onePagePdf("Secret text to strip")
        val doc = KitePDF.open(base)

        val out = doc.edit().apply { removeAllText(doc.pages[0]) }.saveIncremental()

        val reopened = KitePDF.open(out)
        assertEquals(1, reopened.pageCount)
        assertFalse(reopened.pages[0].extractText().contains("Secret text"))
    }

    @Test fun edit_page_content_custom_transform() {
        val base = onePagePdf("keep me")
        val doc = KitePDF.open(base)
        // Identity transform must leave the text intact (proves parse→serialize
        // of a real page's content is faithful).
        val out = doc.edit().apply { editPageContent(doc.pages[0]) { it } }.saveIncremental()

        val reopened = KitePDF.open(out)
        assertContains(reopened.pages[0].extractText(), "keep me")
    }
}
