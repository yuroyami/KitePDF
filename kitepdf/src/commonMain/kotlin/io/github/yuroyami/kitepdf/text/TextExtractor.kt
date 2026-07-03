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
object TextExtractor {

    fun extract(page: PdfPage): String {
        val ops = ContentStreamParser.parse(page.contentBytes)
        return extract(ops, loadFonts(page))
    }

    /** Font-blind overload for callers that have operations but no resources. */
    fun extract(ops: List<Operation>): String = extract(ops, emptyMap())

    private fun extract(ops: List<Operation>, fonts: Map<String, PdfFont>): String {
        val sb = StringBuilder()
        var inText = false
        var font: PdfFont? = null

        for (op in ops) {
            when (op.operator) {
                "BT" -> inText = true
                "ET" -> {
                    if (inText) sb.append('\n')
                    inText = false
                }
                "Tf" -> font = (op.operands.firstOrNull() as? PdfName)?.let { fonts[it.value] }
                "Td", "TD", "T*", "Tm" -> if (inText) sb.append('\n')
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
                    for (item in arr) {
                        when (item) {
                            is PdfString -> sb.append(decode(item, font))
                            is PdfReal -> {
                                if (item.value <= -100.0) sb.append(' ')
                            }
                            is PdfInt -> {
                                if (item.value <= -100L) sb.append(' ')
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
