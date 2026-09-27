package io.github.yuroyami.kitepdf.svg

import io.github.yuroyami.kitepdf.core.KiteCancellation
import io.github.yuroyami.kitepdf.core.render.KiteMatrix
import io.github.yuroyami.kitepdf.core.render.RecordingCanvas
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * An SVG page stops a cancelled render before its next element. It drew every element however
 * early the render was cancelled (#370).
 */
class SvgRenderCancellationTest {

    private val page = SvgDocument.open(
        ("""<svg xmlns="http://www.w3.org/2000/svg" width="100" height="100"><g>""" +
            (0 until 10).joinToString("") { """<rect x="$it" y="0" width="1" height="1" fill="red"/>""" } +
            "</g></svg>").encodeToByteArray(),
    ).pages.single()

    private fun fills(cancellation: KiteCancellation?): Int {
        val canvas = RecordingCanvas()
        if (cancellation == null) page.renderTo(canvas, KiteMatrix.IDENTITY) else page.renderTo(canvas, KiteMatrix.IDENTITY, cancellation)
        assertTrue(canvas.calls.last() is RecordingCanvas.Call.EndPage, "the canvas was left open")
        return canvas.calls.count { it is RecordingCanvas.Call.Fill }
    }

    @Test
    fun a_cancelled_render_draws_no_element() {
        assertEquals(0, fills { true })
    }

    @Test
    fun a_render_cancelled_midway_draws_the_elements_before() {
        assertEquals(10, fills(null))
        var checks = 0
        val some = fills { ++checks > 4 }
        assertTrue(some in 1 until 10, "$some of 10 elements drew")
    }
}
