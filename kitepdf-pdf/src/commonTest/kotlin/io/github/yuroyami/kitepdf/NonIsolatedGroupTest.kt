package io.github.yuroyami.kitepdf

import io.github.yuroyami.kitepdf.core.KiteRectangle
import io.github.yuroyami.kitepdf.core.render.KiteBlendMode
import io.github.yuroyami.kitepdf.core.render.KiteCanvas
import io.github.yuroyami.kitepdf.core.render.KiteMatrix
import io.github.yuroyami.kitepdf.core.render.KiteRaster
import io.github.yuroyami.kitepdf.core.render.KiteRasterScope
import io.github.yuroyami.kitepdf.core.render.KiteRasterStep
import io.github.yuroyami.kitepdf.core.render.RecordingCanvas
import io.github.yuroyami.kitepdf.render.GroupRasters
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A non-isolated transparency group whose paints blend, composited as ISO 32000-1, 11.4.8
 * says through a raster step, and painted as a layer where the canvas cannot read the
 * backdrop (#308).
 */
class NonIsolatedGroupTest {

    /** A one-page PDF that paints [content] over a form XObject Fm1 with [group] and [formContent]. */
    private fun pdf(content: String, pageResources: String, group: String, formContent: String, formResources: String): ByteArray {
        val objects = listOf(
            "<< /Type /Catalog /Pages 2 0 R >>",
            "<< /Type /Pages /Kids [3 0 R] /Count 1 >>",
            "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 200 200] /Resources << /XObject << /Fm1 5 0 R >> $pageResources >> /Contents 4 0 R >>",
            "<< /Length ${content.length} >>\nstream\n$content\nendstream",
            "<< /Type /XObject /Subtype /Form /BBox [0 0 200 200] /Group << /S /Transparency $group >> " +
                "/Resources << $formResources >> /Length ${formContent.length} >>\nstream\n$formContent\nendstream",
        )
        val out = StringBuilder("%PDF-1.7\n")
        val offsets = ArrayList<Int>()
        objects.forEachIndexed { i, body ->
            offsets += out.length
            out.append("${i + 1} 0 obj\n$body\nendobj\n")
        }
        val xref = out.length
        out.append("xref\n0 ${objects.size + 1}\n0000000000 65535 f \n")
        for (o in offsets) out.append("${o.toString().padStart(10, '0')} 00000 n \n")
        out.append("trailer\n<< /Size ${objects.size + 1} /Root 1 0 R >>\nstartxref\n$xref\n%%EOF\n")
        return out.toString().encodeToByteArray()
    }

    /** The Screen group of the issue: a yellow square multiplied inside a non-isolated group. */
    private val screenGroup = pdf(
        "0.5 1 0.5 rg 0 0 200 200 re f /GS1 gs /Fm1 Do", "/ExtGState << /GS1 << /BM /Screen /ca 0.8 >> >>",
        "/I false", "/GM gs 1 1 0 rg 40 40 120 120 re f", "/ExtGState << /GM << /BM /Multiply >> >>",
    )

    /** The knockout group of the issue: a red square, then a yellow one in Multiply, which knocks it out. */
    private val knockoutGroup = pdf(
        "0 1 0 rg 0 0 200 200 re f /Fm1 Do", "",
        "/I false /K true", "1 0 0 rg 20 20 100 100 re f /GM gs 1 1 0 rg 80 80 100 100 re f", "/ExtGState << /GM << /BM /Multiply >> >>",
    )

    /**
     * A canvas whose raster steps hand out [backdrop], a box of [side] by [side] pixels, paint
     * the content of each render through the recording, and keep what they draw.
     */
    private class StepCanvas(
        private val backdrop: KiteRaster?, private val side: Int = 2, val recording: RecordingCanvas = RecordingCanvas(),
    ) : KiteCanvas by recording {
        var renders = 0
        val drawn = ArrayList<Pair<Double, KiteBlendMode>>()
        override fun rasterStep(region: KiteRectangle, ctm: KiteMatrix, step: KiteRasterStep): Boolean = step.run(object : KiteRasterScope {
            override val width = side
            override val height = side
            override val toPixels = KiteMatrix.IDENTITY
            override fun backdrop(): KiteRaster? = backdrop
            override fun render(initial: KiteRaster?, content: () -> Unit): KiteRaster {
                renders++
                content()
                return initial?.copy() ?: KiteRaster(2, 2)
            }
            override fun draw(raster: KiteRaster, alpha: Double, blendMode: KiteBlendMode) {
                drawn += alpha to blendMode
            }
        })
    }

    private val green = KiteRaster(2, 2).apply { pixels.fill(0xFF00FF00.toInt()) }

    private fun fills(canvas: RecordingCanvas) = canvas.calls.filterIsInstance<RecordingCanvas.Call.Fill>()

    @Test
    fun a_group_with_a_blend_mode_of_its_own_paints_over_the_page_and_alone() {
        val canvas = StepCanvas(green)
        PdfDocument.open(screenGroup).pages[0].renderTo(canvas, KiteMatrix.IDENTITY)
        assertEquals(2, canvas.renders, "once over a copy of the page, once alone")
        // The page's own fill, then the square twice.
        assertEquals(3, fills(canvas.recording).size)
        assertEquals(listOf(0.8 to KiteBlendMode.Screen), canvas.drawn, "the group lands once, in its own alpha and blend mode")
        assertTrue(canvas.recording.calls.none { it is RecordingCanvas.Call.PushGroup })
    }

    @Test
    fun a_knockout_group_paints_each_object_three_times_inside_its_clip() {
        val canvas = StepCanvas(green)
        PdfDocument.open(knockoutGroup).pages[0].renderTo(canvas, KiteMatrix.IDENTITY)
        assertEquals(6, canvas.renders, "over the page, for its shape and alone, for each of two squares")
        assertEquals(1 + 6, fills(canvas.recording).size)
        // The squares paint in Normal and Multiply, except for their shapes, which paint in Normal at full alpha.
        val squares = fills(canvas.recording).drop(1)
        assertEquals(listOf(KiteBlendMode.Normal, KiteBlendMode.Normal, KiteBlendMode.Normal), squares.take(3).map { it.blendMode })
        assertEquals(listOf(KiteBlendMode.Multiply, KiteBlendMode.Normal, KiteBlendMode.Multiply), squares.drop(3).map { it.blendMode })
        // Each render pushes the form's box again, since a render starts without the page's clips.
        assertEquals(6, canvas.recording.calls.count { it is RecordingCanvas.Call.PushClip })
        assertEquals(listOf(1.0 to KiteBlendMode.Normal), canvas.drawn)
    }

    @Test
    fun without_a_backdrop_the_group_paints_as_a_layer() {
        // A canvas without raster steps, and one whose step has no backdrop to hand out.
        val plain = RecordingCanvas()
        val declining = StepCanvas(null)
        for (target in listOf(plain, declining)) PdfDocument.open(knockoutGroup).pages[0].renderTo(target, KiteMatrix.IDENTITY)
        assertEquals(0, declining.renders, "a step without a backdrop renders nothing")
        for (canvas in listOf(plain, declining.recording)) {
            val group = canvas.calls.filterIsInstance<RecordingCanvas.Call.PushGroup>().single()
            assertEquals(false, group.isolated)
            assertEquals(true, group.knockout)
            assertEquals(3, fills(canvas).size, "the page and each square, once")
        }
        val screen = StepCanvas(null)
        PdfDocument.open(screenGroup).pages[0].renderTo(screen, KiteMatrix.IDENTITY)
        assertEquals(0, screen.renders)
        assertEquals(KiteBlendMode.Screen, screen.recording.calls.filterIsInstance<RecordingCanvas.Call.PushGroup>().single().blendMode)
        assertEquals(2, fills(screen.recording).size)
    }

    @Test
    fun a_knockout_group_past_the_budget_paints_as_a_layer() {
        // Two squares, three renders each, of a box of 100 million pixels.
        val canvas = StepCanvas(green, side = 10_000)
        PdfDocument.open(knockoutGroup).pages[0].renderTo(canvas, KiteMatrix.IDENTITY)
        assertEquals(0, canvas.renders)
        assertEquals(true, canvas.recording.calls.filterIsInstance<RecordingCanvas.Call.PushGroup>().single().knockout)
        // A plain group of the same box renders twice, and its budget is the canvas's.
        val plain = StepCanvas(green, side = 10_000)
        PdfDocument.open(screenGroup).pages[0].renderTo(plain, KiteMatrix.IDENTITY)
        assertEquals(2, plain.renders)
    }

    private fun assertArgb(expected: Int, actual: Int) {
        val close = intArrayOf(24, 16, 8, 0).all { abs(((expected ushr it) and 0xFF) - ((actual ushr it) and 0xFF)) <= 1 }
        assertTrue(close, "expected ${expected.toUInt().toString(16)}, got ${actual.toUInt().toString(16)}")
    }

    @Test
    fun the_backdrop_comes_out_of_the_group() {
        // A yellow square at half alpha multiplied onto green: over the page it stays green, and
        // alone it is yellow at half alpha. Without the page, the group is green at half alpha:
        // αg × C = αn × Cn − α0 × (1 − αg) × C0 = (0, 1, 0) − 0.5 × (0, 1, 0).
        val backdrop = KiteRaster(1, 1, intArrayOf(0xFF00FF00.toInt()))
        val over = KiteRaster(1, 1, intArrayOf(0xFF00FF00.toInt()))
        val alone = KiteRaster(1, 1, intArrayOf(0x80FFFF00.toInt()))
        assertArgb(0x8000FF00.toInt(), GroupRasters.withoutBackdrop(backdrop, over, alone).pixels[0])
        // Where the group paints nothing it is transparent, and on a transparent backdrop it is what it painted.
        assertEquals(0, GroupRasters.withoutBackdrop(backdrop, backdrop, KiteRaster(1, 1)).pixels[0])
        val nothing = KiteRaster(1, 1)
        assertArgb(0x80FFFF00.toInt(), GroupRasters.withoutBackdrop(nothing, alone, alone).pixels[0])
    }

    @Test
    fun a_knockout_object_replaces_the_ones_before_it_within_its_shape() {
        val backdrop = KiteRaster(2, 1, intArrayOf(0xFF00FF00.toInt(), 0xFF00FF00.toInt()))
        val group = GroupRasters.Knockout(backdrop)
        // A red square over both pixels.
        group.add(
            over = KiteRaster(2, 1, intArrayOf(0xFFFF0000.toInt(), 0xFFFF0000.toInt())),
            shape = KiteRaster(2, 1, intArrayOf(0xFFFF0000.toInt(), 0xFFFF0000.toInt())),
            alone = KiteRaster(2, 1, intArrayOf(0xFFFF0000.toInt(), 0xFFFF0000.toInt())),
        )
        // Yellow multiplied onto the page over the second pixel only: green there.
        group.add(
            over = KiteRaster(2, 1, intArrayOf(0xFF00FF00.toInt(), 0xFF00FF00.toInt())),
            shape = KiteRaster(2, 1, intArrayOf(0, 0xFFFFFF00.toInt())),
            alone = KiteRaster(2, 1, intArrayOf(0, 0xFFFFFF00.toInt())),
        )
        val result = group.result().pixels
        assertArgb(0xFFFF0000.toInt(), result[0])
        assertArgb(0xFF00FF00.toInt(), result[1])
    }
}
