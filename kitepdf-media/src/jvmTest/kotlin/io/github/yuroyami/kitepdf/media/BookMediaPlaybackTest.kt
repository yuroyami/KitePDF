package io.github.yuroyami.kitepdf.media

import io.github.yuroyami.kitepdf.media.MediaBooks.book
import io.github.yuroyami.kitepdf.media.MediaBooks.firstPage
import io.github.yuroyami.kitepdf.media.MediaBooks.fixture
import io.github.yuroyami.kiteplayer.KitePlayer
import io.github.yuroyami.kiteplayer.PlaybackStatus
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.time.Duration.Companion.seconds

/**
 * The player streams an element's media out of the book's own bytes and opens it with the right
 * duration (#31). Both fixtures last two seconds. The player decodes with FFmpeg and plays to a
 * silent output, so the test runs on a machine with no sound device.
 */
class BookMediaPlaybackTest {

    private fun player(): KitePlayer = SilentPlayers.create()

    private fun opensWithItsDuration(body: String, file: String) = runBlocking {
        val doc = book(body, files = mapOf(file to fixture(file)))
        val player = player()
        try {
            val opened = withTimeout(30.seconds) {
                player.openFirstPlayable(mediaItems(firstPage(doc).media.single(), doc, allowRemote = false))
            }
            assertEquals("OEBPS/$file", assertNotNull(opened, "the player opened no source").uri)
            val snapshot = player.state.value
            assertEquals(PlaybackStatus.Paused, snapshot.status, "an open item waits, paused")
            val duration = assertNotNull(snapshot.duration, "the item has no duration")
            assertEquals(2.0, duration.inWholeMilliseconds / 1000.0, 0.15, "the duration of $file")
        } finally {
            player.closeAndAwait()
        }
    }

    @Test
    fun an_mp3_streams_out_of_the_book() = opensWithItsDuration("""<audio controls="controls" src="tone.mp3"></audio>""", "tone.mp3")

    @Test
    fun an_mp4_streams_out_of_the_book() = opensWithItsDuration("""<video controls="controls" src="clip.mp4" width="64" height="48"></video>""", "clip.mp4")

    @Test
    fun a_source_the_player_cannot_play_gives_way_to_the_next() = runBlocking {
        val doc = book(
            """<audio controls="controls"><source src="broken.mp3"/><source src="tone.mp3"/></audio>""",
            files = mapOf("broken.mp3" to "not media at all".encodeToByteArray(), "tone.mp3" to fixture("tone.mp3")),
        )
        val player = player()
        try {
            val opened = withTimeout(30.seconds) {
                player.openFirstPlayable(mediaItems(firstPage(doc).media.single(), doc, allowRemote = false))
            }
            assertEquals("OEBPS/tone.mp3", opened?.uri)
        } finally {
            player.closeAndAwait()
        }
    }
}
