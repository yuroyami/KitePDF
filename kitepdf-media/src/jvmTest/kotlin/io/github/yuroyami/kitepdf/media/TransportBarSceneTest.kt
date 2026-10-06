package io.github.yuroyami.kitepdf.media

import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.click
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.percentOffset
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.test.swipe
import androidx.compose.ui.unit.dp
import io.github.yuroyami.kitepdf.compose.KiteDocView
import io.github.yuroyami.kitepdf.compose.rememberKiteDocViewState
import io.github.yuroyami.kitepdf.media.MediaBooks.book
import io.github.yuroyami.kitepdf.media.MediaBooks.fixture
import io.github.yuroyami.kiteplayer.KitePlayer
import kotlinx.coroutines.runBlocking
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * The transport bar of a media element seeks on a tap and a drag, shows the times, mutes and
 * changes the speed, and says its controls in the words the app gives (#479, #484).
 */
@OptIn(ExperimentalTestApi::class)
class TransportBarSceneTest {

    private val players = ArrayList<KitePlayer>()

    @AfterTest
    fun closePlayers() = runBlocking { players.forEach { it.closeAndAwait() } }

    /** A player that stays at the position it opened or sought to, so a seek is easy to read. */
    private fun newPlayer(): KitePlayer = SilentPlayers.create().also { players += it }

    /** Shows an audio element of the two-second tone, by default wide enough for the whole bar, and starts it. */
    private fun ComposeUiTest.startAudio(labels: KiteMediaLabels = KiteMediaLabels(), width: String = "100%"): KitePlayer {
        val doc = book("""<audio src="tone.mp3" controls="controls" style="width: $width"></audio>""", files = mapOf("tone.mp3" to fixture("tone.mp3")))
        setContent {
            KiteDocView(rememberKiteDocViewState(doc), Modifier.size(400.dp, 600.dp), pageOverlay = { KiteMediaOverlay(newPlayer = ::newPlayer, labels = labels) })
        }
        waitUntil(timeoutMillis = 10_000) { onAllNodesWithContentDescription(labels.play).fetchSemanticsNodes().isNotEmpty() }
        onNodeWithContentDescription(labels.play).performClick()
        waitUntil(timeoutMillis = 30_000) { players.isNotEmpty() && players[0].state.value.duration != null && players[0].state.value.seekable }
        waitUntil(timeoutMillis = 10_000) { onAllNodesWithContentDescription(labels.seek).fetchSemanticsNodes().isNotEmpty() }
        return players.single()
    }

    private fun ComposeUiTest.waitForPosition(player: KitePlayer, expected: Duration) {
        val reached = runCatching {
            waitUntil(timeoutMillis = 10_000) { (player.position() - expected).absoluteValue <= 150.milliseconds }
        }.isSuccess
        assertTrue(reached, "the player is at ${player.position()}, not $expected")
    }

    @Test
    fun a_tap_on_the_line_seeks_there() = runComposeUiTest {
        val player = startAudio()
        onNodeWithContentDescription("Seek").performTouchInput { click(percentOffset(0.75f, 0.5f)) }
        waitForPosition(player, 1.5.seconds)
    }

    @Test
    fun a_drag_on_the_line_scrubs_and_lands_where_it_ends() = runComposeUiTest {
        val player = startAudio()
        onNodeWithContentDescription("Seek").performTouchInput {
            swipe(percentOffset(0.1f, 0.5f), percentOffset(0.5f, 0.5f), durationMillis = 400)
        }
        waitForPosition(player, 1.seconds)
    }

    @Test
    fun a_screen_reader_seeks_the_line_as_a_slider() = runComposeUiTest {
        val player = startAudio()
        val line = onNodeWithContentDescription("Seek").fetchSemanticsNode()
        val range = assertNotNull(line.config.getOrNull(SemanticsProperties.ProgressBarRangeInfo))
        assertEquals(0f..1f, range.range)
        assertEquals(0, range.steps, "a two-second clip has no five-second step inside it")
        onNodeWithContentDescription("Seek").performSemanticsAction(SemanticsActions.SetProgress) { it(0.25f) }
        waitForPosition(player, 0.5.seconds)
    }

    @Test
    fun the_bar_shows_the_elapsed_and_the_total_time() = runComposeUiTest {
        startAudio()
        waitUntil(timeoutMillis = 10_000) { onAllNodesWithText("0:02").fetchSemanticsNodes().isNotEmpty() }
        assertTrue(onAllNodesWithText("0:00").fetchSemanticsNodes().isNotEmpty(), "no elapsed time")
    }

    @Test
    fun a_narrow_bar_drops_the_times_and_keeps_the_line() = runComposeUiTest {
        startAudio(width = "200px")
        assertTrue(onAllNodesWithText("0:02").fetchSemanticsNodes().isEmpty(), "the times crowd a narrow bar")
        assertTrue(onAllNodesWithContentDescription("Mute").fetchSemanticsNodes().isNotEmpty())
    }

    @Test
    fun the_mute_button_turns_the_sound_off_and_on() = runComposeUiTest {
        val player = startAudio()
        assertTrue(!player.state.value.muted)
        onNodeWithContentDescription("Mute").performClick()
        waitUntil(timeoutMillis = 10_000) { player.state.value.muted }
        waitUntil(timeoutMillis = 10_000) { onAllNodesWithContentDescription("Unmute").fetchSemanticsNodes().isNotEmpty() }
        onNodeWithContentDescription("Unmute").performClick()
        waitUntil(timeoutMillis = 10_000) { !player.state.value.muted }
    }

    @Test
    fun the_speed_menu_sets_the_speed() = runComposeUiTest {
        val player = startAudio()
        onNodeWithContentDescription("Playback speed").performClick()
        onNodeWithText("1.5×").performClick()
        waitUntil(timeoutMillis = 10_000) { player.state.value.speed == 1.5 }
        waitUntil(timeoutMillis = 10_000) { onAllNodesWithText("2×").fetchSemanticsNodes().isEmpty() }
        assertTrue(onAllNodesWithText("1.5×").fetchSemanticsNodes().isNotEmpty(), "the button shows the new speed")
    }

    @Test
    fun the_controls_say_the_words_the_app_gives() = runComposeUiTest {
        val french = KiteMediaLabels(play = "Lire", pause = "Pause", mute = "Couper le son", unmute = "Remettre le son", seek = "Position", speed = "Vitesse")
        startAudio(french)
        for (word in listOf("Couper le son", "Position", "Vitesse")) {
            assertTrue(onAllNodesWithContentDescription(word).fetchSemanticsNodes().isNotEmpty(), "no control says $word")
        }
        assertTrue(onAllNodesWithContentDescription("Mute").fetchSemanticsNodes().isEmpty(), "an English word stayed")
    }

    @Test
    fun times_and_speeds_read_as_players_show_them() {
        assertEquals("0:00", clock(Duration.ZERO))
        assertEquals("1:05", clock(65.4.seconds))
        assertEquals("1:02:03", clock((3600 + 123).seconds))
        assertEquals(listOf("0.5×", "0.75×", "1×", "1.25×", "1.5×", "2×"), listOf(0.5, 0.75, 1.0, 1.25, 1.5, 2.0).map(::speedText))
    }
}
