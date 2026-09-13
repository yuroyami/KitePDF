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
}
