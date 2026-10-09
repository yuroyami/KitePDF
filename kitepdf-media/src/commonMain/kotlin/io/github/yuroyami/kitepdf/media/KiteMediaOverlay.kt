package io.github.yuroyami.kitepdf.media

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.text.BasicText
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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.Popup
import io.github.yuroyami.kitepdf.compose.KitePageOverlayScope
import io.github.yuroyami.kitepdf.epub.EpubDocument
import io.github.yuroyami.kitepdf.epub.EpubMedia
import io.github.yuroyami.kitepdf.epub.EpubMediaKind
import io.github.yuroyami.kitepdf.epub.EpubPage
import io.github.yuroyami.kiteplayer.KitePlayer
import io.github.yuroyami.kiteplayer.LoopMode
import io.github.yuroyami.kiteplayer.PlaybackStatus
import io.github.yuroyami.kiteplayer.SeekMode
import io.github.yuroyami.kiteplayer.TrackId
import io.github.yuroyami.kiteplayer.TrackKind
import io.github.yuroyami.kiteplayer.Tracks
import io.github.yuroyami.kiteplayer.compose.KitePlayerVideo
import io.github.yuroyami.kiteplayer.session.BackgroundPolicy
import io.github.yuroyami.kiteplayer.compose.KiteRenderPath
import io.github.yuroyami.kiteplayer.isAvailable
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
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
 * The elements share [session]: an element whose page comes back shows the place where it
 * stopped, paused, and one element plays at a time.
 *
 * @param allowRemote plays a source that the book names by an `https` URL. False by default,
 *   because such a source tells its server that the book was opened. A source of any other
 *   scheme never plays, `http` and `file` included (EPUB Reading Systems 3.3, 3.3 and 3.5).
 * @param newPlayer makes the player of an element when it starts, or returns null where the platform
 *   cannot play. The default player plays through FFmpeg and the platform's audio output; pass
 *   `{ if (KitePlayer.isAvailable) KitePlayer(PlayerConfig(...)) else null }` for settings of your own.
 * @param labels the words of the controls, which a screen reader says. English by default.
 * @param session what the elements share. See [KiteMediaSession].
 */
@Composable
public fun KitePageOverlayScope.KiteMediaOverlay(
    allowRemote: Boolean = false,
    newPlayer: () -> KitePlayer? = { if (KitePlayer.isAvailable) KitePlayer() else null },
    labels: KiteMediaLabels = KiteMediaLabels(),
    session: KiteMediaSession = KiteMediaSession.Default,
) {
    val epubPage = page as? EpubPage ?: return
    val media = remember(epubPage) { epubPage.media }
    // One opened book: its metadata is one object for every document over the same parse, so a new
    // font size keeps the places, and opening the book again starts with none.
    val book = epubPage.document.epubMetadata.hashCode()
    media.forEachIndexed { index, element ->
        key(epubPage, index) {
            // The chapter and the element name the place, not the page object, which a new layout replaces.
            val name = element.id ?: element.sources.firstOrNull()?.href.orEmpty()
            val twin = media.subList(0, index).count { (it.id ?: it.sources.firstOrNull()?.href.orEmpty()) == name }
            EpubMediaPlayer(
                media = element,
                document = epubPage.document,
                modifier = Modifier.displayRect(element.rect),
                allowRemote = allowRemote,
                newPlayer = newPlayer,
                labels = labels,
                session = session,
                place = "$book|${epubPage.chapter}|$name|$twin",
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
 * The bar shows the elapsed and the total time around a line of how far playback has gone. A tap
 * on the line seeks there and a drag scrubs, and a screen reader moves it as a slider (#479). The
 * bar ends with a mute button and a menu of speeds from 0.5 to 2, which keep the pitch (#484).
 * Where the box is too narrow, the times and then the menus leave the bar.
 *
 * The subtitle and caption tracks of the element load with it, and the one it marks `default`
 * shows over the video at once. A menu on the bar picks another track or none (#483).
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
 * The player is closed when this leaves the composition, so a page that scrolls away stops. With
 * a [place], [session] keeps where it stopped, and the element shows that place, paused, when it
 * comes back. Starting it pauses any other element of [session] that plays. When the app leaves
 * the screen, a video pauses and an audio element follows [KiteMediaSession.audioInBackground].
 *
 * @param allowRemote plays a source that the book names by an `https` URL. See [KiteMediaOverlay].
 * @param newPlayer makes the player when the element starts. See [KiteMediaOverlay].
 * @param labels the words of the controls. See [KiteMediaOverlay].
 * @param session what the elements share. See [KiteMediaSession].
 * @param place a name for the element that stays the same when its chapter is laid out again,
 *   under which [session] keeps where it stopped. Null keeps no place.
 */
@Composable
public fun EpubMediaPlayer(
    media: EpubMedia,
    document: EpubDocument,
    modifier: Modifier = Modifier,
    allowRemote: Boolean = false,
    newPlayer: () -> KitePlayer? = { if (KitePlayer.isAvailable) KitePlayer() else null },
    labels: KiteMediaLabels = KiteMediaLabels(),
    session: KiteMediaSession = KiteMediaSession.Default,
    place: String? = null,
) {
    val scope = rememberCoroutineScope()
    val currentNewPlayer by rememberUpdatedState(newPlayer)
    var player by remember(media) { mutableStateOf<KitePlayer?>(null) }
    var unplayable by remember(media) { mutableStateOf(false) }

    /** Opens the element, at the place the session kept for it, and plays it unless [paused]. */
    fun start(byAutoplay: Boolean, paused: Boolean = false) {
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
        val kept = place?.let(session::placeOf)
        created.setMuted(kept?.muted ?: (media.muted || byAutoplay))
        created.setLoop(if (media.loop) LoopMode.One else LoopMode.Off)
        kept?.let { runCatching { created.setSpeed(it.speed) } }
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
                if (kept != null && kept.position > Duration.ZERO) runCatching { created.seek(kept.position) }
                if (!paused) created.play()
            }
        }
    }

    LaunchedEffect(media) {
        when {
            // An element the reader played shows where it stopped, paused, and autoplay does not start it again.
            place != null && session.placeOf(place) != null -> start(byAutoplay = false, paused = true)
            media.autoplay -> start(byAutoplay = true)
        }
    }
    val open = player
    DisposableEffect(open) {
        if (open != null) session.register(open)
        onDispose {
            if (open != null) {
                session.unregister(open)
                if (place != null) session.keep(place, placeToKeep(open))
                open.close()
            }
        }
    }
    if (open != null) {
        LaunchedEffect(open) {
            open.state.map { it.status.isActive }.distinctUntilChanged().collect { active -> if (active) session.started(open) }
        }
        BackgroundHandling(open, if (media.kind == EpubMediaKind.VIDEO) BackgroundPolicy.PauseAll else session.audioInBackground)
    }
    var fullScreen by remember(open) { mutableStateOf(false) }

    Box(modifier) {
        if (open != null && !unplayable) {
            Playing(
                open, media, scope, labels,
                unmuteOnTouch = media.autoplay && !media.muted,
                fullScreen = fullScreen,
                onFullScreen = { fullScreen = it },
            )
        } else if (!unplayable) {
            PlayButton(labels.play, Modifier.fillMaxSize()) { start(byAutoplay = false) }
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
    labels: KiteMediaLabels,
    unmuteOnTouch: Boolean,
    fullScreen: Boolean,
    onFullScreen: (Boolean) -> Unit,
) {
    val snapshot by player.state.collectAsState()
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
        TransportBar(player, scope, labels, toggle, Modifier.fillMaxSize())
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
            Box(Modifier.fillMaxSize().clickable(onClickLabel = if (active) labels.pause else labels.play, onClick = touched))
            if (media.controls || revealed) {
                // Over the whole screen, the bar keeps clear of a notch, a cutout and the home indicator.
                val clear = if (fullScreen) Modifier.windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom)) else Modifier
                TransportBar(
                    player, scope, labels, touched,
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

/** Where [player] stopped, to show again, or null for one that never opened or that played to its end. */
private fun placeToKeep(player: KitePlayer): KiteMediaSession.Place? {
    val snapshot = player.state.value
    if (snapshot.status == PlaybackStatus.Idle || snapshot.status == PlaybackStatus.Failed || snapshot.status == PlaybackStatus.Ended) return null
    return KiteMediaSession.Place(player.position(), snapshot.muted, snapshot.speed)
}

/** A round play button in the middle of the box, as a browser draws over a video that has not started. */
@Composable
private fun PlayButton(label: String, modifier: Modifier, onClick: () -> Unit) {
    Box(
        modifier
            .semantics { contentDescription = label }
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
 * A play or pause toggle, the elapsed time, a line of how far playback has gone that seeks, the
 * total time, a mute button, a menu of the subtitle tracks when there are any, and a speed menu.
 * A video's bar ends with a button that enters full screen, or leaves it while [fullScreen] is
 * true. Null leaves it out. A narrow bar drops the times first, then the two menus.
 */
@Composable
private fun TransportBar(
    player: KitePlayer,
    scope: CoroutineScope,
    labels: KiteMediaLabels,
    toggle: () -> Unit,
    modifier: Modifier,
    fullScreen: Boolean? = null,
    onFullScreen: () -> Unit = {},
) {
    val snapshot by player.state.collectAsState()
    val progress by player.progress.collectAsState()
    val active = snapshot.status.isActive
    val duration = snapshot.duration?.takeIf { it > Duration.ZERO }
    BoxWithConstraints(modifier.background(SCRIM)) {
        val roomy = maxWidth >= WIDTH_FOR_TIMES
        val withSpeed = maxWidth >= WIDTH_FOR_SPEED
        Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
            BarButton(if (active) labels.pause else labels.play, toggle) {
                if (active) drawPauseGlyph() else drawPlayGlyph(Offset(size.width * 0.15f, 0f), size.minDimension)
            }
            if (roomy) BasicText(clock(progress.position), style = TIME_STYLE)
            SeekLine(
                position = progress.position,
                duration = duration.takeIf { snapshot.seekable },
                label = labels.seek,
                onSeek = { to, final ->
                    if (final) {
                        scope.launch { runCatching { player.seek(to, SeekMode.Precise) } }
                    } else {
                        runCatching { player.requestSeek(to, SeekMode.KeyframeThenRefine) }
                    }
                },
                modifier = Modifier.weight(1f).fillMaxHeight().padding(horizontal = 8.dp),
            )
            if (roomy && duration != null) BasicText(clock(duration), style = TIME_STYLE)
            BarButton(if (snapshot.muted) labels.unmute else labels.mute, { player.setMuted(!snapshot.muted) }) {
                drawSpeakerGlyph(muted = snapshot.muted)
            }
            if (withSpeed && snapshot.tracks.subtitles.isNotEmpty()) {
                SubtitleMenu(snapshot.tracks, labels) { track -> scope.launch { runCatching { player.selectTrack(TrackKind.Subtitle, track) } } }
            }
            if (withSpeed) SpeedMenu(snapshot.speed, labels.speed) { speed -> runCatching { player.setSpeed(speed) } }
            if (fullScreen != null) {
                BarButton(if (fullScreen) labels.exitFullScreen else labels.fullScreen, onFullScreen) { drawFullScreenGlyph(inward = fullScreen) }
            } else {
                Box(Modifier.size(4.dp))
            }
        }
    }
}

/** A button of the bar: a white glyph that [draw] paints, with [label] for a screen reader. */
@Composable
private fun BarButton(label: String, onClick: () -> Unit, draw: DrawScope.() -> Unit) {
    Box(
        Modifier
            .fillMaxHeight()
            .padding(horizontal = 8.dp)
            .semantics { contentDescription = label }
            .clickable(role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.size(GLYPH_SIZE), onDraw = draw)
    }
}

/**
 * The line of how far [position] is into [duration]. A tap seeks to the point under it, and a drag
 * scrubs: [onSeek] gets each point of the drag with false, and its end with true. A screen reader
 * sees a slider with a step of [SEEK_STEP]. Without a [duration], for a source that cannot seek,
 * the line only shows.
 */
@Composable
private fun SeekLine(
    position: Duration,
    duration: Duration?,
    label: String,
    onSeek: (to: Duration, final: Boolean) -> Unit,
    modifier: Modifier,
) {
    // Where a drag holds the line, so it follows the finger and not the player's late position.
    var scrub by remember { mutableStateOf<Float?>(null) }
    val fraction = scrub ?: duration?.let { (position / it).toFloat().coerceIn(0f, 1f) } ?: 0f
    val input = if (duration == null) {
        Modifier
    } else {
        Modifier
            .pointerInput(duration) {
                detectTapGestures { onSeek(duration * (it.x / size.width).coerceIn(0f, 1f).toDouble(), true) }
            }
            .pointerInput(duration) {
                detectHorizontalDragGestures(
                    onDragStart = { scrub = (it.x / size.width).coerceIn(0f, 1f) },
                    onDragEnd = {
                        scrub?.let { onSeek(duration * it.toDouble(), true) }
                        scrub = null
                    },
                    onDragCancel = { scrub = null },
                ) { change, _ ->
                    change.consume()
                    val at = (change.position.x / size.width).coerceIn(0f, 1f)
                    scrub = at
                    onSeek(duration * at.toDouble(), false)
                }
            }
            .semantics {
                contentDescription = label
                stateDescription = "${clock(duration * fraction.toDouble())} / ${clock(duration)}"
                val steps = (duration / SEEK_STEP).toInt() - 1
                progressBarRangeInfo = ProgressBarRangeInfo(fraction, 0f..1f, steps.coerceIn(0, MAX_SEEK_STEPS))
                setProgress { target ->
                    onSeek(duration * target.coerceIn(0f, 1f).toDouble(), true)
                    true
                }
            }
    }
    Canvas(modifier.then(input)) {
        val y = size.height / 2f
        val h = 4.dp.toPx()
        drawRoundRect(TRACK, Offset(0f, y - h / 2f), Size(size.width, h), CornerRadius(h / 2f))
        drawRoundRect(Color.White, Offset(0f, y - h / 2f), Size(size.width * fraction, h), CornerRadius(h / 2f))
        if (duration != null) drawCircle(Color.White, 6.dp.toPx(), Offset(size.width * fraction, y))
    }
}

/** The speed now, as a button that opens a menu of [SPEEDS]; [onSpeed] gets the one the reader picks. */
@Composable
private fun SpeedMenu(speed: Double, label: String, onSpeed: (Double) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box(
        Modifier
            .fillMaxHeight()
            .padding(horizontal = 6.dp)
            .semantics { contentDescription = label; stateDescription = speedText(speed) }
            .clickable(role = Role.Button) { open = !open },
        contentAlignment = Alignment.Center,
    ) {
        BasicText(speedText(speed), style = TIME_STYLE)
        if (open) {
            Popup(alignment = Alignment.BottomCenter, onDismissRequest = { open = false }) {
                Column(Modifier.background(MENU).padding(vertical = 4.dp)) {
                    for (choice in SPEEDS) {
                        val chosen = abs(choice - speed) < 0.001
                        Box(
                            Modifier
                                .widthIn(min = 64.dp)
                                .semantics { selected = chosen }
                                .clickable(role = Role.Button) {
                                    open = false
                                    onSpeed(choice)
                                }
                                .padding(horizontal = 12.dp, vertical = 8.dp),
                        ) {
                            BasicText(speedText(choice), style = if (chosen) TIME_STYLE.copy(color = ACCENT) else TIME_STYLE)
                        }
                    }
                }
            }
        }
    }
}

/**
 * A button that opens a menu of the subtitle and caption tracks in [tracks], with an entry that
 * shows none first (#483). [onTrack] gets the track the reader picks, or null for none.
 */
@Composable
private fun SubtitleMenu(tracks: Tracks, labels: KiteMediaLabels, onTrack: (TrackId?) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val chosen = tracks.selectedSubtitle
    Box(
        Modifier
            .fillMaxHeight()
            .padding(horizontal = 8.dp)
            .semantics {
                contentDescription = labels.subtitles
                stateDescription = chosen?.let { tracks.find(it) }?.label ?: labels.subtitlesOff
            }
            .clickable(role = Role.Button) { open = !open },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.size(GLYPH_SIZE)) { drawSubtitleGlyph(on = chosen != null) }
        if (open) {
            Popup(alignment = Alignment.BottomCenter, onDismissRequest = { open = false }) {
                Column(Modifier.background(MENU).padding(vertical = 4.dp)) {
                    for (track in listOf(null) + tracks.subtitles) {
                        val picked = track?.id == chosen
                        Box(
                            Modifier
                                .widthIn(min = 96.dp)
                                .semantics { selected = picked }
                                .clickable(role = Role.Button) {
                                    open = false
                                    onTrack(track?.id)
                                }
                                .padding(horizontal = 12.dp, vertical = 8.dp),
                        ) {
                            BasicText(track?.label ?: labels.subtitlesOff, style = if (picked) TIME_STYLE.copy(color = ACCENT) else TIME_STYLE)
                        }
                    }
                }
            }
        }
    }
}

/** [speed] as a player shows it, such as `1.25×`. */
internal fun speedText(speed: Double): String {
    val hundredths = kotlin.math.round(speed * 100).toLong()
    val whole = hundredths / 100
    val rest = (hundredths % 100).toString().padStart(2, '0').trimEnd('0')
    return if (rest.isEmpty()) "$whole×" else "$whole.$rest×"
}

/** [time] as `m:ss`, or `h:mm:ss` from an hour on. */
internal fun clock(time: Duration): String {
    val total = time.inWholeSeconds.coerceAtLeast(0)
    val h = total / 3600
    val m = total / 60 % 60
    val s = (total % 60).toString().padStart(2, '0')
    return if (h > 0) "$h:${m.toString().padStart(2, '0')}:$s" else "$m:$s"
}

/** A white speaker, with sound waves, or with a cross while [muted]. */
private fun DrawScope.drawSpeakerGlyph(muted: Boolean) {
    val w = size.width
    val h = size.height
    val body = Path().apply {
        moveTo(0f, h * 0.35f)
        lineTo(w * 0.22f, h * 0.35f)
        lineTo(w * 0.5f, h * 0.08f)
        lineTo(w * 0.5f, h * 0.92f)
        lineTo(w * 0.22f, h * 0.65f)
        lineTo(0f, h * 0.65f)
        close()
    }
    drawPath(body, Color.White)
    val stroke = size.minDimension * 0.1f
    if (muted) {
        drawLine(Color.White, Offset(w * 0.62f, h * 0.32f), Offset(w * 0.98f, h * 0.68f), stroke)
        drawLine(Color.White, Offset(w * 0.62f, h * 0.68f), Offset(w * 0.98f, h * 0.32f), stroke)
    } else {
        for (r in listOf(0.22f, 0.4f)) {
            drawArc(
                Color.White, -50f, 100f, useCenter = false,
                topLeft = Offset(w * 0.5f - w * r, h * 0.5f - h * r), size = Size(2 * w * r, 2 * h * r),
                style = androidx.compose.ui.graphics.drawscope.Stroke(stroke),
            )
        }
    }
}

/** A white frame with two lines of text in it, filled while a track shows ([on]), as players draw subtitles. */
private fun DrawScope.drawSubtitleGlyph(on: Boolean) {
    val stroke = size.minDimension * 0.1f
    val w = size.width
    val h = size.height
    val frame = Size(w - stroke, h * 0.7f - stroke)
    val corner = CornerRadius(stroke * 1.5f)
    val topLeft = Offset(stroke / 2f, h * 0.15f + stroke / 2f)
    val ink = if (on) Color.Black else Color.White
    if (on) drawRoundRect(Color.White, topLeft, frame, corner) else drawRoundRect(Color.White, topLeft, frame, corner, style = androidx.compose.ui.graphics.drawscope.Stroke(stroke))
    drawLine(ink, Offset(w * 0.2f, h * 0.48f), Offset(w * 0.8f, h * 0.48f), stroke)
    drawLine(ink, Offset(w * 0.2f, h * 0.66f), Offset(w * 0.6f, h * 0.66f), stroke)
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

/** The dark of the controls over the poster, the unplayed part of the bar, and the speed menu. */
private val SCRIM = Color(0x99000000)
private val TRACK = Color(0x66FFFFFF)
private val MENU = Color(0xE6202020)
private val ACCENT = Color(0xFF8AB4F8)

private val TIME_STYLE = TextStyle(color = Color.White, fontSize = 12.sp)

/** The speeds of the menu, as most players offer them. */
private val SPEEDS = listOf(0.5, 0.75, 1.0, 1.25, 1.5, 2.0)

/** How far a screen reader moves the line in one step, and the most steps it gets. */
private val SEEK_STEP = 5.seconds
private const val MAX_SEEK_STEPS = 1000

/** The narrowest bar that shows the times, and the narrowest that shows the speed. */
private val WIDTH_FOR_TIMES = 260.dp
private val WIDTH_FOR_SPEED = 180.dp

private val PLAY_BUTTON_SIZE = 56.dp
private val GLYPH_SIZE = 18.dp
private val TRANSPORT_HEIGHT = 40.dp

/** How long the bar that a tap showed on a video without controls stays once the video plays. */
private val REVEAL_TIME = 3.seconds
