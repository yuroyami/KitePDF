package io.github.yuroyami.kitepdf.epub

import io.github.yuroyami.kitepdf.core.KiteBookmark
import io.github.yuroyami.kitepdf.core.KiteLocation
import io.github.yuroyami.kitepdf.core.KiteTextRange
import io.github.yuroyami.kitepdf.epub.convert.BookPackager
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** A range of a book's text follows its words through a reflow, and is found again when the text changed (#944). */
class TextRangeTest {

    private val filler = (1..40).joinToString(" ") { "Filler sentence number $it keeps the chapter going for a while." }
    private val target = "The quick brown fox jumps over the lazy dog near the riverbank."

    private fun book(chapterOne: String = "<p>$filler</p><p>$target</p><p>$filler</p>", fontSize: Double = 12.0, width: Double = 300.0): EpubDocument {
        val b = BookPackager("Ranges")
        b.chapter(BookPackager.Chapter("one.xhtml", xhtml(chapterOne)))
        b.chapter(BookPackager.Chapter("two.xhtml", xhtml("<p>Second chapter starts here and goes on.</p><p>$filler</p>")))
        return EpubDocument.open(b.build(), EpubSettings(pageWidth = width, pageHeight = 300.0, fontSize = fontSize, margin = 20.0))
    }

    private fun xhtml(body: String) = """<?xml version="1.0" encoding="UTF-8"?><html xmlns="http://www.w3.org/1999/xhtml"><head><title>t</title></head><body>$body</body></html>"""

    private fun fold(s: String) = s.replace(Regex("\\s+"), " ").trim()

    /** The range of [needle] as a reader would select it on the page that shows its first word. */
    private fun select(doc: EpubDocument, needle: String): KiteTextRange {
        for (p in 0 until doc.pageCountIn(0)) {
            val location = KiteLocation(0, p)
            val text = doc.page(location).textContent()
            // The page's chars in reading order, a space at each line break that the index map skips.
            val flat = StringBuilder()
            val index = ArrayList<Int>()
            var line = -1
            for (block in text.blocks) for (l in block.lines) {
                line++
                if (flat.isNotEmpty() && flat.last() != ' ') { flat.append(' '); index.add(-1) }
                for (c in l.text) { flat.append(c); index.add(index.count { it >= 0 }) }
            }
            val at = flat.indexOf(needle)
            if (at >= 0) {
                val first = index.subList(at, at + needle.length).first { it >= 0 }
                val last = index.subList(at, at + needle.length).last { it >= 0 }
                return doc.textRangeOf(location, first, last)!!
            }
        }
        error("'$needle' is on no page")
    }

    private fun covered(doc: EpubDocument, range: KiteTextRange): String =
        doc.locate(range)!!.hits.joinToString(" ") { it.text }.let(::fold)

    @Test
    fun a_selection_becomes_a_range_of_its_words() {
        val doc = book()
        val range = select(doc, "brown fox jumps")
        assertEquals(0, range.start.chapter)
        assertEquals("brown fox jumps", range.text)
        // Paragraphs follow each other in the chapter's text with nothing between them.
        assertTrue(range.prefix.trimEnd().endsWith("for a while.The quick"), range.prefix)
        assertTrue(range.suffix.trimStart().startsWith("over the lazy"), range.suffix)
        assertEquals("brown fox jumps", covered(doc, range))
    }

    @Test
    fun the_range_covers_the_same_words_after_a_reflow() {
        val range = select(book(), target)
        for ((size, width) in listOf(12.0 to 300.0, 18.0 to 300.0, 18.0 to 200.0, 9.0 to 400.0)) {
            val doc = book(fontSize = size, width = width)
            val hits = doc.locate(range)!!
            assertFalse(hits.repaired, "at $size pt and $width wide the offsets still hold the text")
            assertEquals(fold(target), fold(hits.hits.joinToString(" ") { it.text }), "at $size pt and $width wide")
        }
    }

    @Test
    fun a_range_across_a_page_break_has_a_hit_on_each_page() {
        val doc = book(fontSize = 18.0, width = 200.0)
        // The whole first paragraph and the target: it runs over several pages.
        val start = KiteBookmark.Flow(0, 0)
        val end = select(doc, "near the riverbank.").end
        val range = doc.textRangeOf(start, end)!!
        val hits = doc.locate(range)!!.hits
        assertTrue(hits.size >= 2, "${hits.size} hits")
        assertEquals(hits.map { it.location!!.page }, hits.map { it.location!!.page }.distinct().sorted(), "one hit a page, in order")
        assertTrue(fold(hits.joinToString(" ") { it.text }).endsWith("near the riverbank."))
        assertTrue(fold(hits.joinToString(" ") { it.text }).startsWith("Filler sentence number 1 "))
    }

    @Test
    fun a_range_across_chapters_covers_both() {
        val doc = book()
        val range = doc.textRangeOf(select(doc, "for a while.").let { KiteBookmark.Flow(0, doc.textRangeOf(it.start, it.end)!!.start.charOffset) }, KiteBookmark.Flow(1, 6))!!
        val hits = doc.locate(range)!!.hits
        assertEquals(setOf(0, 1), hits.map { it.location!!.chapter }.toSet())
        assertTrue(fold(hits.last().text).endsWith("Second"), hits.last().text)
    }

    @Test
    fun a_range_survives_being_saved_as_text() {
        val range = select(book(), "jumps over the lazy dog")
        val saved = range.encode()
        val restored = KiteTextRange.decode(saved)
        assertEquals(range, restored)
        assertEquals("jumps over the lazy dog", covered(book(fontSize = 16.0), restored!!))
        val odd = KiteTextRange(KiteBookmark.Flow(1, 2), KiteBookmark.Flow(1, 9), "a;b%c ü", "x\ny", "")
        assertEquals(odd, KiteTextRange.decode(odd.encode()))
        assertNull(KiteTextRange.decode("kiterange:1;1;2"))
        assertNull(KiteTextRange.decode("not a range"))
    }

    @Test
    fun a_range_whose_text_moved_is_found_again_by_its_quote() {
        val range = select(book(), "brown fox jumps")
        // A new edition adds a paragraph before it, so every offset after it moves.
        val edited = book("<p>A new foreword that the first edition did not have.</p><p>$filler</p><p>$target</p><p>$filler</p>")
        val hits = edited.locate(range)!!
        assertTrue(hits.repaired)
        assertEquals("brown fox jumps", fold(hits.hits.joinToString(" ") { it.text }))
        assertTrue(hits.range.start.charOffset > range.start.charOffset)
        assertFalse(edited.locate(hits.range)!!.repaired, "the repaired range holds its text")
    }

    @Test
    fun a_range_whose_text_is_gone_is_lost_not_moved() {
        val range = select(book(), "brown fox jumps")
        val edited = book("<p>$filler</p><p>The slow grey cat sleeps under the porch.</p><p>$filler</p>")
        assertNull(edited.locate(range))
    }

    @Test
    fun the_context_picks_the_right_copy_of_a_repeated_quote() {
        val twice = "<p>$filler</p><p>Alpha says the word again.</p><p>Omega says the word again.</p>"
        val range = select(book(twice), "says the word")
        // The selection took the first copy, after "Alpha". Shift the text and look again.
        val edited = book("<p>Preface.</p>$twice")
        val hits = edited.locate(range)!!
        assertTrue(hits.repaired)
        assertTrue(hits.range.prefix.trimEnd().endsWith("Alpha"), hits.range.prefix)
    }

    @Test
    fun a_fixed_layout_document_has_no_ranges() {
        val pdfLike = object : io.github.yuroyami.kitepdf.core.KiteDocument {
            override val pageCount = 0
            override val pages = emptyList<io.github.yuroyami.kitepdf.core.KitePage>()
        }
        assertNull(pdfLike.textRangeOf(KiteLocation(0, 0), 0, 3))
        assertNotNull(select(book(), "lazy dog"))
    }
}
