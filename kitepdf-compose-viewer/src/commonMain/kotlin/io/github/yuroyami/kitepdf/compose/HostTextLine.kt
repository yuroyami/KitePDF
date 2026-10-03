package io.github.yuroyami.kitepdf.compose

import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import io.github.yuroyami.kitepdf.core.font.FontSpec

/**
 * One piece of host-font text, laid out by the platform's own text engine with what Compose's
 * text gives it, so that it draws the pixels Compose's text drew, a fallback face for a
 * character the face lacks included: Skia's paragraph engine with Compose's family names, style
 * and glyph rasterization on the desktop JVM, iOS and macOS, and Android's text stack with
 * Compose's paint on Android.
 *
 * Compose's text stack keeps caches in Kotlin, and two threads that measured through them at
 * once broke them on iOS (db082392, #428), so a page with host-font text was drawn on the UI
 * thread. Neither engine goes through those caches, so a piece laid out here can be drawn on any
 * thread, and such a page renders off the UI thread in one pass (#131, #487).
 */
internal interface HostTextLine {
    /** The advance of the piece, in pixels. */
    val width: Float

    /** Draws the piece with its baseline origin at (0, 0) of [scope]. */
    fun draw(scope: DrawScope)
}

/**
 * True where [hostTextLine] shapes host-font text: on the desktop JVM, iOS, macOS and Android.
 * A browser has no host faces for Skia to find and one thread to draw on, so it keeps
 * Compose's text.
 */
internal expect val hostTextAnyThread: Boolean

/**
 * [text] in the host face for [fontSpec] at [sizePx] pixels to the em, to draw in [color], its
 * alpha included, with [blendMode]. Null where [hostTextAnyThread] is false.
 */
internal expect fun hostTextLine(text: String, fontSpec: FontSpec, sizePx: Float, color: Color, blendMode: BlendMode): HostTextLine?
