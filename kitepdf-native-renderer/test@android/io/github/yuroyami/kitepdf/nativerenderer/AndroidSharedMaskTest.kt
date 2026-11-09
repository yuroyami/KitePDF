package io.github.yuroyami.kitepdf.nativerenderer

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import io.github.yuroyami.kitepdf.PdfDocument
import io.github.yuroyami.kitepdf.core.render.KiteMatrix
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.math.abs
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Many paints under one soft mask render its group once on the Android canvas, and draw what
 * rendering it for each paint draws (#912), as `SkiaSharedMaskTest` checks on Skia. Each square
 * rendered the mask group twice, once to take the backdrop out and once to mask the content (#764).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [29, 35])
class AndroidSharedMaskTest {

    private val size = 600

    /** The mask group: a band of 0.3, a band of 0.8 and a white ellipse. */
    private val bands = "0.3 g 0 0 600 600 re f 0.8 g 0 300 600 300 re f 1 g 300 300 m 450 300 450 500 300 500 c 150 500 150 300 300 300 c f"

    /** The bands and 100 grey ellipses over them, a group that costs something to render, as an exported chart's does. */
    private val detailed = bands + (0 until 100).joinToString("") { i ->
        val x = 30 + (i % 10) * 57
        val y = 30 + (i / 10) * 57
        " ${(i % 9 + 1) / 10.0} g $x $y m ${x + 25} $y ${x + 25} ${y + 40} $x ${y + 40} c ${x - 25} ${y + 40} ${x - 25} $y $x $y c f"
    }

    /** [n] squares under one `gs`, or each under its own ExtGState when [fresh], masked by [group]. */
    private fun pdf(n: Int, fresh: Boolean = false, group: String = bands): ByteArray {
        val per = kotlin.math.ceil(kotlin.math.sqrt(n.toDouble())).toInt()
        val squares = StringBuilder("q 20 20 560 560 re W n 0 0 1 rg ")
        if (!fresh) squares.append("/GS0 gs ")
        for (i in 0 until n) {
            val x = 24 + (i % per) * (552 / per)
            val y = 24 + (i / per) * (552 / per)
            if (fresh) squares.append("/GS$i gs ")
            squares.append("q 1 0 0 1 $x $y cm 0 0 8 8 re f Q ")
        }
        squares.append("Q")
        val count = if (fresh) n else 1
        val states = (0 until count).joinToString(" ") { "/GS$it ${6 + it} 0 R" }
        return testPage(
            squares.toString(), "/ExtGState << $states >>",
            listOf(
                "<< /Type /XObject /Subtype /Form /BBox [0 0 $size $size] /Group << /S /Transparency /CS /DeviceRGB >> " +
                    "/Length ${group.length} >>\nstream\n$group\nendstream",
            ) + List(count) { "<< /Type /ExtGState /SMask << /S /Luminosity /G 5 0 R >> >>" },
            side = size,
        )
    }

    private class Drawn(val pixels: IntArray, val maskRenders: Int)

    private fun draw(pdf: ByteArray, keep: Boolean = true): Drawn {
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val host = Canvas(bitmap)
        host.drawColor(Color.WHITE)
        val canvas = AndroidNativeCanvas(host).apply {
            target = bitmap
            keepMasks = keep
        }
        PdfDocument.open(pdf).pages[0].renderTo(canvas, KiteMatrix(1.0, 0.0, 0.0, -1.0, 0.0, size.toDouble()))
        val pixels = IntArray(size * size)
        bitmap.getPixels(pixels, 0, size, 0, 0, size, size)
        bitmap.recycle()
        return Drawn(pixels, canvas.maskRenders)
    }

    private fun worst(a: IntArray, b: IntArray): Pair<Int, Int> {
        var worst = 0
        var differing = 0
        for (i in a.indices) {
            var d = 0
            for (shift in intArrayOf(0, 8, 16, 24)) d = maxOf(d, abs((a[i] ushr shift and 0xFF) - (b[i] ushr shift and 0xFF)))
            if (d > 0) differing++
            worst = maxOf(worst, d)
        }
        return worst to differing
    }

    @Test
    fun a_shared_mask_draws_what_a_mask_for_each_paint_draws_and_renders_once() {
        for (n in listOf(1, 2, 49)) {
            val shared = draw(pdf(n))
            val each = draw(pdf(n, fresh = true))
            val (worst, differing) = worst(shared.pixels, each.pixels)
            assertTrue(worst <= 1 && differing <= shared.pixels.size / 10_000, "$n squares: $differing pixels differ, by up to $worst levels")
        }
        val shared = draw(pdf(49))
        // Two renders for the first square, which takes the backdrop out and masks the content, and one kept for the rest.
        assertEquals(3, shared.maskRenders, "mask renders for 49 squares under one mask")
        assertEquals(98, draw(pdf(49, fresh = true)).maskRenders, "mask renders for 49 squares each under its own mask")
        assertEquals(98, draw(pdf(49), keep = false).maskRenders, "mask renders with no mask kept")
        // The squares are masked: less blue shows under the band of 0.3, the lower half in device rows.
        fun reddest(rows: IntRange): Int = rows.flatMap { y -> (0 until size).map { shared.pixels[y * size + it] } }
            .filter { (it and 0xFF) > 250 && (it ushr 16 and 0xFF) < 250 }.minOf { it ushr 16 and 0xFF }
        assertTrue(reddest(310..590) > reddest(10..290), "less blue shows under 0.3 than under 0.8")
    }

    @Test
    fun a_shared_mask_renders_faster_when_kept() {
        fun millis(pdf: ByteArray, keep: Boolean): Double {
            draw(pdf, keep)
            return (0 until 3).minOf { val t = System.nanoTime(); draw(pdf, keep); (System.nanoTime() - t) / 1e6 }
        }
        val sdk = android.os.Build.VERSION.SDK_INT
        val plain = pdf(400)
        println("[android-shared-mask] SDK $sdk, 400 squares, 3 fills in the mask: kept ${millis(plain, true)} ms, rendered for each ${millis(plain, false)} ms")
        val masked = pdf(400, group = detailed)
        val kept = millis(masked, true)
        val again = millis(masked, false)
        println("[android-shared-mask] SDK $sdk, 400 squares, 103 fills in the mask: kept $kept ms, rendered for each $again ms")
        // Below API 35 the kept mask still draws through the mask's colour filter over its whole
        // box for each paint, which costs about what rendering a small group does, so only the
        // group's render is saved there.
        org.junit.Assume.assumeTrue("the kept mask draws as alpha from API 35", sdk >= 35)
        assertTrue(kept * 1.5 < again, "the kept mask took $kept ms, a mask for each paint $again ms")
    }
}
