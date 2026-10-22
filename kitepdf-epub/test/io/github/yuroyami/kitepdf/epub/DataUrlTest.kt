package io.github.yuroyami.kitepdf.epub

import io.github.yuroyami.kitepdf.core.render.RecordingCanvas
import kotlin.io.encoding.Base64
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** A resource that a document carries in a `data:` URL loads like one in the book (#514). */
class DataUrlTest {

    private val bmp = "data:image/bmp;base64," + Base64.encode(EpubFixtures.bmp2x1())

    private fun calls(body: String): List<RecordingCanvas.Call> {
        val doc = EpubDocument.open(EpubFixtures.epub(body))
        return doc.pages.flatMap { page -> RecordingCanvas().also { page.renderTo(it) }.calls }
    }

    @Test
    fun an_image_whose_source_is_a_data_url_draws() {
        val images = calls("""<p>Before</p><img src="$bmp" alt="pixels"/>""").filterIsInstance<RecordingCanvas.Call.Image>()
        assertEquals(listOf(2 to 1), images.map { it.image.width to it.image.height })
    }

    @Test
    fun an_svg_in_a_percent_encoded_data_url_draws() {
        val svg = "data:image/svg+xml,%3Csvg xmlns='http://www.w3.org/2000/svg' width='20' height='10'%3E" +
            "%3Crect width='20' height='10' fill='%23ff0000'/%3E%3C/svg%3E"
        val fills = calls("""<img src="$svg" alt="a red box"/>""").filterIsInstance<RecordingCanvas.Call.Fill>()
        assertTrue(fills.any { it.color.r == 1.0 && it.color.g == 0.0 && it.color.b == 0.0 }, "the SVG's red rectangle")
    }

    @Test
    fun a_css_background_in_a_data_url_paints_quoted_or_not() {
        for (url in listOf(bmp, "'$bmp'", "\"$bmp\"")) {
            val images = calls("""<div style="width:40px;height:20px;background-image:url($url)"></div>""".replace("\"$bmp\"", "&quot;$bmp&quot;"))
                .filterIsInstance<RecordingCanvas.Call.Image>()
            assertTrue(images.isNotEmpty(), "background url($url)")
        }
    }

    @Test
    fun a_font_in_a_data_url_draws_the_text() {
        val font = "data:font/ttf;base64," + Base64.encode(EpubFixtures.ligatureTtf())
        val css = "@font-face{font-family:'L';src:url($font)}p{font-family:'L'}"
        val glyphs = calls("<style>$css</style><p>fif</p>").filterIsInstance<RecordingCanvas.Call.Glyphs>().flatMap { it.glyphs }
        assertEquals(listOf(3 to "fi", 1 to "f"), glyphs.map { it.gid to it.text }, "the fi ligature of the embedded font")
    }

    @Test
    fun the_resource_of_a_data_url_is_its_content() {
        val doc = EpubDocument.open(EpubFixtures.epub("<p>x</p>"))
        assertContentEquals(EpubFixtures.bmp2x1(), doc.resource(bmp))
        assertEquals("image/bmp", doc.resourceType(bmp))
    }
}
