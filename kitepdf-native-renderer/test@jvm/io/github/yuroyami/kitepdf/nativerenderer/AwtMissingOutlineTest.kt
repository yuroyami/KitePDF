package io.github.yuroyami.kitepdf.nativerenderer

import io.github.yuroyami.kitepdf.core.font.FontSpec
import io.github.yuroyami.kitepdf.core.font.KiteFontFamily
import io.github.yuroyami.kitepdf.core.font.TextGlyph
import io.github.yuroyami.kitepdf.core.render.KiteBlendMode
import io.github.yuroyami.kitepdf.core.render.KiteMatrix
import io.github.yuroyami.kitepdf.core.render.RgbColor
import java.awt.Color
import java.awt.image.BufferedImage
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * A glyph that an embedded program gives no outline draws nothing on AWT, as on every canvas: a
 * host face in its place drew "A" and hid a font that KitePDF could not read (ISO 32000-1, 9.9; #853).
 */
class AwtMissingOutlineTest {

    @Test
    fun a_run_of_an_embedded_font_with_no_outline_draws_nothing() {
        val image = BufferedImage(100, 80, BufferedImage.TYPE_INT_RGB)
        val g = image.createGraphics()
        g.color = Color.WHITE
        g.fillRect(0, 0, 100, 80)
        val glyph = TextGlyph(0, 1, 36, "A", 600.0, null, false, 0.0, 0.0, 0.0)
        AwtCanvas(g).drawGlyphs(
            listOf(glyph), 48.0, 1000, true, FontSpec(KiteFontFamily.SansSerif, false, false, "", null),
            KiteMatrix(1.0, 0.0, 0.0, -1.0, 10.0, 60.0), RgbColor.BLACK, 1.0, KiteBlendMode.Normal,
        )
        g.dispose()
        var inked = 0
        for (y in 0 until 80) for (x in 0 until 100) if (image.getRGB(x, y) and 0xFFFFFF != 0xFFFFFF) inked++
        assertEquals(0, inked, "pixels a host face inked")
    }
}
