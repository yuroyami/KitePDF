package io.github.yuroyami.kitepdf.svg

import io.github.yuroyami.kitepdf.core.font.Encodings
import io.github.yuroyami.kitepdf.core.font.FontSpec
import io.github.yuroyami.kitepdf.core.font.KiteFontFamily
import io.github.yuroyami.kitepdf.core.font.Standard14Widths
import io.github.yuroyami.kitepdf.core.font.TextGlyph

/**
 * `<text>` support: SVG carries no font file, so the run is measured against
 * the standard-font metrics and handed to the canvas with no outlines, which
 * is the same route EPUB takes for a font it did not embed. The backend picks
 * a host typeface from the [FontSpec].
 */
internal object SvgText {

    /** The standard font whose published metrics stand in for [spec]. */
    private fun metricFont(spec: FontSpec): String {
        val suffix = when {
            spec.bold && spec.italic -> 2
            spec.bold -> 1
            spec.italic -> 3
            else -> 0
        }
        return when (spec.family) {
            KiteFontFamily.Monospace ->
                listOf("Courier", "Courier-Bold", "Courier-BoldOblique", "Courier-Oblique")[suffix]
            KiteFontFamily.Serif ->
                listOf("Times-Roman", "Times-Bold", "Times-BoldItalic", "Times-Italic")[suffix]
            KiteFontFamily.SansSerif ->
                listOf("Helvetica", "Helvetica-Bold", "Helvetica-BoldOblique", "Helvetica-Oblique")[suffix]
        }
    }

    /**
     * Glyph name for a character, via WinAnsi (which covers the Latin text SVG
     * labels are written in). Anything outside it falls back to an average
     * advance, which shifts the run slightly rather than losing it.
     */
    private fun glyphName(ch: Char): String? =
        if (ch.code in 0..255) Encodings.winAnsiEncoding[ch.code] else null

    /** Lay [text] out into glyphs with metric advances (1/1000 em). */
    fun glyphs(text: String, spec: FontSpec): List<TextGlyph> {
        val font = metricFont(spec)
        return text.mapIndexed { i, ch ->
            val w = glyphName(ch)?.let { Standard14Widths.widthOf(font, it) } ?: 500
            TextGlyph(
                byteOffset = i, byteCount = 1, gid = -1, text = ch.toString(),
                advanceWidth = w.toDouble(), outline = null,
                isWordSpace = ch == ' ',
            )
        }
    }

    /** Total advance of [glyphs] at [fontSize], in user units. */
    fun width(glyphs: List<TextGlyph>, fontSize: Double): Double =
        glyphs.sumOf { it.advanceWidth } * fontSize / 1000.0

    /** `text-anchor`: how far left of the anchor the run starts. */
    fun anchorShift(anchor: String?, runWidth: Double): Double = when (anchor?.trim()) {
        "middle" -> -runWidth / 2.0
        "end" -> -runWidth
        else -> 0.0
    }
}
