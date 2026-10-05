package io.github.yuroyami.kitepdf.media

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
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
import androidx.compose.ui.window.Dialog
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Plays the `<video>` and `<audio>` elements of an EPUB page over the page (#31). Pass it as the
 * viewer's page overlay:
 *
 * ```kotlin
 * KiteDocView(state, pageOverlay = { KiteMediaOverlay() })
 * ```
 *
 * Each element gets an [EpubMediaPlayer] on its box, so it moves and scales with the page, and the
 * bar of a video can take it full screen. A page that is not an EPUB page, or has no media, draws
 * nothing. An element starts no player until it starts to play, so a page of posters costs nothing.
 *
 * @param allowRemote plays a source that the book names by an `https` URL. False by default,
 *   because such a source tells its server that the book was opened. A source of any other
 *   scheme never plays, `http` and `file` included (EPUB Reading Systems 3.3, 3.3 and 3.5).
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
 * bar along its bottom too, and a tap pauses and plays again. Without it, a tap still pauses and
 * plays again, and shows the bar until the video has played on for a few seconds, since a reader
 * must be able to stop what moves (#480). An element with
 * [EpubMedia.autoplay] starts by itself, muted, since browsers let only muted media start unasked;
 * the first touch of its controls, or of a video without them, turns the sound on. [EpubMedia.muted] mutes it from the start. [EpubMedia.loop] plays it again from the start when it
 * ends. The player plays the first source it can, in the element's order, and when it can play none,
 * the poster stays and the button goes.
 *
 * The bar of a video has a full-screen button (#482). Full screen shows the same player over the
 * whole window, in a [Dialog], so playback goes on without a break, and the button, a back gesture
 * or Escape leaves it. While it is up, the box on the page shows what the page paints there, the
 * poster where the element has one. On Android, full screen also hides the system bars, and a
 * landscape video turns the screen to landscape when the activity handles orientation changes
 * itself, since a recreated activity would close the player. On iOS 16 and later, a landscape
 * video asks the window scene for landscape. Both turn back to the orientation they found when
 * full screen ends.
 *
 * The player is closed when this leaves the composition, so a page that scrolls away stops.
 *
 * @param allowRemote plays a source that the book names by an `https` URL. See [KiteMediaOverlay].
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
    var fullScreen by remember(open) { mutableStateOf(false) }

    Box(modifier) {
        if (open != null && !unplayable) {
            Playing(
                open, media, scope,
                unmuteOnTouch = media.autoplay && !media.muted,
                fullScreen = fullScreen,
                onFullScreen = { fullScreen = it },
            )
        } else if (!unplayable) {
            PlayButton(Modifier.fillMaxSize()) { start(byAutoplay = false) }
        }
    }
}

/**
 * The element while its player exists: the video, and the transport bar where the element shows one,
 * or for a moment after a tap on a video that shows none. With [unmuteOnTouch], the first touch of a control turns on the sound that autoplay muted, and the
 * media goes on playing. With [fullScreen], the video and its bar show over the whole window instead
 * of on the box, and [onFullScreen] switches between the two.
 */
@Composable
private fun Playing(
    player: KitePlayer,
    media: EpubMedia,
    scope: CoroutineScope,
    unmuteOnTouch: Boolean,
    fullScreen: Boolean,
    onFullScreen: (Boolean) -> Unit,
) {
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
    if (media.kind != EpubMediaKind.VIDEO) {
        TransportBar(active, progress.position, snapshot.duration, toggle, Modifier.fillMaxSize())
        return
    }
    // HTML, 4.8.11 lets a reading system offer controls the element does not ask for, and WCAG 2.2,
    // 2.2.2 asks for a way to pause what moves. A tap shows the bar while the video stays paused,
    // and for a few seconds once it plays, so a decoration does not carry a bar for good (#480).
    var revealed by remember(player) { mutableStateOf(false) }
    var touches by remember(player) { mutableIntStateOf(0) }
    LaunchedEffect(revealed, touches, active) {
        if (revealed && active) {
            delay(REVEAL_TIME)
            revealed = false
        }
    }
    val touched: () -> Unit = {
        toggle()
        if (!media.controls) {
            revealed = true
            touches++
        }
    }
    val video = @Composable {
        Box(Modifier.fillMaxSize()) {
            // Compose draws the frames, so the video clips, scrolls and zooms with the page, and the
            // controls over it take clicks: over a native view, macOS sends a click to the view.
            KitePlayerVideo(player, Modifier.fillMaxSize(), path = KiteRenderPath.ComposeCanvas)
            Box(Modifier.fillMaxSize().clickable(onClickLabel = if (active) "Pause" else "Play", onClick = touched))
            if (media.controls || revealed) {
                // Over the whole screen, the bar keeps clear of a notch, a cutout and the home indicator.
                val clear = if (fullScreen) Modifier.windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom)) else Modifier
                TransportBar(
                    active, progress.position, snapshot.duration, touched,
                    Modifier.align(Alignment.BottomCenter).fillMaxWidth().then(clear).height(TRANSPORT_HEIGHT),
                    fullScreen = fullScreen,
                    onFullScreen = { onFullScreen(!fullScreen) },
                )
            }
        }
    }
    if (!fullScreen) {
        video()
        return
    }
    // The video leaves the box and composes in the layer, so one renderer at a time is attached
    // to the player: the box's detaches as it goes, and the layer's attaches a frame later. The
    // engine keeps playing between the two, and the box shows what the page paints there.
    val shape = snapshot.videoSize?.displayAspect?.takeIf { it > 0f } ?: media.rect.let { (abs(it.right - it.left) / abs(it.top - it.bottom)).toFloat() }
    Dialog(onDismissRequest = { onFullScreen(false) }, properties = fullScreenDialogProperties()) {
        FullScreenWindow(landscape = shape > 1f)
        Box(Modifier.fillMaxSize().background(Color.Black)) { video() }
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

/**
 * A play or pause toggle, then a bar of how far [position] is into [duration]. A video's bar ends with
 * a button that enters full screen, or leaves it while [fullScreen] is true. Null leaves it out.
 */
@Composable
private fun TransportBar(
    active: Boolean,
    position: Duration,
    duration: Duration?,
    toggle: () -> Unit,
    modifier: Modifier,
    fullScreen: Boolean? = null,
    onFullScreen: () -> Unit = {},
) {
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
        Canvas(Modifier.weight(1f).height(GLYPH_SIZE).padding(end = if (fullScreen == null) 12.dp else 4.dp)) {
            val y = size.height / 2f
            val h = 4.dp.toPx()
            drawRoundRect(TRACK, Offset(0f, y - h / 2f), Size(size.width, h), CornerRadius(h / 2f))
            drawRoundRect(Color.White, Offset(0f, y - h / 2f), Size(size.width * fraction, h), CornerRadius(h / 2f))
        }
        if (fullScreen != null) {
            Box(
                Modifier
                    .fillMaxHeight()
                    .padding(horizontal = 8.dp)
                    .semantics { contentDescription = if (fullScreen) "Exit full screen" else "Full screen" }
                    .clickable(role = Role.Button, onClick = onFullScreen),
                contentAlignment = Alignment.Center,
            ) {
                Canvas(Modifier.size(GLYPH_SIZE)) { drawFullScreenGlyph(inward = fullScreen) }
            }
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

/**
 * Four white corners of a square: pointing out to enter full screen, and with [inward], pointing in
 * to leave it, as players draw the two.
 */
private fun DrawScope.drawFullScreenGlyph(inward: Boolean) {
    val stroke = size.minDimension * 0.14f
    val arm = size.minDimension * 0.36f
    val w = size.width
    val h = size.height
    for ((cx, cy) in listOf(0f to 0f, w to 0f, 0f to h, w to h)) {
        // Outward, each corner is an L whose tip sits in the canvas corner. Inward, the tip sits an
        // arm's length in from it, and the arms point back to the edges.
        val dx = if (cx == 0f) 1f else -1f
        val dy = if (cy == 0f) 1f else -1f
        val tipX = if (inward) cx + dx * arm else cx
        val tipY = if (inward) cy + dy * arm else cy
        val armX = if (inward) -dx else dx
        val armY = if (inward) -dy else dy
        drawLine(Color.White, Offset(tipX, tipY), Offset(tipX + armX * arm, tipY), stroke)
        drawLine(Color.White, Offset(tipX, tipY), Offset(tipX, tipY + armY * arm), stroke)
    }
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

/** How long the bar that a tap showed on a video without controls stays once the video plays. */
private val REVEAL_TIME = 3.seconds
