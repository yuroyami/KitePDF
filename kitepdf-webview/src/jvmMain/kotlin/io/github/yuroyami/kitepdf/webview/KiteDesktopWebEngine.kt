package io.github.yuroyami.kitepdf.webview

import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.staticCompositionLocalOf

/**
 * A web engine that draws offscreen for [EpubWebView] on the desktop JVM (#41). The view draws
 * each frame the engine hands it on the page, so a web view clips, scrolls and zooms with the
 * page, and the view hands the engine the reader's mouse and keys.
 *
 * [JavaFxWebEngine], the default, runs JavaFX's web view in process. An app that prefers
 * Chromium implements this over an embedder that renders offscreen, such as JCEF, and provides
 * it through [LocalKiteDesktopWebEngine].
 */
public fun interface KiteDesktopWebEngine {
    /**
     * Opens [url] in a new surface that reports to [host], or returns null when the engine
     * cannot run here. The surface draws nothing until [KiteDesktopWebSurface.resize] gives it a
     * size. The URL is the book's own origin for a file of the book.
     */
    public fun open(url: String, host: KiteDesktopWebHost): KiteDesktopWebSurface?
}

/** What a [KiteDesktopWebSurface] reports to the view. Either call may come on any thread. */
public interface KiteDesktopWebHost {
    /**
     * A new frame: [pixels] are premultiplied ARGB, [width] by [height] device pixels, row by
     * row from the top. The view copies them, so the engine may reuse the array.
     */
    public fun frame(pixels: IntArray, width: Int, height: Int)

    /** The page opened a link to [url], absolute, which the engine did not follow. */
    public fun link(url: String)
}

/** One page of a [KiteDesktopWebEngine], which [EpubWebView] draws and sends input to. */
public interface KiteDesktopWebSurface : AutoCloseable {
    /**
     * Lays the page out in [width] by [height] CSS pixels and draws it at [scale] device pixels
     * to each, so a frame is `ceil(width * scale)` by `ceil(height * scale)` pixels.
     */
    public fun resize(width: Int, height: Int, scale: Float)

    /**
     * A mouse event at ([x], [y]) in CSS pixels from the page's top-left corner. [button] is the
     * button that [action] presses or releases, one of [KiteDesktopInput.BUTTON_PRIMARY] and the
     * others, or 0. [buttons] are the buttons held down after the event, and [modifiers] the keys,
     * as [KiteDesktopInput] names them.
     */
    public fun mouse(action: KiteMouseAction, x: Float, y: Float, button: Int, buttons: Int, modifiers: Int)

    /** A turn of the wheel at ([x], [y]), by [deltaX] and [deltaY] notches, positive to the right and down. */
    public fun wheel(x: Float, y: Float, deltaX: Float, deltaY: Float, modifiers: Int)

    /**
     * A key event. [keyCode] is the key's AWT virtual key code, `java.awt.event.KeyEvent.VK_*`,
     * and [char] the character a [KiteKeyAction.TYPE] types, or 0.
     */
    public fun key(action: KiteKeyAction, keyCode: Int, char: Char, modifiers: Int)

    /** The page gains or loses the keyboard focus. */
    public fun focus(focused: Boolean)
}

/** What a mouse event of a [KiteDesktopWebSurface] does. */
public enum class KiteMouseAction { PRESS, RELEASE, MOVE, ENTER, EXIT }

/** What a key event of a [KiteDesktopWebSurface] does. */
public enum class KiteKeyAction { PRESS, RELEASE, TYPE }

/** The buttons and modifier keys of a [KiteDesktopWebSurface]'s input, as bits. */
public object KiteDesktopInput {
    public const val BUTTON_PRIMARY: Int = 1
    public const val BUTTON_SECONDARY: Int = 2
    public const val BUTTON_MIDDLE: Int = 4

    public const val SHIFT: Int = 1
    public const val CONTROL: Int = 2
    public const val ALT: Int = 4
    public const val META: Int = 8
}

/** The engine [EpubWebView] uses on the desktop JVM. [JavaFxWebEngine] unless an app provides another. */
public val LocalKiteDesktopWebEngine: ProvidableCompositionLocal<KiteDesktopWebEngine> =
    staticCompositionLocalOf { JavaFxWebEngine }
