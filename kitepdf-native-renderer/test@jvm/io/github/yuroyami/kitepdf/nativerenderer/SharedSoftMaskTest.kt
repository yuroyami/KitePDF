package io.github.yuroyami.kitepdf.nativerenderer

import io.github.yuroyami.kitepdf.KitePDF
import io.github.yuroyami.kitepdf.core.render.KiteCanvas
import io.github.yuroyami.kitepdf.core.render.KiteMatrix
import io.github.yuroyami.kitepdf.core.render.KiteRasterCanvas
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Many paints under one soft mask render its group once, and draw the pixels that rendering it
 * for each paint draws (#912). The check renders the same squares under one mask and under a
 * fresh mask for each, whose groups are the same form.
 */
class SharedSoftMaskTest {

    private val size = 600

    /** [n] squares under one `gs`, or each under its own ExtGState when [fresh]; or under `/ca` alone for [control]. */
    private fun pdf(n: Int, fresh: Boolean = false, control: Boolean = false): ByteArray {
        val per = kotlin.math.ceil(kotlin.math.sqrt(n.toDouble())).toInt()
        val squares = StringBuilder()
        // A clip and a cm move the content, never the mask, so the squares share it through them.
        squares.append("q 20 20 560 560 re W n 0 0 1 rg ")
        if (!fresh) squares.append(if (control) "/CA0 gs " else "/GS0 gs ")
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
        // A luminosity group of grey bands and a curved shape, whose edges are antialiased.
        val group = "0.3 g 0 0 600 600 re f 0.8 g 0 300 600 300 re f 1 g 300 300 m 450 300 450 500 300 500 c 150 500 150 300 300 300 c f"
        val states = if (control) "/CA0 << /ca 0.6 >>" else (0 until if (fresh) n else 1).joinToString(" ") { "/GS$it ${6 + it} 0 R" }
        write("%PDF-1.4\n")
        obj("<< /Type /Catalog /Pages 2 0 R >>")
        obj("<< /Type /Pages /Kids [3 0 R] /Count 1 /MediaBox [0 0 $size $size] >>")
        obj("<< /Type /Page /Parent 2 0 R /Resources << /ExtGState << $states >> >> /Contents 4 0 R >>")
        obj("<< /Length ${squares.length} >>\nstream\n$squares\nendstream")
        obj("<< /Type /XObject /Subtype /Form /BBox [0 0 $size $size] /Group << /S /Transparency /CS /DeviceRGB >> /Length ${group.length} >>\nstream\n$group\nendstream")
        if (!control) for (i in 0 until if (fresh) n else 1) obj("<< /Type /ExtGState /SMask << /S /Luminosity /G 5 0 R >> >>")
        val xref = out.size()
        write("xref\n0 ${offsets.size + 1}\n0000000000 65535 f \n")
        for (o in offsets) write("${o.toString().padStart(10, '0')} 00000 n \n")
        write("trailer\n<< /Size ${offsets.size + 1} /Root 1 0 R >>\nstartxref\n$xref\n%%EOF\n")
        return out.toByteArray()
    }

    private val flip = KiteMatrix(1.0, 0.0, 0.0, -1.0, 0.0, size.toDouble())

    private fun awt(pdf: ByteArray): IntArray {
        val page = KitePDF.open(pdf).pages[0]
        val image = BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB)
        val g = image.createGraphics()
        try { g.color = Color.WHITE; g.fillRect(0, 0, size, size); page.renderTo(AwtCanvas(g), flip) } finally { g.dispose() }
        return image.getRGB(0, 0, size, size, null, 0, size)
    }

    private fun raster(pdf: ByteArray): IntArray {
        val canvas = KiteRasterCanvas(size, size)
        KitePDF.open(pdf).pages[0].renderTo(canvas, flip)
        return canvas.toRaster().pixels
    }

    private fun millis(pdf: ByteArray, render: (ByteArray) -> Any): Double {
        render(pdf)
        return (0 until 3).minOf { val t = System.nanoTime(); render(pdf); (System.nanoTime() - t) / 1e6 }
    }

    @Test
    fun a_shared_mask_draws_what_a_mask_for_each_paint_draws() {
        for (n in listOf(1, 2, 49)) {
            assertTrue(raster(pdf(n)).contentEquals(raster(pdf(n, fresh = true))), "raster, $n squares")
            // AWT keeps a mask that two paints in a row use and renders it once over its whole box,
            // which can move an antialiased edge by one level from the mask rendered for each paint's box.
            val shared = awt(pdf(n))
            val each = awt(pdf(n, fresh = true))
            var worst = 0
            var differing = 0
            for (i in shared.indices) {
                var d = 0
                for (shift in intArrayOf(0, 8, 16, 24)) d = maxOf(d, abs((shared[i] ushr shift and 0xFF) - (each[i] ushr shift and 0xFF)))
                if (d > 0) differing++
                worst = maxOf(worst, d)
            }
            assertTrue(worst <= 1 && differing <= shared.size / 10_000, "AWT, $n squares: $differing pixels differ, by up to $worst levels")
        }
        // The squares are masked: a band of 0.3 lets less of the blue through than one of 0.8.
        val pixels = awt(pdf(49))
        fun reddest(rows: IntRange): Int = rows.flatMap { y -> (0 until size).map { pixels[y * size + it] } }
            .filter { (it and 0xFF) > 250 && (it ushr 16 and 0xFF) < 250 }.minOf { it ushr 16 and 0xFF }
        // Device rows run down: the lower half of the page, under the band of 0.3, is rows 300 to 599.
        assertTrue(reddest(310..590) > reddest(10..290), "less blue shows under 0.3 than under 0.8")
    }

    @Test
    fun many_paints_under_one_mask_render_it_once_on_the_raster_canvas() {
        val rasterShared = millis(pdf(100), ::raster)
        val rasterFresh = millis(pdf(100, fresh = true), ::raster)
        println("raster, 100 squares: one mask $rasterShared ms, a mask each $rasterFresh ms")
        assertTrue(rasterShared * 3 < rasterFresh, "raster: 100 squares under one mask take $rasterShared ms, under a mask each $rasterFresh ms")
    }

    private fun awt(pdf: ByteArray, bounded: Boolean, keep: Boolean = true): IntArray {
        val page = KitePDF.open(pdf).pages[0]
        val image = BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB)
        val g = image.createGraphics()
        try {
            g.color = Color.WHITE; g.fillRect(0, 0, size, size)
            page.renderTo(AwtCanvas(g).apply { boundsMaskContent = bounded; keepMasks = keep }, flip)
        } finally { g.dispose() }
        return image.getRGB(0, 0, size, size, null, 0, size)
    }

    private fun maskRenders(pdf: ByteArray): Int {
        val image = BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB)
        val g = image.createGraphics()
        val canvas = AwtCanvas(g)
        try { KitePDF.open(pdf).pages[0].renderTo(canvas, flip) } finally { g.dispose() }
        return canvas.maskRenders
    }

    @Test
    fun awt_counts_one_mask_render_for_a_shared_mask_and_one_for_each_fresh_mask() {
        // The first paint renders it for its own box, the second for the whole box, and it is kept.
        assertEquals(2, maskRenders(pdf(1600)))
        assertEquals(49, maskRenders(pdf(49, fresh = true)))
    }

    @Test
    fun awt_renders_a_shared_mask_once() {
        // Each of 1,600 squares rendered the 600 by 600 mask group again (#912).
        val masked = pdf(1600)
        val keptMs = millis(masked) { awt(it, bounded = true, keep = true) }
        val againMs = millis(masked) { awt(it, bounded = true, keep = false) }
        val controlMs = millis(pdf(1600, control = true)) { awt(it, bounded = true) }
        println("[shared-mask] AWT, 1,600 squares: the mask kept $keptMs ms, rendered for each $againMs ms, /ca 0.6 $controlMs ms")
        assertTrue(keptMs * 1.5 < againMs, "the kept mask took $keptMs ms, a mask for each paint $againMs ms")
    }

    @Test
    fun awt_reads_only_the_box_a_masked_paint_can_touch() {
        // AWT scanned its whole content layer, 600 by 600 here, after each small square (#912).
        val masked = pdf(1600)
        val whole = awt(masked, bounded = false)
        val bounded = awt(masked, bounded = true)
        assertTrue(whole.contentEquals(bounded), "reading the paint's box changed the pixels")
        val boundedMs = millis(masked) { awt(it, bounded = true) }
        val wholeMs = millis(masked) { awt(it, bounded = false) }
        val controlMs = millis(pdf(1600, control = true)) { awt(it, bounded = true) }
        println("[shared-mask] AWT, 1,600 squares: whole layer $wholeMs ms, the paint's box $boundedMs ms, /ca 0.6 $controlMs ms")
        assertTrue(boundedMs < wholeMs, "the paint's box took $boundedMs ms, the whole layer $wholeMs ms")
    }
}
