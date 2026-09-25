package io.github.yuroyami.kitepdf.core.font

import io.github.yuroyami.kitepdf.core.compression.Inflate
import io.github.yuroyami.kitepdf.core.parser.IndirectResolver
import io.github.yuroyami.kitepdf.core.parser.PdfDictionary
import io.github.yuroyami.kitepdf.core.parser.PdfInt
import io.github.yuroyami.kitepdf.core.parser.PdfName
import io.github.yuroyami.kitepdf.core.parser.PdfObject
import io.github.yuroyami.kitepdf.core.parser.PdfStream
import kotlin.io.encoding.Base64
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * A CFF charset names most glyphs by standard string, and the CFF specification defines
 * 391 of them (Adobe Technical Note 5176, Appendix A). The accented Latin letters sit
 * from SID 150 up.
 */
class CffStandardStringsTest {

    /** A real CFF program: Nimbus Sans, whose charset uses the standard strings. */
    private val nimbusSans: ByteArray =
        Inflate.decode(Base64.decode(Standard14FontData.chunks("NimbusSans-Regular")!!.joinToString("")))

    @Test
    fun standard_strings_above_149_name_their_glyphs() {
        val cff = CffFont.parse(nimbusSans)
        // SIDs 150, 170, 171, 207, 227 and 228 of Appendix A.
        for (name in listOf("onesuperior", "copyright", "Aacute", "eacute", "ydieresis", "zcaron")) {
            assertTrue(cff.glyphIdForName(name) > 0, "no glyph named $name")
        }
    }

    @Test
    fun an_embedded_type1c_font_draws_accented_letters() {
        val fontFile = PdfStream(PdfDictionary(linkedMapOf<String, PdfObject>("Subtype" to PdfName("Type1C"))), nimbusSans)
        val descriptor = PdfDictionary(
            linkedMapOf<String, PdfObject>("Type" to PdfName("FontDescriptor"), "Flags" to PdfInt(32), "FontFile3" to fontFile),
        )
        val font = PdfFont.from(
            PdfDictionary(
                linkedMapOf<String, PdfObject>(
                    "Subtype" to PdfName("Type1"),
                    "BaseFont" to PdfName("ABCDEF+NimbusSans-Regular"),
                    "Encoding" to PdfName("WinAnsiEncoding"),
                    "FontDescriptor" to descriptor,
                ),
            ),
            IndirectResolver { null },
        )
        assertTrue(font.hasEmbeddedOutlines)
        // é, ©, ° and ½ in WinAnsiEncoding.
        for (code in listOf(0xE9, 0xA9, 0xB0, 0xBD)) {
            val outline = assertNotNull(font.outlineForByte(code), "no outline for 0x${code.toString(16)}")
            assertFalse(outline.isEmpty(), "an empty outline for 0x${code.toString(16)}")
        }
    }
}
