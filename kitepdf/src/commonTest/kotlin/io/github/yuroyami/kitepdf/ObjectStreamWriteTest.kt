package io.github.yuroyami.kitepdf

import io.github.yuroyami.kitepdf.writer.PdfBuilder
import io.github.yuroyami.kitepdf.writer.StandardFont
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Object-stream + cross-reference-stream output (PDF 1.5+ compact form) via
 * [io.github.yuroyami.kitepdf.writer.PdfEditor.saveRewritten].
 */
class ObjectStreamWriteTest {

    private fun base(): ByteArray = PdfBuilder()
        .setInfo(title = "Compact", author = "KitePDF")
        .page { text(StandardFont.Helvetica, 18.0, 72.0, 700.0, "Object stream page one") }
        .page { text(StandardFont.TimesRoman, 18.0, 72.0, 700.0, "Object stream page two") }
        .build()

    @Test fun rewrite_with_object_streams_round_trips() {
        val doc = KitePDF.open(base())
        val out = doc.edit().saveRewritten(useObjectStreams = true)

        val reopened = KitePDF.open(out)
        assertEquals(2, reopened.pageCount)
        assertContains(reopened.pages[0].extractText(), "page one")
        assertContains(reopened.pages[1].extractText(), "page two")
        assertEquals("Compact", reopened.info.title)
        // The output is driven by a cross-reference stream (trailer == the XRef dict).
        assertEquals("XRef", reopened.trailer.getName("Type"))
    }

    @Test fun object_stream_output_is_smaller_than_classic() {
        // Pack many tiny objects so ObjStm compression clearly wins.
        val builder = PdfBuilder()
        repeat(20) { i -> builder.page { text(StandardFont.Helvetica, 12.0, 72.0, 700.0, "Page $i body text") } }
        val src = builder.build()
        val doc = KitePDF.open(src)
        val classic = doc.edit().saveRewritten(useObjectStreams = false)
        val compact = doc.edit().saveRewritten(useObjectStreams = true)
        assertTrue(compact.size < classic.size, "compact=${compact.size} classic=${classic.size}")
        // And it still round-trips.
        assertEquals(20, KitePDF.open(compact).pageCount)
    }

    @Test fun classic_rewrite_still_works() {
        val out = KitePDF.open(base()).edit().saveRewritten(useObjectStreams = false)
        val reopened = KitePDF.open(out)
        assertEquals(2, reopened.pageCount)
        assertContains(reopened.pages[0].extractText(), "page one")
    }
}
