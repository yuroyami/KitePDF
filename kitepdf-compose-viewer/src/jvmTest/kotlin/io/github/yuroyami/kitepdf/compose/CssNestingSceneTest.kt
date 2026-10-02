package io.github.yuroyami.kitepdf.compose

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import io.github.yuroyami.kitepdf.core.KiteLocation
import io.github.yuroyami.kitepdf.epub.EpubDocument
import io.github.yuroyami.kitepdf.epub.EpubSettings
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull

/** Deep author CSS must not strand a chapter on its loading placeholder (#451). */
class CssNestingSceneTest {

    @Test
    fun deeply_nested_media_and_supports_leave_the_chapter_readable() {
        val css = "@media screen { @supports (display: block) {".repeat(1_500) +
            "p { display: none; }" + "}}".repeat(1_500)
        assertChapterRenders(css)
    }

    @Test
    fun deeply_nested_not_selectors_leave_the_chapter_readable() {
        val css = "p" + ":not(".repeat(3_000) + ".absent" + ")".repeat(3_000) +
            " { display: none; }"
        assertChapterRenders(css)
    }

    private fun assertChapterRenders(css: String) = forBothEffectOrders { queued ->
        withoutEscapes {
            val doc = EpubDocument.open(
                multiSpineEpub(
                    listOf(
                        "<style>$css p { color: #0000ff; }</style><p>Still here.</p>",
                        "<p>The next chapter.</p>",
                    ),
                ),
                EpubSettings(pageWidth = 200.0, pageHeight = 200.0),
            )
            assertFalse(doc.isChapterReady(0), "the viewer must load the chapter")
            lateinit var state: KiteDocViewState
            val (scene, driver) = drivenScene(200, 260, queued) {
                state = rememberKiteDocViewState(doc)
                KiteDocView(
                    state = state,
                    modifier = Modifier.fillMaxSize(),
                    renderSpec = KiteRenderSpec.Vectorized(),
                )
            }
            scene.use {
                driver.pumpUntilState {
                    doc.isChapterReady(0) && doc.isChapterReady(1) &&
                        state.items.none { it is DocItem.ChapterGap }
                }
                val text = assertNotNull(doc.page(KiteLocation(0, 0)).textContent())
                assertEquals("Still here.", text.plainText.trim(), "the damaged CSS must preserve the chapter text")
                // Only the valid rule after the deep construct can make these text pixels blue.
                driver.pumpUntil { pixels ->
                    (0 until 200 step 2).sumOf { y ->
                        (0 until 200 step 2).count { x ->
                            val color = pixels[x, y]
                            color.blue > 0.8f && color.red < 0.3f && color.green < 0.3f
                        }
                    } > 15
                }
            }
        }
    }
}
