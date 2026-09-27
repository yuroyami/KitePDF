package io.github.yuroyami.kitepdf.compose

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.Density
import androidx.compose.ui.use
import io.github.yuroyami.kitepdf.core.KiteTextLine
import io.github.yuroyami.kitepdf.epub.EpubDocument
import io.github.yuroyami.kitepdf.epub.EpubSettings
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** The selection of a book holds its text as a reader copies it: a wrapped paragraph is one line (#438). */
class CopySelectionSceneTest {

    @Test
    fun a_selection_across_a_wrapped_paragraph_has_no_layout_line_breaks() {
        val words = List(10) { "Hyphenation makes representative typography comfortable" }.joinToString(" ")
        val doc = EpubDocument.open(
            multiSpineEpub(listOf("<p>$words</p>")),
            EpubSettings(pageWidth = 200.0, pageHeight = 200.0, margin = 10.0, hyphenate = true),
        )
        val lines = doc.pages[0].textContent().blocks[0].lines
        assertTrue(lines.size > 3, "the paragraph wraps on the first page")

        lateinit var state: KiteDocViewState
        ImageComposeScene(width = 200, height = 200, density = Density(1f)) {
            state = rememberKiteDocViewState(doc)
            KiteDocView(state = state, modifier = Modifier.fillMaxSize(), layout = KiteDocLayout.SinglePage(0))
        }.use { scene ->
            SceneTestDriver(scene).pumpUntilState { state.pageGeometry.isNotEmpty() }
            // Display space is viewport space here: a 200 pt page in a 200 px slot.
            fun at(line: KiteTextLine, edge: Int) = Offset(line.charEdges[edge].toFloat(), ((line.bounds.bottom + line.bounds.top) / 2).toFloat())
            state.beginSelection(at(lines.first(), 0) + Offset(1f, 0f))
            state.extendSelection(at(lines.last(), lines.last().text.length) + Offset(-1f, 0f))
            val text = assertNotNull(state.selection).text
            assertFalse('\n' in text, "no line break inside the paragraph: $text")
            assertTrue(text.length > 50 && words.startsWith(text), "the selection is the book's text: $text")
        }
    }
}
