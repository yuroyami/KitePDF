package io.github.yuroyami.kitepdf.javascript

import io.github.yuroyami.kitepdf.core.KiteLocation
import io.github.yuroyami.kitepdf.core.font.FontSpec
import io.github.yuroyami.kitepdf.core.render.KiteMatrix
import io.github.yuroyami.kitepdf.core.render.KitePath
import io.github.yuroyami.kitepdf.core.render.KiteRaster
import io.github.yuroyami.kitepdf.core.render.KiteRasterCanvas
import io.github.yuroyami.kitepdf.core.render.RecordingCanvas
import io.github.yuroyami.kitepdf.core.render.encodePng
import io.github.yuroyami.kitepdf.epub.EpubDocument
import kotlin.math.roundToInt
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.test.TestResult

/**
 * The pixels of a canvas that scripts read and write (#610): what `getImageData` reads of a
 * drawing, and what the page paints after `putImageData`. The page renders through
 * `KiteRasterCanvas`, so every platform checks the same pixels.
 */
class CanvasReadbackTest {

    private val runners = ArrayList<EpubScriptRunner>()

    @AfterTest
    fun closeRunners() {
        runners.forEach { it.close() }
    }

    /** Runs [script] on a canvas 40 by 20 pixels, and answers what it logged and the book. */
    private suspend fun run(
        script: String,
        files: Map<String, ByteArray> = emptyMap(),
        fontOutlines: ((String, FontSpec) -> KitePath?)? = null,
        before: String = "",
    ): Pair<List<String>, EpubDocument> {
        val book = ScriptBooks.chapter(
            """<div style="height: 4px; background: rgb(0, 255, 0)"></div>$before<div><canvas id="c" width="40" height="20"></canvas></div>""" +
                """<script>var c = document.getElementById('c'), x = c.getContext('2d');$script</script>""",
            binaryFiles = files,
        )
        val console = ArrayList<String>()
        val scripts = EpubScriptRunner(book, onConsole = { _, m -> console += m }, fontOutlines = fontOutlines).also { runners += it }
        scripts.chapterOpened(0)
        assertEquals(emptyList(), scripts.failures.map { it.message })
        return console to book
    }

    /** The page as pixels, one to a CSS pixel, and where the canvas starts on it. */
    private fun shoot(book: EpubDocument): Triple<KiteRaster, Int, Int> {
        val page = book.page(KiteLocation(0, 0))
        val bar = RecordingCanvas().also { page.renderTo(it) }.calls.filterIsInstance<RecordingCanvas.Call.Fill>()
            .first { it.color.g > 0.9 && it.color.r < 0.1 }.let { it.path.bounds(it.ctm)!! }
        val canvas = KiteRasterCanvas((page.displayWidth * SCALE).roundToInt(), (page.displayHeight * SCALE).roundToInt())
        canvas.clear()
        page.renderTo(canvas, KiteMatrix(SCALE, 0.0, 0.0, -SCALE, 0.0, page.displayHeight * SCALE))
        return Triple(canvas.toRaster(), (bar.left * SCALE).roundToInt(), ((page.displayHeight - bar.bottom) * SCALE).roundToInt())
    }

    private fun Int.rgba(): String = "${this ushr 16 and 255},${this ushr 8 and 255},${this and 255},${this ushr 24}"

    @Test
    fun a_book_image_drawn_into_a_canvas_reads_back(): TestResult = scriptTest {
        val png = KiteRaster(2, 1, intArrayOf(0xFFFF0000.toInt(), 0x800000FF.toInt())).encodePng()
        val (log) = run(
            // The image draws once it has loaded, before the window's load event.
            "window.onload = function () { x.drawImage(document.getElementById('i'), 0, 0);" +
                " console.log(Array.prototype.join.call(x.getImageData(0, 0, 3, 1).data, ',')); };",
            files = mapOf("pic.png" to png),
            before = """<img id="i" src="pic.png" alt=""/>""",
        )
        assertEquals(listOf("255,0,0,255,0,0,255,128,0,0,0,0"), log)
    }

    @Test
    fun put_image_data_is_what_the_page_paints(): TestResult = scriptTest {
        val (log, book) = run(
            "var d = x.createImageData(20, 20);" +
                "for (var i = 0; i < 400; i++) { d.data[i * 4] = i % 20 < 10 ? 255 : 0; d.data[i * 4 + 2] = i % 20 < 10 ? 0 : 255; d.data[i * 4 + 3] = 255; }" +
                "x.fillStyle = 'rgb(0, 128, 0)'; x.fillRect(0, 0, 40, 20); x.putImageData(d, 20, 0);" +
                "console.log(Array.prototype.join.call(x.getImageData(15, 5, 1, 1).data, ',') + ' ' +" +
                " Array.prototype.join.call(x.getImageData(25, 5, 1, 1).data, ',') + ' ' + Array.prototype.join.call(x.getImageData(35, 5, 1, 1).data, ','));",
        )
        assertEquals(listOf("0,128,0,255 255,0,0,255 0,0,255,255"), log)
        val (raster, left, top) = shoot(book)
        // The canvas sits below the 4 pixel bar.
        assertEquals("0,128,0,255", raster[left + 15, top + 4 + 5].rgba(), "the drawing beside the pixels")
        assertEquals("255,0,0,255", raster[left + 25, top + 4 + 5].rgba(), "the red half of the pixels")
        assertEquals("0,0,255,255", raster[left + 35, top + 4 + 5].rgba(), "the blue half of the pixels")
    }

    @Test
    fun a_dirty_rectangle_puts_only_its_own_pixels(): TestResult = scriptTest {
        // Each pixel of a 4 by 3 image has its own red, 10 for each column and 100 for each row.
        val (log) = run(
            "var d = x.createImageData(4, 3);" +
                "for (var i = 0; i < 12; i++) { d.data[i * 4] = (i % 4) * 10 + ((i / 4) | 0) * 100; d.data[i * 4 + 3] = 255; }" +
                "x.putImageData(d, 5, 5, 1, 1, 2, 2);" +
                "var row = function (y) { return Array.prototype.filter.call(x.getImageData(5, y, 4, 1).data, function (v, i) { return i % 4 === 0; }).join(' '); };" +
                "console.log(row(5) + ' | ' + row(6) + ' | ' + row(7));" +
                "var w = x.getImageData(0, 0, 40, 20); w.data[0] = 7; w.data[3] = 255; x.putImageData(w, 0, 0, 0, 0, 40, 1);" +
                "console.log(x.getImageData(0, 0, 1, 1).data[0] + ' ' + x.getImageData(5, 6, 4, 1).data[4]);",
        )
        assertEquals(listOf("0 0 0 0 | 0 110 120 0 | 0 210 220 0", "7 110"), log)
    }

    @Test
    fun drawing_after_put_image_data_goes_over_the_pixels(): TestResult = scriptTest {
        val (log, book) = run(
            "var d = x.createImageData(40, 20); for (var i = 0; i < 800; i++) { d.data[i * 4] = 255; d.data[i * 4 + 3] = 255; }" +
                "x.putImageData(d, 0, 0); x.fillStyle = 'rgba(0, 0, 255, 0.5)'; x.fillRect(20, 0, 20, 20);" +
                "console.log(Array.prototype.join.call(x.getImageData(10, 10, 1, 1).data, ',') + ' ' + Array.prototype.join.call(x.getImageData(30, 10, 1, 1).data, ','));",
        )
        assertEquals(listOf("255,0,0,255 127,0,128,255"), log)
        val (raster, left, top) = shoot(book)
        assertEquals("255,0,0,255", raster[left + 10, top + 4 + 10].rgba())
        assertEquals("127,0,128,255", raster[left + 30, top + 4 + 10].rgba())
    }

    @Test
    fun text_reads_back_through_the_font_outlines(): TestResult = scriptTest {
        // Every run is a box 600 units wide and 700 tall on the baseline: 12 by 14 pixels at 20px.
        val box: (String, FontSpec) -> KitePath? = { _, _ ->
            KitePath.Builder().apply { moveTo(0.0, 0.0); lineTo(600.0, 0.0); lineTo(600.0, 700.0); lineTo(0.0, 700.0); close() }.build()
        }
        val script = "x.font = '20px serif'; x.fillText('A', 2, 18);" +
            "console.log(x.getImageData(8, 10, 1, 1).data[3] + ' ' + x.getImageData(8, 2, 1, 1).data[3] + ' ' + x.getImageData(20, 10, 1, 1).data[3]);"
        assertEquals(listOf("255 0 0"), run(script, fontOutlines = box).first)
        assertEquals(listOf("0 0 0"), run(script).first, "without outlines text reads as blank")
    }

    @Test
    fun the_png_of_to_data_url_paints_the_same_pixels(): TestResult = scriptTest {
        val (_, book) = run(
            "var d = x.createImageData(40, 20);" +
                "for (var i = 0; i < 800; i++) { if (i % 40 < 20) { d.data[i * 4] = 255; d.data[i * 4 + 3] = 255; } else { d.data[i * 4 + 1] = 255; d.data[i * 4 + 3] = 128; } }" +
                "x.putImageData(d, 0, 0); document.getElementById('o').src = c.toDataURL();",
            before = """<img id="o" alt="" style="display: block"/>""",
        )
        val (raster, left, top) = shoot(book)
        // The image sits right below the 4 pixel bar, at its own size of 40 by 20.
        assertEquals("255,0,0,255", raster[left + 10, top + 4 + 10].rgba(), "the opaque half")
        assertEquals("0,255,0,128", raster[left + 30, top + 4 + 10].rgba(), "the half with alpha")
    }

    private companion object {
        /** Device pixels to a point, so a CSS pixel is one device pixel. */
        const val SCALE = 4.0 / 3.0
    }
}
