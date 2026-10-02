package io.github.yuroyami.kitepdf.compose

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PixelMap
import androidx.compose.ui.unit.dp
import io.github.yuroyami.kitepdf.core.KiteBookmark
import io.github.yuroyami.kitepdf.epub.EpubDocument
import io.github.yuroyami.kitepdf.epub.EpubSettings
import kotlin.test.Test

/** A chapter placeholder in a pager has the shape of the page that replaces it (#353). */
class PlaceholderShapeSceneTest {

    private fun near(pixels: PixelMap, x: Int, y: Int, color: Color): Boolean {
        val c = pixels[x, y]
        return kotlin.math.abs(c.red - color.red) < 0.1f &&
            kotlin.math.abs(c.green - color.green) < 0.1f &&
            kotlin.math.abs(c.blue - color.blue) < 0.1f
    }

    @Test
    fun a_placeholder_is_letterboxed_like_a_page() {
        for (layout in listOf(KiteDocLayout.Paged(), KiteDocLayout.SinglePage(1))) {
            forBothEffectOrders { queued ->
                // Square pages, and chapter 1 held so its slot stays a placeholder.
                val book = EpubDocument.open(
                    multiSpineEpub(listOf("<p>One.</p>", "<p>Two.</p>")),
                    EpubSettings(pageWidth = 200.0, pageHeight = 200.0),
                )
                book.prepareChapter(0)
                val doc = LatchedDocument(book, held = 1)
                lateinit var state: KiteDocViewState
                val (scene, driver) = drivenScene(400, 200, queued) {
                    state = rememberKiteDocViewState(doc, KiteBookmark.Flow(chapter = 1, charOffset = 0))
                    KiteDocView(
                        state = state,
                        modifier = Modifier.fillMaxSize(),
                        layout = layout,
                        colors = KiteDocViewColors(pageBackground = Color.White, viewportBackground = Color.Blue),
                        chapterPlaceholder = { Box(Modifier.size(20.dp).background(Color.Red)) },
                    )
                }
                scene.use {
                    val name = layout::class.simpleName
                    try {
                        // The page is 200 x 200 in the middle of a 400 x 200 viewport: blue on both
                        // sides, white inside, and the placeholder's own content at the centre.
                        driver.pumpUntil { near(it, 200, 100, Color.Red) }
                        driver.pumpUntil { near(it, 10, 100, Color.Blue) && near(it, 390, 100, Color.Blue) && near(it, 120, 30, Color.White) }
                    } catch (failure: AssertionError) {
                        throw AssertionError("$name: the placeholder is not letterboxed", failure)
                    }
                }
            }
        }
    }
}
