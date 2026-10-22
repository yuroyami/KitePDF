package io.github.yuroyami.kitepdf

import io.github.yuroyami.kitepdf.core.ByteArrayBuilder
import io.github.yuroyami.kitepdf.core.render.KiteMatrix
import io.github.yuroyami.kitepdf.core.render.RecordingCanvas
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Stable live-form appearances across asynchronous draws, ISO 32000-1, 12.7.3.3. */
class PdfFormStateSnapshotTest {
    @Test
    fun a_snapshot_keeps_the_accepted_text_choice_identity_and_field_flags() {
        val doc = document()
        val state = PdfFormState(doc)
        state.setValue("text", "accepted")
        state.setChoiceSelection("choice", PdfChoiceSelection(listOf(1)))
        state.setHidden("text", false)
        state.setReadOnly("choice", true)
        val snapshot = state.snapshot()
        val revision = state.revision
        val fieldRevision = state.fieldRevision("choice")

        state.setValue("text", "later")
        state.setChoiceSelection("choice", PdfChoiceSelection(listOf(0)))
        state.setHidden("text", true)
        state.setReadOnly("choice", false)

        assertEquals("accepted", snapshot.value("text"))
        assertEquals("same", snapshot.value("choice"))
        assertEquals(listOf(1), snapshot.choiceSelection("choice")!!.indices)
        assertFalse(snapshot.isHidden("text"))
        assertTrue(snapshot.isReadOnly("choice"))
        assertEquals(revision, snapshot.revision)
        assertEquals(fieldRevision, snapshot.fieldRevision("choice"))
        assertEquals(setOf("text", "choice"), snapshot.changedFields)
        assertTrue(snapshot.isChanged("choice"))

        val canvas = RecordingCanvas()
        doc.pages[0].renderTo(canvas, KiteMatrix.IDENTITY, snapshot)
        val drawnText = canvas.calls.filterIsInstance<RecordingCanvas.Call.Glyphs>()
            .joinToString("") { call -> call.glyphs.joinToString("") { it.text.orEmpty() } }
        assertTrue("accepted" in drawnText, drawnText)
        assertTrue("Second label" in drawnText, drawnText)
        assertFalse("First label" in drawnText, drawnText)
        assertFalse("same" in drawnText, "an export value must not replace the choice label")
    }

    @Test
    fun resetting_or_editing_either_state_does_not_change_the_other_or_share_listeners() {
        val state = PdfFormState(document())
        state.setValue("text", "accepted")
        state.setChoiceSelection("choice", PdfChoiceSelection(listOf(1)))
        state.setHidden("choice", true)
        state.setReadOnly("choice", true)
        var liveEvents = 0
        state.onChange { liveEvents++ }
        val snapshot = state.snapshot()
        assertEquals(0, liveEvents, "taking a snapshot is not an edit")

        snapshot.resetAll()
        assertEquals("source", snapshot.value("text"))
        assertEquals(listOf(0), snapshot.choiceSelection("choice")!!.indices)
        assertFalse(snapshot.isHidden("choice"))
        assertFalse(snapshot.isReadOnly("choice"))
        assertEquals("accepted", state.value("text"))
        assertEquals(listOf(1), state.choiceSelection("choice")!!.indices)
        assertTrue(state.isHidden("choice"))
        assertTrue(state.isReadOnly("choice"))
        assertEquals(0, liveEvents)

        snapshot.setValue("text", "detached")
        state.resetAll()
        assertEquals("detached", snapshot.value("text"))
        assertEquals("source", state.value("text"))
        assertTrue(liveEvents > 0, "the original still has its own listeners")
    }

    @Test
    fun source_fallbacks_and_reset_invalidations_survive_the_snapshot() {
        val doc = document()
        val state = PdfFormState(doc)
        state.reset("choice")
        val snapshot = state.snapshot()
        assertEquals(0, snapshot.revision)
        assertEquals(1, snapshot.fieldRevision("choice"))
        assertEquals(emptySet(), snapshot.changedFields)
        assertEquals("source", snapshot.value("text"))
        assertTrue(snapshot.isHidden("text"), "the source widget is hidden")
        assertTrue(snapshot.isReadOnly("text"), "the source field is read-only")
        assertFalse(snapshot.setChoiceSelection("choice", PdfChoiceSelection(listOf(1)), 0))

        snapshot.resetForm()
        assertEquals("default", snapshot.value("text"))
        assertEquals(listOf(2), snapshot.choiceSelection("choice")!!.indices)
        assertEquals("source", state.value("text"))
        assertEquals(listOf(0), state.choiceSelection("choice")!!.indices)
    }

    @Test
    fun free_text_and_unresolved_defaults_remain_complete_after_live_state_changes() {
        val editable = PdfFormState(document(choiceEntries = "/Ff 393216 /V (custom)"))
        editable.setChoiceSelection("choice", PdfChoiceSelection(emptyList(), freeText = "typed"))
        val freeText = editable.snapshot()
        editable.resetAll()
        assertEquals("typed", freeText.choiceSelection("choice")!!.freeText)
        assertEquals("typed", freeText.value("choice"))

        val damaged = PdfFormState(document(choiceEntries = "/Ff 2097152 /V [(same)] /DV [(missing) (gone)]"))
        damaged.resetForm()
        val unresolved = damaged.snapshot()
        damaged.setChoiceSelection("choice", PdfChoiceSelection(listOf(2)))
        assertEquals(listOf("missing", "gone"), unresolved.choiceSelection("choice")!!.unresolvedValues)
        assertEquals("missing", unresolved.value("choice"))
    }

    private fun document(choiceEntries: String = "/Ff 131072 /V (same) /I [0] /DV (other)"): PdfDocument {
        val out = ByteArrayBuilder()
        val offsets = LinkedHashMap<Int, Int>()
        fun obj(number: Int, body: String) {
            offsets[number] = out.size()
            out.append("$number 0 obj\n$body\nendobj\n".encodeToByteArray())
        }
        out.append("%PDF-1.7\n".encodeToByteArray())
        obj(1, "<< /Type /Catalog /Pages 2 0 R /AcroForm << /Fields [4 0 R 5 0 R] >> >>")
        obj(2, "<< /Type /Pages /Kids [3 0 R] /Count 1 >>")
        obj(3, "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 300 200] /Annots [4 0 R 5 0 R] >>")
        obj(4, "<< /Type /Annot /Subtype /Widget /FT /Tx /T (text) /V (source) /DV (default) /F 2 /Ff 1 /Rect [20 120 200 160] /DA (/Helv 12 Tf 0 g) >>")
        obj(5, "<< /Type /Annot /Subtype /Widget /FT /Ch /T (choice) /Opt [[(same) (First label)] [(same) (Second label)] [(other) (Other label)]] $choiceEntries /Rect [20 20 200 80] /DA (/Helv 12 Tf 0 g) >>")
        val xref = out.size()
        out.append("xref\n0 6\n0000000000 65535 f \n".encodeToByteArray())
        for (number in 1..5) {
            out.append("${offsets.getValue(number).toString().padStart(10, '0')} 00000 n \n".encodeToByteArray())
        }
        out.append("trailer\n<< /Size 6 /Root 1 0 R >>\nstartxref\n$xref\n%%EOF\n".encodeToByteArray())
        return PdfDocument.open(out.toByteArray())
    }
}
