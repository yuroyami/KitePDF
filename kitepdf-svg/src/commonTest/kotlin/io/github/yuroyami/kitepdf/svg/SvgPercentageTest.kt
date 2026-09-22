package io.github.yuroyami.kitepdf.svg

import io.github.yuroyami.kitepdf.core.render.KiteMatrix
import io.github.yuroyami.kitepdf.core.render.KitePath
import io.github.yuroyami.kitepdf.core.render.RecordingCanvas
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/** SVG 2, section 8.9: percentages use the nearest viewport's user-unit extent (#177). */
class SvgPercentageTest {
    private fun calls(body: String, attrs: String = "width='200' height='100'"): List<RecordingCanvas.Call> {
        val image = SvgImage.parse("<svg $attrs>$body</svg>".encodeToByteArray())
        assertNotNull(image)
        return RecordingCanvas().also { image.render(it, KiteMatrix.IDENTITY) }.calls
    }

    private fun assertPoint(x: Double, y: Double, actual: Pair<Double, Double>) {
        assertEquals(x, actual.first, 1e-8)
        assertEquals(y, actual.second, 1e-8)
    }

    private fun rectCorners(call: RecordingCanvas.Call.Fill): List<Pair<Double, Double>> = call.path.segments.mapNotNull {
        when (it) {
            is KitePath.Segment.MoveTo -> call.ctm.transformPoint(it.x, it.y)
            is KitePath.Segment.LineTo -> call.ctm.transformPoint(it.x, it.y)
            else -> null
        }
    }

    @Test
    fun percentage_rectangle_covers_the_viewport() {
        val rect = calls("<rect width='100%' height='100%'/>").filterIsInstance<RecordingCanvas.Call.Fill>().single()
        val corners = rectCorners(rect)
        assertPoint(0.0, 0.0, corners[0])
        assertPoint(200.0, 100.0, corners[2])
    }

    @Test
    fun coordinates_and_sizes_use_their_own_axis() {
        val rect = calls("<rect x='10%' y='20%' width='50%' height='30%'/>").filterIsInstance<RecordingCanvas.Call.Fill>().single()
        val corners = rectCorners(rect)
        assertPoint(20.0, 20.0, corners[0])
        assertPoint(120.0, 50.0, corners[2])
    }

    @Test
    fun viewbox_percentages_use_its_extent_without_adding_its_origin() {
        val rect = calls(
            "<rect x='25%' y='50%' width='50%' height='25%'/>",
            "width='200' height='100' viewBox='10 20 80 40'",
        ).filterIsInstance<RecordingCanvas.Call.Fill>().single()
        val corners = rectCorners(rect)
        assertPoint(25.0, 0.0, corners[0])
        assertPoint(125.0, 25.0, corners[2])
    }

    @Test
    fun circle_radius_uses_the_normalized_diagonal() {
        val circle = calls("<circle cx='50%' cy='50%' r='10%'/>").filterIsInstance<RecordingCanvas.Call.Fill>().single()
        val start = circle.path.segments.first() as KitePath.Segment.MoveTo
        assertPoint(100.0 + sqrt(25000.0) * 0.1, 50.0, start.x to start.y)
        val withViewBox = calls(
            "<circle cx='50%' cy='50%' r='10%'/>", "width='200' height='100' viewBox='0 0 100 100'",
        ).filterIsInstance<RecordingCanvas.Call.Fill>().single()
        val vbStart = withViewBox.path.segments.first() as KitePath.Segment.MoveTo
        assertPoint(110.0, 50.0, withViewBox.ctm.transformPoint(vbStart.x, vbStart.y))
    }

    @Test
    fun ellipse_and_rounded_rectangle_radii_use_their_respective_axes() {
        val ellipse = calls("<ellipse cx='50%' cy='50%' rx='25%' ry='25%'/>").filterIsInstance<RecordingCanvas.Call.Fill>().single()
        val start = ellipse.path.segments.first() as KitePath.Segment.MoveTo
        val quarter = ellipse.path.segments[1] as KitePath.Segment.CurveTo
        assertPoint(150.0, 50.0, start.x to start.y)
        assertPoint(100.0, 75.0, quarter.x3 to quarter.y3)
        val rect = calls("<rect width='100%' height='100%' rx='10%' ry='10%'/>").filterIsInstance<RecordingCanvas.Call.Fill>().single()
        val corner = rect.path.segments[2] as KitePath.Segment.CurveTo
        assertPoint(200.0, 10.0, corner.x3 to corner.y3)
    }

    @Test
    fun line_and_stroke_percentages_use_axis_and_diagonal_bases() {
        val line = calls("<line x1='10%' y1='20%' x2='90%' y2='80%' stroke='black' stroke-width='1%' stroke-dasharray='2%,1%' stroke-dashoffset='1%'/>")
            .filterIsInstance<RecordingCanvas.Call.Stroke>().single()
        val start = line.path.segments.first() as KitePath.Segment.MoveTo
        val end = line.path.segments[1] as KitePath.Segment.LineTo
        assertPoint(20.0, 20.0, start.x to start.y)
        assertPoint(180.0, 80.0, end.x to end.y)
        val unit = sqrt(25000.0) / 100
        assertEquals(unit, line.lineWidth, 1e-8)
        assertEquals(unit * 2, line.dashArray!![0], 1e-8)
        assertEquals(unit, line.dashPhase, 1e-8)
    }

    @Test
    fun nested_viewports_resolve_against_the_immediate_parent() {
        val rect = calls("""
            <svg x='25%' y='25%' width='50%' height='50%'>
              <svg x='25%' y='20%' width='50%' height='50%'>
                <rect width='100%' height='100%'/>
              </svg>
            </svg>
        """.trimIndent(), "width='400' height='200'").filterIsInstance<RecordingCanvas.Call.Fill>().single()
        val corners = rectCorners(rect)
        assertPoint(150.0, 70.0, corners[0])
        assertPoint(250.0, 120.0, corners[2])
    }

    @Test
    fun nested_viewbox_establishes_the_child_percentage_base() {
        val rect = calls("""
            <svg x='10%' y='10%' width='50%' height='50%' viewBox='0 0 20 10'>
              <rect x='50%' y='20%' width='50%' height='50%'/>
            </svg>
        """.trimIndent(), "width='400' height='200'").filterIsInstance<RecordingCanvas.Call.Fill>().single()
        val corners = rectCorners(rect)
        assertPoint(140.0, 40.0, corners[0])
        assertPoint(240.0, 90.0, corners[2])
    }

    @Test
    fun object_bounding_box_clip_measures_percentage_shapes() {
        val clip = calls("""
            <defs><clipPath id='cut' clipPathUnits='objectBoundingBox'><rect width='0.5' height='1'/></clipPath></defs>
            <rect x='10%' y='20%' width='50%' height='40%' clip-path='url(#cut)'/>
        """.trimIndent()).filterIsInstance<RecordingCanvas.Call.PushClip>().single()
        val first = clip.path.segments[0] as KitePath.Segment.MoveTo
        val last = clip.path.segments[2] as KitePath.Segment.LineTo
        assertPoint(20.0, 20.0, first.x to first.y)
        assertPoint(70.0, 60.0, last.x to last.y)
    }

    @Test
    fun object_bounding_box_clip_content_keeps_the_viewport_percentage_base() {
        // SVG 2, 8.11: 0.25% of a 200-unit viewport is 0.5 in the clip's
        // user space, which is then scaled by the subject's bounding box.
        for (child in listOf(
            "<rect x='0.125%' width='0.25%' height='1%'/>",
            "<use href='#slice' x='0.125%'/>",
        )) {
            val clip = calls("""
                <defs>
                  <rect id='slice' width='0.25%' height='1%'/>
                  <clipPath id='cut' clipPathUnits='objectBoundingBox'>$child</clipPath>
                </defs>
                <rect x='10%' y='20%' width='50%' height='40%' clip-path='url(#cut)'/>
            """.trimIndent()).filterIsInstance<RecordingCanvas.Call.PushClip>().single()
            val first = clip.path.segments[0] as KitePath.Segment.MoveTo
            val last = clip.path.segments[2] as KitePath.Segment.LineTo
            assertPoint(45.0, 20.0, first.x to first.y)
            assertPoint(95.0, 60.0, last.x to last.y)
        }
    }

    @Test
    fun text_and_use_positions_share_the_current_viewport() {
        val rendered = calls("""
            <defs><rect id='r' width='10%' height='20%'/></defs>
            <use href='#r' x='50%' y='25%'/>
            <text x='25%' y='50%'>A<tspan x='50%' y='75%'>B</tspan></text>
        """.trimIndent())
        val rect = rendered.filterIsInstance<RecordingCanvas.Call.Fill>().single()
        assertPoint(100.0, 25.0, rectCorners(rect)[0])
        val text = rendered.filterIsInstance<RecordingCanvas.Call.Glyphs>()
        assertPoint(50.0, 50.0, text[0].textToDevice.e to text[0].textToDevice.f)
        assertPoint(100.0, 75.0, text[1].textToDevice.e to text[1].textToDevice.f)
    }
}
