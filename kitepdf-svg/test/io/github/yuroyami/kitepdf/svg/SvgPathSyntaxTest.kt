package io.github.yuroyami.kitepdf.svg

import io.github.yuroyami.kitepdf.core.render.KiteMatrix
import io.github.yuroyami.kitepdf.core.render.KitePath
import io.github.yuroyami.kitepdf.core.render.RecordingCanvas
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Path data, paint alpha and filter lists read by the SVG 2 grammar, salvaging what is in error. */
class SvgPathSyntaxTest {

    private fun points(d: String): List<Pair<Double, Double>> = SvgImage.parsePathData(d).segments.mapNotNull {
        when (it) {
            is KitePath.Segment.MoveTo -> it.x to it.y
            is KitePath.Segment.LineTo -> it.x to it.y
            is KitePath.Segment.CurveTo -> it.x3 to it.y3
            else -> null
        }
    }

    @Test
    fun compactNumbersAndArcFlagsRead() {
        // SVG 2, 9.3.9: a second dot starts a number, and a flag is one character (#696).
        assertEquals(listOf(0.5 to 0.5, 10.5 to 0.5), points("M.5.5L10.5.5"))
        val arc = points("M0 0a5 5 0 0110 10")
        assertEquals(10.0 to 10.0, arc.last())
    }

    @Test
    fun aCommandInErrorEndsThePath() {
        // SVG 2, 9.5.4: the path draws up to the last command read without error.
        assertEquals(listOf(0.0 to 0.0, 10.0 to 0.0), points("M0 0 L10 0 L20"))
        assertEquals(listOf(0.0 to 0.0, 10.0 to 0.0), points("M0 0 L10 0 X 30 30 L40 40"))
    }

    @Test
    fun anArcThatEndsWhereItStartsIsOmitted() {
        // SVG 1.1, F.6.2: no NaN reaches the path (#701).
        val path = SvgImage.parsePathData("M10 10 H50 A20 20 0 1 1 50 10 H90 V90 H10 Z")
        val coords = points("M10 10 H50 A20 20 0 1 1 50 10 H90 V90 H10 Z")
        assertTrue(coords.all { it.first.isFinite() && it.second.isFinite() }, "$coords")
        assertTrue(path.segments.none { it is KitePath.Segment.CurveTo })
    }

    private fun fill(attr: String): RecordingCanvas.Call.Fill? {
        val img = assertNotNull(SvgImage.parse("<svg width='10' height='10' xmlns='http://www.w3.org/2000/svg'><rect width='10' height='10' $attr/></svg>".encodeToByteArray()))
        val rc = RecordingCanvas()
        img.render(rc, KiteMatrix.IDENTITY, null)
        return rc.calls.filterIsInstance<RecordingCanvas.Call.Fill>().singleOrNull()
    }

    @Test
    fun theAlphaOfAPaintColourCounts() {
        // SVG 2, 13.2 and CSS Color 4: alpha is part of the colour (#723).
        assertEquals(0.5, fill("fill='rgba(255,0,0,0.5)'")!!.alpha, 1e-6)
        assertEquals(0.25, fill("fill='rgba(255,0,0,0.5)' fill-opacity='0.5'")!!.alpha, 1e-6)
        assertEquals(128 / 255.0, fill("fill='#ff000080'")!!.alpha, 1e-3)
        assertEquals(null, fill("fill='transparent'"), "transparent paints nothing")
        assertEquals(1.0, fill("fill='red'")!!.alpha, 1e-6)
    }

    @Test
    fun aFilterListWithAnInfiniteNumberDoesNotFailThePage() {
        // #737: 1e400 made NaN pixels, which threw out of toRaster.
        val svg = """<svg width="40" height="40" xmlns="http://www.w3.org/2000/svg">
            <filter id="f" color-interpolation-filters="sRGB"><feColorMatrix type="matrix" values="1e400 0 0 0 0  0 1 0 0 0  0 0 1 0 0  0 0 0 1 0"/></filter>
            <rect width="40" height="40" fill="red" filter="url(#f)"/></svg>"""
        val img = assertNotNull(SvgImage.parse(svg.encodeToByteArray()))
        img.render(RecordingCanvas(), KiteMatrix.IDENTITY, null)
    }
}
