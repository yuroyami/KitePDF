package io.github.yuroyami.kitepdf.javascript

import io.github.yuroyami.kitepdf.core.KiteLocation
import io.github.yuroyami.kitepdf.core.render.KiteMatrix
import io.github.yuroyami.kitepdf.core.render.RecordingCanvas
import io.github.yuroyami.kitepdf.nativerenderer.AwtCanvas
import java.awt.Color
import java.awt.image.BufferedImage
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.TestResult

/**
 * The pixels a canvas paints, through the desktop renderer (#501): the composite operations
 * and blend modes that need the canvas as an image, and erasing part of it.
 */
class CanvasPixelsTest {

    private val runners = ArrayList<EpubScriptRunner>()

    @AfterTest
    fun closeRunners() {
        runners.forEach { it.close() }
    }

    /** Reads the colour at a point of a 200 by 100 canvas, in canvas pixels. */
    private class Shot(private val image: BufferedImage, private val left: Double, private val top: Double) {
        fun at(x: Int, y: Int): Triple<Int, Int, Int> {
            val rgb = image.getRGB(((left + x * 0.75) * SCALE).roundToInt(), ((top + y * 0.75) * SCALE).roundToInt())
            return Triple(rgb shr 16 and 255, rgb shr 8 and 255, rgb and 255)
        }
    }

    /** Runs [script] on a canvas 200 by 100 pixels and paints the page on white. */
    private fun shoot(script: String): Shot {
        val book = ScriptBooks.chapter(
            """<div style="height: 4px; background: rgb(0, 255, 0)"></div><div><canvas id="c" width="200" height="100"></canvas></div>""" +
                """<script>var c = document.getElementById('c'), x = c.getContext('2d');$script</script>""",
        )
        val scripts = EpubScriptRunner(book).also { runners += it }
        runBlocking { scripts.chapterOpened(0) }
        assertEquals(emptyList(), scripts.failures.map { it.message })
        val page = book.page(KiteLocation(0, 0))
        // The green bar above the canvas gives where the canvas starts, in page points down from the top.
        val bar = RecordingCanvas().also { page.renderTo(it) }.calls.filterIsInstance<RecordingCanvas.Call.Fill>()
            .first { it.color.g > 0.9 && it.color.r < 0.1 }.let { it.path.bounds(it.ctm)!! }
        val w = (page.displayWidth * SCALE).toInt()
        val h = (page.displayHeight * SCALE).toInt()
        val image = BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB)
        val g = image.createGraphics()
        try {
            g.color = Color.WHITE
            g.fillRect(0, 0, w, h)
            page.renderTo(AwtCanvas(g), KiteMatrix(SCALE, 0.0, 0.0, -SCALE, 0.0, page.displayHeight * SCALE))
        } finally {
            g.dispose()
        }
        return Shot(image, bar.left, page.displayHeight - bar.bottom)
    }

    private fun assertColour(expected: Triple<Int, Int, Int>, actual: Triple<Int, Int, Int>, what: String) =
        assertTrue(
            abs(expected.first - actual.first) <= 2 && abs(expected.second - actual.second) <= 2 && abs(expected.third - actual.third) <= 2,
            "$what: expected $expected, got $actual",
        )

    @Test
    fun lighter_adds_the_colours_where_two_drawings_meet(): TestResult = scriptTest {
        val shot = shoot(
            "x.fillStyle = 'rgb(200, 0, 0)'; x.fillRect(0, 0, 120, 100);" +
                "x.globalCompositeOperation = 'lighter'; x.fillStyle = 'rgb(100, 0, 100)'; x.fillRect(80, 0, 120, 100);",
        )
        assertColour(Triple(200, 0, 0), shot.at(40, 50), "the first rectangle alone")
        assertColour(Triple(255, 0, 100), shot.at(100, 50), "the sum, clamped")
        assertColour(Triple(100, 0, 100), shot.at(160, 50), "the second rectangle alone")
    }

    @Test
    fun multiply_darkens_what_is_under_it(): TestResult = scriptTest {
        val shot = shoot(
            "x.fillStyle = 'rgb(255, 128, 0)'; x.fillRect(0, 0, 120, 100);" +
                "x.globalCompositeOperation = 'multiply'; x.fillStyle = 'rgb(128, 128, 255)'; x.fillRect(80, 0, 120, 100);",
        )
        assertColour(Triple(128, 64, 0), shot.at(100, 50), "the product")
        assertColour(Triple(128, 128, 255), shot.at(160, 50), "over white, the colour stays")
    }

    @Test
    fun clear_rect_and_source_in_cut_what_is_drawn(): TestResult = scriptTest {
        val shot = shoot(
            "x.fillStyle = '#f00'; x.fillRect(0, 0, 200, 100); x.clearRect(20, 20, 40, 40);" +
                "x.globalCompositeOperation = 'source-in'; x.fillStyle = '#00f'; x.fillRect(100, 0, 100, 100);",
        )
        assertColour(Triple(255, 255, 255), shot.at(40, 40), "the cleared hole shows the page")
        assertColour(Triple(255, 255, 255), shot.at(80, 50), "source-in keeps nothing where the new drawing is not")
        assertColour(Triple(0, 0, 255), shot.at(150, 50), "and the new colour where both are")
    }

    @Test
    fun a_shadow_paints_offset_under_the_shape(): TestResult = scriptTest {
        val shot = shoot(
            "x.shadowColor = 'rgb(0, 0, 255)'; x.shadowOffsetX = 30; x.shadowOffsetY = 0;" +
                "x.fillStyle = '#f00'; x.fillRect(20, 20, 40, 40);",
        )
        assertColour(Triple(255, 0, 0), shot.at(40, 40), "the shape")
        assertColour(Triple(0, 0, 255), shot.at(75, 40), "its shadow, 30 pixels to the right")
        assertColour(Triple(255, 255, 255), shot.at(120, 40), "nothing past it")
    }

    private companion object {
        const val SCALE = 4.0
    }
}
