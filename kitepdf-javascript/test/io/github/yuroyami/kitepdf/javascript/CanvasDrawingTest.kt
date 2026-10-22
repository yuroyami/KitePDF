package io.github.yuroyami.kitepdf.javascript

import io.github.yuroyami.kitepdf.core.KiteLocation
import io.github.yuroyami.kitepdf.core.KiteRectangle
import io.github.yuroyami.kitepdf.core.render.RecordingCanvas
import io.github.yuroyami.kitepdf.epub.EpubDocument
import io.github.yuroyami.kitepdf.epub.EpubPage
import kotlin.math.abs
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.TestResult

/**
 * What a script draws on a canvas shows on the page where the canvas sits (#501), and a later
 * drawing paints the page again.
 */
class CanvasDrawingTest {

    private val runners = ArrayList<EpubScriptRunner>()

    @AfterTest
    fun closeRunners() {
        runners.forEach { it.close() }
    }

    private fun runner(book: EpubDocument): EpubScriptRunner = EpubScriptRunner(book).also { runners += it }

    /** The bounds of each fill of [page] in [r], [g], [b] (0 to 255), in page points. */
    private fun fillsOf(page: EpubPage, r: Int, g: Int, b: Int): List<KiteRectangle> =
        RecordingCanvas().also { page.renderTo(it) }.calls.filterIsInstance<RecordingCanvas.Call.Fill>()
            .filter { abs(it.color.r * 255 - r) < 2 && abs(it.color.g * 255 - g) < 2 && abs(it.color.b * 255 - b) < 2 }
            .mapNotNull { it.path.bounds(it.ctm) }

    /** The left edge of the content box, from the green bar above the canvas. */
    private fun contentLeft(page: EpubPage): Double = fillsOf(page, 0, 255, 0).single().left

    private fun near(expected: Double, actual: Double, what: String) =
        assertTrue(abs(expected - actual) < 0.05, "$what: expected $expected, got $actual")

    private fun canvasBook(script: String, canvas: String = """<canvas id="c" width="200" height="100"></canvas>""") =
        ScriptBooks.chapter("""<div style="height: 4px; background: rgb(0, 255, 0)"></div><div>$canvas</div><script>var c = document.getElementById('c'), x = c.getContext('2d');$script</script>""")

    @Test
    fun a_rectangle_and_a_circle_land_where_the_canvas_sits(): TestResult = scriptTest {
        val book = canvasBook(
            "x.fillStyle = '#f00'; x.fillRect(10, 10, 80, 40);" +
                "x.fillStyle = 'rgb(0, 0, 255)'; x.beginPath(); x.arc(150, 50, 20, 0, Math.PI * 2); x.fill();",
        )
        val scripts = runner(book)
        scripts.chapterOpened(0)
        val page = book.page(KiteLocation(0, 0))
        val red = fillsOf(page, 255, 0, 0).single()
        val blue = fillsOf(page, 0, 0, 255).single()
        // A CSS pixel is 0.75 points.
        near(60.0, red.width, "the rectangle's width")
        near(30.0, red.height, "the rectangle's height")
        near(30.0, blue.width, "the circle's width")
        near(contentLeft(page) + 10 * 0.75, red.left, "the rectangle's left")
        near(contentLeft(page) + 130 * 0.75, blue.left, "the circle's left")
        near(red.top, blue.top + 15.0, "the rectangle's top against the circle's, which runs up the page")
        assertEquals(emptyList(), scripts.failures.map { it.message })
    }

    @Test
    fun the_css_size_scales_the_drawing(): TestResult = scriptTest {
        val book = canvasBook(
            "x.fillStyle = '#f00'; x.fillRect(0, 0, 100, 50);",
            canvas = """<canvas id="c" width="100" height="50" style="width: 200px; height: 150px"></canvas>""",
        )
        runner(book).chapterOpened(0)
        val red = fillsOf(book.page(KiteLocation(0, 0)), 255, 0, 0).single()
        near(150.0, red.width, "the width")
        near(112.5, red.height, "the height")
    }

    @Test
    fun a_tap_that_draws_again_paints_the_page_again(): TestResult = scriptTest {
        val book = canvasBook(
            "x.fillStyle = '#f00'; x.fillRect(0, 0, 200, 100);" +
                "c.addEventListener('click', function () { x.clearRect(0, 0, 200, 100); x.fillStyle = '#00f'; x.fillRect(50, 25, 100, 50); });",
        )
        val scripts = runner(book)
        scripts.chapterOpened(0)
        val page = book.page(KiteLocation(0, 0))
        val red = fillsOf(page, 255, 0, 0).single()
        assertTrue(fillsOf(page, 0, 0, 255).isEmpty())

        // A tap is in display space, which runs down the page from its top 200 points up.
        scripts.tap(page, red.left + red.width / 2, 200.0 - (red.top + red.bottom) / 2)

        val after = book.page(KiteLocation(0, 0))
        assertTrue(fillsOf(after, 255, 0, 0).isEmpty(), "clearRect over the whole canvas wiped the red")
        val blue = fillsOf(after, 0, 0, 255).single()
        near(75.0, blue.width, "the new rectangle's width")
        assertEquals(emptyList(), scripts.failures.map { it.message })
    }

    @Test
    fun setting_the_width_wipes_the_canvas_and_resizes_it(): TestResult = scriptTest {
        val book = canvasBook("x.fillStyle = '#f00'; x.fillRect(0, 0, 200, 100); c.width = 40; x.fillStyle = '#00f'; x.fillRect(0, 0, 40, 40);")
        runner(book).chapterOpened(0)
        val page = book.page(KiteLocation(0, 0))
        assertTrue(fillsOf(page, 255, 0, 0).isEmpty())
        val blue = fillsOf(page, 0, 0, 255).single()
        near(30.0, blue.width, "the canvas is 40 pixels wide now")
    }

    @Test
    fun one_canvas_drawn_on_another_copies_its_content(): TestResult = scriptTest {
        val book = canvasBook(
            "var d = document.createElement('canvas'); d.width = 20; d.height = 20;" +
                "var y = d.getContext('2d'); y.fillStyle = '#00f'; y.fillRect(0, 0, 20, 20);" +
                "x.drawImage(d, 100, 10, 40, 40);",
        )
        val scripts = runner(book)
        scripts.chapterOpened(0)
        val page = book.page(KiteLocation(0, 0))
        val blue = fillsOf(page, 0, 0, 255)
        assertNotNull(blue.singleOrNull(), "one blue square: $blue")
        near(30.0, blue.single().width, "drawn at twice its size")
        near(contentLeft(page) + 100 * 0.75, blue.single().left, "the square's left")
        assertEquals(emptyList(), scripts.failures.map { it.message })
    }

    @Test
    fun a_canvas_no_script_draws_on_paints_nothing(): TestResult = scriptTest {
        val book = canvasBook("")
        runner(book).chapterOpened(0)
        assertNull(fillsOf(book.page(KiteLocation(0, 0)), 0, 0, 0).firstOrNull { it.width > 100 }, "no black box")
    }
}
