package io.github.yuroyami.kitepdf.writer

import io.github.yuroyami.kitepdf.core.KiteRectangle
import io.github.yuroyami.kitepdf.content.Operation
import io.github.yuroyami.kitepdf.core.font.PdfFont
import io.github.yuroyami.kitepdf.core.render.KiteMatrix
import io.github.yuroyami.kitepdf.core.parser.PdfArray
import io.github.yuroyami.kitepdf.core.parser.PdfInt
import io.github.yuroyami.kitepdf.core.parser.PdfName
import io.github.yuroyami.kitepdf.core.parser.PdfObject
import io.github.yuroyami.kitepdf.core.parser.PdfReal
import io.github.yuroyami.kitepdf.core.parser.PdfString

/**
 * The content-stream transform behind true region redaction.
 *
 * It replays the renderer's graphics + text state machine (CTM via `q/Q/cm`,
 * the text matrix via `BT/Td/TD/Tm/T*`, font metrics via [PdfFont.layoutBytes])
 * to compute each shown run's box in page user space. Any run (or any string
 * inside a `TJ` array, or any image) whose box intersects a redaction
 * rectangle has its **bytes removed** from the output and replaced by an
 * equivalent text-space advance, so the remaining text keeps its position while
 * the redacted text is genuinely gone (not merely covered).
 *
 * The decision is deliberately conservative: a run is dropped if its box
 * *touches* a rectangle, so partial overlaps over-remove rather than risk
 * leaving redacted content. The math mirrors `PageRenderer` exactly; if it
 * drifts, redaction could mis-judge a position, so the two must stay in sync.
 *
 * Two categories of removed content are reported back to the caller so it can
 * finish the job at the document level (the engine only rewrites the content
 * stream, it can't touch resource dicts or nested objects):
 *
 *  - [droppedImageNames] / [survivingImageNames]: image XObject names whose
 *    draw op was removed vs. still drawn. The caller prunes the dropped ones
 *    from `/Resources /XObject` so the reachability GC drops the image stream.
 *  - [formXObjectHits]: one entry per `Do` invocation of a form XObject, carrying
 *    the redaction rectangles mapped into that form's own coordinate space and
 *    whether the invocation paints into a region at all, so the caller can recurse
 *    in and redact there (content inside a form XObject is otherwise never
 *    reached) without disturbing the invocations that no region touches.
 *
 * Vector paths get the same treatment as text. A signature or a chart drawn as
 * line art IS its coordinates, so a path whose ink reaches a region has its
 * construction operators removed along with its painting operator, rather than
 * being covered by the black box. The hit test is per SEGMENT (each `l`, curve
 * or `re` edge on its own), not one bounding box for the whole path: a box for
 * the whole path would call a page-spanning rectangle a hit for any region on
 * the page, since its own bounding box covers the entire page (see
 * [pathIntersectsRedaction]). Two documented limits remain: a path that also
 * sets a clip keeps its coordinates (see [paintPath] for why), and a line width
 * set through an ExtGState `/LW` rather than the `w` operator is not seen, so a
 * stroke's ink is padded by the last `w` (or the inherited default, see below)
 * and the last `M` (or the inherited default) instead.
 *
 * A form XObject invocation is a save/restore around the form's content (ISO
 * 32000-1, 8.10.2): every graphics state parameter in effect at the `Do`,
 * including line width and miter limit, is in scope inside the form unless the
 * form's own content sets a new value, and nothing the form sets leaks back out.
 * The caller that descends into a form (see [FormHit]) is expected to construct
 * the nested [RedactionEngine] with [initialLineWidth] and [initialMiterLimit]
 * taken from its own current pen, exactly as `PageRenderer` carries the whole
 * graphics state into `renderFormXObjectInner` and changes only the CTM. Left at
 * the constructor defaults, a stroke that inherits its width would be judged
 * with the wrong pen and could survive inside a redacted region.
 */
internal class RedactionEngine(
    private val fonts: Map<String, PdfFont>,
    private val imageXObjectNames: Set<String>,
    private val formXObjectNames: Set<String>,
    private val rectangles: List<KiteRectangle>,
    initialLineWidth: Double = 1.0,
    initialMiterLimit: Double = 10.0,
) {

    /** An image XObject 'Do' invocation was dropped (intersected a region). */
    val droppedImageNames = LinkedHashSet<String>()

    /** An image XObject 'Do' invocation was kept (still drawn on this page). */
    val survivingImageNames = LinkedHashSet<String>()

    /**
     * One `Do` invocation of a form XObject, carrying the regions mapped into that
     * form's own space and the index of the `Do` in the FILTERED output stream.
     * The index is what lets the caller repoint this one invocation at a redacted
     * clone without touching its siblings.
     *
     * [intersects] says whether this invocation actually paints into a region.
     * A false one needs no redaction and no clone, but the caller still needs to
     * know it exists: it is the reason the form's original object has to stay
     * pristine, since that is what this invocation goes on drawing.
     *
     * [lineWidth] and [miterLimit] are the pen in effect at this `Do`, for the
     * caller to seed the nested engine with (8.10.2): the form's own content may
     * never set either, in which case its strokes are the invoking stream's.
     */
    data class FormHit(
        val name: String,
        val formRects: List<KiteRectangle>,
        val opIndex: Int,
        val intersects: Boolean,
        val lineWidth: Double,
        val miterLimit: Double,
    )

    /** Every form XObject `Do` invocation seen, one entry per invocation. */
    val formXObjectHits = ArrayList<FormHit>()

    /**
     * Optional per-form `/Matrix` (default identity). Populated by the caller
     * before [run] so the reported [FormHit.formRects] are in the form's space.
     */
    var formMatrices: Map<String, KiteMatrix> = emptyMap()

    /**
     * Optional per-form `/BBox`, in the form's own space. Populated by the caller
     * before [run] so [FormHit.intersects] can be decided. A form the caller could
     * not read a `/BBox` for counts as intersecting: `/BBox` is required
     * (ISO 32000-1, 8.10.2, Table 95), so its absence means a malformed form, and
     * over-redacting is the safe side of that error.
     */
    var formBBoxes: Map<String, KiteRectangle> = emptyMap()

    private data class TextState(
        val textMatrix: KiteMatrix = KiteMatrix.IDENTITY,
        val lineMatrix: KiteMatrix = KiteMatrix.IDENTITY,
        val font: PdfFont? = null,
        val fontSize: Double = 0.0,
        val charSpacing: Double = 0.0,
        val wordSpacing: Double = 0.0,
        val horizontalScaling: Double = 100.0,
        val leading: Double = 0.0,
        val rise: Double = 0.0,
    )

    /**
     * [lineWidth] and [miterLimit] are graphics state (ISO 32000-1, 8.4.3.2 and
     * 8.4.3.5), so `Q` must put both back.
     */
    private data class GraphicsState(
        val ctm: KiteMatrix = KiteMatrix.IDENTITY,
        val text: TextState = TextState(),
        val lineWidth: Double = 1.0,
        val miterLimit: Double = 10.0,
    )

    private var gs = GraphicsState(lineWidth = initialLineWidth, miterLimit = initialMiterLimit)
    private val stack = ArrayDeque<GraphicsState>()

    /**
     * Construction ops seen since the current path began, held until the painting
     * operator that consumes them says whether they may be written out.
     *
     * Path construction is NOT graphics state (ISO 32000-1, 8.5.1): it lives
     * between a first `m`/`re` and the paint operator, so `q`/`Q` do not touch it.
     */
    private val pathOps = ArrayList<Operation>()

    /**
     * User-space bounds of each SEGMENT built since the current path began: one
     * entry per `l`, per curve, per `h`, and four per `re` (its edges). Kept apart
     * rather than folded into one running box for the whole path, so a small
     * region cannot be swallowed by a large path's aggregate box (see
     * [pathIntersectsRedaction]).
     */
    private val pathSegments = ArrayList<SegmentBounds>()

    /** Where the pen is right now, in user space, as the path is built. */
    private var currentX = 0.0
    private var currentY = 0.0

    /** Where the CURRENT subpath began, for `h` and for `re`'s implicit close. */
    private var subpathStartX = 0.0
    private var subpathStartY = 0.0

    /** True once `W` or `W*` marked the current path as a clip. */
    private var pathClips = false

    /** One segment's user-space bounding box (ISO 32000-1, 8.5.2). */
    private data class SegmentBounds(val minX: Double, val minY: Double, val maxX: Double, val maxY: Double)

    fun run(ops: List<Operation>): List<Operation> {
        val out = ArrayList<Operation>(ops.size)
        for (op in ops) {
            when (op.operator) {
                "q" -> { stack.addLast(gs); out.add(op) }
                "Q" -> { gs = stack.removeLastOrNull() ?: gs; out.add(op) }
                "cm" -> { gs = gs.copy(ctm = gs.ctm.concat(matrix(op))); out.add(op) }
                // A `w` with no operand is malformed, and `num` reads a missing
                // operand as 0. Unguarded, that would silently zero the pen instead
                // of leaving it alone, and a redaction that under-covers ships ink
                // it promised to remove. `PageRenderer` guards this exact operator
                // and keeps the previous width; this mirrors it.
                "w" -> { if (op.operands.isNotEmpty()) gs = gs.copy(lineWidth = num(op, 0)); out.add(op) }
                // `M` has no such guard, matching `PageRenderer`, which reads it the
                // same unguarded way. A malformed or degenerate `M` still cannot
                // under-cover: [pathIntersectsRedaction] floors the miter factor at
                // 1, so the pad never drops below the plain lineWidth/2 body pad.
                "M" -> { gs = gs.copy(miterLimit = num(op, 0)); out.add(op) }

                "BT" -> { gs = gs.copy(text = TextState(font = gs.text.font, fontSize = gs.text.fontSize)); out.add(op) }
                "Tf" -> {
                    gs = gs.copy(text = gs.text.copy(font = fonts[name(op, 0)], fontSize = num(op, 1)))
                    out.add(op)
                }
                "Tc" -> { gs = gs.copy(text = gs.text.copy(charSpacing = num(op, 0))); out.add(op) }
                "Tw" -> { gs = gs.copy(text = gs.text.copy(wordSpacing = num(op, 0))); out.add(op) }
                "Tz" -> { gs = gs.copy(text = gs.text.copy(horizontalScaling = num(op, 0))); out.add(op) }
                "TL" -> { gs = gs.copy(text = gs.text.copy(leading = num(op, 0))); out.add(op) }
                "Ts" -> { gs = gs.copy(text = gs.text.copy(rise = num(op, 0))); out.add(op) }

                "Td" -> { moveText(num(op, 0), num(op, 1), setLeading = false); out.add(op) }
                "TD" -> { moveText(num(op, 0), num(op, 1), setLeading = true); out.add(op) }
                "Tm" -> {
                    val m = matrix(op)
                    gs = gs.copy(text = gs.text.copy(textMatrix = m, lineMatrix = m))
                    out.add(op)
                }
                "T*" -> { moveText(0.0, -gs.text.leading, setLeading = false); out.add(op) }

                "Tj" -> emitShow(bytesOf(op.operands.firstOrNull()), out)
                "'" -> {
                    moveText(0.0, -gs.text.leading, setLeading = false)
                    out.add(Operation("T*", emptyList()))
                    emitShow(bytesOf(op.operands.firstOrNull()), out)
                }
                "\"" -> {
                    val aw = num(op, 0)
                    val ac = num(op, 1)
                    gs = gs.copy(text = gs.text.copy(wordSpacing = aw, charSpacing = ac))
                    out.add(Operation("Tw", listOf(PdfReal(aw))))
                    out.add(Operation("Tc", listOf(PdfReal(ac))))
                    moveText(0.0, -gs.text.leading, setLeading = false)
                    out.add(Operation("T*", emptyList()))
                    emitShow(bytesOf(op.operands.lastOrNull()), out)
                }
                "TJ" -> emitTJ(op.operands.firstOrNull() as? PdfArray, out)

                // Path construction (ISO 32000-1, 8.5.2). Buffered, not emitted:
                // whether these ops may be written out is only known at the paint.
                "m" -> { pathOps.add(op); moveTo(num(op, 0), num(op, 1)) }
                "l" -> { pathOps.add(op); lineTo(num(op, 0), num(op, 1)) }
                "c" -> {
                    pathOps.add(op)
                    curveTo(num(op, 0) to num(op, 1), num(op, 2) to num(op, 3), num(op, 4) to num(op, 5))
                }
                "v", "y" -> { pathOps.add(op); curveTo(num(op, 0) to num(op, 1), num(op, 2) to num(op, 3)) }
                "h" -> { pathOps.add(op); closeSubpath() }
                "re" -> { pathOps.add(op); rectSubpath(num(op, 0), num(op, 1), num(op, 2), num(op, 3)) }

                "W", "W*" -> { pathClips = true; pathOps.add(op) }

                // Path painting (ISO 32000-1, 8.5.3): every one ends the path object.
                "f", "F", "f*", "B", "B*", "b", "b*", "S", "s", "n" -> paintPath(op, out)

                "Do" -> handleDo(op, out)
                "BI" -> if (op.inlineImage == null || !imageBoxIntersects()) out.add(op)

                else -> out.add(op)
            }
        }
        flushUnpaintedPath(out)
        return out
    }

    /* ─── Paths ──────────────────────────────────────────────────────────── */

    /** `m`: start a new subpath. No ink is laid down, so no segment, just a move. */
    private fun moveTo(x: Double, y: Double) {
        currentX = x; currentY = y
        subpathStartX = x; subpathStartY = y
    }

    /** `l`: a straight segment from the current point to `(x, y)` (8.5.2.1). */
    private fun lineTo(x: Double, y: Double) {
        addSegment(listOf(currentX to currentY, x to y))
        currentX = x; currentY = y
    }

    /**
     * `c`/`v`/`y`: a cubic Bezier from the current point through [rest]'s control
     * points. `c` supplies all three explicit points; `v` and `y` each supply the
     * two the spelling doesn't leave implicit (8.5.2.1), so both call this the
     * same way.
     *
     * The curve lies within the convex hull of ALL its control points, including
     * the current point (8.5.2.2), so the segment's box is taken from that whole
     * set, not stitched from point-to-point pieces: a curve can bulge toward the
     * middle of its control polygon, away from every individual edge of it, and
     * only the full set's box is guaranteed to contain that bulge.
     */
    private fun curveTo(vararg rest: Pair<Double, Double>) {
        val points = ArrayList<Pair<Double, Double>>(rest.size + 1)
        points.add(currentX to currentY)
        points.addAll(rest)
        addSegment(points)
        val last = rest.last()
        currentX = last.first; currentY = last.second
    }

    /** `h`: a straight segment back to where the current subpath began (8.5.2.1). */
    private fun closeSubpath() {
        addSegment(listOf(currentX to currentY, subpathStartX to subpathStartY))
        currentX = subpathStartX; currentY = subpathStartY
    }

    /**
     * `re`: a closed rectangular subpath, as its four edges (8.5.2.1 defines it as
     * equivalent to `x y m (x+w) y l (x+w)(y+h) l x (y+h) l h`). Each edge is its
     * own segment: a page-spanning rectangle's edges sit at the page border, so a
     * region over the middle of the page touches none of them, even though the
     * rectangle's own bounding box covers that region entirely.
     */
    private fun rectSubpath(x: Double, y: Double, w: Double, h: Double) {
        val x2 = x + w
        val y2 = y + h
        addSegment(listOf(x to y, x2 to y))
        addSegment(listOf(x2 to y, x2 to y2))
        addSegment(listOf(x2 to y2, x to y2))
        addSegment(listOf(x to y2, x to y))
        currentX = x; currentY = y
        subpathStartX = x; subpathStartY = y
    }

    /** Record one segment's user-space bounding box, tested on its own by [pathIntersectsRedaction]. */
    private fun addSegment(points: List<Pair<Double, Double>>) {
        var minX = points[0].first; var maxX = minX
        var minY = points[0].second; var maxY = minY
        for ((x, y) in points) {
            if (x < minX) minX = x; if (x > maxX) maxX = x
            if (y < minY) minY = y; if (y > maxY) maxY = y
        }
        pathSegments.add(SegmentBounds(minX, minY, maxX, maxY))
    }

    /**
     * Decide the fate of the path this painting operator consumes.
     *
     * A path whose ink reaches a region is removed outright: its coordinates ARE
     * the content, so painting over it would leave the shape recoverable. The whole
     * path goes, every subpath of it, because one painting operator paints them all
     * and over-removing is the safe side of that call.
     *
     * When the path also sets a clip (`W`/`W*`), the construction is kept and the
     * paint becomes `n` (or `h n`, see below), because everything up to the
     * matching `Q` is clipped by this path (ISO 32000-1, 8.5.4) and dropping it
     * would let all of that escape and paint over the rest of the page. The clip's
     * own coordinates survive, which is the documented price of keeping the clip.
     *
     * `s`, `b` and `b*` close the current subpath before painting (8.5.3), so a
     * bare `n` would lose that close; [CLOSING_PAINT_OPERATORS] gets an explicit
     * `h` first instead. `PageRenderer` closes before computing a pending clip for
     * exactly those three operators and no others, so this mirrors it.
     */
    private fun paintPath(op: Operation, out: MutableList<Operation>) {
        val hit = pathSegments.isNotEmpty() && pathIntersectsRedaction(strokes = op.operator in STROKING_PAINT_OPERATORS)
        when {
            !hit -> { out.addAll(pathOps); out.add(op) }
            pathClips -> {
                out.addAll(pathOps)
                if (op.operator in CLOSING_PAINT_OPERATORS) out.add(Operation("h", emptyList()))
                out.add(Operation("n", emptyList()))
            }
            else -> Unit // construction and paint go together
        }
        resetPath()
    }

    /**
     * A stream that ends mid-path (malformed, but real) leaves construction ops
     * buffered. They painted nothing, so emitting them changes no pixels, but their
     * coordinates are content all the same: one in a region is dropped for exactly
     * the reason a painted one is, and one outside every region is written out so
     * the rewrite stays faithful to everything not deliberately removed.
     *
     * A pending `W` is not honoured: a clip only takes effect once a painting
     * operator ends the path object (ISO 32000-1, 8.5.4), and here there is no
     * painting operator and nothing after it left to clip.
     */
    private fun flushUnpaintedPath(out: MutableList<Operation>) {
        val hit = pathSegments.isNotEmpty() && pathIntersectsRedaction(strokes = false)
        if (!hit) out.addAll(pathOps)
        resetPath()
    }

    private fun resetPath() {
        pathOps.clear()
        pathSegments.clear()
        pathClips = false
        currentX = 0.0; currentY = 0.0
        subpathStartX = 0.0; subpathStartY = 0.0
    }

    /**
     * Does the current path's ink touch a region? Tested per SEGMENT (see
     * [pathSegments]), not by one box for the whole path: a full-page background
     * rectangle's four edges each stay far from a region even though the
     * rectangle's OWN bounding box covers it, so testing that one aggregate box
     * would erase page-spanning art for a redaction anywhere on the page. A
     * signature or any shape whose actual ink enters a region still has a segment
     * that does, so it is still removed there; a donut whose hole boundary lies in
     * a region is removed through that subpath's own segments.
     *
     * The pen is padded in USER space, before the CTM maps a segment out, because
     * that is where a line width is measured (ISO 32000-1, 8.4.3.2). Padding
     * afterwards by a single scale factor would under-cover a stroke under an
     * anisotropic CTM: `1 0 0 10 0 0 cm` lays ten times as much ink up the page as
     * across it, and a redaction that under-covers ships ink it promised to remove.
     *
     * Padding by half the line width alone still under-covers: a miter join can
     * extend `miterLimit * lineWidth / 2` from a vertex (8.4.3.5; the default limit
     * is 10, so up to 5x the plain pad). Rather than padding only vertices, every
     * segment is padded by the larger amount: simpler, and the only place it
     * over-covers is a subpath's two open ends, the safe direction. `maxOf(1.0, …)`
     * floors the factor at the plain body pad, so a malformed or explicitly small
     * `M` cannot shrink the pad below what an unstroked segment already gets.
     */
    private fun pathIntersectsRedaction(strokes: Boolean): Boolean {
        if (rectangles.isEmpty() || pathSegments.isEmpty()) return false
        val pad = if (strokes) gs.lineWidth * maxOf(1.0, gs.miterLimit) / 2.0 else 0.0
        return pathSegments.any { seg ->
            boxIntersects(gs.ctm, seg.minX - pad, seg.minY - pad, seg.maxX + pad, seg.maxY + pad)
        }
    }

    /* ─── Text showing ───────────────────────────────────────────────────── */

    /** Emit a `Tj`: keep it, or (if redacted) replace with an equivalent advance. */
    private fun emitShow(bytes: ByteArray?, out: MutableList<Operation>) {
        if (bytes == null) return
        val font = gs.text.font
        if (font == null) {
            // No font → the renderer wouldn't show or advance; pass through.
            out.add(Operation("Tj", listOf(PdfString(bytes))))
            return
        }
        val advance = advanceOf(bytes, font)
        if (runIntersectsRedaction(advance)) {
            compensation(advance)?.let { out.add(Operation("TJ", listOf(PdfArray(listOf(PdfReal(it)))))) }
        } else {
            out.add(Operation("Tj", listOf(PdfString(bytes))))
        }
        advanceTextMatrix(advance)
    }

    /** Rebuild a `TJ` array, replacing redacted strings with equivalent spacing. */
    private fun emitTJ(array: PdfArray?, out: MutableList<Operation>) {
        if (array == null) return
        val font = gs.text.font
        val items = ArrayList<PdfObject>(array.items.size)
        for (item in array.items) {
            when (item) {
                is PdfString -> {
                    if (font == null) {
                        items.add(item)
                    } else {
                        val advance = advanceOf(item.bytes, font)
                        if (runIntersectsRedaction(advance)) {
                            compensation(advance)?.let { items.add(PdfReal(it)) }
                        } else {
                            items.add(item)
                        }
                        advanceTextMatrix(advance)
                    }
                }
                is PdfReal -> { items.add(item); adjustTextX(-item.value) }
                is PdfInt -> { items.add(item); adjustTextX(-item.value.toDouble()) }
                else -> items.add(item)
            }
        }
        out.add(Operation("TJ", listOf(PdfArray(items))))
    }

    /** Total text-space advance of [bytes], matching PageRenderer.totalAdvance. */
    private fun advanceOf(bytes: ByteArray, font: PdfFont): Double {
        val t = gs.text
        val sizeFactor = t.fontSize / 1000.0
        val hScale = t.horizontalScaling / 100.0
        var advance = 0.0
        font.forEachGlyphAdvance(bytes) { width, isWordSpace ->
            advance += (width * sizeFactor + t.charSpacing + (if (isWordSpace) t.wordSpacing else 0.0)) * hScale
        }
        return advance
    }

    /**
     * The TJ number that advances the cursor by [advance] text-space units
     * (the renderer applies `adjustTextX(-number)`), or null when font size /
     * horizontal scale make it ill-defined (then the advance is ~0 anyway).
     */
    private fun compensation(advance: Double): Double? {
        val denom = gs.text.fontSize * (gs.text.horizontalScaling / 100.0)
        if (kotlin.math.abs(denom) < 1e-9) return null
        return -advance * 1000.0 / denom
    }

    private fun advanceTextMatrix(advance: Double) {
        gs = gs.copy(text = gs.text.copy(textMatrix = KiteMatrix.translation(advance, 0.0).concat(gs.text.textMatrix)))
    }

    private fun adjustTextX(thousandths: Double) {
        val tx = thousandths / 1000.0 * gs.text.fontSize * (gs.text.horizontalScaling / 100.0)
        gs = gs.copy(text = gs.text.copy(textMatrix = KiteMatrix.translation(tx, 0.0).concat(gs.text.textMatrix)))
    }

    private fun moveText(tx: Double, ty: Double, setLeading: Boolean) {
        val moved = KiteMatrix.translation(tx, ty).concat(gs.text.lineMatrix)
        gs = gs.copy(text = gs.text.copy(lineMatrix = moved, textMatrix = moved, leading = if (setLeading) -ty else gs.text.leading))
    }

    /* ─── Geometry ───────────────────────────────────────────────────────── */

    /**
     * Does the current run (text-space x in [0,advance]) touch any redaction rect?
     *
     * The vertical extent is deliberately generous: tall accents (e.g. Å, Ĝ) rise
     * above a typical cap height and descenders (g, y, ç) drop below the baseline,
     * so a tight box could miss glyphs that visually overlap a region and leave
     * them un-redacted. We over-cover with ascent ~1.0em / descent ~0.35em. For a
     * redaction tool, removing slightly too much is correct; missing content is not.
     */
    private fun runIntersectsRedaction(advance: Double): Boolean {
        if (rectangles.isEmpty()) return false
        val fs = gs.text.fontSize
        val ascent = fs * 1.0
        val descent = fs * 0.35
        val m = gs.ctm.concat(gs.text.textMatrix).let { KiteMatrix.translation(0.0, gs.text.rise).concat(it) }
        return boxIntersects(m, x0 = 0.0, y0 = -descent, x1 = advance, y1 = ascent)
    }

    /**
     * Handle a `Do` XObject invocation. Images intersecting a region are dropped
     * (and their name recorded so the caller can prune the resource entry); every
     * form XObject invocation is recorded as a [FormHit] (kept in the stream: the
     * caller recurses in to redact its content, mapping the region into the form's
     * space) so nested content in a region is never silently retained.
     */
    private fun handleDo(op: Operation, out: MutableList<Operation>) {
        val xobjectName = name(op, 0)
        if (xobjectName != null && xobjectName in imageXObjectNames) {
            if (imageBoxIntersects()) {
                droppedImageNames.add(xobjectName)
                return // drop the draw op
            }
            survivingImageNames.add(xobjectName)
            out.add(op)
            return
        }
        if (xobjectName != null && xobjectName in formXObjectNames) {
            recordFormHit(xobjectName, out.size)
        }
        out.add(op)
    }

    /**
     * Record a [FormHit] for this form invocation, carrying the redaction
     * rectangles mapped into the form's own coordinate space. The mapping is
     * `formSpace = (ctm ∘ formMatrix)⁻¹` applied to each page-space rect.
     *
     * Every invocation is recorded, whether or not it paints into a region;
     * [FormHit.intersects] carries which. The caller needs both kinds: an
     * invocation that misses every region needs no redaction, but it does need the
     * form's original object left alone, and only the caller knows whether some
     * other invocation of the same form was about to rewrite it.
     *
     * The test is the mapped rects against the form's `/BBox`, the box outside
     * which a form paints nothing (ISO 32000-1, 8.10.2). Two conservative fallbacks,
     * because a redaction must never no-op on content it cannot reason about: a
     * singular matrix passes the page-space rects through unchanged and counts as
     * intersecting, and so does a form whose `/BBox` the caller could not read.
     */
    private fun recordFormHit(xobjectName: String, opIndex: Int) {
        val formMatrix = formMatrices[xobjectName] ?: KiteMatrix.IDENTITY
        // model→page transform seen by content drawn inside the form.
        val toPage = gs.ctm.concat(formMatrix)
        val inv = toPage.invert()
        val mapped = ArrayList<KiteRectangle>(rectangles.size)
        for (r in rectangles) {
            if (inv == null) {
                // Can't map into form space. Pass the page-space rect through so
                // the recursion still attempts redaction (conservative).
                mapped.add(r)
                continue
            }
            val corners = listOf(
                inv.transformPoint(r.left, r.bottom), inv.transformPoint(r.right, r.bottom),
                inv.transformPoint(r.left, r.top), inv.transformPoint(r.right, r.top),
            )
            val minX = corners.minOf { it.first }
            val maxX = corners.maxOf { it.first }
            val minY = corners.minOf { it.second }
            val maxY = corners.maxOf { it.second }
            mapped.add(KiteRectangle(left = minX, bottom = minY, right = maxX, top = maxY))
        }
        val bbox = formBBoxes[xobjectName]
        val intersects = inv == null || bbox == null || mapped.any { overlaps(it, bbox) }
        formXObjectHits.add(FormHit(xobjectName, mapped, opIndex, intersects, gs.lineWidth, gs.miterLimit))
    }

    /** Do two rectangles share area? Touching edges do not count, as in [boxIntersects]. */
    private fun overlaps(a: KiteRectangle, b: KiteRectangle): Boolean =
        a.left < b.right && a.right > b.left && a.bottom < b.top && a.top > b.bottom

    /** Image XObjects (and inline images) are painted into the unit square under the CTM. */
    private fun imageBoxIntersects(): Boolean {
        if (rectangles.isEmpty()) return false
        return boxIntersects(gs.ctm, 0.0, 0.0, 1.0, 1.0)
    }

    /** Map the box [x0,y0,x1,y1] through [m] and test its AABB against every rect. */
    private fun boxIntersects(m: KiteMatrix, x0: Double, y0: Double, x1: Double, y1: Double): Boolean {
        val corners = listOf(
            m.transformPoint(x0, y0), m.transformPoint(x1, y0),
            m.transformPoint(x0, y1), m.transformPoint(x1, y1),
        )
        val minX = corners.minOf { it.first }
        val maxX = corners.maxOf { it.first }
        val minY = corners.minOf { it.second }
        val maxY = corners.maxOf { it.second }
        for (r in rectangles) {
            if (minX < r.right && maxX > r.left && minY < r.top && maxY > r.bottom) return true
        }
        return false
    }

    /* ─── Operand helpers ────────────────────────────────────────────────── */

    private fun matrix(op: Operation) =
        KiteMatrix(num(op, 0), num(op, 1), num(op, 2), num(op, 3), num(op, 4), num(op, 5))

    private fun num(op: Operation, i: Int): Double = when (val v = op.operands.getOrNull(i)) {
        is PdfInt -> v.value.toDouble()
        is PdfReal -> v.value
        else -> 0.0
    }

    private fun name(op: Operation, i: Int): String? = (op.operands.getOrNull(i) as? PdfName)?.value

    private fun bytesOf(o: PdfObject?): ByteArray? = (o as? PdfString)?.bytes
}

/** Painting operators that lay ink down with the pen, so their bounds need padding. */
private val STROKING_PAINT_OPERATORS = setOf("S", "s", "B", "B*", "b", "b*")

/** Painting operators that close the current subpath before painting (ISO 32000-1, 8.5.3). */
private val CLOSING_PAINT_OPERATORS = setOf("s", "b", "b*")
