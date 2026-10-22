package io.github.yuroyami.kitepdf.compose

import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asComposePath
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.platform.Typeface
import io.github.yuroyami.kitepdf.core.font.FontSpec
import io.github.yuroyami.kitepdf.core.font.KiteFontFamily
import org.jetbrains.skia.Font
import org.jetbrains.skia.FontMgr
import org.jetbrains.skia.FontStyle
import org.jetbrains.skia.Typeface as SkTypeface

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

internal actual fun hostFontFamily(fontSpec: FontSpec): FontFamily? {
    if (fontSpec.languageSample == null) return null
    val key = fontSpec.copy(name = "")
    if (key in cjkFamilies) return cjkFamilies[key]
    return cjkTypeface(fontSpec, styleOf(fontSpec))?.let { FontFamily(Typeface(it)) }.also { cjkFamilies[key] = it }
}

/** The family of each CJK spec, null where the host has no face of its language. Host text thread only. */
private val cjkFamilies = HashMap<FontSpec, FontFamily?>()

private fun styleOf(spec: FontSpec): FontStyle = when {
    spec.bold && spec.italic -> FontStyle.BOLD_ITALIC
    spec.bold -> FontStyle.BOLD
    spec.italic -> FontStyle.ITALIC
    else -> FontStyle.NORMAL
}

/**
 * A face of [spec]'s CJK language: a named face of [FontSpec.hostFaces], else any face the host
 * matches to the language and [FontSpec.languageSample]. Null for a spec without a CJK language.
 */
private fun cjkTypeface(spec: FontSpec, style: FontStyle): SkTypeface? {
    val sample = spec.languageSample ?: return null
    val language = spec.language ?: return null
    return try {
        val mgr = FontMgr.default
        val generic = if (spec.family == KiteFontFamily.Serif) "serif" else "sans-serif"
        spec.hostFaces.firstNotNullOfOrNull { mgr.matchFamilyStyle(it, style) }
            ?: mgr.matchFamilyStyleCharacter(generic, style, arrayOf(language), sample)
    } catch (failure: Throwable) {
        null
    }
}

/**
 * The first family of the list that the host has, else any family it has. Skia matches a
 * family by its real name, and one name is never on every host. A CJK spec takes a face of its
 * language first, since the Latin faces of the list have no Han, kana or Hangul glyphs (#472).
 */
private fun hostTypeface(spec: FontSpec): SkTypeface? {
    val style = styleOf(spec)
    return namedTypeface(spec, style) ?: try {
        val mgr = FontMgr.default
        (0 until mgr.familiesCount).firstNotNullOfOrNull { mgr.matchFamilyStyle(mgr.getFamilyName(it), style) }
    } catch (failure: Throwable) {
        null
    }
}

/** A face of [spec]'s CJK language, else the first family of [spec]'s list that the host has, or null. */
private fun namedTypeface(spec: FontSpec, style: FontStyle): SkTypeface? {
    cjkTypeface(spec, style)?.let { return it }
    val names = when (spec.family) {
        KiteFontFamily.Serif -> listOf("Times New Roman", "Times", "Liberation Serif", "Nimbus Roman", "Tinos", "DejaVu Serif", "Noto Serif")
        KiteFontFamily.SansSerif -> listOf("Helvetica", "Arial", "Liberation Sans", "Nimbus Sans", "Arimo", "DejaVu Sans", "Noto Sans")
        KiteFontFamily.Monospace -> listOf("Courier New", "Courier", "Liberation Mono", "Nimbus Mono PS", "Cousine", "DejaVu Sans Mono", "Noto Sans Mono")
    }
    return try {
        val mgr = FontMgr.default
        names.firstNotNullOfOrNull { mgr.matchFamilyStyle(it, style) }
    } catch (failure: Throwable) {
        null
    }
}

internal actual fun hostHasFace(fontSpec: FontSpec): Boolean {
    val key = fontSpec.copy(name = "")
    return namedFaces.getOrPut(key) { namedTypeface(fontSpec, styleOf(fontSpec)) != null }
}

/** Whether the host has a face of each spec by name. A page asks on every run of host text. Host text thread only. */
private val namedFaces = HashMap<FontSpec, Boolean>()

/** A browser has no host faces for Skia to find, and a page draws on the only thread anyway (#131). */
internal actual val hostTextAnyThread: Boolean = false

internal actual fun hostTextLine(text: String, fontSpec: FontSpec, sizePx: Float, color: Color, blendMode: BlendMode): HostTextLine? = null
