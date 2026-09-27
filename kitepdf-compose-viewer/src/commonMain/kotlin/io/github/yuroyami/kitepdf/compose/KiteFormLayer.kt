package io.github.yuroyami.kitepdf.compose

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.runtime.withFrameMillis
import io.github.yuroyami.kitepdf.PdfAnnotation
import io.github.yuroyami.kitepdf.PdfFormField
import io.github.yuroyami.kitepdf.PdfPage
import io.github.yuroyami.kitepdf.PdfScriptHandler
import io.github.yuroyami.kitepdf.core.KitePage
import io.github.yuroyami.kitepdf.core.render.KiteMatrix
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch

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
): Modifier {
    if (scripts == null || page !is PdfPage) return this
    return drawWithContent {
        drawContent()
        val width = page.displayWidth
        if (width <= 0.0 || size.width <= 0f) return@drawWithContent
        val scale = size.width / width
        if (!scale.isFinite() || scale <= 0.0) return@drawWithContent
        val deviceCtm = KiteMatrix.scaling(scale, scale).concat(page.displayToDeviceBase())
        val canvas = ComposeCanvas(this, textMeasurer, hairlineWidthPx)
        failure.guard("form layer") {
            page.renderAnnotationsTo(canvas, deviceCtm, scripts.formState) {
                it.subtype == PdfAnnotation.Subtype.Widget
            }
        }
    }
}

/**
 * Runs the timers a script set, once a frame, for as long as one is waiting.
 *
 * Nothing in a document runs on its own: a script asks for a timer and the viewer decides when to
 * call it, which is what keeps a document from taking the thread. A frame is the right pace,
 * because what a timer does is almost always change what the page shows.
 */
@Composable
internal fun KiteScriptTimers(scripts: PdfScriptHandler?) {
    if (scripts == null) return
    LaunchedEffect(scripts) {
        var running = false
        while (true) {
            val frameTime = withFrameMillis { it }
            // One round at a time: a frame that takes longer than a frame must not queue another.
            if (!running && scripts.hasTimers) {
                running = true
                kotlinx.coroutines.withContext(kitepdfScriptDispatcher()) { scripts.pumpTimers(frameTime) }
                running = false
            }
        }
    }
}

/**
 * Runs [work] on the thread the document's scripts live on, so a long one never blocks drawing.
 *
 * A script engine belongs to one thread, so everything the viewer asks of a document goes through
 * here, in the order it was asked.
 */
internal fun CoroutineScope.postToScripts(work: () -> Unit) {
    launch(kitepdfScriptDispatcher()) { work() }
}

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
            for (signal in changed) state.formRevision = form.revision
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
 * takes the caret (ISO 32000-1, 12.6.3, Table 194).
 */
internal fun handleWidgetTap(
    state: KiteDocViewState,
    scripts: PdfScriptHandler?,
    offset: Offset,
    scope: CoroutineScope? = null,
): Boolean {
    if (scripts == null) return false
    val hit = state.hitTest(offset) ?: return false
    val page = state.pageAt(hit.pageIndex) as? PdfPage ?: return false
    val field = try {
        page.formFieldAt(hit.x, hit.y)
    } catch (failure: Throwable) {
        // A page whose fields cannot be read acts as a page without fields (#334).
        io.github.yuroyami.kitepdf.core.kiteWarn { "tap: the fields of a page cannot be read: ${failure.message}" }
        null
    } ?: return false
    val name = field.fullyQualifiedName
    if (scripts.formState.isReadOnly(name)) return true
    // The field that had the caret commits first, so this widget's scripts read the totals
    // that commit computes (#363).
    if (state.focusedField != name) state.blurFocusedField()
    // The scripts of a widget may take a while, so they go to the script thread. A test with no
    // scope runs them where it stands, which keeps its assertions in order.
    val press = {
        scripts.mouseDown(name)
        toggleIfButton(scripts, field)
        scripts.mouseUp(name)
    }
    if (scope != null) scope.postToScripts(press) else press()
    // A text or choice field takes the caret, which is what opens the keyboard.
    if (field.type == PdfFormField.FieldType.Text || field.type == PdfFormField.FieldType.Choice) {
        state.focusField(name)
    } else {
        state.blurFocusedField()
    }
    return true
}

/** A check box turns on and off; a radio button turns on and leaves its group. */
private fun toggleIfButton(scripts: PdfScriptHandler, field: PdfFormField) {
    if (field.type != PdfFormField.FieldType.Button) return
    if ((field.flags and PUSH_BUTTON) != 0) return
    val name = field.fullyQualifiedName
    val on = field.widgets.firstOrNull { it.onStateName != null }?.onStateName ?: "Yes"
    val current = scripts.formState.value(name)
    val isOn = !current.isNullOrEmpty() && current != "Off"
    val radio = (field.flags and RADIO) != 0
    scripts.commit(name, if (isOn && !radio) "Off" else on)
}

/** `/Ff` bit 17: a push button, which has no state to toggle. */
private const val PUSH_BUTTON = 1 shl 16

/** `/Ff` bit 16: one of a radio group, which cannot be turned off by pressing it again. */
private const val RADIO = 1 shl 15
