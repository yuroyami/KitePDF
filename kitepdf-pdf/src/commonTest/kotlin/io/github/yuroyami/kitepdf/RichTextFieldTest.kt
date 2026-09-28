package io.github.yuroyami.kitepdf

import io.github.yuroyami.kitepdf.core.ByteArrayBuilder
import io.github.yuroyami.kitepdf.core.parser.IndirectResolver
import io.github.yuroyami.kitepdf.core.parser.PdfDictionary
import io.github.yuroyami.kitepdf.core.parser.PdfInt
import io.github.yuroyami.kitepdf.core.parser.PdfName
import io.github.yuroyami.kitepdf.core.parser.PdfString
import io.github.yuroyami.kitepdf.core.render.KiteMatrix
import io.github.yuroyami.kitepdf.core.render.RecordingCanvas
import io.github.yuroyami.kitepdf.core.render.RgbColor
import io.github.yuroyami.kitepdf.writer.FieldAppearance
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * A rich text field draws its styled value, a multi-line field wraps and breaks its lines, and
 * filling a rich text field keeps its rich value in step (ISO 32000-1, 12.7.3.3 and 12.7.3.4, #204).
 */
class RichTextFieldTest {

    /** A one-page PDF of 300 x 300 with the widget [annot] as object 4 and the [extras] after it. */
    private fun pdf(annot: String, vararg extras: String): ByteArray {
        val buf = ByteArrayBuilder()
        val offsets = ArrayList<Int>()
        fun obj(body: String) {
            offsets += buf.size()
            buf.append("${offsets.size} 0 obj\n$body\nendobj\n".encodeToByteArray())
        }
        buf.append("%PDF-1.7\n".encodeToByteArray())
        obj("<< /Type /Catalog /Pages 2 0 R /AcroForm << /Fields [4 0 R] >> >>")
        obj("<< /Type /Pages /Kids [3 0 R] /Count 1 >>")
        obj("<< /Type /Page /Parent 2 0 R /MediaBox [0 0 300 300] /Annots [4 0 R] >>")
        obj(annot)
        for (e in extras) obj(e)
        val xref = buf.size()
        buf.append("xref\n0 ${offsets.size + 1}\n0000000000 65535 f \n".encodeToByteArray())
        for (o in offsets) buf.append("${o.toString().padStart(10, '0')} 00000 n \n".encodeToByteArray())
        buf.append("trailer\n<< /Size ${offsets.size + 1} /Root 1 0 R >>\nstartxref\n$xref\n%%EOF\n".encodeToByteArray())
        return buf.toByteArray()
    }

    private fun glyphs(pdf: ByteArray): List<RecordingCanvas.Call.Glyphs> =
        RecordingCanvas().also { KitePDF.open(pdf).pages[0].renderTo(it, KiteMatrix.IDENTITY) }.calls
            .filterIsInstance<RecordingCanvas.Call.Glyphs>()

    private val rich = """<?xml version="1.0"?><body xmlns="http://www.w3.org/1999/xhtml"><p style="text-align:center">Plain """ +
        """<span style="font-weight:bold;color:#ff0000">Bold</span></p></body>"""

    @Test
    fun a_rich_text_field_draws_its_styled_value() {
        val runs = glyphs(
            pdf("<< /Type /Annot /Subtype /Widget /FT /Tx /Ff 33554432 /T (r) /Rect [0 0 200 40] /DA (/Helv 10 Tf 0 g) /V (Plain Bold) /RV ($rich) /DS (font: 10pt Helvetica) >>"),
        )
        val plain = runs.first { it.text.startsWith("Plain") }
        val bold = runs.first { it.text == "Bold" }
        assertFalse(plain.fontSpec.bold)
        assertTrue(bold.fontSpec.bold, "the span is bold")
        assertEquals(RgbColor(1.0, 0.0, 0.0), bold.color, "the span is red")
        assertEquals(RgbColor(0.0, 0.0, 0.0), plain.color)
        // About 50 points of text in the middle of 200.
        assertTrue(plain.textToDevice.e > 60.0, "the paragraph is centred: it starts at ${plain.textToDevice.e}")
        assertEquals(plain.textToDevice.f, bold.textToDevice.f, 0.01, "one line")
    }

    @Test
    fun a_rich_value_in_a_stream_draws_too() {
        val stream = "<< /Length ${rich.length} >>\nstream\n$rich\nendstream"
        val runs = glyphs(
            pdf("<< /Type /Annot /Subtype /Widget /FT /Tx /Ff 33554432 /T (r) /Rect [0 0 200 40] /DA (/Helv 10 Tf 0 g) /V (Plain Bold) /RV 5 0 R >>", stream),
        )
        assertTrue(runs.first { it.text == "Bold" }.fontSpec.bold)
    }

    @Test
    fun a_multi_line_field_breaks_and_wraps_its_lines_and_a_single_line_field_does_not() {
        val value = "first line\\nsecond one is a long line of words that wraps"
        val multi = glyphs(pdf("<< /Type /Annot /Subtype /Widget /FT /Tx /Ff 4096 /T (m) /Rect [0 0 120 200] /DA (/Helv 10 Tf 0 g) /V ($value) >>"))
        val baselines = multi.map { it.textToDevice.f }.distinct()
        assertTrue(baselines.size >= 3, "a line break and a wrap make three lines or more: $baselines")
        assertEquals(baselines.sortedDescending(), baselines, "the lines run down from the top")
        assertTrue(multi.first().textToDevice.f > 150.0, "the first line is at the top of the field")
        assertEquals("first line", multi.filter { it.textToDevice.f == baselines[0] }.joinToString("") { it.text }, "a line break ends the first line")
        val single = glyphs(pdf("<< /Type /Annot /Subtype /Widget /FT /Tx /T (s) /Rect [0 0 120 20] /DA (/Helv 10 Tf 0 g) /V ($value) >>"))
        assertEquals(1, single.map { it.textToDevice.f }.distinct().size, "a single-line field keeps one line")
    }

    @Test
    fun a_value_a_reader_typed_draws_plain_in_the_default_appearance() {
        val widget = PdfDictionary(
            linkedMapOf(
                "FT" to PdfName("Tx"),
                "Ff" to PdfInt(1L shl 25),
                "DA" to PdfString("/Helv 10 Tf 0 g".encodeToByteArray()),
                "RV" to PdfString(rich.encodeToByteArray()),
            ),
        )
        val stream = FieldAppearance.synthesize(widget, 200.0, 40.0, IndirectResolver { null }, valueOverride = "Typed")!!
        val content = stream.rawBytes.decodeToString()
        assertTrue("(Typed) Tj" in content, content)
        assertFalse("Bold" in content, "the old rich value does not draw")
    }

    @Test
    fun a_media_annotation_draws_its_own_appearance_as_a_poster() {
        val poster = "<< /Type /XObject /Subtype /Form /BBox [0 0 100 60] /Length 26 >>\nstream\n1 0 0 rg 0 0 100 60 re f\nendstream"
        val doc = pdf("<< /Type /Annot /Subtype /Screen /Rect [50 50 150 110] /AP << /N 5 0 R >> >>", poster)
        val fills = RecordingCanvas().also { KitePDF.open(doc).pages[0].renderTo(it, KiteMatrix.IDENTITY) }.calls
            .filterIsInstance<RecordingCanvas.Call.Fill>()
        assertTrue(fills.any { it.color == RgbColor(1.0, 0.0, 0.0) }, "the poster draws")
    }

    @Test
    fun filling_a_multi_line_field_wraps_its_new_value() {
        val doc = KitePDF.open(pdf("<< /Type /Annot /Subtype /Widget /FT /Tx /Ff 4096 /T (m) /Rect [0 0 120 200] /DA (/Helv 10 Tf 0 g) /V () >>"))
        val out = doc.edit().apply { setTextFieldValue(doc.formField("m")!!, "one\ntwo") }.saveIncremental()
        val runs = glyphs(out)
        assertEquals(listOf("one", "two"), runs.map { it.text })
        assertTrue(runs[0].textToDevice.f > runs[1].textToDevice.f, "the second line is below the first")
    }

    @Test
    fun filling_a_rich_text_field_keeps_its_rich_value_in_step() {
        val doc = KitePDF.open(
            pdf("<< /Type /Annot /Subtype /Widget /FT /Tx /Ff 33554432 /T (r) /Rect [0 0 200 40] /DA (/Helv 10 Tf 0 g) /V (Plain Bold) /RV ($rich) >>"),
        )
        val out = doc.edit().apply { setTextFieldValue(doc.formField("r")!!, "New <text>") }.saveIncremental()
        val reopened = KitePDF.open(out)
        val field = reopened.formField("r")!!
        assertEquals("New <text>", field.value)
        assertTrue(field.isRichText)
        val rv = (field.fieldDict["RV"] as PdfString).asText()
        assertTrue("<p>New &lt;text&gt;</p>" in rv, rv)
        assertEquals(listOf("New <text>"), glyphs(out).map { it.text })
    }
}
