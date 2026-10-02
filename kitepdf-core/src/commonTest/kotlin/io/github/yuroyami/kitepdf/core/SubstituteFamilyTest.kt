package io.github.yuroyami.kitepdf.core

import io.github.yuroyami.kitepdf.core.font.KiteFontFamily
import io.github.yuroyami.kitepdf.core.font.PdfFont
import io.github.yuroyami.kitepdf.core.parser.IndirectResolver
import io.github.yuroyami.kitepdf.core.parser.PdfArray
import io.github.yuroyami.kitepdf.core.parser.PdfDictionary
import io.github.yuroyami.kitepdf.core.parser.PdfInt
import io.github.yuroyami.kitepdf.core.parser.PdfName
import io.github.yuroyami.kitepdf.core.parser.PdfObject
import io.github.yuroyami.kitepdf.core.parser.PdfString
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * A font without a program draws in a host face of the family [PdfFont.fontSpec] picks. The
 * Serif flag of the font descriptor picks a serif face (ISO 32000-1, 9.8.2, Table 123, #472).
 */
class SubstituteFamilyTest {

    private val refs = IndirectResolver { null }

    private fun dict(vararg entries: Pair<String, PdfObject>) = PdfDictionary(linkedMapOf(*entries))

    private fun descriptor(name: String, flags: Int?) = dict(
        "Type" to PdfName("FontDescriptor"), "FontName" to PdfName(name),
        *(if (flags != null) arrayOf("Flags" to PdfInt(flags.toLong())) else emptyArray()),
    )

    /** An unembedded Adobe-Japan1 CIDFont under an Identity-V Type 0 font, as the corpus page has it. */
    private fun japan1(name: String, flags: Int?): PdfFont {
        val cidFont = dict(
            "Type" to PdfName("Font"), "Subtype" to PdfName("CIDFontType0"), "BaseFont" to PdfName(name),
            "CIDSystemInfo" to dict(
                "Registry" to PdfString("Adobe".encodeToByteArray()),
                "Ordering" to PdfString("Japan1".encodeToByteArray()),
                "Supplement" to PdfInt(5),
            ),
            "FontDescriptor" to descriptor(name, flags),
        )
        return PdfFont.from(
            dict(
                "Type" to PdfName("Font"), "Subtype" to PdfName("Type0"), "BaseFont" to PdfName("$name-Identity-V"),
                "Encoding" to PdfName("Identity-V"), "DescendantFonts" to PdfArray(listOf(cidFont)),
            ),
            refs,
        )
    }

    /** An unembedded simple TrueType font. */
    private fun trueType(name: String, flags: Int?): PdfFont = PdfFont.from(
        dict(
            "Type" to PdfName("Font"), "Subtype" to PdfName("TrueType"), "BaseFont" to PdfName(name),
            "Encoding" to PdfName("WinAnsiEncoding"), "FontDescriptor" to descriptor(name, flags),
        ),
        refs,
    )

    @Test
    fun a_mincho_font_with_the_serif_flag_draws_serif() {
        // The corpus page: HiraMinPro-W3 with /Flags 6, the Serif and Symbolic bits.
        assertEquals(KiteFontFamily.Serif, japan1("HiraMinPro-W3", 6).fontSpec.family)
    }

    @Test
    fun the_serif_flag_picks_serif_whatever_the_name() {
        assertEquals(KiteFontFamily.Serif, japan1("ABCDEF-W3", 6).fontSpec.family)
        assertEquals(KiteFontFamily.SansSerif, japan1("ABCDEF-W3", 4).fontSpec.family)
        assertEquals(KiteFontFamily.Serif, trueType("Georgia", 34).fontSpec.family)
        assertEquals(KiteFontFamily.SansSerif, trueType("Verdana", 32).fontSpec.family)
    }

    @Test
    fun a_japanese_mincho_name_draws_serif_without_the_flag() {
        for (name in listOf("HiraMinProN-W3", "Ryumin-Light", "KozMinPro-Regular", "MS-Mincho")) {
            assertEquals(KiteFontFamily.Serif, japan1(name, null).fontSpec.family, name)
        }
        for (name in listOf("HiraKakuProN-W3", "KozGoPro-Regular", "MS-Gothic")) {
            assertEquals(KiteFontFamily.SansSerif, japan1(name, null).fontSpec.family, name)
        }
    }

    @Test
    fun a_courier_name_stays_monospace_under_the_serif_flag() {
        assertEquals(KiteFontFamily.Monospace, trueType("CourierNewPSMT", 35).fontSpec.family)
    }
}
