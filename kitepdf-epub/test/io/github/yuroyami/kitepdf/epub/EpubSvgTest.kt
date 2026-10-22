package io.github.yuroyami.kitepdf.epub

import io.github.yuroyami.kitepdf.core.render.RecordingCanvas
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** SVG inside a book: an inline `<svg>` element and an `<img src="x.svg">`. */
class EpubSvgTest {

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
        // width=50 height=30 are CSS pixels, 37.5pt by 22.5pt, so the 100x60 SVG scales by 0.375 (#112).
        val svg = """<svg width="100" height="60"><rect width="100" height="60" fill="red"/></svg>"""
        val body = """<body><img src="p.svg" width="50" height="30" style="display:block"/></body>"""
        val fills = epubFills(body, listOf("OEBPS/p.svg" to svg.encodeToByteArray()))
        val red = fills.single { it.color.r > 0.9 && it.color.g < 0.1 }
        assertEquals(0.375, kotlin.math.abs(red.ctm.a), 1e-6, "width 50px is 37.5pt over a 100-wide SVG")
        assertEquals(0.375, kotlin.math.abs(red.ctm.d), 1e-6, "height 30px is 22.5pt over a 60-tall SVG")
    }

    @Test
    fun an_svg_wrapping_a_page_image_draws_the_image() {
        // The fixed-layout comic idiom: one SVG per page, holding one <image>.
        val svg = """<svg xmlns="http://www.w3.org/2000/svg" width="60" height="40">
            <image xlink:href="page01.bmp" x="0" y="0" width="60" height="40"/></svg>"""
        val book = EpubFixtures.epub(
            """<body><img src="page.svg"/></body>""",
            listOf(
                "OEBPS/page.svg" to svg.encodeToByteArray(),
                "OEBPS/page01.bmp" to EpubFixtures.bmp2x1(),
            ),
        )
        val images = EpubDocument.open(book).pages.flatMap { page ->
            RecordingCanvas().also { page.renderTo(it) }.calls.filterIsInstance<RecordingCanvas.Call.Image>()
        }
        assertTrue(images.isNotEmpty(), "the SVG's <image> reached the canvas")
    }

    @Test
    fun an_svg_written_in_a_chapter_loads_its_image_from_the_chapter_folder() {
        // The usual cover page: the chapter and its picture both sit in OEBPS/, not at the root (#276).
        val book = EpubFixtures.epub(
            """<div><svg xmlns="http://www.w3.org/2000/svg" xmlns:xlink="http://www.w3.org/1999/xlink" viewBox="0 0 200 100">
                <image width="200" height="100" xlink:href="pic.bmp"/></svg></div>""",
            listOf("OEBPS/pic.bmp" to EpubFixtures.bmp2x1()),
        )
        val images = EpubDocument.open(book).pages.flatMap { page ->
            RecordingCanvas().also { page.renderTo(it) }.calls.filterIsInstance<RecordingCanvas.Call.Image>()
        }
        assertEquals(1, images.size, "the cover picture reached the canvas")
    }

    /** A 1x1 24-bit BMP, so a test can tell it from [EpubFixtures.bmp2x1]. */
    private fun bmp1x1(): ByteArray {
        val h = ByteArray(54)
        h[0] = 'B'.code.toByte(); h[1] = 'M'.code.toByte()
        fun le32(o: Int, v: Int) { var s = 0; var i = o; while (s < 32) { h[i++] = ((v ushr s) and 0xFF).toByte(); s += 8 } }
        fun le16(o: Int, v: Int) { h[o] = (v and 0xFF).toByte(); h[o + 1] = ((v ushr 8) and 0xFF).toByte() }
        le32(2, 58); le32(10, 54); le32(14, 40); le32(18, 1); le32(22, 1)
        le16(26, 1); le16(28, 24); le32(34, 4)
        return h + byteArrayOf(0, 0xFF.toByte(), 0, 0)
    }

    @Test
    fun an_svg_file_loads_its_image_from_its_own_folder_in_every_position() {
        // The SVG and its picture sit in OEBPS/images, the chapter in OEBPS/Text. A decoy with the
        // same name sits next to the chapter, so a lookup from the wrong folder draws the decoy (#425).
        val wrapper = """<svg xmlns="http://www.w3.org/2000/svg" width="12" height="12"><image href="p.bmp" width="12" height="12"/></svg>"""
        val extras = listOf(
            "OEBPS/images/wrapper.svg" to wrapper.encodeToByteArray(),
            "OEBPS/images/p.bmp" to EpubFixtures.bmp2x1(),
            "OEBPS/Text/p.bmp" to bmp1x1(),
        )
        val img = """<img src="../images/wrapper.svg"/>"""
        val vertical = "html { writing-mode: vertical-rl }"
        val cases = listOf(
            Triple("block", """<img style="display:block" src="../images/wrapper.svg"/>""", ""),
            Triple("inline", "<p>$img</p>", ""),
            Triple("inline, in a link", """<p><a href="#x">$img</a></p>""", ""),
            Triple("block, vertical", """<img style="display:block" src="../images/wrapper.svg"/>""", vertical),
            Triple("inline, vertical", "<p>$img</p>", vertical),
        )
        for ((name, body, css) in cases) {
            val sheets = if (css.isEmpty()) emptyList() else listOf("book.css" to css)
            val doc = EpubDocument.open(EpubFixtures.epubFoldered(listOf(body), sheets, extras))
            val images = doc.pages.flatMap { page ->
                RecordingCanvas().also { page.renderTo(it) }.calls.filterIsInstance<RecordingCanvas.Call.Image>()
            }
            assertEquals(1, images.size, "$name: the SVG's picture reached the canvas once")
            assertEquals(2, images.single().image.width, "$name: the picture came from the chapter's folder, not the SVG's")
        }
    }
}
