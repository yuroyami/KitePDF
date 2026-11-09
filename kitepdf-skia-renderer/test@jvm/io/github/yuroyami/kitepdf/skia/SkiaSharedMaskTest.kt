package io.github.yuroyami.kitepdf.skia

import io.github.yuroyami.kitepdf.KitePDF
import io.github.yuroyami.kitepdf.core.render.KiteMatrix
import org.jetbrains.skia.Color
import org.jetbrains.skia.Surface
import java.io.ByteArrayOutputStream
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Many paints under one soft mask render its group once on Skia, and draw what rendering it for
 * each paint draws (#912). Each square rendered the mask group twice, once to take the backdrop
 * out and once to mask the content (#764).
 */
class SkiaSharedMaskTest {

    private val size = 600

    /** [n] squares under one `gs`, or each under its own ExtGState when [fresh]. */
    private fun pdf(n: Int, fresh: Boolean = false): ByteArray {
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
        val out = ByteArrayOutputStream()
        val offsets = ArrayList<Int>()
        fun write(s: String) = out.write(s.encodeToByteArray())
        fun obj(body: String) { offsets += out.size(); write("${offsets.size} 0 obj\n$body\nendobj\n") }
        val group = "0.3 g 0 0 600 600 re f 0.8 g 0 300 600 300 re f 1 g 300 300 m 450 300 450 500 300 500 c 150 500 150 300 300 300 c f"
        val states = (0 until if (fresh) n else 1).joinToString(" ") { "/GS$it ${6 + it} 0 R" }
        write("%PDF-1.4\n")
        obj("<< /Type /Catalog /Pages 2 0 R >>")
        obj("<< /Type /Pages /Kids [3 0 R] /Count 1 /MediaBox [0 0 $size $size] >>")
        obj("<< /Type /Page /Parent 2 0 R /Resources << /ExtGState << $states >> >> /Contents 4 0 R >>")
        obj("<< /Length ${squares.length} >>\nstream\n$squares\nendstream")
        obj("<< /Type /XObject /Subtype /Form /BBox [0 0 $size $size] /Group << /S /Transparency /CS /DeviceRGB >> /Length ${group.length} >>\nstream\n$group\nendstream")
        for (i in 0 until if (fresh) n else 1) obj("<< /Type /ExtGState /SMask << /S /Luminosity /G 5 0 R >> >>")
        val xref = out.size()
        write("xref\n0 ${offsets.size + 1}\n0000000000 65535 f \n")
        for (o in offsets) write("${o.toString().padStart(10, '0')} 00000 n \n")
        write("trailer\n<< /Size ${offsets.size + 1} /Root 1 0 R >>\nstartxref\n$xref\n%%EOF\n")
        return out.toByteArray()
    }

    private class Drawn(val pixels: IntArray, val maskRenders: Int)

    private fun skia(pdf: ByteArray, keep: Boolean = true): Drawn {
        val surface = Surface.makeRasterN32Premul(size, size)
        try {
            surface.canvas.clear(Color.WHITE)
            val canvas = SkiaCanvas(surface.canvas).apply { keepMasks = keep }
            KitePDF.open(pdf).pages[0].renderTo(canvas, KiteMatrix(1.0, 0.0, 0.0, -1.0, 0.0, size.toDouble()))
            val bitmap = org.jetbrains.skia.Bitmap.makeFromImage(surface.makeImageSnapshot())
            val pixels = IntArray(size * size) { bitmap.getColor(it % size, it / size) }
            bitmap.close()
            return Drawn(pixels, canvas.maskRenders)
        } finally {
            surface.close()
        }
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
            val shared = skia(pdf(n))
            val each = skia(pdf(n, fresh = true))
            val (worst, differing) = worst(shared.pixels, each.pixels)
            assertTrue(worst <= 1 && differing <= shared.pixels.size / 10_000, "$n squares: $differing pixels differ, by up to $worst levels")
        }
        val shared = skia(pdf(49))
        // Two renders for the first square, which takes the backdrop out and masks the content, and one kept for the rest.
        assertEquals(3, shared.maskRenders, "mask renders for 49 squares under one mask")
        assertEquals(98, skia(pdf(49, fresh = true)).maskRenders, "mask renders for 49 squares each under its own mask")
        assertEquals(98, skia(pdf(49), keep = false).maskRenders, "mask renders with no mask kept")
        // The squares are masked: less blue shows under the band of 0.3, the lower half in device rows.
        fun reddest(rows: IntRange): Int = rows.flatMap { y -> (0 until size).map { shared.pixels[y * size + it] } }
            .filter { (it and 0xFF) > 250 && (it ushr 16 and 0xFF) < 250 }.minOf { it ushr 16 and 0xFF }
        assertTrue(reddest(310..590) > reddest(10..290), "less blue shows under 0.3 than under 0.8")
    }

    @Test
    fun a_shared_mask_renders_faster_when_kept() {
        val masked = pdf(400)
        fun millis(keep: Boolean): Double {
            skia(masked, keep)
            return (0 until 3).minOf { val t = System.nanoTime(); skia(masked, keep); (System.nanoTime() - t) / 1e6 }
        }
        val kept = millis(true)
        val again = millis(false)
        println("[skia-shared-mask] 400 squares: the mask kept $kept ms, rendered for each $again ms")
        assertTrue(kept * 1.5 < again, "the kept mask took $kept ms, a mask for each paint $again ms")
    }
}
