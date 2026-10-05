package io.github.yuroyami.kitepdf.media

import androidx.compose.foundation.layout.size
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import io.github.yuroyami.kitepdf.compose.KiteDocLayout
import io.github.yuroyami.kitepdf.compose.KiteDocView
import io.github.yuroyami.kitepdf.compose.KiteDocViewState
import io.github.yuroyami.kitepdf.compose.KiteHighlight
import io.github.yuroyami.kitepdf.core.KiteLocation
import io.github.yuroyami.kitepdf.core.KiteSearchHit
import io.github.yuroyami.kitepdf.epub.EpubDocument
import io.github.yuroyami.kitepdf.media.NarratedBooks.Chapter
import io.github.yuroyami.kitepdf.media.NarratedBooks.book
import io.github.yuroyami.kitepdf.media.NarratedBooks.par
import io.github.yuroyami.kiteplayer.KitePlayer
import kotlinx.coroutines.runBlocking
import java.util.Collections
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlin.time.Duration

/**
 * [KiteReadAloud] reads a book's media overlays with a real player and follows the text in a real
 * viewer (#36). The audio is a synthetic WAV, and the player plays it to an output that takes the
 * samples as a sound device would and drops them, so the reading takes the time the clips say.
 */
@OptIn(ExperimentalTestApi::class)
class ReadAloudTest {

    private val players = Collections.synchronizedList(ArrayList<KitePlayer>())

    @AfterTest
    fun closePlayers() = runBlocking { players.toList().forEach { it.closeAndAwait() } }

    private fun newPlayer(): KitePlayer = SilentPlayers.realTime().also { players += it }

    /**
     * The position of [player] once it stops moving. A render that the device had started when
     * the pause came is counted just after the status says Paused, so the position moves one
     * period after that (#568). A player that goes on moving while paused never settles.
     */
    private fun settledPosition(player: KitePlayer): Duration {
        val until = System.nanoTime() + 2_000_000_000L
        var last = player.position()
        while (System.nanoTime() < until) {
            Thread.sleep(100)
            val now = player.position()
            if (now == last) return now
            last = now
        }
        fail("the position of a paused player never settled: $last")
    }

    private val audio = mapOf("narration.wav" to NarratedBooks.wav(3))

    /** Three sentences read a second each from a three second file. A tall block puts the third on the second page. */
    private fun threeSentences(): EpubDocument = book(
        listOf(
            Chapter(
                """<p id="s1">One.</p><p id="s2">Two.</p><div style="height:150px"></div><p id="s3">Three.</p>""",
                par(1, "s1", "narration.wav", 0.0, 1.0) + par(1, "s2", "narration.wav", 1.0, 2.0) + par(1, "s3", "narration.wav", 2.0, 3.0),
            ),
        ),
        audio,
    )

    /** The read-aloud highlight as it changed, when, in milliseconds from the start, and on which page the reader was. */
    private class Mark(val ms: Long, val highlight: KiteHighlight?, val page: KiteLocation) {
        override fun toString(): String = "${highlight?.hit?.quads?.firstOrNull()?.bottom?.toInt()}@${ms}ms"
    }

    /** What a reading did: the id of each clip as it started, null at the end, and each change of its highlight. */
    private class Log {
        val clips: MutableList<String?> = Collections.synchronizedList(ArrayList())
        val marks: MutableList<Mark> = Collections.synchronizedList(ArrayList())

        @Volatile
        var finished = false
    }

    /** Shows [state] and reads it while [playing] is true. Setting [jump] moves the viewer there, as a reader's link would. */
    private fun ComposeUiTest.read(
        state: KiteDocViewState,
        playing: MutableState<Boolean>,
        log: Log,
        jump: MutableState<KiteLocation?> = mutableStateOf(null),
    ) {
        val start = System.nanoTime()
        setContent {
            KiteDocView(state, Modifier.size(400.dp, 600.dp), layout = KiteDocLayout.Paged())
            jump.value?.let { to -> LaunchedEffect(to) { state.scrollTo(to) } }
            KiteReadAloud(state, playing.value, newPlayer = ::newPlayer, onClip = { log.clips += it?.id }, onFinished = { log.finished = true })
            LaunchedEffect(state) {
                snapshotFlow { state.highlights.firstOrNull { it.id == READ_ALOUD_HIGHLIGHT_ID } }
                    .collect { log.marks += Mark((System.nanoTime() - start) / 1_000_000, it, state.currentLocation) }
            }
        }
    }

    @Test
    fun the_highlight_follows_the_clips_in_time_and_the_viewer_turns_to_the_page_of_the_third() = runComposeUiTest {
        val doc = threeSentences()
        val boxes = (1..3).map { assertNotNull(doc.locateFragment("OEBPS/c1.xhtml#s$it")) }
        assertEquals(
            listOf(KiteLocation(0, 0), KiteLocation(0, 0), KiteLocation(0, 1)),
            boxes.map { it.location },
            "the third sentence is on the second page",
        )
        val state = KiteDocViewState(doc)
        val note = KiteHighlight(KiteSearchHit(KiteLocation(0, 0), boxes[0].rects, "One."), id = "note")
        state.highlights = listOf(note)
        val log = Log()
        read(state, mutableStateOf(true), log)
        waitUntil(timeoutMillis = 30_000) { log.finished }
        waitForIdle()

        assertEquals(listOf("s1", "s2", "s3", null), log.clips.toList())
        val shown = log.marks.filter { it.highlight != null }
        assertEquals(
            boxes.map { it.location to it.rects },
            shown.map { it.highlight!!.hit.location to it.highlight.hit.quads },
            "one highlight for each clip, on its text, in the order of the overlay",
        )
        // The audio sets the time, so a clip's highlight never comes before its second of the file.
        for (i in 1 until shown.size) {
            val gap = shown[i].ms - shown[i - 1].ms
            assertTrue(gap >= 800, "clip ${i + 1} came $gap ms after clip $i, before its second of audio: ${log.marks}")
        }
        val end = log.marks.last()
        assertNull(end.highlight, "the highlight goes when the reading ends")
        assertTrue(end.ms - shown.last().ms >= 800, "the last clip was cut short: ${end.ms - shown.last().ms} ms")
        assertEquals(listOf(KiteLocation(0, 0), KiteLocation(0, 0)), shown.take(2).map { it.page }, "the first two clips read on the first page")
        assertEquals(KiteLocation(0, 1), runOnIdle { state.currentLocation }, "the viewer turned to the page of the third clip")
        assertEquals(listOf(note), runOnIdle { state.highlights }, "the app's own highlight stays")
    }

    @Test
    fun a_reading_starts_at_the_first_clip_on_the_page_the_reader_is_on() = runComposeUiTest {
        val state = KiteDocViewState(threeSentences(), initialPage = 1)
        val playing = mutableStateOf(false)
        val log = Log()
        read(state, playing, log)
        waitUntil(timeoutMillis = 10_000) { state.currentLocation == KiteLocation(0, 1) }
        playing.value = true
        waitUntil(timeoutMillis = 30_000) { log.finished }
        assertEquals(listOf("s3", null), log.clips.toList())
    }

    @Test
    fun a_paused_reading_holds_its_clip_and_goes_on_from_there() = runComposeUiTest {
        val playing = mutableStateOf(true)
        val log = Log()
        read(KiteDocViewState(threeSentences()), playing, log)
        waitUntil(timeoutMillis = 30_000) { players.isNotEmpty() && players[0].state.value.status.isActive }
        playing.value = false
        waitForIdle()
        val player = players.single()
        waitUntil(timeoutMillis = 10_000) { !player.state.value.status.isActive }
        val at = settledPosition(player)
        Thread.sleep(1_500)
        assertEquals(at, player.position(), "the position moved while paused")
        assertEquals(listOf("s1"), log.clips.toList(), "the reading moved on while paused")
        assertTrue(at.inWholeMilliseconds < 1_000, "paused at $at, past the first clip")

        playing.value = true
        waitUntil(timeoutMillis = 30_000) { log.finished }
        assertEquals(listOf("s1", "s2", "s3", null), log.clips.toList())
        assertEquals(1, players.size, "a pause keeps the player")
    }

    @Test
    fun a_clip_without_audio_or_with_audio_the_book_lacks_is_skipped() = runComposeUiTest {
        // The last clip reads from a second file, which the player opens in place of the first.
        val doc = book(
            listOf(
                Chapter(
                    """<p id="s1">One.</p><p id="s2">Two.</p><p id="s3">Three.</p><p id="s4">Four.</p>""",
                    par(1, "s1", "narration.wav", 0.0, 1.0) + par(1, "s2", "gone.wav", 0.0, 1.0) +
                        par(1, "s3", null, 0.0, 1.0) + par(1, "s4", "other.wav", 1.0, 2.0),
                ),
            ),
            audio + ("other.wav" to NarratedBooks.wav(2)),
        )
        val log = Log()
        read(KiteDocViewState(doc), mutableStateOf(true), log)
        waitUntil(timeoutMillis = 30_000) { log.finished }
        waitForIdle()
        assertEquals(listOf("s1", "s4", null), log.clips.toList())
        val shown = log.marks.filter { it.highlight != null }
        assertEquals(2, shown.size)
        assertTrue(log.marks.last().ms - shown.last().ms >= 800, "the clip of the second file did not play its second: ${log.marks}")
        assertEquals(1, players.size, "one player for both files")
    }

    @Test
    fun the_reading_goes_on_with_the_next_chapter_that_has_an_overlay() = runComposeUiTest {
        val doc = book(
            listOf(
                Chapter("""<p id="a">First chapter.</p>""", par(1, "a", "narration.wav", 0.0, 1.0)),
                Chapter("""<p>No narration here.</p>"""),
                Chapter("""<p id="b">Third chapter.</p>""", par(3, "b", "narration.wav", 1.0, 2.0)),
            ),
            audio,
        )
        val state = KiteDocViewState(doc)
        val log = Log()
        read(state, mutableStateOf(true), log)
        waitUntil(timeoutMillis = 30_000) { log.finished }
        waitForIdle()
        assertEquals(listOf("a", "b", null), log.clips.toList())
        assertEquals(
            listOf(KiteLocation(0, 0), KiteLocation(2, 0)),
            log.marks.mapNotNull { it.highlight?.location },
            "the highlight moved to the third chapter",
        )
        assertEquals(KiteLocation(2, 0), runOnIdle { state.currentLocation }, "the viewer turned to it")
    }

    @Test
    fun a_book_without_narration_ends_at_once_and_makes_no_player() = runComposeUiTest {
        val log = Log()
        read(KiteDocViewState(book(listOf(Chapter("<p>Silent.</p>")), emptyMap())), mutableStateOf(true), log)
        waitUntil(timeoutMillis = 10_000) { log.finished }
        assertEquals(listOf<String?>(null), log.clips.toList())
        assertTrue(players.isEmpty())
    }

    /**
     * Three sentences read two seconds each, then a second chapter of one, so a jump lands well
     * inside the first clip.
     */
    private fun twoChapters(): EpubDocument = book(
        listOf(
            Chapter(
                """<p id="a1">One.</p><p id="a2">Two.</p><p id="a3">Three.</p>""",
                par(1, "a1", "long.wav", 0.0, 2.0) + par(1, "a2", "long.wav", 2.0, 4.0) + par(1, "a3", "long.wav", 4.0, 6.0),
            ),
            Chapter("""<p id="b">Second chapter.</p>""", par(2, "b", "long.wav", 0.0, 2.0)),
        ),
        mapOf("long.wav" to NarratedBooks.wav(6)),
    )

    @Test
    fun a_jump_by_the_reader_restarts_the_reading_at_the_first_clip_there() = runComposeUiTest {
        // EPUB Media Overlays 3.3, navigation during playback: the reading goes on from the first
        // clip at or after the place the reader moves to (#524).
        val state = KiteDocViewState(twoChapters())
        val jump = mutableStateOf<KiteLocation?>(null)
        val log = Log()
        read(state, mutableStateOf(true), log, jump)
        waitUntil(timeoutMillis = 30_000) { "a1" in log.clips }
        jump.value = KiteLocation(1, 0)
        waitUntil(timeoutMillis = 30_000) { log.finished }
        waitForIdle()
        val clips = log.clips.toList()
        assertEquals("a1", clips.first())
        assertTrue("a3" !in clips, "the reading went on in the chapter the reader left: $clips")
        assertEquals(listOf("b", null), clips.takeLast(2), "the reading did not go on where the reader went: $clips")
        assertEquals(KiteLocation(1, 0), runOnIdle { state.currentLocation }, "the reading took the reader back")
    }

    @Test
    fun a_jump_while_paused_moves_the_reading_there() = runComposeUiTest {
        val state = KiteDocViewState(twoChapters())
        val playing = mutableStateOf(true)
        val jump = mutableStateOf<KiteLocation?>(null)
        val log = Log()
        read(state, playing, log, jump)
        waitUntil(timeoutMillis = 30_000) { "a1" in log.clips }
        playing.value = false
        jump.value = KiteLocation(1, 0)
        waitUntil(timeoutMillis = 10_000) { state.currentLocation == KiteLocation(1, 0) }
        Thread.sleep(2_500)
        assertTrue(log.clips.none { it == "a2" || it == "a3" }, "the reading moved on while paused: ${log.clips}")
        playing.value = true
        waitUntil(timeoutMillis = 30_000) { log.finished }
        assertEquals(listOf("a1", "b", null), log.clips.toList())
    }
}
