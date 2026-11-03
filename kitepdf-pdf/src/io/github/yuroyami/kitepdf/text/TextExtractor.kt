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
import io.github.yuroyami.kitepdf.core.render.KiteMatrix
import io.github.yuroyami.kitepdf.missingAsNull
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sqrt

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
 * A label that the page draws twice in one place reads once (#644). A fake bold fills a label
 * and then strokes it, or fills it again a fraction of a point aside, and a halo strokes it in
 * white under its fill, each pass a text-showing operator of its own (9.4.3). So the walk also
 * moves a pen as the renderer does, through the text matrix, the advance of each glyph and the
 * CTM (9.4.2 and 9.4.4), and skips a show when an earlier show that stayed drew the same text
 * there: at the same size within a tenth, in the same direction, and with both ends less than a
 * tenth of an em away. [RedrawnText] makes the same test glyph by glyph for the structured text.
 * This walk compares whole shows, so a second pass that splits its text into other shows than
 * the first reads twice here. Shows that follow each other along a line never match each other,
 * and a space never matches, so double letters in tight type and the gap after a leader stay.
 *
 * This is the cheap linear path: the order is the order of the content stream, and the line
 * breaks follow `Tm` and `Td` alone. [PdfPage.structuredText] gives positions and reading order.
 */
public object TextExtractor {

    public fun extract(page: PdfPage): String {
        // The renderer's colour spaces, so inline images end at the same byte (#266).
        val colorSpaces = ContentStreamParser.colorSpaces(page.resources, page.internalDocument)
        val ops = page.operations(colorSpaces)
        val sb = StringBuilder()
        Walk(page.internalDocument, page.resources, sb).run(ops, page.resources, depth = 0, Pen(Placement()))
        return tidy(sb)
    }

    /**
     * Font-blind overload for callers that have operations but no resources: it draws no forms.
     * Without the widths of a font it cannot tell where a show ends, so it skips no second pass.
     */
    public fun extract(ops: List<Operation>): String {
        val sb = StringBuilder()
        Walk(null, null, sb).run(ops, null, depth = 0, Pen(Placement()))
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

        /**
         * Line breaks not written yet. A break waits for the next text, so a skipped show leaves
         * no blank line: the breaks on each side of it make one run, as long as the longer side.
         */
        private var breaks = 0
        private var heldBreaks = 0
        private var skippedSinceText = false

        /** The shows that stayed, by their text, the latest first. */
        private val shown = HashMap<String, Shown>()

        /**
         * Counts the moves that set the pen. The shows of one count follow each other along a line,
         * so they never match each other, as the glyphs of one run never do in [RedrawnText].
         */
        private var chain = 0

        /** Whether the last show was skipped, and its [chain], so that a space after it goes with it. */
        private var lastSkipped = false
        private var lastChain = -1

        /** The text of one `TJ`, built before the walk knows whether it stays. */
        private val arrayText = StringBuilder()

        /** The outermost `/ActualText` sequence while it is open, and the shows it stands for. */
        private var replacement: Replacement? = null

        fun run(ops: List<Operation>, resources: PdfDictionary?, depth: Int, pen: Pen) {
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
                    // The pen follows the graphics state, which the line breaks do not read.
                    "q" -> { pen.save(); chain++ }
                    "Q" -> { pen.restore(); chain++ }
                    "cm" -> { pen.transform(matrix(op.operands)); chain++ }
                    "Do" -> {
                        val name = (op.operands.firstOrNull() as? PdfName)?.value
                        chain++
                        if (name != null && depth < MAX_FORM_DEPTH) form(name, resources, depth, pen)
                        chain++
                    }
                    "BDC" -> {
                        val actual = actualText(op.operands.getOrNull(1), resources)
                        if (actual != null && replacing == 0) replace(actual)
                        marked.add(actual != null)
                        if (actual != null) replacing++
                    }
                    "BMC" -> marked.add(false)
                    "EMC" -> if (marked.isNotEmpty() && marked.removeAt(marked.lastIndex) && --replacing == 0) replaced()
                    "BT" -> {
                        inText = true
                        lineY = 0.0
                        haveLine = false
                        tmScaleY = 1.0 // Tm resets to identity at BT.
                        pen.beginText()
                        chain++
                    }
                    "ET" -> {
                        if (inText) lineBreak()
                        inText = false
                        haveLine = false
                    }
                    "Tf" -> {
                        val named = (op.operands.firstOrNull() as? PdfName)?.let { fonts[it.value] }
                        font = named
                        fontSize = (op.operands.getOrNull(1) as? PdfReal)?.value
                            ?: (op.operands.getOrNull(1) as? PdfInt)?.value?.toDouble()
                            ?: fontSize
                        pen.placement.font = named
                        pen.placement.size = number(op.operands.getOrNull(1))
                    }
                    "Tc" -> pen.placement.charSpacing = number(op.operands.firstOrNull())
                    "Tw" -> pen.placement.wordSpacing = number(op.operands.firstOrNull())
                    "Tz" -> pen.placement.scale = number(op.operands.firstOrNull()) / 100.0
                    "TL" -> pen.placement.leading = number(op.operands.firstOrNull())
                    "Ts" -> pen.placement.rise = number(op.operands.firstOrNull())
                    "Td", "TD" -> {
                        // tx ty relative to the current line origin, in PRE-Tm units,
                        // so scale it to keep lineY in the same space as Tm's f. Only
                        // a vertical shift beyond the threshold counts as a new line.
                        val ty = number(op.operands.getOrNull(1))
                        val newY = (if (haveLine) lineY else 0.0) + ty * tmScaleY
                        maybeBreakLine(inText, newY, lineY, haveLine, fontSize * tmScaleY)
                        lineY = newY
                        haveLine = true
                        pen.move(number(op.operands.firstOrNull()), ty, setLeading = op.operator == "TD")
                        chain++
                    }
                    "Tm" -> {
                        // a b c d e f: f is the new line-origin Y in text space, and
                        // c/d give the Y-basis length (the scale the size rides on).
                        val c = number(op.operands.getOrNull(2))
                        val d = number(op.operands.getOrNull(3))
                        val scale = sqrt(c * c + d * d)
                        tmScaleY = if (scale.isFinite() && scale > 0.0) scale else 1.0
                        val newY = number(op.operands.getOrNull(5))
                        maybeBreakLine(inText, newY, lineY, haveLine, fontSize * tmScaleY)
                        lineY = newY
                        haveLine = true
                        pen.setMatrix(matrix(op.operands))
                        chain++
                    }
                    "T*" -> {
                        // Always advances to the next line (by leading).
                        if (inText) lineBreak()
                        haveLine = true
                        pen.nextLine()
                        chain++
                    }
                    "Tj" -> {
                        val s = op.operands.firstOrNull() as? PdfString ?: continue
                        val at = pen.show(s, chain)
                        if (replacing == 0) show(decode(s, font), at) else enclosed(at)
                    }
                    "'" -> {
                        lineBreak()
                        pen.nextLine()
                        chain++
                        val s = op.operands.firstOrNull() as? PdfString ?: continue
                        val at = pen.show(s, chain)
                        if (replacing == 0) show(decode(s, font), at) else enclosed(at)
                    }
                    "\"" -> {
                        lineBreak()
                        pen.placement.wordSpacing = number(op.operands.getOrNull(0))
                        pen.placement.charSpacing = number(op.operands.getOrNull(1))
                        pen.nextLine()
                        chain++
                        // operands: aw ac string. The string is last.
                        val s = op.operands.lastOrNull() as? PdfString ?: continue
                        val at = pen.show(s, chain)
                        if (replacing == 0) show(decode(s, font), at) else enclosed(at)
                    }
                    "TJ" -> {
                        val arr = op.operands.firstOrNull() as? PdfArray ?: continue
                        val at = pen.show(arr, chain)
                        if (replacing > 0) {
                            enclosed(at)
                            continue
                        }
                        // TJ numbers are in thousandths of a text-space em, applied to
                        // the pen before the font-size scale. A negative adjustment
                        // moves the pen forward (a gap). Scale the word-break threshold
                        // with the effective font size so a large font needs a
                        // proportionally larger gap to read as a space.
                        val emThreshold = wordGapThreshold(fontSize * tmScaleY)
                        arrayText.setLength(0)
                        for (item in arr) {
                            when (item) {
                                is PdfString -> arrayText.append(decode(item, font))
                                is PdfReal -> {
                                    if (item.value <= emThreshold) arrayText.append(' ')
                                }
                                is PdfInt -> {
                                    if (item.value.toDouble() <= emThreshold) arrayText.append(' ')
                                }
                                else -> { /* ignore */ }
                            }
                        }
                        show(arrayText.toString(), at)
                    }
                }
            }
            // A sequence this stream left open ends with it.
            for (opened in marked) if (opened && --replacing == 0) replaced()
        }

        /**
         * Writes [text], the text of one show, unless a show that stayed drew the same text in
         * its place. [at] is where [Pen.show] placed it, or null when the walk cannot tell.
         */
        private fun show(text: String, at: Shown?) {
            val blank = RedrawnText.blank(text)
            // A space paints nothing and never matches, but it goes with a skipped show before it on its line.
            val again = if (blank) lastSkipped && lastChain == chain else at != null && drawnBefore(text, at)
            lastSkipped = again
            lastChain = chain
            if (again) {
                skip()
                return
            }
            if (at != null && !blank) remember(text, at)
            write(text)
        }

        /** Writes the `/ActualText` [text] of a sequence that opens, and keeps what its end needs to take it back. */
        private fun replace(text: String) {
            val r = if (RedrawnText.blank(text)) null else Replacement(text, sb.length, breaks, heldBreaks)
            write(text)
            r?.end = sb.length
            replacement = r
        }

        /** A show inside the open replacement, which stands for it. */
        private fun enclosed(at: Shown?) {
            val r = replacement ?: return
            if (at == null) r.lost = true
            if (r.first == null) r.first = at
            r.last = at
        }

        /**
         * The outermost replacement ends. Its text reads as one show from the start of its first
         * show to the end of its last, so two passes that carry the same `/ActualText` read once.
         * Nothing is written while a replacement is open, so a second pass takes its text back.
         */
        private fun replaced() {
            val r = replacement ?: return
            replacement = null
            val first = r.first
            val last = r.last
            val at = if (r.lost || first == null || last == null) null
            else Shown(first.x0, first.y0, last.x1, last.y1, first.size, first.ux, first.uy, first.chain)
            val again = at != null && sb.length == r.end && drawnBefore(r.text, at)
            lastSkipped = again
            lastChain = chain
            if (again) {
                // As if the text had never been written and the replacement were a skipped show.
                sb.setLength(r.mark)
                heldBreaks = max(r.heldBreaks, r.breaks)
                skippedSinceText = true
            } else if (at != null) {
                remember(r.text, at)
            }
        }

        private fun remember(text: String, at: Shown) {
            at.next = shown[text]
            shown[text] = at
        }

        /**
         * Whether a show that stayed drew [text] in the place of [at], by the test of
         * [RedrawnText]: the same size within a tenth, the same direction, and a start and an
         * end less than a tenth of an em away on each axis. The end keeps a pass in a font of
         * other widths, which drifts away along the label, as [RedrawnText] keeps such a run.
         */
        private fun drawnBefore(text: String, at: Shown): Boolean {
            val reach = RedrawnText.MAX_DIST * at.size
            var e = shown[text]
            var candidates = MAX_CANDIDATES
            while (e != null && --candidates >= 0) {
                if (e.chain != at.chain &&
                    abs(e.x0 - at.x0) < reach && abs(e.y0 - at.y0) < reach &&
                    abs(e.x1 - at.x1) < reach && abs(e.y1 - at.y1) < reach &&
                    abs(e.size - at.size) <= RedrawnText.MAX_DIST * max(e.size, at.size) &&
                    e.ux * at.ux + e.uy * at.uy >= RedrawnText.SAME_DIRECTION
                ) {
                    return true
                }
                e = e.next
            }
            return false
        }

        /**
         * Emit a line break only when the baseline moved vertically beyond a
         * threshold. TJ kerning re-positions text horizontally on the same line,
         * so a same-baseline Td/Tm must not spray a newline.
         */
        private fun maybeBreakLine(
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
            if (abs(newY - oldY) > tol) lineBreak()
        }

        private fun lineBreak() {
            breaks++
        }

        /** Writes [text] after the line breaks that wait for it. */
        private fun write(text: String) {
            if (text.isEmpty()) return
            val count = if (skippedSinceText) max(heldBreaks, breaks) else breaks
            repeat(count) { sb.append('\n') }
            breaks = 0
            heldBreaks = 0
            skippedSinceText = false
            sb.append(text)
        }

        /** A skipped show: the breaks before it and the breaks after it make one run. */
        private fun skip() {
            heldBreaks = max(heldBreaks, breaks)
            breaks = 0
            skippedSinceText = true
        }

        /** Reads the form XObject [name] of [resources] in place, with its own resources. */
        private fun form(name: String, resources: PdfDictionary?, depth: Int, pen: Pen) {
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
                // The form matrix maps form space to the user space of the `Do` (ISO 32000-1, 8.10.1).
                val formMatrix = missingAsNull { stream.dict.getArray("Matrix", doc) }?.let { matrix(it) } ?: KiteMatrix.IDENTITY
                run(ops, formResources, depth + 1, Pen(pen.placementFor(formMatrix)))
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

    /**
     * The part of the graphics state that places text: the CTM and the text state parameters,
     * which `q` and `Q` save and restore (ISO 32000-1, 8.4.1 and 9.3.1). [scale] is `Tz` over 100.
     * The font that decodes the text does not come back at `Q`, as it never has here, so the text
     * stays as it was, and only the pen reads this one.
     */
    private class Placement(
        var ctm: KiteMatrix = KiteMatrix.IDENTITY,
        var font: PdfFont? = null,
        var size: Double = 0.0,
        var charSpacing: Double = 0.0,
        var wordSpacing: Double = 0.0,
        var scale: Double = 1.0,
        var leading: Double = 0.0,
        var rise: Double = 0.0,
    ) {
        fun copy() = Placement(ctm, font, size, charSpacing, wordSpacing, scale, leading, rise)
    }

    /**
     * Where the shows of one content stream draw, moved as the renderer moves it: the
     * [placement], and the text matrix and line matrix of the open text object (ISO 32000-1,
     * 9.4.2). The pen is lost after a show whose advance the walk cannot tell, in a Type 3
     * font, whose widths are in glyph space (9.6.5), or with no font, until a move sets it again.
     */
    private class Pen(var placement: Placement) {
        private var tm = KiteMatrix.IDENTITY
        private var tlm = KiteMatrix.IDENTITY
        private var known = true

        /** What a `q` saved. The renderer keeps the text matrices in its graphics state, so a `Q` restores them too. */
        private class Saved(val placement: Placement, val tm: KiteMatrix, val tlm: KiteMatrix, val known: Boolean)

        private val saved = ArrayList<Saved>()

        /** The saves past [MAX_SAVES], which the renderer does not keep, so their `Q`s restore nothing (#49). */
        private var overflow = 0

        fun save() {
            if (saved.size >= MAX_SAVES) {
                overflow++
                return
            }
            saved.add(Saved(placement.copy(), tm, tlm, known))
        }

        /** A `Q` with nothing saved in its own content stream changes nothing, as in the renderer (#48). */
        fun restore() {
            if (overflow > 0) {
                overflow--
                return
            }
            val last = saved.removeLastOrNull() ?: return
            placement = last.placement
            tm = last.tm
            tlm = last.tlm
            known = last.known
        }

        fun transform(m: KiteMatrix) {
            placement.ctm = placement.ctm.concat(m)
        }

        fun beginText() {
            tm = KiteMatrix.IDENTITY
            tlm = KiteMatrix.IDENTITY
            known = true
        }

        fun setMatrix(m: KiteMatrix) {
            tm = m
            tlm = m
            known = true
        }

        /** `Td` and `TD`: the offset is in text space, under the line matrix (ISO 32000-1, 9.4.2). */
        fun move(tx: Double, ty: Double, setLeading: Boolean = false) {
            if (setLeading) placement.leading = -ty
            tlm = tlm.concat(KiteMatrix.translation(tx, ty))
            tm = tlm
            known = true
        }

        fun nextLine() = move(0.0, -placement.leading)

        /** The placement that a form XObject drawn here starts with: this one, with the form matrix on the CTM. */
        fun placementFor(formMatrix: KiteMatrix): Placement = placement.copy().also { it.ctm = placement.ctm.concat(formMatrix) }

        /**
         * Moves the pen over [operand], a string or a `TJ` array, by the displacement of each
         * glyph and each adjustment (ISO 32000-1, 9.4.3 and 9.4.4), and gives where the show
         * starts and ends on the page, its size and its direction, or null when the walk cannot
         * tell. A glyph moves the pen by `(w0 x Tfs + Tc + Tw) x Th` along the line, a `TJ`
         * number by `-n / 1000 x Tfs x Th`, and in a font that writes top to bottom the pen
         * moves down the column by `w1y x Tfs + Tc + Tw`, without `Th` (9.7.4.3).
         */
        fun show(operand: Any, chain: Int): Shown? {
            val p = placement
            val font = p.font
            if (!known || font == null || font.subtype == "Type3") {
                known = false
                return null
            }
            val vertical = font.isVertical
            // Along the line before Th, or down the column, in text space.
            var pen = 0.0
            when (operand) {
                is PdfString -> pen += advance(operand.bytes, font, vertical)
                is PdfArray -> for (item in operand) {
                    when (item) {
                        is PdfString -> pen += advance(item.bytes, font, vertical)
                        is PdfReal, is PdfInt -> pen -= number(item) / 1000.0 * p.size
                        else -> { /* ignore */ }
                    }
                }
            }
            if (!pen.isFinite()) {
                known = false
                return null
            }
            // Text space to the page, with Th and Ts in front of Tm, as the renderer draws a run.
            val m = p.ctm.concat(tm.concat(KiteMatrix(p.scale, 0.0, 0.0, 1.0, 0.0, p.rise)))
            tm = tm.concat(if (vertical) KiteMatrix.translation(0.0, pen) else KiteMatrix.translation(pen * p.scale, 0.0))
            // RedrawnText's size of a glyph: the expansion of its text rendering matrix.
            val size = abs(p.size) * sqrt(abs(m.a * m.d - m.b * m.c))
            val length = sqrt(m.a * m.a + m.b * m.b)
            if (!size.isFinite() || size <= 0.0 || !length.isFinite() || length <= 0.0) return null
            val endX = if (vertical) 0.0 else pen
            val endY = if (vertical) pen else 0.0
            return Shown(m.e, m.f, m.transformX(endX, endY), m.transformY(endX, endY), size, m.a / length, m.b / length, chain)
        }

        /** How far [bytes] move the pen, or NaN for a column whose glyphs have no vertical metrics. */
        private fun advance(bytes: ByteArray, font: PdfFont, vertical: Boolean): Double {
            val p = placement
            var pen = 0.0
            if (!vertical) {
                font.forEachGlyphAdvance(bytes) { width, isWordSpace ->
                    pen += width * p.size / 1000.0 + p.charSpacing + (if (isWordSpace) p.wordSpacing else 0.0)
                }
                return pen
            }
            val metrics = font.verticalMetrics(bytes) ?: return Double.NaN
            var i = 0
            font.forEachGlyphAdvance(bytes) { _, isWordSpace ->
                val v = metrics.getOrNull(i++)
                pen += if (v == null) Double.NaN else v.displacement * p.size / 1000.0 + p.charSpacing + (if (isWordSpace) p.wordSpacing else 0.0)
            }
            return if (i == metrics.size) pen else Double.NaN
        }
    }

    /**
     * An `/ActualText` that the walk wrote at its `BDC` (ISO 32000-1, 14.9.4): its [text], the
     * length of the output and the waiting line breaks before it, the length after it, and the
     * first and last show inside it. [lost] says that the walk could not place one of them.
     */
    private class Replacement(val text: String, val mark: Int, val breaks: Int, val heldBreaks: Int) {
        var end = 0
        var first: Shown? = null
        var last: Shown? = null
        var lost = false
    }

    /**
     * Where one show drew its text: from [x0], [y0] to [x1], [y1] on the page, at [size], along
     * the baseline direction [ux], [uy], with the pen moves counted at [chain]. Once it stays,
     * [next] is the show before it with the same text.
     */
    private class Shown(
        val x0: Double,
        val y0: Double,
        val x1: Double,
        val y1: Double,
        val size: Double,
        val ux: Double,
        val uy: Double,
        val chain: Int,
    ) {
        var next: Shown? = null
    }

    private fun number(v: Any?): Double = when (v) {
        is PdfReal -> v.value
        is PdfInt -> v.value.toDouble()
        else -> 0.0
    }

    /** The matrix `a b c d e f` of six operands, with 0 for each one missing. */
    private fun matrix(values: List<Any?>): KiteMatrix = KiteMatrix(
        number(values.getOrNull(0)), number(values.getOrNull(1)), number(values.getOrNull(2)),
        number(values.getOrNull(3)), number(values.getOrNull(4)), number(values.getOrNull(5)),
    )

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

    /** The depth of the renderer's graphics state stack, MuPDF's limit. */
    private const val MAX_SAVES = 4096

    /**
     * How many earlier shows of one text a show compares with at most, the latest first. A
     * page that repeats one word in many places costs a bounded lookup per show.
     */
    private const val MAX_CANDIDATES = 64

    // Compiled once, not per extract() call.
    private val SPACES_RUN = Regex("[ \\t]+")
    private val BLANK_LINES_RUN = Regex("\\n{3,}")
}
