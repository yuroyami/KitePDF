package io.github.yuroyami.kitepdf.text

import io.github.yuroyami.kitepdf.PageContents
import io.github.yuroyami.kitepdf.PdfDocument
import io.github.yuroyami.kitepdf.PdfPage
import io.github.yuroyami.kitepdf.content.ContentStreamParser
import io.github.yuroyami.kitepdf.content.Operation
import io.github.yuroyami.kitepdf.core.font.PdfFont
import io.github.yuroyami.kitepdf.core.parser.PdfDictionary
import io.github.yuroyami.kitepdf.core.parser.PdfReference
import io.github.yuroyami.kitepdf.core.parser.PdfArray
import io.github.yuroyami.kitepdf.core.parser.PdfInt
import io.github.yuroyami.kitepdf.core.parser.PdfName
import io.github.yuroyami.kitepdf.core.parser.PdfReal
import io.github.yuroyami.kitepdf.core.parser.PdfStream
import io.github.yuroyami.kitepdf.core.parser.PdfString

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
 * font's `/Encoding` and `/ToUnicode` CMap apply. This is what makes composite
 * Type 0 fonts (Identity-H, 2-byte codes) and embedded CJK extract correctly
 * rather than byte-for-byte. When no font is resolvable the bytes fall back to
 * `PdfString.asText()` (PDFDocEncoding / UTF-16BE BOM detection).
 *
 * Line breaks are inserted on `BT/ET`, `Td`, `TD`, `T*`, `Tm` heuristics
 * because PDF text positioning is geometric, not line-based. Those
 * size-scaled heuristics read the `Tf` size times the `Tm` scale, since a
 * producer may set `Tf` to 1 and carry the real size in the matrix.
 *
 * A form XObject that the page draws with `Do` adds its text in place, read with the form's
 * own fonts (ISO 32000-1, 8.10, #459). A marked-content sequence with `/ActualText` adds that
 * text in place of the text it encloses (14.9.4, #468).
 *
 * This is the cheap linear path: it tracks `Tm` but not `cm` or the wider
 * graphics stack, so the order is the order of the content stream.
 * [PdfPage.structuredText] gives positions and reading order.
 */
public object TextExtractor {

    public fun extract(page: PdfPage): String {
        // The renderer's colour spaces, so inline images end at the same byte (#266).
        val colorSpaces = ContentStreamParser.colorSpaces(page.resources, page.internalDocument)
        val ops = page.operations(colorSpaces)
        val sb = StringBuilder()
        Walk(page.internalDocument, page.resources, sb).run(ops, page.resources, depth = 0)
        return tidy(sb)
    }

    /** Font-blind overload for callers that have operations but no resources: it draws no forms. */
    public fun extract(ops: List<Operation>): String {
        val sb = StringBuilder()
        Walk(null, null, sb).run(ops, null, depth = 0)
        return tidy(sb)
    }

    private fun tidy(sb: StringBuilder): String = sb.toString()
        .replace(SPACES_RUN, " ")
        .replace(BLANK_LINES_RUN, "\n\n")
        .trim()

    /** One extraction: the text so far, and the state that spans form XObjects. */
    private class Walk(
        private val document: PdfDocument?,
        /** A form without resources of its own reads the page's (ISO 32000-1, 7.8.3). */
        private val pageResources: PdfDictionary?,
        private val sb: StringBuilder,
    ) {
        /** Operations left to read, forms included, so repeated forms cannot multiply without end. */
        private var budget = MAX_OPS

        /** Forms being read, so a form that draws itself stops. */
        private val openForms = HashSet<Long>()

        /** Open `/ActualText` sequences: their own text replaces what they enclose. */
        private var replacing = 0

        fun run(ops: List<Operation>, resources: PdfDictionary?, depth: Int) {
            val fonts = loadFonts(resources)
            // Each entry says whether that marked-content sequence opened an ActualText replacement.
            val marked = ArrayList<Boolean>()
            var inText = false
            var font: PdfFont? = null
            var fontSize = 0.0

            // Text-line baseline Y in text space (origin of the current line, per the
            // text-line matrix Tlm). A pure-horizontal Td/Tm keeps the same Y, so no
            // newline is emitted; only a genuine vertical move opens a new line.
            var lineY = Double.NaN
            var haveLine = false
            // Tm Y-basis length. Producers that write `/F1 1 Tf` carry the real font
            // size here, so the size-scaled heuristics below need it (#22).
            var tmScaleY = 1.0

            for (op in ops) {
                if (--budget < 0) break
                when (op.operator) {
                    "Do" -> {
                        val name = (op.operands.firstOrNull() as? PdfName)?.value
                        if (name != null && depth < MAX_FORM_DEPTH) form(name, resources, depth)
                    }
                    "BDC" -> {
                        val actual = actualText(op.operands.getOrNull(1), resources)
                        if (actual != null && replacing == 0) sb.append(actual)
                        marked.add(actual != null)
                        if (actual != null) replacing++
                    }
                    "BMC" -> marked.add(false)
                    "EMC" -> if (marked.isNotEmpty() && marked.removeAt(marked.lastIndex)) replacing--
                    "BT" -> {
                        inText = true
                        lineY = 0.0
                        haveLine = false
                        tmScaleY = 1.0 // Tm resets to identity at BT.
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
                        // tx ty relative to the current line origin, in PRE-Tm units,
                        // so scale it to keep lineY in the same space as Tm's f. Only
                        // a vertical shift beyond the threshold counts as a new line.
                        val ty = number(op.operands.getOrNull(1))
                        val newY = (if (haveLine) lineY else 0.0) + ty * tmScaleY
                        maybeBreakLine(sb, inText, newY, lineY, haveLine, fontSize * tmScaleY)
                        lineY = newY
                        haveLine = true
                    }
                    "Tm" -> {
                        // a b c d e f: f is the new line-origin Y in text space, and
                        // c/d give the Y-basis length (the scale the size rides on).
                        val c = number(op.operands.getOrNull(2))
                        val d = number(op.operands.getOrNull(3))
                        val scale = kotlin.math.sqrt(c * c + d * d)
                        tmScaleY = if (scale.isFinite() && scale > 0.0) scale else 1.0
                        val newY = number(op.operands.getOrNull(5))
                        maybeBreakLine(sb, inText, newY, lineY, haveLine, fontSize * tmScaleY)
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
                        if (replacing == 0) sb.append(decode(s, font))
                    }
                    "'" -> {
                        sb.append('\n')
                        val s = op.operands.firstOrNull() as? PdfString ?: continue
                        if (replacing == 0) sb.append(decode(s, font))
                    }
                    "\"" -> {
                        sb.append('\n')
                        // operands: aw ac string. The string is last.
                        val s = op.operands.lastOrNull() as? PdfString ?: continue
                        if (replacing == 0) sb.append(decode(s, font))
                    }
                    "TJ" -> {
                        if (replacing > 0) continue
                        val arr = op.operands.firstOrNull() as? PdfArray ?: continue
                        // TJ numbers are in thousandths of a text-space em, applied to
                        // the pen before the font-size scale. A negative adjustment
                        // moves the pen forward (a gap). Scale the word-break threshold
                        // with the effective font size so a large font needs a
                        // proportionally larger gap to read as a space.
                        val emThreshold = wordGapThreshold(fontSize * tmScaleY)
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
            // A sequence this stream left open ends with it.
            for (opened in marked) if (opened) replacing--
        }

        /** Reads the form XObject [name] of [resources] in place, with its own resources. */
        private fun form(name: String, resources: PdfDictionary?, depth: Int) {
            val doc = document ?: return
            val entry = resources?.getDict("XObject", doc)?.get(name) ?: return
            val stream = runCatching { entry.resolve(doc) }.getOrNull() as? PdfStream ?: return
            if (stream.dict.getName("Subtype") != "Form") return
            val number = (entry as? PdfReference)?.objectNumber
            if (number != null && !openForms.add(number)) return
            try {
                val own = stream.dict.getDict("Resources", doc)
                val formResources = own ?: pageResources
                val colorSpaces = ContentStreamParser.colorSpaces(formResources, doc)
                val parse = { ContentStreamParser.parse(PageContents.decodeNested(stream, "form XObject"), colorSpaces) }
                // A form with resources of its own parses the same way wherever it is drawn (#118).
                val ops = if (own != null && number != null) doc.operations(number, parse) else parse()
                run(ops, formResources, depth + 1)
            } finally {
                if (number != null) openForms.remove(number)
            }
        }

        /** The `/ActualText` of a `BDC` property list, given inline or named in `/Properties`, or null. */
        private fun actualText(operand: Any?, resources: PdfDictionary?): String? {
            val doc = document
            val props = when (operand) {
                is PdfDictionary -> operand
                is PdfName -> doc?.let { resources?.getDict("Properties", it)?.getDict(operand.value, it) }
                else -> null
            } ?: return null
            val value = props["ActualText"]?.let { v -> if (doc != null) runCatching { v.resolve(doc) }.getOrNull() else v }
            return (value as? PdfString)?.asText()
        }

        /** Resolve the `/Font` entries of [resources] to [PdfFont]s (same path the renderer uses). */
        private fun loadFonts(resources: PdfDictionary?): Map<String, PdfFont> {
            val resolver = document ?: return emptyMap()
            val fonts = resources?.getDict("Font", resolver) ?: return emptyMap()
            return fonts.map.mapValues { (_, ref) ->
                (ref as? PdfReference)?.let { reference -> resolver.font(reference.objectNumber) { PdfFont.from(ref, resolver) } }
                    ?: PdfFont.from(ref, resolver)
            }
        }
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
        effectiveFontSize: Double,
    ) {
        if (!inText || !haveLine) return
        // Threshold scales with font size (fall back to a small absolute value
        // when the size is unknown). A move smaller than this is intra-line
        // kerning/subscript jitter, not a new line.
        val tol = if (effectiveFontSize > 0.0) effectiveFontSize * 0.3 else 1.0
        if (kotlin.math.abs(newY - oldY) > tol) sb.append('\n')
    }

    /**
     * TJ inter-glyph gap threshold, in raw TJ units (thousandths of a text-space
     * em). A negative adjustment ≤ this magnitude implies a word space. Fixed in
     * TJ units (0.20 em ⇒ -200) because the array values are already normalised
     * to the em; the effective device gap then scales with font size when the
     * renderer multiplies by the font size, so word detection holds across sizes.
     */
    private fun wordGapThreshold(effectiveFontSize: Double): Double {
        // Larger fonts tolerate slightly larger kerning before a gap reads as a
        // space; smaller fonts should trip sooner. Anchor at -200 (0.20 em) and
        // nudge with size so the visual gap needed stays roughly constant.
        val base = -200.0
        return when {
            effectiveFontSize <= 0.0 -> base
            effectiveFontSize >= 18.0 -> base * 1.2   // large font ⇒ need a wider gap
            effectiveFontSize <= 6.0 -> base * 0.75   // tiny font ⇒ trip sooner
            else -> base
        }
    }

    private fun decode(s: PdfString, font: PdfFont?): String =
        font?.decode(s.bytes) ?: s.asText()

    /** Form nesting and operations read per page, the renderer's bounds. */
    private const val MAX_FORM_DEPTH = 15
    private const val MAX_OPS = 20_000_000L

    // Compiled once, not per extract() call.
    private val SPACES_RUN = Regex("[ \\t]+")
    private val BLANK_LINES_RUN = Regex("\\n{3,}")
}
