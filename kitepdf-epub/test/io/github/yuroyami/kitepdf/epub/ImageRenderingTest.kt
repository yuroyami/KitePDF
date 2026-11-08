package io.github.yuroyami.kitepdf.epub

import io.github.yuroyami.kitepdf.core.render.RecordingCanvas
import kotlin.io.encoding.Base64
import kotlin.test.Test
import kotlin.test.assertEquals

/** `image-rendering: pixelated` on an `<img>` draws it with nearest pixels (CSS Images 3; #936). */
class ImageRenderingTest {

    private val checker = Base64.decode("iVBORw0KGgoAAAANSUhEUgAAAAIAAAACCAIAAAD91JpzAAAAFUlEQVR4nAXBAQEAAACAEP9PF1CpMCnkBftM2nerAAAAAElFTkSuQmCC")

    private fun interpolates(body: String): List<Boolean> {
        val doc = EpubDocument.open(EpubFixtures.epub(body, extraEntries = listOf("OEBPS/pic.png" to checker)))
        return doc.pages.flatMap { page ->
            RecordingCanvas().also { page.renderTo(it) }.calls.filterIsInstance<RecordingCanvas.Call.Image>().map { it.image.interpolate }
        }
    }

    @Test
    fun a_pixelated_image_keeps_hard_edges_inline_and_as_a_block() {
        assertEquals(listOf(true), interpolates("""<p>a <img src="pic.png" style="width:100px"/> b</p>"""))
        assertEquals(listOf(false), interpolates("""<p>a <img src="pic.png" style="width:100px; image-rendering: pixelated"/> b</p>"""))
        assertEquals(listOf(false), interpolates("""<div style="image-rendering: crisp-edges"><img src="pic.png" style="display:block; width:100px"/></div>"""))
        assertEquals(listOf(true), interpolates("""<img src="pic.png" style="display:block; width:100px"/>"""))
    }
}
