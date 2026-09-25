package io.github.yuroyami.kitepdf.epub

import io.github.yuroyami.kitepdf.core.render.RecordingCanvas
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals

/** A character outside the Basic Multilingual Plane draws from the font of the book (#319). */
class SupplementaryPlaneRenderTest {

    private fun noto(name: String): ByteArray? {
        val rel = "mupdf-master/resources/fonts/noto/$name"
        var d: File? = File(System.getProperty("user.dir")).absoluteFile
        while (d != null && !File(d, rel).exists()) d = d.parentFile
        return d?.let { File(it, rel) }?.readBytes()
    }

    @Test
    fun a_character_outside_the_bmp_is_one_glyph_of_the_font() {
        val otf = noto("NotoSansMath-Regular.otf").orSkip("NotoSansMath-Regular.otf")
        val css = "@font-face{font-family:'M';src:url(f.otf)}p{font-family:'M'}"
        val doc = EpubDocument.open(
            EpubFixtures.epub("<body><style>$css</style><p>&#x1D400;&#x1D401;x</p></body>", listOf("OEBPS/f.otf" to otf)),
        )
        val glyphs = doc.pages.flatMap { page ->
            RecordingCanvas().also { page.renderTo(it) }.calls.filterIsInstance<RecordingCanvas.Call.Glyphs>()
        }.flatMap { it.glyphs }
        // The cmap of the font maps U+1D400 to glyph 73 and U+1D401 to glyph 84.
        assertEquals(listOf(73, 84, 2606), glyphs.map { it.gid })
        // Each glyph carries its whole character, so the text of the page is the text of the book.
        assertEquals(listOf(0x1D400, 0x1D401, 'x'.code), glyphs.map { it.text.codePointAt(0) })
        assertEquals(listOf(2, 2, 1), glyphs.map { it.text.length })
    }
}
