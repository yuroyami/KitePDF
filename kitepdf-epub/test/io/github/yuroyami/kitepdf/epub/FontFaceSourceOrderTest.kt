package io.github.yuroyami.kitepdf.epub

import io.github.yuroyami.kitepdf.core.render.RecordingCanvas
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** `src` is a prioritized list, tried in its own order until a source loads (CSS Fonts 4, 4.3). */
class FontFaceSourceOrderTest {

    private fun run(src: String): RecordingCanvas.Call.Glyphs {
        val body = """
            <style>@font-face{font-family:X;src:$src}</style>
            <p style="font-family:X">AAA</p>
        """.trimIndent()
        val files = listOf(
            "OEBPS/square.ttf" to EpubFixtures.squareTtf(),
            "OEBPS/other.woff" to EpubFixtures.ligatureTtf(),
            "OEBPS/broken.ttf" to "no font".encodeToByteArray(),
        )
        val canvas = RecordingCanvas()
        EpubDocument.open(EpubFixtures.epub(body, files)).pages[0].renderTo(canvas)
        return canvas.calls.filterIsInstance<RecordingCanvas.Call.Glyphs>().single()
    }

    private fun advances(g: RecordingCanvas.Call.Glyphs) = g.unitsPerEm to g.glyphs.map { it.advanceWidth }

    @Test
    fun aMissingOrBrokenFirstSourceYieldsToTheNext() {
        // #880
        val square = run("url(square.ttf)")
        assertTrue(square.hasOutlines)
        for (src in listOf("url(missing.ttf), url(square.ttf)", "url(broken.ttf), url(square.ttf)")) {
            val g = run(src)
            assertTrue(g.hasOutlines, src)
            assertEquals(advances(square), advances(g), src)
        }
    }

    @Test
    fun theFirstSourceWinsWhateverItsExtension() {
        // #882: a .woff named first comes before a .ttf named second.
        val other = run("url(other.woff)")
        val square = run("url(square.ttf)")
        kotlin.test.assertNotEquals(advances(other), advances(square), "the fixtures differ")
        assertEquals(advances(other), advances(run("url(other.woff), url(square.ttf)")))
    }
}
