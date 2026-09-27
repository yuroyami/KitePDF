package io.github.yuroyami.kitepdf.compose

import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import io.github.yuroyami.kitepdf.PdfScriptHandler
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.withContext

/**
 * Typing into a form field.
 *
 * A PDF text field is not a text box of its own: it is a rectangle on a page, and what a reader
 * types has to pass through the document's own scripts before it lands. A keystroke script may
 * refuse a character, which is how a form keeps letters out of a number field, and Chrome's
 * engine asks it for every character (ISO 32000-1, 12.6.3, the keystroke trigger).
 *
 * The platform keyboard is opened by a text field with no size and no colour, focused while a
 * widget has the caret. Everything it produces goes through [PdfScriptHandler.keystroke] first,
 * and the value is committed when the field loses the caret or the reader presses done.
 */
@Composable
internal fun KiteFormInput(state: KiteDocViewState, scripts: PdfScriptHandler?, lane: CoroutineDispatcher) {
    val fieldName = state.focusedField ?: return
    if (scripts == null) return
    // One input per field, so a new field starts with its own caret and its own focus history.
    key(fieldName) { FieldInput(state, scripts, fieldName, lane) }
}

@Composable
private fun FieldInput(state: KiteDocViewState, scripts: PdfScriptHandler, fieldName: String, lane: CoroutineDispatcher) {
    val requester = remember { FocusRequester() }
    val pipeline = remember { KeystrokePipeline(scripts.formState.value(fieldName) ?: "") }
    var value by remember { mutableStateOf(TextFieldValue(pipeline.screen, TextRange(pipeline.screen.length))) }
    var hadFocus by remember { mutableStateOf(false) }
    val typed = remember { Channel<Unit>(Channel.CONFLATED) }

    LaunchedEffect(Unit) { requester.requestFocus() }

    // One key at a time goes to the script, and its answer lands on this thread, the one that
    // composition reads the value on (#363, #364).
    LaunchedEffect(Unit) {
        for (signal in typed) {
            while (true) {
                val edit = pipeline.next() ?: break
                val kept = withContext(lane) { askScript(scripts, fieldName, edit) }
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
            // The character is shown at once and the script has the last word. Asking the script
            // first would mean waiting on the thread it runs on, which may be busy with a
            // document that works for minutes.
            val previous = value
            value = next
            if (next.text == previous.text) return@BasicTextField
            pipeline.typed(editOf(previous.text, next.text))
            state.editingText = next.text
            typed.trySend(Unit)
        },
        singleLine = true,
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { state.blurFocusedField() }),
        modifier = Modifier
            .size(1.dp)
            .alpha(0f)
            .focusRequester(requester)
            .onFocusChanged { focus ->
                // The first event comes when the input attaches, before the request above lands,
                // and it says "not focused". Only a field that had the caret can lose it (#356).
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
private fun askScript(scripts: PdfScriptHandler, fieldName: String, edit: Edit): String? = try {
    scripts.keystroke(fieldName, edit.change, edit.start, edit.end)?.also { scripts.formState.setValue(fieldName, it) }
} catch (failure: Exception) {
    io.github.yuroyami.kitepdf.core.kiteWarn { "form: the keystroke script of $fieldName failed: ${failure.message}" }
    edit.applyTo(scripts.formState.value(fieldName) ?: "").also { scripts.formState.setValue(fieldName, it) }
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
