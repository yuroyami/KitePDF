package io.github.yuroyami.kitepdf.epub

import io.github.yuroyami.kitepdf.core.font.TextGlyph
import io.github.yuroyami.kitepdf.core.render.RecordingCanvas
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Right-to-left text through the layout (#320, #321, #323): the levels of UAX #9 decide the
 * visual order of each line, and a bracket at a right-to-left level draws as its mirror.
 */
class BidiLayoutTest {

    private fun noto(name: String): ByteArray? {
        val rel = "mupdf-master/resources/fonts/noto/$name"
        var d: File? = File(System.getProperty("user.dir")).absoluteFile
        while (d != null && !File(d, rel).exists()) d = d.parentFile
        return d?.let { File(it, rel) }?.readBytes()
    }

    /** The glyphs of [body], in visual order, with Noto Sans Syriac as `S` and Noto Serif as `L`. */
    private fun glyphs(body: String): Pair<List<TextGlyph>, EmbeddedFace> {
        val syriac = noto("NotoSansSyriac-Regular.otf").orSkip("NotoSansSyriac-Regular.otf")
        val latin = noto("NotoSerif-Regular.otf").orSkip("NotoSerif-Regular.otf")
        val css = "@font-face{font-family:'S';src:url(s.otf)}@font-face{font-family:'L';src:url(l.otf)}p{font-family:'S','L'}"
        val doc = EpubDocument.open(
            EpubFixtures.epub("<body><style>$css</style>$body</body>", listOf("OEBPS/s.otf" to syriac, "OEBPS/l.otf" to latin)),
        )
        val glyphs = doc.pages.flatMap { page ->
            RecordingCanvas().also { page.renderTo(it) }.calls.filterIsInstance<RecordingCanvas.Call.Glyphs>()
        }.flatMap { it.glyphs }
        return glyphs to FontRegistry.face("s", bold = false, italic = false, syriac)!!
    }

    private fun List<TextGlyph>.characters() = map { it.text.codePointAt(0) }

    @Test
    fun a_syriac_word_lays_out_right_to_left() {
        val (glyphs, _) = glyphs("<p>ܐܒܓ</p>")
        // Gamal, beth, alaph from left to right: Alaph, the first letter, is on the right.
        assertEquals(listOf(0x0713, 0x0712, 0x0710), glyphs.characters())
    }

    @Test
    fun brackets_at_a_right_to_left_level_draw_as_their_mirrors() {
        val (glyphs, face) = glyphs("<p dir=\"rtl\">ܐ (ܒܓ) ܕ</p>")
        assertEquals(listOf(0x0715, ')'.code, 0x0713, 0x0712, '('.code, 0x0710), glyphs.characters())
        // The closing bracket is on the left of the pair, so it draws the glyph of `(`.
        assertEquals(face.gidFor('('.code), glyphs[1].gid)
        assertEquals(face.gidFor(')'.code), glyphs[4].gid)
    }

    @Test
    fun brackets_at_a_left_to_right_level_keep_their_glyphs() {
        // Rule N0 gives the brackets the direction of the text before them, so they stay left to right.
        val (glyphs, face) = glyphs("<p>ab (ܐܒ) cd</p>")
        assertEquals(listOf('a'.code, 'b'.code, '('.code, 0x0712, 0x0710, ')'.code, 'c'.code, 'd'.code), glyphs.characters())
        assertEquals(face.gidFor('('.code), glyphs[2].gid)
        assertEquals(face.gidFor(')'.code), glyphs[5].gid)
    }
}
