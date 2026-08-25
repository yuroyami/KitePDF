package io.github.yuroyami.kitepdf.svg

import io.github.yuroyami.kitepdf.core.KiteFormatException
import io.github.yuroyami.kitepdf.core.render.KiteMatrix
import io.github.yuroyami.kitepdf.core.render.RecordingCanvas
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** A standalone `.svg` opened as a one-page document. */
class SvgDocumentTest {

    private val file = """
        <?xml version="1.0"?>
        <svg xmlns="http://www.w3.org/2000/svg" width="120" height="80">
          <rect x="10" y="10" width="40" height="30" fill="#ff0000"/>
        </svg>
    """.trimIndent().encodeToByteArray()

    @Test
    fun a_file_opens_as_one_page_at_its_own_size() {
        val doc = SvgDocument.open(file)
        assertEquals(1, doc.pageCount)
        val page = doc.pages.single()
        assertEquals(120.0, page.displayWidth)
        assertEquals(80.0, page.displayHeight)
    }

    @Test
    fun the_page_paints_its_shapes() {
        val page = SvgDocument.open(file).pages.single()
        val canvas = RecordingCanvas()
        page.renderTo(canvas, KiteMatrix.IDENTITY)
        assertTrue(
            canvas.calls.any { it is RecordingCanvas.Call.Fill },
            "the rect fills (got ${canvas.calls})",
        )
    }

    @Test
    fun bytes_with_no_svg_in_them_are_refused() {
        assertFailsWith<KiteFormatException> { SvgDocument.open("plain text".encodeToByteArray()) }
        assertNull(SvgDocument.openOrNull("plain text".encodeToByteArray()))
    }

    @Test
    fun a_viewbox_alone_sizes_the_page() {
        val bytes = """<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 200 100"/>""".encodeToByteArray()
        val page = SvgDocument.open(bytes).pages.single()
        assertEquals(200.0, page.displayWidth)
        assertEquals(100.0, page.displayHeight)
    }
}
