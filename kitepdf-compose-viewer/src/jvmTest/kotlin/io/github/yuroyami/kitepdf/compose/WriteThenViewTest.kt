package io.github.yuroyami.kitepdf.compose

import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asSkiaBitmap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.font.createFontFamilyResolver
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import io.github.yuroyami.kitepdf.KitePDF
import io.github.yuroyami.kitepdf.core.render.Matrix as PdfMatrix
import io.github.yuroyami.kitepdf.writer.PdfBuilder
import io.github.yuroyami.kitepdf.writer.PdfImage
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The full GoldenHour loop end to end: GENERATE a PDF with KitePDF's writer
 * (image + clip + text + vectors), then VIEW it through ComposeCanvas — the
 * same engine on both ends. Asserts the rendered pixels match what was written,
 * proving write/read consistency (the reason to generate reports with KitePDF
 * instead of per-platform PDF kits).
 */
class WriteThenViewTest {

    @Test
    fun generated_image_clip_and_vectors_render_correctly() {
        // 2×2 image: top row red, bottom row blue. Drawn into the lower-left quadrant.
        val pixels = byteArrayOf(
            0xFF.toByte(), 0, 0, 0xFF.toByte(),
            0xFF.toByte(), 0, 0, 0xFF.toByte(),
            0, 0, 0xFF.toByte(), 0xFF.toByte(),
            0, 0, 0xFF.toByte(), 0xFF.toByte(),
        )
        val img = PdfImage.rgba(pixels, 2, 2)

        val bytes = PdfBuilder()
            .page(width = 200.0, height = 200.0) {
                // Image in the bottom-left 100×100.
                drawImage(img, x = 0.0, y = 0.0, width = 100.0, height = 100.0)
                // Clipped green fill: clip to a 40×40 box at (120,120), then try to
                // fill the whole page — only the clip window should turn green.
                save()
                rectangle(120.0, 120.0, 40.0, 40.0)
                clip(); endPath()
                setFillRgb(0.0, 1.0, 0.0)
                rectangle(0.0, 0.0, 200.0, 200.0); fill()
                restore()
                // A hairline vector stroke across the top.
                setStrokeRgb(0.0, 0.0, 0.0)
                setLineWidth(0.1)
                moveTo(10.0, 190.0); lineTo(190.0, 190.0); stroke()
            }
            .build()

        val page = KitePDF.open(bytes).pages[0]
        val w = 200
        val h = 200
        val bmp = ImageBitmap(w, h)
        val density = Density(1f)
        val ld = LayoutDirection.Ltr
        val tm = TextMeasurer(createFontFamilyResolver(), density, ld)
        CanvasDrawScope().draw(density, ld, Canvas(bmp), Size(w.toFloat(), h.toFloat())) {
            drawRect(Color.White, size = size)
            page.renderTo(ComposeCanvas(this, tm), PdfMatrix(1.0, 0.0, 0.0, -1.0, 0.0, h.toDouble()))
        }
        val sk = bmp.asSkiaBitmap()
        fun r(c: Int) = (c shr 16) and 0xFF
        fun g(c: Int) = (c shr 8) and 0xFF
        fun b(c: Int) = c and 0xFF

        // PDF image rect (0,0)-(100,100) → device rect x 0..100, y 100..200 (Y-flip).
        // Image top row (red) maps to the TOP of that rect → device y ~120.
        val top = sk.getColor(25, 120)
        assertTrue(r(top) > 200 && b(top) < 80, "image top row not red: ${top.toString(16)}")
        // Image bottom row (blue) → bottom of the rect → device y ~180.
        val bottom = sk.getColor(25, 180)
        assertTrue(b(bottom) > 200 && r(bottom) < 80, "image bottom row not blue: ${bottom.toString(16)}")

        // Clip window (PDF 120..160 in both axes → device y = 200-140 = 60 area) is green.
        val inClip = sk.getColor(140, 60)
        assertTrue(g(inClip) > 200 && r(inClip) < 80 && b(inClip) < 80, "clip window not green: ${inClip.toString(16)}")
        // Just outside the clip window stays white (the page fill was clipped away).
        val outClip = sk.getColor(40, 60)
        assertTrue(r(outClip) > 200 && g(outClip) > 200 && b(outClip) > 200, "fill leaked outside clip: ${outClip.toString(16)}")
    }
}
