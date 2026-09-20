package io.github.yuroyami.kitepdf.compose

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
        page.renderAnnotationsTo(canvas, deviceCtm, scripts.formState) {
            it.subtype == PdfAnnotation.Subtype.Widget
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
internal fun KiteScriptTimers(scripts: PdfScriptHandler?, onChanged: () -> Unit) {
    if (scripts == null) return
    LaunchedEffect(scripts) {
        while (true) {
            withFrameMillis { frameTimeMillis ->
                if (scripts.hasTimers) {
                    val before = scripts.formState.revision
                    scripts.pumpTimers(frameTimeMillis)
                    if (scripts.formState.revision != before) onChanged()
                }
            }
        }
    }
}

/** Keeps a number that changes whenever the form does, so a layer can repaint on it. */
@Composable
internal fun rememberFormRevision(scripts: PdfScriptHandler?): Int {
    var revision by remember(scripts) { mutableIntStateOf(scripts?.formState?.revision ?: 0) }
    LaunchedEffect(scripts) {
        val state = scripts?.formState ?: return@LaunchedEffect
        val stop = state.onChange { revision = state.revision }
        try {
            // The flow of changes ends with the composition that watches it.
            kotlinx.coroutines.awaitCancellation()
        } finally {
            stop()
        }
    }
    return revision
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
): Boolean {
    if (scripts == null) return false
    val hit = state.hitTest(offset) ?: return false
    val page = state.pageAt(hit.pageIndex) as? PdfPage ?: return false
    val field = page.formFieldAt(hit.x, hit.y) ?: return false
    val name = field.fullyQualifiedName
    if (scripts.formState.isReadOnly(name)) return true
    scripts.mouseDown(name)
    toggleIfButton(scripts, field)
    scripts.mouseUp(name)
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
