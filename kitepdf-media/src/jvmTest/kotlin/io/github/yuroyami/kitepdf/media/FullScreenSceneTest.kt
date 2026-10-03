package io.github.yuroyami.kitepdf.media

import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import io.github.yuroyami.kitepdf.compose.KiteDocView
import io.github.yuroyami.kitepdf.compose.KiteDocViewState
import io.github.yuroyami.kitepdf.compose.rememberKiteDocViewState
import io.github.yuroyami.kitepdf.core.KiteRectangle
import io.github.yuroyami.kitepdf.media.MediaBooks.book
import io.github.yuroyami.kitepdf.media.MediaBooks.firstPage
import io.github.yuroyami.kitepdf.media.MediaBooks.fixture
import io.github.yuroyami.kiteplayer.KitePlayer
import io.github.yuroyami.kiteplayer.PlaybackStatus
import androidx.navigationevent.DirectNavigationEventInput
import androidx.navigationevent.NavigationEventDispatcherOwner
import androidx.navigationevent.compose.LocalNavigationEventDispatcherOwner
import kotlinx.coroutines.runBlocking
import java.util.Collections
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds

/**
 * The bar of a video has a full-screen button, and full screen shows the same player over the
 * whole window and goes back to the box on the page (#482). The player plays to an output that
 * takes the sound in real time, so the position moves as it would on a device.
 */
@OptIn(ExperimentalTestApi::class)
class FullScreenSceneTest {

    private val players = Collections.synchronizedList(ArrayList<KitePlayer>())

    @AfterTest
    fun closePlayers() = runBlocking { players.toList().forEach { it.closeAndAwait() } }

    private fun newPlayer(): KitePlayer = SilentPlayers.realTime().also { players += it }

    /** A two second 64 by 48 test pattern that plays again from the start, so it never ends during a test. */
    private val videoBook = """<video src="clip.mp4" width="160" height="120" controls="controls" loop="loop"></video>"""

    private val inLayer = hasAnyAncestor(isDialog())
    private val video = hasContentDescription("Video")

    /** The viewer, the window's size, and the box of the video on its page. */
    private class Scene(val state: KiteDocViewState, val window: IntSize, val media: KiteRectangle)

    private fun ComposeUiTest.playVideo(show: () -> Boolean = { true }, beside: @Composable () -> Unit = {}): Scene {
        val doc = book(videoBook, files = mapOf("clip.mp4" to fixture("clip.mp4")))
        var state: KiteDocViewState? = null
        var window = IntSize.Zero
        setContent {
            window = LocalWindowInfo.current.containerSize
            beside()
            if (show()) {
                val s = rememberKiteDocViewState(doc).also { state = it }
                KiteDocView(s, Modifier.size(400.dp, 600.dp), pageOverlay = { KiteMediaOverlay(newPlayer = ::newPlayer) })
            }
        }
        waitUntil(timeoutMillis = 10_000) { onAllNodesWithContentDescription("Play").fetchSemanticsNodes().isNotEmpty() }
        onNodeWithContentDescription("Play").performClick()
        waitUntil(timeoutMillis = 30_000) { players.isNotEmpty() && players[0].state.value.status == PlaybackStatus.Playing }
        waitUntil(timeoutMillis = 10_000) { onAllNodesWithContentDescription("Full screen").fetchSemanticsNodes().isNotEmpty() }
        return Scene(assertNotNull(state), window, firstPage(doc).media.single().rect)
    }

    /** The share of the pixels of [rect] in [image] that the test pattern colours: about 0.78 of a frame. */
    private fun coloured(image: ImageBitmap, rect: Rect): Double {
        val pixels = image.toPixelMap()
        val left = rect.left.roundToInt().coerceIn(0, pixels.width)
        val right = rect.right.roundToInt().coerceIn(0, pixels.width)
        val top = rect.top.roundToInt().coerceIn(0, pixels.height)
        val bottom = rect.bottom.roundToInt().coerceIn(0, pixels.height)
        var count = 0
        var all = 0
        for (y in top until bottom step 2) for (x in left until right step 2) {
            val c = pixels[x, y]
            if (maxOf(c.red, c.green, c.blue) - minOf(c.red, c.green, c.blue) > 0.25f) count++
            all++
        }
        return if (all == 0) 0.0 else count.toDouble() / all
    }

    private fun ComposeUiTest.videoBounds(where: SemanticsMatcher): Rect = onNode(video and where).fetchSemanticsNode().boundsInWindow

    private fun ComposeUiTest.awaitFullScreen() {
        waitUntil(timeoutMillis = 10_000) { onAllNodes(video and inLayer).fetchSemanticsNodes().isNotEmpty() }
    }

    private fun ComposeUiTest.awaitBox() {
        waitUntil(timeoutMillis = 10_000) {
            onAllNodes(isDialog()).fetchSemanticsNodes().isEmpty() && onAllNodes(video).fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun assertNear(expected: Rect, actual: Rect, what: String) {
        val off = listOf(expected.left - actual.left, expected.top - actual.top, expected.right - actual.right, expected.bottom - actual.bottom)
        assertTrue(off.all { abs(it) <= 1f }, "$what: expected $expected, got $actual")
    }

    /**
     * Waits for the position to move on by 300 ms, or to start again from the loop, and checks that the
     * player is still active. The loop buffers for a moment as it starts again, so Buffering counts:
     * CI caught the clip there, just after the wrap this accepts (#494). A pause leaves the position
     * where it stopped and reads Paused.
     */
    private fun ComposeUiTest.assertPlaysOn(player: KitePlayer, what: String) {
        val from = player.position()
        val moved = runCatching {
            waitUntil(timeoutMillis = 10_000) { player.position() >= from + 300.milliseconds || player.position() < from }
        }.isSuccess
        val status = player.state.value.status
        assertTrue(moved && status.isActive, "$what: the position went from $from to ${player.position()}, status $status")
    }

    @Test
    fun full_screen_plays_the_same_video_over_the_window_and_its_button_brings_it_back_to_the_box() = runComposeUiTest {
        val scene = playVideo()
        val player = players.single()
        val box = assertNotNull(runOnIdle { scene.state.displayRectToViewport(0, scene.media) })
        assertNear(box, videoBounds(!inLayer), "the video before full screen")

        onNodeWithContentDescription("Full screen").performClick()
        awaitFullScreen()
        assertEquals(1, onAllNodes(video).fetchSemanticsNodes().size, "the box kept a video of its own in full screen")
        val window = Rect(0f, 0f, scene.window.width.toFloat(), scene.window.height.toFloat())
        assertNear(window, videoBounds(inLayer), "the full-screen video")
        // The frames reach the layer: its renderer took over from the box's.
        waitUntil(timeoutMillis = 10_000) { coloured(onNode(isDialog()).captureToImage(), window) > 0.5 }
        assertPlaysOn(player, "full screen paused the video")

        onNodeWithContentDescription("Exit full screen").performClick()
        awaitBox()
        assertNear(box, videoBounds(!inLayer), "the video after full screen")
        waitUntil(timeoutMillis = 10_000) { coloured(onAllNodes(isRoot()).onFirst().captureToImage(), box) > 0.5 }
        assertPlaysOn(player, "leaving full screen paused the video")
        assertEquals(1, players.size, "full screen made a player of its own")
    }

    @Test
    fun a_back_event_leaves_full_screen() = runComposeUiTest {
        var owner: NavigationEventDispatcherOwner? = null
        playVideo(beside = { owner = LocalNavigationEventDispatcherOwner.current })
        val player = players.single()
        onNodeWithContentDescription("Full screen").performClick()
        awaitFullScreen()
        // A desktop window turns Escape into this event, and iOS its back gesture. The test scene has
        // no window, so the test sends the event as the window would.
        val back = DirectNavigationEventInput()
        runOnIdle {
            assertNotNull(owner, "the scene has no navigation dispatcher").navigationEventDispatcher.addInput(back)
            back.backCompleted()
        }
        awaitBox()
        assertPlaysOn(player, "back paused the video")
        assertEquals(1, players.size)
    }

    @Test
    fun a_page_that_leaves_the_composition_takes_the_full_screen_video_with_it() = runComposeUiTest {
        var show by mutableStateOf(true)
        playVideo(show = { show })
        val player = players.single()
        onNodeWithContentDescription("Full screen").performClick()
        awaitFullScreen()
        show = false
        waitUntil(timeoutMillis = 10_000) { onAllNodes(isDialog()).fetchSemanticsNodes().isEmpty() }
        assertTrue(onAllNodes(video).fetchSemanticsNodes().isEmpty(), "a video stayed on the screen")
        // A closed player refuses every command.
        waitUntil(timeoutMillis = 10_000) { runCatching { player.play() }.isFailure }
    }

    @Test
    fun an_audio_bar_has_no_full_screen_button() = runComposeUiTest {
        val doc = book("""<audio src="tone.mp3" controls="controls"></audio>""", files = mapOf("tone.mp3" to fixture("tone.mp3")))
        setContent {
            KiteDocView(rememberKiteDocViewState(doc), Modifier.size(400.dp, 600.dp), pageOverlay = { KiteMediaOverlay(newPlayer = ::newPlayer) })
        }
        waitUntil(timeoutMillis = 10_000) { onAllNodesWithContentDescription("Play").fetchSemanticsNodes().isNotEmpty() }
        onNodeWithContentDescription("Play").performClick()
        waitUntil(timeoutMillis = 30_000) { onAllNodesWithContentDescription("Pause").fetchSemanticsNodes().isNotEmpty() }
        assertTrue(onAllNodesWithContentDescription("Full screen").fetchSemanticsNodes().isEmpty())
    }
}
