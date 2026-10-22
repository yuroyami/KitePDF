package io.github.yuroyami.kitepdf.svg

import io.github.yuroyami.kitepdf.core.KiteRectangle
import io.github.yuroyami.kitepdf.core.render.KiteCanvas
import io.github.yuroyami.kitepdf.core.render.KiteMatrix
import io.github.yuroyami.kitepdf.core.render.RecordingCanvas
import io.github.yuroyami.kitepdf.core.render.SoftMask
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/** A mask draws its content clipped to its own region, whatever the canvas does with the mask box (#209). */
class SvgMaskTest {

    /** Records the page, and the content of each mask on a canvas of its own. */
    private class MaskRecorder : KiteCanvas by RecordingCanvas() {
        val masks = ArrayList<Pair<SoftMask.Kind, RecordingCanvas>>()
        override fun applySoftMask(
            kind: SoftMask.Kind, maskBBox: KiteRectangle, maskCtm: KiteMatrix, render: () -> Unit, renderMask: (KiteCanvas) -> Unit,
        ) {
            render()
            masks += kind to RecordingCanvas().also(renderMask)
        }
    }

    @Test
    fun the_mask_content_is_clipped_to_the_mask_region() {
        val image = assertNotNull(
            SvgImage.parse(
                """<svg xmlns="http://www.w3.org/2000/svg" width="100" height="100">
                    <mask id="r" maskUnits="userSpaceOnUse" x="0" y="10" width="30" height="50"><rect width="100" height="100" fill="white"/></mask>
                    <rect width="100" height="100" fill="#00f" mask="url(#r)"/></svg>""".encodeToByteArray(),
            ),
        )
        val canvas = MaskRecorder()
        image.render(canvas, KiteMatrix.IDENTITY)
        val (kind, content) = canvas.masks.single()
        assertEquals(SoftMask.Kind.Luminosity, kind)
        val clip = content.calls.filterIsInstance<RecordingCanvas.Call.PushClip>().first()
        assertEquals(KiteRectangle(0.0, 10.0, 30.0, 60.0), clip.path.bounds(clip.ctm))
        assertEquals(1, content.calls.filterIsInstance<RecordingCanvas.Call.Fill>().size, "the mask content draws once")
    }
}
