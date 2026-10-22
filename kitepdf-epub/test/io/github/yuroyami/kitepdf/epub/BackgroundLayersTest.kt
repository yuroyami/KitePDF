package io.github.yuroyami.kitepdf.epub

import io.github.yuroyami.kitepdf.core.KiteRectangle
import io.github.yuroyami.kitepdf.core.render.RecordingCanvas
import io.github.yuroyami.kitepdf.core.render.RgbColor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Every layer of `background-image` paints, the first on top, each tiled by its
 * `background-size`, and a gradient's stops may differ in alpha (CSS Backgrounds 3, 3; CSS
 * Images 3, 3.5.3) (#503).
 */
class BackgroundLayersTest {

    private val blue = RgbColor(0.0, 0.0, 1.0)

    private fun fills(body: String): List<RecordingCanvas.Call.Fill> {
        val pages = EpubDocument.open(EpubFixtures.epub(body), EpubSettings(pageWidth = 400.0, pageHeight = 640.0)).pages
        return pages.flatMap { page -> RecordingCanvas().also { page.renderTo(it) }.calls }
            .filterIsInstance<RecordingCanvas.Call.Fill>()
    }

    private fun RecordingCanvas.Call.Fill.box(): KiteRectangle = path.bounds(ctm)!!

    @Test
    fun a_grid_of_two_gradient_layers_paints_its_lines() {
        // The idiom of the W3C tests lay-pp-xhtml-icb and the like, at a 20 pixel cell.
        val grid = "color: #0000ff; background-color: white; width: 100px; height: 100px; " +
            "background-image: linear-gradient(currentColor 1px, transparent 1px), linear-gradient(to right, currentColor 1px, transparent 1px); " +
            "background-size: 20px 20px"
        val lines = fills("""<div style="$grid"></div>""").filter { it.color == blue }
        // 100 pixels hold five cells a side, so each layer paints 25 tiles of one line.
        val across = lines.filter { kotlin.math.abs(it.box().height - 0.75) < 1e-6 }
        val down = lines.filter { kotlin.math.abs(it.box().width - 0.75) < 1e-6 }
        assertEquals(25, across.size, "the lines across, one pixel high: $lines")
        assertEquals(25, down.size, "the lines down, one pixel wide")
        assertTrue(across.all { kotlin.math.abs(it.box().width - 15.0) < 1e-6 && it.alpha == 1.0 })
        val rows = across.map { it.box().top }.distinctBy { kotlin.math.round(it * 1000) }.sortedDescending()
        assertEquals(5, rows.size, "one line atop each row of cells")
        for ((a, b) in rows.zipWithNext()) assertEquals(15.0, a - b, 1e-6, "a cell is 20 pixels high")
        // The first layer is on top, so it paints last.
        assertTrue(lines.indexOf(across.first()) > lines.indexOf(down.last()), "the first layer paints over the second")
    }

    @Test
    fun a_gradient_tiles_by_its_background_size() {
        val style = "width: 100px; height: 100px; background-image: linear-gradient(#ff0000, #0000ff); background-size: 50% 50%"
        val tiles = fills("""<div style="$style"></div>""").filter { it.color != RgbColor(1.0, 1.0, 1.0) }
        assertEquals(4, tiles.size, "two tiles a side: $tiles")
        assertTrue(tiles.all { kotlin.math.abs(it.box().width - 37.5) < 1e-6 && kotlin.math.abs(it.box().height - 37.5) < 1e-6 })
    }

    @Test
    fun a_fade_to_transparent_keeps_its_colour_and_loses_its_alpha() {
        // Stops interpolate premultiplied, so transparent black does not darken the red (CSS Images 3, 3.5.3).
        val style = "width: 100px; height: 20px; background-image: linear-gradient(to right, #ff0000, transparent)"
        val strips = fills("""<div style="$style"></div>""").filter { it.color != RgbColor(1.0, 1.0, 1.0) }
        assertTrue(strips.size > 4, "a smooth fade paints in strips: $strips")
        assertTrue(strips.all { it.color.r > 0.999 && it.color.g < 1e-9 && it.color.b < 1e-9 }, "every strip is red")
        val byX = strips.sortedBy { it.box().left }
        assertTrue(byX.zipWithNext().all { (a, b) -> a.alpha > b.alpha }, "alpha falls to the right")
        assertTrue(byX.first().alpha > 0.9 && byX.last().alpha < 0.1)
        assertEquals(75.0, byX.last().box().right - byX.first().box().left, 1e-6, "the strips span the box")
    }

    @Test
    fun the_shorthand_sets_each_layer() {
        val style = "color: #0000ff; width: 100px; height: 100px; " +
            "background: linear-gradient(currentColor 1px, transparent 1px) 0 0 / 50px 50px, linear-gradient(to right, currentColor 1px, transparent 1px) 0 0 / 25px 50px white"
        val lines = fills("""<div style="$style"></div>""").filter { it.color == blue }
        assertEquals(4, lines.count { kotlin.math.abs(it.box().height - 0.75) < 1e-6 }, "two by two cells of the first layer")
        assertEquals(8, lines.count { kotlin.math.abs(it.box().width - 0.75) < 1e-6 }, "four by two cells of the second")
    }

    @Test
    fun a_box_taller_than_the_tile_budget_paints_its_tiles_on_every_page() {
        // Some 20 by 1000 cells are more tiles than one box may paint, but each page shows a part of it.
        val style = "color: #0000ff; width: 100px; " +
            "background-image: linear-gradient(currentColor 1px, transparent 1px); background-size: 5px 5px"
        val text = (1..150).joinToString("") { "<p style='color: black'>Line $it</p>" }
        val pages = EpubDocument.open(EpubFixtures.epub("""<div style="$style">$text</div>"""), EpubSettings(pageWidth = 400.0, pageHeight = 640.0)).pages
        assertTrue(pages.size > 2, "the box runs over ${pages.size} pages")
        for ((i, page) in pages.withIndex()) {
            val lines = RecordingCanvas().also { page.renderTo(it) }.calls.filterIsInstance<RecordingCanvas.Call.Fill>().count { it.color == blue }
            assertTrue(lines > 0, "page $i paints its part of the grid")
        }
    }
}
