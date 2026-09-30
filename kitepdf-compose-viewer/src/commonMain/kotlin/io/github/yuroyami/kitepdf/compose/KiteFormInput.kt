package io.github.yuroyami.kitepdf.compose

import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.layout.layout
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.Constraints
import io.github.yuroyami.kitepdf.PdfChoiceSelection
import io.github.yuroyami.kitepdf.PdfAction
import io.github.yuroyami.kitepdf.PdfDocument
import io.github.yuroyami.kitepdf.PdfFormField
import io.github.yuroyami.kitepdf.PdfScriptHandler
import kotlin.math.roundToInt
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Typing into a form field.
 *
 * A PDF text field is not a text box of its own: it is a rectangle on a page, and what a reader
 * types has to pass through the document's own scripts before it lands. A keystroke script may
 * refuse a character, which is how a form keeps letters out of a number field, and Chrome's
 * engine asks it for every character (ISO 32000-1, 12.6.3, the keystroke trigger).
 *
 * The platform keyboard is opened by a text field with no colour, focused while a widget has the
 * caret. It lies over the widget and under the pages, so the platform keeps the widget in view
 * above the keyboard and the pages still take every tap. It takes keys the way the field asks
 * (see [fieldInputOptions]). Everything it produces goes through [PdfScriptHandler.keystroke]
 * first, and the value is committed when the field loses the caret or the reader presses done.
 *
 * Call it as the first child of the viewer's box, so the host's layout never sees it (#362).
 */
@Composable
internal fun KiteFormInput(state: KiteDocViewState, scripts: PdfScriptHandler?, lane: CoroutineDispatcher) {
    val fieldName = state.focusedField ?: return
    if (scripts == null) return
    val field = remember(state.document, fieldName) {
        (state.document as? PdfDocument)?.formFields?.firstOrNull { it.fullyQualifiedName == fieldName }
    }
    if (field?.type == PdfFormField.FieldType.Choice && !field.isEditableCombo) return
    if (field?.isEditableCombo == true && state.choiceInputSession?.ready != true) return
    // One input per field, so a new field starts with its own caret and its own focus history.
    // A screen reader names the input by the field's tooltip, else its own name (#427).
    val label = field?.tooltip?.takeIf { it.isNotBlank() } ?: field?.partialName?.takeIf { it.isNotBlank() }
        ?: LocalKiteViewerStrings.current.formField
    key(fieldName, state.formInputGeneration) { FieldInput(state, scripts, field, fieldName, lane, fieldInputOptions(field), label) }
}

/** How the input for a field takes keys: on one line or several, with which keyboard, and how many. */
internal class FieldInputOptions(val singleLine: Boolean, val keyboard: KeyboardOptions, val maxLength: Int?)

/**
 * The input options that [field] asks for (ISO 32000-1, 12.7.4.3, Table 228). A multi-line
 * field takes line breaks, a password field gets a password keyboard with no suggestions, a field
 * with a number format gets a number keyboard, and `/MaxLen` caps the length (#362).
 */
internal fun fieldInputOptions(field: PdfFormField?): FieldInputOptions {
    val flags = field?.flags ?: 0
    val text = field?.type == PdfFormField.FieldType.Text
    val multiline = field?.isMultiline == true
    val password = text && flags and PASSWORD_FLAG != 0
    val numeric = (field?.additionalActions?.format as? PdfAction.JavaScript)?.script
        ?.let { "AFNumber_Format" in it || "AFPercent_Format" in it } == true
    return FieldInputOptions(
        singleLine = !multiline,
        keyboard = KeyboardOptions(
            keyboardType = when {
                password -> KeyboardType.Password
                numeric -> KeyboardType.Decimal
                else -> KeyboardType.Text
            },
            imeAction = if (multiline) ImeAction.Default else ImeAction.Done,
            autoCorrectEnabled = !password && flags and DO_NOT_SPELL_CHECK_FLAG == 0,
        ),
        maxLength = field?.maxLength?.takeIf { text && it > 0 },
    )
}

/** Text field flags: bit 14 is Password, bit 23 DoNotSpellCheck. */
private const val PASSWORD_FLAG = 1 shl 13
private const val DO_NOT_SPELL_CHECK_FLAG = 1 shl 22

/**
 * Sizes and places the input over the widget with the caret, or in the corner when that is not
 * known. The node itself takes no room, so the viewer's box keeps its size.
 */
private fun Modifier.overFocusedWidget(state: KiteDocViewState): Modifier = layout { measurable, _ ->
    val area = state.focusedWidgetArea()
    val width = (area?.width?.roundToInt() ?: 1).coerceAtLeast(1)
    val height = (area?.height?.roundToInt() ?: 1).coerceAtLeast(1)
    val placeable = measurable.measure(Constraints.fixed(width, height))
    layout(0, 0) { placeable.place(area?.left?.roundToInt() ?: 0, area?.top?.roundToInt() ?: 0) }
}

@Composable
private fun FieldInput(
    state: KiteDocViewState,
    scripts: PdfScriptHandler,
    field: PdfFormField?,
    fieldName: String,
    lane: CoroutineDispatcher,
    options: FieldInputOptions,
    label: String,
) {
    val generation = remember { state.formInputGeneration }
    val session = remember { state.choiceInputSession }
    fun current(): Boolean = state.formInputGeneration == generation && state.focusedField == fieldName && session?.cancelled != true
    val requester = remember { FocusRequester() }
    val pipeline = remember {
        val selected = scripts.formState.choiceSelection(fieldName)
        val face = if (field?.isEditableCombo == true) selected?.indices?.singleOrNull()?.let { index ->
            field.choiceOptions.firstOrNull { it.index == index }?.label
        } ?: selected?.freeText ?: selected?.unresolvedValues?.firstOrNull() else null
        KeystrokePipeline(face ?: scripts.formState.value(fieldName) ?: "")
    }
    var value by remember { mutableStateOf(TextFieldValue(pipeline.screen, TextRange(pipeline.screen.length))) }
    var hadFocus by remember { mutableStateOf(false) }
    val typed = remember { Channel<Unit>(Channel.CONFLATED) }
    var inFlight by remember { mutableStateOf<InputKeystroke?>(null) }
    var flushing by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(Unit) { if (current()) requester.requestFocus() }

    DisposableEffect(state, session) {
        val capture: (KiteDocViewState.ChoiceSession) -> (() -> Unit)? = capture@{ target ->
            if (session !== target || !current() || flushing) return@capture null
            val pending = pipeline.pendingEdits()
            if (pending.isEmpty()) return@capture null
            val confirmed = pipeline.confirmed
            val flight = inFlight
            flushing = true
            val drain: () -> Unit = {
                val remaining = KeystrokePipeline(confirmed)
                pending.forEach(remaining::typed)
                if (flight != null && flight.completed && remaining.next() == flight.edit) remaining.answered(flight.kept)
                while (!target.cancelled) {
                    val edit = remaining.next() ?: break
                    remaining.answered(askScript(scripts, fieldName, edit, true, remaining.confirmed, target))
                }
                state.scriptsRan()
                scope.launch {
                    backOnComposeThread()
                    if (current()) {
                        pipeline.finish(remaining.confirmed)
                        value = value.movedTo(pipeline.screen, editOf(value.text, pipeline.screen))
                        state.editingText = pipeline.screen
                        inFlight = null
                        flushing = false
                        typed.trySend(Unit)
                    }
                }
            }
            drain
        }
        if (session != null) state.captureChoiceInput = capture
        onDispose { if (state.captureChoiceInput === capture) state.captureChoiceInput = null }
    }

    // One key at a time goes to the script, and its answer lands on this thread, the one that
    // composition reads the value on (#363, #364).
    LaunchedEffect(Unit) {
        for (signal in typed) {
            while (true) {
                if (!current()) return@LaunchedEffect
                if (flushing) break
                val edit = pipeline.next() ?: break
                val confirmed = pipeline.confirmed
                val flight = InputKeystroke(edit)
                inFlight = flight
                val kept = withContext(lane) {
                    askScript(scripts, fieldName, edit, field?.isEditableCombo == true, confirmed, session).also {
                        flight.kept = it
                        flight.completed = true
                        state.scriptsRan()
                    }
                }
                backOnComposeThread()
                if (!current()) return@LaunchedEffect
                if (flushing) break
                inFlight = null
                val before = pipeline.screen
                pipeline.answered(kept)
                val after = pipeline.screen
                if (after != before) {
                    value = value.movedTo(after, editOf(before, after))
                    state.editingText = after
                }
            }
        }
    }

    BasicTextField(
        value = value,
        onValueChange = { next ->
            if (!current() || flushing) return@BasicTextField
            // The character is shown at once and the script has the last word. Asking the script
            // first would mean waiting on the thread it runs on, which may be busy with a
            // document that works for minutes.
            val previous = value
            // A key that would take the value past the field's length is not taken. A shorter
            // value is, even one still too long, so the reader can always delete.
            val max = options.maxLength
            if (max != null && next.text.length > max && next.text.length > previous.text.length) return@BasicTextField
            value = next
            if (next.text == previous.text) return@BasicTextField
            pipeline.typed(editOf(previous.text, next.text))
            state.editingText = next.text
            typed.trySend(Unit)
        },
        singleLine = options.singleLine,
        keyboardOptions = options.keyboard,
        keyboardActions = KeyboardActions(onDone = { if (current()) state.blurFocusedField() }),
        visualTransformation = if (options.keyboard.keyboardType == KeyboardType.Password) PasswordVisualTransformation() else VisualTransformation.None,
        modifier = Modifier
            .overFocusedWidget(state)
            .semantics { contentDescription = label }
            .alpha(0f)
            .focusRequester(requester)
            .onPreviewKeyEvent { current() && state.choiceKeyHandler?.invoke(it) == true }
            .onFocusChanged { focus ->
                // The first event comes when the input attaches, before the request above lands,
                // and it says "not focused". Only a field that had the caret can lose it (#356).
                if (!current()) return@onFocusChanged
                if (focus.isFocused) {
                    hadFocus = true
                } else if (hadFocus && state.focusedField == fieldName) {
                    state.blurFocusedField()
                }
            },
    )
}

/**
 * Asks the field's keystroke script about [edit], measured against the value the form holds.
 * Returns the value the script kept, stored in the form, or null when it refused the key. A
 * handler that fails is logged and the key stands, since a broken script must not block typing.
 */
private class InputKeystroke(val edit: Edit) {
    var completed = false
    var kept: String? = null
}

private fun askScript(
    scripts: PdfScriptHandler,
    fieldName: String,
    edit: Edit,
    editableChoice: Boolean,
    confirmed: String,
    session: KiteDocViewState.ChoiceSession?,
): String? {
    val revision = scripts.formState.fieldRevision(fieldName)
    if (editableChoice && (session == null || session.cancelled || revision != session.expectedRevision)) return null
    val previous = if (editableChoice) scripts.formState.choiceSelection(fieldName) else null
    val kept = try {
        scripts.keystroke(fieldName, edit.change, edit.start, edit.end)
    } catch (failure: Exception) {
        io.github.yuroyami.kitepdf.core.kiteWarn { "form: the keystroke script of $fieldName failed: ${failure.message}" }
        edit.applyTo(confirmed)
    } ?: return null
    if (editableChoice) {
        val selection = PdfChoiceSelection(emptyList(), freeText = kept)
        if (session?.cancelled != false || !scripts.formState.setChoiceSelection(fieldName, selection, revision)) return null
        session.expectedRevision = revision + if (previous != selection) 1 else 0
        if (scripts.formState.fieldRevision(fieldName) != session.expectedRevision) return null
        session.draft = selection
        session.dirty = true
    } else scripts.formState.setValue(fieldName, kept)
    return kept
}

/** This value with its text replaced by [text], and its caret moved through [shift]. */
private fun TextFieldValue.movedTo(text: String, shift: Edit): TextFieldValue =
    TextFieldValue(text, TextRange(shift.map(selection.start).coerceIn(0, text.length), shift.map(selection.end).coerceIn(0, text.length)))

/**
 * The keystrokes of one field, in order, between the reader and the field's keystroke script.
 *
 * The screen shows each key at once, and the script answers later on its own thread. Only one
 * key is with the script at a time, measured against the value the script last kept, so no
 * answer applies to a value it was not asked about. When the script refuses a key or rewrites
 * the value, the keys typed after it move onto what the script kept: a refused letter in the
 * middle of a number no longer shifts or drops the digits typed after it (#363).
 */
internal class KeystrokePipeline(initial: String) {

    /** The value the script last kept, which the form holds. */
    var confirmed: String = initial
        private set

    /** Keys typed and not answered yet, oldest first, each measured against the text before it. */
    private val pending = ArrayDeque<Edit>()

    /** What the reader sees: the confirmed value with every pending key applied. */
    val screen: String get() = pending.fold(confirmed) { text, edit -> edit.applyTo(text) }

    /** A key the reader typed, measured against [screen]. */
    fun typed(edit: Edit) {
        pending.addLast(edit)
    }

    /** The next key to ask the script about, measured against [confirmed]. */
    fun next(): Edit? = pending.firstOrNull()

    /** Captured on the input thread when blur transfers the remaining work to the script lane. */
    fun pendingEdits(): List<Edit> = pending.toList()

    /** Publishes the result of that detached drain back to a still-open input. */
    fun finish(value: String) {
        confirmed = value
        pending.clear()
    }

    /** The script's answer to [next]: the value it kept, or null when it refused the key. */
    fun answered(kept: String?) {
        val edit = pending.removeFirstOrNull() ?: return
        val expected = edit.applyTo(confirmed)
        val actual = kept ?: confirmed
        confirmed = actual
        if (actual == expected) return
        // The later keys were measured on the value the screen assumed. Move them onto the one the script kept.
        val shift = editOf(expected, actual)
        for (i in pending.indices) pending[i] = pending[i].rebasedOver(shift)
    }
}

/** One edit as the keystroke trigger describes it: the text put in, and the range it replaces. */
internal class Edit(val change: String, val start: Int, val end: Int) {

    /** [text] with this edit made, its range clamped into the text. */
    fun applyTo(text: String): String {
        val from = start.coerceIn(0, text.length)
        val to = end.coerceIn(from, text.length)
        return text.substring(0, from) + change + text.substring(to)
    }

    /** Where position [p] of the text before this edit is in the text after it. */
    fun map(p: Int): Int = when {
        p <= start -> p
        p >= end -> p + change.length - (end - start)
        // Inside the replaced range: after what replaced it.
        else -> start + change.length
    }

    /** This edit, measured on the text before [shift], moved onto the text after it. */
    fun rebasedOver(shift: Edit): Edit {
        val from = shift.map(start)
        return Edit(change, from, maxOf(from, shift.map(end)))
    }
}

/** Reduces a before and after text to that one edit. */
internal fun editOf(before: String, after: String): Edit {
    val common = before.commonPrefixWith(after).length
    val tailLength = before.drop(common).commonSuffixWith(after.drop(common)).length
    return Edit(after.substring(common, after.length - tailLength), common, before.length - tailLength)
}
