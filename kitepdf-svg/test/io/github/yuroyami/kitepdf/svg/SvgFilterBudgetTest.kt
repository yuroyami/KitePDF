package io.github.yuroyami.kitepdf.svg

import io.github.yuroyami.kitepdf.core.render.KiteMatrix
import io.github.yuroyami.kitepdf.core.render.KiteRasterCanvas
import io.github.yuroyami.kitepdf.core.render.RgbColor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.TimeSource

/** The memory and the work of one SVG filter stay bounded whatever its markup (#992, #993, #994). */
class SvgFilterBudgetTest {

    private fun render(svg: String, size: Int): io.github.yuroyami.kitepdf.core.render.KiteRaster {
        val canvas = KiteRasterCanvas(size, size)
        canvas.clear(RgbColor.WHITE)
        SvgDocument.open(svg.encodeToByteArray()).pages.single().renderTo(canvas, KiteMatrix.IDENTITY)
        return canvas.toRaster()
    }

    private fun quickly(what: String, body: () -> Unit) {
        val start = TimeSource.Monotonic.markNow()
        body()
        assertTrue(start.elapsedNow().inWholeSeconds < 30, "$what took ${start.elapsedNow()}")
    }

    @Test
    fun aFilterOverALargeRegionShrinksBeforeItBecomesFloats() {
        // 3000 by 3000 is past the 4 million pixel budget, so the filter runs smaller and is drawn back (#992).
        val svg = """<svg xmlns="http://www.w3.org/2000/svg" width="3000" height="3000"><filter id="f"><feOffset dx="0" dy="0"/></filter>""" +
            """<rect width="3000" height="3000" fill="red" filter="url(#f)"/></svg>"""
        quickly("render") {
            val raster = render(svg, 3000)
            val p = raster[1500, 1500]
            assertEquals(0xFF, (p shr 16) and 0xFF, "the filtered rect still draws red: ${p.toUInt().toString(16)}")
        }
    }

    @Test
    fun namedResultsThatNothingReadsAreDropped() {
        // Each flood is named, and only the last result shows (#993).
        val floods = (0 until 32).joinToString("") { """<feFlood flood-color="red" result="r$it"/>""" }
        val svg = """<svg xmlns="http://www.w3.org/2000/svg" width="200" height="200"><filter id="f" filterUnits="userSpaceOnUse" x="0" y="0" width="200" height="200">""" +
            floods + """<feMerge><feMergeNode in="r3"/></feMerge></filter><rect width="200" height="200" fill="blue" filter="url(#f)"/></svg>"""
        val p = render(svg, 200)[100, 100]
        assertEquals(0xFF, (p shr 16) and 0xFF, "the merge of r3 is red: ${p.toUInt().toString(16)}")
    }

    @Test
    fun aFilterPastItsWorkBudgetDrawsNothingAndEnds() {
        val kernel = "1" + " 0".repeat(255)
        val convolutions = (0 until 8).joinToString("") { """<feConvolveMatrix order="16" kernelMatrix="$kernel"/>""" }
        val svg = """<svg xmlns="http://www.w3.org/2000/svg" width="1000" height="1000"><filter id="f" filterUnits="userSpaceOnUse" x="0" y="0" width="1000" height="1000">""" +
            convolutions + """</filter><rect width="1000" height="1000" fill="red" filter="url(#f)"/></svg>"""
        quickly("render") {
            val p = render(svg, 1000)[500, 500]
            assertEquals(0xFFFFFFFF.toInt(), p, "a filter in error draws nothing")
        }
    }
}
