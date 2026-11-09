package io.github.yuroyami.kitepdf.svg

import io.github.yuroyami.kitepdf.core.render.KiteMatrix
import io.github.yuroyami.kitepdf.core.render.RecordingCanvas
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.TimeSource

/**
 * A use that leads back to itself renders nothing, and the instances of one render stay within a
 * budget of elements, so a small file cannot draw billions of shapes (#991).
 */
class SvgUseBudgetTest {

    private fun page(svg: String) = SvgDocument.open(svg.encodeToByteArray()).pages.single()

    private fun fills(svg: String): Int {
        val canvas = RecordingCanvas()
        page(svg).renderTo(canvas, KiteMatrix.IDENTITY)
        return canvas.calls.count { it is RecordingCanvas.Call.Fill }
    }

    private fun quickly(what: String, body: () -> Unit) {
        val start = TimeSource.Monotonic.markNow()
        body()
        // The bound catches a render with no budget, which draws 10^8 shapes and runs for hours. A
        // debug Kotlin/Native build draws the budget's 500,000 in about 20 s on CI's Linux runner.
        assertTrue(start.elapsedNow().inWholeSeconds < 120, "$what took ${start.elapsedNow()}")
    }

    @Test
    fun aGroupThatUsesItselfDrawsOnlyItsOwnShapes() {
        val svg = """<svg xmlns="http://www.w3.org/2000/svg" xmlns:xlink="http://www.w3.org/1999/xlink" width="10" height="10">""" +
            """<g id="a"><rect width="1" height="1"/><use href="#a"/><use href="#a"/><use href="#a"/></g></svg>"""
        quickly("render") { assertEquals(1, fills(svg)) }
    }

    @Test
    fun aChainThatFansOutStopsAtTheBudget() {
        val levels = 8
        val defs = StringBuilder("""<rect id="g0" width="1" height="1"/>""")
        for (k in 1..levels) defs.append("""<g id="g$k">""" + (0 until 10).joinToString("") { """<use href="#g${k - 1}"/>""" } + "</g>")
        val svg = """<svg xmlns="http://www.w3.org/2000/svg" width="10" height="10"><defs>$defs</defs><use href="#g$levels"/><rect width="2" height="2"/></svg>"""
        quickly("render") { assertTrue(fills(svg) <= 500_000) }
        quickly("text") { page(svg).textContent() }
        // A chain within the budget draws every shape.
        val small = """<svg xmlns="http://www.w3.org/2000/svg" width="10" height="10"><defs>""" +
            """<rect id="g0" width="1" height="1"/><g id="g1"><use href="#g0"/><use href="#g0"/></g><g id="g2"><use href="#g1"/><use href="#g1"/></g>""" +
            """</defs><use href="#g2"/></svg>"""
        assertEquals(4, fills(small))
    }
}
