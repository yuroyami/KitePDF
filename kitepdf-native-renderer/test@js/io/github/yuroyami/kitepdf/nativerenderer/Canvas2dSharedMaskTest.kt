package io.github.yuroyami.kitepdf.nativerenderer

import io.github.yuroyami.kitepdf.PdfDocument
import io.github.yuroyami.kitepdf.core.render.KiteMatrix
import kotlinx.browser.document
import org.w3c.dom.CanvasRenderingContext2D
import org.w3c.dom.HTMLCanvasElement
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Many paints under one soft mask render its group once on Canvas2D, and draw what rendering it for
 * each paint draws (#912). Each paint rendered the group into a layer and turned that to alpha.
 */
class Canvas2dSharedMaskTest {

    /** [n] squares under one `gs`, or each under its own ExtGState when [fresh]. */
    private fun pdf(n: Int, fresh: Boolean = false): ByteArray {
        val squares = StringBuilder("q 10 10 180 180 re W n 0 0 1 rg ")
        if (!fresh) squares.append("/GS0 gs ")
        for (i in 0 until n) {
            val x = 12 + (i % 7) * 25
            val y = 12 + (i / 7) * 25
            if (fresh) squares.append("/GS$i gs ")
            squares.append("q 1 0 0 1 $x $y cm 0 0 8 8 re f Q ")
        }
        squares.append("Q")
        val group = "0.3 g 0 0 200 200 re f 0.8 g 0 100 200 100 re f 1 g 100 100 m 150 100 150 170 100 170 c 50 170 50 100 100 100 c f"
        val groupForm = "<< /Type /XObject /Subtype /Form /BBox [0 0 200 200] /Group << /S /Transparency /CS /DeviceRGB >> /Length ${group.length} >>\nstream\n$group\nendstream"
        val count = if (fresh) n else 1
        val states = (0 until count).joinToString(" ") { "/GS$it ${6 + it} 0 R" }
        return testPage(squares.toString(), "/ExtGState << $states >>", listOf(groupForm) + List(count) { "<< /Type /ExtGState /SMask << /S /Luminosity /G 5 0 R >> >>" })
    }

    private class Drawn(val pixels: List<Int>, val maskRenders: Int)

    private fun draw(bytes: ByteArray, keep: Boolean = true): Drawn {
        val canvas = document.createElement("canvas") as HTMLCanvasElement
        canvas.width = SIDE
        canvas.height = SIDE
        val ctx = canvas.getContext("2d") as CanvasRenderingContext2D
        ctx.fillStyle = "white"
        ctx.fillRect(0.0, 0.0, SIDE.toDouble(), SIDE.toDouble())
        val target = Canvas2dCanvas(ctx).apply { keepMasks = keep }
        PdfDocument.open(bytes).pages[0].renderTo(target, KiteMatrix(1.0, 0.0, 0.0, -1.0, 0.0, SIDE.toDouble()))
        val data = ctx.getImageData(0.0, 0.0, SIDE.toDouble(), SIDE.toDouble()).data.asDynamic()
        return Drawn(List(SIDE * SIDE * 4) { data[it] as Int }, target.maskRenders)
    }

    @Test
    fun a_shared_mask_draws_what_a_mask_for_each_paint_draws_and_renders_once() {
        for (n in listOf(1, 2, 49)) {
            val shared = draw(pdf(n)).pixels
            val each = draw(pdf(n, fresh = true)).pixels
            var worst = 0
            var differing = 0
            for (i in shared.indices) {
                val d = abs(shared[i] - each[i])
                if (d > 0) differing++
                worst = maxOf(worst, d)
            }
            assertTrue(worst <= 1 && differing <= shared.size / 10_000, "$n squares: $differing samples differ, by up to $worst levels")
        }
        assertEquals(2, draw(pdf(49)).maskRenders, "mask renders for 49 squares under one mask")
        assertEquals(49, draw(pdf(49, fresh = true)).maskRenders, "mask renders for 49 squares each under its own mask")
        assertEquals(49, draw(pdf(49), keep = false).maskRenders, "mask renders with no mask kept")
    }
}
