package io.github.yuroyami.kitepdf.compose

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.isTraversalGroup
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.yuroyami.kitepdf.PdfChoiceSelection
import io.github.yuroyami.kitepdf.PdfDocument
import io.github.yuroyami.kitepdf.PdfFormField
import io.github.yuroyami.kitepdf.PdfPage
import io.github.yuroyami.kitepdf.PdfScriptHandler
import kotlin.math.roundToInt

/**
 * The messages of a PDF choice control (ISO 32000-1, 12.7.4.4). Provide translated words with
 * [LocalKiteChoiceStrings]; option labels themselves come from the document's `/Opt` entries.
 */
@Immutable
public class KiteChoiceStrings(
    public val noOptions: String = "No options available",
    public val noMatches: String = "No matching options",
    public val rejected: String = "Selection was not accepted",
    public val emptyOption: String = "Empty value",
)

/** The words of PDF choice controls below this composition. */
public val LocalKiteChoiceStrings: ProvidableCompositionLocal<KiteChoiceStrings> = staticCompositionLocalOf { KiteChoiceStrings() }

/**
 * The active PDF choice control. Lists stay in their widget; combos show an anchored picker.
 * The picker is a viewer child, so its geometry follows the page through zoom, crop and rotation.
 * Only editable combos leave the caret in [KiteFormInput]; the other controls never start an IME.
 */
@Composable
internal fun KiteChoiceInput(state: KiteDocViewState, scripts: PdfScriptHandler?) {
    val session = state.choiceInputSession ?: return
    val name = session.name
    val field = remember(state.document, name) {
        (state.document as? PdfDocument)?.formFields?.firstOrNull { it.fullyQualifiedName == name }
    }
    val revision = state.formRevision
    val usable = scripts != null && scripts === session.handler && field != null &&
        !scripts.formState.isHidden(name) && !scripts.formState.isReadOnly(name)
    val area = state.choiceWidgetArea()
    LaunchedEffect(session, scripts, revision, usable, area) {
        if (state.choiceInputSession === session && (!usable || area == null)) state.dismissChoice(commit = false)
    }
    if (!usable || area == null || field == null) return
    key(session) { ChoiceControl(state, field) }
}

@Composable
private fun ChoiceControl(state: KiteDocViewState, field: PdfFormField) {
    val strings = LocalKiteChoiceStrings.current
    val viewerStrings = LocalKiteViewerStrings.current
    val name = field.fullyQualifiedName
    val label = field.tooltip?.takeIf { it.isNotBlank() } ?: field.partialName?.takeIf { it.isNotBlank() } ?: viewerStrings.formField
    val query = if (field.isEditableCombo) state.editingText else null
    val options = remember(field, query) {
        field.choiceOptions.filter { query.isNullOrEmpty() || it.label.contains(query, ignoreCase = true) }
    }
    val selection = state.choiceDraft ?: PdfChoiceSelection(emptyList())
    var closeWhenAccepted by remember { mutableStateOf(false) }
    val requester = remember { FocusRequester() }
    val density = LocalDensity.current
    val widget = field.widgets.getOrNull(state.choiceWidgetIndex)
    val area = state.choiceWidgetArea()
    val rotation = (state.choiceWidgetBox?.slot?.let { state.pageAt(it) } as? PdfPage)?.rotationNormalized ?: 0
    val sideways = rotation == 90 || rotation == 270
    val displayHeight = (if (sideways) area?.width else area?.height) ?: 1f
    val safeScale = (displayHeight / (widget?.rect?.height?.toFloat() ?: 1f)).coerceAtLeast(0.01f)
    val rowHeight = if (field.isCombo) 44.dp else with(density) { (field.choiceRowHeight.toFloat() * safeScale).toDp() }
    val inset = if (field.isCombo) 0.dp else with(density) { (field.choiceContentPadding(state.choiceWidgetIndex).toFloat() * safeScale).toDp() }
    val rowPx = with(density) { rowHeight.roundToPx() }.coerceAtLeast(1)
    val visibleRows = (((widget?.rect?.height ?: 1.0) - field.choiceContentPadding(state.choiceWidgetIndex) * 2) / field.choiceRowHeight).toInt().coerceAtLeast(1)
    val topIndex = field.choiceTopIndexFor(selection, visibleRows)
    val initialTop = if (field.isCombo) 0 else options.indexOfFirst { it.index == topIndex }.coerceAtLeast(0)
    var candidate by remember { mutableIntStateOf(options.indexOfFirst { it.index in selection.indices }.takeIf { it >= 0 } ?: initialTop) }
    val scroll = rememberScrollState(initialTop * rowPx)
    LaunchedEffect(options) { candidate = candidate.coerceIn(0, (options.size - 1).coerceAtLeast(0)) }
    LaunchedEffect(candidate, scroll.viewportSize) {
        // The first composition has no measured scroll viewport. Scrolling then starts an
        // unnecessary animation, whose drag detector consumes the reader's next tap to stop it.
        if (scroll.viewportSize <= 0) return@LaunchedEffect
        val top = candidate * rowPx
        if (top < scroll.value) scroll.scrollTo(top)
        else if (top + rowPx > scroll.value + scroll.viewportSize) scroll.scrollTo((top + rowPx - scroll.viewportSize).coerceAtLeast(0))
    }
    LaunchedEffect(Unit) { if (!field.isEditableCombo) requester.requestFocus() }
    LaunchedEffect(state.choiceCommitPending, state.choiceRejected, closeWhenAccepted) {
        if (closeWhenAccepted && !state.choiceCommitPending) {
            closeWhenAccepted = false
            if (!state.choiceRejected) state.dismissChoice(commit = false)
        }
    }
    fun choose(position: Int) {
        val option = options.getOrNull(position) ?: return
        candidate = position
        val current = state.choiceDraft ?: PdfChoiceSelection(emptyList())
        val indices = if (field.isMultiSelect) {
            if (option.index in current.indices) current.indices - option.index else (current.indices + option.index).sorted()
        } else listOf(option.index)
        val commit = field.isCombo || field.commitOnSelectionChange
        state.chooseChoice(PdfChoiceSelection(indices), commit)
        if (field.isCombo) closeWhenAccepted = true
    }
    val onKey by rememberUpdatedState<(KeyEvent) -> Boolean> { event ->
        if (event.type != KeyEventType.KeyDown) false
        else when (event.key) {
            Key.Escape -> { state.dismissChoice(commit = false); true }
            Key.Tab -> { state.focusNextFormField(backwards = event.isShiftPressed); true }
            Key.Enter -> {
                if (!field.isCombo) state.dismissChoice()
                else if (!state.choiceCommitPending) {
                    if (options.isNotEmpty()) choose(candidate) else state.dismissChoice()
                }
                true
            }
            Key.Spacebar -> if (!field.isEditableCombo && !state.choiceCommitPending) { choose(candidate); true } else false
            Key.DirectionDown, Key.DirectionUp, Key.MoveHome, Key.MoveEnd -> {
                if (options.isNotEmpty()) {
                    candidate = when (event.key) {
                        Key.MoveHome -> 0
                        Key.MoveEnd -> options.lastIndex
                        Key.DirectionDown -> (candidate + 1).coerceAtMost(options.lastIndex)
                        else -> (candidate - 1).coerceAtLeast(0)
                    }
                    if (!field.isCombo && !field.isMultiSelect && !state.choiceCommitPending) choose(candidate)
                }
                true
            }
            else -> false
        }
    }
    DisposableEffect(state, name) {
        val handler: (KeyEvent) -> Boolean = { onKey(it) }
        state.choiceKeyHandler = handler
        onDispose { if (state.choiceKeyHandler === handler) state.choiceKeyHandler = null }
    }
    // The outside target closes the control without moving the page. Its child consumes option
    // taps, and an editable field keeps its platform caret throughout suggestion filtering.
    val outside = if (field.isCombo) Modifier.pointerInput(state, name) { detectTapGestures(onTap = { state.dismissChoice() }) } else Modifier
    Box(Modifier.fillMaxSize().then(outside)) {
        Column(
            Modifier
                .nearChoice(state, field.isCombo, rotation)
                .clipToBounds()
                .background(KiteSelectionMenuDefaults.ContainerColor)
                .border(1.dp, Color(0xFF75808F))
                .focusRequester(requester)
                .onPreviewKeyEvent { onKey(it) }
                .focusable(enabled = !field.isEditableCombo)
                .semantics { contentDescription = label; isTraversalGroup = true },
        ) {
            Column(Modifier.weight(1f, fill = !field.isCombo).padding(inset).verticalScroll(scroll)) {
                if (options.isEmpty()) {
                    BasicText(
                        if (field.choiceOptions.isEmpty()) strings.noOptions else strings.noMatches,
                        modifier = Modifier.padding(12.dp),
                        style = TextStyle(color = KiteSelectionMenuDefaults.ContentColor, fontSize = if (field.isCombo) 14.sp else with(density) { (field.choiceRowHeight.toFloat() / 1.2f * safeScale).toSp() }),
                    )
                }
                for ((position, option) in options.withIndex()) {
                    val selected = option.index in selection.indices
                    val active = position == candidate
                    BasicText(
                        option.label.ifEmpty { strings.emptyOption },
                        modifier = Modifier
                            .fillMaxWidth()
                            .then(if (field.isCombo) Modifier.heightIn(min = rowHeight) else Modifier.height(rowHeight))
                            .background(if (selected) Color(0xFFDCEBFA) else if (active) Color(0xFFF0F3F6) else Color.Transparent)
                            .then(if (active) Modifier.border(1.dp, Color(0xFF4A90D9)) else Modifier)
                            .focusProperties { canFocus = false }
                            .selectable(
                                selected = selected,
                                enabled = !state.choiceCommitPending,
                                role = if (field.isMultiSelect) Role.Checkbox else Role.RadioButton,
                                onClick = { choose(position) },
                            )
                            .padding(horizontal = 12.dp, vertical = if (field.isCombo) 12.dp else 0.dp),
                        style = TextStyle(color = KiteSelectionMenuDefaults.ContentColor, fontSize = if (field.isCombo) 14.sp else with(density) { (field.choiceRowHeight.toFloat() / 1.2f * safeScale).toSp() }),
                    )
                }
            }
            if (state.choiceRejected) {
                BasicText(strings.rejected, Modifier.padding(8.dp), style = TextStyle(color = Color(0xFF9F2424), fontSize = 12.sp))
            }
        }
    }
}

/** Clamps a combo above or below its widget, or places a list directly over its widget. */
private fun Modifier.nearChoice(state: KiteDocViewState, combo: Boolean, rotation: Int): Modifier = layout { measurable, constraints ->
    val area = state.choiceWidgetArea()
    val viewportWidth = constraints.maxWidth.coerceAtLeast(1)
    val viewportHeight = constraints.maxHeight.coerceAtLeast(1)
    if (!combo && area != null) {
        val sideways = rotation == 90 || rotation == 270
        val width = (if (sideways) area.height else area.width).roundToInt().coerceAtLeast(1)
        val height = (if (sideways) area.width else area.height).roundToInt().coerceAtLeast(1)
        val placeable = measurable.measure(Constraints.fixed(width, height))
        val x = (area.center.x - width / 2f).roundToInt()
        val y = (area.center.y - height / 2f).roundToInt()
        layout(viewportWidth, viewportHeight) {
            placeable.placeWithLayer(x, y) { rotationZ = rotation.toFloat() }
        }
    } else {
        val width = (area?.width?.roundToInt() ?: 1).coerceIn(1, viewportWidth)
        val above = area?.top?.roundToInt()?.coerceIn(0, viewportHeight) ?: 0
        val below = viewportHeight - (area?.bottom?.roundToInt()?.coerceIn(0, viewportHeight) ?: 0)
        val height = maxOf(above, below).coerceAtLeast(1)
        val placeable = measurable.measure(Constraints(minWidth = width, maxWidth = width, maxHeight = height))
        val x = (area?.left?.roundToInt() ?: 0).coerceIn(0, (viewportWidth - placeable.width).coerceAtLeast(0))
        val y = (if (below >= placeable.height) area?.bottom?.roundToInt() ?: 0 else above - placeable.height)
            .coerceIn(0, (viewportHeight - placeable.height).coerceAtLeast(0))
        layout(viewportWidth, viewportHeight) { placeable.place(x, y) }
    }
}
