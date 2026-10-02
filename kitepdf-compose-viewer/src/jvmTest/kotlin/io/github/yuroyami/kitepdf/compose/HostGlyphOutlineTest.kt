package io.github.yuroyami.kitepdf.compose

import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.PixelMap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.font.createFontFamilyResolver
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import io.github.yuroyami.kitepdf.PdfDocument
import io.github.yuroyami.kitepdf.core.font.FontSpec
import io.github.yuroyami.kitepdf.core.font.KiteFontFamily
import io.github.yuroyami.kitepdf.core.render.KiteMatrix
import io.github.yuroyami.kitepdf.core.render.KitePath
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Stroked and clipping text in a font with no program of its own takes its shapes from the host
 * face that stands in for the font. The canvas used the rectangles of a text selection, so a
 * stroked O was a box (ISO 32000-1, 9.3.6, #415).
 */
class HostGlyphOutlineTest {

    private val density = Density(1f)
    private val measurer = TextMeasurer(createFontFamilyResolver(), density, LayoutDirection.Ltr)

    /** A 300 x 300 page whose font /F1 is neither embedded nor one of the standard 14. */
    private fun pdf(content: String): PdfDocument {
        val sb = StringBuilder("%PDF-1.7\n")
        val offsets = ArrayList<Int>()
        fun add(body: String) {
            offsets.add(sb.length)
            sb.append("${offsets.size} 0 obj\n$body\nendobj\n")
        }
        add("<< /Type /Catalog /Pages 2 0 R >>")
        add("<< /Type /Pages /Kids [3 0 R] /Count 1 >>")
        add("<< /Type /Page /Parent 2 0 R /MediaBox [0 0 300 300] /Resources << /Font << /F1 5 0 R >> >> /Contents 4 0 R >>")
        add("<< /Length ${content.length} >>\nstream\n$content\nendstream")
        add("<< /Type /Font /Subtype /TrueType /BaseFont /Verdana >>")
        val xref = sb.length
        sb.append("xref\n0 ${offsets.size + 1}\n0000000000 65535 f \n")
        for (offset in offsets) sb.append("${offset.toString().padStart(10, '0')} 00000 n \n")
        sb.append("trailer\n<< /Size ${offsets.size + 1} /Root 1 0 R >>\nstartxref\n$xref\n%%EOF\n")
        return PdfDocument.open(sb.toString().encodeToByteArray())
    }

    private fun render(content: String): PixelMap {
        val bitmap = ImageBitmap(300, 300)
        val page = pdf(content).pages[0]
        CanvasDrawScope().drawOnTestUiThread(density, LayoutDirection.Ltr, Canvas(bitmap), Size(300f, 300f)) {
            drawRect(Color.White, size = size)
            page.renderTo(ComposeCanvas(this, measurer), page.pageToDeviceBase())
        }
        return bitmap.toPixelMap()
    }

    /** The bounds of the pixels that [inked] accepts, as left, top, right, bottom. */
    private fun bounds(pixels: PixelMap, inked: (Color) -> Boolean): List<Int>? {
        var l = Int.MAX_VALUE; var t = Int.MAX_VALUE; var r = -1; var b = -1
        for (x in 0 until 300) for (y in 0 until 300) if (inked(pixels[x, y])) {
            l = minOf(l, x); t = minOf(t, y); r = maxOf(r, x); b = maxOf(b, y)
        }
        return if (r < 0) null else listOf(l, t, r, b)
    }

    @Test
    fun a_host_outline_has_the_curves_of_its_letter() {
        lateinit var outline: KitePath
        CanvasDrawScope().drawOnTestUiThread(density, LayoutDirection.Ltr, Canvas(ImageBitmap(10, 10)), Size(10f, 10f)) {
            outline = assertNotNull(ComposeCanvas(this, measurer).hostGlyphOutline("O", FontSpec(KiteFontFamily.SansSerif, false, false)))
        }
        assertTrue(
            outline.segments.any { it is KitePath.Segment.CurveTo || it is KitePath.Segment.QuadTo },
            "an O has curves, not the four lines of a box: ${outline.segments.take(6)}",
        )
    }

    @Test
    fun clipping_text_exposes_the_letter_and_not_its_box() {
        // Mode 7 adds the O to the clip, and the red fill then shows through its ring only.
        val pixels = render("BT /F1 200 Tf 7 Tr 40 60 Td (O) Tj ET 1 0 0 rg 0 0 300 300 re f")
        val red = { c: Color -> c.red > 0.8f && c.green < 0.3f }
        val (l, t, r, b) = assertNotNull(bounds(pixels, red), "the clip shows some of the fill")
        // The baseline is at y = 300 - 60 = 240 on the page, and the O stands on it.
        assertTrue(b in 230..250 && t < 150, "the O stands on its baseline: $t..$b")
        assertTrue(!red(pixels[(l + r) / 2, (t + b) / 2]), "the hole of the O stays unpainted")
        assertTrue(!red(pixels[l + 3, t + 3]), "the corner of the O's bounds stays unpainted")
    }

    @Test
    fun stroked_text_draws_the_letter_and_not_its_box() {
        // Mode 1 strokes the O. A box would put its stroke through the corners of its bounds.
        val pixels = render("BT /F1 200 Tf 1 Tr 6 w 0 0 1 RG 40 60 Td (O) Tj ET")
        val blue = { c: Color -> c.blue > 0.8f && c.red < 0.3f }
        val (l, t, r, b) = assertNotNull(bounds(pixels, blue), "the stroke draws")
        assertTrue(blue(pixels[l + 2, (t + b) / 2]), "the stroke runs down the side of the O")
        assertTrue(!blue(pixels[l + 4, t + 4]) && !blue(pixels[r - 4, b - 4]), "the corners of the O's bounds stay unpainted")
    }
}
