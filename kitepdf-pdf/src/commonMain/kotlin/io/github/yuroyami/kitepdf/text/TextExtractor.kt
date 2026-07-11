package io.github.yuroyami.kitepdf.text

import io.github.yuroyami.kitepdf.PdfPage
import io.github.yuroyami.kitepdf.content.ContentStreamParser
import io.github.yuroyami.kitepdf.content.Operation
import io.github.yuroyami.kitepdf.font.PdfFont
import io.github.yuroyami.kitepdf.parser.PdfArray
import io.github.yuroyami.kitepdf.parser.PdfInt
import io.github.yuroyami.kitepdf.parser.PdfName
import io.github.yuroyami.kitepdf.parser.PdfReal
import io.github.yuroyami.kitepdf.parser.PdfString

/**
 * Linear text extraction (ISO 32000-1 §9.4).
 *
 * Walks content-stream operations for text-showing operators:
 *   - `Tj`  : show a string
 *   - `TJ`  : show an array of strings/spacing adjustments
 *   - `'`   : move to next line and show
 *   - `"`   : set spacing, move to next line, and show
 *
 * Show strings are decoded through the *current font* (set by `Tf`), so each
 * font's `/Encoding` and `/ToUnicode` CMap apply — this is what makes composite
 * Type 0 fonts (Identity-H, 2-byte codes) and embedded CJK extract correctly
 * rather than byte-for-byte. When no font is resolvable the bytes fall back to
 * `PdfString.asText()` (PDFDocEncoding / UTF-16BE BOM detection).
 *
 * Line breaks are inserted on `BT/ET`, `Td`, `TD`, `T*`, `Tm` heuristics
 * because PDF text positioning is geometric, not line-based.
 */
public object TextExtractor {

    public fun extract(page: PdfPage): String {
        val ops = ContentStreamParser.parse(page.contentBytes)
        return extract(ops, loadFonts(page))
    }

    /** Font-blind overload for callers that have operations but no resources. */
    public fun extract(ops: List<Operation>): String = extract(ops, emptyMap())

    private fun extract(ops: List<Operation>, fonts: Map<String, PdfFont>): String {
        val sb = StringBuilder()
        var inText = false
        var font: PdfFont? = null
        var fontSize = 0.0

        // Text-line baseline Y in text space (origin of the current line, per the
        // text-line matrix Tlm). A pure-horizontal Td/Tm keeps the same Y, so no
        // newline is emitted; only a genuine vertical move opens a new line.
        var lineY = Double.NaN
        var haveLine = false

        for (op in ops) {
            when (op.operator) {
                "BT" -> {
                    inText = true
                    lineY = 0.0
                    haveLine = false
                }
                "ET" -> {
                    if (inText) sb.append('\n')
                    inText = false
                    haveLine = false
                }
                "Tf" -> {
                    font = (op.operands.firstOrNull() as? PdfName)?.let { fonts[it.value] }
                    fontSize = (op.operands.getOrNull(1) as? PdfReal)?.value
                        ?: (op.operands.getOrNull(1) as? PdfInt)?.value?.toDouble()
                        ?: fontSize
                }
                "Td", "TD" -> {
                    // tx ty relative to the current line origin. Only a vertical
                    // shift beyond the threshold counts as a new line.
                    val ty = number(op.operands.getOrNull(1))
                    val newY = (if (haveLine) lineY else 0.0) + ty
                    maybeBreakLine(sb, inText, newY, lineY, haveLine, fontSize)
                    lineY = newY
                    haveLine = true
                }
                "Tm" -> {
                    // a b c d e f — f is the new line-origin Y in text space.
                    val newY = number(op.operands.getOrNull(5))
                    maybeBreakLine(sb, inText, newY, lineY, haveLine, fontSize)
                    lineY = newY
                    haveLine = true
                }
                "T*" -> {
                    // Always advances to the next line (by leading).
                    if (inText) sb.append('\n')
                    haveLine = true
                }
                "Tj" -> {
                    val s = op.operands.firstOrNull() as? PdfString ?: continue
                    sb.append(decode(s, font))
                }
                "'" -> {
                    sb.append('\n')
                    val s = op.operands.firstOrNull() as? PdfString ?: continue
                    sb.append(decode(s, font))
                }
                "\"" -> {
                    sb.append('\n')
                    // operands: aw ac string — string is last
                    val s = op.operands.lastOrNull() as? PdfString ?: continue
                    sb.append(decode(s, font))
                }
                "TJ" -> {
                    val arr = op.operands.firstOrNull() as? PdfArray ?: continue
                    // TJ numbers are in thousandths of a text-space em, applied to
                    // the pen before the font-size scale. A negative adjustment
                    // moves the pen forward (a gap). Scale the word-break threshold
                    // with the effective font size so a large font needs a
                    // proportionally larger gap to read as a space.
                    val emThreshold = wordGapThreshold(fontSize)
                    for (item in arr) {
                        when (item) {
                            is PdfString -> sb.append(decode(item, font))
                            is PdfReal -> {
                                if (item.value <= emThreshold) sb.append(' ')
                            }
                            is PdfInt -> {
                                if (item.value.toDouble() <= emThreshold) sb.append(' ')
                            }
                            else -> { /* ignore */ }
                        }
                    }
                }
            }
        }
        return sb.toString()
            .replace(SPACES_RUN, " ")
            .replace(BLANK_LINES_RUN, "\n\n")
            .trim()
    }

    private fun number(v: Any?): Double = when (v) {
        is PdfReal -> v.value
        is PdfInt -> v.value.toDouble()
        else -> 0.0
    }

    /**
     * Emit a line break only when the baseline moved vertically beyond a
     * threshold. TJ kerning re-positions text horizontally on the same line,
     * so a same-baseline Td/Tm must not spray a newline.
     */
    private fun maybeBreakLine(
        sb: StringBuilder,
        inText: Boolean,
        newY: Double,
        oldY: Double,
        haveLine: Boolean,
        fontSize: Double,
    ) {
        if (!inText || !haveLine) return
        // Threshold scales with font size (fall back to a small absolute value
        // when the size is unknown). A move smaller than this is intra-line
        // kerning/subscript jitter, not a new line.
        val tol = if (fontSize > 0.0) fontSize * 0.3 else 1.0
        if (kotlin.math.abs(newY - oldY) > tol) sb.append('\n')
    }

    /**
     * TJ inter-glyph gap threshold, in raw TJ units (thousandths of a text-space
     * em). A negative adjustment ≤ this magnitude implies a word space. Fixed in
     * TJ units (0.20 em ⇒ -200) because the array values are already normalised
     * to the em; the effective device gap then scales with font size when the
     * renderer multiplies by the font size, so word detection holds across sizes.
     */
    private fun wordGapThreshold(fontSize: Double): Double {
        // Larger fonts tolerate slightly larger kerning before a gap reads as a
        // space; smaller fonts should trip sooner. Anchor at -200 (0.20 em) and
        // nudge with size so the visual gap needed stays roughly constant.
        val base = -200.0
        return when {
            fontSize <= 0.0 -> base
            fontSize >= 18.0 -> base * 1.2   // large font ⇒ need a wider gap
            fontSize <= 6.0 -> base * 0.75   // tiny font ⇒ trip sooner
            else -> base
        }
    }

    /** Resolve the page's `/Resources /Font` entries to [PdfFont]s (same path the renderer uses). */
    private fun loadFonts(page: PdfPage): Map<String, PdfFont> {
        val resolver = page.internalDocument
        val fonts = page.resources?.getDict("Font", resolver) ?: return emptyMap()
        return fonts.map.mapValues { (_, ref) -> PdfFont.from(ref, resolver) }
    }

    private fun decode(s: PdfString, font: PdfFont?): String =
        font?.decode(s.bytes) ?: s.asText()

    // Compiled once, not per extract() call.
    private val SPACES_RUN = Regex("[ \\t]+")
    private val BLANK_LINES_RUN = Regex("\\n{3,}")
}
