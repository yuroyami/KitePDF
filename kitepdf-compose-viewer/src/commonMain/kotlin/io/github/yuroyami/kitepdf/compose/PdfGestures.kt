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
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.util.fastAny
import androidx.compose.ui.util.fastForEach
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Zoom/pan gesture layer for [PdfView] content.
 *
 * Designed to coexist with the scroll container underneath instead of fighting
 * it:
 *
 *  - **Pinch** is watched on the *Initial* pointer pass; while two or more
 *    fingers are down (and pinch is enabled) all changes are consumed up
 *    front, so the list/pager beneath never interprets a pinch as a fling.
 *  - **Single-finger pan while zoomed** runs on the *Main* pass (*after*
 *    the inner scrollable) and only consumes what that scrollable left over,
 *    clamped to the zoomed content bounds via [PdfViewState.panBy]. In paged
 *    mode (pager scroll disabled while zoomed) it owns both axes; in
 *    continuous mode the scroll axis stays native and pan covers the cross
 *    axis.
 *  - **Text selection wins over pan.** While [PdfViewState.isSelectionActive]
 *    is set, neither pan site moves the page. Zoom itself is untouched, so a
 *    two-finger pinch still works mid-selection; it is only the translation
 *    that yields.
 *  - **Double-tap** toggles between min zoom and [PdfZoomSpec.doubleTapZoom],
 *    anchored at the tap position.
 *  - **Single-tap** ([onTap]) is reported without consuming pan/swipe. A host
 *    uses it to toggle a HUD. When double-tap is also on, the tap is held back
 *    until the double-tap window lapses; otherwise it fires immediately.
 *  - At minimum zoom with one finger down, nothing is consumed: swipes and
 *    flings reach the pager/list untouched.
 */
/**
 * Text-selection gesture (T-80): long-press anchors a selection at the char
 * under the finger, dragging extends it, release keeps it. Runs through
 * [androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress],
 * which claims the drag once the long-press fires; pages without
 * `textContent()` make the whole gesture a no-op (the anchor never sets).
 *
 * Claiming the drag is not on its own enough to stop the page moving. The
 * pinch handler reads its pan on the *Initial* pass, before this detector has
 * seen anything at all, and `calculatePan` ignores consumption, so both pan
 * sites would still fire under a selection drag. [PdfViewState.beginSelection]
 * therefore raises [PdfViewState.isSelectionActive] the moment the long press
 * lands, and the pan sites below gate on it. [PdfViewState.endSelectionGesture]
 * releases it again when the drag anchored nothing.
 */
internal fun Modifier.pdfSelectionGestures(
    state: PdfViewState,
    haptics: HapticFeedback? = null,
): Modifier =
    pointerInput(state, haptics) {
        // The finger is on the words it is choosing, so the words are covered.
        // The ticks are the channel that is not: a long-press buzz when the
        // anchor lands, then one tick per change of the selected TEXT while
        // dragging. Text, not position: pixels change every frame of a drag,
        // and a tick per frame is a rattle, but the selection growing by a
        // character is exactly as often as the platform text fields tick.
        var lastSelectedText: String? = null
        detectDragGesturesAfterLongPress(
            onDragStart = { pos ->
                state.beginSelection(pos)
                lastSelectedText = state.selection?.text
                if (state.isSelectionActive) {
                    haptics?.performHapticFeedback(HapticFeedbackType.LongPress)
                }
            },
            onDragEnd = { state.endSelectionGesture() },
            onDragCancel = { state.endSelectionGesture() },
            onDrag = { change, _ ->
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

internal fun Modifier.pdfTransformGestures(
    state: PdfViewState,
    spec: PdfZoomSpec,
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
        .pointerInput(state, spec.pinchEnabled) {
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
        .pointerInput(state, spec.panEnabled) {
            if (!spec.panEnabled) return@pointerInput
            awaitEachGesture {
                awaitFirstDown(requireUnconsumed = false)
                while (true) {
                    val event = awaitPointerEvent() // Main pass: after the inner scrollable
                    val pointersDown = event.changes.count { it.pressed }
                    if (pointersDown == 1 && state.isZoomed && !state.isSelectionActive) {
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
