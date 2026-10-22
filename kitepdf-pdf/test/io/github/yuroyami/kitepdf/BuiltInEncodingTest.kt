package io.github.yuroyami.kitepdf

import io.github.yuroyami.kitepdf.core.render.KiteMatrix
import io.github.yuroyami.kitepdf.core.render.RecordingCanvas
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * An embedded font without `/Encoding` uses its program's built-in encoding (ISO 32000-1,
 * Table 111). A TeX paper's math fonts are like this, and read as control characters and
 * draw the wrong glyphs without it (#469).
 */
class BuiltInEncodingTest {

    private fun hex(s: String) = ByteArray(s.length / 2) { s.substring(2 * it, 2 * it + 2).toInt(16).toByte() }

    /** A synthetic Type1C program with mu, period and braceleft at codes 22, 58 and 102. */
    private val program = hex(
        "010004010001010102540001010113c002bd8bf856f82405b00fb7108beb12bc1100000000000098000f005c0003163a" +
        "660004010104101c28f8880ef888bd16f824f75cfc24060ef888bd16f824f7c0fc24060ef888bd16f824f824fc24060e",
    )

    private fun pdf(encoding: String): ByteArray {
        val font = RawPdf.obj(
            6,
            "<< /Type /Font /Subtype /Type1 /BaseFont /ABCDEF+TestMath $encoding/FontDescriptor 7 0 R >>",
        )
        val descriptor = RawPdf.obj(
            7,
            "<< /Type /FontDescriptor /FontName /ABCDEF+TestMath /Flags 4 /FontBBox [0 0 500 800] " +
                "/ItalicAngle 0 /Ascent 800 /Descent -200 /CapHeight 700 /StemV 80 /FontFile3 8 0 R >>",
        )
        val fontFile = RawPdf.obj(8, "<< /Subtype /Type1C >>", program)
        return RawPdf.page(
            "BT /M 24 Tf 36 100 Td <163A66> Tj ET".encodeToByteArray(),
            resources = "<< /Font << /M 6 0 R >> >>",
            extra = listOf(font, descriptor, fontFile),
        )
    }

    private fun glyphs(pdf: ByteArray) = RecordingCanvas().also {
        KitePDF.open(pdf).pages[0].renderTo(it, KiteMatrix.IDENTITY)
    }.calls.filterIsInstance<RecordingCanvas.Call.Glyphs>()

    @Test
    fun a_font_without_encoding_reads_and_draws_through_its_program() {
        val pdf = pdf("")
        val page = KitePDF.open(pdf).pages[0]
        // mu reads as U+03BC or the micro sign U+00B5; both are the glyph name mu.
        val text = page.extractText().trim()
        assertTrue(text == "\u03BC.{" || text == "\u00B5.{", "the page text is [$text]")
        assertEquals(text, page.textContent().plainText.trim())
        val drawn = glyphs(pdf).flatMap { it.glyphs }
        assertEquals(listOf(1, 2, 3), drawn.map { it.gid }, "each code draws its own glyph")
        assertTrue(glyphs(pdf).all { it.hasOutlines }, "the embedded outlines draw, not a system font")
    }

    @Test
    fun differences_without_a_base_apply_to_the_built_in_encoding() {
        // ISO 32000-1, Table 114: for an embedded program the implicit base is its own encoding.
        val pdf = pdf("/Encoding << /Differences [58 /braceleft] >> ")
        assertEquals(listOf(1, 3, 3), glyphs(pdf).flatMap { it.glyphs }.map { it.gid })
    }
}
