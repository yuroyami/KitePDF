package io.github.yuroyami.kitepdf.epub

import io.github.yuroyami.kitepdf.core.render.KiteMatrix
import io.github.yuroyami.kitepdf.core.render.KitePath
import io.github.yuroyami.kitepdf.core.render.RecordingCanvas
import io.github.yuroyami.kitepdf.core.zip.Crc32
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** CSS Writing Modes 4, 3/7.2 and CSS Images 3, 4.3.2/4.5 (#100, #170). */
class VerticalImageTest {
    private val settings = EpubSettings(pageWidth = 300.0, pageHeight = 400.0, margin = 20.0, fontSize = 10.0)
    private val verticalModes = listOf("vertical-rl", "vertical-lr")
    private val allModes = listOf("horizontal-tb") + verticalModes
    private val svg = """<svg xmlns="http://www.w3.org/2000/svg" width="4" height="2"><rect width="4" height="2" fill="red"/></svg>""".encodeToByteArray()

    private fun open(mode: String, body: String): EpubDocument = EpubDocument.open(
        EpubFixtures.epub(
            "<style>html{writing-mode:$mode}body,p{margin:0;padding:0;text-indent:0}</style>$body",
            extraEntries = listOf("OEBPS/pic.png" to png(), "OEBPS/pic.svg" to svg),
        ), settings,
    )

    private fun calls(mode: String, body: String, ctm: KiteMatrix = KiteMatrix.IDENTITY): List<RecordingCanvas.Call> =
        RecordingCanvas().also { open(mode, body).pages.first().renderTo(it, ctm) }.calls

    private fun images(mode: String, body: String): List<RecordingCanvas.Call.Image> =
        calls(mode, body).filterIsInstance<RecordingCanvas.Call.Image>()

    private fun assertSize(m: KiteMatrix, width: Double, height: Double, message: String) {
        assertEquals(width, m.a, 1e-7, message)
        assertEquals(0.0, m.b, 1e-7, "$message: no rotation")
        assertEquals(0.0, m.c, 1e-7, "$message: no rotation")
        assertEquals(height, m.d, 1e-7, message)
    }

    @Test
    fun css_physical_dimensions_stay_upright_for_block_and_inline_in_both_vertical_modes() {
        for (mode in allModes) for (display in listOf("block", "inline")) {
            val img = images(mode, """<img src="pic.png" style="display:$display;width:80pt;height:40pt"/>""").single()
            assertSize(img.ctm, 80.0, 40.0, "$mode $display")
        }
    }

    @Test
    fun html_attributes_and_single_css_dimensions_keep_the_intrinsic_physical_aspect() {
        for (mode in verticalModes) for (display in listOf("block", "inline")) {
            for (attrs in listOf("width=\"80\" height=\"40\"", "style=\"width:60pt\"", "style=\"height:30pt\"")) {
                val body = """<style>img{display:$display}</style><img src="pic.png" $attrs/>"""
                assertSize(images(mode, body).single().ctm, 60.0, 30.0, "$mode $display $attrs")
            }
        }
    }

    @Test
    fun percentage_dimensions_use_physical_page_axes_in_vertical_flow() {
        for (mode in verticalModes) for (display in listOf("block", "inline")) {
            val img = images(mode, """<img src="pic.png" style="display:$display;width:50%;height:25%"/>""").single()
            assertSize(img.ctm, 130.0, 90.0, "$mode $display physical percentage bases")
        }
    }

    @Test
    fun an_inline_image_shares_its_glyph_column_in_both_vertical_modes() {
        // CSS Writing Modes 4, 6.4: line-over is the right side in both vertical modes (#261).
        val offsets = verticalModes.map { mode ->
            val recorded = calls(mode, "<p>\u65E5<img src='pic.png' style='width:10pt;height:10pt'/>\u672C</p>")
            val image = recorded.filterIsInstance<RecordingCanvas.Call.Image>().single()
            val glyph = recorded.filterIsInstance<RecordingCanvas.Call.Glyphs>().first { it.text == "\u65E5" }
            image.ctm.e - glyph.textToDevice.e
        }
        assertEquals(offsets[0], offsets[1], 1e-9)
    }

    @Test
    fun a_vertical_column_stays_inside_its_line_in_both_modes() {
        for (mode in verticalModes) {
            val doc = open(mode, "<p>\u65E5\u672C</p>")
            val line = doc.pages[0].textContent().blocks.first().lines.first()
            val glyph = RecordingCanvas().also { doc.pages[0].renderTo(it) }.calls
                .filterIsInstance<RecordingCanvas.Call.Glyphs>().first { it.text == "\u65E5" }
            val em = glyph.glyphs.single().advanceWidth * glyph.fontSize / 1000.0
            assertTrue(glyph.textToDevice.e >= line.bounds.left - 1.0, "$mode: ${glyph.textToDevice.e} vs ${line.bounds}")
            assertTrue(glyph.textToDevice.e + em <= line.bounds.right + 1.0, "$mode: ${glyph.textToDevice.e + em} vs ${line.bounds}")
        }
    }

    @Test
    fun inline_image_intrinsic_dimensions_are_not_transposed() {
        for (mode in verticalModes) {
            assertSize(images(mode, "<img src=\"pic.png\"/>").single().ctm, 3.0, 1.5, mode)
        }
    }

    @Test
    fun vertical_block_images_reserve_physical_width_along_the_column_axis() {
        for (mode in verticalModes) {
            val image = """<img src="pic.png" style="display:block;width:80pt;height:40pt"/>"""
            val draws = images(mode, image + image)
            assertEquals(2, draws.size)
            val direction = if (mode == "vertical-lr") 1 else -1
            assertEquals(direction * 80.0, draws[1].ctm.e - draws[0].ctm.e, 1e-7, mode)
            assertEquals(draws[0].ctm.f, draws[1].ctm.f, 1e-7, "same physical top")
            assertEquals(if (mode == "vertical-lr") 20.0 else 200.0, draws[0].ctm.e, 1e-7)
        }
    }

    @Test
    fun inline_advance_uses_physical_height_and_line_growth_uses_physical_width() {
        for (mode in verticalModes) {
            val image = """<img src="pic.png" style="width:80pt;height:40pt"/>"""
            val draws = images(mode, "<p>$image$image<br/>$image</p>")
            assertEquals(3, draws.size)
            assertEquals(draws[0].ctm.e, draws[1].ctm.e, 1e-7, "same column")
            assertEquals(-40.0, draws[1].ctm.f - draws[0].ctm.f, 1e-7, "advance down by physical height")
            val step = (draws[2].ctm.e - draws[0].ctm.e) * if (mode == "vertical-lr") 1 else -1
            assertTrue(step >= 80.0, "$mode next column clears the physical image width: $step")
        }
    }

    @Test
    fun cover_is_centered_and_clipped_for_block_and_inline_in_every_writing_mode() {
        for (mode in allModes) for (display in listOf("block", "inline")) {
            val recorded = calls(mode, """<img src="pic.png" style="display:$display;width:40pt;height:60pt;object-fit:cover"/>""")
            val img = recorded.filterIsInstance<RecordingCanvas.Call.Image>().single()
            assertSize(img.ctm, 120.0, 60.0, "$mode $display cover preserves 2:1 aspect")
            val clip = recorded.filterIsInstance<RecordingCanvas.Call.PushClip>().single()
            val points = clip.path.segments.filterIsInstance<KitePath.Segment.LineTo>()
            val start = clip.path.segments.first() as KitePath.Segment.MoveTo
            assertEquals(40.0, points.maxOf { it.x } - start.x, 1e-7)
            assertEquals(60.0, points.maxOf { it.y } - start.y, 1e-7)
            assertEquals(start.x - 40.0, img.ctm.e, 1e-7, "centred horizontal crop")
            assertEquals(start.y, img.ctm.f, 1e-7)
            assertEquals(1, recorded.count { it is RecordingCanvas.Call.PopClip })
        }
    }

    @Test
    fun cover_clip_and_draw_use_the_same_device_transform() {
        val device = KiteMatrix(2.0, 0.0, 0.0, -3.0, 7.0, 1200.0)
        for (mode in verticalModes) {
            val recorded = calls(mode, """<img src="pic.png" style="width:40pt;height:60pt;object-fit:cover"/>""", device)
            val clip = recorded.filterIsInstance<RecordingCanvas.Call.PushClip>().single()
            assertEquals(device, clip.ctm)
            val img = recorded.filterIsInstance<RecordingCanvas.Call.Image>().single()
            assertSize(img.ctm, 240.0, -180.0, "transformed cover")
        }
    }

    @Test
    fun svg_replaced_content_stays_upright_and_cover_preserves_its_aspect() {
        for (mode in allModes) for (display in listOf("block", "inline")) for (cover in listOf(false, true)) {
            val fit = if (cover) "object-fit:cover" else ""
            val recorded = calls(mode, """<img src="pic.svg" style="display:$display;width:40pt;height:60pt;$fit"/>""")
            val fill = recorded.filterIsInstance<RecordingCanvas.Call.Fill>().single()
            assertSize(fill.ctm, if (cover) 30.0 else 10.0, -30.0, "$mode $display SVG")
            assertEquals(if (cover) 1 else 0, recorded.count { it is RecordingCanvas.Call.PushClip })
            assertEquals(if (cover) 1 else 0, recorded.count { it is RecordingCanvas.Call.PopClip })
        }
    }

    @Test
    fun vertical_page_caps_preserve_physical_aspect_and_keep_images_inside_the_page() {
        for (mode in verticalModes) for (display in listOf("block", "inline")) {
            val img = images(mode, """<img src="pic.png" style="display:$display;width:520pt;height:260pt"/>""").single()
            assertSize(img.ctm, 260.0, 130.0, "$mode $display page cap")
            assertTrue(img.ctm.e >= 20.0 && img.ctm.e + img.ctm.a <= 280.0, "physical horizontal bounds")
            assertTrue(img.ctm.f >= 20.0 && img.ctm.f + img.ctm.d <= 380.0, "physical vertical bounds")
        }
    }

    private fun png(): ByteArray {
        fun be32(n: Int) = byteArrayOf((n ushr 24).toByte(), (n ushr 16).toByte(), (n ushr 8).toByte(), n.toByte())
        fun chunk(type: String, data: ByteArray): ByteArray {
            val body = type.encodeToByteArray() + data
            return be32(data.size) + body + be32(Crc32.of(body).toInt())
        }
        val scan = ByteArray(26) // 4x2 RGB, with one filter byte per row.
        var a = 1; var b = 0
        for (v in scan) { a = (a + (v.toInt() and 255)) % 65521; b = (b + a) % 65521 }
        val nlen = scan.size.inv()
        val zlib = byteArrayOf(0x78, 1, 1, scan.size.toByte(), 0, nlen.toByte(), (nlen ushr 8).toByte()) + scan + be32((b shl 16) or a)
        return byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47, 13, 10, 26, 10) +
            chunk("IHDR", be32(4) + be32(2) + byteArrayOf(8, 2, 0, 0, 0)) + chunk("IDAT", zlib) + chunk("IEND", byteArrayOf())
    }
}
