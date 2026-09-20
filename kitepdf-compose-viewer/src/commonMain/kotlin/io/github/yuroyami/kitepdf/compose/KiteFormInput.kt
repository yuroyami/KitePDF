package io.github.yuroyami.kitepdf.compose

import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
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
internal fun KiteFormInput(state: KiteDocViewState, scripts: PdfScriptHandler?) {
    val fieldName = state.focusedField ?: return
    if (scripts == null) return
    val requester = remember(fieldName) { FocusRequester() }
    var value by remember(fieldName) {
        val text = scripts.formState.value(fieldName) ?: ""
        mutableStateOf(TextFieldValue(text, TextRange(text.length)))
    }

    LaunchedEffect(fieldName) { requester.requestFocus() }

    BasicTextField(
        value = value,
        onValueChange = { next ->
            // The character is shown at once and the script has the last word. Asking the script
            // first would mean waiting on the thread it runs on, which may be busy with a
            // document that works for minutes.
            val previous = value
            value = next
            val edit = editOf(previous.text, next.text)
            state.post {
                val accepted = scripts.keystroke(fieldName, edit.change, edit.start, edit.end)
                if (accepted == null) {
                    value = previous
                } else {
                    scripts.formState.setValue(fieldName, accepted)
                    if (accepted != next.text) value = TextFieldValue(accepted, TextRange(accepted.length))
                }
            }
        },
        singleLine = true,
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { state.blurFocusedField() }),
        modifier = Modifier
            .size(1.dp)
            .alpha(0f)
            .focusRequester(requester)
            .onFocusChanged { focus -> if (!focus.isFocused) state.blurFocusedField() },
    )
}

/** One edit as the keystroke trigger describes it: the text put in, and the range it replaces. */
internal class Edit(val change: String, val start: Int, val end: Int)

/** Reduces a before and after text to that one edit. */
internal fun editOf(before: String, after: String): Edit {
    val common = before.commonPrefixWith(after).length
    val tailLength = before.drop(common).commonSuffixWith(after.drop(common)).length
    return Edit(after.substring(common, after.length - tailLength), common, before.length - tailLength)
}
