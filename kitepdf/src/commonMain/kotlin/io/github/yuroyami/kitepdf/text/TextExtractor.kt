package io.github.yuroyami.kitepdf.text

import io.github.yuroyami.kitepdf.PdfPage
import io.github.yuroyami.kitepdf.content.ContentStreamParser
import io.github.yuroyami.kitepdf.content.Operation
import io.github.yuroyami.kitepdf.parser.PdfArray
import io.github.yuroyami.kitepdf.parser.PdfInt
import io.github.yuroyami.kitepdf.parser.PdfReal
import io.github.yuroyami.kitepdf.parser.PdfString

/**
 * Naive text extraction (ISO 32000-1 §9.4).
 *
 * Walks content-stream operations for text-showing operators:
 *   - `Tj`  : show a string
 *   - `TJ`  : show an array of strings/spacing adjustments
 *   - `'`   : move to next line and show
 *   - `"`   : set spacing, move to next line, and show
 *
 * Output decoding: we treat the showed bytes as PDFDocEncoding (close to
 * latin-1) when no font/encoding is known. UTF-16BE BOM strings are detected
 * via PdfString.asText(). PDF spec compliance for *real* text extraction
 * requires resolving each font's /Encoding and /ToUnicode CMap — that's a
 * session-2 deliverable. The current output is a useful first approximation
 * for documents using standard fonts with WinAnsi/PDFDocEncoding.
 *
 * Line breaks are inserted on `BT/ET`, `Td`, `TD`, `T*`, `Tm` heuristics
 * because PDF text positioning is geometric, not line-based.
 */
object TextExtractor {

    fun extract(page: PdfPage): String {
        val ops = ContentStreamParser.parse(page.contentBytes)
        return extract(ops)
    }

    fun extract(ops: List<Operation>): String {
        val sb = StringBuilder()
        var inText = false

        for (op in ops) {
            when (op.operator) {
                "BT" -> inText = true
                "ET" -> {
                    if (inText) sb.append('\n')
                    inText = false
                }
                "Td", "TD", "T*", "Tm" -> if (inText) sb.append('\n')
                "Tj" -> {
                    val s = op.operands.firstOrNull() as? PdfString ?: continue
                    sb.append(decode(s))
                }
                "'" -> {
                    sb.append('\n')
                    val s = op.operands.firstOrNull() as? PdfString ?: continue
                    sb.append(decode(s))
                }
                "\"" -> {
                    sb.append('\n')
                    // operands: aw ac string — string is last
                    val s = op.operands.lastOrNull() as? PdfString ?: continue
                    sb.append(decode(s))
                }
                "TJ" -> {
                    val arr = op.operands.firstOrNull() as? PdfArray ?: continue
                    for (item in arr) {
                        when (item) {
                            is PdfString -> sb.append(decode(item))
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

    private fun decode(s: PdfString): String = s.asText()

    // Compiled once, not per extract() call.
    private val SPACES_RUN = Regex("[ \\t]+")
    private val BLANK_LINES_RUN = Regex("\\n{3,}")
}
