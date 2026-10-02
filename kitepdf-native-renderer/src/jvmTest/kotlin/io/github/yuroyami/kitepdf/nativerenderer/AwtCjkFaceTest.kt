package io.github.yuroyami.kitepdf.nativerenderer

import io.github.yuroyami.kitepdf.core.font.FontSpec
import io.github.yuroyami.kitepdf.core.font.KiteFontFamily
import io.github.yuroyami.kitepdf.core.render.KitePath
import org.junit.Assume.assumeTrue
import java.awt.Font
import java.awt.GraphicsEnvironment
import java.awt.font.FontRenderContext
import java.awt.geom.PathIterator
import java.awt.image.BufferedImage
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * A font without a program of a CJK collection draws in a host face of its own language. The
 * logical Serif font of the JVM draws Han characters in the first CJK face it lists, a Chinese
 * Song face on macOS, so a Japanese Mincho font drew Chinese glyph forms (#472).
 */
class AwtCjkFaceTest {

    private val canvas = AwtCanvas(BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB).createGraphics())

    private val installed = GraphicsEnvironment.getLocalGraphicsEnvironment()
        .getAvailableFontFamilyNames(Locale.ENGLISH).toSet()

    /** The outline of [text] in [font] at 1000 units per em, y up, as [AwtCanvas.hostGlyphOutline] builds it. */
    private fun outline(font: Font, text: String): List<KitePath.Segment> {
        val b = KitePath.Builder()
        val c = DoubleArray(6)
        val it = font.deriveFont(1000f).createGlyphVector(FontRenderContext(null, true, true), text).outline.getPathIterator(null)
        while (!it.isDone) {
            when (it.currentSegment(c)) {
                PathIterator.SEG_MOVETO -> b.moveTo(c[0], -c[1])
                PathIterator.SEG_LINETO -> b.lineTo(c[0], -c[1])
                PathIterator.SEG_QUADTO -> b.quadTo(c[0], -c[1], c[2], -c[3])
                PathIterator.SEG_CUBICTO -> b.curveTo(c[0], -c[1], c[2], -c[3], c[4], -c[5])
                PathIterator.SEG_CLOSE -> b.close()
            }
            it.next()
        }
        return b.build().segments
    }

    @Test
    fun a_japanese_serif_font_draws_in_an_installed_mincho_face() {
        val spec = FontSpec(KiteFontFamily.Serif, bold = false, italic = false, name = "HiraMinPro-W3", language = "ja")
        val face = spec.hostFaces.firstOrNull { it in installed }
        assumeTrue("no Japanese Mincho face on this host, skipping.", face != null)
        // U+76F4 has a different form in Japanese and Chinese faces.
        assertEquals(outline(Font(checkNotNull(face), Font.PLAIN, 1), "直"), canvas.hostGlyphOutline("直", spec).segments, "drew in another face than $face")
    }

    @Test
    fun a_japanese_sans_serif_font_draws_in_an_installed_gothic_face() {
        val spec = FontSpec(KiteFontFamily.SansSerif, bold = false, italic = false, language = "ja")
        val face = spec.hostFaces.firstOrNull { it in installed }
        assumeTrue("no Japanese Gothic face on this host, skipping.", face != null)
        assertEquals(outline(Font(checkNotNull(face), Font.PLAIN, 1), "直"), canvas.hostGlyphOutline("直", spec).segments, "drew in another face than $face")
    }

    @Test
    fun a_font_without_a_language_keeps_the_logical_face() {
        val spec = FontSpec(KiteFontFamily.Serif, bold = false, italic = false, name = "Georgia")
        assertEquals(outline(Font(Font.SERIF, Font.PLAIN, 1), "Ag"), canvas.hostGlyphOutline("Ag", spec).segments)
    }

    @Test
    fun a_character_the_cjk_face_lacks_draws_in_the_logical_font() {
        val spec = FontSpec(KiteFontFamily.Serif, bold = false, italic = false, language = "ja")
        val face = spec.hostFaces.firstOrNull { it in installed }
        // U+FE12, the vertical ideographic full stop, which the IPA faces do not have.
        val stop = "\uFE12"
        assumeTrue(
            "no Japanese Mincho face on this host that lacks U+FE12, skipping.",
            face != null && Font(face, Font.PLAIN, 1).canDisplayUpTo(stop) != -1 && Font(Font.SERIF, Font.PLAIN, 1).canDisplayUpTo(stop) == -1,
        )
        assertEquals(outline(Font(Font.SERIF, Font.PLAIN, 1), stop), canvas.hostGlyphOutline(stop, spec).segments)
    }
}
