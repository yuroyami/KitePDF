package io.github.yuroyami.kitepdf.javascript

import io.github.yuroyami.kitepdf.PdfAction
import io.github.yuroyami.kitepdf.PdfChoiceSelection
import io.github.yuroyami.kitepdf.PdfDocument
import io.github.yuroyami.kitepdf.PdfFormState
import io.github.yuroyami.kitepdf.PdfScriptHandler
import io.github.yuroyami.kitepdf.core.parser.PdfDictionary
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Choice events and Field APIs follow the Adobe Acrobat JavaScript reference (#446). */
class ChoiceScriptTest {
    private fun fixture(
        flags: Int = 1 shl 17,
        options: String = "[[(S) (Small)] [(M) (Medium)] [(L) (Large)]]",
        value: String = "/V (S)",
        keystroke: String = "",
        validate: String = "",
        calculate: String = "",
        format: String = "",
    ): PdfDocument {
        fun script(source: String): String = "<< /S /JavaScript /JS <${source.encodeToByteArray().joinToString("") { (it.toInt() and 255).toString(16).padStart(2, '0') }}> >>"
        val actions = listOf("K" to keystroke, "V" to validate, "F" to format)
            .filter { it.second.isNotEmpty() }.joinToString(" ") { "/${it.first} ${script(it.second)}" }
        val calculation = if (calculate.isEmpty()) "" else "/AA << /C ${script(calculate)} >>"
        val objects = listOf(
            "<< /Type /Catalog /Pages 2 0 R /AcroForm << /Fields [4 0 R 5 0 R] /CO [5 0 R] >> >>",
            "<< /Type /Pages /Kids [3 0 R] /Count 1 >>",
            "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 200 200] /Annots [4 0 R 5 0 R] >>",
            "<< /Type /Annot /Subtype /Widget /FT /Ch /T (choice) /Ff $flags /Opt $options $value /Rect [10 100 150 160] /AA << $actions >> >>",
            "<< /Type /Annot /Subtype /Widget /FT /Tx /T (total) /V () /Rect [10 50 150 80] $calculation >>",
        )
        val pdf = StringBuilder("%PDF-1.7\n")
        val offsets = objects.mapIndexed { index, body -> pdf.length.also { pdf.append("${index + 1} 0 obj\n$body\nendobj\n") } }
        val xref = pdf.length
        pdf.append("xref\n0 ${objects.size + 1}\n0000000000 65535 f \n")
        offsets.forEach { pdf.append("${it.toString().padStart(10, '0')} 00000 n \n") }
        pdf.append("trailer\n<< /Size ${objects.size + 1} /Root 1 0 R >>\nstartxref\n$xref\n%%EOF\n")
        return PdfDocument.open(pdf.toString().encodeToByteArray())
    }

    private fun PdfScriptRunner.runScript(source: String) = run(PdfAction.JavaScript(source, PdfDictionary(emptyMap())))

    private fun PdfScriptRunner.choose(name: String, selection: PdfChoiceSelection): Boolean {
        val accepted = choiceKeystroke(name, selection) ?: return false
        return commitChoice(name, accepted)
    }

    @Test
    fun selection_change_commit_validate_calculate_format_use_the_right_values() {
        val trace = ArrayList<String>()
        val document = fixture(
            keystroke = "console.println('K:' + event.willCommit + ':' + event.value + ':' + event.change + ':' + event.changeEx);",
            validate = "console.println('V:' + event.value);",
            calculate = "console.println('C:' + getField('choice').value); event.value = getField('choice').value;",
            format = "console.println('F:' + event.value);",
        )
        PdfScriptRunner(document, onConsole = { trace += it }).use { runner ->
            assertTrue(runner.choose("choice", PdfChoiceSelection(listOf(1))))
            assertEquals(listOf("K:false:Small:Medium:M", "K:true:Medium::", "V:Medium", "C:M", "F:Medium"), trace)
            assertEquals("M", runner.formState.value("choice"))
            assertEquals("M", runner.formState.value("total"))
            assertEquals(1, runner.formState.fieldRevision("choice"))
            assertTrue(runner.failures.isEmpty())
        }
    }

    @Test
    fun a_single_list_selection_reports_old_export_then_new_face_then_validates_export() {
        val trace = ArrayList<String>()
        val document = fixture(
            flags = 0,
            keystroke = "console.println(event.willCommit + ':' + event.value + ':' + event.change + ':' + event.changeEx);",
            validate = "console.println('V:' + event.value);",
        )
        PdfScriptRunner(document, onConsole = { trace += it }).use { runner ->
            assertTrue(runner.choose("choice", PdfChoiceSelection(listOf(1))))
            assertEquals(listOf("false:S:Medium:M", "true:Medium::", "V:M"), trace)
        }
    }

    @Test
    fun deferred_choices_run_each_selection_event_without_storing_or_repeating_it_on_commit() {
        val trace = ArrayList<String>()
        val document = fixture(
            flags = 0,
            keystroke = "console.println(event.willCommit + ':' + event.value + ':' + event.change); if (!event.willCommit && event.change == 'Large') event.rc = false;",
            validate = "console.println('validate');",
        )
        PdfScriptRunner(document, onConsole = { trace += it }).use { runner ->
            val medium = runner.choiceKeystroke("choice", PdfChoiceSelection(listOf(1)))
            assertEquals(PdfChoiceSelection(listOf(1)), medium)
            assertEquals(null, runner.choiceKeystroke("choice", PdfChoiceSelection(listOf(2)), medium!!))
            assertEquals(listOf("false:S:Medium", "false:M:Large"), trace)
            assertEquals("S", runner.formState.value("choice"))
            assertEquals(0, runner.formState.fieldRevision("choice"))
            assertTrue(runner.commitChoice("choice", medium!!))
            assertEquals(listOf("false:S:Medium", "false:M:Large", "true:Medium:", "validate"), trace)
            assertEquals("M", runner.formState.value("choice"))
        }
    }

    @Test
    fun any_refused_stage_keeps_the_old_selection_without_calculating() {
        for (stage in listOf("selection", "commit", "validate")) {
            val trace = ArrayList<String>()
            val document = fixture(
                keystroke = when (stage) {
                    "selection" -> "if (!event.willCommit) event.rc = false;"
                    "commit" -> "if (event.willCommit) event.rc = false;"
                    else -> ""
                },
                validate = if (stage == "validate") "event.rc = false;" else "",
                calculate = "console.println('calculated');",
            )
            PdfScriptRunner(document, onConsole = { trace += it }).use { runner ->
                assertFalse(runner.choose("choice", PdfChoiceSelection(listOf(1))), stage)
                assertEquals(PdfChoiceSelection(listOf(0)), runner.formState.choiceSelection("choice"), stage)
                assertEquals(0, runner.formState.fieldRevision("choice"), stage)
                assertTrue(trace.isEmpty(), stage)
            }
        }
    }

    @Test
    fun rewrites_are_resolved_as_display_labels_and_invalid_ones_are_refused() {
        for (stage in listOf("selection", "commit", "validate")) {
            for (rewrite in listOf("Large", "not an option")) {
                val script = "event.${if (stage == "selection") "change" else "value"} = '$rewrite';"
                val document = fixture(
                    keystroke = if (stage == "validate") "" else "if (event.willCommit == ${stage == "commit"}) { $script }",
                    validate = if (stage == "validate") script else "",
                )
                PdfScriptRunner(document).use { runner ->
                    assertEquals(rewrite == "Large", runner.choose("choice", PdfChoiceSelection(listOf(1))), "$stage/$rewrite")
                    assertEquals(if (rewrite == "Large") "L" else "S", runner.formState.value("choice"))
                }
            }
        }
    }

    @Test
    fun multiple_selection_commits_once_and_calculations_observe_the_whole_array() {
        val trace = ArrayList<String>()
        val document = fixture(
            flags = 1 shl 21,
            keystroke = "if (event.willCommit) { console.println('K:' + event.value); event.value = 'bad'; }",
            validate = "console.println('V:' + event.value); event.value = 'bad';",
            calculate = "var f = getField('choice'); console.println(JSON.stringify(f.value)); event.value = f.value.length;",
            format = "console.println('F:' + event.value); event.value = 'bad';",
        )
        PdfScriptRunner(document, onConsole = { trace += it }).use { runner ->
            val changes = ArrayList<PdfFormState.Change>()
            val stop = runner.formState.onChange { if (it.fieldName == "choice") changes += it }
            assertTrue(runner.choose("choice", PdfChoiceSelection(listOf(2, 0))))
            assertEquals(PdfChoiceSelection(listOf(0, 2)), runner.formState.choiceSelection("choice"))
            assertEquals(listOf("K:", "V:", "[\"S\",\"L\"]", "F:"), trace)
            assertEquals("2", runner.formState.value("total"))
            assertEquals(1, changes.size)
            assertEquals(listOf(0, 2), changes.single().choiceSelection?.indices)
            assertEquals("", runner.formattedValue("choice"))
            stop()
        }
    }

    @Test
    fun field_value_and_indices_preserve_arrays_and_export_display_pairs() {
        val trace = ArrayList<String>()
        val document = fixture(flags = 1 shl 21)
        PdfScriptRunner(document, onConsole = { trace += it }).use { runner ->
            runner.runScript("var f = getField('choice'); f.value = ['L', 'S']; console.println(JSON.stringify(f.value)); console.println(JSON.stringify(f.currentValueIndices)); console.println(f.getItemAt(1)); console.println(f.getItemAt(1, false)); f.currentValueIndices = [2, 1];")
            assertEquals(listOf("[\"S\",\"L\"]", "[0,2]", "M", "Medium"), trace)
            assertEquals(PdfChoiceSelection(listOf(1, 2)), runner.formState.choiceSelection("choice"))
            runner.runScript("f.value = ['S', 'invalid']; f.currentValueIndices = [0, 88];")
            assertEquals(PdfChoiceSelection(listOf(1, 2)), runner.formState.choiceSelection("choice"))
            runner.runScript("f.currentValueIndices = -1;")
            assertEquals(PdfChoiceSelection(emptyList()), runner.formState.choiceSelection("choice"))
            assertTrue(runner.failures.isEmpty())
        }
    }

    @Test
    fun scalar_numeric_value_and_value_as_string_keep_their_existing_meanings() {
        val trace = ArrayList<String>()
        val document = fixture(options = "[[(020) (Twenty)] [(021) (Twenty one)]]", value = "/V (020)")
        PdfScriptRunner(document, onConsole = { trace += it }).use { runner ->
            runner.runScript("var f = getField('choice'); console.println(typeof f.value + ':' + f.value); console.println(f.valueAsString); console.println(f.currentValueIndices);")
            assertEquals(listOf("number:20", "020", "0"), trace)
            assertEquals("Twenty", runner.formattedValue("choice"))
        }
    }

    @Test
    fun corrupted_options_keep_original_indices_in_the_javascript_bridge() {
        val trace = ArrayList<String>()
        val document = fixture(options = "[[(S) (Small)] null [(L) (Large)]]")
        PdfScriptRunner(document, onConsole = { trace += it }).use { runner ->
            runner.runScript("var f = getField('choice'); f.currentValueIndices = 2; console.println(f.currentValueIndices); console.println(f.getItemAt(2, false)); console.println(f.getItemAt(1)); console.println(f.numItems);")
            assertEquals(listOf("2", "Large", "", "3"), trace)
            assertEquals(PdfChoiceSelection(listOf(2)), runner.formState.choiceSelection("choice"))
        }
    }

    @Test
    fun duplicate_exports_keep_the_typed_identity() {
        val document = fixture(options = "[[(S) (Small)] [(S) (Also small)] [(M) (Medium)]]")
        PdfScriptRunner(document).use { runner ->
            assertTrue(runner.choose("choice", PdfChoiceSelection(listOf(1))))
            assertEquals(PdfChoiceSelection(listOf(1)), runner.formState.choiceSelection("choice"))
            assertEquals("Also small", runner.formattedValue("choice"))
        }
    }

    @Test
    fun a_field_change_during_validation_invalidates_the_pending_choice() {
        for (mutation in listOf("readonly = true", "hidden = true", "value = 'L'", "readonly = true; getField('choice').readonly = false")) {
            val document = fixture(validate = "getField('choice').$mutation;")
            PdfScriptRunner(document).use { runner ->
                assertFalse(runner.choose("choice", PdfChoiceSelection(listOf(1))), mutation)
                assertEquals(if (mutation == "value = 'L'") "L" else "S", runner.formState.value("choice"))
            }
        }
    }

    @Test
    fun validation_can_change_another_field_without_invalidating_the_choice() {
        val document = fixture(validate = "getField('total').value = 'side effect';")
        PdfScriptRunner(document).use { runner ->
            assertTrue(runner.choose("choice", PdfChoiceSelection(listOf(1))))
            assertEquals("M", runner.formState.value("choice"))
            assertEquals("side effect", runner.formState.value("total"))
        }
    }

    @Test
    fun denied_scripts_still_enforce_options_flags_and_editable_combo_rules() {
        val document = fixture()
        PdfScriptRunner(document, policy = PdfScriptPolicy.DENY).use { runner ->
            assertFalse(runner.setFieldValue("choice", "invented"))
            assertFalse(runner.choose("choice", PdfChoiceSelection(listOf(0, 1))))
            assertTrue(runner.choose("choice", PdfChoiceSelection(listOf(1))))
            runner.formState.setReadOnly("choice", true)
            assertFalse(runner.choose("choice", PdfChoiceSelection(listOf(2))))
        }
        val editable = fixture(flags = (1 shl 17) or (1 shl 18))
        PdfScriptRunner(editable, policy = PdfScriptPolicy.DENY).use { runner ->
            assertTrue(runner.choose("choice", PdfChoiceSelection(emptyList(), freeText = "custom")))
            assertEquals("custom", runner.formState.value("choice"))
        }
    }

    @Test
    fun the_first_editable_combo_keystroke_edits_the_face_label() {
        val trace = ArrayList<String>()
        val document = fixture(
            flags = (1 shl 17) or (1 shl 18),
            keystroke = "console.println(event.value + ':' + event.change + ':' + typeof event.changeEx);",
        )
        PdfScriptRunner(document, onConsole = { trace += it }).use { runner ->
            val result = runner.keystroke("choice", "er")
            assertTrue(result.accepted)
            assertEquals("Smaller", result.value)
            assertEquals(listOf("Small:er:undefined"), trace)
            assertEquals("S", runner.formState.value("choice"), "a keystroke alone does not commit")
            assertTrue(runner.formState.setChoiceSelection("choice", PdfChoiceSelection(emptyList(), freeText = result.value)))
            assertTrue(runner.commitChoice("choice", PdfChoiceSelection(emptyList(), freeText = result.value)))
            assertEquals(listOf("Small:er:undefined", "Smaller::string"), trace, "blur runs only the commit keystroke")
            assertEquals(PdfChoiceSelection(emptyList(), freeText = "Smaller"), runner.formState.choiceSelection("choice"))
        }
    }

    @Test
    fun a_legacy_handler_runs_callbacks_and_refuses_unsupported_selection_shapes() {
        val document = fixture()
        val calls = ArrayList<String>()
        val handler = object : PdfScriptHandler {
            override val formState = PdfFormState(document)
            override fun keystroke(fieldName: String, change: String, selectionStart: Int, selectionEnd: Int): String {
                calls += "key:$change:$selectionStart:$selectionEnd"
                return change
            }
            override fun commit(fieldName: String, value: String): Boolean {
                calls += "commit:$value"
                formState.setValue(fieldName, value)
                return true
            }
        }
        assertFalse(handler.supportsMultipleChoices)
        assertTrue(handler.commitChoice("choice", handler.choiceKeystroke("choice", PdfChoiceSelection(listOf(1)))!!))
        assertEquals(listOf("key:Medium:0:1", "commit:M"), calls)
        assertEquals("M", handler.formState.value("choice"))
        val multiple = object : PdfScriptHandler {
            override val formState = PdfFormState(fixture(flags = 1 shl 21))
            override fun keystroke(fieldName: String, change: String, selectionStart: Int, selectionEnd: Int): String = error("must refuse before callbacks")
        }
        assertFalse(multiple.commitChoice("choice", PdfChoiceSelection(listOf(0, 1))))
        val duplicate = object : PdfScriptHandler {
            override val formState = PdfFormState(fixture(options = "[[(S) (Small)] [(S) (Duplicate)]]"))
            override fun keystroke(fieldName: String, change: String, selectionStart: Int, selectionEnd: Int): String = change
        }
        assertFalse(duplicate.commitChoice("choice", PdfChoiceSelection(listOf(1))))
    }

    @Test
    fun a_legacy_keystroke_adapter_replaces_the_whole_export_when_its_label_is_shorter() {
        val document = fixture(options = "[[(LONG-EXPORT) (A)] [(NEXT) (B)]]", value = "/V (LONG-EXPORT)")
        val handler = object : PdfScriptHandler {
            override val formState = PdfFormState(document)
            override fun keystroke(fieldName: String, change: String, selectionStart: Int, selectionEnd: Int): String {
                val current = formState.value(fieldName).orEmpty()
                return current.substring(0, selectionStart) + change + current.substring(selectionEnd)
            }
        }
        val selected = handler.choiceKeystroke("choice", PdfChoiceSelection(listOf(1)))
        assertEquals(PdfChoiceSelection(listOf(1)), selected)
        assertTrue(handler.commitChoice("choice", selected!!))
        assertEquals("NEXT", handler.formState.value("choice"))
    }
}
