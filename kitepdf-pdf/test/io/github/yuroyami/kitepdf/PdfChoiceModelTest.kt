package io.github.yuroyami.kitepdf

import io.github.yuroyami.kitepdf.core.ByteArrayBuilder
import io.github.yuroyami.kitepdf.core.render.KiteMatrix
import io.github.yuroyami.kitepdf.core.render.RecordingCanvas
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Choice identities and source salvage, ISO 32000-1, 12.7.4.4, Tables 230 and 231. */
class PdfChoiceModelTest {
    @Test
    fun options_keep_export_label_and_original_indices_through_malformed_entries() {
        val field = document("/Opt [(plain) [(x) (Same)] null [5 (bad)] [(y) (Same)] 8 0 R] /V (y)").formField("choice")!!
        assertEquals(listOf(0, 1, 4, 5), field.choiceOptions.map { it.index })
        assertEquals(listOf("plain", "x", "y", "remote"), field.choiceOptions.map { it.exportValue })
        assertEquals(listOf("plain", "Same", "Same", "Remote label"), field.options)
        assertEquals(PdfChoiceSelection(listOf(4)), field.choiceSelection)
        assertNull(field.validateChoiceSelection(PdfChoiceSelection(listOf(3))))
    }

    @Test
    fun consistent_indices_distinguish_duplicate_exports_and_inconsistent_indices_lose_to_values() {
        val options = "/Opt [[(same) (First)] [(same) (Second)] [(other) (Other)]] /Ff 2097152"
        val field = document("$options /V [(same)] /I [1]").formField("choice")!!
        assertEquals(listOf(1), field.choiceSelection.indices)
        assertEquals(listOf(0), field.choiceSelectionForValue("same")!!.indices)
        assertEquals(listOf(2), document("$options /V (other) /I [1]").formField("choice")!!.choiceSelection.indices)
        assertEquals(listOf(0), document("$options /V (same) /I [-1 99 (bad)]").formField("choice")!!.choiceSelection.indices)
    }

    @Test
    fun source_values_accept_the_spec_label_spelling_and_common_export_spelling() {
        val options = "/Opt [[(a) (Alpha)] [(b) (Beta)]] /Ff 2097152"
        assertEquals(listOf(1), document("$options /V (Beta)").formField("choice")!!.choiceSelection.indices)
        assertEquals(listOf(0, 1), document("$options /V [(Beta) (Alpha)] /I [0 1]").formField("choice")!!.choiceSelection.indices)
        assertEquals(listOf(0, 1), document("$options /V [(b) (a)] /I [0 1]").formField("choice")!!.choiceSelection.indices)
        assertEquals("missing", document("$options /V /missing").formField("choice")!!.value)
    }

    @Test
    fun inherited_options_flags_values_and_defaults_are_typed() {
        val doc = document("/Opt [[(a) (Alpha)] [(b) (Beta)]] /Ff 69206016 /V [(b) (a)] /I [0 1] /DV [(b)] /TI 1", inherited = true)
        val field = doc.formField("parent.choice")!!
        assertTrue(field.isMultiSelect)
        assertTrue(field.commitOnSelectionChange)
        assertFalse(field.isCombo)
        assertEquals(listOf(0, 1), field.choiceSelection.indices)
        assertEquals("a", field.value)
        assertEquals(listOf(1), field.defaultChoiceSelection.indices)
        assertEquals(1, field.topIndex)
    }

    @Test
    fun unknown_source_values_survive_open_and_reset_without_becoming_valid_new_values() {
        val doc = document("/Opt [(a)] /Ff 2097152 /V [(a) (gone)] /DV [(old)]")
        val field = doc.formField("choice")!!
        val state = PdfFormState(doc)
        assertEquals(listOf("gone"), state.choiceSelection("choice")!!.unresolvedValues)
        assertFalse(state.isChanged("choice"))
        assertFalse(state.setChoiceSelection("choice", field.choiceSelection))
        assertTrue(state.setChoiceSelection("choice", PdfChoiceSelection(listOf(0))))
        state.reset("choice")
        assertEquals(field.choiceSelection, state.choiceSelection("choice"))
        state.resetForm()
        assertEquals(listOf("old"), state.choiceSelection("choice")!!.unresolvedValues)
        assertEquals("old", state.value("choice"))
    }

    @Test
    fun cancelling_an_edit_can_restore_exact_unresolved_source_with_a_current_revision() {
        val doc = document("/Opt [(a)] /Ff 393216 /V [(missing) (alsoMissing)] /DV [(old) (obsolete)]")
        val field = doc.formField("choice")!!
        val state = PdfFormState(doc)
        val source = field.choiceSelection
        assertEquals(listOf("missing", "alsoMissing"), source.unresolvedValues)
        val before = state.fieldRevision("choice")
        assertTrue(state.setChoiceSelection("choice", PdfChoiceSelection(emptyList(), freeText = "typed")))
        assertFalse(state.setChoiceSelection("choice", source, before), "stale cancellation cannot replace a newer value")
        assertFalse(state.setChoiceSelection("choice", source), "ordinary writes still require a valid edit")
        val current = state.fieldRevision("choice")
        assertFalse(state.setChoiceSelection("choice", PdfChoiceSelection(emptyList(), unresolvedValues = listOf("invented")), current))
        assertTrue(state.setChoiceSelection("choice", source, current))
        assertEquals(source, state.choiceSelection("choice"))
        assertEquals("missing", state.value("choice"))
        assertTrue(state.setChoiceSelection("choice", field.defaultChoiceSelection, state.fieldRevision("choice")))
        assertEquals(field.defaultChoiceSelection, state.choiceSelection("choice"))
        assertNull(field.validateChoiceSelection(source), "user commit validation must stay strict")
    }

    @Test
    fun free_text_requires_editable_combo_and_empty_export_differs_from_empty_selection() {
        val entries = "/Opt [[() (Empty)] [(x) (X)]] /V (custom)"
        val editable = document("$entries /Ff 393216").formField("choice")!!
        assertTrue(editable.isEditableCombo)
        assertEquals("custom", editable.choiceSelection.freeText)
        assertEquals(PdfChoiceSelection(emptyList(), freeText = "new"), editable.choiceSelectionForValue("new"))
        assertEquals(listOf(0), editable.choiceSelectionForValue("")!!.indices)
        assertTrue(PdfChoiceSelection(emptyList()) != PdfChoiceSelection(listOf(0)))
        val list = document("$entries /Ff 262144").formField("choice")!!
        assertFalse(list.isEditableCombo)
        assertNull(list.choiceSelectionForValue("new"))
        assertNull(list.validateChoiceSelection(PdfChoiceSelection(emptyList(), freeText = "new")))
        assertNull(editable.validateChoiceSelection(PdfChoiceSelection(listOf(1), freeText = "new")))
    }

    @Test
    fun one_multiselection_is_one_revision_and_one_immutable_typed_event() {
        val state = PdfFormState(document("/Opt [(a) (b) (c)] /Ff 2097152 /V (a)"))
        val changes = ArrayList<PdfFormState.Change>()
        state.onChange { changes += it }
        val indices = mutableListOf(2, 1)
        val selection = PdfChoiceSelection(indices)
        indices.clear()
        assertTrue(state.setChoiceSelection("choice", selection))
        assertEquals(1, state.revision)
        assertEquals(1, state.fieldRevision("choice"))
        assertEquals(listOf(1, 2), state.choiceSelection("choice")!!.indices)
        assertEquals("b", state.value("choice"))
        assertEquals(1, changes.size)
        assertEquals(state.choiceSelection("choice"), changes.single().choiceSelection)
        assertFalse(selection.indices is MutableList<*>)
        assertTrue(state.setChoiceSelection("choice", PdfChoiceSelection(listOf(1, 2))))
        assertEquals(1, state.revision)
        assertFalse(state.setChoiceSelection("choice", PdfChoiceSelection(listOf(1, 1))))
        assertFalse(state.setChoiceSelection("choice", PdfChoiceSelection(listOf(9))))
        assertEquals(1, state.revision)
    }

    @Test
    fun scalar_adapter_replaces_multiple_values_and_rejects_unavailable_values() {
        val state = PdfFormState(document("/Opt [[(x) (First)] [(x) (Second)] [(z) (Last)]] /Ff 2097152 /V [(x) (z)] /I [1 2]"))
        state.setValue("choice", "First")
        assertEquals(listOf(1, 2), state.choiceSelection("choice")!!.indices)
        state.setValue("choice", "x")
        assertEquals(listOf(0), state.choiceSelection("choice")!!.indices)
        state.setValue("choice", "")
        assertEquals(emptyList(), state.choiceSelection("choice")!!.indices)
    }

    @Test
    fun stale_user_transaction_refuses_value_and_flag_changes_atomically() {
        val state = PdfFormState(document("/Opt [(a) (b)] /V (a)"))
        val before = state.fieldRevision("choice")
        state.setReadOnly("choice", true)
        assertFalse(state.setChoiceSelection("choice", PdfChoiceSelection(listOf(1)), before))
        assertFalse(state.setChoiceSelection("choice", PdfChoiceSelection(listOf(1)), state.fieldRevision("choice")))
        state.setReadOnly("choice", false)
        val visible = state.fieldRevision("choice")
        state.setHidden("choice", true)
        assertFalse(state.setChoiceSelection("choice", PdfChoiceSelection(listOf(1)), visible))
        state.setHidden("choice", false)
        assertTrue(state.setChoiceSelection("choice", PdfChoiceSelection(listOf(1)), state.fieldRevision("choice")))
        assertEquals("b", state.value("choice"))
        state.resetAll()
        assertFalse(state.setChoiceSelection("choice", PdfChoiceSelection(listOf(1)), visible))
        assertEquals("a", state.value("choice"))
    }

    @Test
    fun cancelling_a_pending_transaction_invalidates_only_its_revision_without_redrawing() {
        val state = PdfFormState(document("/Opt [(a) (b)] /V (a)"))
        state.setValue("other", "updated")
        val otherRevision = state.fieldRevision("other")
        val global = state.revision
        val pending = state.fieldRevision("choice")
        var events = 0
        state.onChange { events++ }
        assertTrue(state.cancelChoiceTransaction("choice", pending))
        assertEquals(pending + 1, state.fieldRevision("choice"))
        assertEquals(otherRevision, state.fieldRevision("other"))
        assertEquals(global, state.revision)
        assertEquals(0, events)
        assertFalse(state.setChoiceSelection("choice", PdfChoiceSelection(listOf(1)), pending))
        assertEquals("a", state.value("choice"))
        assertEquals("updated", state.value("other"))
        assertFalse(state.cancelChoiceTransaction("choice", pending), "stale cancellation cannot invalidate a newer edit")
        assertFalse(state.cancelChoiceTransaction("other", otherRevision), "the primitive is choice-specific")
        assertFalse(state.cancelChoiceTransaction("missing", 0))
        val current = state.fieldRevision("choice")
        assertTrue(state.setChoiceSelection("choice", PdfChoiceSelection(listOf(1)), current))
        assertFalse(state.cancelChoiceTransaction("choice", current), "an already committed value remains committed")
        assertEquals("b", state.value("choice"))
    }

    @Test
    fun reset_intent_invalidates_pending_edits_even_when_no_live_value_changes() {
        for (reset in listOf<(PdfFormState) -> Unit>(
            { it.reset("choice") }, { it.resetAll() }, { it.resetForm(listOf("choice")) },
        )) {
            val state = PdfFormState(document("/Opt [(a) (b)] /V (a) /DV (a)"))
            val before = state.fieldRevision("choice")
            var events = 0
            state.onChange { events++ }
            reset(state)
            assertFalse(state.setChoiceSelection("choice", PdfChoiceSelection(listOf(1)), before))
            assertEquals("a", state.value("choice"))
            assertEquals(0, state.revision, "a no-op reset needs no redraw")
            assertEquals(0, events)
        }
    }

    @Test
    fun repeating_default_reset_invalidates_pending_edits_without_republishing_the_value() {
        val state = PdfFormState(document("/Opt [(a) (b)] /V (a) /DV (b)"))
        state.resetForm()
        val before = state.fieldRevision("choice")
        val global = state.revision
        state.resetForm(listOf("unrelated"))
        assertEquals(before, state.fieldRevision("choice"))
        state.resetForm(listOf("choice"))
        assertFalse(state.setChoiceSelection("choice", PdfChoiceSelection(listOf(0)), before))
        assertEquals(global, state.revision)
        assertEquals("b", state.value("choice"))
    }

    @Test
    fun combo_draws_display_text_and_retains_background_border() {
        val doc = document("/Opt [[(code-a) (Alpha)] [(code-b) (Beta)]] /Ff 131072 /V (code-a)")
        val state = PdfFormState(doc)
        val source = draw(doc, state)
        assertEquals("Alpha", text(source))
        state.setChoiceSelection("choice", PdfChoiceSelection(listOf(1)))
        val changed = draw(doc, state)
        assertEquals("Beta", text(changed))
        assertTrue(changed.calls.filterIsInstance<RecordingCanvas.Call.Fill>().isNotEmpty())
        assertTrue(changed.calls.filterIsInstance<RecordingCanvas.Call.Stroke>().isNotEmpty())
    }

    @Test
    fun list_draws_rows_and_selected_backgrounds_from_typed_indices() {
        val doc = document("/Opt [[(x) (Alpha)] [(x) (Beta)] [(z) (Gamma)]] /Ff 2097152 /V [(x) (z)] /I [1 2]")
        val state = PdfFormState(doc)
        val source = draw(doc, state)
        assertEquals("AlphaBetaGamma", text(source))
        val fills = source.calls.filterIsInstance<RecordingCanvas.Call.Fill>().size
        state.setChoiceSelection("choice", PdfChoiceSelection(emptyList()))
        val cleared = draw(doc, state)
        assertEquals("AlphaBetaGamma", text(cleared))
        assertEquals(2, fills - cleared.calls.filterIsInstance<RecordingCanvas.Call.Fill>().size)
    }

    @Test
    fun list_viewport_uses_original_top_index_and_scrolls_a_new_selection_into_view() {
        val doc = document("/Opt [(a) null (b) (c) (d) (e)] /TI 3 /Ff 2097152 /V (c)")
        val field = doc.formField("choice")!!
        assertEquals(3, field.choiceTopIndexFor(field.choiceSelection, 2))
        assertEquals(5, field.choiceTopIndexFor(PdfChoiceSelection(listOf(5)), 2))
        assertEquals(2.0, field.choiceContentPadding())
        assertEquals(14.4, field.choiceRowHeight, 0.0001)
    }

    private fun draw(doc: PdfDocument, state: PdfFormState): RecordingCanvas = RecordingCanvas().also {
        doc.pages[0].renderTo(it, KiteMatrix.IDENTITY, state)
    }

    private fun text(canvas: RecordingCanvas): String = canvas.calls.filterIsInstance<RecordingCanvas.Call.Glyphs>()
        .joinToString("") { call -> call.glyphs.joinToString("") { it.text ?: "" } }

    private fun document(entries: String, inherited: Boolean = false): PdfDocument {
        val out = ByteArrayBuilder()
        val offsets = LinkedHashMap<Int, Int>()
        fun obj(number: Int, body: String) {
            offsets[number] = out.size()
            out.append("$number 0 obj\n$body\nendobj\n".encodeToByteArray())
        }
        out.append("%PDF-1.7\n".encodeToByteArray())
        obj(1, "<< /Type /Catalog /Pages 2 0 R /AcroForm << /Fields [${if (inherited) 5 else 4} 0 R 6 0 R] >> >>")
        obj(2, "<< /Type /Pages /Kids [3 0 R] /Count 1 >>")
        obj(3, "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 300 200] /Annots [4 0 R] >>")
        obj(4, "<< /Type /Annot /Subtype /Widget /T (choice) /Rect [20 20 200 80] /DA (/Helv 12 Tf 0 g) /MK << /BG [1 1 1] /BC [0 0 0] >> /BS << /W 1 >> ${if (inherited) "/Parent 5 0 R" else "/FT /Ch $entries"} >>")
        if (inherited) obj(5, "<< /T (parent) /FT /Ch $entries /Kids [4 0 R] >>")
        obj(6, "<< /FT /Tx /T (other) /V (original) /DV (original) >>")
        obj(8, "[(remote) (Remote label)]")
        val xref = out.size()
        out.append("xref\n0 9\n0000000000 65535 f \n".encodeToByteArray())
        for (number in 1..8) out.append((offsets[number]?.let { "${it.toString().padStart(10, '0')} 00000 n \n" } ?: "0000000000 65535 f \n").encodeToByteArray())
        out.append("trailer\n<< /Size 9 /Root 1 0 R >>\nstartxref\n$xref\n%%EOF\n".encodeToByteArray())
        return PdfDocument.open(out.toByteArray())
    }
}
