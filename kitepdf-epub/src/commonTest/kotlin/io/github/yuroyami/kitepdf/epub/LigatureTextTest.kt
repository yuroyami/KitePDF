package io.github.yuroyami.kitepdf.epub

import io.github.yuroyami.kitepdf.core.render.RecordingCanvas
import kotlin.test.Test
import kotlin.test.assertEquals

/** A ligature draws several characters, and the text of the page keeps all of them (#314). */
class LigatureTextTest {

    @Test
    fun a_ligature_carries_the_text_of_every_character_it_draws() {
        val css = "@font-face{font-family:'L';src:url(f.ttf)}p{font-family:'L'}"
        val doc = EpubDocument.open(
            EpubFixtures.epub("<body><style>$css</style><p>fif</p></body>", listOf("OEBPS/f.ttf" to EpubFixtures.ligatureTtf())),
        )
        val page = doc.pages.first()
        val glyphs = RecordingCanvas().also { page.renderTo(it) }.calls
            .filterIsInstance<RecordingCanvas.Call.Glyphs>().flatMap { it.glyphs }
        assertEquals(listOf(3 to "fi", 1 to "f"), glyphs.map { it.gid to it.text }, "the fi ligature, then f")
        assertEquals("fif", page.textContent().blocks.single().lines.single().text)
    }
}
