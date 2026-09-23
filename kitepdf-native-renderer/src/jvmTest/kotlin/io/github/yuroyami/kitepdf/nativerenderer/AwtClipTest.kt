package io.github.yuroyami.kitepdf.nativerenderer

import io.github.yuroyami.kitepdf.core.KiteRectangle
import io.github.yuroyami.kitepdf.core.parser.PdfDictionary
import io.github.yuroyami.kitepdf.core.parser.PdfInt
import io.github.yuroyami.kitepdf.core.parser.PdfName
import io.github.yuroyami.kitepdf.core.parser.PdfStream
import io.github.yuroyami.kitepdf.core.render.KiteBlendMode
import io.github.yuroyami.kitepdf.core.render.KiteImageData
import io.github.yuroyami.kitepdf.core.render.KiteMatrix
import io.github.yuroyami.kitepdf.core.render.KitePath
import io.github.yuroyami.kitepdf.core.render.RgbColor
import java.awt.Color
import java.awt.image.BufferedImage
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** AWT clips as MuPDF does: a rectangle to whole pixels, any other shape with anti-aliased edges (#285). */
class AwtClipTest {

    private val black = RgbColor(0.0, 0.0, 0.0)
    private val everything = rect(0.0, 0.0, 20.0, 20.0)

    /** The triangle below the line x + y = 20.3, in device pixels. */
    private val triangle = KitePath.Builder().apply {
        moveTo(0.0, 0.0); lineTo(20.3, 0.0); lineTo(0.0, 20.3); close()
    }.build()

    private fun rect(x0: Double, y0: Double, x1: Double, y1: Double) =
        KitePath.Builder().apply { rectangle(x0, y0, x1 - x0, y1 - y0) }.build()

    /** A white page of 20 by 20 pixels that [paint] draws on, with device space as user space. */
    private fun page(paint: (AwtCanvas) -> Unit): BufferedImage {
        val image = BufferedImage(20, 20, BufferedImage.TYPE_INT_ARGB)
        val g = image.createGraphics()
        g.color = Color.WHITE
        g.fillRect(0, 0, 20, 20)
        g.clip = java.awt.Rectangle(0, 0, 20, 20)
        paint(AwtCanvas(g))
        g.dispose()
        return image
    }

    /** 255 minus the blue channel: 0 on the white page, 255 under black. */
    private fun ink(image: BufferedImage, x: Int, y: Int) = 255 - (image.getRGB(x, y) and 0xFF)

    private fun fill(canvas: AwtCanvas, path: KitePath, color: RgbColor = black, mode: KiteBlendMode = KiteBlendMode.Normal) =
        canvas.fillPath(path, KiteMatrix.IDENTITY, color, evenOdd = false, blendMode = mode)

    private fun assertSamePixels(expected: BufferedImage, actual: BufferedImage, where: (Int, Int) -> Boolean, tolerance: Int) {
        for (y in 0 until 20) for (x in 0 until 20) {
            if (!where(x, y)) continue
            val e = expected.getRGB(x, y)
            val a = actual.getRGB(x, y)
            for (shift in intArrayOf(16, 8, 0)) {
                val d = abs(((e ushr shift) and 0xFF) - ((a ushr shift) and 0xFF))
                assertTrue(d <= tolerance, "($x, $y): expected ${Integer.toHexString(e)}, got ${Integer.toHexString(a)}")
            }
        }
    }

    @Test
    fun a_rectangle_clips_to_every_pixel_it_touches() {
        val image = page { c ->
            c.pushClip(rect(2.3, 2.3, 10.4, 10.03), KiteMatrix.IDENTITY, evenOdd = false)
            fill(c, everything)
            c.popClip()
        }
        assertEquals(255, ink(image, 2, 5), "the column of the left edge at 2.3")
        assertEquals(255, ink(image, 10, 5), "the column of the right edge at 10.4, whose centre lies outside")
        assertEquals(0, ink(image, 11, 5))
        assertEquals(0, ink(image, 1, 5))
        // MuPDF snaps an edge down to a fifteenth of a pixel, so an edge at 10.03 touches no pixel of row 10.
        assertEquals(255, ink(image, 5, 9))
        assertEquals(0, ink(image, 5, 10))
    }

    @Test
    fun any_other_clip_has_anti_aliased_edges() {
        val clipped = page { c ->
            c.pushClip(triangle, KiteMatrix.IDENTITY, evenOdd = false)
            fill(c, everything)
            c.popClip()
        }
        // The centre of pixel (10, 10) lies outside the triangle, and 4.5 % of its area inside.
        assertTrue(ink(clipped, 10, 10) in 1..40, "partial ink where the edge crosses: ${ink(clipped, 10, 10)}")
        val filled = page { c -> fill(c, triangle) }
        assertSamePixels(filled, clipped, { _, _ -> true }, tolerance = 2)
    }

    @Test
    fun an_image_in_a_clip_has_anti_aliased_edges() {
        val dot = assertNotNull(
            KiteImageData.from(
                PdfStream(
                    PdfDictionary(
                        mapOf(
                            "Type" to PdfName("XObject"), "Subtype" to PdfName("Image"),
                            "Width" to PdfInt(1), "Height" to PdfInt(1), "BitsPerComponent" to PdfInt(8),
                            "ColorSpace" to PdfName("DeviceRGB"), "Length" to PdfInt(3),
                        ),
                    ),
                    byteArrayOf(0, 0, 0),
                ),
            ),
        )
        val clipped = page { c ->
            c.pushClip(triangle, KiteMatrix.IDENTITY, evenOdd = false)
            // The image covers (-10, -10) to (30, 30), so its own edges lie off the page.
            c.drawImage(dot, KiteMatrix(40.0, 0.0, 0.0, 40.0, -10.0, -10.0))
            c.popClip()
        }
        assertSamePixels(page { c -> fill(c, triangle) }, clipped, { _, _ -> true }, tolerance = 2)
    }

    @Test
    fun paints_in_other_blend_modes_inside_a_clip_blend_with_the_page() {
        val rose = RgbColor(1.0, 0.5, 0.5)
        val grey = RgbColor(0.5, 0.5, 0.5)
        val blue = RgbColor(0.2, 0.4, 1.0)
        fun paints(c: AwtCanvas) {
            fill(c, everything, grey, KiteBlendMode.Multiply)
            fill(c, rect(0.0, 0.0, 6.0, 6.0), blue)
            fill(c, rect(3.0, 3.0, 9.0, 9.0), grey, KiteBlendMode.Multiply)
        }
        val direct = page { c -> fill(c, everything, rose); paints(c) }
        val clipped = page { c ->
            fill(c, everything, rose)
            c.pushClip(triangle, KiteMatrix.IDENTITY, evenOdd = false)
            paints(c)
            c.popClip()
        }
        // Away from its edge, the clip changes nothing: each paint blended with what lay under it.
        assertSamePixels(direct, clipped, { x, y -> x + y < 17 }, tolerance = 1)
        assertTrue(abs((clipped.getRGB(4, 4) and 0xFF) - 127) <= 1, "blue multiplied by grey")
        assertEquals(page { c -> fill(c, everything, rose) }.getRGB(15, 15), clipped.getRGB(15, 15), "outside the clip")
    }

    @Test
    fun a_clip_popped_inside_a_group_ends_after_the_group() {
        val image = page { c ->
            c.pushClip(triangle, KiteMatrix.IDENTITY, evenOdd = false)
            c.beginTransparencyGroup(KiteRectangle(0.0, 0.0, 20.0, 20.0), KiteMatrix.IDENTITY, alpha = 0.5)
            c.popClip()
            fill(c, everything)
            c.endTransparencyGroup()
            // The clip has ended, so this paint reaches the whole page.
            fill(c, rect(18.0, 18.0, 20.0, 20.0))
        }
        assertTrue(ink(image, 2, 2) in 126..129, "the group at half alpha, inside the clip: ${ink(image, 2, 2)}")
        assertEquals(0, ink(image, 15, 15), "the group stays inside the clip")
        assertEquals(255, ink(image, 19, 19), "the paint after the group")
    }
}
