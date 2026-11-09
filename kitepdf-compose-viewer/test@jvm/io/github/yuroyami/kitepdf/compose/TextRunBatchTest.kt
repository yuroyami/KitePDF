package io.github.yuroyami.kitepdf.compose

import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import io.github.yuroyami.kitepdf.PdfDocument
import io.github.yuroyami.kitepdf.core.render.KiteMatrix
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A page typeset word by word draws its text in a few bitmaps, not one a word (#790): the runs of
 * a `TJ` array join while they keep their colour and their box stays dense, and the pixels are
 * the ones a bitmap a run gives, to within a level.
 */
class TextRunBatchTest {

    private val measurer = TextMeasurer(testFontFamilyResolver(), Density(1f), LayoutDirection.Ltr)

    /** An A4 page of 70 lines of Helvetica 9 pt, each line one TJ with a string per word, and a red word in the middle. */
    private fun page(): ByteArray {
        val words = "The quick brown fox jumps over the lazy dog again".split(' ')
        val content = StringBuilder()
        for (line in 0 until 70) {
            val y = 800 - line * 11
            val tj = words.joinToString(" -20 ") { "($it )" }
            content.append("BT /F1 9 Tf 40 $y Td [$tj] TJ ET\n")
            if (line == 35) content.append("BT 1 0 0 rg /F1 9 Tf 400 $y Td (red) Tj ET\n")
        }
        val c = content.toString()
        val objects = listOf(
            "<< /Type /Catalog /Pages 2 0 R >>",
            "<< /Type /Pages /Kids [3 0 R] /Count 1 >>",
            "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 595 842] /Contents 4 0 R /Resources << /Font << /F1 5 0 R >> >> >>",
            "<< /Length ${c.length} >>\nstream\n$c\nendstream",
            "<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>",
        )
        val out = StringBuilder("%PDF-1.7\n")
        val offsets = ArrayList<Int>()
        objects.forEachIndexed { i, body -> offsets += out.length; out.append("${i + 1} 0 obj\n$body\nendobj\n") }
        val xref = out.length
        out.append("xref\n0 ${objects.size + 1}\n0000000000 65535 f \n")
        for (o in offsets) out.append(o.toString().padStart(10, '0')).append(" 00000 n \n")
        out.append("trailer\n<< /Size ${objects.size + 1} /Root 1 0 R >>\nstartxref\n$xref\n%%EOF\n")
        return out.toString().encodeToByteArray()
    }

    private class Drawn(val pixels: IntArray, val bitmaps: Int)

    private fun draw(batch: Boolean): Drawn {
        val doc = PdfDocument.open(page())
        val p = doc.pages[0]
        val w = 1190
        val h = 1684
        val bitmap = ImageBitmap(w, h)
        var bitmaps = 0
        CanvasDrawScope().draw(Density(1f), LayoutDirection.Ltr, Canvas(bitmap), Size(w.toFloat(), h.toFloat())) {
            drawRect(Color.White)
            val canvas = ComposeCanvas(
                this, measurer, hairlineWidthPx = 1f, skipSystemFontText = false, magnification = 1f,
                glyphMasks = GlyphMaskCache(), target = bitmap, batchText = batch,
            )
            p.renderTo(canvas, KiteMatrix.scaling(2.0, 2.0).concat(p.displayToDeviceBase()))
            bitmaps = canvas.runBitmaps
        }
        return Drawn(IntArray(w * h).also { bitmap.readPixels(it) }, bitmaps)
    }

    @Test
    fun a_page_set_word_by_word_draws_its_runs_in_few_bitmaps_with_the_same_pixels() {
        val each = draw(batch = false)
        val batched = draw(batch = true)
        println("text run bitmaps: ${each.bitmaps} a run, ${batched.bitmaps} batched")
        assertTrue(each.bitmaps >= 700, "a bitmap a word: ${each.bitmaps}")
        assertTrue(batched.bitmaps <= 80, "${batched.bitmaps} bitmaps for 70 lines")
        var worst = 0
        var red = 0
        for (i in each.pixels.indices) {
            for (shift in intArrayOf(0, 8, 16, 24)) worst = maxOf(worst, abs((each.pixels[i] ushr shift and 0xFF) - (batched.pixels[i] ushr shift and 0xFF)))
            val px = batched.pixels[i]
            if ((px ushr 16 and 0xFF) > 200 && (px ushr 8 and 0xFF) < 60 && (px and 0xFF) < 60) red++
        }
        assertTrue(worst <= 1, "the pixels differ by $worst levels")
        assertTrue(red > 20, "the red word drew, $red red pixels")
        assertEquals(each.pixels.size, batched.pixels.size)
    }
}
