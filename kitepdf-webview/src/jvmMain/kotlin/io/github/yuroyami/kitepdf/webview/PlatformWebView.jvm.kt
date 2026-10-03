package io.github.yuroyami.kitepdf.webview

import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.input.pointer.PointerEvent
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.isAltPressed
import androidx.compose.ui.input.pointer.isCtrlPressed
import androidx.compose.ui.input.pointer.isMetaPressed
import androidx.compose.ui.input.pointer.isPrimaryPressed
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.isShiftPressed
import androidx.compose.ui.input.pointer.isTertiaryPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import io.github.yuroyami.kitepdf.epub.EpubDocument
import kotlinx.coroutines.launch
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.Image
import org.jetbrains.skia.ImageInfo
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.ceil
import kotlin.math.roundToInt
import kotlin.math.sqrt

internal actual fun platformWebViewAvailable(): Boolean = true

/**
 * The desktop's web view (#41): a [KiteDesktopWebEngine] draws the page offscreen, and this draws
 * each frame on the island's box, so the web view clips, scrolls and zooms with the page. The
 * engine lays the document out in the island's CSS size and draws it at the box's scale. The
 * reader's mouse and keys go to the engine in CSS pixels. A wheel over an embedded document
 * scrolls the document; over a whole page it scrolls the book, as the page has no more to show.
 */
@Composable
internal actual fun PlatformWebView(
    island: EpubWebIsland,
    document: EpubDocument,
    onLink: (String) -> Unit,
    modifier: Modifier,
) {
    val engine = LocalKiteDesktopWebEngine.current
    val remote = hasScheme(island.href)
    val server = remember(document, remote) { if (remote) null else BookServer.acquire(document) }
    DisposableEffect(server) { onDispose { server?.let(BookServer::release) } }
    val url = remember(server, island.href) { server?.urlOf(island.href) ?: island.href }

    var frame by remember(url) { mutableStateOf<ImageBitmap?>(null) }
    val scope = rememberCoroutineScope()
    val currentOnLink by rememberUpdatedState(onLink)
    val surface = remember(engine, url) {
        engine.open(
            url,
            object : KiteDesktopWebHost {
                override fun frame(pixels: IntArray, width: Int, height: Int) {
                    frame = bitmapOf(pixels, width, height)
                }

                override fun link(url: String) {
                    val href = server?.hrefOf(url) ?: url
                    scope.launch { currentOnLink(href) }
                }
            },
        )
    }
    DisposableEffect(surface) { onDispose { surface?.close() } }
    if (surface == null) return

    val cssWidth = ceil(island.contentWidth).toInt().coerceAtLeast(1)
    val cssHeight = ceil(island.contentHeight).toInt().coerceAtLeast(1)
    var box by remember { mutableStateOf(IntSize.Zero) }
    val focus = remember { FocusRequester() }
    Box(
        modifier
            .semantics { contentDescription = island.href }
            .onSizeChanged { size ->
                box = size
                if (size.width > 0 && size.height > 0) surface.resize(cssWidth, cssHeight, scaleOf(size, cssWidth, cssHeight))
            }
            .focusRequester(focus)
            .onFocusChanged { surface.focus(it.isFocused) }
            .focusable()
            .onKeyEvent { event ->
                val awt = event.nativeKeyEvent as? java.awt.event.KeyEvent ?: return@onKeyEvent false
                val action = when (awt.id) {
                    java.awt.event.KeyEvent.KEY_PRESSED -> KiteKeyAction.PRESS
                    java.awt.event.KeyEvent.KEY_RELEASED -> KiteKeyAction.RELEASE
                    java.awt.event.KeyEvent.KEY_TYPED -> KiteKeyAction.TYPE
                    else -> return@onKeyEvent false
                }
                val char = if (action == KiteKeyAction.TYPE && awt.keyChar != java.awt.event.KeyEvent.CHAR_UNDEFINED) awt.keyChar else 0.toChar()
                surface.key(action, awt.keyCode, char, modifiersOf(awt))
                true
            }
            .pointerInput(surface, island.kind) {
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull() ?: continue
                        val sx = if (size.width > 0) cssWidth.toFloat() / size.width else 1f
                        val sy = if (size.height > 0) cssHeight.toFloat() / size.height else 1f
                        val x = change.position.x * sx
                        val y = change.position.y * sy
                        val modifiers = modifiersOf(event)
                        val buttons = buttonsOf(event)
                        when (event.type) {
                            // A touch holds no button, so the button a press or a release names is counted in by hand.
                            PointerEventType.Press -> {
                                focus.requestFocus()
                                val button = buttonOf(event)
                                surface.mouse(KiteMouseAction.PRESS, x, y, button, buttons or button, modifiers)
                            }
                            PointerEventType.Release -> {
                                val button = buttonOf(event)
                                surface.mouse(KiteMouseAction.RELEASE, x, y, button, buttons and button.inv(), modifiers)
                            }
                            PointerEventType.Move -> surface.mouse(KiteMouseAction.MOVE, x, y, 0, buttons, modifiers)
                            PointerEventType.Enter -> surface.mouse(KiteMouseAction.ENTER, x, y, 0, buttons, modifiers)
                            PointerEventType.Exit -> surface.mouse(KiteMouseAction.EXIT, x, y, 0, buttons, modifiers)
                            PointerEventType.Scroll -> {
                                if (island.kind == EpubWebIslandKind.PAGE) continue
                                surface.wheel(x, y, change.scrollDelta.x, change.scrollDelta.y, modifiers)
                            }
                            else -> continue
                        }
                        change.consume()
                    }
                }
            }
            .drawBehind {
                val image = frame ?: return@drawBehind
                drawImage(
                    image, srcOffset = IntOffset.Zero, srcSize = IntSize(image.width, image.height),
                    dstSize = IntSize(size.width.roundToInt(), size.height.roundToInt()),
                    filterQuality = FilterQuality.Medium,
                )
            },
    )
}

/** Device pixels to each CSS pixel for a box of [size], kept under [MAX_FRAME_PIXELS] in a frame. */
private fun scaleOf(size: IntSize, cssWidth: Int, cssHeight: Int): Float {
    val scale = size.width.toFloat() / cssWidth
    val pixels = cssWidth.toDouble() * cssHeight * scale * scale
    return if (pixels <= MAX_FRAME_PIXELS) scale else (scale * sqrt(MAX_FRAME_PIXELS / pixels)).toFloat()
}

/** The most pixels a frame of a web view holds, about 64 MB: past it the frame is drawn coarser and scaled up. */
private const val MAX_FRAME_PIXELS = 16_000_000.0

/** Premultiplied ARGB [pixels] as an image: in a little-endian int, ARGB is Skia's BGRA. */
private fun bitmapOf(pixels: IntArray, width: Int, height: Int): ImageBitmap {
    val bytes = ByteArray(width * height * 4)
    ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asIntBuffer().put(pixels, 0, width * height)
    val info = ImageInfo(width, height, ColorType.BGRA_8888, ColorAlphaType.PREMUL)
    return Image.makeRaster(info, bytes, width * 4).toComposeImageBitmap()
}

@OptIn(ExperimentalComposeUiApi::class)
private fun buttonOf(event: PointerEvent): Int = when (event.button) {
    PointerButton.Secondary -> KiteDesktopInput.BUTTON_SECONDARY
    PointerButton.Tertiary -> KiteDesktopInput.BUTTON_MIDDLE
    else -> KiteDesktopInput.BUTTON_PRIMARY
}

private fun buttonsOf(event: PointerEvent): Int {
    var bits = 0
    if (event.buttons.isPrimaryPressed) bits = bits or KiteDesktopInput.BUTTON_PRIMARY
    if (event.buttons.isSecondaryPressed) bits = bits or KiteDesktopInput.BUTTON_SECONDARY
    if (event.buttons.isTertiaryPressed) bits = bits or KiteDesktopInput.BUTTON_MIDDLE
    return bits
}

private fun modifiersOf(event: PointerEvent): Int {
    val keys = event.keyboardModifiers
    var bits = 0
    if (keys.isShiftPressed) bits = bits or KiteDesktopInput.SHIFT
    if (keys.isCtrlPressed) bits = bits or KiteDesktopInput.CONTROL
    if (keys.isAltPressed) bits = bits or KiteDesktopInput.ALT
    if (keys.isMetaPressed) bits = bits or KiteDesktopInput.META
    return bits
}

private fun modifiersOf(awt: java.awt.event.KeyEvent): Int {
    var bits = 0
    if (awt.isShiftDown) bits = bits or KiteDesktopInput.SHIFT
    if (awt.isControlDown) bits = bits or KiteDesktopInput.CONTROL
    if (awt.isAltDown) bits = bits or KiteDesktopInput.ALT
    if (awt.isMetaDown) bits = bits or KiteDesktopInput.META
    return bits
}
