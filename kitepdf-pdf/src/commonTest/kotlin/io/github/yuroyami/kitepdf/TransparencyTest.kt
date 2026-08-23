package io.github.yuroyami.kitepdf

import io.github.yuroyami.kitepdf.core.ByteArrayBuilder
import io.github.yuroyami.kitepdf.core.parser.IndirectResolver
import io.github.yuroyami.kitepdf.core.parser.PdfDictionary
import io.github.yuroyami.kitepdf.core.parser.PdfName
import io.github.yuroyami.kitepdf.core.parser.PdfReal
import io.github.yuroyami.kitepdf.core.render.KiteBlendMode
import io.github.yuroyami.kitepdf.core.render.ExtGState
import io.github.yuroyami.kitepdf.core.render.KiteMatrix
import io.github.yuroyami.kitepdf.core.render.RecordingCanvas
import io.github.yuroyami.kitepdf.core.render.SoftMask
import io.github.yuroyami.kitepdf.core.render.applyExtGState
import io.github.yuroyami.kitepdf.core.render.GraphicsState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TransparencyTest {

    private val noopResolver = IndirectResolver { null }

    @Test
    fun blendMode_parses_all_16_names() {
        assertEquals(KiteBlendMode.Normal, KiteBlendMode.parse("Normal"))
        assertEquals(KiteBlendMode.Normal, KiteBlendMode.parse("Compatible"))   // legacy alias
        assertEquals(KiteBlendMode.Multiply, KiteBlendMode.parse("Multiply"))
        assertEquals(KiteBlendMode.Screen, KiteBlendMode.parse("Screen"))
        assertEquals(KiteBlendMode.Overlay, KiteBlendMode.parse("Overlay"))
        assertEquals(KiteBlendMode.Darken, KiteBlendMode.parse("Darken"))
        assertEquals(KiteBlendMode.Lighten, KiteBlendMode.parse("Lighten"))
        assertEquals(KiteBlendMode.ColorDodge, KiteBlendMode.parse("ColorDodge"))
        assertEquals(KiteBlendMode.ColorBurn, KiteBlendMode.parse("ColorBurn"))
        assertEquals(KiteBlendMode.HardLight, KiteBlendMode.parse("HardLight"))
        assertEquals(KiteBlendMode.SoftLight, KiteBlendMode.parse("SoftLight"))
        assertEquals(KiteBlendMode.Difference, KiteBlendMode.parse("Difference"))
        assertEquals(KiteBlendMode.Exclusion, KiteBlendMode.parse("Exclusion"))
        assertEquals(KiteBlendMode.Hue, KiteBlendMode.parse("Hue"))
        assertEquals(KiteBlendMode.Saturation, KiteBlendMode.parse("Saturation"))
        assertEquals(KiteBlendMode.Color, KiteBlendMode.parse("Color"))
        assertEquals(KiteBlendMode.Luminosity, KiteBlendMode.parse("Luminosity"))
        assertEquals(KiteBlendMode.Normal, KiteBlendMode.parse("BogusName"))   // fallback
        assertEquals(KiteBlendMode.Normal, KiteBlendMode.parse(null))
    }

    @Test
    fun extgstate_parses_alpha_and_blend_mode() {
        val dict = PdfDictionary(linkedMapOf(
            "Type" to PdfName("ExtGState"),
            "ca" to PdfReal(0.4),
            "CA" to PdfReal(0.8),
            "BM" to PdfName("Multiply"),
        ))
        val gs = ExtGState.parse(dict, noopResolver)
        assertEquals(0.4, gs.fillAlpha)
        assertEquals(0.8, gs.strokeAlpha)
        assertEquals(KiteBlendMode.Multiply, gs.blendMode)
    }

    @Test
    fun extgstate_smask_none_clears_active_mask() {
        val dict = PdfDictionary(linkedMapOf(
            "SMask" to PdfName("None"),
        ))
        val gs = ExtGState.parse(dict, noopResolver)
        assertEquals(SoftMask.None, gs.softMask)
    }

    @Test
    fun applying_extgstate_merges_only_present_fields() {
        val base = GraphicsState(
            fillAlpha = 0.5, strokeAlpha = 0.5, blendMode = KiteBlendMode.Darken,
        )
        val ext = ExtGState(fillAlpha = 0.3, blendMode = KiteBlendMode.Multiply)
        val merged = base.applyExtGState(ext)
        assertEquals(0.3, merged.fillAlpha)     // overridden
        assertEquals(0.5, merged.strokeAlpha)   // preserved
        assertEquals(KiteBlendMode.Multiply, merged.blendMode)
    }

    @Test
    fun gs_operator_propagates_alpha_to_fill_calls() {
        val pdf = pdfWithGs(
            extGStateDict = "<< /ca 0.5 /BM /Multiply >>",
            content = "/GS1 gs 0.5 0.5 1 rg 10 10 100 100 re f",
        )
        val doc = KitePDF.open(pdf)
        val canvas = RecordingCanvas()
        doc.pages[0].renderTo(canvas, KiteMatrix.IDENTITY)
        val fills = canvas.calls.filterIsInstance<RecordingCanvas.Call.Fill>()
        assertEquals(1, fills.size)
        assertEquals(0.5, fills[0].alpha, "fill alpha should reflect /ca")
        assertEquals(KiteBlendMode.Multiply, fills[0].blendMode, "blend mode should reflect /BM")
    }

    @Test
    fun gs_only_overrides_named_fields() {
        // /GS1 sets /ca only. /CA stays at its default (1.0).
        val pdf = pdfWithGs(
            extGStateDict = "<< /ca 0.25 >>",
            content = "/GS1 gs 1 0 0 RG 0 0 0 rg 10 10 100 100 re B",
        )
        val doc = KitePDF.open(pdf)
        val canvas = RecordingCanvas()
        doc.pages[0].renderTo(canvas, KiteMatrix.IDENTITY)
        val fills = canvas.calls.filterIsInstance<RecordingCanvas.Call.Fill>()
        val strokes = canvas.calls.filterIsInstance<RecordingCanvas.Call.Stroke>()
        assertEquals(1, fills.size); assertEquals(1, strokes.size)
        assertEquals(0.25, fills[0].alpha)
        assertEquals(1.0, strokes[0].alpha)   // /CA unset → default
    }

    @Test
    fun text_inherits_fill_alpha_from_gs() {
        val pdf = pdfWithGs(
            extGStateDict = "<< /ca 0.3 >>",
            content = "/GS1 gs BT /F1 14 Tf 0 0 0 rg 72 720 Td (translucent) Tj ET",
        )
        val doc = KitePDF.open(pdf)
        val canvas = RecordingCanvas()
        doc.pages[0].renderTo(canvas, KiteMatrix.IDENTITY)
        val texts = canvas.calls.filterIsInstance<RecordingCanvas.Call.Glyphs>()
        assertEquals(1, texts.size)
        assertEquals(0.3, texts[0].alpha)
        assertTrue(texts[0].text.contains("translucent"))
    }

    /**
     * Build a one-page PDF with a /ExtGState resource named /GS1 carrying
     * the given dict body, and the given content stream. Useful for verifying
     * the `gs <name>` operator wiring end-to-end.
     */
    private fun pdfWithGs(extGStateDict: String, content: String): ByteArray {
        val buf = ByteArrayBuilder()
        val offsets = mutableListOf<Int>()
        fun w(s: String) = buf.append(s.encodeToByteArray())

        w("%PDF-1.4\n%Äå\n")
        offsets.add(buf.size())
        w("1 0 obj\n<< /Type /Catalog /Pages 2 0 R >>\nendobj\n")
        offsets.add(buf.size())
        w("2 0 obj\n<< /Type /Pages /Kids [3 0 R] /Count 1 >>\nendobj\n")
        offsets.add(buf.size())
        w("3 0 obj\n<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] " +
            "/Resources << /Font << /F1 4 0 R >> /ExtGState << /GS1 $extGStateDict >> >> " +
            "/Contents 5 0 R >>\nendobj\n")
        offsets.add(buf.size())
        w("4 0 obj\n<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>\nendobj\n")
        offsets.add(buf.size())
        val payload = content.encodeToByteArray()
        w("5 0 obj\n<< /Length ${payload.size} >>\nstream\n")
        buf.append(payload); w("\nendstream\nendobj\n")
        val xref = buf.size()
        w("xref\n0 6\n0000000000 65535 f \n")
        for (o in offsets) w("${o.toString().padStart(10, '0')} 00000 n \n")
        w("trailer\n<< /Size 6 /Root 1 0 R >>\nstartxref\n$xref\n%%EOF\n")
        return buf.toByteArray()
    }
}
