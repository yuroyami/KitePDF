package io.github.yuroyami.kitepdf.nativerenderer

import io.github.yuroyami.kitepdf.KitePDF
import io.github.yuroyami.kitepdf.writer.PdfBuilder
import io.github.yuroyami.kitepdf.writer.StandardFont
import java.awt.image.BufferedImage
import kotlin.test.Test
import kotlin.test.assertTrue

/** Outlined text in a standard font, which has no embedded program, on AWT (#85). */
class AwtOutlinedTextTest {

    private fun render(ops: String): BufferedImage {
        val bytes = PdfBuilder().page {
            beginText()
            setFont(StandardFont.Helvetica, 90.0)
            raw(ops)
            moveText(60.0, 400.0)
            showText("HELLO")
            endText()
        }.build()
        return AwtPdfRasterizer.renderToImage(KitePDF.open(bytes).pages[0])
    }

    private fun count(img: BufferedImage, test: (r: Int, g: Int, b: Int) -> Boolean): Int {
        var n = 0
        for (y in 0 until img.height) for (x in 0 until img.width) {
            val p = img.getRGB(x, y)
            if (test((p shr 16) and 255, (p shr 8) and 255, p and 255)) n++
        }
        return n
    }

    private fun inked(img: BufferedImage) = count(img) { r, g, b -> r + g + b < 3 * 160 }

    @Test
    fun stroke_mode_draws_the_outline_of_the_substitute_face() {
        val filled = inked(render("0 Tr"))
        val outlined = inked(render("1 Tr 1.5 w"))
        assertTrue(outlined > 500, "the outlined word paints: $outlined pixels")
        assertTrue(outlined < filled * 3 / 4, "an outline inks less than the filled word: $outlined against $filled")
    }

    @Test
    fun fill_then_stroke_draws_the_stroke_colour() {
        val red = count(render("2 Tr 2 w 1 0 0 RG")) { r, g, b -> r > 150 && g < 100 && b < 100 }
        assertTrue(red > 300, "the red outline paints: $red pixels")
    }
}
