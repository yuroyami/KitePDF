package io.github.yuroyami.kitepdf.compose

import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import io.github.yuroyami.kitepdf.core.font.FontSpec

/**
 * One piece of host-font text, laid out by Skia's own paragraph engine with the family names,
 * style and glyph rasterization that Compose's text gives it, so that it draws the pixels
 * Compose's text drew, a fallback face for a character the face lacks included.
 *
 * Compose's text stack keeps a process-wide style cache in Kotlin, and two threads that measure
 * through it at once broke it on iOS (db082392, #428), so a page with host-font text was drawn on
 * the UI thread. Skia's engine does not go through that cache, so a piece laid out here can be
 * drawn on any thread, and such a page renders off the UI thread in one pass (#131).
 */
internal interface HostTextLine {
    /** The advance of the piece, in pixels. */
    val width: Float

    /** Draws the piece with its baseline origin at (0, 0) of [scope]. */
    fun draw(scope: DrawScope)
}

/**
 * True where [hostTextLine] shapes host-font text: on the desktop JVM, iOS and macOS. Android
 * draws text through its own framework under Compose, and a browser has no host faces for
 * Skia to find, so both keep Compose's text on the UI thread.
 */
internal expect val hostTextAnyThread: Boolean

/**
 * [text] in the host face for [fontSpec] at [sizePx] pixels to the em, to draw in [color], its
 * alpha included, with [blendMode]. Null where [hostTextAnyThread] is false.
 */
internal expect fun hostTextLine(text: String, fontSpec: FontSpec, sizePx: Float, color: Color, blendMode: BlendMode): HostTextLine?
