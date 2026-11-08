package io.github.yuroyami.kitepdf.compose

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import io.github.yuroyami.kitepdf.core.KiteBookmark
import io.github.yuroyami.kitepdf.core.KiteLocation
import io.github.yuroyami.kitepdf.core.KiteRectangle
import io.github.yuroyami.kitepdf.core.KiteTextRange
import io.github.yuroyami.kitepdf.epub.EpubDocument
import io.github.yuroyami.kitepdf.epub.EpubSettings
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * A highlight made from a selection keeps its sentence through a change of font size and of
 * viewport, a page break included, and through a save as text and a reopen (#944).
 */
class TextRangeHighlightSceneTest {

    private val sentence = "Down the rabbit hole she went, never once considering how in the world she was to get out again."

    private val bytes = multiSpineEpub(listOf(
        (0 until 14).joinToString("") { "<p>Opening paragraph $it of the first chapter, with words enough to wrap.</p>" } +
            "<p>$sentence</p>" + (0 until 14).joinToString("") { "<p>Closing paragraph $it.</p>" },
        "<p>The second chapter.</p>",
    ))

    private fun fold(s: String) = s.replace(Regex("\\s+"), " ").trim()

    /** The flattened char indices of [needle] on [location], line breaks read as spaces. */
    private fun charsOf(doc: EpubDocument, location: KiteLocation, needle: String): IntRange? {
        val text = doc.page(location).textContent()
        val flat = StringBuilder()
        val index = ArrayList<Int>()
        var n = 0
        for (block in text.blocks) for (l in block.lines) {
            if (flat.isNotEmpty() && flat.last() != ' ') { flat.append(' '); index.add(-1) }
            for (c in l.text) { flat.append(c); index.add(n++) }
        }
        val at = flat.indexOf(needle).takeIf { it >= 0 } ?: return null
        val span = index.subList(at, at + needle.length).filter { it >= 0 }
        return span.first()..span.last()
    }

    @Test
    fun a_highlighted_sentence_stays_on_its_words_through_a_reflow() = forBothEffectOrders { queued ->
        // 1. At 12 points the reader selects the sentence, and the app saves its range as text.
        var saved = ""
        run {
            val doc = EpubDocument.open(bytes, EpubSettings(pageWidth = 240.0, pageHeight = 300.0, fontSize = 12.0, margin = 16.0))
            doc.prepareChapter(0)
            val page = (0 until doc.pageCountIn(0)).first { charsOf(doc, KiteLocation(0, it), sentence) != null }
            val location = KiteLocation(0, page)
            lateinit var state: KiteDocViewState
            val (scene, driver) = drivenScene(240, 300, queued) {
                state = rememberKiteDocViewState(doc, KiteBookmark.Flow(0, doc.bookmarkOf(location).charOffset))
                KiteDocView(state, Modifier.fillMaxSize(), layout = KiteDocLayout.Paged())
            }
            scene.use {
                driver.pumpUntilState { state.currentLocation == location && state.slotOf(location)?.let { state.pageGeometry.containsKey(it) } == true }
                val chars = assertNotNull(charsOf(doc, location, sentence))
                val text = doc.page(location).textContent()
                fun pointAt(char: Int): Offset = assertNotNull(state.displayRectToViewport(assertNotNull(state.slotOf(location)), text.quadsFor(char, char).first())).center
                onTestUiThread { state.beginSelection(pointAt(chars.first)) }
                onTestUiThread { state.extendSelection(pointAt(chars.last)) }
                onTestUiThread { state.endSelectionGesture() }
                val range = assertNotNull(state.textRangeOf(assertNotNull(state.selection)))
                assertEquals(sentence, fold(range.text))
                saved = range.encode()
            }
        }

        // 2. At 18 points, and then in a narrower viewport, the restored range covers the sentence.
        var crossed = false
        for ((width, size) in listOf(240 to 18.0, 170 to 18.0)) {
            val doc = EpubDocument.open(bytes, EpubSettings(pageWidth = width.toDouble(), pageHeight = 300.0, fontSize = size, margin = 16.0))
            val range = assertNotNull(KiteTextRange.decode(saved))
            lateinit var state: KiteDocViewState
            val (scene, driver) = drivenScene(width, 300, queued) {
                state = rememberKiteDocViewState(doc, range.start)
                KiteDocView(state, Modifier.fillMaxSize(), layout = KiteDocLayout.Paged())
            }
            scene.use {
                onTestUiThread { state.highlights = listOf(KiteHighlight(range, color = Color.Magenta, id = "note")) }
                driver.pumpUntilState { state.highlightsByPage.isNotEmpty() && state.openAt == null }
                val marks = state.highlightsByPage.toSortedMap().values.flatten()
                assertEquals(sentence, fold(marks.joinToString(" ") { it.hit.text }), "at $size points, $width wide")
                assertTrue(marks.all { it.id == "note" && it.range == range })
                if (marks.map { it.hit.location }.distinct().size > 1) crossed = true
                // The first page of the highlight paints it, and a tap on it finds the note.
                val first = marks.first()
                val location = assertNotNull(first.hit.location)
                driver.runOnUi { state.scrollTo(location) }
                assertPainted(driver, state, location, first.hit.quads.first())
                val point = assertNotNull(state.displayRectToViewport(assertNotNull(state.slotOf(location)), first.hit.quads.first())).center
                assertEquals("note", state.highlightAt(point)?.id)
            }
        }
        assertTrue(crossed, "the narrow viewport puts a page break inside the sentence")
    }

    private fun assertPainted(driver: SceneTestDriver, state: KiteDocViewState, location: KiteLocation, quad: KiteRectangle) {
        driver.pumpUntil { pixels ->
            val slot = state.slotOf(location) ?: return@pumpUntil false
            val rect = state.displayRectToViewport(slot, quad) ?: return@pumpUntil false
            val x = rect.center.x.toInt()
            val y = rect.center.y.toInt()
            if (x !in 0 until pixels.width || y !in 0 until pixels.height) return@pumpUntil false
            val c = pixels[x, y]
            // Magenta over the text: much red and blue, little green.
            c.red > 0.5f && c.blue > 0.5f && c.green < 0.5f
        }
    }
}
