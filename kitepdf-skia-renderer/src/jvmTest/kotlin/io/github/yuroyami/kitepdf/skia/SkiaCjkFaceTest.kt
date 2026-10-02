package io.github.yuroyami.kitepdf.skia

import io.github.yuroyami.kitepdf.core.font.FontSpec
import io.github.yuroyami.kitepdf.core.font.KiteFontFamily
import org.jetbrains.skia.FontMgr
import org.jetbrains.skia.FontStyle
import org.jetbrains.skia.Surface
import org.junit.Assume.assumeTrue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * A font without a program of a CJK collection draws in a host face of its own language. The
 * Latin faces of the Skia lists have no Han, kana or Hangul glyphs, and Skia draws glyph 0 for
 * a character its typeface lacks, so such text drew nothing (#472).
 */
class SkiaCjkFaceTest {

    private fun spec(family: KiteFontFamily, language: String?) =
        FontSpec(family, bold = false, italic = false, language = language)

    @Test
    fun a_japanese_font_takes_the_first_named_face_the_host_has() {
        for (family in KiteFontFamily.entries) {
            val spec = spec(family, "ja")
            val face = spec.hostFaces.firstOrNull { FontMgr.default.matchFamilyStyle(it, FontStyle.NORMAL) != null }
            assumeTrue("no named Japanese face on this host, skipping.", face != null)
            assertEquals(face, SkiaSystemFonts.resolve(spec, FontStyle.NORMAL)?.familyName, "$family")
        }
    }

    @Test
    fun a_japanese_font_draws_its_kana() {
        val spec = spec(KiteFontFamily.Serif, "ja")
        val sample = checkNotNull(spec.languageSample)
        assumeTrue(
            "no face on this host draws kana, skipping.",
            FontMgr.default.matchFamilyStyleCharacter("serif", FontStyle.NORMAL, arrayOf("ja"), sample) != null,
        )
        val typeface = assertNotNull(SkiaSystemFonts.resolve(spec, FontStyle.NORMAL))
        assertTrue(typeface.getUTF32Glyph(sample) != 0.toShort(), "${typeface.familyName} has no glyph for U+3042")
    }

    @Test
    fun a_font_without_a_language_keeps_the_latin_face() {
        for (family in KiteFontFamily.entries) {
            assertEquals(
                SkiaSystemFonts.resolve(family, FontStyle.NORMAL)?.familyName,
                SkiaSystemFonts.resolve(spec(family, null), FontStyle.NORMAL)?.familyName,
            )
        }
    }

    @Test
    fun a_character_the_cjk_face_lacks_draws_in_another_face_of_its_language() {
        val spec = spec(KiteFontFamily.Serif, "ja")
        // U+FE12, the vertical ideographic full stop, which some Japanese faces do not have.
        val stop = 0xFE12
        val face = assertNotNull(SkiaSystemFonts.resolve(spec, FontStyle.NORMAL))
        assumeTrue(
            "the Japanese face of this host has U+FE12, or no face does, skipping.",
            face.getUTF32Glyph(stop) == 0.toShort() &&
                FontMgr.default.matchFamilyStyleCharacter("serif", FontStyle.NORMAL, arrayOf("ja"), stop) != null,
        )
        val canvas = SkiaCanvas(Surface.makeRasterN32Premul(10, 10).canvas)
        val outline = assertNotNull(canvas.hostGlyphOutline(String(Character.toChars(stop)), spec))
        assertTrue(outline.segments.size > 4, "U+FE12 drew as an empty glyph: ${outline.segments}")
    }
}
