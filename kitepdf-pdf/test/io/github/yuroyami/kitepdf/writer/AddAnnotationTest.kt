package io.github.yuroyami.kitepdf.writer

import io.github.yuroyami.kitepdf.PdfAnnotation
import io.github.yuroyami.kitepdf.PdfDocument
import io.github.yuroyami.kitepdf.core.KiteRectangle
import io.github.yuroyami.kitepdf.core.render.RgbColor
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The editor adds an annotation of each common subtype with its own appearance, and removes one,
 * and a saved file reads them back (ISO 32000-1, 12.5, #638).
 */
class AddAnnotationTest {

    private fun blank(): PdfDocument = PdfDocument.open(PdfBuilder().page(width = 300.0, height = 400.0) {}.build())

    private val quad = listOf(50.0, 320.0, 150.0, 320.0, 50.0, 300.0, 150.0, 300.0)
    private val red = RgbColor(1.0, 0.0, 0.0)

    private fun all(): List<PdfNewAnnotation> = listOf(
        PdfNewAnnotation.Highlight(quad, contents = "highlight"),
        PdfNewAnnotation.Underline(quad, contents = "underline"),
        PdfNewAnnotation.StrikeOut(quad, contents = "strike"),
        PdfNewAnnotation.Squiggly(quad, contents = "squiggly"),
        PdfNewAnnotation.Text(KiteRectangle(10.0, 370.0, 30.0, 390.0), "A note é"),
        PdfNewAnnotation.FreeText(KiteRectangle(20.0, 200.0, 200.0, 240.0), "Free text", color = red),
        PdfNewAnnotation.Ink(listOf(listOf(20.0, 20.0, 40.0, 60.0, 60.0, 20.0)), color = red, width = 2.0, contents = "ink"),
        PdfNewAnnotation.Square(KiteRectangle(100.0, 100.0, 160.0, 150.0), interiorColor = RgbColor(0.0, 1.0, 0.0), contents = "square"),
        PdfNewAnnotation.Circle(KiteRectangle(170.0, 100.0, 230.0, 150.0), contents = "circle"),
        PdfNewAnnotation.Line(20.0, 80.0, 200.0, 90.0, width = 3.0, contents = "line"),
        PdfNewAnnotation.Link(KiteRectangle(200.0, 300.0, 280.0, 320.0), "https://example.com/a"),
    )

    private fun near(a: Double, b: Double) = abs(a - b) < 0.01

    @Test
    fun every_subtype_of_the_first_set_reads_back_with_its_appearance() {
        val doc = blank()
        val editor = doc.edit()
        val wanted = all()
        for (annotation in wanted) editor.addAnnotation(doc.pages[0], annotation)
        val saved = PdfDocument.open(editor.saveRewritten())
        val read = saved.pages[0].annotations
        assertEquals(wanted.size, read.size)
        val expectedSubtypes = listOf(
            PdfAnnotation.Subtype.Highlight, PdfAnnotation.Subtype.Underline, PdfAnnotation.Subtype.StrikeOut,
            PdfAnnotation.Subtype.Squiggly, PdfAnnotation.Subtype.Text, PdfAnnotation.Subtype.FreeText,
            PdfAnnotation.Subtype.Ink, PdfAnnotation.Subtype.Square, PdfAnnotation.Subtype.Circle,
            PdfAnnotation.Subtype.Line, PdfAnnotation.Subtype.Link,
        )
        assertEquals(expectedSubtypes, read.map { it.subtype })
        for ((annotation, got) in wanted.zip(read)) {
            val rect = AnnotationWriter.rectOf(annotation)
            assertTrue(near(rect.left, got.rect.left) && near(rect.top, got.rect.top) && near(rect.right, got.rect.right) && near(rect.bottom, got.rect.bottom), "${got.subtype}: ${got.rect} for $rect")
            assertEquals(annotation.contents, got.contents, "${got.subtype}")
            assertNotNull(got.appearanceStream, "${got.subtype} has no appearance")
            if (annotation !is PdfNewAnnotation.Link) {
                val c = assertNotNull(got.color, "${got.subtype} has no colour")
                assertTrue(near(c.r, annotation.color.r) && near(c.g, annotation.color.g) && near(c.b, annotation.color.b), "${got.subtype}: $c")
            }
        }
        assertEquals(quad, read[0].quadPoints)
        assertEquals("https://example.com/a", read.last().uri)
        assertEquals(RgbColor(0.0, 1.0, 0.0), read[7].interiorColor)
        // The page draws the highlight: its appearance fills the quad.
        assertTrue(io.github.yuroyami.kitepdf.core.filters.FilterChain.decode(read[0].appearanceStream!!).decodeToString().contains(" f"), "the highlight's appearance fills nothing")
    }

    @Test
    fun a_removed_annotation_is_no_longer_listed() {
        val doc = blank()
        val editor = doc.edit()
        val keep = editor.addAnnotation(doc.pages[0], PdfNewAnnotation.Square(KiteRectangle(10.0, 10.0, 50.0, 50.0), contents = "keep"))
        val drop = editor.addAnnotation(doc.pages[0], PdfNewAnnotation.Circle(KiteRectangle(60.0, 10.0, 90.0, 50.0), contents = "drop"))
        assertTrue(editor.removeAnnotation(doc.pages[0], drop))
        assertEquals(false, editor.removeAnnotation(doc.pages[0], drop), "a second removal found the annotation")
        val saved = PdfDocument.open(editor.saveIncremental())
        assertEquals(listOf("keep"), saved.pages[0].annotations.map { it.contents })
        assertTrue(keep != drop)
    }

    /** Each annotation draws from its own appearance: the page with all of them fills and strokes paths (#638). */
    @Test
    fun the_saved_annotations_draw_on_the_page() {
        val doc = blank()
        val editor = doc.edit()
        for (annotation in all()) editor.addAnnotation(doc.pages[0], annotation)
        val calls = io.github.yuroyami.kitepdf.TestPdf.calls(editor.saveRewritten())
        val fills = calls.count { it is io.github.yuroyami.kitepdf.core.render.RecordingCanvas.Call.Fill }
        val strokes = calls.count { it is io.github.yuroyami.kitepdf.core.render.RecordingCanvas.Call.Stroke }
        assertTrue(fills >= 3, "the page fills $fills paths")
        assertTrue(strokes >= 6, "the page strokes $strokes paths")
    }
}
