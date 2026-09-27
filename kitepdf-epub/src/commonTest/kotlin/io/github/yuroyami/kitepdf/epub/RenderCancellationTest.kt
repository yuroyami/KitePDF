package io.github.yuroyami.kitepdf.epub

import io.github.yuroyami.kitepdf.core.KiteCancellation
import io.github.yuroyami.kitepdf.core.KiteLocation
import io.github.yuroyami.kitepdf.core.render.KiteMatrix
import io.github.yuroyami.kitepdf.core.render.RecordingCanvas
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A book page stops a cancelled render between two paint steps. It drew every line of the page
 * however early the render was cancelled (#370).
 */
class RenderCancellationTest {

    private val page = EpubDocument.open(
        EpubFixtures.epubMultiSpine(listOf((1..12).joinToString("") { "<p>Line $it of the page.</p>" })),
        EpubSettings(pageWidth = 400.0, pageHeight = 800.0),
    ).page(KiteLocation(0, 0))

    private fun runs(cancellation: KiteCancellation?): Int {
        val canvas = RecordingCanvas()
        if (cancellation == null) page.renderTo(canvas, KiteMatrix.IDENTITY) else page.renderTo(canvas, KiteMatrix.IDENTITY, cancellation)
        assertTrue(canvas.calls.last() is RecordingCanvas.Call.EndPage, "the canvas was left open")
        return canvas.calls.count { it is RecordingCanvas.Call.Glyphs }
    }

    @Test
    fun a_cancelled_render_draws_no_line() {
        assertEquals(0, runs { true })
    }

    @Test
    fun a_render_cancelled_midway_draws_the_lines_before() {
        val all = runs(null)
        var checks = 0
        val some = runs { ++checks > 4 }
        assertTrue(some in 1 until all, "$some of $all runs drew")
    }
}
