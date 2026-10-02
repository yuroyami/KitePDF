package io.github.yuroyami.kitepdf

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * A simple font selects glyphs with one-byte codes (ISO 32000-1, 9.6.1), so its ToUnicode CMap is
 * read one byte at a time, even when the CMap wrongly declares a two-byte codespace (#466).
 */
class SimpleFontToUnicodeTest {

    /** Helvetica with WinAnsiEncoding and a ToUnicode CMap holding [cmapBody], showing [text]. */
    private fun pdf(cmapBody: String, text: String): ByteArray {
        val font = RawPdf.obj(
            6,
            "<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica /Encoding /WinAnsiEncoding /ToUnicode 7 0 R >>",
        )
        val toUnicode = RawPdf.obj(
            7,
            "<< >>",
            ("/CIDInit /ProcSet findresource begin 12 dict begin begincmap $cmapBody endcmap end end").encodeToByteArray(),
        )
        return RawPdf.page(
            "BT /H 12 Tf 72 700 Td ($text) Tj ET".encodeToByteArray(),
            resources = "<< /Font << /H 6 0 R >> >>",
            extra = listOf(font, toUnicode),
        )
    }

    private fun hex(c: Char) = c.code.toString(16).padStart(2, '0').uppercase()

    /** One-byte `bfchar` entries that map each of [chars] to itself. */
    private fun identity(chars: String): String =
        "${chars.length} beginbfchar " + chars.map { "<${hex(it)}> <00${hex(it)}>" }.joinToString(" ") + " endbfchar"

    private fun plainText(pdf: ByteArray) = KitePDF.open(pdf).pages[0].extractText().trim()

    private fun structuredText(pdf: ByteArray) = KitePDF.open(pdf).pages[0].textContent().plainText.trim()

    @Test
    fun a_two_byte_codespace_does_not_join_two_codes_of_a_simple_font() {
        val pdf = pdf("1 begincodespacerange <0000> <FFFF> endcodespacerange ${identity("lesonan ")}", "lesson lanes")
        assertEquals("lesson lanes", plainText(pdf))
        assertEquals("lesson lanes", structuredText(pdf))
    }

    @Test
    fun a_code_the_cmap_lacks_falls_back_to_the_encoding() {
        // ISO 32000-1, 9.10.2: without a mapping, the encoding gives the character.
        val pdf = pdf("1 begincodespacerange <00> <FF> endcodespacerange ${identity("lso ")}", "lesson lanes")
        assertEquals("lesson lanes", plainText(pdf))
        assertEquals("lesson lanes", structuredText(pdf))
    }
}
