package io.github.yuroyami.kitepdf.epub

import io.github.yuroyami.kitepdf.core.KiteLocation
import io.github.yuroyami.kitepdf.core.render.RecordingCanvas
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A font file is parsed once per book, however many `@font-face` rules name
 * it. A converter that repeats the book's font in every chapter's own `<style>`
 * gave the book one parsed font per chapter, each with its own copy of the
 * bytes and its own glyph caches, and no budget counted them (#224).
 */
class SharedFontProgramTest {

    private val settings = EpubSettings(pageWidth = 400.0, pageHeight = 640.0)

    private val face = "@font-face{font-family:'Embedded';src:url(../Fonts/font.ttf)}"

    private fun outlineRuns(doc: EpubDocument, chapter: Int): List<RecordingCanvas.Call.Glyphs> {
        val canvas = RecordingCanvas()
        doc.page(KiteLocation(chapter, 0)).renderTo(canvas)
        return canvas.calls.filterIsInstance<RecordingCanvas.Call.Glyphs>().filter { it.hasOutlines }
    }

    private fun book(chapters: Int, sheet: String, font: ByteArray = EpubFixtures.squareTtf()): EpubDocument =
        EpubDocument.open(
            EpubFixtures.epubFoldered(
                bodies = List(chapters) { "<style>$face</style><p class=\"e\">AAA</p>" },
                sheets = listOf("book.css" to sheet),
                extraEntries = listOf("OEBPS/Fonts/font.ttf" to font),
            ),
            settings,
        )

    @Test
    fun a_font_declared_in_every_chapter_is_parsed_once() {
        val doc = book(chapters = 4, sheet = ".e{font-family:'Embedded'}")
        for (c in 0 until doc.chapterCount) doc.prepareChapter(c)
        assertEquals(1, doc.fontFilesParsed, "one file, one parse, four chapters declaring it")
        for (c in 0 until doc.chapterCount) {
            assertTrue(outlineRuns(doc, c).isNotEmpty(), "chapter $c still draws with the shared face")
        }
    }

    @Test
    fun a_stylesheet_face_and_an_inline_face_for_one_file_share_the_parse() {
        // The sheet sits in Styles/, the chapters in Text/: two urls, one zip path.
        val doc = book(chapters = 3, sheet = "$face .e{font-family:'Embedded'}")
        for (c in 0 until doc.chapterCount) doc.prepareChapter(c)
        assertEquals(1, doc.fontFilesParsed)
        assertTrue(outlineRuns(doc, 2).isNotEmpty())
    }

    @Test
    fun a_font_file_that_will_not_parse_is_tried_once() {
        val doc = book(chapters = 3, sheet = ".e{font-family:'Embedded'}", font = "not a font".encodeToByteArray())
        for (c in 0 until doc.chapterCount) doc.prepareChapter(c)
        assertEquals(1, doc.fontFilesParsed, "a failed parse is remembered, not repeated per chapter")
        assertTrue(outlineRuns(doc, 0).isEmpty(), "the text falls back to the generic face")
    }
}
