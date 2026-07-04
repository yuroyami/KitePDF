package io.github.yuroyami.kitepdf.epub

import io.github.yuroyami.kitepdf.epub.css.GenericFont
import io.github.yuroyami.kitepdf.font.Encodings
import io.github.yuroyami.kitepdf.font.GlyphList
import io.github.yuroyami.kitepdf.font.Standard14Widths

/**
 * Real per-character advance widths, replacing the old `advanceEm` heuristic.
 *
 * EPUB text is drawn on the Canvas fallback path (no embedded outlines yet), where
 * the backend substitutes a host serif/sans/mono face. That substitute is chosen
 * to be metric-compatible with the PDF base-14 fonts, so the Standard-14 AFM
 * widths in core ([Standard14Widths]) are the right measurements for BOTH line
 * breaking AND the advances handed to `drawGlyphs`. Widths are in 1/1000 em; the
 * layout scales by the run's font size.
 *
 * Mapping is char -> PostScript glyph name -> Standard-14 width. The reverse
 * name table is built once from the WinAnsi and Standard encodings via the core
 * Adobe Glyph List, covering Latin-1 plus the common punctuation books use
 * (curly quotes, dashes, ellipsis).
 */
internal object FontMetrics {

    // Base-14 face names, indexed by (bold?2:0) or (italic?1:0).
    private val SERIF = arrayOf("Times-Roman", "Times-Italic", "Times-Bold", "Times-BoldItalic")
    private val SANS = arrayOf("Helvetica", "Helvetica-Oblique", "Helvetica-Bold", "Helvetica-BoldOblique")
    private val MONO = arrayOf("Courier", "Courier-Oblique", "Courier-Bold", "Courier-BoldOblique")

    /** Width used when a char has no Standard-14 glyph (rare non-Latin symbol). */
    private const val FALLBACK_WIDTH = 500

    /** Courier is monospaced: every glyph advances the same. */
    private const val MONO_WIDTH = 600

    /** unicode codepoint -> PostScript glyph name (WinAnsi first, then Standard). */
    private val uniToGlyph: Map<Int, String> by lazy {
        val m = HashMap<Int, String>(512)
        fun ingest(table: Array<String?>) {
            for (name in table) {
                if (name == null) continue
                val cp = GlyphList.unicodeFor(name) ?: continue
                if (cp !in m) m[cp] = name
            }
        }
        ingest(Encodings.winAnsiEncoding)
        ingest(Encodings.standardEncoding)
        m
    }

    private fun baseFont(bold: Boolean, italic: Boolean, family: GenericFont): String {
        val fam = when (family) {
            GenericFont.MONO -> MONO
            GenericFont.SANS -> SANS
            GenericFont.SERIF -> SERIF
        }
        return fam[(if (bold) 2 else 0) or (if (italic) 1 else 0)]
    }

    /** Advance of [cp] in 1/1000 em for the selected base-14 face. */
    fun advance1000(cp: Int, bold: Boolean = false, italic: Boolean = false, family: GenericFont = GenericFont.SERIF): Int {
        if (isWide(cp)) return 1000 // CJK ideographs and full-width forms are one em
        val glyph = uniToGlyph[cp]
        if (glyph != null) {
            Standard14Widths.widthOf(baseFont(bold, italic, family), glyph)?.let { return it }
        }
        return if (family == GenericFont.MONO) MONO_WIDTH else FALLBACK_WIDTH
    }

    /** True for CJK/full-width code points (one em wide, break between them). */
    fun isWide(cp: Int): Boolean =
        cp in 0x1100..0x115F || cp in 0x2E80..0x303E || cp in 0x3041..0x33FF ||
            cp in 0x3400..0x4DBF || cp in 0x4E00..0x9FFF || cp in 0xA000..0xA4CF ||
            cp in 0xAC00..0xD7A3 || cp in 0xF900..0xFAFF || cp in 0xFE30..0xFE4F ||
            cp in 0xFF00..0xFF60 || cp in 0xFFE0..0xFFE6

    /** Advance of [ch] in points at [fontSize]. */
    fun advancePt(ch: Char, fontSize: Double, bold: Boolean = false, italic: Boolean = false, family: GenericFont = GenericFont.SERIF): Double =
        advance1000(ch.code, bold, italic, family) * fontSize / 1000.0
}
