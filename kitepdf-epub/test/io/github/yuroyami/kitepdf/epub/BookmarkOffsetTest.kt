package io.github.yuroyami.kitepdf.epub

import io.github.yuroyami.kitepdf.core.KiteLocation
import io.github.yuroyami.kitepdf.core.render.RecordingCanvas
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A reading position counts characters of the book's text, not glyphs. A ligature, an added
 * hyphen, a space dropped at a line break and a ruby reading move no offset, so a bookmark
 * restores the same passage after a change of font, width or hyphenation (#434).
 */
class BookmarkOffsetTest {

    private fun n(i: Int) = i.toString().padStart(4, '0')

    // Latin words: the book font joins every "fi" into one glyph, and "hyphenation" can split.
    // Blank lines longer than a page sit in the middle, so a page starts with them; line
    // breaks are not characters of the text.
    private val latinItems = (0 until 300).map { "fifi ${n(it)} hyphenation" }
    private val latinHtml = latinItems.take(150).joinToString(" ") + "<br/>".repeat(30) + latinItems.drop(150).joinToString(" ")
    private val latinText = latinItems.take(150).joinToString(" ") + latinItems.drop(150).joinToString(" ")

    // Ruby bases whose readings are not text of the page.
    private val rubyHtml = (0 until 100).joinToString("") { "<ruby>漢<rt>か</rt></ruby>${n(it)}" }
    private val rubyText = (0 until 100).joinToString("") { "漢${n(it)}" }

    // Preformatted text whose first characters draw nothing.
    private val preText = "  x"

    /** The chapter's text as the book holds it. */
    private val text = preText + latinText + rubyText

    private val css = "@font-face{font-family:'L';src:url(f.ttf)}p{font-family:'L';margin:0}"
    private val base = EpubDocument.open(
        EpubFixtures.epub(
            "<body><style>$css</style><pre>$preText</pre><p>$latinHtml</p><p>$rubyHtml</p></body>",
            listOf("OEBPS/f.ttf" to EpubFixtures.ligatureTtf()),
        ),
        EpubSettings(pageWidth = 200.0, pageHeight = 200.0, margin = 10.0),
    )

    private val layouts = listOf(
        "book font" to base,
        "monospace" to base.withSettings(base.settings.copy(fontFamily = ReaderFontFamily.MONOSPACE)),
        "narrow and hyphenated" to base.withSettings(base.settings.copy(pageWidth = 120.0, hyphenate = true)),
        "bigger font" to base.withSettings(base.settings.copy(fontSize = 17.0)),
    )

    /**
     * Where the text of each page starts in [text], or -1 for a page with no text. It walks every
     * line of the chapter in order: a line starts where the last one ended, after any spaces. The
     * chapter's first page starts at 0, spaces and all.
     */
    private fun pageStarts(doc: EpubDocument): List<Int> {
        var pos = 0
        return doc.pages.map { page ->
            var first = -1
            for (line in page.textContent().blocks.flatMap { it.lines }) {
                val t = line.text.removeSuffix("-") // the book has no hyphen, so this one was added
                val at = text.indexOf(t, pos)
                assertTrue(at >= pos && text.substring(pos, at).isBlank(), "the test lost its place at '$t': $at, expected $pos")
                if (first < 0) first = if (pos == 0) 0 else at
                pos = at + t.length
            }
            first
        }.also { assertEquals(text.length, pos, "the pages hold the whole text") }
    }

    private fun glyphTexts(doc: EpubDocument): List<String> = doc.pages.flatMap { page ->
        RecordingCanvas().also { page.renderTo(it) }.calls.filterIsInstance<RecordingCanvas.Call.Glyphs>().flatMap { c -> c.glyphs.map { it.text } }
    }

    @Test
    fun a_page_bookmark_is_where_the_page_text_starts_in_the_book() {
        // What the layouts must differ in, or the test proves nothing.
        assertTrue("fi" in glyphTexts(base), "the book font draws the fi ligature")
        assertTrue("fi" !in glyphTexts(layouts[1].second), "the monospace font draws no ligature")
        val narrow = layouts[2].second
        assertTrue(narrow.pages.any { p -> p.textContent().blocks.any { b -> b.lines.any { it.text.endsWith("-") } } }, "the narrow layout hyphenates")
        for ((name, doc) in layouts) {
            val starts = pageStarts(doc)
            assertTrue(starts.size > 5, "$name: a book of several pages")
            for ((page, start) in starts.withIndex()) {
                if (start < 0) continue
                assertEquals(start, doc.bookmarkOf(KiteLocation(0, page)).charOffset, "$name, page $page")
            }
        }
    }

    @Test
    fun a_bookmark_restores_the_page_that_holds_its_text_after_a_new_layout() {
        for ((name, doc) in layouts.drop(1)) {
            val starts = pageStarts(doc)
            for (page in base.pages.indices) {
                val mark = base.bookmarkOf(KiteLocation(0, page))
                val found = doc.locate(mark).page
                val next = starts.drop(found + 1).firstOrNull { it >= 0 } ?: text.length
                assertTrue(
                    mark.charOffset >= starts[found] && mark.charOffset < next,
                    "$name: the bookmark of page $page, at ${mark.charOffset}, restored page $found, which holds ${starts[found]} until $next",
                )
            }
        }
    }

    @Test
    fun a_bookmark_on_a_page_that_holds_only_an_image_restores_that_page() {
        // The image fills a page of its own between two runs of text; its character is a place too.
        val words = (0 until 60).joinToString(" ") { "word${n(it)}" }
        val img = """<img src="pic.bmp" style="width:150pt;height:170pt"/>"""
        val bodies = mapOf(
            "a paragraph of its own" to "<p>$words</p><p>$img</p><p>$words</p>",
            "inside a paragraph" to "<p>$words $img $words</p>",
        )
        for ((name, body) in bodies) {
            val doc = EpubDocument.open(
                EpubFixtures.epub(body, listOf("OEBPS/pic.bmp" to EpubFixtures.bmp2x1())),
                EpubSettings(pageWidth = 200.0, pageHeight = 200.0, margin = 10.0),
            )
            fun imagePage(d: EpubDocument): Int = d.pages.indexOfFirst { page ->
                val calls = RecordingCanvas().also { page.renderTo(it) }.calls
                calls.any { it is RecordingCanvas.Call.Image }.also { if (it) assertTrue(calls.none { c -> c is RecordingCanvas.Call.Glyphs }, "$name: the image has its page to itself") }
            }
            val mark = doc.bookmarkOf(KiteLocation(0, imagePage(doc)))
            assertTrue(imagePage(doc) > 0, "$name: text comes before the image page")
            for (other in listOf(doc.withFontSize(15.0), doc.withSettings(doc.settings.copy(fontFamily = ReaderFontFamily.MONOSPACE)))) {
                assertEquals(imagePage(other), other.locate(mark).page, "$name: the bookmark restores the page of the image")
            }
        }
    }
}
