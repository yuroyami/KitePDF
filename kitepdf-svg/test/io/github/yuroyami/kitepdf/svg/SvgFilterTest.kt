package io.github.yuroyami.kitepdf.svg

import io.github.yuroyami.kitepdf.core.KiteRectangle
import io.github.yuroyami.kitepdf.core.render.KiteCanvas
import io.github.yuroyami.kitepdf.core.render.KiteMatrix
import io.github.yuroyami.kitepdf.core.render.KiteRaster
import io.github.yuroyami.kitepdf.core.render.KiteRasterScope
import io.github.yuroyami.kitepdf.core.render.KiteRasterStep
import io.github.yuroyami.kitepdf.core.render.RecordingCanvas
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** How a filtered element reaches a canvas, with or without raster steps (#209). */
class SvgFilterTest {

    private fun parse(body: String) = assertNotNull(
        SvgImage.parse("""<svg xmlns="http://www.w3.org/2000/svg" width="100" height="100">$body</svg>""".encodeToByteArray()),
    )

    private fun fills(canvas: RecordingCanvas) = canvas.calls.filterIsInstance<RecordingCanvas.Call.Fill>()

    @Test
    fun a_canvas_without_raster_steps_draws_the_element_unfiltered() {
        val image = parse("""<filter id="f"><feGaussianBlur stdDeviation="3"/></filter><rect x="10" y="10" width="20" height="20" fill="#f00" filter="url(#f)"/>""")
        val canvas = RecordingCanvas()
        image.render(canvas, KiteMatrix.IDENTITY)
        assertEquals(1, fills(canvas).size, "the rectangle still draws")
    }

    @Test
    fun an_empty_region_or_an_empty_filter_draws_nothing_on_any_canvas() {
        val image = parse(
            """<filter id="b"><feGaussianBlur stdDeviation="2"/></filter><filter id="e"/>""" +
                """<line x1="10" y1="20" x2="90" y2="20" stroke="#000" filter="url(#b)"/>""" +
                """<rect x="10" y="40" width="20" height="20" filter="url(#e)"/>""",
        )
        val canvas = RecordingCanvas()
        image.render(canvas, KiteMatrix.IDENTITY)
        assertTrue(canvas.calls.none { it is RecordingCanvas.Call.Fill || it is RecordingCanvas.Call.Stroke })
    }

    @Test
    fun an_invalid_filter_is_ignored() {
        val image = parse(
            """<rect x="10" y="10" width="20" height="20" filter="url(#missing)"/>""" +
                """<rect x="40" y="10" width="20" height="20" style="filter: wobble(3)"/>""",
        )
        val canvas = RecordingCanvas()
        image.render(canvas, KiteMatrix.IDENTITY)
        assertEquals(2, fills(canvas).size)
    }

    /** A canvas whose raster step records its region and paints the content as it is. */
    private class StepCanvas(private val inner: RecordingCanvas = RecordingCanvas()) : KiteCanvas by inner {
        val regions = ArrayList<KiteRectangle>()
        val drawn = ArrayList<Pair<KiteRaster, Double>>()
        val calls get() = inner.calls
        override fun rasterStep(region: KiteRectangle, ctm: KiteMatrix, step: KiteRasterStep): Boolean {
            regions += region
            return step.run(object : KiteRasterScope {
                override val width = 10
                override val height = 10
                override val toPixels = KiteMatrix.IDENTITY
                override fun backdrop(): KiteRaster? = null
                override fun render(initial: KiteRaster?, content: () -> Unit): KiteRaster = KiteRaster(10, 10).also { content() }
                override fun draw(raster: KiteRaster, alpha: Double, blendMode: io.github.yuroyami.kitepdf.core.render.KiteBlendMode) {
                    drawn += raster to alpha
                }
            })
        }
    }

    @Test
    fun the_filter_region_defaults_to_the_box_widened_by_a_tenth_and_the_opacity_waits_for_the_filter() {
        val image = parse(
            """<filter id="f"><feOffset dx="1"/></filter><rect x="10" y="20" width="50" height="30" fill="#f00" opacity="0.4" filter="url(#f)"/>""",
        )
        val canvas = StepCanvas()
        image.render(canvas, KiteMatrix.IDENTITY)
        val region = canvas.regions.single()
        assertEquals(5.0, region.left, 1e-9)
        assertEquals(17.0, region.bottom, 1e-9)
        assertEquals(65.0, region.right, 1e-9)
        assertEquals(53.0, region.top, 1e-9)
        assertEquals(0.4, canvas.drawn.single().second, 1e-9)
        // The content painted for the filter leaves the element's opacity out.
        assertEquals(1.0, canvas.calls.filterIsInstance<RecordingCanvas.Call.Fill>().single().alpha, 1e-9)
    }

    @Test
    fun a_chain_of_functions_widens_the_region_by_each_blur() {
        val image = parse("""<rect x="10" y="10" width="20" height="20" style="filter: blur(2px) blur(1px)"/>""")
        val canvas = StepCanvas()
        image.render(canvas, KiteMatrix.IDENTITY)
        // Three deviations for each blur: 6 and then 3 more.
        assertEquals(1.0, canvas.regions.single().left, 1e-9)
        assertEquals(39.0, canvas.regions.single().right, 1e-9)
    }
}
