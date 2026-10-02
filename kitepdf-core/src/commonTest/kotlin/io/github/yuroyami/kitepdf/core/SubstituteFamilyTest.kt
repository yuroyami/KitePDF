package io.github.yuroyami.kitepdf.core

import io.github.yuroyami.kitepdf.core.font.FontSpec
import io.github.yuroyami.kitepdf.core.font.KiteCjkScript
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
import kotlin.test.assertTrue

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
    private fun japan1(name: String, flags: Int?): PdfFont = cidFont(name, flags, "Japan1")

    /** An unembedded CIDFont of the Adobe collection [ordering], or with no CIDSystemInfo when null. */
    private fun cidFont(name: String, flags: Int?, ordering: String?, encoding: String = "Identity-V"): PdfFont {
        val cidFont = dict(
            "Type" to PdfName("Font"), "Subtype" to PdfName("CIDFontType0"), "BaseFont" to PdfName(name),
            *(
                if (ordering == null) emptyArray() else arrayOf(
                    "CIDSystemInfo" to dict(
                        "Registry" to PdfString("Adobe".encodeToByteArray()),
                        "Ordering" to PdfString(ordering.encodeToByteArray()),
                        "Supplement" to PdfInt(5),
                    ),
                )
            ),
            "FontDescriptor" to descriptor(name, flags),
        )
        return PdfFont.from(
            dict(
                "Type" to PdfName("Font"), "Subtype" to PdfName("Type0"), "BaseFont" to PdfName("$name-$encoding"),
                "Encoding" to PdfName(encoding), "DescendantFonts" to PdfArray(listOf(cidFont)),
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

    @Test
    fun the_character_collection_names_the_language() {
        assertEquals("ja", japan1("HiraMinPro-W3", 6).fontSpec.language)
        assertEquals("zh-Hans", cidFont("STSong-Light", 6, "GB1").fontSpec.language)
        assertEquals("zh-Hant", cidFont("MSung-Light", 6, "CNS1").fontSpec.language)
        assertEquals("ko", cidFont("HYSMyeongJo-Medium", 6, "Korea1").fontSpec.language)
        assertEquals(null, cidFont("Unknown", 6, "Identity").fontSpec.language)
        assertEquals(null, trueType("Georgia", 34).fontSpec.language)
    }

    @Test
    fun a_predefined_cmap_names_the_language_without_a_cid_system_info() {
        // ISO 32000-1, 9.10.2: the collection of a predefined CMap is the font's collection.
        assertEquals("ja", cidFont("Ryumin-Light", null, null, encoding = "UniJIS-UCS2-H").fontSpec.language)
        assertEquals("ko", cidFont("HYGoThic-Medium", null, null, encoding = "UniKS-UCS2-H").fontSpec.language)
    }

    @Test
    fun a_japanese_serif_font_lists_mincho_faces_and_a_sans_serif_one_gothic_faces() {
        val serif = japan1("HiraMinPro-W3", 6).fontSpec
        assertEquals(KiteCjkScript.Japanese, serif.cjkScript)
        assertEquals("Hiragino Mincho ProN", serif.hostFaces.first())
        assertTrue("IPAMincho" in serif.hostFaces && "IPAGothic" !in serif.hostFaces, serif.hostFaces.toString())
        val sans = japan1("HiraKakuPro-W3", 4).fontSpec
        assertTrue("IPAGothic" in sans.hostFaces && "IPAMincho" !in sans.hostFaces, sans.hostFaces.toString())
    }

    @Test
    fun the_script_comes_from_the_language_and_region_subtags() {
        fun script(tag: String?) = FontSpec(KiteFontFamily.Serif, bold = false, italic = false, language = tag).cjkScript
        assertEquals(KiteCjkScript.Japanese, script("ja-JP"))
        assertEquals(KiteCjkScript.Korean, script("ko"))
        assertEquals(KiteCjkScript.SimplifiedChinese, script("zh"))
        assertEquals(KiteCjkScript.SimplifiedChinese, script("zh-Hans-CN"))
        assertEquals(KiteCjkScript.TraditionalChinese, script("zh-Hant"))
        assertEquals(KiteCjkScript.TraditionalChinese, script("zh-TW"))
        assertEquals(KiteCjkScript.TraditionalChinese, script("zh_HK"))
        assertEquals(null, script("en-US"))
        assertEquals(null, script(null))
    }

    @Test
    fun a_font_outside_cjk_lists_no_faces_and_no_sample() {
        val spec = FontSpec(KiteFontFamily.Serif, bold = false, italic = false, language = "fr")
        assertTrue(spec.hostFaces.isEmpty())
        assertEquals(null, spec.languageSample)
    }

    @Test
    fun each_sample_is_a_character_of_its_own_script() {
        fun sample(tag: String) = FontSpec(KiteFontFamily.Serif, bold = false, italic = false, language = tag).languageSample
        assertEquals(0x3042, sample("ja"))   // Hiragana A
        assertEquals(0xD55C, sample("ko"))   // Hangul HAN
        assertEquals(0x8FD9, sample("zh"))   // the Simplified form of "this"
        assertEquals(0x9019, sample("zh-TW")) // the Traditional form of the same word
    }
}
