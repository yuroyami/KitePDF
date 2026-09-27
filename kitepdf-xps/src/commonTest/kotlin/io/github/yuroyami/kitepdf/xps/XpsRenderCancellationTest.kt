package io.github.yuroyami.kitepdf.xps

import io.github.yuroyami.kitepdf.core.KiteCancellation
import io.github.yuroyami.kitepdf.core.render.KiteMatrix
import io.github.yuroyami.kitepdf.core.render.RecordingCanvas
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * An XPS page stops a cancelled render before its next element. It drew every element however
 * early the render was cancelled (#370).
 */
class XpsRenderCancellationTest {

    private val page = XpsDocument.open(
        XpsFixtures.packageBytes((0 until 10).joinToString("") { """<Path Fill="#ff0000" Data="M$it,0 L${it + 1},0 ${it + 1},1Z"/>""" }),
    ).pages.single()

    private fun fills(cancellation: KiteCancellation?): Int {
        val canvas = RecordingCanvas()
        if (cancellation == null) page.renderTo(canvas, KiteMatrix.IDENTITY) else page.renderTo(canvas, KiteMatrix.IDENTITY, cancellation)
        assertTrue(canvas.calls.last() is RecordingCanvas.Call.EndPage, "the canvas was left open")
        assertEquals(canvas.calls.count { it is RecordingCanvas.Call.PushClip }, canvas.calls.count { it is RecordingCanvas.Call.PopClip })
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
