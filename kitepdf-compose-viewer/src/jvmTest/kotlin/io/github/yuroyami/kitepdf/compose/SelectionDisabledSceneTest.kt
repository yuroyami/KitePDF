package io.github.yuroyami.kitepdf.compose

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.Density
import androidx.compose.ui.use
import io.github.yuroyami.kitepdf.KitePDF
import io.github.yuroyami.kitepdf.core.KiteTextLine
import io.github.yuroyami.kitepdf.writer.PdfBuilder
import io.github.yuroyami.kitepdf.writer.StandardFont
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * `selectionEnabled = false`: a viewer that only navigates. Nothing can select
 * text, so no wash, no thumbs, and no menu to gate. Written for hosts that show
 * a document as a picture (a chart, a scan, a trace) where a selection is
 * meaningless and a stray long press just gets in the way.
 */
class SelectionDisabledSceneTest {

    private fun twoLineDoc() = KitePDF.open(
        PdfBuilder()
            .page(width = 200.0, height = 200.0) {
                text(StandardFont.Helvetica, 12.0, 20.0, 150.0, "hello world")
                text(StandardFont.Helvetica, 12.0, 20.0, 134.0, "second line")
            }
            .build(compress = false),
    )

    private fun firstLine(doc: io.github.yuroyami.kitepdf.PdfDocument): KiteTextLine =
        doc.pages[0].textContent().blocks.first().lines.first()

    private fun charPoint(line: KiteTextLine, index: Int) = Offset(
        (line.charEdges[index] + 1).toFloat(),
        ((line.bounds.bottom + line.bounds.top) / 2).toFloat(),
    )

    @Test
    fun a_long_press_on_text_selects_nothing_when_selection_is_disabled() {
        val doc = twoLineDoc()
        val line = firstLine(doc)
        lateinit var state: KiteDocViewState
        ImageComposeScene(width = 200, height = 200, density = Density(1f)) {
            state = rememberKiteDocViewState(doc)
            KiteDocView(
                state = state,
                modifier = Modifier.fillMaxSize(),
                layout = KiteDocLayout.SinglePage(0),
                selectionEnabled = false,
            )
        }.use { scene ->
            SceneTestDriver(scene).pumpUntil { state.pageGeometry.isNotEmpty() }

            state.beginSelection(charPoint(line, 0))
            assertNull(state.selection, "nothing anchors while selection is off")
            assertFalse(state.isSelectionActive, "and the page is never locked for it")
            state.extendSelection(charPoint(line, 6))
            assertNull(state.selection, "extending is inert too")
        }
    }

    @Test
    fun turning_selection_off_clears_what_was_already_selected() {
        val doc = twoLineDoc()
        val line = firstLine(doc)
        val enabled = mutableStateOf(true)
        lateinit var state: KiteDocViewState
        ImageComposeScene(width = 200, height = 200, density = Density(1f)) {
            state = rememberKiteDocViewState(doc)
            KiteDocView(
                state = state,
                modifier = Modifier.fillMaxSize(),
                layout = KiteDocLayout.SinglePage(0),
                selectionEnabled = enabled.value,
            )
        }.use { scene ->
            val driver = SceneTestDriver(scene)
            driver.pumpUntil { state.pageGeometry.isNotEmpty() }

            state.beginSelection(charPoint(line, 0))
            state.extendSelection(charPoint(line, line.text.length - 1))
            state.endSelectionGesture()
            assertNotNull(state.selection, "selection works while it is enabled")
            assertTrue(state.isSelectionActive)

            enabled.value = false
            driver.pumpUntil(maxFrames = 4) { false }

            assertNull(state.selection, "switching selection off drops the live selection")
            assertFalse(state.isSelectionActive, "and hands scrolling and panning back")
            assertNull(state.handleAt(Offset(100f, 100f), 24f), "no selection means no thumb to grab")
        }
    }

    @Test
    fun selection_stays_on_by_default() {
        val doc = twoLineDoc()
        val line = firstLine(doc)
        lateinit var state: KiteDocViewState
        ImageComposeScene(width = 200, height = 200, density = Density(1f)) {
            state = rememberKiteDocViewState(doc)
            KiteDocView(state = state, modifier = Modifier.fillMaxSize(), layout = KiteDocLayout.SinglePage(0))
        }.use { scene ->
            SceneTestDriver(scene).pumpUntil { state.pageGeometry.isNotEmpty() }

            state.beginSelection(charPoint(line, 0))
            assertNotNull(state.selection, "the default is unchanged for every existing caller")
        }
    }
}
