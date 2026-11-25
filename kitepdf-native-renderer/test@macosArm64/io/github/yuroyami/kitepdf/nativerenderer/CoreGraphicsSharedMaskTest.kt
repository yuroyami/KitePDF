package io.github.yuroyami.kitepdf.nativerenderer

import io.github.yuroyami.kitepdf.PdfDocument
import io.github.yuroyami.kitepdf.core.render.KiteMatrix
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Many paints under one soft mask render its group once on CoreGraphics, and draw what rendering
 * it for each paint draws (#912), as `AndroidSharedMaskTest` checks on Android.
 */
class CoreGraphicsSharedMaskTest {

    private val size = 600

    /** The mask group: a band of 0.3, a band of 0.8 and a white ellipse. */
    private val bands = "0.3 g 0 0 600 600 re f 0.8 g 0 300 600 300 re f 1 g 300 300 m 450 300 450 500 300 500 c 150 500 150 300 300 300 c f"

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
        val count = if (fresh) n else 1
        val states = (0 until count).joinToString(" ") { "/GS$it ${6 + it} 0 R" }
        return cgTestPage(
            squares.toString(), "/ExtGState << $states >>",
            listOf(
                "<< /Type /XObject /Subtype /Form /BBox [0 0 $size $size] /Group << /S /Transparency /CS /DeviceRGB >> " +
                    "/Length ${bands.length} >>\nstream\n$bands\nendstream",
            ) + List(count) { "<< /Type /ExtGState /SMask << /S /Luminosity /G 5 0 R >> >>" },
            width = size,
        )
    }

    private fun draw(pdf: ByteArray): Pair<CgPixels, Int> {
        var renders = 0
        val pixels = cgDraw(size, size) { canvas ->
            PdfDocument.open(pdf).pages[0].renderTo(canvas, KiteMatrix.IDENTITY)
            renders = canvas.maskRenders
        }
        return pixels to renders
    }

    @Test
    fun a_shared_mask_draws_what_a_mask_for_each_paint_draws_and_renders_once() {
        for (n in listOf(1, 2, 49)) {
            val (shared, _) = draw(pdf(n))
            val (each, _) = draw(pdf(n, fresh = true))
            assertContentEquals(each.bytes, shared.bytes, "$n squares")
        }
        val (shared, renders) = draw(pdf(49))
        assertEquals(1, renders, "mask renders for 49 squares under one mask")
        assertEquals(49, draw(pdf(49, fresh = true)).second, "mask renders for 49 squares each under its own mask")
        // The squares are masked: less blue shows under the band of 0.3, the lower half.
        fun reddest(rows: IntRange): Int = rows.flatMap { y -> (0 until size).map { shared.rgb(it, y) } }
            .filter { it[2] > 250 && it[0] < 250 }.minOf { it[0] }
        assertTrue(reddest(10..290) > reddest(310..590), "less blue shows under 0.3 than under 0.8")
    }
}
