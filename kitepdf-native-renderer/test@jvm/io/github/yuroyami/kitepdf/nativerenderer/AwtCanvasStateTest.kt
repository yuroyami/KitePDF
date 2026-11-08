package io.github.yuroyami.kitepdf.nativerenderer

import io.github.yuroyami.kitepdf.core.KiteRectangle
import io.github.yuroyami.kitepdf.core.render.KiteBlendMode
import io.github.yuroyami.kitepdf.core.render.KiteMatrix
import io.github.yuroyami.kitepdf.core.render.KitePath
import java.awt.image.BufferedImage
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** AWT canvas rules: dash arrays, the clip and group stacks, and blending onto a transparent surface. */
class AwtCanvasStateTest {

    @Test
    fun dash_zeros_are_kept() {
        assertContentEquals(floatArrayOf(0f, 12f), awtDash(listOf(0.0, 12.0), 1.0), "a dotted line: zero-length dashes 12 apart")
        assertContentEquals(floatArrayOf(24f, 0f), awtDash(listOf(12.0, 0.0), 2.0), "a zero gap keeps the line solid")
        assertNull(awtDash(listOf(0.0, 0.0), 1.0), "all zero is solid, and BasicStroke refuses it")
        assertNull(awtDash(emptyList(), 1.0))
    }

    @Test
    fun a_dash_too_fine_to_show_strokes_solid_and_at_once() {
        // 2 w [D D] 0 d 0 0 m 612 792 l S at a scale of 2 took seconds per line on Java2D (#979).
        val start = System.nanoTime()
        val img = BufferedImage(1224, 1584, BufferedImage.TYPE_INT_ARGB)
        val g = img.createGraphics()
        val line = KitePath.Builder().apply { moveTo(0.0, 0.0); lineTo(612.0, 792.0) }.build()
        AwtCanvas(g).strokePath(
            line, KiteMatrix(2.0, 0.0, 0.0, 2.0, 0.0, 0.0), io.github.yuroyami.kitepdf.core.render.RgbColor(0.0, 0.0, 0.0), 2.0,
            1.0, KiteBlendMode.Normal, listOf(0.00001, 0.00001), 0.0, 0, 0, 10.0,
        )
        g.dispose()
        assertTrue((System.nanoTime() - start) / 1_000_000 < 2_000, "took ${(System.nanoTime() - start) / 1_000_000} ms")
        assertEquals(255, img.getRGB(306 * 2, 396 * 2) ushr 24, "the line draws solid")
        // A pattern of a few hundred dashes still dashes.
        val path = java.awt.geom.Line2D.Double(0.0, 0.0, 1000.0, 0.0)
        assertEquals(200.0, dashCount(path, floatArrayOf(5f, 5f)), 1e-9, "100 dashes and 100 gaps")
    }

    @Test
    fun a_clip_and_a_group_can_interleave() {
        val img = BufferedImage(20, 20, BufferedImage.TYPE_INT_ARGB)
        val g = img.createGraphics()
        val before = g.composite
        val canvas = AwtCanvas(g)
        val square = KitePath.Builder().apply { rectangle(0.0, 0.0, 10.0, 10.0) }.build()
        canvas.pushClip(square, KiteMatrix.IDENTITY, evenOdd = false)
        canvas.beginTransparencyGroup(KiteRectangle(0.0, 0.0, 20.0, 20.0), KiteMatrix.IDENTITY, alpha = 0.5, blendMode = KiteBlendMode.Normal)
        val inGroup = g.composite
        canvas.popClip()
        assertNull(g.clip, "popping the clip removes the clip")
        assertSame(inGroup, g.composite, "and leaves the open group's composite alone")
        canvas.endTransparencyGroup()
        assertSame(before, g.composite)
        g.dispose()
    }

    @Test
    fun multiply_onto_a_transparent_surface_keeps_the_source_colour() {
        val src = BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB).apply { setRGB(0, 0, 0xFFFF0000.toInt()) }
        val dst = BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB) // fully transparent
        PdfBlendComposite(KiteBlendMode.Multiply, 0.5f).createContext(src.colorModel, dst.colorModel, null)
            .compose(src.raster, dst.raster, dst.raster)
        val px = IntArray(4)
        dst.raster.getPixel(0, 0, px)
        assertTrue(px[0] > 250, "red survives: there is no ink under it to multiply with (got ${px.toList()})")
        assertEquals(127, px[3], "half alpha")
    }

    @Test
    fun multiply_onto_an_rgb_surface_blends_with_the_opaque_backdrop() {
        // A raster with no alpha band is opaque, so Multiply must darken it (#272).
        val src = BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB).apply { setRGB(0, 0, 0xFFFF0000.toInt()) }
        val dst = BufferedImage(1, 1, BufferedImage.TYPE_INT_RGB).apply { setRGB(0, 0, 0xFF8080FF.toInt()) }
        PdfBlendComposite(KiteBlendMode.Multiply, 1f).createContext(src.colorModel, dst.colorModel, null)
            .compose(src.raster, dst.raster, dst.raster)
        assertEquals(0xFF800000.toInt(), dst.getRGB(0, 0))
    }

    @Test
    fun a_page_leaves_the_hosts_graphics_as_it_found_it() {
        // A Swing component drew its own text after the page in the page's font, colour and dashes (#866).
        val img = BufferedImage(300, 300, BufferedImage.TYPE_INT_ARGB)
        val g = img.createGraphics()
        g.font = java.awt.Font("Dialog", java.awt.Font.PLAIN, 12)
        g.color = java.awt.Color.MAGENTA
        g.stroke = java.awt.BasicStroke(1f)
        g.setRenderingHint(java.awt.RenderingHints.KEY_ANTIALIASING, java.awt.RenderingHints.VALUE_ANTIALIAS_OFF)
        val font = g.font
        val transform = g.transform
        val pdf = io.github.yuroyami.kitepdf.writer.PdfBuilder().page(width = 600.0, height = 600.0) {
            text(io.github.yuroyami.kitepdf.writer.StandardFont.Helvetica, 48.0, 50.0, 300.0, "Hello")
        }.build()
        val page = io.github.yuroyami.kitepdf.PdfDocument.open(pdf).pages[0]
        val canvas = AwtCanvas(g)
        page.renderTo(canvas, KiteMatrix(0.5, 0.0, 0.0, -0.5, 0.0, 300.0))
        canvas.strokePath(
            KitePath.Builder().apply { moveTo(0.0, 0.0); lineTo(100.0, 100.0) }.build(), KiteMatrix.IDENTITY,
            io.github.yuroyami.kitepdf.core.render.RgbColor(1.0, 0.0, 0.0), 6.0, 1.0, KiteBlendMode.Normal, listOf(8.0, 4.0), 0.0, 0, 0, 10.0,
        )
        assertEquals(font, g.font)
        assertEquals(java.awt.Color.MAGENTA, g.color)
        assertEquals(java.awt.BasicStroke(1f), g.stroke)
        assertEquals(java.awt.RenderingHints.VALUE_ANTIALIAS_OFF, g.getRenderingHint(java.awt.RenderingHints.KEY_ANTIALIASING))
        assertEquals(transform, g.transform)
        assertTrue((0 until 300).any { x -> (0 until 300).any { y -> img.getRGB(x, y) ushr 24 != 0 } }, "the page drew into the host's image")
        g.dispose()
    }

    @Test
    fun a_packed_image_wraps_its_pixels_as_an_int_argb_image() {
        // setRGB copied every pixel through the colour model (#870).
        val argb = intArrayOf(0xFFFF0000.toInt(), 0x8000FF00.toInt(), 0x000000FF, -1)
        val image = packedArgbImage(argb, 2, 2)
        assertEquals(BufferedImage.TYPE_INT_ARGB, image.type, "Java2D draws it through its own ARGB loops")
        assertEquals(listOf(0xFFFF0000.toInt(), 0x8000FF00.toInt(), 0x000000FF, -1), listOf(image.getRGB(0, 0), image.getRGB(1, 0), image.getRGB(0, 1), image.getRGB(1, 1)))
        assertSame(argb, (image.raster.dataBuffer as java.awt.image.DataBufferInt).data, "no copy")
    }
}
