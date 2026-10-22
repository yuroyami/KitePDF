package io.github.yuroyami.kitepdf.media

import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import io.github.yuroyami.kitepdf.compose.KiteDocView
import io.github.yuroyami.kitepdf.compose.KiteDocViewState
import io.github.yuroyami.kitepdf.compose.rememberKiteDocViewState
import io.github.yuroyami.kitepdf.media.MediaBooks.book
import io.github.yuroyami.kitepdf.media.MediaBooks.firstPage
import io.github.yuroyami.kitepdf.media.MediaBooks.fixture
import io.github.yuroyami.kiteplayer.KitePlayer
import io.github.yuroyami.kiteplayer.LoopMode
import io.github.yuroyami.kiteplayer.PlaybackStatus
import kotlinx.coroutines.runBlocking
import kotlin.math.abs
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The overlay draws a play button on each media box of a page, and the video on the box once it
 * plays (#31). A viewer without the overlay plays nothing, and shows the poster as before.
 */
@OptIn(ExperimentalTestApi::class)
class MediaOverlaySceneTest {

    private val players = ArrayList<KitePlayer>()

    @AfterTest
    fun closePlayers() = runBlocking { players.forEach { it.closeAndAwait() } }

    private fun newPlayer(): KitePlayer = SilentPlayers.create().also { players += it }

    private val videoBook = """<video src="clip.mp4" width="160" height="120" controls="controls"></video>"""

    private fun assertNear(expected: Rect, actual: Rect, what: String) {
        val off = listOf(expected.left - actual.left, expected.top - actual.top, expected.right - actual.right, expected.bottom - actual.bottom)
        assertTrue(off.all { abs(it) <= 1f }, "$what: expected $expected, got $actual")
    }

    @Test
    fun the_video_plays_on_the_box_of_its_element() = runComposeUiTest {
        val doc = book(videoBook, files = mapOf("clip.mp4" to fixture("clip.mp4")))
        val box = firstPage(doc).media.single().rect
        lateinit var state: KiteDocViewState
        setContent {
            state = rememberKiteDocViewState(doc)
            KiteDocView(state, Modifier.size(400.dp, 600.dp), pageOverlay = { KiteMediaOverlay(newPlayer = ::newPlayer) })
        }
        waitUntil(timeoutMillis = 10_000) { onAllNodesWithContentDescription("Play").fetchSemanticsNodes().isNotEmpty() }
        val expected = assertNotNull(runOnIdle { state.displayRectToViewport(0, box) }, "the page of the box is not placed")
        assertNear(expected, onNodeWithContentDescription("Play").fetchSemanticsNode().boundsInRoot, "the play button")

        onNodeWithContentDescription("Play").performClick()
        waitUntil(timeoutMillis = 30_000) { onAllNodesWithContentDescription("Video").fetchSemanticsNodes().isNotEmpty() }
        assertNear(expected, onNodeWithContentDescription("Video").fetchSemanticsNode().boundsInRoot, "the video")
        assertEquals(1, players.size, "one element starts one player")
    }

    @Test
    fun a_page_plays_nothing_until_an_element_starts() = runComposeUiTest {
        val doc = book(videoBook, files = mapOf("clip.mp4" to fixture("clip.mp4")))
        setContent {
            KiteDocView(rememberKiteDocViewState(doc), Modifier.size(400.dp, 600.dp), pageOverlay = { KiteMediaOverlay(newPlayer = ::newPlayer) })
        }
        waitUntil(timeoutMillis = 10_000) { onAllNodesWithContentDescription("Play").fetchSemanticsNodes().isNotEmpty() }
        waitForIdle()
        assertTrue(players.isEmpty(), "a player was made before the element started")
        assertTrue(onAllNodesWithContentDescription("Video").fetchSemanticsNodes().isEmpty())
    }

    @Test
    fun an_autoplay_element_starts_by_itself_muted_and_its_first_touch_turns_the_sound_on() = runComposeUiTest {
        val doc = book(
            """<video src="clip.mp4" width="160" height="120" autoplay="autoplay" loop="loop" controls="controls"></video>""",
            files = mapOf("clip.mp4" to fixture("clip.mp4")),
        )
        setContent {
            KiteDocView(rememberKiteDocViewState(doc), Modifier.size(400.dp, 600.dp), pageOverlay = { KiteMediaOverlay(newPlayer = ::newPlayer) })
        }
        waitUntil(timeoutMillis = 30_000) { onAllNodesWithContentDescription("Video").fetchSemanticsNodes().isNotEmpty() }
        val player = players.single()
        assertTrue(player.state.value.muted, "autoplay starts muted")
        assertEquals(LoopMode.One, player.state.value.loop)

        waitUntil(timeoutMillis = 30_000) { player.state.value.status.isActive }
        waitUntil(timeoutMillis = 10_000) { onAllNodesWithContentDescription("Pause").fetchSemanticsNodes().isNotEmpty() }
        onNodeWithContentDescription("Pause").performClick()
        // The player takes the command on its own thread, so its state turns after Compose is idle.
        val unmuted = runCatching { waitUntil(timeoutMillis = 10_000) { !player.state.value.muted } }.isSuccess
        assertTrue(unmuted, "the first touch did not turn the sound on")
        assertTrue(player.state.value.status.isActive, "the first touch paused instead")
    }

    @Test
    fun an_element_with_no_playable_source_keeps_its_poster_and_drops_its_button() = runComposeUiTest {
        val doc = book(
            """<video src="clip.mp4" width="160" height="120" controls="controls"></video>""",
            files = mapOf("clip.mp4" to "not a video".encodeToByteArray()),
        )
        setContent {
            KiteDocView(rememberKiteDocViewState(doc), Modifier.size(400.dp, 600.dp), pageOverlay = { KiteMediaOverlay(newPlayer = ::newPlayer) })
        }
        waitUntil(timeoutMillis = 10_000) { onAllNodesWithContentDescription("Play").fetchSemanticsNodes().isNotEmpty() }
        onNodeWithContentDescription("Play").performClick()
        waitUntil(timeoutMillis = 30_000) { onAllNodesWithContentDescription("Play").fetchSemanticsNodes().isEmpty() }
        assertTrue(onAllNodesWithContentDescription("Video").fetchSemanticsNodes().isEmpty(), "a video surface for nothing")
    }

    @Test
    fun a_video_without_controls_pauses_on_a_tap_and_shows_its_bar() = runComposeUiTest {
        // HTML, 4.8.11 lets a reading system offer controls the element does not ask for, and WCAG
        // 2.2, 2.2.2 asks for a way to pause what moves for more than five seconds (#480). The clip
        // loops, so it never stops by itself.
        val doc = book("""<video src="clip.mp4" width="160" height="120" loop="loop"></video>""", files = mapOf("clip.mp4" to fixture("clip.mp4")))
        setContent {
            KiteDocView(rememberKiteDocViewState(doc), Modifier.size(400.dp, 600.dp), pageOverlay = { KiteMediaOverlay(newPlayer = ::newPlayer) })
        }
        waitUntil(timeoutMillis = 10_000) { onAllNodesWithContentDescription("Play").fetchSemanticsNodes().isNotEmpty() }
        onNodeWithContentDescription("Play").performClick()
        waitUntil(timeoutMillis = 30_000) { players.isNotEmpty() && players[0].state.value.status.isActive }
        val player = players.single()
        assertTrue(onAllNodesWithContentDescription("Pause").fetchSemanticsNodes().isEmpty(), "a video without controls shows no bar while it plays")

        onNodeWithContentDescription("Video").performClick()
        // The player takes the command on its own thread, so its state turns after Compose is idle.
        val paused = runCatching { waitUntil(timeoutMillis = 10_000) { player.state.value.status == PlaybackStatus.Paused } }.isSuccess
        assertTrue(paused, "a tap did not pause the video")
        waitUntil(timeoutMillis = 10_000) { onAllNodesWithContentDescription("Play").fetchSemanticsNodes().isNotEmpty() }

        onNodeWithContentDescription("Play").performClick()
        waitUntil(timeoutMillis = 10_000) { player.state.value.status.isActive }
        // Once it plays on for a moment, the bar goes again.
        waitUntil(timeoutMillis = 10_000) { onAllNodesWithContentDescription("Pause").fetchSemanticsNodes().isEmpty() }
        assertTrue(player.state.value.status.isActive, "the video stopped before its bar went")
    }

    @Test
    fun an_autoplay_video_without_controls_turns_its_sound_on_at_the_first_tap() = runComposeUiTest {
        // Only a touch turns on the sound that autoplay muted, so without one the video stays mute for good (#480).
        val doc = book(
            """<video src="clip.mp4" width="160" height="120" autoplay="autoplay" loop="loop"></video>""",
            files = mapOf("clip.mp4" to fixture("clip.mp4")),
        )
        setContent {
            KiteDocView(rememberKiteDocViewState(doc), Modifier.size(400.dp, 600.dp), pageOverlay = { KiteMediaOverlay(newPlayer = ::newPlayer) })
        }
        waitUntil(timeoutMillis = 30_000) { onAllNodesWithContentDescription("Video").fetchSemanticsNodes().isNotEmpty() }
        val player = players.single()
        waitUntil(timeoutMillis = 30_000) { player.state.value.status.isActive }
        assertTrue(player.state.value.muted, "autoplay starts muted")

        onNodeWithContentDescription("Video").performClick()
        val unmuted = runCatching { waitUntil(timeoutMillis = 10_000) { !player.state.value.muted } }.isSuccess
        assertTrue(unmuted, "the first tap did not turn the sound on")
        assertTrue(player.state.value.status.isActive, "the first tap paused instead")
        waitUntil(timeoutMillis = 10_000) { onAllNodesWithContentDescription("Pause").fetchSemanticsNodes().isNotEmpty() }
    }
}
