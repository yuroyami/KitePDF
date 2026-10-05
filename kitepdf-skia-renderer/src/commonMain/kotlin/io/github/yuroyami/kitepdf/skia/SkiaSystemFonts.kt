package io.github.yuroyami.kitepdf.skia

import io.github.yuroyami.kitepdf.core.KiteLock
import io.github.yuroyami.kitepdf.core.font.FontSpec
import io.github.yuroyami.kitepdf.core.font.KiteFontFamily
import io.github.yuroyami.kitepdf.core.withLock
import org.jetbrains.skia.FontMgr
import org.jetbrains.skia.FontSlant
import org.jetbrains.skia.FontStyle
import org.jetbrains.skia.Typeface

/**
 * Picks the host typeface that stands in for a Standard-14 font.
 *
 * Skia matches a family by its real name, so one name is never enough: the
 * Helvetica/Times/Courier trio ships on macOS and Windows, while Linux carries
 * metric-compatible clones under other names. A family the host lacks yields a
 * null typeface, and Skia draws nothing whatsoever with one of those and
 * reports no error, so every list ends in whatever the host does have.
 */
internal object SkiaSystemFonts {

    private val SERIF = listOf(
        "Times New Roman", "Times", "Liberation Serif", "Nimbus Roman",
        "Tinos", "DejaVu Serif", "Noto Serif", "FreeSerif",
    )
    private val SANS_SERIF = listOf(
        "Helvetica", "Arial", "Liberation Sans", "Nimbus Sans",
        "Arimo", "DejaVu Sans", "Noto Sans", "FreeSans",
    )
    private val MONOSPACE = listOf(
        "Courier New", "Courier", "Liberation Mono", "Nimbus Mono PS",
        "Cousine", "DejaVu Sans Mono", "Noto Sans Mono", "FreeMono",
    )

    fun resolve(family: KiteFontFamily, style: FontStyle): Typeface? = resolveCandidates(
        when (family) {
            KiteFontFamily.Serif -> SERIF
            KiteFontFamily.SansSerif -> SANS_SERIF
            KiteFontFamily.Monospace -> MONOSPACE
        },
        style,
    )

    /**
     * The typeface for [spec]. A font of a CJK language takes a face of that language first: the
     * Latin faces above have no Han, kana or Hangul glyphs, and a Han character takes the form of
     * whichever CJK face draws it (#472). Without a named face, the host is asked for any face of
     * the language that draws [FontSpec.languageSample].
     */
    fun resolve(spec: FontSpec, style: FontStyle): Typeface? {
        val sample = spec.languageSample ?: return resolve(spec.family, style)
        val language = spec.language ?: return resolve(spec.family, style)
        val cjk = try {
            val mgr = FontMgr.default
            spec.hostFaces.firstNotNullOfOrNull { mgr.matchFamilyStyle(it, style) }
                ?: mgr.matchFamilyStyleCharacter(genericName(spec.family), style, arrayOf(language), sample)
        } catch (t: Throwable) {
            null
        }
        return cjk ?: resolve(spec.family, style)
    }

    /**
     * A host face that draws [codePoint], for a character the face of [resolve] lacks, preferring
     * one of [spec]'s language. Skia would draw glyph 0 for it, which is nothing: the letters of a
     * script the Latin faces lack, such as Arabic (#587), and the characters one CJK face lacks
     * and another has, such as the vertical forms of U+FE10 to U+FE19 (#472).
     */
    fun fallback(spec: FontSpec, style: FontStyle, codePoint: Int): Typeface? {
        // A host lookup takes a fifth of a millisecond, and a page of Arabic asks for each letter in every run.
        val key = FallbackKey(spec.family, style.weight, style.width, style.slant, spec.language, codePoint)
        fallbackLock.withLock { if (key in fallbacks) return fallbacks[key] }
        val face = try {
            FontMgr.default.matchFamilyStyleCharacter(genericName(spec.family), style, spec.language?.let { arrayOf(it) }, codePoint)
        } catch (t: Throwable) {
            null
        }
        fallbackLock.withLock {
            if (fallbacks.size >= FALLBACK_CACHE_SIZE) fallbacks.clear()
            fallbacks[key] = face
        }
        return face
    }

    private data class FallbackKey(
        val family: KiteFontFamily, val weight: Int, val width: Int, val slant: FontSlant, val language: String?, val codePoint: Int,
    )

    private val fallbackLock = KiteLock()
    private val fallbacks = HashMap<FallbackKey, Typeface?>()
    private const val FALLBACK_CACHE_SIZE = 4096

    private fun genericName(family: KiteFontFamily): String = when (family) {
        KiteFontFamily.Serif -> "serif"
        KiteFontFamily.SansSerif -> "sans-serif"
        KiteFontFamily.Monospace -> "monospace"
    }

    /** First candidate the host has, else any family it does have. */
    fun resolveCandidates(candidates: List<String>, style: FontStyle): Typeface? = try {
        val mgr = FontMgr.default
        candidates.firstNotNullOfOrNull { mgr.matchFamilyStyle(it, style) }
            ?: (0 until mgr.familiesCount).firstNotNullOfOrNull { mgr.matchFamilyStyle(mgr.getFamilyName(it), style) }
    } catch (t: Throwable) {
        null
    }
}
