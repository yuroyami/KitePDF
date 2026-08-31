package io.github.yuroyami.kitepdf.skia

import io.github.yuroyami.kitepdf.core.font.KiteFontFamily
import org.jetbrains.skia.FontMgr
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

    /** First candidate the host has, else any family it does have. */
    fun resolveCandidates(candidates: List<String>, style: FontStyle): Typeface? = try {
        val mgr = FontMgr.default
        candidates.firstNotNullOfOrNull { mgr.matchFamilyStyle(it, style) }
            ?: (0 until mgr.familiesCount).firstNotNullOfOrNull { mgr.matchFamilyStyle(mgr.getFamilyName(it), style) }
    } catch (t: Throwable) {
        null
    }
}
