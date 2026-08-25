package io.github.yuroyami.kitepdf.nativerenderer

import io.github.yuroyami.kitepdf.core.font.FontSpec
import io.github.yuroyami.kitepdf.core.font.TextGlyph
import io.github.yuroyami.kitepdf.core.render.KiteBlendMode
import io.github.yuroyami.kitepdf.core.render.KiteImageData
import io.github.yuroyami.kitepdf.core.render.KiteMatrix
import io.github.yuroyami.kitepdf.core.render.RgbColor
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import platform.CoreGraphics.CGBitmapContextCreate
import platform.CoreGraphics.CGColorSpaceCreateDeviceRGB
import platform.CoreGraphics.CGContextFillRect
import platform.CoreGraphics.CGContextRelease
import platform.CoreGraphics.CGContextSetRGBFillColor
import platform.CoreGraphics.CGImageAlphaInfo
import platform.CoreGraphics.CGRectMake
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Real pixels out of [CoreGraphicsCanvas]: the D-3 acceptance for the Apple
 * backend, runnable natively on a Mac.
 */
@OptIn(ExperimentalForeignApi::class)
class CoreGraphicsPixelTest {

    private val side = 40

    /** Draw through [block] into an RGBA bitmap context, return the pixels. */
    private fun render(block: (CoreGraphicsCanvas) -> Unit): UByteArray {
        val pixels = UByteArray(side * side * 4)
        pixels.usePinned { pinned ->
            val ctx = CGBitmapContextCreate(
                pinned.addressOf(0), side.toULong(), side.toULong(),
                8u, (side * 4).toULong(), CGColorSpaceCreateDeviceRGB(),
                CGImageAlphaInfo.kCGImageAlphaPremultipliedLast.value,
            )!!
            // White ground: a fresh context is transparent black, which a
            // dark-ink counter cannot tell from painted text.
            CGContextSetRGBFillColor(ctx, 1.0, 1.0, 1.0, 1.0)
            CGContextFillRect(ctx, CGRectMake(0.0, 0.0, side.toDouble(), side.toDouble()))
            block(CoreGraphicsCanvas(ctx))
            CGContextRelease(ctx)
        }
        return pixels
    }

    private fun UByteArray.px(x: Int, y: Int): Triple<Int, Int, Int> {
        // `side`, not `size`: inside this extension, `size` is the ARRAY's length.
        val p = (y * side + x) * 4
        return Triple(this[p].toInt(), this[p + 1].toInt(), this[p + 2].toInt())
    }

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
        assertEquals(KiteImageData.Kind.RAW, image.kind, "core decodes BMP to RAW")
        return image
    }

    /** Unit square over the whole context, image upright (row 0 at v = 1). */
    private fun fullContextCtm(): KiteMatrix =
        KiteMatrix(side.toDouble(), 0.0, 0.0, -side.toDouble(), 0.0, side.toDouble())

    @Test
    fun raw_image_paints_red_left_blue_right() {
        val pixels = render { canvas ->
            canvas.drawImage(rawRedBlue2x1(), fullContextCtm())
        }
        val (lr, lg, lb) = pixels.px(side / 4, side / 2)
        val (rr, rg, rb) = pixels.px(3 * side / 4, side / 2)
        assertTrue(lr > 200 && lb < 60, "left half red, got ($lr,$lg,$lb)")
        assertTrue(rb > 200 && rr < 60, "right half blue, got ($rr,$rg,$rb)")
    }

    @Test
    fun raw_image_alpha_halves_the_ink() {
        val opaque = render { c -> c.drawImage(rawRedBlue2x1(), fullContextCtm()) }
        val half = render { c -> c.drawImage(rawRedBlue2x1(), fullContextCtm(), alpha = 0.5) }
        val full = opaque.px(side / 4, side / 2).first
        val faded = half.px(side / 4, side / 2).first
        assertTrue(faded < full - 60, "alpha 0.5 must fade red: $faded vs $full")
    }

    @Test
    fun standard14_text_paints_ink() {
        val pixels = render { canvas ->
            canvas.drawGlyphs(
                glyphs = listOf(
                    TextGlyph(
                        byteOffset = 0, byteCount = 1, gid = -1, text = "H",
                        advanceWidth = 722.0, outline = null, isWordSpace = false,
                    ),
                ),
                fontSize = 30.0,
                unitsPerEm = 1000,
                hasOutlines = false,
                fontSpec = FontSpec.SansSerif,
                // Text space y-up onto a y-down device: flip, baseline near the bottom.
                textToDevice = KiteMatrix(1.0, 0.0, 0.0, -1.0, 4.0, 34.0),
                color = RgbColor(0.0, 0.0, 0.0),
                alpha = 1.0,
                blendMode = KiteBlendMode.Normal,
            )
        }
        var inked = 0
        for (y in 0 until side) for (x in 0 until side) {
            val (r, g, b) = pixels.px(x, y)
            if (r < 100 && g < 100 && b < 100) inked++
        }
        assertTrue(inked > 20, "an H at 30pt must ink pixels, found $inked")
    }
}
