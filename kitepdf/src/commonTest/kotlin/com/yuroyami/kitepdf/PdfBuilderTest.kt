package com.yuroyami.kitepdf

import com.yuroyami.kitepdf.writer.PdfBuilder
import com.yuroyami.kitepdf.writer.StandardFont
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Tests the from-scratch full writer ([PdfBuilder]): documents it creates must
 * re-open through KitePDF's own reader with the expected pages, sizes, text,
 * and metadata — both compressed and uncompressed.
 */
class PdfBuilderTest {

    @Test fun creates_single_page_pdf_with_text() {
        val bytes = PdfBuilder()
            .page { text(StandardFont.Helvetica, 24.0, 72.0, 700.0, "Hello from PdfBuilder") }
            .build()

        val doc = KitePDF.open(bytes)
        assertEquals("1.7", doc.version)
        assertEquals(1, doc.pageCount)
        assertEquals(612.0, doc.pages[0].width)
        assertEquals(792.0, doc.pages[0].height)
        assertContains(doc.pages[0].extractText(), "Hello from PdfBuilder")
    }

    @Test fun creates_multi_page_pdf_in_order() {
        val bytes = PdfBuilder()
            .page { text(StandardFont.Helvetica, 18.0, 72.0, 700.0, "Page one content") }
            .page { text(StandardFont.TimesRoman, 18.0, 72.0, 700.0, "Page two content") }
            .page { text(StandardFont.Courier, 18.0, 72.0, 700.0, "Page three content") }
            .build()

        val doc = KitePDF.open(bytes)
        assertEquals(3, doc.pageCount)
        assertContains(doc.pages[0].extractText(), "Page one")
        assertContains(doc.pages[1].extractText(), "Page two")
        assertContains(doc.pages[2].extractText(), "Page three")
    }

    @Test fun sets_document_info() {
        val bytes = PdfBuilder()
            .setInfo(title = "Built Title", author = "KitePDF", producer = "KitePDF Writer")
            .page { text(StandardFont.Helvetica, 12.0, 72.0, 700.0, "x") }
            .build()

        val doc = KitePDF.open(bytes)
        assertEquals("Built Title", doc.info.title)
        assertEquals("KitePDF", doc.info.author)
        assertEquals("KitePDF Writer", doc.info.producer)
    }

    @Test fun uncompressed_build_round_trips() {
        val bytes = PdfBuilder()
            .page { text(StandardFont.Helvetica, 14.0, 72.0, 700.0, "Uncompressed content stream") }
            .build(compress = false)

        val doc = KitePDF.open(bytes)
        assertEquals(1, doc.pageCount)
        assertContains(doc.pages[0].extractText(), "Uncompressed content stream")
    }

    @Test fun multiple_fonts_on_one_page_each_render() {
        val bytes = PdfBuilder()
            .page {
                text(StandardFont.Helvetica, 14.0, 72.0, 700.0, "Helvetica line")
                text(StandardFont.TimesBold, 14.0, 72.0, 680.0, "Times bold line")
            }
            .build()

        val doc = KitePDF.open(bytes)
        val text = doc.pages[0].extractText()
        assertContains(text, "Helvetica line")
        assertContains(text, "Times bold line")
    }

    @Test fun custom_page_size_is_preserved() {
        val bytes = PdfBuilder()
            .page(width = 300.0, height = 400.0) {
                text(StandardFont.Helvetica, 10.0, 20.0, 350.0, "small page")
            }
            .build()

        val doc = KitePDF.open(bytes)
        assertEquals(300.0, doc.pages[0].width)
        assertEquals(400.0, doc.pages[0].height)
    }

    @Test fun vector_only_page_is_valid_with_no_text() {
        // A page that draws a filled rectangle and uses no fonts at all.
        val bytes = PdfBuilder()
            .page {
                setFillRgb(0.2, 0.4, 0.8)
                rectangle(100.0, 100.0, 200.0, 150.0)
                fill()
            }
            .build()

        val doc = KitePDF.open(bytes)
        assertEquals(1, doc.pageCount)
        assertTrue(doc.pages[0].extractText().isBlank())
    }

    @Test fun build_without_pages_fails() {
        assertFailsWith<IllegalArgumentException> { PdfBuilder().build() }
    }
}
