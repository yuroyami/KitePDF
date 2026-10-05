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

    fun resolve(family: KiteFontFamily, style: FontStyle): Typeface? = resolveCandidates(candidatesOf(family), style)

    private fun candidatesOf(family: KiteFontFamily): List<String> = when (family) {
        KiteFontFamily.Serif -> SERIF
        KiteFontFamily.SansSerif -> SANS_SERIF
        KiteFontFamily.Monospace -> MONOSPACE
    }

    /**
     * The typeface for [spec]. A font of a CJK language takes a face of that language first: the
     * Latin faces above have no Han, kana or Hangul glyphs, and a Han character takes the form of
     * whichever CJK face draws it (#472). Without a named face, the host is asked for any face of
     * the language that draws [FontSpec.languageSample].
     */
    fun resolve(spec: FontSpec, style: FontStyle): Typeface? {
        // A lookup takes a millisecond or more, and a page asks for the face on every run of host text (#590).
        val key = FaceKey(spec.family, style.weight, style.width, style.slant, spec.language, codePoint = -1)
        cacheLock.withLock { if (key in faces) return faces[key] }
        val face = lookUp(spec, style)
        cacheLock.withLock {
            if (faces.size >= FACE_CACHE_SIZE) faces.clear()
            faces[key] = face
        }
        return face
    }

    private fun lookUp(spec: FontSpec, style: FontStyle): Typeface? = cjkFace(spec, style) ?: resolve(spec.family, style)

    /** A face of [spec]'s CJK language, or null for a spec without one or a host without such a face. */
    private fun cjkFace(spec: FontSpec, style: FontStyle): Typeface? {
        val sample = spec.languageSample ?: return null
        val language = spec.language ?: return null
        return try {
            val mgr = FontMgr.default
            spec.hostFaces.firstNotNullOfOrNull { mgr.matchFamilyStyle(it, style) }
                ?: mgr.matchFamilyStyleCharacter(genericName(spec.family), style, arrayOf(language), sample)
        } catch (t: Throwable) {
            null
        }
    }

    /**
     * True when the host has a face of [spec]'s family by one of its names, or of its CJK
     * language, so that [resolve] is not just whatever face the host has. A browser has one face,
     * Roboto, and a family it lacks draws from the bundled standard faces instead (#593).
     */
    fun hasFace(spec: FontSpec, style: FontStyle): Boolean {
        val key = FaceKey(spec.family, style.weight, style.width, style.slant, spec.language, codePoint = -2)
        cacheLock.withLock { named[key]?.let { return it } }
        val has = cjkFace(spec, style) != null || try {
            val mgr = FontMgr.default
            candidatesOf(spec.family).any { mgr.matchFamilyStyle(it, style) != null }
        } catch (t: Throwable) {
            false
        }
        cacheLock.withLock {
            if (named.size >= FACE_CACHE_SIZE) named.clear()
            named[key] = has
        }
        return has
    }

    /**
     * A host face that draws [codePoint], for a character the face of [resolve] lacks, preferring
     * one of [spec]'s language. Skia would draw glyph 0 for it, which is nothing: the letters of a
     * script the Latin faces lack, such as Arabic (#587), and the characters one CJK face lacks
     * and another has, such as the vertical forms of U+FE10 to U+FE19 (#472).
     */
    fun fallback(spec: FontSpec, style: FontStyle, codePoint: Int): Typeface? {
        // A host lookup takes a fifth of a millisecond, and a page of Arabic asks for each letter in every run.
        val key = FaceKey(spec.family, style.weight, style.width, style.slant, spec.language, codePoint)
        cacheLock.withLock { if (key in fallbacks) return fallbacks[key] }
        val face = try {
            FontMgr.default.matchFamilyStyleCharacter(genericName(spec.family), style, spec.language?.let { arrayOf(it) }, codePoint)
        } catch (t: Throwable) {
            null
        }
        cacheLock.withLock {
            if (fallbacks.size >= FALLBACK_CACHE_SIZE) fallbacks.clear()
            fallbacks[key] = face
        }
        return face
    }

    /** What a face is looked up by. [codePoint] is the character of a [fallback], -1 for the face of [resolve], or -2 for [hasFace]. */
    private data class FaceKey(
        val family: KiteFontFamily, val weight: Int, val width: Int, val slant: FontSlant, val language: String?, val codePoint: Int,
    )

    private val cacheLock = KiteLock()
    private val faces = HashMap<FaceKey, Typeface?>()
    private val fallbacks = HashMap<FaceKey, Typeface?>()
    private val named = HashMap<FaceKey, Boolean>()
    private const val FACE_CACHE_SIZE = 256
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
