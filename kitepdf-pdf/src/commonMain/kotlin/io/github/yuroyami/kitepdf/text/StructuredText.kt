package io.github.yuroyami.kitepdf.text

import io.github.yuroyami.kitepdf.PdfDocument
import io.github.yuroyami.kitepdf.PdfPage
import io.github.yuroyami.kitepdf.Rectangle
import io.github.yuroyami.kitepdf.font.FontSpec
import io.github.yuroyami.kitepdf.font.TextGlyph
import io.github.yuroyami.kitepdf.render.BlendMode
import io.github.yuroyami.kitepdf.render.ImageXObject
import io.github.yuroyami.kitepdf.render.Matrix
import io.github.yuroyami.kitepdf.render.PageRenderer
import io.github.yuroyami.kitepdf.render.PdfCanvas
import io.github.yuroyami.kitepdf.render.PdfPath
import io.github.yuroyami.kitepdf.render.RgbColor

/**
 * Structured text — `pageGlyphs → spans → lines → blocks` (ISO 32000-1
 * §14.8 is the spec basis; MuPDF's `fz_stext_page` is the architectural
 * reference).
 *
 * The renderer text-state-machine already knows how to turn `Tj`/`TJ` plus
 * the text-matrix stack into positioned glyph runs. We hijack it: a
 * recording canvas captures every `drawText` call, then a layout pass
 * clusters those positioned spans into reading order.
 *
 * Use this when:
 *  - you want copy/paste-quality text (preserves spacing, line breaks)
 *  - you need to highlight/search a region of the page
 *  - you need geometric data alongside the text (positions, fonts)
 *
 * For a raw concatenated string, [PdfPage.extractText] is still simpler
 * and cheaper.
 */
public data class PdfStructuredText(
    val pageWidth: Double,
    val pageHeight: Double,
    val blocks: List<PdfTextBlock>,
) {
    /** Flattened plain text — paragraph breaks become `\n\n`, line breaks `\n`. */
    val plainText: String by lazy {
        blocks.joinToString(separator = "\n\n") { block ->
            block.lines.joinToString(separator = "\n") { it.text }
        }
    }

    /** Every text run in reading order. */
    val spans: List<PdfTextSpan> get() = blocks.flatMap { it.lines.flatMap { l -> l.spans } }
}

/**
 * One paragraph-ish chunk: a vertical run of lines with no big gap.
 * Block boundaries fall where vertical spacing exceeds [GAP_TO_NEW_BLOCK]
 * × the median line height — heuristic, not authoritative, but matches
 * what readers consider "paragraph breaks" in the absence of structure
 * tagging.
 */
public data class PdfTextBlock(
    val bounds: Rectangle,
    val lines: List<PdfTextLine>,
)

/**
 * One line of text: spans whose Y origins cluster within [Y_CLUSTER_TOL]
 * × font size. Spans are stored left-to-right.
 */
public data class PdfTextLine(
    val bounds: Rectangle,
    val spans: List<PdfTextSpan>,
) {
    val text: String by lazy {
        val sb = StringBuilder()
        var prevSpan: PdfTextSpan? = null
        for (s in spans) {
            // Insert a synthesised space if there's a visible gap between spans
            // that the source didn't explicitly mark.
            val prev = prevSpan
            if (prev != null) {
                val gap = s.bounds.left - prev.bounds.right
                val emWidth = prev.fontSize * 0.25
                if (gap > emWidth && sb.isNotEmpty() && sb.last() != ' ') sb.append(' ')
            }
            sb.append(s.text)
            prevSpan = s
        }
        sb.toString().trimEnd()
    }
}

/**
 * One renderer-`drawText` worth of glyphs that share font, size, and
 * baseline. Position is the device-space origin (where the baseline
 * starts).
 */
public data class PdfTextSpan(
    val text: String,
    val fontSpec: FontSpec,
    val fontSize: Double,
    /** Baseline-space origin in PDF user units. */
    val origin: Pair<Double, Double>,
    /** Approximate bounding box (baseline + ascender heuristic). */
    val bounds: Rectangle,
    /**
     * `text.length + 1` user-space baseline points: entry `i` is where char
     * `i` starts, the last entry where the run ends. A multi-char glyph
     * (ligature) splits its advance evenly across its chars. Null when the
     * producer didn't record geometry (the [KiteTextAdapter] falls back to
     * an even split of [bounds]).
     */
    val charEdgePoints: List<Pair<Double, Double>>? = null,
)

/**
 * Tunables for the structured-text clustering pass. PDF text has no
 * inherent line/paragraph notion — these heuristics work well for the
 * common case (running text in horizontal writing mode) and degrade
 * gracefully for headings, tabular data, and rotated runs.
 */
internal object StructuredTextTuning {
    /** Y-position tolerance (× font size) under which spans are considered the same line. */
    const val Y_CLUSTER_TOL = 0.5

    /**
     * Vertical gap (× median line height) above which we open a new block.
     * 1.0 means "more than one full line of empty space" — empirically a
     * solid paragraph-break indicator for running text.
     */
    const val GAP_TO_NEW_BLOCK = 1.0
}

/**
 * One-shot extractor: runs the PageRenderer text-state-machine against a
 * recording canvas and clusters the captured runs.
 */
internal object StructuredTextExtractor {

    fun extract(page: PdfPage): PdfStructuredText {
        val collector = TextCollectorCanvas()
        PageRenderer(collector, accessDocument(page)).render(page, Matrix.IDENTITY)

        val spans = collector.runs.mapNotNull { it.toSpan() }
        if (spans.isEmpty()) {
            return PdfStructuredText(page.width, page.height, emptyList())
        }

        val lines = clusterLines(spans)
        val blocks = clusterBlocks(lines)
        return PdfStructuredText(page.width, page.height, blocks)
    }

    private fun accessDocument(page: PdfPage): PdfDocument {
        // Tiny visibility shim — PdfPage's `document` field is private. Use
        // the public reflection-free path: the page's resources resolver
        // already routes through the document.
        // We just need the document for the PageRenderer constructor.
        return DocumentAccessor.documentOf(page)
    }

    private fun clusterLines(spans: List<PdfTextSpan>): List<PdfTextLine> {
        // Group by Y; PDF Y increases upwards, so we sort descending so the
        // first line in output order is the topmost on the page.
        val sorted = spans.sortedByDescending { it.origin.second }
        val out = mutableListOf<PdfTextLine>()
        val current = mutableListOf<PdfTextSpan>()
        var currentY = Double.NaN
        var currentFontSize = 0.0

        for (s in sorted) {
            val tol = currentFontSize * StructuredTextTuning.Y_CLUSTER_TOL
            if (current.isEmpty() || kotlin.math.abs(s.origin.second - currentY) <= tol) {
                if (current.isEmpty()) {
                    currentY = s.origin.second
                    currentFontSize = s.fontSize
                }
                current.add(s)
            } else {
                out += finishLine(current)
                current.clear()
                current.add(s)
                currentY = s.origin.second
                currentFontSize = s.fontSize
            }
        }
        if (current.isNotEmpty()) out += finishLine(current)
        return out
    }

    private fun finishLine(spans: MutableList<PdfTextSpan>): PdfTextLine {
        spans.sortBy { it.bounds.left }
        val left = spans.minOf { it.bounds.left }
        val right = spans.maxOf { it.bounds.right }
        val bottom = spans.minOf { it.bounds.bottom }
        val top = spans.maxOf { it.bounds.top }
        return PdfTextLine(Rectangle(left, bottom, right, top), spans.toList())
    }

    private fun clusterBlocks(lines: List<PdfTextLine>): List<PdfTextBlock> {
        if (lines.isEmpty()) return emptyList()
        // Threshold derives from the line height (≈ font size), not the gap
        // distribution — the gap-based approach was self-referential and
        // broke down for 2-line documents (single gap == its own median).
        // "More than one full line of empty space" is the rule of thumb.
        val medianHeight = lines.map { it.bounds.height }.sorted()
            .let { if (it.isEmpty()) 0.0 else it[it.size / 2] }
        val threshold = medianHeight * StructuredTextTuning.GAP_TO_NEW_BLOCK

        val blocks = mutableListOf<PdfTextBlock>()
        val current = mutableListOf<PdfTextLine>()
        for (line in lines) {
            if (current.isEmpty()) {
                current.add(line)
                continue
            }
            val prev = current.last()
            val gap = prev.bounds.bottom - line.bounds.top
            if (gap > threshold + 0.001) {
                blocks += finishBlock(current)
                current.clear()
            }
            current.add(line)
        }
        if (current.isNotEmpty()) blocks += finishBlock(current)
        return blocks
    }

    private fun finishBlock(lines: MutableList<PdfTextLine>): PdfTextBlock {
        val left = lines.minOf { it.bounds.left }
        val right = lines.maxOf { it.bounds.right }
        val bottom = lines.minOf { it.bounds.bottom }
        val top = lines.maxOf { it.bounds.top }
        return PdfTextBlock(Rectangle(left, bottom, right, top), lines.toList())
    }
}

/**
 * Canvas that records every `drawText` call. Path and image ops are
 * dropped — they don't contribute to text extraction.
 */
private class TextCollectorCanvas : PdfCanvas {
    data class TextRun(
        val glyphs: List<TextGlyph>,
        val fontSpec: FontSpec,
        val fontSize: Double,
        val textMatrix: Matrix,
        /** Character spacing Tc, in unscaled text-space units (default 0). */
        val charSpacing: Double = 0.0,
        /** Word spacing Tw, in unscaled text-space units (default 0). */
        val wordSpacing: Double = 0.0,
        /** Horizontal scaling Tz as a fraction (100% ⇒ 1.0, default 1.0). */
        val horizScale: Double = 1.0,
    ) {
        fun toSpan(): PdfTextSpan? {
            if (glyphs.isEmpty()) return null

            // Advance per ISO 32000-1 §9.4.4: for each glyph the displacement is
            //   ((w0 - Tj/1000) * Tfs + Tc + Tw) * Th
            // where Th is the horizontal scale (Tz/100). Here there is no per-glyph
            // Tj (that lives in TJ arrays the renderer already applied), so we sum
            //   (w0 * Tfs/1000 + Tc + Tw?) * Th.
            // Tw applies only to single-byte code 32 in simple fonts. A simple
            // font decodes one byte per glyph, so bytes.size == glyphs.size marks
            // the single-byte case; composite fonts never receive Tw.
            val singleByte = glyphs.all { it.byteCount == 1 }
            var advance = 0.0
            // Local (text-space) x boundary per char, for selection/search
            // quads. A multi-char glyph splits its advance evenly; a glyph
            // with no text (no Unicode) widens the previous boundary.
            val localEdges = ArrayList<Double>(glyphs.size + 1)
            localEdges.add(0.0)
            for (g in glyphs) {
                var glyphAdvance = g.advanceWidth * fontSize / 1000.0 + charSpacing
                if (singleByte && wordSpacing != 0.0 && g.isWordSpace) {
                    glyphAdvance += wordSpacing
                }
                val n = g.text.length
                if (n == 0) {
                    advance += glyphAdvance
                    localEdges[localEdges.size - 1] = advance
                } else {
                    for (k in 1..n) localEdges.add(advance + glyphAdvance * k / n)
                    advance += glyphAdvance
                }
            }
            advance *= horizScale

            // Origin and bounds come from the *full* text-rendering matrix, so
            // rotation/skew (b, c) and non-uniform scale are honoured — not just
            // e/f and scaleX. Build the local box in text space then map its
            // corners through the matrix and take the axis-aligned hull.
            val originX = textMatrix.e
            val originY = textMatrix.f
            val ascender = fontSize * 0.8
            val descender = fontSize * 0.2

            // Local (text-space) corners of the glyph run box: x in [0, advance],
            // y in [-descender, ascender]. Map each through the matrix.
            val corners = listOf(
                textMatrix.transformPoint(0.0, -descender),
                textMatrix.transformPoint(advance, -descender),
                textMatrix.transformPoint(advance, ascender),
                textMatrix.transformPoint(0.0, ascender),
            )
            val left = corners.minOf { it.first }
            val right = corners.maxOf { it.first }
            val bottom = corners.minOf { it.second }
            val top = corners.maxOf { it.second }

            val text = glyphs.joinToString("") { it.text }
            val edgePoints = localEdges.map { textMatrix.transformPoint(it * horizScale, 0.0) }
            return PdfTextSpan(
                text = text,
                fontSpec = fontSpec,
                fontSize = fontSize,
                origin = originX to originY,
                bounds = Rectangle(
                    left = left,
                    bottom = bottom,
                    right = right,
                    top = top,
                ),
                charEdgePoints = if (edgePoints.size == text.length + 1) edgePoints else null,
            )
        }
    }

    val runs = mutableListOf<TextRun>()

    override fun beginPage(widthPt: Double, heightPt: Double, deviceCtm: Matrix) {}
    override fun endPage() {}
    override fun fillPath(path: PdfPath, ctm: Matrix, color: RgbColor, evenOdd: Boolean, alpha: Double, blendMode: BlendMode) {}
    override fun strokePath(path: PdfPath, ctm: Matrix, color: RgbColor, lineWidth: Double, alpha: Double, blendMode: BlendMode, dashArray: List<Double>?, dashPhase: Double, lineCap: Int, lineJoin: Int, miterLimit: Double) {}
    override val resolvesGlyphOutlines: Boolean get() = false
    override fun drawGlyphs(
        glyphs: List<TextGlyph>, fontSize: Double, unitsPerEm: Int, hasOutlines: Boolean,
        fontSpec: FontSpec, textToDevice: Matrix, color: RgbColor, alpha: Double, blendMode: BlendMode,
    ) {
        runs.add(TextRun(glyphs, fontSpec, fontSize, textToDevice))
    }
    override fun pushClip(path: PdfPath, ctm: Matrix, evenOdd: Boolean) {}
    override fun popClip() {}
    override fun drawImage(image: ImageXObject, ctm: Matrix, alpha: Double) {}
}

/**
 * Tiny accessor that gets the [PdfDocument] from a [PdfPage] without
 * widening the page's API surface. The renderer needs the document to
 * resolve indirect resource refs.
 */
internal object DocumentAccessor {
    fun documentOf(page: PdfPage): PdfDocument = page.internalDocument
}
