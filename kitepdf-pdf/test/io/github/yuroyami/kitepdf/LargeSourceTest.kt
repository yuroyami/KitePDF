package io.github.yuroyami.kitepdf

import io.github.yuroyami.kitepdf.core.KiteRawApi
import io.github.yuroyami.kitepdf.core.PdfFormatException
import io.github.yuroyami.kitepdf.core.parser.PdfReference
import io.github.yuroyami.kitepdf.core.parser.PdfStream
import io.github.yuroyami.kitepdf.core.render.KiteMatrix
import io.github.yuroyami.kitepdf.core.render.RecordingCanvas
import io.github.yuroyami.kitepdf.parser.XrefEntry
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * A PDF of more than 2 GiB opens from a source, whose zeros it makes as they are read, so the
 * test needs neither the memory nor the disk (#906). ISO 32000-1, 7.5.4 gives an offset ten
 * digits, and 7.5.8.3 lets a cross-reference stream's offset field be as wide as /W says.
 */
@OptIn(KiteRawApi::class)
class LargeSourceTest {

    @Test
    fun a_classic_table_past_2_gib_opens_and_its_page_renders() {
        val pdf = LargePdf(LargePdf.PAST_2_GIB)
        assertTrue(pdf.contentOffset > Int.MAX_VALUE && pdf.classicXrefOffset > Int.MAX_VALUE)
        val doc = PdfDocument.open(pdf.source())
        assertEquals(1, doc.pageCount)
        val content = assertIs<XrefEntry.InUse>(doc.xref[6L])
        assertEquals(pdf.contentOffset, content.offset)
        @Suppress("DEPRECATION")
        assertFailsWith<IllegalStateException> { content.byteOffset }
        assertTrue(LargePdf.FIRST_TEXT in doc.pages[0].extractText())
        val canvas = RecordingCanvas()
        doc.pages[0].renderTo(canvas, KiteMatrix.IDENTITY)
        assertTrue(canvas.calls.filterIsInstance<RecordingCanvas.Call.Glyphs>().any { LargePdf.FIRST_TEXT in it.text })
    }

    @Test
    fun an_xref_stream_and_its_prev_past_2_gib_merge() {
        val pdf = LargePdf(LargePdf.PAST_2_GIB, revised = true)
        val doc = PdfDocument.open(pdf.source())
        assertEquals(1, doc.pageCount)
        // The xref stream's rows name the new content, and /Prev the table that names the rest.
        assertTrue(assertIs<XrefEntry.InUse>(doc.xref[7L]).offset > (1L shl 31))
        assertEquals(pdf.classicXrefOffset, doc.trailer.getInt("Prev"))
        val text = doc.pages[0].extractText()
        assertTrue(LargePdf.REVISED_TEXT in text, text)
        assertTrue(LargePdf.FIRST_TEXT !in text, text)
    }

    @Test
    fun the_large_stream_keeps_its_place_and_fails_only_when_its_body_is_read() {
        val doc = PdfDocument.open(LargePdf(LargePdf.PAST_2_GIB).source())
        val stream = assertIs<PdfStream>(doc.resolve(PdfReference(5, 0)))
        assertEquals(LargePdf.PAST_2_GIB, stream.dict.getInt("Length"))
        assertFailsWith<PdfFormatException> { stream.rawBytes }
    }

    @Test
    fun a_save_of_a_document_larger_than_an_array_fails_clearly() {
        val doc = PdfDocument.open(LargePdf(LargePdf.PAST_2_GIB).source())
        assertFailsWith<UnsupportedOperationException> { doc.edit().saveIncremental() }
        assertFailsWith<UnsupportedOperationException> { doc.edit().saveRewritten() }
        assertFailsWith<UnsupportedOperationException> { doc.bytes }
    }

    @Test
    fun a_small_file_from_a_source_still_saves() {
        val doc = PdfDocument.open(LargePdf(1000).source())
        assertTrue(LargePdf.FIRST_TEXT in doc.pages[0].extractText())
        val saved = PdfDocument.open(doc.edit().saveIncremental())
        assertTrue(LargePdf.FIRST_TEXT in saved.pages[0].extractText())
    }
}
