package io.github.yuroyami.kitepdf

import io.github.yuroyami.kitepdf.core.KiteRectangle
import io.github.yuroyami.kitepdf.core.render.KiteCanvas
import io.github.yuroyami.kitepdf.core.render.KiteMaskTransfer
import io.github.yuroyami.kitepdf.core.render.KiteMatrix
import io.github.yuroyami.kitepdf.core.render.NoopCanvas
import io.github.yuroyami.kitepdf.core.render.SoftMask
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The renderer hands the canvas one mask callback for every paint under the same mask in the
 * same coordinate system, so a canvas that keeps a mask renders it once (#912), and a new one
 * after a `gs` that sets another mask.
 */
class SharedSoftMaskTest {

    /** Counts the paints under a mask and the mask callbacks that came with them. */
    private class CountingCanvas : KiteCanvas by NoopCanvas {
        val callbacks = ArrayList<Any>()

        override fun applySoftMask(kind: SoftMask.Kind, maskBBox: KiteRectangle, maskCtm: KiteMatrix, render: () -> Unit, renderMask: (KiteCanvas) -> Unit) {
            callbacks += renderMask
            render()
        }

        override fun applySoftMask(
            kind: SoftMask.Kind, maskBBox: KiteRectangle, maskCtm: KiteMatrix, transfer: KiteMaskTransfer?, render: () -> Unit, renderMask: (KiteCanvas) -> Unit,
        ) = applySoftMask(kind, maskBBox, maskCtm, render, renderMask)

        /** How many of [callbacks] are different objects. */
        fun distinct(): Int {
            val seen = ArrayList<Any>()
            for (c in callbacks) if (seen.none { it === c }) seen += c
            return seen.size
        }
    }

    private fun count(content: String): CountingCanvas {
        val pdf = TestPdf.onePage(
            content,
            resources = "/ExtGState << /A << /SMask << /S /Luminosity /G 5 0 R >> >> /B << /SMask << /S /Alpha /G 5 0 R >> >> >>",
            extra = listOf(TestPdf.stream("0.5 g 0 0 200 200 re f", "/Type /XObject /Subtype /Form /BBox [0 0 200 200] /Group << /S /Transparency /CS /DeviceGray >>")),
        )
        val canvas = CountingCanvas()
        PdfDocument.open(pdf).pages[0].renderTo(canvas, KiteMatrix.IDENTITY)
        return canvas
    }

    @Test
    fun the_paints_under_one_mask_share_its_callback_through_a_cm_and_a_clip() {
        val squares = (0 until 30).joinToString(" ") { "q 1 0 0 1 ${it * 5} 10 cm 0 0 4 4 re f Q" }
        val canvas = count("/A gs q 0 0 150 150 re W n $squares Q 0 0 9 9 re S")
        assertEquals(31, canvas.callbacks.size)
        assertEquals(1, canvas.distinct())
    }

    @Test
    fun another_mask_or_another_coordinate_system_takes_a_new_callback() {
        // Another mask, the first mask again, and the first mask set again under another cm.
        val canvas = count("/A gs 0 0 4 4 re f 0 0 4 4 re f /B gs 0 0 4 4 re f /A gs 0 0 4 4 re f 2 0 0 2 0 0 cm /A gs 0 0 4 4 re f")
        assertEquals(5, canvas.callbacks.size)
        assertEquals(4, canvas.distinct())
    }
}
