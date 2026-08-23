package io.github.yuroyami.kitepdf.epub

import io.github.yuroyami.kitepdf.core.KiteLocation
import io.github.yuroyami.kitepdf.core.render.RecordingCanvas
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Where embedded fonts come from now that chapters parse lazily: `@font-face` in
 * a stylesheet belongs to the whole book, `@font-face` in a document's own inline
 * `<style>` belongs to that document. On the JVM so it can read the in-repo
 * `DroidSansFallback.ttf`.
 */
class LazyFontScopeTest {

    private val settings = EpubSettings(pageWidth = 400.0, pageHeight = 640.0)

    private fun droidSans(): ByteArray? {
        val rel = "mupdf-master/resources/fonts/droid/DroidSansFallback.ttf"
        var d: File? = File(System.getProperty("user.dir")).absoluteFile
        while (d != null && !File(d, rel).exists()) d = d.parentFile
        return d?.let { File(it, rel) }?.takeIf { it.exists() }?.readBytes()
    }

    private fun outlineRuns(doc: EpubDocument, chapter: Int): List<RecordingCanvas.Call.Glyphs> {
        val canvas = RecordingCanvas()
        doc.page(KiteLocation(chapter, 0)).renderTo(canvas)
        return canvas.calls.filterIsInstance<RecordingCanvas.Call.Glyphs>().filter { it.hasOutlines }
    }

    /**
     * A `url()` in a stylesheet resolves against that stylesheet's folder, which
     * is what CSS says. Here the sheet sits one level above the documents, so
     * resolving against the document's folder would miss the file entirely.
     */
    @Test
    fun a_font_url_resolves_against_its_own_stylesheet() {
        val ttf = droidSans() ?: return
        val container = """<?xml version="1.0"?><container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container"><rootfiles><rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/></rootfiles></container>"""
        val opf = """<?xml version="1.0"?>
            <package xmlns="http://www.idpf.org/2007/opf" version="3.0" unique-identifier="uid">
              <manifest>
                <item id="c1" href="Text/chapter1.xhtml" media-type="application/xhtml+xml"/>
                <item id="css" href="book.css" media-type="text/css"/>
                <item id="f" href="Fonts/font.ttf" media-type="font/ttf"/>
              </manifest>
              <spine><itemref idref="c1"/></spine>
            </package>"""
        // Sheet at OEBPS/, font at OEBPS/Fonts/, document at OEBPS/Text/.
        val css = "@font-face{font-family:'Embedded';src:url(Fonts/font.ttf)}p{font-family:'Embedded'}"
        val chapter = """<?xml version="1.0"?><html xmlns="http://www.w3.org/1999/xhtml">
            <head><link rel="stylesheet" href="../book.css"/></head><body><p>中文字</p></body></html>"""
        val bytes = EpubFixtures.storedZip(
            listOf(
                "mimetype" to "application/epub+zip".encodeToByteArray(),
                "META-INF/container.xml" to container.encodeToByteArray(),
                "OEBPS/content.opf" to opf.encodeToByteArray(),
                "OEBPS/book.css" to css.encodeToByteArray(),
                "OEBPS/Fonts/font.ttf" to ttf,
                "OEBPS/Text/chapter1.xhtml" to chapter.encodeToByteArray(),
            ),
        )
        val doc = EpubDocument.open(bytes, settings)
        assertTrue(outlineRuns(doc, 0).isNotEmpty(), "the embedded face should have been found and used")
    }

    /**
     * An inline `<style>` styles its own document, so the `@font-face` in it is
     * that document's. A chapter that asks for the family without declaring it
     * falls back, exactly as it would in a browser.
     */
    @Test
    fun an_inline_font_face_stays_in_its_own_chapter() {
        val ttf = droidSans() ?: return
        val face = "@font-face{font-family:'Embedded';src:url(../Fonts/font.ttf)}"
        val bytes = EpubFixtures.epubFoldered(
            bodies = listOf(
                "<style>$face</style><p>中文字</p>",
                "<p>中文字</p>",
            ),
            sheets = listOf("book.css" to "p{font-family:'Embedded'}"),
            extraEntries = listOf("OEBPS/Fonts/font.ttf" to ttf),
        )
        val doc = EpubDocument.open(bytes, settings)
        assertTrue(outlineRuns(doc, 0).isNotEmpty(), "chapter 1 declares the face, so it draws with it")
        assertTrue(outlineRuns(doc, 1).isEmpty(), "chapter 2 declares nothing, so it falls back")
    }

    /** Order does not change it: preparing the plain chapter first gives the same answer. */
    @Test
    fun the_scope_does_not_depend_on_layout_order() {
        val ttf = droidSans() ?: return
        val face = "@font-face{font-family:'Embedded';src:url(../Fonts/font.ttf)}"
        val bytes = EpubFixtures.epubFoldered(
            bodies = listOf("<style>$face</style><p>中文字</p>", "<p>中文字</p>"),
            sheets = listOf("book.css" to "p{font-family:'Embedded'}"),
            extraEntries = listOf("OEBPS/Fonts/font.ttf" to ttf),
        )
        val forward = EpubDocument.open(bytes, settings)
        forward.prepareChapter(0)
        val backward = EpubDocument.open(bytes, settings)
        backward.prepareChapter(1)

        assertEquals(outlineRuns(forward, 1).size, outlineRuns(backward, 1).size)
        assertEquals(outlineRuns(forward, 0).size, outlineRuns(backward, 0).size)
        assertFalse(outlineRuns(backward, 0).isEmpty())
    }

    /** A stylesheet face is the whole book's, whichever chapter is laid out first. */
    @Test
    fun a_stylesheet_face_reaches_every_chapter() {
        val ttf = droidSans() ?: return
        val css = "@font-face{font-family:'Embedded';src:url(../Fonts/font.ttf)}p{font-family:'Embedded'}"
        val bytes = EpubFixtures.epubFoldered(
            bodies = listOf("<p>中文字</p>", "<p>中文字</p>", "<p>中文字</p>"),
            sheets = listOf("book.css" to css),
            extraEntries = listOf("OEBPS/Fonts/font.ttf" to ttf),
        )
        val doc = EpubDocument.open(bytes, settings)
        for (c in 2 downTo 0) assertTrue(outlineRuns(doc, c).isNotEmpty(), "chapter $c missed the face")
    }
}
