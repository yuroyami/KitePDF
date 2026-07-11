package io.github.yuroyami.kitepdf.writer

import io.github.yuroyami.kitepdf.font.Encodings
import io.github.yuroyami.kitepdf.font.Standard14Widths

/**
 * Width of [text] set in this [StandardFont] at [fontSize], in text-space units
 * (points when the font size is in points) — the writer-side counterpart of a
 * reader's glyph-advance sum.
 *
 * Characters are mapped through WinAnsi (the implicit encoding for the
 * non-symbolic standard-14 fonts) to glyph names, then summed from the AFM
 * widths in [Standard14Widths]. Characters with no WinAnsi glyph (or any glyph
 * absent from the metrics) fall back to [fallbackWidth] thousandths-of-em.
 *
 * Use it to lay out or truncate text before drawing — e.g. fit a string into a
 * column and append an ellipsis when it overflows.
 */
public fun StandardFont.stringWidth(text: String, fontSize: Double, fallbackWidth: Int = 500): Double {
    var total = 0
    for (ch in text) {
        val code = ch.code
        val glyph = if (code in 0..255) Encodings.winAnsiEncoding[code] else null
        val w = glyph?.let { Standard14Widths.widthOf(baseFont, it) } ?: fallbackWidth
        total += w
    }
    return total / 1000.0 * fontSize
}
