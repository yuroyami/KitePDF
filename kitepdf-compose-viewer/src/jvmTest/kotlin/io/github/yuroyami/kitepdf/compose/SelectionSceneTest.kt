package io.github.yuroyami.kitepdf.compose

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.unit.Density
import androidx.compose.ui.use
import io.github.yuroyami.kitepdf.KitePDF
import io.github.yuroyami.kitepdf.writer.PdfBuilder
import io.github.yuroyami.kitepdf.writer.StandardFont
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * T-80: long-press-drag selection through the real composed layout. The
 * gesture callbacks are driven directly (begin/extend), the same way the
 * link-tap acceptance drives its handler; geometry, hit-testing, the char
 * index math and the selection model all run for real.
 */
class SelectionSceneTest {

    private fun twoLineDoc() = KitePDF.open(
        PdfBuilder()
            .page(width = 200.0, height = 200.0) {
                text(StandardFont.Helvetica, 12.0, 20.0, 150.0, "hello world")
                text(StandardFont.Helvetica, 12.0, 20.0, 134.0, "second line")
            }
            .build(compress = false),
    )

    @Test
    fun drag_across_two_lines_selects_their_text_with_one_quad_per_line() {
        val doc = twoLineDoc()
        val kite = doc.pages[0].textContent()
        val lines = kite.blocks.flatMap { it.lines }
        assertEquals(listOf("hello world", "second line"), lines.map { it.text })

        lateinit var state: PdfViewState
        val changes = mutableListOf<TextSelection?>()
        ImageComposeScene(width = 200, height = 200, density = Density(1f)) {
            state = rememberPdfViewState(doc)
            state.onSelectionChange = { changes.add(it) }
            PdfView(state = state, modifier = Modifier.fillMaxSize(), layout = PdfLayout.SinglePage(0))
        }.use { scene ->
            val driver = SceneTestDriver(scene)
            driver.pumpUntil { state.pageGeometry.isNotEmpty() }

            // Display space == viewport space here (200pt page, 200px slot).
            fun mid(line: io.github.yuroyami.kitepdf.KiteTextLine, edge: Int) = Offset(
                line.charEdges[edge].toFloat(),
                ((line.bounds.bottom + line.bounds.top) / 2).toFloat(),
            )

            // Long-press on the first char of line 1, drag to the end of line 2.
            state.beginSelection(mid(lines[0], 0) + Offset(1f, 0f))
            assertNotNull(state.selection, "long-press anchors a selection")
            state.extendSelection(mid(lines[1], lines[1].text.length) + Offset(-1f, 0f))

            val sel = assertNotNull(state.selection)
            assertEquals(0, sel.pageIndex)
            assertEquals("hello world\nsecond line", sel.text, "text matches the extraction exactly")
            assertEquals(2, sel.quads.size, "one quad per line touched")
            for (q in sel.quads) {
                assertTrue(q.width > 0 && q.height > 0)
                assertTrue(q.left >= 0 && q.right <= 200.0 && q.bottom >= 0 && q.top <= 200.0)
            }
            assertTrue(changes.count { it != null } >= 2, "onSelectionChange fired for anchor and extension")

            // The overlay paints: a pixel inside the first quad turns blue-ish.
            val q = sel.quads[0]
            val px = ((q.left + q.right) / 2).toInt()
            val py = ((q.bottom + q.top) / 2).toInt()
            val img = driver.pumpUntil { map -> map[px, py].blue > map[px, py].red }
            val p = img.toComposeImageBitmap().toPixelMap()[px, py]
            assertTrue(p.blue > p.red, "selection overlay tints the page ($p)")

            // Clearing resets state and notifies.
            state.clearSelection()
            assertNull(state.selection)
            assertNull(changes.last())
        }
    }

    @Test
    fun selection_drag_is_a_noop_off_text_and_across_pages() {
        val doc = twoLineDoc()
        lateinit var state: PdfViewState
        ImageComposeScene(width = 200, height = 200, density = Density(1f)) {
            state = rememberPdfViewState(doc)
            PdfView(state = state, modifier = Modifier.fillMaxSize(), layout = PdfLayout.SinglePage(0))
        }.use { scene ->
            SceneTestDriver(scene).pumpUntil { state.pageGeometry.isNotEmpty() }
            // Long-press on an empty page region: no crash, no selection.
            state.beginSelection(Offset(100f, 190f))
            assertNull(state.selection)
            // Extending without an anchor is inert too.
            state.extendSelection(Offset(50f, 60f))
            assertNull(state.selection)
        }
    }
}
