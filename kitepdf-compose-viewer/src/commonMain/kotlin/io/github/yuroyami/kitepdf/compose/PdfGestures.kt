package io.github.yuroyami.kitepdf.compose

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.isSpecified
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
 *  - **Single-finger pan while zoomed** runs on the *Main* pass — i.e. *after*
 *    the inner scrollable — and only consumes what that scrollable left over,
 *    clamped to the zoomed content bounds via [PdfViewState.panBy]. In paged
 *    mode (pager scroll disabled while zoomed) it owns both axes; in
 *    continuous mode the scroll axis stays native and pan covers the cross
 *    axis.
 *  - **Double-tap** toggles between min zoom and [PdfZoomSpec.doubleTapZoom],
 *    anchored at the tap position.
 *  - **Single-tap** ([onTap]) is reported without consuming pan/swipe — a host
 *    uses it to toggle a HUD. When double-tap is also on, the tap is held back
 *    until the double-tap window lapses; otherwise it fires immediately.
 *  - At minimum zoom with one finger down, nothing is consumed: swipes and
 *    flings reach the pager/list untouched.
 */
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
                        if (pan != Offset.Zero && spec.panEnabled) state.panBy(pan)
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
                    if (pointersDown == 1 && state.isZoomed) {
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
