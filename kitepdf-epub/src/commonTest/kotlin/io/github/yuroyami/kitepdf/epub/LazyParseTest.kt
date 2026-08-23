package io.github.yuroyami.kitepdf.epub

import io.github.yuroyami.kitepdf.core.KiteLocation
import io.github.yuroyami.kitepdf.core.render.RecordingCanvas
import io.github.yuroyami.kitepdf.core.render.RgbColor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Opening a book reads the container, the OPF and the table of contents. Chapter
 * documents are read and parsed one at a time, when something asks for them, and
 * a stylesheet is parsed once per file however many chapters link it.
 */
class LazyParseTest {

    private val settings = EpubSettings(pageWidth = 400.0, pageHeight = 640.0)

    private val sheet = """
        body { margin: 0; font-size: 12px }
        p { margin-bottom: 6px; text-indent: 1em }
        h1 { font-size: 2em; margin: 12px 0 }
    """.trimIndent()

    private fun bodies(n: Int) = List(n) { c ->
        "<h1 id=\"top$c\">Chapter ${c + 1}</h1>" +
            (0 until 12).joinToString("") { "<p>Chapter ${c + 1} paragraph $it, long enough to wrap.</p>" }
    }

    private fun book(chapters: Int = 6) =
        EpubFixtures.epubFoldered(bodies(chapters), sheets = listOf("book.css" to sheet))

    private fun open(chapters: Int = 6) = EpubDocument.open(book(chapters), settings)

    private fun parsedChapters(doc: EpubDocument) =
        (0 until doc.chapterCount).filter { doc.isChapterParsed(it) }

    @Test
    fun opening_parses_no_chapter() {
        val doc = open()
        assertEquals(6, doc.chapterCount)
        assertEquals(emptyList(), parsedChapters(doc), "opening should not read any chapter")
        assertEquals(0, doc.stylesheetsParsed)
    }

    @Test
    fun metadata_and_navigation_parse_no_chapter() {
        val doc = EpubDocument.open(EpubFixtures.epubWithToc(chapters = 5), settings)
        doc.metadata
        doc.epubMetadata
        doc.tableOfContents
        doc.outline
        assertNotNull(doc.bookmarkOf("OEBPS/chapter4.xhtml#head3"))
        assertEquals(emptyList(), parsedChapters(doc), "the table of contents should not read chapters")
    }

    /**
     * Laying out chapter N reads chapter N, plus chapter 0: the writing mode and
     * the hyphenation language are one decision per book and both come from the
     * first chapter. That is two chapters, never the whole book.
     */
    @Test
    fun laying_out_one_chapter_reads_that_chapter_and_the_first() {
        val doc = open()
        doc.prepareChapter(4)
        assertEquals(listOf(0, 4), parsedChapters(doc))
        assertTrue(doc.isChapterReady(4))
        assertFalse(doc.isChapterReady(0), "chapter 0 was read, not laid out")
    }

    @Test
    fun a_shared_stylesheet_is_parsed_once() {
        val doc = open()
        for (c in 0 until doc.chapterCount) doc.prepareChapter(c)
        assertEquals(6, parsedChapters(doc).size)
        assertEquals(1, doc.stylesheetsParsed, "one file, one parse, six chapters linking it")
    }

    @Test
    fun each_stylesheet_is_parsed_once() {
        val doc = EpubDocument.open(
            EpubFixtures.epubFoldered(
                bodies(4),
                sheets = listOf("book.css" to sheet, "extra.css" to "em { font-style: italic }"),
            ),
            settings,
        )
        for (c in 0 until doc.chapterCount) doc.prepareChapter(c)
        assertEquals(2, doc.stylesheetsParsed)
    }

    private fun runsOf(doc: EpubDocument, chapter: Int = 0): List<RecordingCanvas.Call.Glyphs> {
        val canvas = RecordingCanvas()
        doc.page(KiteLocation(chapter, 0)).renderTo(canvas)
        return canvas.calls.filterIsInstance<RecordingCanvas.Call.Glyphs>()
    }

    /** The cascade must not change just because a sheet is now shared. */
    @Test
    fun a_cached_sheet_still_cascades_in_document_order() {
        val bytes = EpubFixtures.epubFoldered(
            listOf("<p>hello</p>"),
            sheets = listOf("a.css" to "p { color: #ff0000 }", "b.css" to "p { color: #0000ff }"),
        )
        val doc = EpubDocument.open(bytes, settings)
        // Same property, same specificity, so the later link wins. That only
        // holds if the cached rules land in the order the document links them.
        val run = runsOf(doc).first { "hello" in it.text }
        assertEquals(RgbColor(0.0, 0.0, 1.0), run.color, "the second sheet should have won")
        assertEquals(2, doc.stylesheetsParsed)
    }

    @Test
    fun an_import_inside_a_cached_sheet_still_resolves() {
        val bytes = EpubFixtures.epubFoldered(
            listOf("<p class=\"x\">hello</p>", "<p class=\"x\">hello</p>"),
            sheets = listOf("book.css" to "@import url(inner.css); p { margin: 0 }"),
            extraEntries = listOf("OEBPS/Styles/inner.css" to ".x { font-weight: bold }".encodeToByteArray()),
        )
        val doc = EpubDocument.open(bytes, settings)
        // The import has to survive the cache, for the second chapter as much
        // as the first: chapter 2 reads the sheet from the cache, not the zip.
        for (c in 0..1) {
            val run = runsOf(doc, c).first { "hello" in it.text }
            assertTrue(run.fontSpec.bold, "chapter ${c + 1} lost the imported rule")
        }
        assertEquals(1, doc.stylesheetsParsed)
    }

    /* ── which spine items survive ───────────────────────────────────────── */

    @Test
    fun a_spine_item_missing_from_the_zip_is_dropped() {
        val doc = EpubDocument.open(
            EpubFixtures.epubFoldered(bodies(3), missingSpineItems = listOf("ghost")),
            settings,
        )
        assertEquals(3, doc.chapterCount, "the manifest names four, the zip holds three")
    }

    @Test
    fun a_book_whose_spine_files_are_all_missing_still_throws() {
        val e = assertFailsWith<EpubFormatException> {
            EpubDocument.open(
                EpubFixtures.epubFoldered(emptyList(), missingSpineItems = listOf("ghost")),
                settings,
            )
        }
        assertTrue("no readable documents" in (e.message ?: ""), "message was: ${e.message}")
    }

    /* ── settings are not baked into the parse ───────────────────────────── */

    /**
     * A fixed-layout document with no `<meta name=viewport>` falls back to the
     * reader's page size. That fallback used to be frozen at the size the book
     * was first opened with, because the parse ran once and kept it.
     */
    @Test
    fun a_fixed_layout_fallback_follows_the_current_page_size() {
        val container = """<?xml version="1.0"?><container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container"><rootfiles><rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/></rootfiles></container>"""
        val opf = """<?xml version="1.0"?>
            <package xmlns="http://www.idpf.org/2007/opf" version="3.0" unique-identifier="uid"
                     prefix="rendition: http://www.idpf.org/vocab/rendition/#">
              <metadata xmlns:dc="http://purl.org/dc/elements/1.1/"><meta property="rendition:layout">pre-paginated</meta></metadata>
              <manifest><item id="c1" href="chapter1.xhtml" media-type="application/xhtml+xml"/></manifest>
              <spine><itemref idref="c1"/></spine>
            </package>"""
        val chapter = """<?xml version="1.0"?><html xmlns="http://www.w3.org/1999/xhtml"><body><p>fixed</p></body></html>"""
        val bytes = EpubFixtures.storedZip(
            listOf(
                "mimetype" to "application/epub+zip".encodeToByteArray(),
                "META-INF/container.xml" to container.encodeToByteArray(),
                "OEBPS/content.opf" to opf.encodeToByteArray(),
                "OEBPS/chapter1.xhtml" to chapter.encodeToByteArray(),
            ),
        )
        val small = EpubDocument.open(bytes, EpubSettings(pageWidth = 300.0, pageHeight = 500.0))
        assertTrue(small.isFixedLayout)
        assertEquals(300.0, small.page(KiteLocation(0, 0)).displayWidth)

        val big = small.withPageSize(800.0, 1000.0)
        assertEquals(800.0, big.page(KiteLocation(0, 0)).displayWidth, "the new size must reach the fallback")
    }

    /** A settings change shares the parse; it must not re-read a single chapter. */
    @Test
    fun a_settings_change_reuses_the_chapters_it_already_read() {
        val doc = open()
        doc.prepareChapter(2)
        val bigger = doc.withFontSize(16.0)
        assertTrue(bigger.isChapterParsed(2), "the parse is shared, so chapter 2 is still parsed")
        assertFalse(bigger.isChapterReady(2), "the layout is not shared, so it has to run again")
        assertEquals(doc.stylesheetsParsed, bigger.stylesheetsParsed)
    }
}
