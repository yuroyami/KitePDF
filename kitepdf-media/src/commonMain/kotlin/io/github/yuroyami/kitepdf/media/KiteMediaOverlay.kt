package io.github.yuroyami.kitepdf.media

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.github.yuroyami.kitepdf.compose.KitePageOverlayScope
import io.github.yuroyami.kitepdf.epub.EpubDocument
import io.github.yuroyami.kitepdf.epub.EpubMedia
import io.github.yuroyami.kitepdf.epub.EpubMediaKind
import io.github.yuroyami.kitepdf.epub.EpubPage
import io.github.yuroyami.kiteplayer.KitePlayer
import io.github.yuroyami.kiteplayer.KitePlayerPlatform
import io.github.yuroyami.kiteplayer.LoopMode
import io.github.yuroyami.kiteplayer.PlaybackStatus
import io.github.yuroyami.kiteplayer.compose.KitePlayerVideo
import io.github.yuroyami.kiteplayer.compose.KiteRenderPath
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlin.time.Duration

/**
 * Plays the `<video>` and `<audio>` elements of an EPUB page over the page (#31). Pass it as the
 * viewer's page overlay:
 *
 * ```kotlin
 * KiteDocView(state, pageOverlay = { KiteMediaOverlay() })
 * ```
 *
 * Each element gets an [EpubMediaPlayer] on its box, so it moves and scales with the page. A page
 * that is not an EPUB page, or has no media, draws nothing. An element starts no player until it
 * starts to play, so a page of posters costs nothing.
 *
 * @param allowRemote plays a source that the book names by a URL, such as `https://`. False by
 *   default, because such a source tells its server that the book was opened.
 * @param newPlayer makes the player of an element when it starts, or returns null where the platform
 *   cannot play. The default player plays through FFmpeg and the platform's audio output; pass
 *   `{ KitePlayerPlatform.createOrNull(PlayerConfig(...)) }` for settings of your own.
 */
@Composable
public fun KitePageOverlayScope.KiteMediaOverlay(
    allowRemote: Boolean = false,
    newPlayer: () -> KitePlayer? = { KitePlayerPlatform.createOrNull() },
) {
    val epubPage = page as? EpubPage ?: return
    val media = remember(epubPage) { epubPage.media }
    media.forEachIndexed { index, element ->
        key(epubPage, index) {
            EpubMediaPlayer(
                media = element,
                document = epubPage.document,
                modifier = Modifier.displayRect(element.rect),
                allowRemote = allowRemote,
                newPlayer = newPlayer,
            )
        }
    }
}

/**
 * A player for one media element of an EPUB page, drawn over the poster that the page paints at
 * [EpubMedia.rect] (HTML, 4.8.11).
 *
 * Before it starts, it draws a play button, and a tap starts it. A video then plays in the box, and
 * an audio element shows a transport bar in its own. With [EpubMedia.controls], a video shows that
 * bar along its bottom too, and a tap pauses and plays again. An element with
 * [EpubMedia.autoplay] starts by itself, muted, since browsers let only muted media start unasked;
 * the first touch of its controls turns the sound on. [EpubMedia.muted] mutes it from the start. [EpubMedia.loop] plays it again from the start when it
 * ends. The player plays the first source it can, in the element's order, and when it can play none,
 * the poster stays and the button goes.
 *
 * The player is closed when this leaves the composition, so a page that scrolls away stops.
 *
 * @param allowRemote plays a source that the book names by a URL. See [KiteMediaOverlay].
 * @param newPlayer makes the player when the element starts. See [KiteMediaOverlay].
 */
@Composable
public fun EpubMediaPlayer(
    media: EpubMedia,
    document: EpubDocument,
    modifier: Modifier = Modifier,
    allowRemote: Boolean = false,
    newPlayer: () -> KitePlayer? = { KitePlayerPlatform.createOrNull() },
) {
    val scope = rememberCoroutineScope()
    val currentNewPlayer by rememberUpdatedState(newPlayer)
    var player by remember(media) { mutableStateOf<KitePlayer?>(null) }
    var unplayable by remember(media) { mutableStateOf(false) }

    fun start(byAutoplay: Boolean) {
        if (player != null || unplayable) return
        // Null, or a failure, on a platform without a player stack, such as a desktop with no audio output.
        val created = try {
            currentNewPlayer()
        } catch (failure: Exception) {
            null
        }
        if (created == null) {
            unplayable = true
            return
        }
        player = created
        created.setMuted(media.muted || byAutoplay)
        created.setLoop(if (media.loop) LoopMode.One else LoopMode.Off)
        scope.launch {
            // A player that fails in a way of its own leaves the poster, never a crash of the app.
            val opened = try {
                created.openFirstPlayable(mediaItems(media, document, allowRemote))
            } catch (failure: CancellationException) {
                throw failure
            } catch (failure: Exception) {
                null
            }
            if (opened == null) {
                unplayable = true
                // Dropping it closes it, through the effect below.
                player = null
            } else {
                created.play()
            }
        }
    }

    LaunchedEffect(media) {
        if (media.autoplay) start(byAutoplay = true)
    }
    val open = player
    DisposableEffect(open) {
        onDispose { open?.close() }
    }

    Box(modifier) {
        if (open != null && !unplayable) {
            Playing(open, media, scope, unmuteOnTouch = media.autoplay && !media.muted)
        } else if (!unplayable) {
            PlayButton(Modifier.fillMaxSize()) { start(byAutoplay = false) }
        }
    }
}

/**
 * The element while its player exists: the video, and the transport bar where the element shows one.
 * With [unmuteOnTouch], the first touch of a control turns on the sound that autoplay muted, and the
 * media goes on playing.
 */
@Composable
private fun Playing(player: KitePlayer, media: EpubMedia, scope: CoroutineScope, unmuteOnTouch: Boolean) {
    val snapshot by player.state.collectAsState()
    val progress by player.progress.collectAsState()
    val active = snapshot.status.isActive
    val toggle: () -> Unit = {
        when {
            unmuteOnTouch && snapshot.muted && active -> player.setMuted(false)
            active -> player.pause()
            snapshot.status == PlaybackStatus.Ended -> scope.launch {
                runCatching { player.seek(Duration.ZERO) }
                player.play()
            }
            else -> player.play()
        }
    }
    if (media.kind == EpubMediaKind.VIDEO) {
        Box(Modifier.fillMaxSize()) {
            // Compose draws the frames, so the video clips, scrolls and zooms with the page, and the
            // controls over it take clicks: over a native view, macOS sends a click to the view.
            KitePlayerVideo(player, Modifier.fillMaxSize(), path = KiteRenderPath.ComposeCanvas)
            val tap = if (media.controls) Modifier.clickable(onClickLabel = if (active) "Pause" else "Play", onClick = toggle) else Modifier
            Box(Modifier.fillMaxSize().then(tap))
            if (media.controls) {
                TransportBar(
                    active, progress.position, snapshot.duration, toggle,
                    Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(TRANSPORT_HEIGHT),
                )
            }
        }
    } else {
        TransportBar(active, progress.position, snapshot.duration, toggle, Modifier.fillMaxSize())
    }
}

/** A round play button in the middle of the box, as a browser draws over a video that has not started. */
@Composable
private fun PlayButton(modifier: Modifier, onClick: () -> Unit) {
    Box(
        modifier
            .semantics { contentDescription = "Play" }
            .clickable(role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.size(PLAY_BUTTON_SIZE)) {
            drawCircle(SCRIM)
            drawPlayGlyph(Offset(size.width * 0.38f, size.height * 0.28f), size.minDimension * 0.44f)
        }
    }
}

/** A play or pause toggle, then a bar of how far [position] is into [duration]. */
@Composable
private fun TransportBar(active: Boolean, position: Duration, duration: Duration?, toggle: () -> Unit, modifier: Modifier) {
    Row(modifier.background(SCRIM), verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier
                .fillMaxHeight()
                .padding(horizontal = 8.dp)
                .semantics { contentDescription = if (active) "Pause" else "Play" }
                .clickable(role = Role.Button, onClick = toggle),
            contentAlignment = Alignment.Center,
        ) {
            Canvas(Modifier.size(GLYPH_SIZE)) {
                if (active) drawPauseGlyph() else drawPlayGlyph(Offset(size.width * 0.15f, 0f), size.minDimension)
            }
        }
        val fraction = if (duration != null && duration > Duration.ZERO) (position / duration).toFloat().coerceIn(0f, 1f) else 0f
        Canvas(Modifier.weight(1f).height(GLYPH_SIZE).padding(end = 12.dp)) {
            val y = size.height / 2f
            val h = 4.dp.toPx()
            drawRoundRect(TRACK, Offset(0f, y - h / 2f), Size(size.width, h), CornerRadius(h / 2f))
            drawRoundRect(Color.White, Offset(0f, y - h / 2f), Size(size.width * fraction, h), CornerRadius(h / 2f))
        }
    }
}

/** A white play triangle of [height], its left edge at [topLeft]. */
private fun DrawScope.drawPlayGlyph(topLeft: Offset, height: Float) {
    val path = Path().apply {
        moveTo(topLeft.x, topLeft.y)
        lineTo(topLeft.x, topLeft.y + height)
        lineTo(topLeft.x + height * 0.85f, topLeft.y + height / 2f)
        close()
    }
    drawPath(path, Color.White)
}

/** Two white pause bars filling the canvas. */
private fun DrawScope.drawPauseGlyph() {
    val bar = size.width * 0.3f
    drawRect(Color.White, Offset(size.width * 0.12f, 0f), Size(bar, size.height))
    drawRect(Color.White, Offset(size.width * 0.58f, 0f), Size(bar, size.height))
}

/** The dark of the controls over the poster, and the unplayed part of the bar. */
private val SCRIM = Color(0x99000000)
private val TRACK = Color(0x66FFFFFF)

private val PLAY_BUTTON_SIZE = 56.dp
private val GLYPH_SIZE = 18.dp
private val TRANSPORT_HEIGHT = 40.dp
