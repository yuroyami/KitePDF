package io.github.yuroyami.kitepdf

import io.github.yuroyami.kitepdf.core.render.KiteMatrix
import io.github.yuroyami.kitepdf.core.render.RecordingCanvas
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A form whose entries point at objects the file does not hold still reads as its fields. ISO
 * 32000-1, 7.3.10 makes such a reference the null object, so the broken entry reads as absent
 * and every other field stays (#441).
 */
class FormSalvageTest {

    /** A PDF whose objects are [bodies], numbered from 1. Object 1 is the catalog. No object is 80 or above. */
    private fun pdf(vararg bodies: String): ByteArray {
        val sb = StringBuilder("%PDF-1.7\n")
        val offsets = ArrayList<Int>()
        for ((index, body) in bodies.withIndex()) {
            offsets.add(sb.length)
            sb.append("${index + 1} 0 obj\n$body\nendobj\n")
        }
        val xref = sb.length
        sb.append("xref\n0 ${offsets.size + 1}\n0000000000 65535 f \n")
        for (offset in offsets) sb.append("${offset.toString().padStart(10, '0')} 00000 n \n")
        sb.append("trailer\n<< /Size ${offsets.size + 1} /Root 1 0 R >>\nstartxref\n$xref\n%%EOF\n")
        return sb.toString().encodeToByteArray()
    }

    /** One field per broken entry, each a text widget unless it says otherwise. */
    private val fields = listOf(
        // Drawn first: its appearance uses resources that are not there, so drawing it fails.
        "bad-resources" to "/FT /Tx /AP << /N ${FIELD_COUNT + 5} 0 R >>",
        "caption" to "/FT /Btn /Ff 65536 /MK 99 0 R",
        "text-mk" to "/FT /Tx /MK 84 0 R /V (shown)",
        "border" to "/FT /Tx /BS 98 0 R",
        "actions" to "/FT /Tx /AA 97 0 R",
        "one-action" to "/FT /Tx /AA << /U 96 0 R /Fo << /S /JavaScript /JS (focus) >> >>",
        "own-action" to "/FT /Btn /Ff 65536 /A 95 0 R",
        "appearance" to "/FT /Tx /AP << /N 94 0 R >> /V (drawn)",
        "options" to "/FT /Ch /Opt 93 0 R",
        "one-option" to "/FT /Ch /Opt [92 0 R (b)]",
        "value" to "/FT /Tx /V 91 0 R",
        "kids" to "/FT /Tx /Kids 90 0 R",
        "parent" to "/FT /Tx /Parent 89 0 R",
        "max-length" to "/FT /Tx /MaxLen 88 0 R",
        "plain" to "/FT /Tx /V (fine)",
    )

    private fun brokenPdf(): ByteArray {
        val first = 5
        val refs = fields.indices.joinToString(" ") { "${first + it} 0 R" }
        val bodies = arrayListOf(
            "<< /Type /Catalog /Pages 2 0 R /AcroForm << /Fields [$refs] >> /AA 87 0 R >>",
            // The page tree names a page that is not there, which the walk for stray widgets reads.
            "<< /Type /Pages /Kids [3 0 R 86 0 R] /Count 2 /MediaBox [0 0 200 200] >>",
            "<< /Type /Page /Parent 2 0 R /Resources << >> /Annots [$refs 4 0 R] >>",
            // A widget outside the field tree whose parent is missing.
            "<< /Type /Annot /Subtype /Widget /Parent 85 0 R /Rect [0 0 10 10] >>",
        )
        for ((index, entry) in fields.withIndex()) {
            val (name, extra) = entry
            val y = 10 * index
            bodies += "<< /Type /Annot /Subtype /Widget /T ($name) /Rect [20 $y 120 ${y + 8}] $extra >>"
        }
        val square = "0 0 5 5 re f"
        bodies += "<< /Type /XObject /Subtype /Form /BBox [0 0 10 10] /Resources 83 0 R /Length ${square.length} >>\nstream\n$square\nendstream"
        check(bodies.size == FIELD_COUNT + 5)
        return pdf(*bodies.toTypedArray())
    }

    @Test
    fun every_field_reads_when_one_entry_points_at_a_missing_object() {
        val doc = PdfDocument.open(brokenPdf())
        val names = doc.formFields.map { it.fullyQualifiedName }
        for ((name, _) in fields) assertNotNull(names.find { it == name }, "$name is missing from $names")
        assertNull(doc.formField("caption")?.widgets?.first()?.caption)
        val oneAction = assertNotNull(doc.formField("one-action")?.additionalActions)
        assertNull(oneAction.mouseUp, "the missing action reads as absent")
        assertEquals("focus", (oneAction.focus as? PdfAction.JavaScript)?.script, "the other action stays")
        assertNull(doc.formField("own-action")?.widgets?.first()?.action)
        assertEquals(listOf("b"), doc.formField("one-option")?.options)
        assertEquals("fine", doc.formField("plain")?.value)
        // The widget whose appearance is missing still takes a tap.
        val y = 10.0 * fields.indexOfFirst { it.first == "appearance" } + 4.0
        assertEquals("appearance", doc.pages[0].widgetAt(70.0, y)?.field?.fullyQualifiedName)
    }

    @Test
    fun the_document_actions_read_as_absent_when_they_point_at_a_missing_object() {
        val doc = PdfDocument.open(brokenPdf())
        assertNull(doc.additionalActions)
    }

    @Test
    fun the_page_draws_its_widgets_when_one_entry_points_at_a_missing_object() {
        val doc = PdfDocument.open(brokenPdf())
        val state = PdfFormState(doc)
        state.setValue("plain", "changed")
        for (form in listOf(null, state)) {
            val canvas = RecordingCanvas()
            doc.pages[0].renderTo(canvas, KiteMatrix.IDENTITY, form)
            val text = canvas.calls.filterIsInstance<RecordingCanvas.Call.Glyphs>().joinToString(" ") { it.text }
            assertTrue("shown" in text, "a widget with a missing /MK still draws its value: $text")
            assertTrue("drawn" in text, "a widget with a missing appearance draws from its field: $text")
            assertTrue((if (form == null) "fine" else "changed") in text, "the widgets after a broken one still draw: $text")
        }
    }

    private companion object {
        /** How many fields [fields] declares, which [brokenPdf] numbers from object 5. */
        const val FIELD_COUNT = 15
    }
}
