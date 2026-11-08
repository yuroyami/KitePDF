package io.github.yuroyami.kitepdf.svg

import io.github.yuroyami.kitepdf.core.render.KiteMatrix
import io.github.yuroyami.kitepdf.core.render.KiteRasterCanvas
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * An SVG mask weighs its content with the luminanceToAlpha row of Filter Effects 1, 0.2125 R +
 * 0.7154 G + 0.0721 B, as CSS Masking 1, 7.1 asks, not with the PDF weights (#821).
 */
class SvgLuminanceMaskTest {

    @Test
    fun a_coloured_mask_fades_its_content_as_a_browser_does() {
        // 255 * (1 - mask) at the centre; the CSS weights give 201, 73 and 237, and Chrome draws 73 for green.
        for ((color, expected) in listOf("#ff0000" to 201, "#00ff00" to 73, "#0000ff" to 237)) {
            val image = assertNotNull(
                SvgImage.parse(
                    """<svg xmlns="http://www.w3.org/2000/svg" width="200" height="200" viewBox="0 0 200 200">
                      <rect width="200" height="200" fill="white"/>
                      <mask id="m" maskUnits="userSpaceOnUse" x="0" y="0" width="200" height="200">
                        <rect width="200" height="200" fill="$color"/>
                      </mask>
                      <rect width="200" height="200" fill="black" mask="url(#m)"/>
                    </svg>""".encodeToByteArray(),
                ),
            )
            val canvas = KiteRasterCanvas(200, 200)
            image.render(canvas, KiteMatrix.IDENTITY)
            val level = canvas.toRaster()[100, 100] shr 8 and 0xFF
            assertTrue(abs(level - expected) <= 1, "$color: centre is $level, expected $expected")
        }
    }
}
