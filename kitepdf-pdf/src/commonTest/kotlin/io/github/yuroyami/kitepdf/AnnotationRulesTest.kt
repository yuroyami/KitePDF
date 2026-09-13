package io.github.yuroyami.kitepdf

import io.github.yuroyami.kitepdf.core.render.KiteBlendMode
import io.github.yuroyami.kitepdf.core.render.KitePath
import io.github.yuroyami.kitepdf.core.render.RecordingCanvas
import io.github.yuroyami.kitepdf.core.render.RgbColor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Which annotations and layers paint, and how (ISO 32000-1, 8.11 and 12.5). */
class AnnotationRulesTest {

    private val green = RgbColor(0.0, 1.0, 0.0)
    private val blue = RgbColor(0.0, 0.0, 1.0)

    private fun fills(pdf: ByteArray) = TestPdf.calls(pdf).filterIsInstance<RecordingCanvas.Call.Fill>()

    /** One page drawing [content], with [annots] at objects 5, 6, ... and [extra] after them. */
    private fun pdf(
        annots: List<String>,
        extra: List<Any> = emptyList(),
        content: String = " ",
        resources: String = "",
        catalog: String = "",
    ): ByteArray = TestPdf.onePage(
        content = content,
        resources = resources,
        pageEntries = "/Annots [${annots.indices.joinToString(" ") { "${5 + it} 0 R" }}]",
        catalogEntries = catalog,
        extra = annots + extra,
    )

    private fun ap(content: String, bbox: String = "0 0 60 60") =
        TestPdf.stream(content, "/Type /XObject /Subtype /Form /BBox [$bbox]")

    @Test
    fun an_unclosed_hidden_layer_in_the_page_does_not_hide_annotations() {
        val calls = fills(pdf(
            annots = listOf("<< /Type /Annot /Subtype /Square /Rect [100 100 160 160] /AP << /N 6 0 R >> >>"),
            extra = listOf(ap("0 1 0 rg 0 0 60 60 re f"), "<< /Type /OCG /Name (Off) >>"),
            content = "0 0 1 rg 10 10 50 50 re f /OC /MC0 BDC 1 0 0 rg 100 10 50 50 re f",
            resources = "/Properties << /MC0 7 0 R >>",
            catalog = "/OCProperties << /OCGs [7 0 R] /D << /OFF [7 0 R] >> >>",
        ))
        assertEquals(listOf(blue, green), calls.map { it.color })
    }

    @Test
    fun an_annotation_on_a_switched_off_layer_is_skipped() {
        val calls = fills(pdf(
            annots = listOf("<< /Type /Annot /Subtype /Square /Rect [100 100 160 160] /OC 7 0 R /AP << /N 6 0 R >> >>"),
            extra = listOf(ap("0 1 0 rg 0 0 60 60 re f"), "<< /Type /OCG /Name (Off) >>"),
            content = "0 0 1 rg 10 10 50 50 re f",
            catalog = "/OCProperties << /OCGs [7 0 R] /D << /OFF [7 0 R] >> >>",
        ))
        assertEquals(listOf(blue), calls.map { it.color })
    }

    @Test
    fun a_cyclic_visibility_expression_counts_as_visible() {
        val calls = fills(TestPdf.onePage(
            content = "0 0 1 rg 10 10 50 50 re f /OC /MC0 BDC 1 0 0 rg 100 10 50 50 re f EMC",
            resources = "/Properties << /MC0 6 0 R >>",
            catalogEntries = "/OCProperties << /OCGs [5 0 R] /D << /OFF [5 0 R] >> >>",
            extra = listOf("<< /Type /OCG /Name (Off) >>", "<< /Type /OCMD /VE 7 0 R >>", "[/Not 7 0 R]"),
        ))
        assertEquals(2, calls.size)
    }

    @Test
    fun a_form_with_partial_resources_still_finds_the_page_layer() {
        val calls = fills(TestPdf.onePage(
            content = "0 0 1 rg 10 10 50 50 re f q 1 0 0 1 100 10 cm /Fm0 Do Q",
            resources = "/Properties << /MC0 6 0 R >> /XObject << /Fm0 5 0 R >>",
            catalogEntries = "/OCProperties << /OCGs [6 0 R] /D << /OFF [6 0 R] >> >>",
            extra = listOf(
                TestPdf.stream(
                    "/OC /MC0 BDC 1 0 0 rg 0 0 60 60 re f EMC",
                    "/Type /XObject /Subtype /Form /BBox [0 0 60 60] /Resources << /ColorSpace << /CS0 /DeviceRGB >> >>",
                ),
                "<< /Type /OCG /Name (Off) >>",
            ),
        ))
        assertEquals(listOf(blue), calls.map { it.color })
    }

    @Test
    fun an_unchanged_base_state_leaves_layers_on() {
        val doc = PdfDocument.open(TestPdf.onePage(
            content = " ",
            catalogEntries = "/OCProperties << /OCGs [5 0 R] /D << /BaseState /Unchanged >> >>",
            extra = listOf("<< /Type /OCG /Name (Layer) >>"),
        ))
        assertTrue(assertNotNull(doc.optionalContent).isVisibleByDefault("5"))
    }

    private fun checkbox(state: String) = pdf(
        annots = listOf("<< /Type /Annot /Subtype /Widget /FT /Btn /Rect [80 80 120 120] /F 4 /AS $state /AP << /N << /On 6 0 R >> >> >>"),
        extra = listOf(ap("0 0 0 rg 0 0 40 40 re f", "0 0 40 40")),
    )

    @Test
    fun a_named_state_with_no_appearance_paints_nothing() {
        assertEquals(0, fills(checkbox("/Off")).size, "an unchecked box with no /Off entry stays blank")
        assertEquals(1, fills(checkbox("/On")).size)
    }

    @Test
    fun a_synthesized_highlight_multiplies() {
        val fill = fills(pdf(listOf(
            "<< /Type /Annot /Subtype /Highlight /Rect [50 80 150 120] /F 4 /C [1 1 0] /QuadPoints [50 120 150 120 50 80 150 80] >>",
        ))).single()
        assertEquals(KiteBlendMode.Multiply, fill.blendMode)
        assertEquals(1.0, fill.alpha)
    }

    @Test
    fun a_rotated_highlight_follows_its_quadrilateral() {
        val fill = fills(pdf(listOf(
            "<< /Type /Annot /Subtype /Highlight /Rect [20 20 180 180] /F 4 /C [1 1 0] /QuadPoints [100 180 180 100 20 100 100 20] >>",
        ))).single()
        val points = fill.path.segments.mapNotNull {
            when (it) {
                is KitePath.Segment.MoveTo -> it.x to it.y
                is KitePath.Segment.LineTo -> it.x to it.y
                else -> null
            }
        }
        assertEquals(listOf(100.0 to 180.0, 180.0 to 100.0, 100.0 to 20.0, 20.0 to 100.0), points)
    }

    @Test
    fun a_no_zoom_stamp_keeps_its_own_size() {
        // /F 12 is Print plus NoZoom: the 20 x 20 artwork stays 20 x 20 at the upper-left.
        val fill = fills(pdf(
            annots = listOf("<< /Type /Annot /Subtype /Stamp /Rect [50 50 150 150] /F 12 /AP << /N 6 0 R >> >>"),
            extra = listOf(ap("0 0 1 rg 0 0 20 20 re f", "0 0 20 20")),
        )).single()
        assertEquals(1.0, fill.ctm.a, 1e-9)
        assertEquals(50.0, fill.ctm.e, 1e-9)
        assertEquals(130.0, fill.ctm.f, 1e-9)
    }

    private fun vendor(flags: Int) = pdf(
        annots = listOf("<< /Type /Annot /Subtype /FunkyVendorThing /Rect [50 50 150 150] /F $flags /AP << /N 6 0 R >> >>"),
        extra = listOf(ap("0 0 1 rg 0 0 100 100 re f", "0 0 100 100")),
    )

    @Test
    fun an_invisible_vendor_annotation_is_hidden() {
        assertEquals(0, fills(vendor(1)).size)
        assertEquals(1, fills(vendor(0)).size)
    }

    @Test
    fun annotation_opacity_applies_to_the_whole_appearance() {
        val calls = TestPdf.calls(pdf(
            annots = listOf("<< /Type /Annot /Subtype /Square /Rect [50 50 110 110] /CA 0.5 /AP << /N 6 0 R >> >>"),
            extra = listOf(ap("1 0 0 rg 0 0 40 40 re f 0 0 1 rg 20 20 40 40 re f")),
        ))
        assertEquals(0.5, calls.filterIsInstance<RecordingCanvas.Call.PushGroup>().single().alpha)
        val inside = calls.dropWhile { it !is RecordingCanvas.Call.PushGroup }.takeWhile { it !is RecordingCanvas.Call.PopGroup }
        assertEquals(listOf(1.0, 1.0), inside.filterIsInstance<RecordingCanvas.Call.Fill>().map { it.alpha })
    }

    @Test
    fun an_indirect_flag_word_is_read() {
        // Object 7 holds 2, the Hidden flag.
        val calls = fills(pdf(
            annots = listOf("<< /Type /Annot /Subtype /Square /Rect [50 50 110 110] /F 7 0 R /AP << /N 6 0 R >> >>"),
            extra = listOf(ap("1 0 0 rg 0 0 60 60 re f"), "2"),
        ))
        assertEquals(0, calls.size)
    }
}
