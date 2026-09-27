package io.github.yuroyami.kitepdf.compose

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.runtime.withFrameMillis
import io.github.yuroyami.kitepdf.PdfAction
import io.github.yuroyami.kitepdf.PdfAnnotation
import io.github.yuroyami.kitepdf.PdfDocument
import io.github.yuroyami.kitepdf.PdfFormField
import io.github.yuroyami.kitepdf.PdfPage
import io.github.yuroyami.kitepdf.PdfScriptHandler
import io.github.yuroyami.kitepdf.core.KitePage
import io.github.yuroyami.kitepdf.core.render.KiteMatrix
import kotlin.coroutines.ContinuationInterceptor
import kotlin.coroutines.EmptyCoroutineContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The form layer: the widgets of a page, drawn over the page itself and redrawn on their own.
 *
 * A page is rasterized once and kept. A form changes far more often than that: a reader types,
 * and a script may write a field twenty times a second. Rasterizing the page again for each of
 * those would cost a page render per field write, so the page is drawn without its widgets and
 * this layer paints them on top, from the live values in [PdfScriptHandler.formState].
 *
 * Without a handler the layer draws nothing and the page keeps its widgets, so a viewer that runs
 * no scripts behaves exactly as before.
 */
internal fun Modifier.kiteFormLayer(
    page: KitePage,
    scripts: PdfScriptHandler?,
    textMeasurer: TextMeasurer,
    hairlineWidthPx: Float,
    /** Read by the caller so a change repaints this layer and nothing else. */
    @Suppress("UNUSED_PARAMETER") revision: Int,
    /** Remembered per page by the caller: a widget layer that failed once stays off (#333). */
    failure: DrawFailure = DrawFailure(),
    /**
     * The reader theme of the page under the widgets, so a field is not painted in the file's own
     * colours over a dark page (#419). The canvas decorator is page paint only and stays out.
     */
    theme: io.github.yuroyami.kitepdf.core.render.ReaderTheme? = null,
): Modifier {
    if (scripts == null || page !is PdfPage) return this
    return drawWithContent {
        drawContent()
        val width = page.displayWidth
        if (width <= 0.0 || size.width <= 0f) return@drawWithContent
        val scale = size.width / width
        if (!scale.isFinite() || scale <= 0.0) return@drawWithContent
        val deviceCtm = KiteMatrix.scaling(scale, scale).concat(page.displayToDeviceBase())
        val base = ComposeCanvas(this, textMeasurer, hairlineWidthPx)
        val canvas = theme?.wrap(base) ?: base
        failure.guard("form layer") {
            page.renderAnnotationsTo(canvas, deviceCtm, scripts.formState) {
                it.subtype == PdfAnnotation.Subtype.Widget
            }
        }
    }
}

/**
 * Runs the timers a script set, on a frame, for as long as one is waiting.
 *
 * Nothing in a document runs on its own: a script asks for a timer and the viewer decides when to
 * call it, which is what keeps a document from taking the thread. A frame is the right moment,
 * because what a timer does is almost always change what the page shows.
 *
 * With no timer waiting, the pump sleeps and asks for no frame, so a form that is only shown lets
 * the device rest (#368). It wakes when a script sets a timer: the handler reports it through
 * [PdfScriptHandler.onTimersChanged], and the viewer checks after each call it makes itself.
 * Between two timers it sleeps until the next one is due.
 */
@Composable
internal fun KiteScriptTimers(state: KiteDocViewState, scripts: PdfScriptHandler?, lane: CoroutineDispatcher) {
    if (scripts == null) return
    LaunchedEffect(state, scripts, lane) {
        val wake = Channel<Unit>(Channel.CONFLATED)
        state.timerWake = wake
        val stop = scriptCall("onTimersChanged", {}) { scripts.onTimersChanged { wake.trySend(Unit) } }
        try {
            while (true) {
                if (!scriptCall("hasTimers", false) { scripts.hasTimers }) {
                    wake.receive()
                    continue
                }
                val frameTime = withFrameMillis { it }
                val wait = withContext(lane) { scriptCall("pumpTimers", null) { scripts.pumpTimers(frameTime) } }
                // Sleep until the next timer is due, less the frame the next round waits for,
                // unless a script sets a sooner one in the meantime.
                if (wait != null && wait > FRAME_MILLIS) {
                    withTimeoutOrNull(wait - FRAME_MILLIS) { wake.receive() }
                }
            }
        } finally {
            stop()
            if (state.timerWake === wake) state.timerWake = null
        }
    }
}

/** About one frame at 60 frames a second. */
private const val FRAME_MILLIS = 16L

/**
 * Keeps [KiteDocViewState.formRevision] in step with the form, whoever changed it: a tap, a
 * script, a timer or the host. A change can come from any thread, and it is published on the
 * composition's thread, the one that reads it (#357, #364).
 */
@Composable
internal fun KiteFormRevision(state: KiteDocViewState, scripts: PdfScriptHandler?) {
    LaunchedEffect(state, scripts) {
        val form = scripts?.formState ?: return@LaunchedEffect
        val changed = Channel<Unit>(Channel.CONFLATED)
        val stop = form.onChange { changed.trySend(Unit) }
        try {
            state.formRevision = form.revision
            for (signal in changed) {
                // A change on the script thread resumes this loop there (#443).
                backOnComposeThread()
                state.formRevision = form.revision
            }
        } finally {
            stop()
        }
    }
}

/**
 * The field a tap landed on, and what a viewer does with it.
 *
 * A widget is a target like a link: a push button runs its press and release scripts, a check box
 * or a radio button changes the form's value and then runs its release script, and a text field
 * takes the caret (ISO 32000-1, 12.6.3, Table 194). A widget with its own action performs that
 * action on release instead of its release script (#361).
 *
 * The scripts run on the script thread in the order of the taps. An action that moves the viewer
 * comes back to [scope]'s thread, and one that leaves the document goes to [onLinkTap].
 */
internal fun handleWidgetTap(
    state: KiteDocViewState,
    scripts: PdfScriptHandler?,
    offset: Offset,
    scope: CoroutineScope,
    onLinkTap: ((KiteLinkAction) -> Boolean)? = null,
): Boolean {
    if (scripts == null) return false
    val lane = state.scriptLane ?: return false
    val hit = state.hitTest(offset) ?: return false
    val page = state.pageAt(hit.pageIndex) as? PdfPage ?: return false
    // The widget under the finger, by its reference and with the live visibility (#359, #360).
    val target = try {
        page.widgetAt(hit.x, hit.y, scripts.formState)
    } catch (failure: Throwable) {
        // A page whose fields cannot be read acts as a page without fields (#334).
        io.github.yuroyami.kitepdf.core.kiteWarn { "tap: the fields of a page cannot be read: ${failure.message}" }
        null
    } ?: return false
    val field = target.field
    val name = field.fullyQualifiedName
    if (scripts.formState.isReadOnly(name)) return true
    // The field that had the caret commits first, so this widget's scripts read the totals
    // that commit computes (#363).
    if (state.focusedField != name) state.blurFocusedField()
    val document = state.document as? PdfDocument
    // What the widget does on release, and what its /Next entry chains after it (#361).
    val actions = target.widget.action?.let { action -> document?.let { action.withNext(it) } ?: listOf(action) }.orEmpty()
    // The scripts of a widget may take a while, so they go to the script thread.
    val viewer = scope.coroutineContext[ContinuationInterceptor] ?: EmptyCoroutineContext
    // Each call is guarded, so a handler that fails is logged and the next call still runs (#365).
    scope.launch(lane) {
        scriptCall("mouseDown", Unit) { scripts.mouseDown(name, target.widgetIndex) }
        scriptCall("toggle", Unit) { toggleIfButton(scripts, field, target.widget) }
        // A widget's own action takes the place of its release script (ISO 32000-1, Table 194).
        if (actions.isEmpty()) scriptCall("mouseUp", Unit) { scripts.mouseUp(name, target.widgetIndex) }
        for (action in actions) {
            if (!scriptCall("runWidgetAction", true) { scripts.runWidgetAction(name, action) }) {
                withContext(viewer) {
                    // A viewer dispatcher that never dispatches leaves this on the script thread (#443).
                    backOnComposeThread()
                    performInViewer(state, document, onLinkTap, action)
                }
            }
        }
        state.scriptsRan()
    }
    // A text or choice field takes the caret, which is what opens the keyboard.
    if (field.type == PdfFormField.FieldType.Text || field.type == PdfFormField.FieldType.Choice) {
        val box = target.widget.rect?.let { KiteDocViewState.WidgetBox(hit.pageIndex, displayRectOf(page, it)) }
        state.focusField(name, target.widgetIndex, box)
    } else {
        state.blurFocusedField()
    }
    return true
}

/** [rect], in [page]'s own space, as a rectangle in its display space: y down, from the page's top-left. */
private fun displayRectOf(page: KitePage, rect: io.github.yuroyami.kitepdf.core.KiteRectangle): androidx.compose.ui.geometry.Rect {
    val toDisplay = page.displayToDeviceBase()
    val corners = listOf(
        toDisplay.transformPoint(rect.left, rect.bottom),
        toDisplay.transformPoint(rect.right, rect.top),
        toDisplay.transformPoint(rect.left, rect.top),
        toDisplay.transformPoint(rect.right, rect.bottom),
    )
    return androidx.compose.ui.geometry.Rect(
        corners.minOf { it.first }.toFloat(),
        corners.minOf { it.second }.toFloat(),
        corners.maxOf { it.first }.toFloat(),
        corners.maxOf { it.second }.toFloat(),
    )
}

/**
 * Performs a widget action that moves the viewer: a go-to in this document or a page-turn named
 * action. Any other action, such as a link, a submit or a print, goes to the host's [onLinkTap].
 */
private suspend fun performInViewer(
    state: KiteDocViewState,
    document: PdfDocument?,
    onLinkTap: ((KiteLinkAction) -> Boolean)?,
    action: PdfAction,
) {
    when (action) {
        is PdfAction.GoTo -> {
            val page = document?.resolveDestination(action.destination)?.pageIndex
            if (page != null) state.animateScrollToPage(page) else onLinkTap?.invoke(KiteLinkAction.Pdf(action))
        }
        is PdfAction.Named -> when (action.name) {
            PdfAction.NamedActionType.NextPage -> state.nextPage()
            PdfAction.NamedActionType.PrevPage -> state.previousPage()
            PdfAction.NamedActionType.FirstPage -> state.animateScrollToPage(0)
            PdfAction.NamedActionType.LastPage -> state.animateScrollToPage(state.itemCount - 1)
            else -> onLinkTap?.invoke(KiteLinkAction.Pdf(action))
        }
        else -> onLinkTap?.invoke(KiteLinkAction.Pdf(action))
    }
}

/**
 * A check box turns on and off. A radio button turns on, and a tap on the selected one turns it
 * off unless the group keeps one button selected at all times (NoToggleToOff, #440). The value
 * is the on state of the widget under the finger, so each button of a group selects itself
 * (ISO 32000-1, 12.7.4.2.4, #359).
 */
private fun toggleIfButton(scripts: PdfScriptHandler, field: PdfFormField, widget: PdfFormField.Widget) {
    if (field.type != PdfFormField.FieldType.Button) return
    if ((field.flags and PUSH_BUTTON) != 0) return
    val name = field.fullyQualifiedName
    val on = widget.onStateName ?: field.widgets.firstOrNull { it.onStateName != null }?.onStateName ?: "Yes"
    if (scripts.formState.value(name) != on) {
        scripts.commit(name, on)
    } else if ((field.flags and RADIO) == 0 || (field.flags and NO_TOGGLE_TO_OFF) == 0) {
        scripts.commit(name, "Off")
    }
}

/** `/Ff` bit 17: a push button, which has no state to toggle. */
private const val PUSH_BUTTON = 1 shl 16

/** `/Ff` bit 16: one of a radio group. */
private const val RADIO = 1 shl 15

/** `/Ff` bit 15: a radio group that keeps one button selected, so a tap on it changes nothing. */
private const val NO_TOGGLE_TO_OFF = 1 shl 14
