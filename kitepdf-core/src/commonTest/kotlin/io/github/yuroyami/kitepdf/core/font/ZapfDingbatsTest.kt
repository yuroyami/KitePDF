package io.github.yuroyami.kitepdf.core.font

import io.github.yuroyami.kitepdf.core.parser.IndirectResolver
import io.github.yuroyami.kitepdf.core.parser.PdfDictionary
import io.github.yuroyami.kitepdf.core.parser.PdfName
import io.github.yuroyami.kitepdf.core.parser.PdfObject
import kotlin.test.Test
import kotlin.test.assertEquals

/** The built-in encoding of ZapfDingbats (#304) and the Unicode of its glyph names (#305). */
class ZapfDingbatsTest {

    private val font = PdfFont.from(
        PdfDictionary(linkedMapOf<String, PdfObject>("Subtype" to PdfName("Type1"), "BaseFont" to PdfName("ZapfDingbats"))),
        IndirectResolver { null },
    )

    @Test
    fun codes_name_the_glyphs_of_the_afm() {
        // Codes 0x34, 0x80, 0x8D, 0xA1, 0xAB, 0xDE and 0xFE of the Dingbats AFM.
        val program = Standard14Fonts.program("ZapfDingbats")!!
        for ((code, name) in listOf(0x34 to "a20", 0x80 to "a89", 0x8D to "a96", 0xA1 to "a101", 0xAB to "a109", 0xDE to "a169", 0xFE to "a191")) {
            assertEquals(program.glyphIdForName(name), font.glyphIdForByte(code), "code 0x${code.toString(16)} should draw $name")
        }
    }

    @Test
    fun symbols_decode_to_their_unicode_values() {
        assertEquals("✔✕●■♠➞", font.decode(byteArrayOf(0x34, 0x35, 0x6C, 0x6E, 0xAB.toByte(), 0xDE.toByte())))
    }
}
