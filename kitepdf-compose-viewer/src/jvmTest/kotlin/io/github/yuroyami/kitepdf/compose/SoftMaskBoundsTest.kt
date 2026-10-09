package io.github.yuroyami.kitepdf.compose

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import io.github.yuroyami.kitepdf.PdfDocument
import io.github.yuroyami.kitepdf.core.render.KiteMatrix
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A soft mask opens its two layers over the box of the mask, not over the whole canvas. Each
 * mask opened two layers the size of the canvas, so a small masked object cost as much as a
 * full-page one (#384).
 */
class SoftMaskBoundsTest {

    /** Records the bounds of every layer, and draws as the canvas it wraps. */
    private class LayerRecorder(private val inner: Canvas) : Canvas by inner {
        val layers = ArrayList<Rect>()
        override fun saveLayer(bounds: Rect, paint: Paint) {
            layers += bounds
            inner.saveLayer(bounds, paint)
        }
    }

    /**
     * A 200 x 200 page that fills red through a soft mask of [kind] whose group, with the box
     * 50 50 70 70, fills white. [maskEntries] go into the mask dictionary.
     */
    private fun maskedPdf(kind: String, maskEntries: String = ""): PdfDocument {
        val sb = StringBuilder("%PDF-1.7\n")
        val offsets = ArrayList<Int>()
        fun add(body: String) {
            offsets.add(sb.length)
            sb.append("${offsets.size} 0 obj\n$body\nendobj\n")
        }
        val group = if (kind == "Alpha") "0 g 50 50 20 20 re f" else "1 g 50 50 20 20 re f"
        add("<< /Type /Catalog /Pages 2 0 R >>")
        add("<< /Type /Pages /Kids [3 0 R] /Count 1 >>")
        add(
            "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 200 200] /Contents 4 0 R " +
                "/Resources << /ExtGState << /GS1 << /SMask << /Type /Mask /S /$kind /G 5 0 R $maskEntries >> >> >> >> >>",
        )
        val content = "/GS1 gs 1 0 0 rg 0 0 200 200 re f"
        add("<< /Length ${content.length} >>\nstream\n$content\nendstream")
        add(
            "<< /Type /XObject /Subtype /Form /BBox [50 50 70 70] /Group << /S /Transparency /CS /DeviceRGB >> " +
                "/Length ${group.length} >>\nstream\n$group\nendstream",
        )
        val xref = sb.length
        sb.append("xref\n0 ${offsets.size + 1}\n0000000000 65535 f \n")
        for (offset in offsets) sb.append("${offset.toString().padStart(10, '0')} 00000 n \n")
        sb.append("trailer\n<< /Size ${offsets.size + 1} /Root 1 0 R >>\nstartxref\n$xref\n%%EOF\n")
        return PdfDocument.open(sb.toString().encodeToByteArray())
    }

    /** Draws page 1 of [doc] on white paper, and returns the pixels and the layers that the draw opened. */
    private fun draw(doc: PdfDocument): Pair<ImageBitmap, List<Rect>> {
        val bitmap = ImageBitmap(200, 200)
        val recorder = LayerRecorder(Canvas(bitmap))
        val density = Density(1f)
        val measurer = TextMeasurer(testFontFamilyResolver(), density, LayoutDirection.Ltr)
        CanvasDrawScope().drawOnTestUiThread(density, LayoutDirection.Ltr, recorder, Size(200f, 200f)) {
            drawRect(Color.White)
            doc.pages[0].renderTo(ComposeCanvas(this, measurer), KiteMatrix(1.0, 0.0, 0.0, -1.0, 0.0, 200.0))
        }
        return bitmap to recorder.layers
    }

    @Test
    fun a_small_mask_opens_layers_over_its_box_only() {
        for (kind in listOf("Luminosity", "Alpha")) {
            val (bitmap, layers) = draw(maskedPdf(kind))
            assertTrue(layers.size >= 2, "$kind: the mask opened ${layers.size} layers")
            // The box 50 50 70 70 is x 50..70, y 130..150 on the canvas.
            for (layer in layers) {
                assertTrue(layer.left >= 50f && layer.top >= 130f && layer.right <= 70f && layer.bottom <= 150f, "$kind: a layer over $layer")
            }
            val pixels = bitmap.toPixelMap()
            // Only the box keeps the red fill. An alpha mask of opaque black lets it through as well.
            assertEquals(Color.Red, pixels[60, 140], "$kind: inside the box")
            assertEquals(Color.White, pixels[10, 10], "$kind: outside the box")
            assertEquals(Color.White, pixels[45, 140], "$kind: just left of the box")
        }
    }

    @Test
    fun a_backdrop_that_lets_content_through_still_covers_the_page() {
        // A white backdrop makes the mask one outside the group, so the red fill covers the page.
        val (bitmap, layers) = draw(maskedPdf("Luminosity", "/BC [1 1 1]"))
        assertTrue(layers.any { it.width >= 200f && it.height >= 200f }, "no layer covers the page: $layers")
        val pixels = bitmap.toPixelMap()
        assertEquals(Color.Red, pixels[10, 10])
        assertEquals(Color.Red, pixels[60, 140])
    }
}
