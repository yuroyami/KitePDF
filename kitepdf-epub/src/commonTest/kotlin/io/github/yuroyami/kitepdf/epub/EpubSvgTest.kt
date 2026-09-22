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
}
