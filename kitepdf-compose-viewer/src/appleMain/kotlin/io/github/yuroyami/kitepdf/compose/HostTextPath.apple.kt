package io.github.yuroyami.kitepdf.compose

import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asComposePath
import io.github.yuroyami.kitepdf.core.font.FontSpec
import io.github.yuroyami.kitepdf.core.font.KiteFontFamily
import org.jetbrains.skia.Font
import org.jetbrains.skia.FontMgr
import org.jetbrains.skia.FontStyle
import org.jetbrains.skia.Typeface

internal actual fun hostTextPath(text: String, fontSpec: FontSpec): Path? {
    val typeface = hostTypeface(fontSpec) ?: return null
    val font = Font(typeface, 1000f)
    val ids = font.getStringGlyphs(text)
    val advances = font.getWidths(ids)
    val out = org.jetbrains.skia.PathBuilder()
    var pen = 0f
    for (k in ids.indices) {
        font.getPath(ids[k])?.let { out.addPath(it, pen, 0f) }
        pen += advances[k]
    }
    return out.detach().asComposePath()
}

/**
 * The first family of the list that the host has, else any family it has. Skia matches a
 * family by its real name, and one name is never on every host.
 */
private fun hostTypeface(spec: FontSpec): Typeface? {
    val style = when {
        spec.bold && spec.italic -> FontStyle.BOLD_ITALIC
        spec.bold -> FontStyle.BOLD
        spec.italic -> FontStyle.ITALIC
        else -> FontStyle.NORMAL
    }
    val names = when (spec.family) {
        KiteFontFamily.Serif -> listOf("Times New Roman", "Times", "Liberation Serif", "Nimbus Roman", "Tinos", "DejaVu Serif", "Noto Serif")
        KiteFontFamily.SansSerif -> listOf("Helvetica", "Arial", "Liberation Sans", "Nimbus Sans", "Arimo", "DejaVu Sans", "Noto Sans")
        KiteFontFamily.Monospace -> listOf("Courier New", "Courier", "Liberation Mono", "Nimbus Mono PS", "Cousine", "DejaVu Sans Mono", "Noto Sans Mono")
    }
    return try {
        val mgr = FontMgr.default
        names.firstNotNullOfOrNull { mgr.matchFamilyStyle(it, style) }
            ?: (0 until mgr.familiesCount).firstNotNullOfOrNull { mgr.matchFamilyStyle(mgr.getFamilyName(it), style) }
    } catch (failure: Throwable) {
        null
    }
}
