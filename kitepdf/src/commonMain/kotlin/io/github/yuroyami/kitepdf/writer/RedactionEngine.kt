package io.github.yuroyami.kitepdf.writer

import io.github.yuroyami.kitepdf.Rectangle
import io.github.yuroyami.kitepdf.content.Operation
import io.github.yuroyami.kitepdf.font.PdfFont
import io.github.yuroyami.kitepdf.render.Matrix
import io.github.yuroyami.kitepdf.parser.PdfArray
import io.github.yuroyami.kitepdf.parser.PdfInt
import io.github.yuroyami.kitepdf.parser.PdfName
import io.github.yuroyami.kitepdf.parser.PdfObject
import io.github.yuroyami.kitepdf.parser.PdfReal
import io.github.yuroyami.kitepdf.parser.PdfString

/**
 * The content-stream transform behind true region redaction.
 *
 * It replays the renderer's graphics + text state machine (CTM via `q/Q/cm`,
 * the text matrix via `BT/Td/TD/Tm/T*`, font metrics via [PdfFont.layoutBytes])
 * to compute each shown run's box in page user space. Any run — or any string
 * inside a `TJ` array, or any image — whose box intersects a redaction
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
 *    draw op was removed vs. still drawn — the caller prunes the pruned ones
 *    from `/Resources /XObject` so the reachability GC drops the image stream.
 *  - [formXObjectHits]: form XObjects invoked via `Do` whose CTM-mapped area
 *    overlaps a region, together with the redaction rectangles mapped into the
 *    form's own coordinate space, so the caller can recurse into the form and
 *    redact there (content inside a form XObject is otherwise never reached).
 *
 * Not handled yet (documented limitation): vector paths inside the region are
 * left as-is.
 */
internal class RedactionEngine(
    private val fonts: Map<String, PdfFont>,
    private val imageXObjectNames: Set<String>,
    private val formXObjectNames: Set<String>,
    private val rectangles: List<Rectangle>,
) {

    /** An image XObject 'Do' invocation was dropped (intersected a region). */
    val droppedImageNames = LinkedHashSet<String>()

    /** An image XObject 'Do' invocation was kept (still drawn on this page). */
    val survivingImageNames = LinkedHashSet<String>()

    /**
     * A form XObject invoked via 'Do' whose CTM-mapped area overlaps a region.
     * [formRects] carries the page-space redaction rectangles mapped into the
     * form's coordinate space (accounting for the CTM at the invocation site and
     * the form's own `/Matrix`, which the caller supplies via [formMatrices]).
     */
    data class FormHit(val name: String, val formRects: List<Rectangle>)

    /** Form XObjects whose invocation overlaps a region and must be recursed into. */
    val formXObjectHits = ArrayList<FormHit>()

    /**
     * Optional per-form `/Matrix` (default identity). Populated by the caller
     * before [run] so the reported [FormHit.formRects] are in the form's space.
     */
    var formMatrices: Map<String, Matrix> = emptyMap()

    private data class TextState(
        val textMatrix: Matrix = Matrix.IDENTITY,
        val lineMatrix: Matrix = Matrix.IDENTITY,
        val font: PdfFont? = null,
        val fontSize: Double = 0.0,
        val charSpacing: Double = 0.0,
        val wordSpacing: Double = 0.0,
        val horizontalScaling: Double = 100.0,
        val leading: Double = 0.0,
        val rise: Double = 0.0,
    )

    private data class GraphicsState(val ctm: Matrix = Matrix.IDENTITY, val text: TextState = TextState())

    private var gs = GraphicsState()
    private val stack = ArrayDeque<GraphicsState>()

    fun run(ops: List<Operation>): List<Operation> {
        val out = ArrayList<Operation>(ops.size)
        for (op in ops) {
            when (op.operator) {
                "q" -> { stack.addLast(gs); out.add(op) }
                "Q" -> { gs = stack.removeLastOrNull() ?: gs; out.add(op) }
                "cm" -> { gs = gs.copy(ctm = gs.ctm.concat(matrix(op))); out.add(op) }

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

                "Do" -> handleDo(op, out)
                "BI" -> if (op.inlineImage == null || !imageBoxIntersects()) out.add(op)

                else -> out.add(op)
            }
        }
        return out
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
        gs = gs.copy(text = gs.text.copy(textMatrix = Matrix.translation(advance, 0.0).concat(gs.text.textMatrix)))
    }

    private fun adjustTextX(thousandths: Double) {
        val tx = thousandths / 1000.0 * gs.text.fontSize * (gs.text.horizontalScaling / 100.0)
        gs = gs.copy(text = gs.text.copy(textMatrix = Matrix.translation(tx, 0.0).concat(gs.text.textMatrix)))
    }

    private fun moveText(tx: Double, ty: Double, setLeading: Boolean) {
        val moved = Matrix.translation(tx, ty).concat(gs.text.lineMatrix)
        gs = gs.copy(text = gs.text.copy(lineMatrix = moved, textMatrix = moved, leading = if (setLeading) -ty else gs.text.leading))
    }

    /* ─── Geometry ───────────────────────────────────────────────────────── */

    /**
     * Does the current run (text-space x in [0,advance]) touch any redaction rect?
     *
     * The vertical extent is deliberately generous: tall accents (e.g. Å, Ĝ) rise
     * above a typical cap height and descenders (g, y, ç) drop below the baseline,
     * so a tight box could miss glyphs that visually overlap a region and leave
     * them un-redacted. We over-cover with ascent ~1.0em / descent ~0.35em — for a
     * redaction tool, removing slightly too much is correct; missing content is not.
     */
    private fun runIntersectsRedaction(advance: Double): Boolean {
        if (rectangles.isEmpty()) return false
        val fs = gs.text.fontSize
        val ascent = fs * 1.0
        val descent = fs * 0.35
        val m = gs.ctm.concat(gs.text.textMatrix).let { Matrix.translation(0.0, gs.text.rise).concat(it) }
        return boxIntersects(m, x0 = 0.0, y0 = -descent, x1 = advance, y1 = ascent)
    }

    /**
     * Handle a `Do` XObject invocation. Images intersecting a region are dropped
     * (and their name recorded so the caller can prune the resource entry); every
     * form XObject invocation is recorded as a [FormHit] (kept in the stream — the
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
            recordFormHitIfIntersects(xobjectName)
        }
        out.add(op)
    }

    /**
     * Record a [FormHit] for this form invocation carrying the redaction
     * rectangles mapped into the form's own coordinate space. The mapping is
     * `formSpace = (ctm ∘ formMatrix)⁻¹` applied to each page-space rect; when the
     * rect maps to empty area in form space the recursion simply finds nothing to
     * redact. If the matrix is singular we can't map cleanly, so we pass the
     * page-space rects through unchanged (over-covering) rather than silently
     * skipping — a redaction must never no-op on content it can't reason about.
     */
    private fun recordFormHitIfIntersects(xobjectName: String) {
        val formMatrix = formMatrices[xobjectName] ?: Matrix.IDENTITY
        // model→page transform seen by content drawn inside the form.
        val toPage = gs.ctm.concat(formMatrix)
        val inv = toPage.invert()
        val mapped = ArrayList<Rectangle>(rectangles.size)
        for (r in rectangles) {
            if (inv == null) {
                // Can't map into form space — pass the page-space rect through so
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
            mapped.add(Rectangle(left = minX, bottom = minY, right = maxX, top = maxY))
        }
        formXObjectHits.add(FormHit(xobjectName, mapped))
    }

    /** Image XObjects (and inline images) are painted into the unit square under the CTM. */
    private fun imageBoxIntersects(): Boolean {
        if (rectangles.isEmpty()) return false
        return boxIntersects(gs.ctm, 0.0, 0.0, 1.0, 1.0)
    }

    /** Map the box [x0,y0,x1,y1] through [m] and test its AABB against every rect. */
    private fun boxIntersects(m: Matrix, x0: Double, y0: Double, x1: Double, y1: Double): Boolean {
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
        Matrix(num(op, 0), num(op, 1), num(op, 2), num(op, 3), num(op, 4), num(op, 5))

    private fun num(op: Operation, i: Int): Double = when (val v = op.operands.getOrNull(i)) {
        is PdfInt -> v.value.toDouble()
        is PdfReal -> v.value
        else -> 0.0
    }

    private fun name(op: Operation, i: Int): String? = (op.operands.getOrNull(i) as? PdfName)?.value

    private fun bytesOf(o: PdfObject?): ByteArray? = (o as? PdfString)?.bytes
}
