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
    fun a_form_without_resources_uses_the_page_it_is_drawn_on() {
        // ISO 32000-1, 7.8.3; a reused renderer kept the first page's resources for it (#767).
        val pdf = TestPdf.build(listOf(
            "<< /Type /Catalog /Pages 2 0 R >>",
            "<< /Type /Pages /Kids [3 0 R 4 0 R] /Count 2 >>",
            "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 200 200] /Contents 5 0 R /Resources << /XObject << /Fm 6 0 R >> /ExtGState << /GS 7 0 R >> >> >>",
            "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 200 200] /Contents 5 0 R /Resources << /XObject << /Fm 6 0 R >> /ExtGState << /GS 8 0 R >> >> >>",
            TestPdf.stream("/Fm Do"),
            TestPdf.stream("/GS gs 0 0 1 rg 20 20 100 100 re f", "/Type /XObject /Subtype /Form /BBox [0 0 200 200]"),
            "<< /Type /ExtGState /ca 0.5 >>",
            "<< /Type /ExtGState /ca 1 >>",
        ))
        val doc = PdfDocument.open(pdf)
        val canvas = RecordingCanvas()
        val renderer = PageRenderer(canvas, doc)
        renderer.render(doc.pages[0], KiteMatrix.IDENTITY)
        renderer.render(doc.pages[1], KiteMatrix.IDENTITY)
        assertEquals(listOf(0.5, 1.0), canvas.calls.filterIsInstance<RecordingCanvas.Call.Fill>().map { it.alpha })
    }

    @Test
    fun a_stencil_mask_paints_the_pattern_fill_through_it() {
        // ISO 32000-1, 8.9.6.2 and 8.7.3.1: the mask gates the pattern; it painted flat black (#780).
        val pdf = TestPdf.build(listOf(
            "<< /Type /Catalog /Pages 2 0 R >>",
            "<< /Type /Pages /Kids [3 0 R] /Count 1 >>",
            "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 200 200] /Contents 4 0 R " +
                "/Resources << /Pattern << /P0 6 0 R >> /XObject << /Im0 5 0 R >> >> >>",
            TestPdf.stream("/Pattern cs /P0 scn q 200 0 0 200 0 0 cm /Im0 Do Q"),
            TestPdf.stream("\u0000", "/Type /XObject /Subtype /Image /Width 1 /Height 1 /ImageMask true /BitsPerComponent 1"),
            "<< /PatternType 2 /Shading << /ShadingType 2 /ColorSpace /DeviceRGB /Coords [0 0 200 0] " +
                "/Function << /FunctionType 2 /Domain [0 1] /C0 [1 0 0] /C1 [0 0 1] /N 1 >> >> >>",
        ))
        val doc = PdfDocument.open(pdf)
        val calls = ArrayList<String>()
        val rc = RecordingCanvas()
        val canvas = object : io.github.yuroyami.kitepdf.core.render.KiteCanvas by rc {
            override fun applySoftMask(
                kind: io.github.yuroyami.kitepdf.core.render.SoftMask.Kind, maskBBox: io.github.yuroyami.kitepdf.core.KiteRectangle,
                maskCtm: KiteMatrix, render: () -> Unit, renderMask: (io.github.yuroyami.kitepdf.core.render.KiteCanvas) -> Unit,
            ) {
                calls += "mask"
                val inner = RecordingCanvas()
                renderMask(inner)
                if (inner.calls.any { it is RecordingCanvas.Call.Image }) calls += "stencil"
                render()
            }
            override fun drawImage(image: io.github.yuroyami.kitepdf.core.render.KiteImageData, ctm: KiteMatrix, alpha: Double) {
                calls += "image"
            }
        }
        PageRenderer(canvas, doc).render(doc.pages[0], KiteMatrix.IDENTITY)
        assertEquals(listOf("mask", "stencil"), calls.take(2), "$calls")
        kotlin.test.assertFalse("image" in calls, "the stencil is not painted flat")
    }

    @Test
    fun one_dash_rule_reaches_every_canvas() {
        // ISO 32000-1, 8.4.3.6: a negative phase wraps into the period; a negative entry is no dash (#770).
        fun stroke(dash: String): RecordingCanvas.Call.Stroke {
            val doc = PdfDocument.open(TestPdf.onePage("$dash 0 0 m 100 0 l S"))
            val canvas = RecordingCanvas()
            PageRenderer(canvas, doc).render(doc.pages[0], KiteMatrix.IDENTITY)
            return canvas.calls.filterIsInstance<RecordingCanvas.Call.Stroke>().single()
        }
        assertEquals(7.0, stroke("[6 3] -2 d").dashPhase, 1e-9)
        assertEquals(listOf(6.0, 3.0), stroke("[6 3] -2 d").dashArray)
        assertEquals(null, stroke("[-6 3] 0 d").dashArray)
        assertEquals(4.0, stroke("[5] 14 d").dashPhase, 1e-9, "an odd array repeats, so the period is 10")
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
