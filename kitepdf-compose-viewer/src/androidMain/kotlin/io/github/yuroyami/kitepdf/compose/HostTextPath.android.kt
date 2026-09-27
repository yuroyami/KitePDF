package io.github.yuroyami.kitepdf.compose

import android.graphics.Paint
import android.graphics.Typeface
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asComposePath
import io.github.yuroyami.kitepdf.core.font.FontSpec
import io.github.yuroyami.kitepdf.core.font.KiteFontFamily

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
    }.getTextPath(text, 0, text.length, 0f, 0f, path)
    return path.asComposePath()
}
