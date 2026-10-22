package io.github.yuroyami.kitepdf

import io.github.yuroyami.kitepdf.core.render.GraphicsStack
import io.github.yuroyami.kitepdf.core.render.GraphicsState
import io.github.yuroyami.kitepdf.core.render.KiteMatrix
import io.github.yuroyami.kitepdf.core.render.RecordingCanvas
import io.github.yuroyami.kitepdf.core.render.RgbColor
import io.github.yuroyami.kitepdf.render.PageRenderer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Whatever a nested content stream leaves unbalanced stays inside it: `q`
 * (ISO 32000-1, 8.4.4) and marked content (8.11.3.2), for forms, tiling cells
 * and annotation appearances alike.
 */
class StreamScopeTest {

    private val red = RgbColor(1.0, 0.0, 0.0)
    private val green = RgbColor(0.0, 1.0, 0.0)
    private val blue = RgbColor(0.0, 0.0, 1.0)

    private fun fills(pdf: ByteArray) = TestPdf.calls(pdf).filterIsInstance<RecordingCanvas.Call.Fill>()

    /** A 300 x 200 page whose form /Fm0 (object 5) runs [form]; [extra] objects follow from 6. */
    private fun formPage(
        content: String,
        form: String,
        resources: String = "",
        catalog: String = "",
        extra: List<Any> = emptyList(),
    ): ByteArray = TestPdf.onePage(
        content = content,
        resources = "/XObject << /Fm0 5 0 R >> $resources",
        catalogEntries = catalog,
        extra = listOf(TestPdf.stream(form, "/Type /XObject /Subtype /Form /BBox [0 0 300 200]")) + extra,
        mediaBox = "0 0 300 200",
    )

    @Test
    fun an_unbalanced_q_in_a_form_does_not_keep_the_callers_clip() {
        // The form is one Q short; the caller's Q must still remove its own clip (#47).
        val calls = TestPdf.calls(formPage(
            content = "q 50 120 100 60 re W n /Fm0 Do Q 0 0 1 rg 0 0 300 100 re f",
            form = "q 1 0 0 rg 60 130 30 20 re f",
        ))
        val beforeBlue = calls.takeWhile { !(it is RecordingCanvas.Call.Fill && it.color == blue) }
        assertEquals(
            beforeBlue.count { it is RecordingCanvas.Call.PushClip },
            beforeBlue.count { it is RecordingCanvas.Call.PopClip },
            "every clip is gone before the blue fill",
        )
    }

    @Test
    fun a_forms_open_q_does_not_leak_its_matrix_into_the_page() {
        // Two unmatched q in a form that scales by 3 (#48).
        val fill = fills(formPage(
            content = "q /Fm0 Do Q 0 0 1 rg 10 10 40 20 re f",
            form = "3 0 0 3 0 0 cm q q 1 0 0 rg 5 40 10 10 re f",
        )).single { it.color == blue }
        assertEquals(KiteMatrix.IDENTITY, fill.ctm)
    }

    @Test
    fun an_extra_q_in_a_form_cannot_pop_the_callers_state() {
        val fill = fills(formPage(content = "q 1 0 0 rg /Fm0 Do 0 0 100 100 re f Q", form = "Q Q")).single()
        assertEquals(red, fill.color)
    }

    @Test
    fun one_malformed_appearance_does_not_displace_the_next() {
        // Stamp A's appearance ends with an unmatched q and a cm (#50).
        val pdf = TestPdf.onePage(
            content = " ",
            pageEntries = "/Annots [5 0 R 7 0 R]",
            mediaBox = "0 0 300 200",
            extra = listOf(
                "<< /Type /Annot /Subtype /Stamp /Rect [10 150 60 190] /F 4 /AP << /N 6 0 R >> >>",
                TestPdf.stream("0 1 0 rg 0 0 50 40 re f q 1 0 0 1 10 10 cm", "/Type /XObject /Subtype /Form /BBox [0 0 50 40]"),
                "<< /Type /Annot /Subtype /Stamp /Rect [200 20 260 60] /F 4 /AP << /N 8 0 R >> >>",
                TestPdf.stream("1 0 0 rg 0 0 60 40 re f", "/Type /XObject /Subtype /Form /BBox [0 0 60 40]"),
            ),
        )
        val fill = fills(pdf).single { it.color == red }
        assertEquals(200.0, fill.ctm.e, 1e-9)
        assertEquals(20.0, fill.ctm.f, 1e-9)
    }

    @Test
    fun an_unclosed_hidden_section_in_a_form_does_not_blank_the_page() {
        // The form opens a hidden layer and never closes it (#51).
        val pdf = formPage(
            content = "0 0 1 rg 10 10 50 50 re f q /Fm0 Do Q 0 1 0 rg 10 100 50 50 re f",
            form = "/OC /MC0 BDC 1 0 0 rg 0 0 60 60 re f",
            resources = "/Properties << /MC0 6 0 R >>",
            catalog = "/OCProperties << /OCGs [6 0 R] /D << /OFF [6 0 R] >> >>",
            extra = listOf("<< /Type /OCG /Name (Off) >>"),
        )
        assertEquals(listOf(blue, green), fills(pdf).map { it.color })
    }

    @Test
    fun an_unclosed_hidden_section_in_a_tile_does_not_blank_the_page() {
        val pdf = TestPdf.onePage(
            content = "/Pattern cs /P0 scn 0 0 30 10 re f 0 1 0 rg 50 50 10 10 re f",
            resources = "/Pattern << /P0 5 0 R >>",
            catalogEntries = "/OCProperties << /OCGs [6 0 R] /D << /OFF [6 0 R] >> >>",
            extra = listOf(
                TestPdf.stream(
                    "1 0 0 rg 0 0 10 10 re f /OC /MC0 BDC",
                    "/PatternType 1 /PaintType 1 /TilingType 1 /BBox [0 0 10 10] /XStep 10 /YStep 10 " +
                        "/Resources << /Properties << /MC0 6 0 R >> >>",
                ),
                "<< /Type /OCG /Name (Off) >>",
            ),
        )
        val colours = fills(pdf).map { it.color }
        assertTrue(colours.count { it == red } >= 3, "every tile paints its red square: $colours")
        assertEquals(green, colours.last())
    }

    @Test
    fun past_the_depth_cap_saves_and_restores_still_pair() {
        // A restore past the cap pops nothing, so it cannot undo a real save (#49).
        val stack = GraphicsStack(GraphicsState(), maxDepth = 2)
        stack.save()
        stack.replace(stack.current.copy(fillColor = red))
        stack.save()
        stack.restore()
        assertEquals(red, stack.current.fillColor)
        stack.restore()
        assertEquals(RgbColor.BLACK, stack.current.fillColor)
    }

    @Test
    fun deep_nesting_restores_the_right_state() {
        val content = "q ".repeat(63) + "1 0 0 rg q 0 0 1 rg 20 120 100 50 re f Q 20 20 100 50 re f " + "Q ".repeat(63)
        assertEquals(listOf(blue, red), fills(TestPdf.onePage(content)).map { it.color })
    }

    @Test
    fun a_reused_renderer_starts_each_page_clean() {
        // Page 1 sets the Type 3 colour lock with a stray d1 (#52).
        val pdf = TestPdf.build(listOf(
            "<< /Type /Catalog /Pages 2 0 R >>",
            "<< /Type /Pages /Kids [3 0 R 4 0 R] /Count 2 >>",
            "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 200 200] /Contents 5 0 R >>",
            "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 200 200] /Contents 6 0 R >>",
            TestPdf.stream("d1 1 0 0 rg 20 20 100 100 re f"),
            TestPdf.stream("0 0 1 rg 20 20 100 100 re f"),
        ))
        val doc = PdfDocument.open(pdf)
        val canvas = RecordingCanvas()
        val renderer = PageRenderer(canvas, doc)
        renderer.render(doc.pages[0], KiteMatrix.IDENTITY)
        renderer.render(doc.pages[1], KiteMatrix.IDENTITY)
        assertEquals(blue, canvas.calls.filterIsInstance<RecordingCanvas.Call.Fill>().last().color)
    }

    @Test
    fun marked_content_nesting_is_capped() {
        val content = "/X BMC ".repeat(10_000) + "0 0 1 rg 0 0 10 10 re f " + "EMC ".repeat(10_000) + "0 1 0 rg 20 20 10 10 re f"
        val doc = PdfDocument.open(TestPdf.onePage(content))
        val canvas = RecordingCanvas()
        val renderer = PageRenderer(canvas, doc)
        renderer.render(doc.pages[0], KiteMatrix.IDENTITY)
        assertEquals(listOf(blue, green), canvas.calls.filterIsInstance<RecordingCanvas.Call.Fill>().map { it.color })
        assertEquals(4096, renderer.deepestMarkedContent, "PageRenderer's MAX_MARKED_CONTENT_DEPTH")
    }
}
