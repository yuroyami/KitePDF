package io.github.yuroyami.kitepdf.epub

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * An overlay document may serve several chapters, and the clips of a chapter are those whose text
 * is in it (EPUB Media Overlays 3.3, 3.1). A chapter of a shared overlay keeps only its own clips,
 * so read-aloud reads each clip once (#522).
 */
class SharedOverlayTest {

    private fun par(id: String, text: String, begin: Int) =
        """<par id="$id"><text src="$text"/><audio src="../audio/a.mp3" clipBegin="${begin}s" clipEnd="${begin + 1}s"/></par>"""

    /** Three chapters; [overlays] names, for each, the id of its overlay item or null. */
    private fun book(smils: Map<String, String>, overlays: List<String?>): EpubDocument {
        val container = """<?xml version="1.0"?><container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container"><rootfiles><rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/></rootfiles></container>"""
        val chapters = overlays.withIndex().joinToString("") { (i, ov) ->
            """<item id="c${i + 1}" href="text/c${i + 1}.xhtml" media-type="application/xhtml+xml"${ov?.let { " media-overlay=\"$it\"" } ?: ""}/>"""
        }
        val smilItems = smils.keys.joinToString("") { """<item id="$it" href="smil/$it.smil" media-type="application/smil+xml"/>""" }
        val opf = """<?xml version="1.0"?><package xmlns="http://www.idpf.org/2007/opf" version="3.0">
            <metadata xmlns:dc="http://purl.org/dc/elements/1.1/"/>
            <manifest>$chapters$smilItems<item id="a" href="audio/a.mp3" media-type="audio/mpeg"/></manifest>
            <spine>${overlays.indices.joinToString("") { """<itemref idref="c${it + 1}"/>""" }}</spine></package>"""
        fun xhtml(n: Int) = """<?xml version="1.0"?><html xmlns="http://www.w3.org/1999/xhtml"><body><p id="a$n">One.</p><p id="b$n">Two.</p></body></html>""".encodeToByteArray()
        return EpubDocument.open(
            EpubFixtures.storedZip(
                listOf(
                    "mimetype" to "application/epub+zip".encodeToByteArray(),
                    "META-INF/container.xml" to container.encodeToByteArray(),
                    "OEBPS/content.opf" to opf.encodeToByteArray(),
                ) + overlays.indices.map { "OEBPS/text/c${it + 1}.xhtml" to xhtml(it + 1) } +
                    smils.map { (id, body) -> "OEBPS/smil/$id.smil" to """<?xml version="1.0"?><smil xmlns="http://www.w3.org/ns/SMIL" version="3.0"><body>$body</body></smil>""".encodeToByteArray() },
            ),
            EpubSettings(pageWidth = 300.0, pageHeight = 200.0, margin = 20.0),
        )
    }

    private fun ids(doc: EpubDocument, chapter: Int) = doc.mediaOverlayOf(chapter)?.clips?.map { it.id }

    @Test
    fun each_chapter_of_a_shared_overlay_keeps_the_clips_of_its_own_text() {
        val shared = par("p1", "../text/c1.xhtml#a1", 0) + par("p2", "../text/c1.xhtml#b1", 1) +
            par("p3", "../text/c2.xhtml#a2", 2) + par("p4", "../text/c3.xhtml#a3", 3) + par("p5", "../text/c3.xhtml#b3", 4)
        val doc = book(mapOf("ov" to shared), listOf("ov", "ov", "ov"))
        assertEquals(listOf("p1", "p2"), ids(doc, 0))
        assertEquals(listOf("p3"), ids(doc, 1))
        assertEquals(listOf("p4", "p5"), ids(doc, 2))
    }

    @Test
    fun an_overlay_of_one_chapter_keeps_every_clip() {
        // A clip whose text is in another chapter still reads, as it did, when no other chapter shares the overlay.
        val own = par("p1", "../text/c1.xhtml#a1", 0) + par("p2", "../text/c2.xhtml#a2", 1)
        val doc = book(mapOf("ov" to own), listOf("ov", null, null))
        assertEquals(listOf("p1", "p2"), ids(doc, 0))
    }
}
