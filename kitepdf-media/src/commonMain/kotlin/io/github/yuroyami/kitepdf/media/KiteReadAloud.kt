package io.github.yuroyami.kitepdf.media

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.graphics.Color
import io.github.yuroyami.kitepdf.compose.KiteDocViewState
import io.github.yuroyami.kitepdf.compose.KiteHighlight
import io.github.yuroyami.kitepdf.core.KiteLocation
import io.github.yuroyami.kitepdf.core.KiteSearchHit
import io.github.yuroyami.kitepdf.epub.EpubDocument
import io.github.yuroyami.kitepdf.epub.EpubFragmentBox
import io.github.yuroyami.kitepdf.epub.EpubOverlayClip
import io.github.yuroyami.kiteplayer.KitePlayer
import io.github.yuroyami.kiteplayer.KitePlayerPlatform
import io.github.yuroyami.kiteplayer.PlaybackStatus
import io.github.yuroyami.kiteplayer.SeekMode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Reads an EPUB aloud with the narration it ships, its media overlays, and follows the text in the
 * viewer of [state] (#36). Place it next to the viewer, and switch [playing] from your own controls:
 *
 * ```kotlin
 * val state = rememberKiteDocViewState(book)
 * var playing by remember { mutableStateOf(false) }
 * KiteDocView(state)
 * KiteReadAloud(state, playing, onFinished = { playing = false })
 * ```
 *
 * The first time [playing] is true, it reads from the first clip whose text is on the reader's page
 * or after it, in that chapter or the next one with an overlay. It plays each clip's audio out of
 * the book, from the clip's `clipBegin` to its `clipEnd`, or to the end of the file without one
 * (EPUB Reading Systems 3.3, 9.2.2). When a chapter's overlay ends, it goes on with the next
 * chapter that has one (9.1). False pauses it, and true again goes on from there.
 *
 * The text of the clip being read gets one entry in [KiteDocViewState.highlights], with the id
 * [READ_ALOUD_HIGHLIGHT_ID], next to the app's own entries, and the viewer turns to the page of
 * that text when the reading reaches another page. The book's `media:active-class` is shown as
 * that highlight and never added to the element, and `media:playback-active-class` is not added
 * either: a class could change the element's style, and a new style lays the chapter out again,
 * where 9.2.3 adds both. Assigning [KiteDocViewState.highlights] while it reads drops the entry
 * until the next clip.
 *
 * A clip without audio, or whose audio the player cannot open, is skipped. Leaving the
 * composition stops the reading, closes the player and takes the highlight away. On a viewer
 * whose document is not an [EpubDocument], it does nothing.
 *
 * @param state the viewer that shows the book, and whose highlights and page follow the reading.
 * @param playing reads while true, and pauses while false.
 * @param color the fill of the highlight. Null paints the viewer's search highlight colour, as a
 *   [KiteHighlight] without a colour does.
 * @param newPlayer makes the player when the reading starts, or returns null where the platform
 *   cannot play, which ends the reading at once. See [KiteMediaOverlay].
 * @param onClip called with each clip as its reading starts, and with null when the reading ends.
 * @param onFinished called when the reading ends: past the last clip of the book, or at once for a
 *   book without narration from the reader's page on, or without a player. It is not called when
 *   the reading leaves the composition.
 */
@Composable
public fun KiteReadAloud(
    state: KiteDocViewState,
    playing: Boolean,
    color: Color? = null,
    newPlayer: () -> KitePlayer? = { KitePlayerPlatform.createOrNull() },
    onClip: (EpubOverlayClip?) -> Unit = {},
    onFinished: () -> Unit = {},
) {
    val book = state.document as? EpubDocument ?: return
    val scope = rememberCoroutineScope()
    val currentColor by rememberUpdatedState(color)
    val currentNewPlayer by rememberUpdatedState(newPlayer)
    val currentOnClip by rememberUpdatedState(onClip)
    val currentOnFinished by rememberUpdatedState(onFinished)
    val reading = remember(state, book) {
        ReadAloud(
            view = state,
            book = book,
            scope = scope,
            color = { currentColor },
            newPlayer = { currentNewPlayer() },
            onClip = { currentOnClip(it) },
            onFinished = { currentOnFinished() },
        )
    }
    DisposableEffect(reading) {
        onDispose { reading.close() }
    }
    LaunchedEffect(reading, playing) {
        if (playing) reading.play() else reading.pause()
    }
}

/** The id of the [KiteHighlight] that [KiteReadAloud] keeps on the text it reads. */
public const val READ_ALOUD_HIGHLIGHT_ID: String = "kitepdf.read-aloud"

/**
 * One reading of [KiteReadAloud]: its player, the clip it reads, and the highlight of that clip.
 * Everything but the waits runs on the thread of [scope], the composition's, so [play] and [pause]
 * never race the reading. The waits for the audio, the overlay parse and the layout behind
 * [EpubDocument.locateFragment] run on [Dispatchers.Default].
 */
internal class ReadAloud(
    private val view: KiteDocViewState,
    private val book: EpubDocument,
    private val scope: CoroutineScope,
    private val color: () -> Color?,
    private val newPlayer: () -> KitePlayer?,
    private val onClip: (EpubOverlayClip?) -> Unit,
    private val onFinished: () -> Unit,
) {
    private var wanted = false
    private var reading: Job? = null
    private var player: KitePlayer? = null

    /** True while the player has the current clip's audio open at the clip, so play and pause apply to it. */
    private var placed = false

    /** The page of the last clip shown, so the viewer turns once for each new page and not for each clip. */
    private var shownPage: KiteLocation? = null
    private var turn: Job? = null

    fun play() {
        wanted = true
        if (reading == null) reading = scope.launch { read() } else if (placed) player?.follow(true)
    }

    fun pause() {
        wanted = false
        if (placed) player?.follow(false)
    }

    fun close() {
        reading?.cancel()
        reading = null
        turn?.cancel()
        player?.close()
        player = null
        placed = false
        unmark()
    }

    private suspend fun read() {
        val here = view.currentLocation
        val start = withContext(Dispatchers.Default) { startAt(here) }
        val made = if (start == null) null else try {
            newPlayer()
        } catch (failure: Exception) {
            // As in EpubMediaPlayer: a platform without a player stack fails here.
            null
        }
        if (start != null && made != null) {
            player = made
            readFrom(made, start.first, start.second)
        }
        player?.close()
        player = null
        placed = false
        reading = null
        unmark()
        onClip(null)
        onFinished()
    }

    /** Reads every clip from [firstClip] of [firstChapter] to the end of the book's narration. */
    private suspend fun readFrom(player: KitePlayer, firstChapter: Int, firstClip: Int) {
        var chapter = firstChapter
        var index = firstClip
        // The audio the player has open, and where in it the last clip ended, null at the end of the file.
        var audio: String? = null
        var end: Double? = null
        val unplayable = HashSet<String>()
        while (true) {
            val clips = withContext(Dispatchers.Default) { clipsOf(chapter) }
            while (index < clips.size) {
                val clip = clips[index++]
                val href = clip.audioHref ?: continue
                if (href in unplayable) continue
                try {
                    if (href != audio) {
                        val item = withContext(Dispatchers.Default) { bookItem(book, href) }
                        if (item == null) {
                            unplayable += href
                            continue
                        }
                        placed = false
                        audio = null
                        // The player opens only from Idle, Ended or Failed.
                        if (player.state.value.status !in OPENABLE) player.stop()
                        player.open(item)
                        audio = href
                        end = null
                    }
                    // A clip that starts where the last one ended plays on without a seek, which would
                    // break the sound between them.
                    val from = end
                    if (from == null || abs(clip.clipBegin - from) > CONTIGUOUS) {
                        placed = false
                        player.seek(clip.clipBegin.seconds, SeekMode.Precise)
                    }
                    show(clip)
                    placed = true
                    player.follow(wanted)
                    withContext(Dispatchers.Default) { player.awaitPosition(clip.clipEnd?.seconds) }
                    end = clip.clipEnd
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: Exception) {
                    // A clip the player cannot place or play is skipped, and the next one opens its audio again.
                    if (audio == null) unplayable += href
                    audio = null
                    end = null
                }
            }
            chapter = withContext(Dispatchers.Default) { nextNarrated(chapter) } ?: return
            index = 0
        }
    }

    /** Highlights [clip]'s text, and turns the viewer to its page when the reading reaches a new page. */
    private suspend fun show(clip: EpubOverlayClip) {
        val box = withContext(Dispatchers.Default) { locate(clip.textHref) }
        if (box == null || box.rects.isEmpty()) {
            unmark()
        } else {
            val mark = KiteHighlight(KiteSearchHit(box.location, box.rects, ""), color = color(), id = READ_ALOUD_HIGHLIGHT_ID)
            view.highlights = view.highlights.filterNot { it.id == READ_ALOUD_HIGHLIGHT_ID } + mark
        }
        onClip(clip)
        val page = box?.location ?: return
        if (page == shownPage) return
        shownPage = page
        if (view.currentLocation != page) {
            turn?.cancel()
            turn = scope.launch { view.scrollTo(page, animate = true) }
        }
    }

    private fun unmark() {
        if (view.highlights.any { it.id == READ_ALOUD_HIGHLIGHT_ID }) {
            view.highlights = view.highlights.filterNot { it.id == READ_ALOUD_HIGHLIGHT_ID }
        }
    }

    /**
     * The chapter and the clip to read from: the first clip whose text is on [here] or after it,
     * else the first clip of the next chapter with an overlay. Null when no narration follows.
     */
    private fun startAt(here: KiteLocation): Pair<Int, Int>? {
        val at = clipsOf(here.chapter).indexOfFirst { clip ->
            val where = locate(clip.textHref)?.location
            where != null && where.chapter == here.chapter && where.page >= here.page
        }
        if (at >= 0) return here.chapter to at
        return nextNarrated(here.chapter)?.let { it to 0 }
    }

    /** Where [href]'s text is, or null, also when its chapter cannot be laid out: the clip then plays without a highlight. */
    private fun locate(href: String): EpubFragmentBox? = try {
        book.locateFragment(href)
    } catch (failure: Exception) {
        null
    }

    private fun nextNarrated(after: Int): Int? = (after + 1 until book.chapterCount).firstOrNull { clipsOf(it).isNotEmpty() }

    private fun clipsOf(chapter: Int): List<EpubOverlayClip> =
        if (chapter in 0 until book.chapterCount) book.mediaOverlayOf(chapter)?.clips.orEmpty() else emptyList()

    private companion object {
        /** How far apart, in seconds, one clip's end and the next one's start may be and still play on. */
        const val CONTIGUOUS = 0.05

        val OPENABLE = setOf(PlaybackStatus.Idle, PlaybackStatus.Ended, PlaybackStatus.Failed)
    }
}

/** Plays when [play] is true, pauses when false. A player that cannot, such as one with nothing open, stays as it is. */
private fun KitePlayer.follow(play: Boolean) {
    val active = state.value.status.isActive
    try {
        if (play && !active) play() else if (!play && active) pause()
    } catch (failure: IllegalStateException) {
        // Closed, or nothing open.
    }
}

/**
 * Returns once the position reaches [end], or once the audio ends or fails. Without [end], once
 * the audio ends. While the player is paused or buffering, it waits for that to change.
 */
private suspend fun KitePlayer.awaitPosition(end: Duration?) {
    while (true) {
        val snapshot = state.value
        val status = snapshot.status
        if (status == PlaybackStatus.Ended || status == PlaybackStatus.Failed || status == PlaybackStatus.Idle) return
        if (status == PlaybackStatus.Playing && end != null) {
            val left = end - position()
            if (left <= Duration.ZERO) return
            // At most a poll apart, so a change of speed or a seek by someone else is seen in time.
            val speed = snapshot.speed.takeIf { it > 0.0 } ?: 1.0
            delay((left / speed).coerceAtMost(POLL))
        } else {
            state.first { it.status != status }
        }
    }
}

private val POLL = 100.milliseconds
