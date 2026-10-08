package io.github.yuroyami.kitepdf.compose

import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.font.createFontFamilyResolver
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import io.github.yuroyami.kitepdf.core.render.KiteImageData
import io.github.yuroyami.kitepdf.core.render.KiteMatrix
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * An image that the shared decoders refuse draws as the placeholder, on this canvas as on every
 * other. A JPEG header without image data still preserves its size and draws a placeholder (#184).
 */
class EncodedImagePlaceholderTest {

    /** SOI, a 64 by 48 SOF0 frame, then EOI without tables or a scan. */
    private val damagedJpeg = (
        "ffd8ffc00011080030004003012200021101031101ffd9"
    ).chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    @Test
    fun a_jpeg_the_shared_decoder_refuses_draws_as_the_placeholder() {
        val image = KiteImageData.fromEncodedImage(damagedJpeg) ?: error("no image")
        assertEquals(KiteImageData.Kind.JPEG, image.kind, "the damaged image must take the placeholder path")
        val bitmap = ImageBitmap(64, 48)
        CanvasDrawScope().drawOnTestUiThread(Density(1f), LayoutDirection.Ltr, Canvas(bitmap), Size(64f, 48f)) {
            ComposeCanvas(this, TextMeasurer(createFontFamilyResolver(), Density(1f), LayoutDirection.Ltr))
                .drawImage(image, KiteMatrix(64.0, 0.0, 0.0, -48.0, 0.0, 48.0), 1.0)
        }
        // A pixel inside the placeholder's grey fill, off its border and off both diagonals.
        val inside = bitmap.toPixelMap()[6, 24]
        assertEquals(0xE0 / 255f, inside.red, 0.01f)
        assertEquals(0xE0 / 255f, inside.green, 0.01f)
        assertEquals(0xE0 / 255f, inside.blue, 0.01f)
    }
}
