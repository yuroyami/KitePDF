package io.github.yuroyami.kitepdf.svg

import io.github.yuroyami.kitepdf.core.render.KiteMatrix
import io.github.yuroyami.kitepdf.core.render.KiteShading
import io.github.yuroyami.kitepdf.core.render.RecordingCanvas
import io.github.yuroyami.kitepdf.core.xml.KiteXml
import io.github.yuroyami.kitepdf.core.xml.KiteXmlNode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** `<use>`, `<image>`, `<text>`, gradients and `clip-path`. */
class SvgFeaturesTest {

    private fun calls(svg: String, load: ((String) -> ByteArray?)? = null): List<RecordingCanvas.Call> {
        val img = SvgImage.parse(svg.encodeToByteArray())
        assertNotNull(img, "SVG parses")
        val rc = RecordingCanvas()
        img.render(rc, KiteMatrix.IDENTITY, load)
        return rc.calls
    }

    private fun fills(svg: String, load: ((String) -> ByteArray?)? = null) =
        calls(svg, load).filterIsInstance<RecordingCanvas.Call.Fill>()

    /* ─── use ────────────────────────────────────────────────────────────── */

    @Test
    fun use_paints_the_element_it_points_at() {
        val f = fills(
            """<svg width="100" height="100">
                 <defs><rect id="r" width="10" height="10" fill="red"/></defs>
                 <use href="#r" x="20" y="30"/>
               </svg>""",
        )
        assertEquals(1, f.size, "the defs copy does not paint, the use does")
        assertEquals(20.0, f[0].ctm.e, 1e-9)
        assertEquals(30.0, f[0].ctm.f, 1e-9)
    }

    @Test
    fun use_of_a_symbol_paints_its_children() {
        val f = fills(
            """<svg width="50" height="50">
                 <symbol id="s"><circle cx="5" cy="5" r="4" fill="blue"/></symbol>
                 <use href="#s"/>
               </svg>""",
        )
        assertEquals(1, f.size)
    }

    @Test
    fun a_use_cycle_stops_instead_of_hanging() {
        val f = fills(
            """<svg width="10" height="10">
                 <g id="a"><use href="#b"/></g>
                 <g id="b"><use href="#a"/><rect width="2" height="2" fill="red"/></g>
               </svg>""",
        )
        assertTrue(f.isNotEmpty(), "the rect still paints")
    }

    /* ─── image ──────────────────────────────────────────────────────────── */

    @Test
    fun an_image_from_a_loader_draws() {
        val drawn = calls(
            """<svg width="50" height="50"><image href="pic.bmp" x="5" y="6" width="20" height="10"/></svg>""",
        ) { if (it == "pic.bmp") bmp2x1() else null }
        val image = drawn.filterIsInstance<RecordingCanvas.Call.Image>().singleOrNull()
        assertNotNull(image, "the image draws (got $drawn)")
        assertEquals(20.0, image.ctm.a, 1e-9)
        assertEquals(-10.0, image.ctm.d, 1e-9, "the unit square is flipped for y-down SVG")
        assertEquals(5.0, image.ctm.e, 1e-9)
        assertEquals(16.0, image.ctm.f, 1e-9, "y + height, since the flip moves the origin")
    }

    @Test
    fun an_image_from_a_data_uri_draws_with_no_loader() {
        val uri = "data:image/bmp;base64," + base64(bmp2x1())
        val drawn = calls("""<svg width="10" height="10"><image href="$uri" width="4" height="4"/></svg>""")
        assertTrue(drawn.any { it is RecordingCanvas.Call.Image }, "got $drawn")
    }

    /* ─── text ───────────────────────────────────────────────────────────── */

    @Test
    fun text_draws_its_characters() {
        val runs = calls("""<svg width="100" height="20"><text x="4" y="15">Hi</text></svg>""")
            .filterIsInstance<RecordingCanvas.Call.Glyphs>()
        assertEquals(1, runs.size)
        assertEquals("Hi", runs[0].text)
        assertEquals(4.0, runs[0].textToDevice.e, 1e-9)
        assertEquals(15.0, runs[0].textToDevice.f, 1e-9)
        assertEquals(-1.0, runs[0].textToDevice.d, 1e-9, "text space is y-up inside a y-down SVG")
    }

    @Test
    fun a_middle_anchor_centres_the_run() {
        val start = calls("""<svg width="100" height="20"><text x="50" y="10">Hello</text></svg>""")
            .filterIsInstance<RecordingCanvas.Call.Glyphs>().single()
        val middle = calls("""<svg width="100" height="20"><text x="50" y="10" text-anchor="middle">Hello</text></svg>""")
            .filterIsInstance<RecordingCanvas.Call.Glyphs>().single()
        assertTrue(middle.textToDevice.e < start.textToDevice.e, "the middle anchor shifts left")
    }

    @Test
    fun a_tspan_can_move_the_pen() {
        val runs = calls(
            """<svg width="100" height="40"><text x="0" y="10">one<tspan x="0" y="30">two</tspan></text></svg>""",
        ).filterIsInstance<RecordingCanvas.Call.Glyphs>()
        assertEquals(listOf("one", "two"), runs.map { it.text })
        assertEquals(30.0, runs[1].textToDevice.f, 1e-9)
    }

    /* ─── gradients ──────────────────────────────────────────────────────── */

    private fun gradient(svg: String, id: String): KiteXmlNode.Element {
        val root = KiteXml.parse(svg)
        fun find(el: KiteXmlNode.Element): KiteXmlNode.Element? {
            if (el.attrs["id"] == id) return el
            for (c in el.children) if (c is KiteXmlNode.Element) find(c)?.let { return it }
            return null
        }
        return find(root)!!
    }

    private fun byId(svg: String): Map<String, KiteXmlNode.Element> {
        val out = LinkedHashMap<String, KiteXmlNode.Element>()
        fun scan(el: KiteXmlNode.Element) {
            el.attrs["id"]?.let { if (it !in out) out[it] = el }
            for (c in el.children) if (c is KiteXmlNode.Element) scan(c)
        }
        scan(KiteXml.parse(svg))
        return out
    }

    @Test
    fun a_linear_gradient_becomes_an_axial_shading() {
        val svg = """
            <svg><linearGradient id="g" x1="0" y1="0" x2="1" y2="0">
              <stop offset="0" stop-color="#ff0000"/><stop offset="1" stop-color="#0000ff"/>
            </linearGradient></svg>
        """.trimIndent()
        val parsed = SvgGradient.parse(gradient(svg, "g"), byId(svg))
        assertNotNull(parsed)
        val axial = parsed.shading as KiteShading.Axial
        assertTrue(parsed.objectBoundingBox, "objectBoundingBox is the default")
        assertEquals(listOf(0.0, 0.0, 1.0, 0.0), axial.coords.toList())
        val start = axial.function.evaluate(doubleArrayOf(0.0))
        val end = axial.function.evaluate(doubleArrayOf(1.0))
        assertEquals(1.0, start[0], 1e-9, "red at t=0")
        assertEquals(1.0, end[2], 1e-9, "blue at t=1")
    }

    @Test
    fun a_radial_gradient_becomes_a_radial_shading() {
        val svg = """
            <svg><radialGradient id="g" cx="0.5" cy="0.5" r="0.5">
              <stop offset="0" stop-color="white"/><stop offset="1" stop-color="black"/>
            </radialGradient></svg>
        """.trimIndent()
        val parsed = SvgGradient.parse(gradient(svg, "g"), byId(svg))!!
        val radial = parsed.shading as KiteShading.Radial
        assertEquals(listOf(0.5, 0.5, 0.0, 0.5, 0.5, 0.5), radial.coords.toList())
    }

    @Test
    fun a_gradient_borrows_the_stops_it_references() {
        val svg = """
            <svg>
              <linearGradient id="base"><stop offset="0" stop-color="red"/><stop offset="1" stop-color="lime"/></linearGradient>
              <linearGradient id="g" href="#base" x1="0" y1="0" x2="0" y2="1"/>
            </svg>
        """.trimIndent()
        val parsed = SvgGradient.parse(gradient(svg, "g"), byId(svg))!!
        val axial = parsed.shading as KiteShading.Axial
        assertEquals(1.0, axial.function.evaluate(doubleArrayOf(0.0))[0], 1e-9, "red from the referenced stops")
        assertEquals(listOf(0.0, 0.0, 0.0, 1.0), axial.coords.toList(), "its own geometry")
    }

    @Test
    fun a_percentage_offset_reads_as_a_fraction() {
        val svg = """
            <svg><linearGradient id="g">
              <stop offset="0%" stop-color="red"/><stop offset="50%" stop-color="lime"/><stop offset="100%" stop-color="blue"/>
            </linearGradient></svg>
        """.trimIndent()
        val axial = SvgGradient.parse(gradient(svg, "g"), byId(svg))!!.shading as KiteShading.Axial
        assertEquals(1.0, axial.function.evaluate(doubleArrayOf(0.5))[1], 1e-9, "lime at the midpoint")
    }

    @Test
    fun a_gradient_fill_still_paints_the_shape() {
        val drawn = calls(
            """<svg width="20" height="20">
                 <linearGradient id="g"><stop offset="0" stop-color="red"/><stop offset="1" stop-color="blue"/></linearGradient>
                 <rect width="20" height="20" fill="url(#g)"/>
               </svg>""",
        )
        assertTrue(drawn.any { it is RecordingCanvas.Call.Fill }, "got $drawn")
    }

    @Test
    fun a_fill_pointing_at_nothing_falls_back_to_the_inherited_colour() {
        val f = fills("""<svg width="10" height="10" fill="red"><rect width="5" height="5" fill="url(#gone)"/></svg>""")
        assertEquals(1, f.size)
        assertEquals(1.0, f[0].color.r, 1e-9)
    }

    /* ─── clip and visibility ────────────────────────────────────────────── */

    @Test
    fun a_clip_path_wraps_the_shape() {
        val drawn = calls(
            """<svg width="30" height="30">
                 <clipPath id="c"><rect width="10" height="10"/></clipPath>
                 <rect width="30" height="30" fill="red" clip-path="url(#c)"/>
               </svg>""",
        )
        val pushAt = drawn.indexOfFirst { it is RecordingCanvas.Call.PushClip }
        val fillAt = drawn.indexOfFirst { it is RecordingCanvas.Call.Fill }
        val popAt = drawn.indexOfFirst { it is RecordingCanvas.Call.PopClip }
        assertTrue(pushAt in 0 until fillAt && fillAt < popAt, "clip brackets the fill (got $drawn)")
    }

    @Test
    fun display_none_and_visibility_hidden_paint_nothing() {
        assertTrue(fills("""<svg width="10" height="10"><rect width="5" height="5" fill="red" display="none"/></svg>""").isEmpty())
        assertTrue(fills("""<svg width="10" height="10"><rect width="5" height="5" fill="red" visibility="hidden"/></svg>""").isEmpty())
    }

    @Test
    fun fill_opacity_multiplies_into_the_alpha() {
        val f = fills("""<svg width="10" height="10"><rect width="5" height="5" fill="red" opacity="0.5" fill-opacity="0.5"/></svg>""")
        assertEquals(0.25, f.single().alpha, 1e-9)
    }

    @Test
    fun a_style_attribute_beats_the_presentation_attribute() {
        val f = fills("""<svg width="10" height="10"><rect width="5" height="5" fill="red" style="fill:#0000ff"/></svg>""")
        assertEquals(1.0, f.single().color.b, 1e-9)
    }

    /* ─── fixtures ───────────────────────────────────────────────────────── */

    private fun bmp2x1(): ByteArray {
        val h = ByteArray(54)
        h[0] = 'B'.code.toByte(); h[1] = 'M'.code.toByte()
        fun le32(o: Int, v: Int) { var s = 0; var i = o; while (s < 32) { h[i++] = ((v ushr s) and 0xFF).toByte(); s += 8 } }
        fun le16(o: Int, v: Int) { h[o] = (v and 0xFF).toByte(); h[o + 1] = ((v ushr 8) and 0xFF).toByte() }
        le32(2, 62); le32(10, 54); le32(14, 40); le32(18, 2); le32(22, 1)
        le16(26, 1); le16(28, 24); le32(34, 8)
        return h + byteArrayOf(0, 0, 0xFF.toByte(), 0xFF.toByte(), 0, 0, 0, 0)
    }

    private fun base64(bytes: ByteArray): String {
        val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"
        val sb = StringBuilder()
        var i = 0
        while (i < bytes.size) {
            val b0 = bytes[i].toInt() and 0xFF
            val b1 = if (i + 1 < bytes.size) bytes[i + 1].toInt() and 0xFF else 0
            val b2 = if (i + 2 < bytes.size) bytes[i + 2].toInt() and 0xFF else 0
            sb.append(alphabet[b0 ushr 2])
            sb.append(alphabet[((b0 and 0x03) shl 4) or (b1 ushr 4)])
            sb.append(if (i + 1 < bytes.size) alphabet[((b1 and 0x0F) shl 2) or (b2 ushr 6)] else '=')
            sb.append(if (i + 2 < bytes.size) alphabet[b2 and 0x3F] else '=')
            i += 3
        }
        return sb.toString()
    }
}
