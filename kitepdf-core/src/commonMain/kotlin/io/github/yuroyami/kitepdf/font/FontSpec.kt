package io.github.yuroyami.kitepdf.font

/** Broad typeface family a non-embedded font substitutes into. */
public enum class FontFamily { Serif, SansSerif, Monospace }

/**
 * Platform-neutral descriptor for picking a substitute system font when a
 * document font ships no embedded outlines (e.g. the PDF Standard-14, or a CSS
 * font with no `@font-face` file). Render backends map [family] + [bold] +
 * [italic] to a host typeface. No PDF (or other format) types cross the canvas
 * seam, so every document handler — PDF, EPUB, ... — feeds the same [FontSpec].
 */
public data class FontSpec(
    val family: FontFamily,
    val bold: Boolean,
    val italic: Boolean,
    /** Original font name (e.g. "Helvetica-Bold"), for diagnostics only. */
    val name: String = "",
) {
    public companion object {
        /** Neutral default: upright sans-serif. */
        public val SansSerif: FontSpec = FontSpec(FontFamily.SansSerif, bold = false, italic = false)
    }
}
