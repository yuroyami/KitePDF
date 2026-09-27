package io.github.yuroyami.kitepdf.compose

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Density
import androidx.compose.ui.use
import io.github.yuroyami.kitepdf.core.KiteDocument
import io.github.yuroyami.kitepdf.epub.EpubDocument
import io.github.yuroyami.kitepdf.epub.EpubSettings
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The viewer tells the document which chapters are on screen, so a document with a layout budget
 * keeps them laid out. A long press or a link tap on a chapter that the budget dropped laid the
 * chapter out again on the UI thread (#377).
 */
class KeepChaptersSceneTest {

    /** A document that records what the viewer asks it to keep. */
    private class Recording(private val inner: KiteDocument) : KiteDocument by inner {
        val kept = mutableListOf<Set<Int>>()
        override fun keepChapters(chapters: Set<Int>) {
            kept += chapters
            inner.keepChapters(chapters)
        }
    }

    private fun book(chapters: Int): EpubDocument = EpubDocument.open(
        multiSpineEpub(
            List(chapters) { c ->
                "<h1>Chapter ${c + 1}</h1>" + (0 until 30).joinToString("") {
                    "<p>Chapter ${c + 1} paragraph $it with words enough to wrap around the page.</p>"
                }
            },
        ),
        EpubSettings(pageWidth = 200.0, pageHeight = 200.0),
    )

    @Test
    fun the_viewer_keeps_the_chapters_on_screen_and_lets_them_go_when_it_leaves() {
        val doc = Recording(book(3))
        val shown = mutableStateOf(true)
        lateinit var state: KiteDocViewState
        ImageComposeScene(width = 200, height = 200, density = Density(1f)) {
            state = rememberKiteDocViewState(doc)
            if (shown.value) KiteDocView(state = state, modifier = Modifier.fillMaxSize())
        }.use { scene ->
            val driver = SceneTestDriver(scene)
            driver.pumpUntilState { doc.kept.lastOrNull()?.contains(0) == true }
            assertTrue(doc.kept.last().all { it in 0 until 3 }, "kept ${doc.kept.last()}")
            shown.value = false
            driver.pumpFrames(2)
            assertEquals(emptySet(), doc.kept.last(), "a viewer that left still keeps chapters")
        }
    }
}
