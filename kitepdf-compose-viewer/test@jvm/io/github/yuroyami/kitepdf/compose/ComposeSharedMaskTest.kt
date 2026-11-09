package io.github.yuroyami.kitepdf.compose

import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import io.github.yuroyami.kitepdf.PdfDocument
import io.github.yuroyami.kitepdf.core.render.KiteMatrix
import java.io.ByteArrayOutputStream
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Many paints under one soft mask render its group once on the Compose canvas, and draw what
 * rendering it for each paint draws (#912). Outside a layer each square rendered the group twice,
 * once to take the backdrop out and once to mask the content (#764).
 */
class ComposeSharedMaskTest {

    private val side = 600
    private val measurer = TextMeasurer(testFontFamilyResolver(), Density(1f), LayoutDirection.Ltr)

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
        obj("<< /Type /Pages /Kids [3 0 R] /Count 1 /MediaBox [0 0 $side $side] >>")
        obj("<< /Type /Page /Parent 2 0 R /Resources << /ExtGState << $states >> >> /Contents 4 0 R >>")
        obj("<< /Length ${squares.length} >>\nstream\n$squares\nendstream")
        obj("<< /Type /XObject /Subtype /Form /BBox [0 0 $side $side] /Group << /S /Transparency /CS /DeviceRGB >> /Length ${group.length} >>\nstream\n$group\nendstream")
        for (i in 0 until if (fresh) n else 1) obj("<< /Type /ExtGState /SMask << /S /Luminosity /G 5 0 R >> >>")
        val xref = out.size()
        write("xref\n0 ${offsets.size + 1}\n0000000000 65535 f \n")
        for (o in offsets) write("${o.toString().padStart(10, '0')} 00000 n \n")
        write("trailer\n<< /Size ${offsets.size + 1} /Root 1 0 R >>\nstartxref\n$xref\n%%EOF\n")
        return out.toByteArray()
    }

    private class Drawn(val pixels: IntArray, val maskRenders: Int)

    private fun compose(pdf: ByteArray, keep: Boolean = true): Drawn {
        val page = PdfDocument.open(pdf).pages[0]
        val bitmap = ImageBitmap(side, side)
        var renders = 0
        CanvasDrawScope().draw(Density(1f), LayoutDirection.Ltr, Canvas(bitmap), Size(side.toFloat(), side.toFloat())) {
            drawRect(Color.White)
            val canvas = ComposeCanvas(this, measurer, hairlineWidthPx = 1f, skipSystemFontText = false, magnification = 1f, target = bitmap)
            canvas.keepMasks = keep
            page.renderTo(canvas, KiteMatrix(1.0, 0.0, 0.0, -1.0, 0.0, side.toDouble()))
            renders = canvas.maskRenders
        }
        return Drawn(IntArray(side * side).also { bitmap.readPixels(it) }, renders)
    }

    @Test
    fun a_shared_mask_draws_what_a_mask_for_each_paint_draws_and_renders_once() {
        for (n in listOf(1, 2, 49)) {
            val shared = compose(pdf(n))
            val each = compose(pdf(n, fresh = true))
            var worst = 0
            var differing = 0
            for (i in shared.pixels.indices) {
                var d = 0
                for (shift in intArrayOf(0, 8, 16, 24)) d = maxOf(d, abs((shared.pixels[i] ushr shift and 0xFF) - (each.pixels[i] ushr shift and 0xFF)))
                if (d > 0) differing++
                worst = maxOf(worst, d)
            }
            assertTrue(worst <= 1 && differing <= shared.pixels.size / 10_000, "$n squares: $differing pixels differ, by up to $worst levels")
        }
        // Two renders for the first square, which takes the backdrop out and masks the content, and one kept for the rest.
        assertEquals(3, compose(pdf(49)).maskRenders, "mask renders for 49 squares under one mask")
        assertEquals(98, compose(pdf(49, fresh = true)).maskRenders, "mask renders for 49 squares each under its own mask")
        assertEquals(98, compose(pdf(49), keep = false).maskRenders, "mask renders with no mask kept")
        val pixels = compose(pdf(49)).pixels
        fun reddest(rows: IntRange): Int = rows.flatMap { y -> (0 until side).map { pixels[y * side + it] } }
            .filter { (it and 0xFF) > 250 && (it ushr 16 and 0xFF) < 250 }.minOf { it ushr 16 and 0xFF }
        assertTrue(reddest(310..590) > reddest(10..290), "less blue shows under 0.3 than under 0.8")
    }
}
