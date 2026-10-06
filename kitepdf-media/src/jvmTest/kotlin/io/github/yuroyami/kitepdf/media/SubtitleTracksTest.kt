package io.github.yuroyami.kitepdf.media

import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import io.github.yuroyami.kitepdf.compose.KiteDocView
import io.github.yuroyami.kitepdf.compose.rememberKiteDocViewState
import io.github.yuroyami.kitepdf.media.MediaBooks.book
import io.github.yuroyami.kitepdf.media.MediaBooks.firstPage
import io.github.yuroyami.kitepdf.media.MediaBooks.fixture
import io.github.yuroyami.kiteplayer.KitePlayer
import kotlinx.coroutines.runBlocking
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The subtitle and caption tracks of a media element load with it, and a menu on the bar picks one (#483). */
@OptIn(ExperimentalTestApi::class)
class SubtitleTracksTest {

    private val players = ArrayList<KitePlayer>()

    @AfterTest
    fun closePlayers() = runBlocking { players.forEach { it.closeAndAwait() } }

    private fun newPlayer(): KitePlayer = SilentPlayers.create().also { players += it }

    private fun vtt(text: String) = "WEBVTT\n\n00:00.000 --> 00:01.500\n$text\n".encodeToByteArray()

    /** A video of the two-second clip with an English caption track that it shows by default and a French subtitle track. */
    private val video =
        """<video src="clip.mp4" width="320" height="240" controls="controls">""" +
            """<track src="en.vtt" kind="captions" srclang="en" label="English" default="default"/>""" +
            """<track src="fr.vtt" srclang="fr" label="Français"/>""" +
            """</video>"""

    private val files = mapOf("clip.mp4" to fixture("clip.mp4"), "en.vtt" to vtt("Hello."), "fr.vtt" to vtt("Bonjour."))

    @Test
    fun the_player_gets_the_subtitle_and_caption_tracks_and_shows_the_default() {
        val doc = book(
            """<video src="clip.mp4" controls="controls">""" +
                """<track src="gone.vtt" kind="captions" default="default"/>""" +
                """<track src="desc.vtt" kind="descriptions"/>""" +
                """<track src="chapters.vtt" kind="chapters"/>""" +
                """<track src="en.vtt" srclang="en" label="English"/>""" +
                """<track src="fr.vtt" srclang="fr" default="default"/>""" +
                """<track src="https://example.com/de.vtt" srclang="de"/>""" +
                """<track src="http://example.com/it.vtt" srclang="it"/>""" +
                """</video>""",
            files = mapOf("en.vtt" to vtt("Hello."), "fr.vtt" to vtt("Bonjour."), "desc.vtt" to vtt("A door opens."), "chapters.vtt" to vtt("One")),
        )
        val media = firstPage(doc).media.single()
        val local = subtitleSources(media, doc, allowRemote = false)
        assertEquals(listOf("OEBPS/en.vtt", "OEBPS/fr.vtt"), local.map { it.uri }, "a missing entry and the tracks of other kinds are left out")
        assertEquals(listOf("English", null), local.map { it.title })
        assertEquals(listOf("en", "fr"), local.map { it.language })
        // The first default is the missing file, so no track that loads shows by itself.
        assertEquals(listOf(false, false), local.map { it.selectImmediately })
        assertTrue(local.all { it.io != null }, "a track of the book reads its entry")

        val remote = subtitleSources(media, doc, allowRemote = true)
        assertEquals(listOf("OEBPS/en.vtt", "OEBPS/fr.vtt", "https://example.com/de.vtt"), remote.map { it.uri }, "https only, as for a source")
        assertNull(remote.last().io)
    }

    @Test
    fun an_audio_element_has_no_picture_to_show_its_tracks_on() {
        val doc = book("""<audio src="tone.mp3" controls="controls"><track src="en.vtt" default="default"/></audio>""", files = mapOf("en.vtt" to vtt("Hello.")))
        val media = firstPage(doc).media.single()
        assertEquals(1, media.tracks.size)
        assertTrue(subtitleSources(media, doc, allowRemote = false).isEmpty())
    }

    @Test
    fun the_default_track_shows_once_the_element_opens() = runBlocking {
        val doc = book(video, files)
        val player = newPlayer()
        val item = assertNotNull(player.openFirstPlayable(mediaItems(firstPage(doc).media.single(), doc, allowRemote = false)))
        assertEquals(2, item.externalSubtitles.size)
        val tracks = player.state.value.tracks
        assertEquals(listOf("English (en)", "Français (fr)"), tracks.subtitles.map { it.label })
        assertEquals(tracks.subtitles.first().id, tracks.selectedSubtitle, "the default track does not show")
    }

    @Test
    fun the_menu_picks_another_track_or_none() = runComposeUiTest {
        val doc = book(video, files)
        setContent {
            KiteDocView(rememberKiteDocViewState(doc), Modifier.size(400.dp, 600.dp), pageOverlay = { KiteMediaOverlay(newPlayer = ::newPlayer) })
        }
        waitUntil(timeoutMillis = 10_000) { onAllNodesWithContentDescription("Play").fetchSemanticsNodes().isNotEmpty() }
        onAllNodesWithContentDescription("Play")[0].performClick()
        waitUntil(timeoutMillis = 30_000) { players.isNotEmpty() && players[0].state.value.tracks.subtitles.size == 2 }
        val player = players.single()
        waitUntil(timeoutMillis = 10_000) { onAllNodesWithContentDescription("Subtitles").fetchSemanticsNodes().isNotEmpty() }
        fun said() = onNodeWithContentDescription("Subtitles").fetchSemanticsNode().config.getOrNull(SemanticsProperties.StateDescription)
        assertEquals("English (en)", said())

        onNodeWithContentDescription("Subtitles").performClick()
        onNodeWithText("Français (fr)").performClick()
        waitUntil(timeoutMillis = 10_000) { player.state.value.tracks.let { it.selectedSubtitle == it.subtitles[1].id } }
        waitUntil(timeoutMillis = 10_000) { said() == "Français (fr)" }

        onNodeWithContentDescription("Subtitles").performClick()
        onNodeWithText("Off").performClick()
        waitUntil(timeoutMillis = 10_000) { player.state.value.tracks.selectedSubtitle == null }
        waitUntil(timeoutMillis = 10_000) { said() == "Off" }
        waitUntil(timeoutMillis = 10_000) { onAllNodesWithText("English (en)").fetchSemanticsNodes().isEmpty() }
    }

    @Test
    fun an_element_without_tracks_has_no_menu() = runComposeUiTest {
        val doc = book("""<video src="clip.mp4" width="320" height="240" controls="controls"></video>""", mapOf("clip.mp4" to fixture("clip.mp4")))
        setContent {
            KiteDocView(rememberKiteDocViewState(doc), Modifier.size(400.dp, 600.dp), pageOverlay = { KiteMediaOverlay(newPlayer = ::newPlayer) })
        }
        waitUntil(timeoutMillis = 10_000) { onAllNodesWithContentDescription("Play").fetchSemanticsNodes().isNotEmpty() }
        onAllNodesWithContentDescription("Play")[0].performClick()
        waitUntil(timeoutMillis = 30_000) { players.isNotEmpty() && players[0].state.value.seekable }
        waitUntil(timeoutMillis = 10_000) { onAllNodesWithContentDescription("Playback speed").fetchSemanticsNodes().isNotEmpty() }
        assertTrue(onAllNodesWithContentDescription("Subtitles").fetchSemanticsNodes().isEmpty())
    }
}
