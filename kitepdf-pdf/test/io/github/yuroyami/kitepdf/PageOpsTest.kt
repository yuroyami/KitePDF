package io.github.yuroyami.kitepdf

import io.github.yuroyami.kitepdf.writer.PdfBuilder
import io.github.yuroyami.kitepdf.writer.StandardFont
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/**
 * Structural page operations: rotate, delete, reorder, insert, and cross-document
 * merge/graft via [io.github.yuroyami.kitepdf.writer.PdfEditor].
 */
class PageOpsTest {

    private fun doc(vararg labels: String): ByteArray {
        val b = PdfBuilder()
        for (l in labels) b.page { text(StandardFont.Helvetica, 18.0, 72.0, 700.0, l) }
        return b.build()
    }

    @Test fun rotate_page_sets_rotation() {
        val d = KitePDF.open(doc("Alpha"))
        val out = d.edit().apply { rotatePage(d.pages[0], 90) }.saveIncremental()
        assertEquals(90, KitePDF.open(out).pages[0].rotation)
    }

    @Test fun remove_page_drops_it() {
        val d = KitePDF.open(doc("One", "Two", "Three"))
        val out = d.edit().apply { removePage(d.pages[1]) }.saveRewritten()
        val r = KitePDF.open(out)
        assertEquals(2, r.pageCount)
        assertContains(r.pages[0].extractText(), "One")
        assertContains(r.pages[1].extractText(), "Three")
        assertFalse(r.pages.any { it.extractText().contains("Two") })
    }

    @Test fun reorder_pages_via_setPageOrder() {
        val d = KitePDF.open(doc("One", "Two", "Three"))
        val refs = d.pages.map { it.reference!! }
        val out = d.edit().apply { setPageOrder(listOf(refs[2], refs[0], refs[1])) }.saveIncremental()
        val r = KitePDF.open(out)
        assertEquals(3, r.pageCount)
        assertContains(r.pages[0].extractText(), "Three")
        assertContains(r.pages[1].extractText(), "One")
        assertContains(r.pages[2].extractText(), "Two")
    }

    @Test fun merge_appends_pages_from_another_document() {
        val a = KitePDF.open(doc("DocA-P1", "DocA-P2"))
        val b = KitePDF.open(doc("DocB-P1"))
        val out = a.edit().apply { mergeDocument(b) }.saveIncremental()
        val r = KitePDF.open(out)
        assertEquals(3, r.pageCount)
        assertContains(r.pages[0].extractText(), "DocA-P1")
        assertContains(r.pages[1].extractText(), "DocA-P2")
        assertContains(r.pages[2].extractText(), "DocB-P1")
    }

    @Test fun append_single_grafted_page() {
        val a = KitePDF.open(doc("Base"))
        val b = KitePDF.open(doc("Imported"))
        val out = a.edit().apply { appendPage(b, 0) }.saveRewritten(useObjectStreams = true)
        val r = KitePDF.open(out)
        assertEquals(2, r.pageCount)
        assertContains(r.pages[0].extractText(), "Base")
        assertContains(r.pages[1].extractText(), "Imported")
    }

    @Test fun insert_grafted_page_at_front() {
        val a = KitePDF.open(doc("Existing"))
        val b = KitePDF.open(doc("Inserted"))
        val editor = a.edit()
        val ref = editor.graftPage(b, 0)
        editor.insertPageAt(0, ref)
        val r = KitePDF.open(editor.saveIncremental())
        assertEquals(2, r.pageCount)
        assertContains(r.pages[0].extractText(), "Inserted")
        assertContains(r.pages[1].extractText(), "Existing")
    }
}
