package io.github.yuroyami.kitepdf.skia

import io.github.yuroyami.kitepdf.core.font.FontSpec
import io.github.yuroyami.kitepdf.core.font.KiteFontFamily
import io.github.yuroyami.kitepdf.core.font.TextGlyph
import io.github.yuroyami.kitepdf.core.render.KiteMatrix
import io.github.yuroyami.kitepdf.core.render.RgbColor
import org.jetbrains.skia.Font
import org.jetbrains.skia.FontMgr
import org.jetbrains.skia.FontStyle
import org.jetbrains.skia.Surface
import org.junit.Assume.assumeTrue
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * A character the host face lacks draws in a host face that has it, whether or not the font
 * names a language. Only CJK languages took a fallback face, so the letters of every other
 * script the face lacked, such as Arabic in DejaVu Serif or Times, drew nothing (#587).
 */
class SkiaScriptFallbackTest {

    /** Dark pixels of [letters] drawn as host text in [spec]. */
    private fun ink(letters: List<String>, spec: FontSpec): Int {
        val surface = Surface.makeRasterN32Premul(300, 120)
        surface.canvas.clear(-1)
        val glyphs = letters.map { TextGlyph(0, 1, -1, it, 700.0, null, false) }
        SkiaCanvas(surface.canvas).drawGlyphs(glyphs, 60.0, 1000, false, spec, KiteMatrix(1.0, 0.0, 0.0, -1.0, 20.0, 80.0), RgbColor.BLACK)
        val pixels = surface.makeImageSnapshot().peekPixels() ?: error("no pixels")
        var dark = 0
        for (y in 0 until 120) for (x in 0 until 300) if ((pixels.getColor(x, y) and 0xFF) < 128) dark++
        return dark
    }

    @Test
    fun letters_the_face_lacks_draw_in_a_face_that_has_them() {
        val samples = mapOf(
            "Arabic" to listOf("ت", "ي", "ب"),
            "Thai" to listOf("ก", "ข", "ค"),
            "Devanagari" to listOf("क", "ख", "ग"),
        )
        var checked = 0
        for (family in KiteFontFamily.entries) {
            val spec = FontSpec(family, bold = false, italic = false)
            val face = checkNotNull(SkiaSystemFonts.resolve(spec, FontStyle.NORMAL))
            for ((script, letters) in samples) {
                val cp = letters.first().codePointAt(0)
                // Only a script this face lacks and another host face has shows the fallback.
                if (Font(face).getUTF32Glyph(cp) != 0.toShort()) continue
                if (FontMgr.default.matchFamilyStyleCharacter(null, FontStyle.NORMAL, null, cp) == null) continue
                assertTrue(ink(letters, spec) > 200, "$script in $family (${face.familyName}) drew nothing")
                checked++
            }
        }
        assumeTrue("every host face has these scripts or none does, skipping.", checked > 0)
    }
}
