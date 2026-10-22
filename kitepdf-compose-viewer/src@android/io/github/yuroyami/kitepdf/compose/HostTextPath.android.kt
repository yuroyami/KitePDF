package io.github.yuroyami.kitepdf.compose

import android.graphics.Paint
import android.graphics.Typeface
import android.os.Build
import android.os.LocaleList
import android.text.Layout
import android.text.TextPaint
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asComposePaint
import androidx.compose.ui.graphics.asComposePath
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.font.FontFamily
import io.github.yuroyami.kitepdf.core.font.FontSpec
import io.github.yuroyami.kitepdf.core.font.KiteFontFamily
import kotlin.math.ceil

internal actual fun hostTextPath(text: String, fontSpec: FontSpec): Path? {
    val base = when (fontSpec.family) {
        KiteFontFamily.Serif -> Typeface.SERIF
        KiteFontFamily.Monospace -> Typeface.MONOSPACE
        KiteFontFamily.SansSerif -> Typeface.SANS_SERIF
    }
    val style = when {
        fontSpec.bold && fontSpec.italic -> Typeface.BOLD_ITALIC
        fontSpec.bold -> Typeface.BOLD
        fontSpec.italic -> Typeface.ITALIC
        else -> Typeface.NORMAL
    }
    val path = android.graphics.Path()
    Paint().apply {
        typeface = Typeface.create(base, style)
        textSize = 1000f
        // The locale picks the CJK fallback face of the font's language (#472).
        fontSpec.language?.let { textLocale = java.util.Locale.forLanguageTag(it) }
    }.getTextPath(text, 0, text.length, 0f, 0f, path)
    return path.asComposePath()
}

/** Android's own fallback picks a face of the locale's language, serif or not, from the generic family. */
internal actual fun hostFontFamily(fontSpec: FontSpec): FontFamily? = null

internal actual val hostTextAnyThread: Boolean = true

/**
 * A piece drawn by Android's own text stack, with the paint Compose's text gives it: the face
 * Compose resolves for the generic family, its weight and slant, the language's locale for a
 * fallback face, anti-aliasing with hinting and no subpixel or linear text (TextMotion.Static),
 * and the colour and blend mode. A paint and a canvas of one thread need no other thread, where
 * Compose's text keeps caches in Kotlin that two threads must not share (#428, #487).
 */
internal actual fun hostTextLine(text: String, fontSpec: FontSpec, sizePx: Float, color: Color, blendMode: BlendMode): HostTextLine? {
    val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        flags = flags and (Paint.SUBPIXEL_TEXT_FLAG or Paint.LINEAR_TEXT_FLAG).inv()
        hinting = Paint.HINTING_ON
        typeface = composeTypeface(fontSpec)
        textSize = sizePx
        fontSpec.language?.let { textLocales = LocaleList(java.util.Locale.forLanguageTag(it)) }
        this.color = color.toArgb()
    }
    // Compose sets the mode through its own paint, which falls back to a Porter-Duff mode below API 29.
    if (blendMode != BlendMode.SrcOver) paint.asComposePaint().blendMode = blendMode
    return AndroidHostTextLine(text, paint)
}

/**
 * The face Compose's text resolves a generic family to: the named system family in the weight and
 * slant asked for, which Android makes bolder or slanted where the family has no such face.
 */
private fun composeTypeface(spec: FontSpec): Typeface {
    val name = when (spec.family) {
        KiteFontFamily.Serif -> "serif"
        KiteFontFamily.Monospace -> "monospace"
        KiteFontFamily.SansSerif -> "sans-serif"
    }
    if (Build.VERSION.SDK_INT >= 28) return Typeface.create(Typeface.create(name, Typeface.NORMAL), if (spec.bold) 700 else 400, spec.italic)
    val style = when {
        spec.bold && spec.italic -> Typeface.BOLD_ITALIC
        spec.bold -> Typeface.BOLD
        spec.italic -> Typeface.ITALIC
        else -> Typeface.NORMAL
    }
    return Typeface.create(name, style)
}

/** A piece drawn on the scope's own Android canvas, under its transform and clip, at its baseline. */
private class AndroidHostTextLine(private val text: String, private val paint: TextPaint) : HostTextLine {
    /** Rounded up, as Compose's text measures a piece, so that a piece is fitted as it was. */
    override val width: Float get() = ceil(Layout.getDesiredWidth(text, paint))

    override fun draw(scope: DrawScope) {
        scope.drawIntoCanvas { it.nativeCanvas.drawText(text, 0f, 0f, paint) }
    }
}

internal actual fun hostHasFace(fontSpec: FontSpec): Boolean = true
