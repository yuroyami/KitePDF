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
            val accepted = keystrokeOf(scripts, fieldName, value, next)
            if (accepted != null) {
                value = accepted
                scripts.formState.setValue(fieldName, accepted.text)
                state.formRevision = scripts.formState.revision
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

/**
 * What the field holds after this edit, or null when a script refused it.
 *
 * The edit is reduced to one insertion or deletion, because that is what the keystroke trigger
 * describes: the text being put in, and the range it replaces.
 */
private fun keystrokeOf(
    scripts: PdfScriptHandler,
    fieldName: String,
    before: TextFieldValue,
    after: TextFieldValue,
): TextFieldValue? {
    if (before.text == after.text) return after
    val common = before.text.commonPrefixWith(after.text).length
    val tailLength = before.text.drop(common).commonSuffixWith(after.text.drop(common)).length
    val start = common
    val end = before.text.length - tailLength
    val change = after.text.substring(start, after.text.length - tailLength)
    val accepted = scripts.keystroke(fieldName, change, start, end) ?: return null
    return if (accepted == after.text) after else TextFieldValue(accepted, TextRange(accepted.length))
}
