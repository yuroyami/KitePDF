package io.github.yuroyami.kitepdf.epub

import io.github.yuroyami.kitepdf.render.Matrix
import io.github.yuroyami.kitepdf.render.RecordingCanvas
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** The SVG renderer ([SvgImage]) — shapes, path data, fill/stroke, viewBox, transforms. */
class SvgImageTest {

    private fun calls(svg: String): List<RecordingCanvas.Call> {
        val img = SvgImage.parse(svg.encodeToByteArray())
        assertNotNull(img, "SVG parses")
        val rc = RecordingCanvas()
        img.render(rc, Matrix.IDENTITY)
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
        val rc = RecordingCanvas(); img.render(rc, Matrix.IDENTITY)
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
        val rc = RecordingCanvas(); img.render(rc, Matrix.IDENTITY)
        val f = rc.calls.filterIsInstance<RecordingCanvas.Call.Fill>().single()
        assertEquals(5.0, f.ctm.e, 1e-6); assertEquals(7.0, f.ctm.f, 1e-6)
    }

    @Test
    fun fill_and_stroke_both_paint() {
        val c = calls("""<svg width="10" height="10"><rect width="8" height="8" fill="blue" stroke="green" stroke-width="1"/></svg>""")
        assertEquals(1, c.filterIsInstance<RecordingCanvas.Call.Fill>().size)
        assertEquals(1, c.filterIsInstance<RecordingCanvas.Call.Stroke>().size)
    }

    // ---- end-to-end through the EPUB pipeline --------------------------------

    private fun epubFills(body: String, extras: List<Pair<String, ByteArray>>): List<RecordingCanvas.Call.Fill> {
        val doc = EpubDocument.open(EpubFixtures.epub(body, extras))
        assertNotNull(doc)
        return doc.pages.flatMap { page ->
            RecordingCanvas().also { page.renderTo(it) }.calls.filterIsInstance<RecordingCanvas.Call.Fill>()
        }
    }

    @Test
    fun inline_svg_renders_in_epub() {
        val body = """<body><p>hi</p><svg width="60" height="40"><rect width="60" height="40" fill="#00ff00"/></svg></body>"""
        val fills = epubFills(body, emptyList())
        assertTrue(fills.any { it.color.g > 0.9 && it.color.r < 0.1 && it.color.b < 0.1 }, "inline SVG rect painted")
    }

    @Test
    fun svg_file_image_renders_in_epub() {
        val svg = """<svg width="60" height="40"><circle cx="30" cy="20" r="15" fill="blue"/></svg>"""
        val body = """<body><img src="pic.svg"/></body>"""
        val fills = epubFills(body, listOf("OEBPS/pic.svg" to svg.encodeToByteArray()))
        assertTrue(fills.any { it.color.b > 0.9 && it.color.r < 0.1 }, "SVG file image painted")
    }

    @Test
    fun explicit_width_height_attrs_size_the_image() {
        // A 100x60 SVG placed at width=50 height=30 => the paint CTM scales x by 0.5, y by 0.5.
        val svg = """<svg width="100" height="60"><rect width="100" height="60" fill="red"/></svg>"""
        val body = """<body><img src="p.svg" width="50" height="30" style="display:block"/></body>"""
        val fills = epubFills(body, listOf("OEBPS/p.svg" to svg.encodeToByteArray()))
        val red = fills.single { it.color.r > 0.9 && it.color.g < 0.1 }
        assertEquals(0.5, kotlin.math.abs(red.ctm.a), 1e-6, "explicit width 50 of a 100-wide SVG => x-scale 0.5")
        assertEquals(0.5, kotlin.math.abs(red.ctm.d), 1e-6, "explicit height 30 of a 60-tall SVG => y-scale 0.5")
    }
}
