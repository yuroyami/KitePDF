package com.yuroyami.kitepdf.compose

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
import com.yuroyami.kitepdf.KitePDF
import com.yuroyami.kitepdf.core.ByteArrayBuilder
import com.yuroyami.kitepdf.render.Matrix as PdfMatrix
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Golden render test for the ComposeCanvas backend — the path GoldenHour uses.
 * Builds a PDF exercising every primitive that has silently broken before
 * (system-font text, a vector fill, a hairline stroke, a RAW/Flate image),
 * renders it off-screen exactly as the app does (ImageBitmap + CanvasDrawScope
 * + a hand-built TextMeasurer), and asserts the expected pixels are painted.
 *
 * Closes the process gap that let invisible text / placeholder images /
 * sub-pixel strokes ship green: prior tests checked extraction + draw-call
 * emission, never rendered pixels.
 */
class RenderGoldenTest {

    private val scale = 2.0
    private val pageW = 300
    private val pageH = 300

    @Test
    fun text_vector_stroke_and_raw_image_all_paint() {
        val doc = KitePDF.open(buildPdf())
        val page = doc.pages[0]
        val w = (pageW * scale).toInt()
        val h = (pageH * scale).toInt()

        val bmp = ImageBitmap(w, h)
        val density = Density(1f)
        val ld = LayoutDirection.Ltr
        val tm = TextMeasurer(createFontFamilyResolver(), density, ld)
        CanvasDrawScope().draw(density, ld, Canvas(bmp), Size(w.toFloat(), h.toFloat())) {
            drawRect(Color.White, size = size)
            page.renderTo(ComposeCanvas(this, tm), PdfMatrix(scale, 0.0, 0.0, -scale, 0.0, h.toDouble()))
        }
        val sk = bmp.asSkiaBitmap()

        fun dx(x: Int) = (x * scale).toInt()
        fun dy(pdfY: Int) = ((pageH - pdfY) * scale).toInt()

        // 1) System-font text "Hg" near (10,250) → dark pixels in its box.
        var darkText = 0
        for (yy in dy(285)..dy(235)) for (xx in dx(8)..dx(95)) if (isDark(sk.getColor(xx, yy))) darkText++
        assertTrue(darkText > 20, "system-font text not rendered (dark px=$darkText)")

        // 2) Vector fill: red rect (10,150) 60x40 → centre is red.
        assertTrue(isReddish(sk.getColor(dx(40), dy(170))), "vector fill not red")

        // 3) Hairline stroke (width 0.1) along y=120 → a 1px anti-aliased line reads
        //    as grey, not pure black, so count non-white pixels on that row band.
        var strokePx = 0
        val syy = dy(120)
        for (xx in dx(15)..dx(285)) for (yy in (syy - 2)..(syy + 2)) if (isNonWhite(sk.getColor(xx, yy))) strokePx++
        assertTrue(strokePx > 50, "hairline stroke invisible (non-white px=$strokePx)")

        // 4) RAW blue image (2x2 DeviceRGB) at cm(40,40 @200,230) → centre is blue, not grey placeholder.
        assertTrue(isBluish(sk.getColor(dx(220), dy(250))), "RAW image not painted (placeholder?)")
    }

    @Test
    fun system_font_text_size_is_density_independent() {
        // A Compose `Sp` font size is re-scaled by device density + accessibility font scale
        // when measured, but the renderer's size is ALREADY in device px — so without cancelling
        // them, text rendered ~density× too large (and overlapped on high-density Android).
        // Same px raster, only the Density differs: the painted text must be identical.
        val pdf = buildPdf()
        fun darkPixels(density: Float): Int {
            val doc = KitePDF.open(pdf)
            val page = doc.pages[0]
            val w = (pageW * scale).toInt(); val h = (pageH * scale).toInt()
            val bmp = ImageBitmap(w, h)
            val d = Density(density)
            val tm = TextMeasurer(createFontFamilyResolver(), d, LayoutDirection.Ltr)
            CanvasDrawScope().draw(d, LayoutDirection.Ltr, Canvas(bmp), Size(w.toFloat(), h.toFloat())) {
                drawRect(Color.White, size = size)
                page.renderTo(ComposeCanvas(this, tm), PdfMatrix(scale, 0.0, 0.0, -scale, 0.0, h.toDouble()))
            }
            val sk = bmp.asSkiaBitmap()
            var dark = 0
            for (yy in 0 until h) for (xx in 0 until w) if (isDark(sk.getColor(xx, yy))) dark++
            return dark
        }
        val d1 = darkPixels(1f)
        val d3 = darkPixels(3f)
        assertTrue(d1 > 50, "no text rendered at density 1 (dark=$d1)")
        assertTrue(
            kotlin.math.abs(d1 - d3) <= d1 / 20,
            "text size is density-dependent (Sp not cancelled): density1=$d1 vs density3=$d3",
        )
    }

    private fun r(c: Int) = (c shr 16) and 0xFF
    private fun g(c: Int) = (c shr 8) and 0xFF
    private fun b(c: Int) = c and 0xFF
    private fun isDark(c: Int) = r(c) < 110 && g(c) < 110 && b(c) < 110
    private fun isNonWhite(c: Int) = r(c) < 220 || g(c) < 220 || b(c) < 220
    private fun isReddish(c: Int) = r(c) > 150 && g(c) < 100 && b(c) < 100
    private fun isBluish(c: Int) = b(c) > 150 && r(c) < 100 && g(c) < 100

    private fun buildPdf(): ByteArray {
        val content = """
            0 0 0 rg
            BT /F1 40 Tf 10 250 Td (Hg) Tj ET
            1 0 0 rg 10 150 60 40 re f
            0 0 0 RG 0.1 w 10 120 m 290 120 l S
            q 40 0 0 40 200 230 cm /Im0 Do Q
        """.trimIndent().encodeToByteArray()

        // 2x2 DeviceRGB image, all blue (0,0,255), raw (no filter).
        val img = ByteArray(12)
        for (p in 0 until 4) { img[p * 3] = 0; img[p * 3 + 1] = 0; img[p * 3 + 2] = 0xFF.toByte() }

        val buf = ByteArrayBuilder()
        val offsets = mutableListOf<Int>()
        fun wr(s: String) = buf.append(s.encodeToByteArray())
        wr("%PDF-1.4\n%Äå\n")
        offsets.add(buf.size()); wr("1 0 obj\n<< /Type /Catalog /Pages 2 0 R >>\nendobj\n")
        offsets.add(buf.size()); wr("2 0 obj\n<< /Type /Pages /Kids [3 0 R] /Count 1 /MediaBox [0 0 $pageW $pageH] >>\nendobj\n")
        offsets.add(buf.size()); wr("3 0 obj\n<< /Type /Page /Parent 2 0 R /Resources << /Font << /F1 4 0 R >> /XObject << /Im0 5 0 R >> >> /Contents 6 0 R >>\nendobj\n")
        offsets.add(buf.size()); wr("4 0 obj\n<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>\nendobj\n")
        offsets.add(buf.size()); wr("5 0 obj\n<< /Type /XObject /Subtype /Image /Width 2 /Height 2 /ColorSpace /DeviceRGB /BitsPerComponent 8 /Length ${img.size} >>\nstream\n"); buf.append(img); wr("\nendstream\nendobj\n")
        offsets.add(buf.size()); wr("6 0 obj\n<< /Length ${content.size} >>\nstream\n"); buf.append(content); wr("\nendstream\nendobj\n")
        val xref = buf.size()
        wr("xref\n0 7\n0000000000 65535 f \n")
        for (o in offsets) wr("${o.toString().padStart(10, '0')} 00000 n \n")
        wr("trailer\n<< /Size 7 /Root 1 0 R >>\nstartxref\n$xref\n%%EOF\n")
        return buf.toByteArray()
    }
}
