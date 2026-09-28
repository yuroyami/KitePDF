package io.github.yuroyami.kitepdf.compose

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.isSpecified
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.isCtrlPressed
import androidx.compose.ui.input.pointer.isMetaPressed
import androidx.compose.ui.unit.LayoutDirection
import kotlin.math.pow
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.fastAny
import androidx.compose.ui.util.fastFirstOrNull
import androidx.compose.ui.util.fastForEach
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Text-selection gesture: long-press anchors a selection at the char
 * under the finger, dragging extends it, release keeps it. Runs through
 * [androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress],
 * which claims the drag once the long-press fires; pages without
 * `textContent()` make the whole gesture a no-op (the anchor never sets).
 *
 * Claiming the drag is not on its own enough to stop the page moving. The
 * pinch handler reads its pan on the *Initial* pass, before this detector has
 * seen anything at all, and `calculatePan` ignores consumption, so both pan
 * sites would still fire under a selection drag. [KiteDocViewState.beginSelection]
 * therefore raises [KiteDocViewState.isSelectionActive] the moment the long press
 * lands, and the pan sites below gate on it. [KiteDocViewState.endSelectionGesture]
 * releases it again when the drag anchored nothing.
 *
 * A second layer sits in front of that one: `kiteHandleDragGesture` reshapes a
 * selection that already exists by its two thumbs. It gets the press first and
 * only claims one that landed on a thumb, so the long-press path below is
 * untouched everywhere else.
 */
internal fun Modifier.kiteSelectionGestures(
    state: KiteDocViewState,
    haptics: HapticFeedback? = null,
): Modifier {
    if (!state.selectionEnabled) return this
    return kiteHandleDragGesture(state, haptics).kiteMouseSelection(state).pointerInput(state, haptics) {
        // The finger is on the words it is choosing, so the words are covered.
        // The ticks are the channel that is not: a long-press buzz when the
        // anchor lands, then one tick per change of the selected TEXT while
        // dragging. Text, not position: pixels change every frame of a drag,
        // and a tick per frame is a rattle, but the selection growing by a
        // character is exactly as often as the platform text fields tick.
        var lastSelectedText: String? = null
        detectDragGesturesAfterLongPress(
            onDragStart = onDragStart@{ pos ->
                // The detector also sees a press that a thumb claimed, and a finger held still
                // on the thumb reaches the timeout. That gesture belongs to the thumb (#313).
                if (state.handleDragInProgress) return@onDragStart
                state.beginSelection(pos)
                lastSelectedText = state.selection?.text
                if (state.isSelectionActive) {
                    haptics?.performHapticFeedback(HapticFeedbackType.LongPress)
                }
            },
            onDragEnd = { state.endSelectionGesture() },
            onDragCancel = { state.endSelectionGesture() },
            onDrag = onDrag@{ change, _ ->
                if (state.handleDragInProgress) return@onDrag
                state.extendSelection(change.position)
                val text = state.selection?.text
                if (text != null && text != lastSelectedText) {
                    lastSelectedText = text
                    haptics?.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                }
                change.consume()
            },
        )
    }
}

/**
 * A mouse press that drags on text selects at once, as text does on a desktop. A finger still
 * needs the long press, since its drag scrolls (#411). The drag must pass the touch slop first,
 * so a click still reaches the tap handlers. A drag that starts off the text is left to the page,
 * which pans or scrolls with it.
 */
private fun Modifier.kiteMouseSelection(state: KiteDocViewState): Modifier =
    pointerInput(state) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            if (down.type != PointerType.Mouse) return@awaitEachGesture
            var change = down
            while ((change.position - down.position).getDistance() <= viewConfiguration.touchSlop) {
                change = awaitPointerEvent().changes.fastFirstOrNull { it.id == down.id } ?: return@awaitEachGesture
                if (!change.pressed || change.isConsumed) return@awaitEachGesture
            }
            // A drag on a selection thumb belongs to the thumb.
            if (state.handleDragInProgress) return@awaitEachGesture
            state.beginSelection(down.position)
            if (state.selection == null) {
                state.endSelectionGesture()
                return@awaitEachGesture
            }
            state.extendSelection(change.position)
            change.consume()
            while (true) {
                val moved = awaitPointerEvent().changes.fastFirstOrNull { it.id == down.id } ?: break
                if (!moved.pressed) break
                state.extendSelection(moved.position)
                moved.consume()
            }
            state.endSelectionGesture()
        }
    }

/**
 * How far from a selection thumb a press still counts as grabbing it.
 *
 * Viewport pixels, so the target stays the same size at any zoom while the
 * marker itself scales with the text. It is also painter-independent: the hit
 * region is the boundary line, not whatever a
 * [KiteSelectionHandlePainter] drew around it.
 */
internal val HandleGrabRadius = 24.dp

/**
 * Reshaping a finished selection by its thumbs.
 *
 * Chained BEFORE the long-press detector and watching the *Initial* pass, so a
 * press that lands on a thumb is claimed before anything else can read it: no
 * long press starts a fresh selection, no tap clears one, and the page cannot
 * move under the drag. A press that misses both thumbs consumes nothing and
 * every other gesture behaves exactly as it did.
 *
 * The finger sits below or beside the boundary it is holding, so the offset
 * between the press and the thumb is captured on grab and reapplied to every
 * drag position. Without it the selection would jump to the fingertip the
 * moment the thumb was touched.
 */
private fun Modifier.kiteHandleDragGesture(
    state: KiteDocViewState,
    haptics: HapticFeedback?,
): Modifier =
    pointerInput(state, haptics) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            // Read per gesture, not once per block: the block's keys do not
            // include density, so a display change would freeze a stale radius.
            val edge = state.handleAt(down.position, HandleGrabRadius.toPx()) ?: return@awaitEachGesture
            val grabOffset = (state.handlePoint(edge) ?: down.position) - down.position
            state.beginHandleDrag(edge)
            haptics?.performHapticFeedback(HapticFeedbackType.TextHandleMove)
            var lastSelectedText = state.selection?.text
            down.consume()
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                val change = event.changes.fastFirstOrNull { it.id == down.id } ?: break
                change.consume()
                if (!change.pressed) break
                state.extendSelection(change.position + grabOffset)
                val text = state.selection?.text
                if (text != null && text != lastSelectedText) {
                    lastSelectedText = text
                    haptics?.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                }
            }
            state.endSelectionGesture()
        }
    }

/**
 * Zoom/pan gesture layer for [KiteDocView] content.
 *
 * Designed to coexist with the scroll container underneath instead of fighting
 * it:
 *
 *  - **Pinch** is watched on the *Initial* pointer pass; while two or more
 *    fingers are down (and pinch is enabled) all changes are consumed up
 *    front, so the list/pager beneath never interprets a pinch as a fling.
 *  - **Single-finger pan while zoomed** runs on the *Main* pass (*after*
 *    the inner scrollable) and only consumes what that scrollable left over,
 *    clamped to the zoomed content bounds via [KiteDocViewState.panBy]. In paged
 *    mode (pager scroll disabled while zoomed) it owns both axes; in
 *    continuous mode the scroll axis stays native and pan covers the cross
 *    axis.
 *  - **Text selection wins over pan.** While [KiteDocViewState.isSelectionActive]
 *    is set, neither pan site moves the page. Zoom itself is untouched, so a
 *    two-finger pinch still works mid-selection; it is only the translation
 *    that yields.
 *  - **Double-tap** toggles between min zoom and [KiteZoomSpec.doubleTapZoom],
 *    anchored at the tap position.
 *  - **Single-tap** ([onTap]) is reported without consuming pan/swipe. A host
 *    uses it to toggle a HUD. When double-tap is also on, the tap is held back
 *    until the double-tap window lapses; otherwise it fires immediately.
 *  - At minimum zoom with one finger down, nothing is consumed: swipes and
 *    flings reach the pager/list untouched.
 */
internal fun Modifier.kiteTransformGestures(
    state: KiteDocViewState,
    spec: KiteZoomSpec,
    scope: CoroutineScope,
    onTap: ((Offset) -> Unit)? = null,
): Modifier {
    if (!spec.pinchEnabled && !spec.doubleTapEnabled && !spec.panEnabled && onTap == null) return this
    return this
        .pointerInput(state, spec.doubleTapEnabled, spec.doubleTapZoom, spec.minZoom, onTap != null) {
            if (!spec.doubleTapEnabled && onTap == null) return@pointerInput
            detectTapGestures(
                onTap = onTap,
                onDoubleTap = if (spec.doubleTapEnabled) {
                    { tapPosition ->
                        val target = if (state.isZoomed) spec.minZoom else spec.doubleTapZoom
                        scope.launch { state.animateZoomTo(target, focal = tapPosition) }
                    }
                } else null,
            )
        }
        // Keyed on every flag the block reads, so a changed flag applies to the next gesture (#404).
        .pointerInput(state, spec.pinchEnabled, spec.panEnabled) {
            if (!spec.pinchEnabled) return@pointerInput
            awaitEachGesture {
                awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                var pinching = false
                while (true) {
                    val event = awaitPointerEvent(PointerEventPass.Initial)
                    val pointersDown = event.changes.count { it.pressed }
                    if (pointersDown >= 2) {
                        pinching = true
                        val zoomChange = event.calculateZoom()
                        val centroid = event.calculateCentroid()
                        if (zoomChange != 1f && centroid.isSpecified) {
                            state.setZoom(state.zoom * zoomChange, focal = centroid)
                        }
                        val pan = event.calculatePan()
                        // Zoom still applies above; only the translation yields
                        // to an active selection.
                        if (pan != Offset.Zero && spec.panEnabled && !state.isSelectionActive) {
                            state.panBy(pan)
                        }
                        event.changes.fastForEach { it.consume() }
                    } else if (pinching) {
                        // Fingers lifting off one by one: keep eating the tail of the
                        // gesture so the underlying scrollable doesn't see a sudden
                        // one-finger drag and jump.
                        event.changes.fastForEach { it.consume() }
                    }
                    if (!event.changes.fastAny { it.pressed }) break
                }
            }
        }
        // Ctrl or Cmd with the wheel zooms about the pointer, as a desktop viewer does. A browser
        // sends a trackpad pinch as such a wheel event too (#411).
        .pointerInput(state, spec.pinchEnabled) {
            if (!spec.pinchEnabled) return@pointerInput
            awaitPointerEventScope {
                while (true) {
                    val event = awaitPointerEvent(PointerEventPass.Initial)
                    if (event.type != PointerEventType.Scroll) continue
                    if (!event.keyboardModifiers.isCtrlPressed && !event.keyboardModifiers.isMetaPressed) continue
                    val change = event.changes.fastFirstOrNull { it.scrollDelta.y != 0f } ?: continue
                    state.setZoom(state.zoom * WHEEL_ZOOM_STEP.pow(-change.scrollDelta.y), focal = change.position)
                    event.changes.fastForEach { it.consume() }
                }
            }
        }
        .pointerInput(state, spec.panEnabled) {
            if (!spec.panEnabled) return@pointerInput
            awaitEachGesture {
                awaitFirstDown(requireUnconsumed = false)
                while (true) {
                    val event = awaitPointerEvent() // Main pass: after the inner scrollable
                    val pointersDown = event.changes.count { it.pressed }
                    if (pointersDown == 1 && state.overflows && !state.isSelectionActive) {
                        val pan = event.calculatePan()
                        if (pan != Offset.Zero) {
                            val consumed = state.panBy(pan)
                            if (consumed != Offset.Zero) {
                                event.changes.fastForEach { it.consume() }
                            }
                        }
                    }
                    if (!event.changes.fastAny { it.pressed }) break
                }
            }
        }
}

/**
 * Turns to the next or the previous page in reading order, or to the next or the previous
 * whole spread when [spread] is true. [KiteDocViewState.nextPage] moves one page, which inside a
 * spread can be the other page of the same spread.
 */
internal suspend fun KiteDocViewState.turn(forward: Boolean, spread: Boolean) {
    if (!spread) {
        if (forward) nextPage() else previousPage()
        return
    }
    val first = currentPage / 2 * 2
    val target = if (forward) first + 2 else first - 2
    if (target in 0 until itemCount) animateScrollToPage(target)
}

/** The zoom factor of one wheel notch with Ctrl or Cmd held. A trackpad sends parts of a notch. */
private const val WHEEL_ZOOM_STEP = 1.1f

/** The zoom factor of one Ctrl or Cmd with plus or minus. */
private const val KEY_ZOOM_STEP = 1.25f

/**
 * What [event] asks the view to do in [layout], or null for a key the view does not use (#411).
 * Page Down, Space and the arrow keys go forward in reading order, Page Up, Shift with Space and
 * the other arrows go back, and Home and End go to the ends. Where pages advance to the left, in
 * a horizontal layout that is right to left or reversed, the left arrow goes forward. Ctrl or Cmd
 * with plus, minus and 0 zooms in, out and back to fit. [paging] and [zooming] turn either set off.
 */
internal fun keyAction(
    event: KeyEvent,
    layout: KiteDocLayout,
    direction: LayoutDirection,
    paging: Boolean,
    zooming: Boolean,
): (suspend (KiteDocViewState) -> Unit)? {
    if (event.type != KeyEventType.KeyDown) return null
    if (event.isCtrlPressed || event.isMetaPressed) {
        if (!zooming) return null
        return when (event.key) {
            Key.Equals, Key.Plus, Key.NumPadAdd -> { state -> state.animateZoomTo(state.zoom * KEY_ZOOM_STEP) }
            Key.Minus, Key.NumPadSubtract -> { state -> state.animateZoomTo(state.zoom / KEY_ZOOM_STEP) }
            Key.Zero, Key.NumPad0 -> { state -> state.resetZoom() }
            else -> null
        }
    }
    if (!paging) return null
    val spread = layout is KiteDocLayout.Spread
    val next: suspend (KiteDocViewState) -> Unit = { it.turn(forward = true, spread) }
    val previous: suspend (KiteDocViewState) -> Unit = { it.turn(forward = false, spread) }
    val (horizontal, reversed) = when (layout) {
        is KiteDocLayout.Paged -> (layout.orientation == androidx.compose.foundation.gestures.Orientation.Horizontal) to layout.reverseLayout
        is KiteDocLayout.Spread -> (layout.orientation == androidx.compose.foundation.gestures.Orientation.Horizontal) to layout.reverseLayout
        is KiteDocLayout.Continuous -> (layout.orientation == androidx.compose.foundation.gestures.Orientation.Horizontal) to false
        else -> false to false
    }
    val forwardIsLeft = horizontal && ((direction == LayoutDirection.Rtl) != reversed)
    return when (event.key) {
        Key.PageDown, Key.DirectionDown -> next
        Key.PageUp, Key.DirectionUp -> previous
        Key.Spacebar -> if (event.isShiftPressed) previous else next
        Key.DirectionRight -> if (forwardIsLeft) previous else next
        Key.DirectionLeft -> if (forwardIsLeft) next else previous
        Key.MoveHome -> { state -> state.animateScrollToPage(0) }
        Key.MoveEnd -> { state -> state.animateScrollToPage((state.itemCount - 1).coerceAtLeast(0)) }
        else -> null
    }
}
