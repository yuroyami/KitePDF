package io.github.yuroyami.kitepdf.compose

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Density
import androidx.compose.ui.use
import io.github.yuroyami.kitepdf.epub.EpubDocument
import io.github.yuroyami.kitepdf.epub.EpubSettings
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * A page on screen is rasterized once while the rest of the book lands behind
 * it. Every chapter landing recomposes the visible slots; if the document hands
 * them a new page object each time, the bitmap cache misses and the page fades
 * in again, which reads as flicker and doubles as a memory leak.
 */
class PageIdentitySceneTest {

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
    fun a_visible_page_is_rasterized_once_while_chapters_land() {
        val doc = book(chapters = 12)
        val renders = HashMap<Int, Int>()
        lateinit var state: KiteDocViewState
        ImageComposeScene(width = 200, height = 260, density = Density(1f)) {
            state = rememberKiteDocViewState(doc)
            KiteDocView(
                state = state,
                modifier = Modifier.fillMaxSize(),
                onPageRendered = { index, _ -> renders[index] = (renders[index] ?: 0) + 1 },
            )
        }.use { scene ->
            val driver = SceneTestDriver(scene)
            driver.pumpUntilState(maxFrames = 3000, timeoutMs = 60_000) { doc.isComplete }
            // A few more frames so the last landing's recomposition has happened.
            driver.pumpUntilState(maxFrames = 30, timeoutMs = 2_000) { false }
            assertTrue(doc.isComplete, "the book never finished laying out")
            assertTrue(0 in renders, "page 0 was never rasterized")
            assertSame(state.pageAt(0), state.pageAt(0), "the same slot must answer the same page object")
            for ((index, count) in renders) {
                assertEquals(1, count, "page $index was rasterized $count times; the chapter landings re-rastered it")
            }
        }
    }
}
