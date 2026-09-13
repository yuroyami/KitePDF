package io.github.yuroyami.kitepdf.svg

import io.github.yuroyami.kitepdf.core.render.KiteMatrix
import io.github.yuroyami.kitepdf.core.render.KitePath
import io.github.yuroyami.kitepdf.core.render.RecordingCanvas
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** The SVG renderer ([SvgImage]): shapes, path data, fill/stroke, viewBox, transforms. */
class SvgImageTest {

    private fun calls(svg: String): List<RecordingCanvas.Call> {
        val img = SvgImage.parse(svg.encodeToByteArray())
        assertNotNull(img, "SVG parses")
        val rc = RecordingCanvas()
        img.render(rc, KiteMatrix.IDENTITY)
        return rc.calls
    }

    private fun fills(svg: String) = calls(svg).filterIsInstance<RecordingCanvas.Call.Fill>()
    private fun strokes(svg: String) = calls(svg).filterIsInstance<RecordingCanvas.Call.Stroke>()

    @Test
    fun rect_fills_with_its_color() {
        val f = fills("""<svg width="100" height="100"><rect x="10" y="20" width="30" height="40" fill="#ff0000"/></svg>""")
        assertEquals(1, f.size)
        assertTrue(f[0].color.r > 0.9 && f[0].color.g < 0.1 && f[0].color.b < 0.1, "red fill")
        assertTrue(f[0].path.segments.isNotEmpty(), "rect builds a path")
    }

    @Test
    fun default_fill_is_black() {
        val f = fills("""<svg width="10" height="10"><circle cx="5" cy="5" r="4"/></svg>""")
        assertEquals(1, f.size)
        assertTrue(f[0].color.r < 0.01 && f[0].color.g < 0.01 && f[0].color.b < 0.01, "default black fill")
    }

    @Test
    fun path_data_and_stroke_only() {
        val c = calls("""<svg width="10" height="10"><path d="M0 0 L10 0 L10 10 Z" fill="none" stroke="black" stroke-width="2"/></svg>""")
        assertTrue(c.filterIsInstance<RecordingCanvas.Call.Fill>().isEmpty(), "fill:none paints no fill")
        val s = c.filterIsInstance<RecordingCanvas.Call.Stroke>()
        assertEquals(1, s.size)
        assertEquals(2.0, s[0].lineWidth, 1e-6)
        assertTrue(s[0].path.segments.size >= 3, "path has the moveto + linetos")
    }

    @Test
    fun curves_and_arcs_parse_without_error() {
        // Cubic, smooth-cubic, quadratic, and an elliptical arc.
        val f = fills("""<svg width="50" height="50"><path d="M0 0 C10 0 10 10 0 10 S-10 20 0 20 Q5 25 10 20 T20 20 A5 5 0 0 1 25 25 Z"/></svg>""")
        assertEquals(1, f.size)
        assertTrue(f[0].path.segments.size > 5, "arc expands to bézier segments")
    }

    @Test
    fun viewbox_scales_into_the_viewport() {
        val img = SvgImage.parse("""<svg width="100" height="100" viewBox="0 0 10 10"><rect width="10" height="10"/></svg>""".encodeToByteArray())
        assertNotNull(img)
        assertEquals(100.0, img.width, 1e-6)
        val rc = RecordingCanvas(); img.render(rc, KiteMatrix.IDENTITY)
        val f = rc.calls.filterIsInstance<RecordingCanvas.Call.Fill>().single()
        assertEquals(10.0, f.ctm.a, 1e-6, "viewBox 10 -> viewport 100 scales x10")
        assertEquals(10.0, f.ctm.d, 1e-6)
    }

    @Test
    fun group_transform_composes_into_ctm() {
        val img = SvgImage.parse(
            """<svg width="50" height="50"><g transform="translate(5,7)"><rect width="10" height="10"/></g></svg>""".encodeToByteArray(),
        )
        assertNotNull(img)
        val rc = RecordingCanvas(); img.render(rc, KiteMatrix.IDENTITY)
        val f = rc.calls.filterIsInstance<RecordingCanvas.Call.Fill>().single()
        assertEquals(5.0, f.ctm.e, 1e-6); assertEquals(7.0, f.ctm.f, 1e-6)
    }

    @Test
    fun fill_and_stroke_both_paint() {
        val c = calls("""<svg width="10" height="10"><rect width="8" height="8" fill="blue" stroke="green" stroke-width="1"/></svg>""")
        assertEquals(1, c.filterIsInstance<RecordingCanvas.Call.Fill>().size)
        assertEquals(1, c.filterIsInstance<RecordingCanvas.Call.Stroke>().size)
    }

    @Test
    fun non_finite_viewport_dimensions_are_rejected() {
        assertEquals(null, SvgImage.parse("<svg width=\"NaN\" height=\"10\"/>".encodeToByteArray()))
        assertEquals(null, SvgImage.parse("<svg width=\"10\" height=\"Infinity\"/>".encodeToByteArray()))
    }

    @Test
    fun finding_an_svg_below_deep_wrappers_is_iterative() {
        val wrapped = buildString {
            repeat(5_000) { append("<g>") }
            append("<svg width=\"10\" height=\"10\"/>")
            repeat(5_000) { append("</g>") }
        }
        assertNotNull(SvgImage.parse(wrapped.encodeToByteArray()))
    }

    @Test
    fun a_transform_in_a_style_declaration_applies() {
        val f = fills("""<svg width="50" height="50"><g style="transform: translate(5px, 7px)"><rect width="10" height="10"/></g></svg>""")
        assertEquals(5.0, f.single().ctm.e, 1e-6)
        assertEquals(7.0, f.single().ctm.f, 1e-6)
    }

    @Test
    fun a_wider_viewport_centres_the_viewbox_instead_of_stretching_it() {
        // xMidYMid meet: one uniform scale, then half the leftover width on each side.
        val ctm = fills("""<svg width="200" height="100" viewBox="0 0 100 100"><circle cx="50" cy="50" r="40"/></svg>""").single().ctm
        assertEquals(1.0, ctm.a, 1e-9)
        assertEquals(1.0, ctm.d, 1e-9)
        assertEquals(50.0, ctm.e, 1e-9)
        assertEquals(0.0, ctm.f, 1e-9)
    }

    @Test
    fun preserve_aspect_ratio_none_stretches_and_slice_fills_and_clips() {
        val none = fills("""<svg width="200" height="100" viewBox="0 0 100 100" preserveAspectRatio="none"><rect width="10" height="10"/></svg>""").single()
        assertEquals(2.0, none.ctm.a, 1e-9)
        assertEquals(1.0, none.ctm.d, 1e-9)

        val slice = calls("""<svg width="200" height="100" viewBox="0 0 100 100" preserveAspectRatio="xMinYMid slice"><rect width="10" height="10"/></svg>""")
        val fill = slice.filterIsInstance<RecordingCanvas.Call.Fill>().single()
        assertEquals(2.0, fill.ctm.a, 1e-9)
        assertEquals(2.0, fill.ctm.d, 1e-9)
        assertEquals(0.0, fill.ctm.e, 1e-9)
        assertEquals(-50.0, fill.ctm.f, 1e-9, "the 200-high content is centred on the 100-high viewport")
        assertTrue(slice.first() is RecordingCanvas.Call.PushClip, "slice clips to the viewport (got $slice)")
        assertTrue(slice.last() is RecordingCanvas.Call.PopClip)
    }

    @Test
    fun physical_units_resolve_as_css_pixels() {
        // 96 user units to the inch, so 72pt and 1in are both 96, and em follows the element's font size.
        val f = fills("""<svg width="300" height="300"><rect width="72pt" height="1in"/><rect width="2em" height="1cm" font-size="10"/></svg>""")
        val a = bounds(f[0].path)
        assertEquals(96.0, a[2], 1e-9)
        assertEquals(96.0, a[3], 1e-9)
        val b = bounds(f[1].path)
        assertEquals(20.0, b[2], 1e-9)
        assertEquals(96.0 / 2.54, b[3], 1e-9)

        val sized = SvgImage.parse("""<svg width="1in" height="72pt"/>""".encodeToByteArray())
        assertNotNull(sized)
        assertEquals(96.0, sized.width, 1e-9)
        assertEquals(96.0, sized.height, 1e-9)
    }

    @Test
    fun stroke_dash_cap_join_and_miter_reach_the_canvas() {
        val s = strokes(
            """<svg width="20" height="20"><path d="M0 0 L10 0" stroke="black" stroke-dasharray="4 2" stroke-linecap="round" stroke-linejoin="round"/></svg>""",
        ).single()
        assertEquals(listOf(4.0, 2.0), s.dashArray)
        assertEquals(1, s.lineCap)
        assertEquals(1, s.lineJoin)
        assertEquals(4.0, s.miterLimit, 1e-9, "the initial SVG miter limit is 4, not the PDF 10")
    }

    @Test
    fun stroke_properties_inherit_and_an_odd_dash_list_repeats() {
        val s = strokes(
            """<svg width="20" height="20"><g stroke-dasharray="3" stroke-dashoffset="1" stroke-linejoin="bevel" stroke-miterlimit="8"><line x2="10" stroke="black"/></g></svg>""",
        ).single()
        assertEquals(listOf(3.0, 3.0), s.dashArray)
        assertEquals(1.0, s.dashPhase, 1e-9)
        assertEquals(2, s.lineJoin)
        assertEquals(8.0, s.miterLimit, 1e-9)
        assertEquals(null, strokes("""<svg width="9" height="9"><line x2="5" stroke="black" stroke-dasharray="0 0"/></svg>""").single().dashArray)
    }

    @Test
    fun a_nested_svg_opens_its_own_viewport() {
        val c = calls(
            """<svg width="200" height="200"><svg x="50" y="60" width="100" height="100" viewBox="0 0 10 10"><rect width="10" height="10"/></svg></svg>""",
        )
        val f = c.filterIsInstance<RecordingCanvas.Call.Fill>().single()
        assertEquals(10.0, f.ctm.a, 1e-9, "the inner viewBox maps 10 units onto 100")
        assertEquals(50.0, f.ctm.e, 1e-9)
        assertEquals(60.0, f.ctm.f, 1e-9)
        assertTrue(c.any { it is RecordingCanvas.Call.PushClip }, "the inner viewport clips what it holds")
    }

    private fun bounds(path: KitePath): DoubleArray {
        val xs = ArrayList<Double>()
        val ys = ArrayList<Double>()
        for (seg in path.segments) when (seg) {
            is KitePath.Segment.MoveTo -> { xs += seg.x; ys += seg.y }
            is KitePath.Segment.LineTo -> { xs += seg.x; ys += seg.y }
            is KitePath.Segment.CurveTo -> { xs += seg.x3; ys += seg.y3 }
            is KitePath.Segment.QuadTo -> { xs += seg.x2; ys += seg.y2 }
            KitePath.Segment.Close -> {}
        }
        return doubleArrayOf(xs.min(), ys.min(), xs.max(), ys.max())
    }
}
