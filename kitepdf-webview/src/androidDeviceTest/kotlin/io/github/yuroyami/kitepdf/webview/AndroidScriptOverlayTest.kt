package io.github.yuroyami.kitepdf.webview

import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.click
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import io.github.yuroyami.kitepdf.compose.KiteDocView
import io.github.yuroyami.kitepdf.compose.KiteDocViewState
import io.github.yuroyami.kitepdf.compose.KiteLinkAction
import io.github.yuroyami.kitepdf.compose.rememberKiteDocViewState
import io.github.yuroyami.kitepdf.core.KiteLocation
import io.github.yuroyami.kitepdf.core.KiteRectangle
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * `KiteScriptOverlay` in `KiteDocView` on an Android device: a scripted fixed-layout page runs
 * its script when the reader taps it, and a frame in a reflowable chapter shows its document
 * over its box while the chapter around it stays the library's own rendering (#41). These need
 * a device, since a web view draws nothing on the JVM; CI runs them on an emulator.
 */
@OptIn(ExperimentalTestApi::class)
class AndroidScriptOverlayTest {

    private fun assertNear(expected: Rect, actual: Rect, what: String) {
        val off = listOf(expected.left - actual.left, expected.top - actual.top, expected.right - actual.right, expected.bottom - actual.bottom)
        assertTrue(off.all { abs(it) <= 1f }, "$what: expected $expected, got $actual")
    }

    /** Whether [argb] is within a few steps of [rgb] in each channel. */
    private fun near(argb: Int, rgb: Int): Boolean =
        (0..2).all { shift -> abs((argb shr (shift * 8) and 0xFF) - (rgb shr (shift * 8) and 0xFF)) <= 24 }

    /** The colour of the screen at ([x], [y]), as `0xAARRGGBB`. */
    private fun ComposeUiTest.colourAt(x: Float, y: Float): Int = onRoot().captureToImage().toPixelMap()[x.toInt(), y.toInt()].toArgb()

    /** Waits for the web view over [href] to be placed, and gives its bounds on the screen. */
    private fun ComposeUiTest.islandOver(href: String): Rect {
        waitUntil(timeoutMillis = 30_000) { onAllNodesWithContentDescription(href).fetchSemanticsNodes().isNotEmpty() }
        return onNodeWithContentDescription(href).fetchSemanticsNode().boundsInRoot
    }

    @Test
    fun a_tap_on_a_scripted_fixed_layout_page_runs_its_script() = runComposeUiTest {
        val doc = WebBooks.scriptedPage()
        lateinit var state: KiteDocViewState
        setContent {
            state = rememberKiteDocViewState(doc)
            KiteDocView(state, Modifier.size(300.dp, 400.dp), pageOverlay = { KiteScriptOverlay() })
        }
        val island = islandOver("OEBPS/page.xhtml")
        val page = assertNotNull(runOnIdle { state.displayRectToViewport(0, KiteRectangle(0.0, 0.0, 225.0, 150.0)) })
        assertNear(page, island, "the web view covers the page")

        // The band is the top 60 of the page's 200 CSS pixels, red until the button runs its script.
        val bandX = island.left + island.width / 2
        val bandY = island.top + island.height * 0.15f
        waitUntil(timeoutMillis = 30_000) { near(colourAt(bandX, bandY), 0xFF0000) }
        onNodeWithContentDescription("OEBPS/page.xhtml").performTouchInput {
            click(Offset(island.width * 70f / 300f, island.height * 120f / 200f))
        }
        waitUntil(timeoutMillis = 30_000) { near(colourAt(bandX, bandY), 0x0000FF) }
    }

    @Test
    fun a_frame_shows_its_document_over_its_box_and_the_chapter_around_it_stays() = runComposeUiTest {
        val doc = WebBooks.quizChapter()
        val embed = doc.page(KiteLocation(0, 0)).embeds.single()
        val offered = mutableListOf<KiteLinkAction>()
        lateinit var state: KiteDocViewState
        setContent {
            state = rememberKiteDocViewState(doc)
            KiteDocView(
                state, Modifier.size(400.dp, 600.dp),
                pageOverlay = { KiteScriptOverlay() },
                onLinkTap = { offered += it; true },
            )
        }
        val island = islandOver("OEBPS/quiz.xhtml")
        val box = assertNotNull(runOnIdle { state.displayRectToViewport(0, embed.rect) })
        assertNear(box, island, "the web view covers the frame's box")
        // The quiz is green, where the library itself paints nothing for a frame.
        waitUntil(timeoutMillis = 30_000) { near(colourAt(island.right - 10f, island.bottom - 10f), 0x00A000) }

        // The chapter's own paragraphs, the library's rendering, above and below the box.
        val image = onRoot().captureToImage().toPixelMap()
        val reach = island.height * 0.4f
        fun inked(top: Float, bottom: Float): Boolean = (top.toInt() until bottom.toInt()).any { y ->
            (island.left.toInt() until island.right.toInt()).any { x -> (image[x, y].toArgb() and 0xFF) < 128 }
        }
        assertTrue(inked(island.top - reach, island.top - 2f), "the paragraph before the quiz")
        assertTrue(inked(island.bottom + 2f, island.bottom + reach), "the paragraph after the quiz")

        // The quiz's link goes to the view's link callback, as a tapped link of the page would.
        onNodeWithContentDescription("OEBPS/quiz.xhtml").performTouchInput {
            click(Offset(island.width * 40f / 200f, island.height * 30f / 100f))
        }
        waitUntil(timeoutMillis = 30_000) { runOnIdle { offered.isNotEmpty() } }
        val action = runOnIdle { offered.single() } as KiteLinkAction.Epub
        assertEquals(WebBooks.NEXT, action.link.href)
        assertEquals(embed.rect, action.rect)
        assertEquals(0, action.pageIndex)
    }
}
