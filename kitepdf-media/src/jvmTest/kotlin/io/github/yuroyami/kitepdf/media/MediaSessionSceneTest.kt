package io.github.yuroyami.kitepdf.media

import androidx.compose.foundation.layout.size
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import io.github.yuroyami.kitepdf.compose.KiteDocLayout
import io.github.yuroyami.kitepdf.compose.KiteDocView
import io.github.yuroyami.kitepdf.compose.KiteDocViewState
import io.github.yuroyami.kitepdf.core.KiteLocation
import io.github.yuroyami.kitepdf.media.MediaBooks.book
import io.github.yuroyami.kitepdf.media.MediaBooks.fixture
import io.github.yuroyami.kiteplayer.KitePlayer
import io.github.yuroyami.kiteplayer.PlaybackStatus
import kotlinx.coroutines.runBlocking
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * The media elements of a book share a session: an element keeps its place when its page leaves
 * and comes back, and one element plays at a time (#481).
 */
@OptIn(ExperimentalTestApi::class)
class MediaSessionSceneTest {

    private val players = ArrayList<KitePlayer>()

    @AfterTest
    fun closePlayers() = runBlocking { players.forEach { it.closeAndAwait() } }

    /** A player that stays at the position it opened or sought to. */
    private fun newPlayer(): KitePlayer = SilentPlayers.create().also { players += it }

    @Test
    fun an_element_whose_page_comes_back_shows_where_it_stopped_paused() = runComposeUiTest {
        // A video on the first page, then enough text for several more pages.
        val doc = book(
            """<video src="clip.mp4" width="160" height="120" controls="controls"></video>""" + (1..300).joinToString("") { "<p>Line $it.</p>" },
            files = mapOf("clip.mp4" to fixture("clip.mp4")),
        )
        val session = KiteMediaSession()
        val state = KiteDocViewState(doc)
        setContent {
            KiteDocView(state, Modifier.size(400.dp, 600.dp), layout = KiteDocLayout.Paged(), pageOverlay = { KiteMediaOverlay(newPlayer = ::newPlayer, session = session) })
        }
        waitUntil(timeoutMillis = 10_000) { onAllNodesWithContentDescription("Play").fetchSemanticsNodes().isNotEmpty() }
        onAllNodesWithContentDescription("Play")[0].performClick()
        waitUntil(timeoutMillis = 30_000) { players.isNotEmpty() && players[0].state.value.seekable }
        val first = players[0]
        runBlocking { first.seek(1.2.seconds) }
        first.setMuted(true)
        assertTrue(doc.pageCount > 3, "the book has ${doc.pageCount} pages")

        runOnIdle { runBlocking { state.scrollTo(KiteLocation(0, doc.pageCount - 1), animate = false) } }
        waitUntil(timeoutMillis = 10_000) { first.state.value.status == PlaybackStatus.Idle || players.size > 1 || session.placeCount > 0 }
        waitUntil(timeoutMillis = 10_000) { session.placeCount == 1 }

        runOnIdle { runBlocking { state.scrollTo(KiteLocation(0, 0), animate = false) } }
        waitUntil(timeoutMillis = 30_000) { players.size == 2 && players[1].state.value.seekable }
        val second = players[1]
        waitUntil(timeoutMillis = 10_000) { (second.position() - 1.2.seconds).absoluteValue <= 150.milliseconds }
        assertTrue(!second.state.value.status.isActive, "the element came back playing")
        assertTrue(second.state.value.muted, "the element came back with its sound on")
    }

    @Test
    fun a_book_opened_again_starts_with_no_place() = runComposeUiTest {
        val session = KiteMediaSession()
        val body = """<video src="clip.mp4" width="160" height="120" controls="controls"></video>"""
        val files = mapOf("clip.mp4" to fixture("clip.mp4"))
        val doc = mutableStateOf(book(body, files))
        setContent {
            key(doc.value) {
                KiteDocView(KiteDocViewState(doc.value), Modifier.size(400.dp, 600.dp), pageOverlay = { KiteMediaOverlay(newPlayer = ::newPlayer, session = session) })
            }
        }
        waitUntil(timeoutMillis = 10_000) { onAllNodesWithContentDescription("Play").fetchSemanticsNodes().isNotEmpty() }
        onAllNodesWithContentDescription("Play")[0].performClick()
        waitUntil(timeoutMillis = 30_000) { players.isNotEmpty() && players[0].state.value.seekable }
        runBlocking { players[0].seek(1.seconds) }
        // The same book, opened again: the old viewer leaves, and keeps its place in the session.
        doc.value = book(body, files)
        waitUntil(timeoutMillis = 10_000) { session.placeCount == 1 }
        waitUntil(timeoutMillis = 10_000) { onAllNodesWithContentDescription("Play").fetchSemanticsNodes().isNotEmpty() }
        waitForIdle()
        assertEquals(1, players.size, "the book opened again made a player for an old place")
    }

    @Test
    fun starting_one_element_pauses_the_other() = runComposeUiTest {
        val doc = book(
            """<audio src="tone.mp3" controls="controls" loop="loop"></audio><p>Between.</p><audio src="other.mp3" controls="controls" loop="loop"></audio>""",
            files = mapOf("tone.mp3" to fixture("tone.mp3"), "other.mp3" to fixture("tone.mp3")),
        )
        setContent {
            KiteDocView(KiteDocViewState(doc), Modifier.size(400.dp, 600.dp), pageOverlay = { KiteMediaOverlay(newPlayer = ::newPlayer, session = KiteMediaSession()) })
        }
        waitUntil(timeoutMillis = 10_000) { onAllNodesWithContentDescription("Play").fetchSemanticsNodes().size == 2 }
        onAllNodesWithContentDescription("Play")[0].performClick()
        waitUntil(timeoutMillis = 30_000) { players.size == 1 && players[0].state.value.status.isActive }
        // The first element now shows Pause, so the only Play left is the second element's button.
        waitUntil(timeoutMillis = 10_000) { onAllNodesWithContentDescription("Play").fetchSemanticsNodes().size == 1 }
        onAllNodesWithContentDescription("Play")[0].performClick()
        waitUntil(timeoutMillis = 30_000) { players.size == 2 && players[1].state.value.status.isActive }
        val paused = runCatching { waitUntil(timeoutMillis = 10_000) { !players[0].state.value.status.isActive } }.isSuccess
        assertTrue(paused, "the first element plays on with the second")
        assertTrue(players[1].state.value.status.isActive)
    }

    @Test
    fun a_session_forgets_the_oldest_places_past_its_bound() {
        val session = KiteMediaSession()
        repeat(300) { session.keep("element $it", KiteMediaSession.Place(it.seconds, muted = false, speed = 1.0)) }
        assertEquals(256, session.placeCount)
        assertEquals(null, session.placeOf("element 0"))
        assertEquals(299.seconds, session.placeOf("element 299")?.position)
        session.keep("element 299", null)
        assertEquals(null, session.placeOf("element 299"))
    }
}
