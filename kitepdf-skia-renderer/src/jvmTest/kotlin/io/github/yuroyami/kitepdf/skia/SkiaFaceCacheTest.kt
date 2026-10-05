package io.github.yuroyami.kitepdf.skia

import io.github.yuroyami.kitepdf.core.font.FontSpec
import io.github.yuroyami.kitepdf.core.font.KiteFontFamily
import org.jetbrains.skia.FontStyle
import kotlin.test.Test
import kotlin.test.assertSame

/**
 * The host face of a font is looked up once and kept. A lookup walks the candidate families
 * with a fontconfig match for each that the host lacks, a millisecond or more, and a page asks
 * for the face on every run of host text, so an Arabic page spent nine tenths of its time on it
 * (#590).
 */
class SkiaFaceCacheTest {

    @Test
    fun a_font_answers_the_face_it_answered_before() {
        for (family in KiteFontFamily.entries) {
            for (style in listOf(FontStyle.NORMAL, FontStyle.BOLD, FontStyle.ITALIC, FontStyle.BOLD_ITALIC)) {
                for (language in listOf(null, "ar", "ja")) {
                    val spec = FontSpec(family, bold = false, italic = false, language = language)
                    val first = SkiaSystemFonts.resolve(spec, style)
                    assertSame(first, SkiaSystemFonts.resolve(spec, style), "$family $style $language")
                }
            }
        }
    }
}
