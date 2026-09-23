package io.github.yuroyami.kitepdf

import io.github.yuroyami.kitepdf.core.KiteRectangle
import io.github.yuroyami.kitepdf.core.render.KiteCanvas
import io.github.yuroyami.kitepdf.core.render.KiteMaskTransfer
import io.github.yuroyami.kitepdf.core.render.KiteMatrix
import io.github.yuroyami.kitepdf.core.render.NoopCanvas
import io.github.yuroyami.kitepdf.core.render.RecordingCanvas
import io.github.yuroyami.kitepdf.core.render.RgbColor
import io.github.yuroyami.kitepdf.core.render.SoftMask
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The /BC backdrop and the /TR transfer function of a soft mask reach the canvas
 * (ISO 32000-1, 11.6.5.2, Table 144, #68).
 */
class SoftMaskBackdropTest {

    /** One soft mask as the canvas received it, with the calls its mask callback made. */
    private class Mask(val kind: SoftMask.Kind, val box: KiteRectangle, val transfer: KiteMaskTransfer?, val calls: List<RecordingCanvas.Call>)

    /** Records each soft mask, through whichever overload the renderer calls. */
    private class MaskCanvas : KiteCanvas by NoopCanvas {
        val masks = ArrayList<Mask>()

        override fun applySoftMask(
            kind: SoftMask.Kind, maskBBox: KiteRectangle, maskCtm: KiteMatrix,
            render: () -> Unit, renderMask: (KiteCanvas) -> Unit,
        ) {
            applySoftMask(kind, maskBBox, maskCtm, null, render, renderMask)
        }

        override fun applySoftMask(
            kind: SoftMask.Kind, maskBBox: KiteRectangle, maskCtm: KiteMatrix, transfer: KiteMaskTransfer?,
            render: () -> Unit, renderMask: (KiteCanvas) -> Unit,
        ) {
            val mask = RecordingCanvas()
            renderMask(mask)
            masks += Mask(kind, maskBBox, transfer, mask.calls)
            render()
        }
    }

    private val page = KiteRectangle(0.0, 0.0, 200.0, 200.0)
    private val box = KiteRectangle(40.0, 40.0, 120.0, 120.0)
    private val inverter = "/TR << /FunctionType 2 /Domain [0 1] /C0 [1] /C1 [0] /N 1 >>"

    /** A red page under a soft mask of [kind] with [entries], whose group in [space] paints black in [box]. */
    private fun mask(kind: String, entries: String, space: String = "DeviceGray"): Mask {
        val pdf = TestPdf.onePage(
            "/GS1 gs 1 0 0 rg 0 0 200 200 re f",
            resources = "/ExtGState << /GS1 << /SMask << /S /$kind /G 5 0 R $entries >> >> >>",
            extra = listOf(
                TestPdf.stream("0 g 40 40 80 80 re f", "/Type /XObject /Subtype /Form /BBox [40 40 120 120] /Group << /S /Transparency /CS /$space >>"),
            ),
        )
        val canvas = MaskCanvas()
        PdfDocument.open(pdf).pages[0].renderTo(canvas, KiteMatrix.IDENTITY)
        return canvas.masks.single()
    }

    /** The colour of the first paint of the mask callback, when it fills before the group paints. */
    private fun Mask.backdrop(): RgbColor? = (calls.firstOrNull() as? RecordingCanvas.Call.Fill)?.color

    @Test
    fun a_mask_without_backdrop_or_transfer_function_keeps_to_the_box_of_its_group() {
        val m = mask("Luminosity", "")
        assertEquals(box, m.box)
        assertNull(m.transfer)
        assertNull(m.backdrop())
    }

    @Test
    fun a_white_backdrop_paints_under_the_group_and_covers_the_page() {
        val m = mask("Luminosity", "/BC [1]")
        assertEquals(page, m.box)
        assertEquals(RgbColor(1.0, 1.0, 1.0), m.backdrop())
    }

    @Test
    fun a_backdrop_reads_its_components_in_the_colour_space_of_the_group() {
        val m = mask("Luminosity", "/BC [0 0 1]", space = "DeviceRGB")
        assertEquals(RgbColor(0.0, 0.0, 1.0), m.backdrop())
        // Blue has some luminosity, so the content shows a little outside the group.
        assertEquals(page, m.box)
    }

    @Test
    fun a_black_backdrop_changes_nothing() {
        val m = mask("Luminosity", "/BC [0]")
        assertEquals(box, m.box)
        assertNull(m.backdrop())
    }

    @Test
    fun an_alpha_mask_ignores_the_backdrop() {
        val m = mask("Alpha", "/BC [1]")
        assertEquals(box, m.box)
        assertNull(m.backdrop())
    }

    @Test
    fun a_transfer_function_reaches_the_canvas_and_can_uncover_the_page() {
        val m = mask("Luminosity", inverter)
        val t = assertNotNull(m.transfer)
        assertEquals(255, t[0])
        assertEquals(0, t[255])
        // The inverter maps the black backdrop to a full mask value, so the page shows outside the group.
        assertEquals(page, m.box)
    }

    @Test
    fun a_transfer_function_that_hides_the_backdrop_keeps_to_the_box_of_its_group() {
        // A white backdrop through the inverter is zero, so nothing shows outside the group.
        val m = mask("Luminosity", "/BC [1] $inverter")
        assertEquals(box, m.box)
        assertEquals(RgbColor(1.0, 1.0, 1.0), m.backdrop())
    }

    @Test
    fun the_identity_name_is_no_transfer_function() {
        assertNull(mask("Luminosity", "/TR /Identity").transfer)
    }

    @Test
    fun a_mask_dictionary_may_be_an_indirect_object() {
        val pdf = TestPdf.onePage(
            "/GS1 gs 1 0 0 rg 0 0 200 200 re f",
            resources = "/ExtGState << /GS1 << /SMask 6 0 R >> >>",
            extra = listOf(
                TestPdf.stream("0 g 40 40 80 80 re f", "/Type /XObject /Subtype /Form /BBox [40 40 120 120] /Group << /S /Transparency >>"),
                "<< /Type /Mask /S /Luminosity /G 5 0 R >>",
            ),
        )
        val canvas = MaskCanvas()
        PdfDocument.open(pdf).pages[0].renderTo(canvas, KiteMatrix.IDENTITY)
        assertEquals(1, canvas.masks.size)
        assertTrue(canvas.masks.single().calls.isNotEmpty(), "the group paints into the mask")
    }
}
