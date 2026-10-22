package io.github.yuroyami.kitepdf.epub

import io.github.yuroyami.kitepdf.core.KiteLocation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A book's synchronised narration: the media overlay metadata, the clips of each chapter's
 * overlay, and where each clip's text is on the page (#36).
 */
class MediaOverlayTest {

    private val smil = """<?xml version="1.0"?>
        <smil xmlns="http://www.w3.org/ns/SMIL" xmlns:epub="http://www.idpf.org/2007/ops" version="3.0">
          <body>
            <seq epub:textref="../text/c1.xhtml" epub:type="bodymatter chapter">
              <par id="p1"><text src="../text/c1.xhtml#s1"/><audio src="../audio/a.mp3" clipBegin="0:00:00.000" clipEnd="0:00:01.200"/></par>
              <seq epub:type="footnote">
                <par id="p2"><text src="../text/c1.xhtml#s2"/><audio src="../audio/a.mp3" clipBegin="1.2s" clipEnd="2400ms"/></par>
              </seq>
              <par id="p3"><text src="../text/c1.xhtml#s3"/><audio src="../audio/a.mp3" clipBegin="00:02.4"/></par>
            </seq>
          </body>
        </smil>"""

    /** A book whose first chapter [body] has the overlay [smil], and a second chapter without one. */
    private fun book(body: String, metadata: String = ""): EpubDocument {
        val container = """<?xml version="1.0"?><container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container"><rootfiles><rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/></rootfiles></container>"""
        val opf = """<?xml version="1.0"?><package xmlns="http://www.idpf.org/2007/opf" version="3.0">
            <metadata xmlns:dc="http://purl.org/dc/elements/1.1/">$metadata</metadata>
            <manifest>
              <item id="c1" href="text/c1.xhtml" media-type="application/xhtml+xml" media-overlay="ov1"/>
              <item id="c2" href="text/c2.xhtml" media-type="application/xhtml+xml"/>
              <item id="ov1" href="smil/c1.smil" media-type="application/smil+xml"/>
              <item id="a" href="audio/a.mp3" media-type="audio/mpeg"/>
            </manifest>
            <spine><itemref idref="c1"/><itemref idref="c2"/></spine></package>"""
        fun xhtml(b: String) = """<?xml version="1.0"?><html xmlns="http://www.w3.org/1999/xhtml"><body>$b</body></html>""".encodeToByteArray()
        return EpubDocument.open(
            EpubFixtures.storedZip(
                listOf(
                    "mimetype" to "application/epub+zip".encodeToByteArray(),
                    "META-INF/container.xml" to container.encodeToByteArray(),
                    "OEBPS/content.opf" to opf.encodeToByteArray(),
                    "OEBPS/text/c1.xhtml" to xhtml(body),
                    "OEBPS/text/c2.xhtml" to xhtml("<p>No narration.</p>"),
                    "OEBPS/smil/c1.smil" to smil.encodeToByteArray(),
                ),
            ),
            EpubSettings(pageWidth = 300.0, pageHeight = 200.0, margin = 20.0),
        )
    }

    @Test
    fun the_package_gives_the_narration_metadata() {
        val doc = book(
            "<p>x</p>",
            """<meta property="media:duration">0:10:30.5</meta><meta property="media:duration" refines="#ov1">0:00:03</meta>
               <meta property="media:narrator">Anne</meta><meta property="media:narrator">Bob</meta>
               <meta property="media:active-class">-epub-media-overlay-active</meta>
               <meta property="media:playback-active-class">-epub-media-overlay-playing</meta>""",
        )
        val narration = doc.epubMetadata.narration
        assertEquals(630.5, narration.duration)
        assertEquals(listOf("Anne", "Bob"), narration.narrators)
        assertEquals("-epub-media-overlay-active", narration.activeClass)
        assertEquals("-epub-media-overlay-playing", narration.playbackActiveClass)
        assertEquals(3.0, doc.mediaOverlayOf(0)?.duration, "the duration that refines the overlay item")
    }

    @Test
    fun an_overlay_reads_its_clips_in_document_order_with_nested_sequences_flattened() {
        val doc = book("<p>x</p>")
        val clips = assertNotNull(doc.mediaOverlayOf(0)).clips
        assertEquals(listOf("p1", "p2", "p3"), clips.map { it.id })
        assertEquals(List(3) { "OEBPS/text/c1.xhtml#s${it + 1}" }, clips.map { it.textHref })
        assertEquals(List(3) { "OEBPS/audio/a.mp3" }, clips.map { it.audioHref })
        assertEquals(listOf(0.0, 1.2, 2.4), clips.map { it.clipBegin })
        assertEquals(listOf(1.2, 2.4, null), clips.map { it.clipEnd })
        // A clip takes the structural type of the nearest sequence around it.
        assertEquals(listOf("bodymatter chapter", "footnote", "bodymatter chapter"), clips.map { it.epubType })
        assertNull(doc.mediaOverlayOf(1), "a chapter without an overlay")
        assertTrue(!doc.isChapterReady(0), "reading the overlay laid the chapter out")
    }

    @Test
    fun clock_values_read_in_every_smil_form() {
        assertEquals(5025.5, SmilClock.seconds("1:23:45.5"))
        assertEquals(90.0, SmilClock.seconds("01:30"))
        assertEquals(3.5, SmilClock.seconds("3.5s"))
        assertEquals(0.25, SmilClock.seconds("250ms"))
        assertEquals(120.0, SmilClock.seconds("2min"))
        assertEquals(7200.0, SmilClock.seconds("2h"))
        assertEquals(12.0, SmilClock.seconds("12"))
        for (bad in listOf("", "abc", "1:2:3:4", "-1s", "5m")) assertNull(SmilClock.seconds(bad), bad)
    }

    @Test
    fun a_fragment_gives_its_page_and_one_rectangle_per_line() {
        val filler = (1..12).joinToString("") { "<p>Filler paragraph number $it.</p>" }
        val doc = book(
            """<p>Start <span id="s1">first sentence</span> here.</p>$filler""" +
                """<p id="s3">A later paragraph <span id="s2">with a sentence long enough that it has to wrap onto the next line of the page</span> end.</p>""" +
                """<p><img id="pic" src="missing.png" alt="x"/></p>""",
        )
        assertTrue(doc.pageCountIn(0) >= 2, "the fixture needs two pages")

        val first = assertNotNull(doc.locateFragment("OEBPS/text/c1.xhtml#s1"))
        assertEquals(KiteLocation(0, 0), first.location)
        val line = doc.page(first.location).textContent().blocks.flatMap { it.lines }.single { "first sentence" in it.text }
        val rect = first.rects.single()
        // The rectangle covers the words of the span, and not the words around it on the line.
        assertTrue(rect.left > line.bounds.left + 1 && rect.right < line.bounds.right - 1, "$rect in ${line.bounds}")
        assertEquals(line.bounds.bottom, rect.bottom, 0.5)

        val wrapped = assertNotNull(doc.locateFragment("OEBPS/text/c1.xhtml#s2"))
        assertTrue(wrapped.location.page >= 1, "the span is on a later page: ${wrapped.location}")
        assertEquals(2, wrapped.rects.size, "one rectangle per line of the span")
        assertTrue(wrapped.rects[1].bottom > wrapped.rects[0].bottom, "the second line is below the first")

        // An id on a block covers the block's lines, which include the span's.
        val block = assertNotNull(doc.locateFragment("OEBPS/text/c1.xhtml#s3"))
        assertEquals(wrapped.location, block.location)
        assertTrue(block.rects.size >= 2)

        // An element without text gives its page from the anchor map, and no rectangle.
        val picture = assertNotNull(doc.locateFragment("OEBPS/text/c1.xhtml#pic"))
        assertTrue(picture.rects.isEmpty())
        assertEquals(0, picture.location.chapter)

        // A span that ends inside a word covers its own letters only.
        val glued = book("""<p><span id="g">Ab</span>cdefghijklmnop</p>""")
        val part = assertNotNull(glued.locateFragment("OEBPS/text/c1.xhtml#g")).rects.single()
        val word = glued.page(KiteLocation(0, 0)).textContent().blocks.single().lines.single().bounds
        assertTrue(part.right - part.left < (word.right - word.left) / 3, "$part covers more than its letters of $word")

        assertNull(doc.locateFragment("OEBPS/text/c1.xhtml#nothing"))
        assertNull(doc.locateFragment("OEBPS/text/missing.xhtml#s1"))
        assertNull(doc.locateFragment("OEBPS/text/c1.xhtml"), "an href without a fragment names no element")
    }
}
