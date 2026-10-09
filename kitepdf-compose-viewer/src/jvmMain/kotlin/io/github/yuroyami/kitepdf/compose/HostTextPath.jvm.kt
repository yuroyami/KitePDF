@file:OptIn(ExperimentalTextApi::class)

package io.github.yuroyami.kitepdf.compose

import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asComposePath
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.skiaCanvas
import androidx.compose.ui.graphics.skiaPaint
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.FontHinting
import androidx.compose.ui.text.FontRasterizationSettings
import androidx.compose.ui.text.FontSmoothing
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.platform.Typeface
import io.github.yuroyami.kitepdf.core.font.FontSpec
import io.github.yuroyami.kitepdf.core.font.KiteFontFamily
import org.jetbrains.skia.Font
import org.jetbrains.skia.FontEdging
import org.jetbrains.skia.FontMgr
import org.jetbrains.skia.FontMgrWithFallback
import org.jetbrains.skia.FontStyle
import org.jetbrains.skia.paragraph.FontCollection
import org.jetbrains.skia.paragraph.HeightMode
import org.jetbrains.skia.paragraph.Paragraph
import org.jetbrains.skia.paragraph.ParagraphBuilder
import org.jetbrains.skia.paragraph.ParagraphStyle
import org.jetbrains.skia.paragraph.TextStyle
import org.jetbrains.skia.paragraph.TypefaceFontProviderWithFallback
import org.jetbrains.skia.Typeface as SkTypeface
import org.jetbrains.skia.FontHinting as SkFontHinting
import kotlin.math.ceil

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

internal actual val hostTextAnyThread: Boolean = true

internal actual fun hostTextLine(text: String, fontSpec: FontSpec, sizePx: Float, color: Color, blendMode: BlendMode): HostTextLine? {
    val shaping = shapings.get()
    val paint = Paint().also {
        it.color = color
        it.blendMode = blendMode
    }.skiaPaint
    // The style Compose's own text gave the piece, so that it draws the same pixels: the family
    // names, the weight and slant, and the platform's rasterization of glyphs.
    val style = TextStyle()
        .setFontFamilies(shaping.families(fontSpec))
        .setFontSize(sizePx)
        .setFontStyle(styleOf(fontSpec))
        .setForeground(paint)
        .apply {
            fontEdging = textEdging
            fontHinting = textHinting
            subpixel = textRasterization.subpixelPositioning
        }
    // The paragraph's own style counts toward its line, so it takes the piece's, as Compose's does.
    shaping.paragraphStyle.textStyle = style
    val builder = ParagraphBuilder(shaping.paragraphStyle, shaping.fonts)
    val paragraph = try {
        builder.pushStyle(style).addText(text).build()
    } finally {
        builder.close()
        style.close()
    }
    return SkiaHostTextLine(paragraph.layout(Float.POSITIVE_INFINITY))
}

/** The glyph rasterization Compose's text uses on this platform. */
private val textRasterization = run {
    ensureComposeBackend()
    FontRasterizationSettings.PlatformDefault
}

private val textEdging = when (textRasterization.smoothing) {
    FontSmoothing.None -> FontEdging.ALIAS
    FontSmoothing.AntiAlias -> FontEdging.ANTI_ALIAS
    FontSmoothing.SubpixelAntiAlias -> FontEdging.SUBPIXEL_ANTI_ALIAS
}

private val textHinting = when (textRasterization.hinting) {
    FontHinting.None -> SkFontHinting.NONE
    FontHinting.Slight -> SkFontHinting.SLIGHT
    FontHinting.Normal -> SkFontHinting.NORMAL
    FontHinting.Full -> SkFontHinting.FULL
}

/** The [HostShaping] of each thread. */
private val shapings: ThreadLocal<HostShaping> = ThreadLocal.withInitial { HostShaping() }

/**
 * What one thread shapes host text with: a font collection over the host's font manager, as
 * Compose's text has, with the CJK face of a spec under a name of its own. Skia's paragraph
 * shapes a word in about 10 us, where its line shaper builds ICU and HarfBuzz iterators for
 * every call and takes 3 ms. A collection caches the faces it finds without a lock, so each
 * thread has its own (#131).
 */
private class HostShaping {
    private val faces = TypefaceFontProviderWithFallback()
    val fonts: FontCollection = FontCollection().setDefaultFontManager(FontMgrWithFallback(faces)).setAssetFontManager(faces)

    /**
     * As Compose's paragraphs are: tabs become spaces, and a line is as tall as its faces' own
     * ascent and descent, which a fallback face's taller metrics would otherwise stretch.
     */
    val paragraphStyle = ParagraphStyle().apply {
        replaceTabCharacters = true
        heightMode = HeightMode.DISABLE_ALL
    }

    private val families = HashMap<FontSpec, Array<String>>()

    /**
     * The family names a piece of [spec] looks its face up by: a face of a CJK spec's language,
     * which [hostFontFamily] gives Compose (#472), else the names Compose looks its generic
     * family up by. Finding a CJK face asks the font manager, so it is done once a spec.
     */
    fun families(spec: FontSpec): Array<String> = families.getOrPut(spec) {
        val cjk = cjkTypeface(spec, styleOf(spec))
        if (cjk != null) arrayOf("kitepdf-host-${families.size}".also { faces.registerTypeface(cjk, it) })
        else composeFamilyNames(spec.family)
    }
}

/**
 * The names Compose looks a generic family up by on this host, from its own table: Compose
 * 1.12 keeps it internal, so it is copied here, and a host that has none of them draws in
 * the font manager's default face, as Compose's text does.
 */
private fun composeFamilyNames(family: KiteFontFamily): Array<String> {
    val os = System.getProperty("os.name").orEmpty()
    return when {
        os.startsWith("Linux") -> when (family) {
            KiteFontFamily.SansSerif -> arrayOf("Noto Sans", "DejaVu Sans", "Arial")
            KiteFontFamily.Serif -> arrayOf("Noto Serif", "DejaVu Serif", "Times New Roman")
            KiteFontFamily.Monospace -> arrayOf("Noto Sans Mono", "DejaVu Sans Mono", "Consolas")
        }
        os.startsWith("Win") -> when (family) {
            KiteFontFamily.SansSerif -> arrayOf("Segoe UI", "Arial")
            KiteFontFamily.Serif -> arrayOf("Times New Roman")
            KiteFontFamily.Monospace -> arrayOf("Consolas")
        }
        os.startsWith("Mac") -> when (family) {
            KiteFontFamily.SansSerif -> arrayOf(".AppleSystemUIFont", "Helvetica Neue", "Helvetica")
            KiteFontFamily.Serif -> arrayOf(".AppleSystemUIFontSerif", "Times", "Times New Roman")
            KiteFontFamily.Monospace -> arrayOf(".AppleSystemUIFontMonospaced", "Menlo", "Courier")
        }
        else -> when (family) {
            KiteFontFamily.SansSerif -> arrayOf("Arial")
            KiteFontFamily.Serif -> arrayOf("Times New Roman")
            KiteFontFamily.Monospace -> arrayOf("Consolas")
        }
    }
}

/** A piece laid out by Skia, drawn on the scope's own Skia canvas, under its transform and clip. */
private class SkiaHostTextLine(private val paragraph: Paragraph) : HostTextLine {
    /** Rounded up, as Compose's text measures a piece, so that a piece is fitted as it was. */
    override val width: Float get() = ceil(paragraph.maxIntrinsicWidth)

    override fun draw(scope: DrawScope) {
        // The baseline of the line the paragraph paints, which Compose's text places a piece by too.
        val baseline = paragraph.lineMetrics.firstOrNull()?.baseline?.toFloat() ?: paragraph.alphabeticBaseline
        scope.drawIntoCanvas { paragraph.paint(it.skiaCanvas, 0f, -baseline) }
    }
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
    cjkTypeface(spec, style)?.let { return it }
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

internal actual fun hostHasFace(fontSpec: FontSpec): Boolean = true
