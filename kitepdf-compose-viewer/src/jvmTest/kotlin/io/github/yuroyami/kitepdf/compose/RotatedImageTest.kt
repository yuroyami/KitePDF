package io.github.yuroyami.kitepdf.compose

import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.font.createFontFamilyResolver
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import io.github.yuroyami.kitepdf.core.render.KiteImageData
import io.github.yuroyami.kitepdf.core.render.KiteMatrix
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A bitmap's full CTM must reach the pixels: rotation, reflection and shear,
 * not just the scale magnitudes (ledger D-3, the Compose sub-item).
 */
class RotatedImageTest {

    private val side = 40

    /** 2x1 24-bit BMP, red pixel then blue, decoded by core into a RAW image. */
    private fun rawRedBlue2x1(): KiteImageData {
        val header = ByteArray(54)
        header[0] = 'B'.code.toByte(); header[1] = 'M'.code.toByte()
        fun le32(o: Int, v: Int) { var s = 0; var i = o; while (s < 32) { header[i++] = ((v ushr s) and 0xFF).toByte(); s += 8 } }
        fun le16(o: Int, v: Int) { header[o] = (v and 0xFF).toByte(); header[o + 1] = ((v ushr 8) and 0xFF).toByte() }
        le32(2, 62); le32(10, 54); le32(14, 40); le32(18, 2); le32(22, 1)
        le16(26, 1); le16(28, 24); le32(34, 8)
        val bmp = header + byteArrayOf(0, 0, 0xFF.toByte(), 0xFF.toByte(), 0, 0, 0, 0)
        val image = KiteImageData.fromEncodedImage(bmp)!!
        assertEquals(KiteImageData.Kind.RAW, image.kind)
        return image
    }

    private fun render(ctm: KiteMatrix): ImageBitmap {
        val bmp = ImageBitmap(side, side)
        val s = side.toFloat()
        CanvasDrawScope().draw(Density(1f), LayoutDirection.Ltr, Canvas(bmp), Size(s, s)) {
            val canvas = ComposeCanvas(
                this,
                TextMeasurer(createFontFamilyResolver(), Density(1f), LayoutDirection.Ltr),
            )
            canvas.drawImage(rawRedBlue2x1(), ctm)
        }
        return bmp
    }

    @Test
    fun an_unrotated_image_lands_red_left_blue_right() {
        val d = side.toDouble()
        val map = render(KiteMatrix(d, 0.0, 0.0, -d, 0.0, d)).toPixelMap()
        val left = map[side / 4, side / 2]
        val right = map[3 * side / 4, side / 2]
        assertTrue(left.red > 0.8f && left.blue < 0.25f, "left red, got $left")
        assertTrue(right.blue > 0.8f && right.red < 0.25f, "right blue, got $right")
    }

    @Test
    fun a_rotated_image_rotates_its_pixels() {
        // The upright mapping, then a 90 degree device-space rotation
        // ((x, y) maps to (side - y, x)): red moves from the left half to the
        // TOP half. Magnitude decomposition cannot produce this.
        val d = side.toDouble()
        val upright = KiteMatrix(d, 0.0, 0.0, -d, 0.0, d)
        val rotate = KiteMatrix(0.0, 1.0, -1.0, 0.0, d, 0.0)
        val map = render(rotate.concat(upright)).toPixelMap()
        val top = map[side / 2, side / 4]
        val bottom = map[side / 2, 3 * side / 4]
        assertTrue(top.red > 0.8f && top.blue < 0.25f, "top must be red: $top")
        assertTrue(bottom.blue > 0.8f && bottom.red < 0.25f, "bottom must be blue: $bottom")
    }
}
