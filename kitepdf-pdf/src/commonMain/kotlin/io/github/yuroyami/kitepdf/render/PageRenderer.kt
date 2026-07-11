package io.github.yuroyami.kitepdf.render

import io.github.yuroyami.kitepdf.core.render.applyExtGState

import io.github.yuroyami.kitepdf.core.render.BlendMode
import io.github.yuroyami.kitepdf.core.render.ColorSpace
import io.github.yuroyami.kitepdf.core.render.ExtGState
import io.github.yuroyami.kitepdf.core.render.GraphicsStack
import io.github.yuroyami.kitepdf.core.render.GraphicsState
import io.github.yuroyami.kitepdf.core.render.ImageXObject
import io.github.yuroyami.kitepdf.core.render.KiteCanvas
import io.github.yuroyami.kitepdf.core.render.KitePath
import io.github.yuroyami.kitepdf.core.render.KitePattern
import io.github.yuroyami.kitepdf.core.render.KiteShading
import io.github.yuroyami.kitepdf.core.render.Matrix
import io.github.yuroyami.kitepdf.core.render.RgbColor
import io.github.yuroyami.kitepdf.core.render.SoftMask
import io.github.yuroyami.kitepdf.core.render.TextState

import io.github.yuroyami.kitepdf.core.kiteWarn
import io.github.yuroyami.kitepdf.PdfAnnotation.Subtype
import io.github.yuroyami.kitepdf.PdfPage
import io.github.yuroyami.kitepdf.content.ContentStreamParser
import io.github.yuroyami.kitepdf.content.Operation
import io.github.yuroyami.kitepdf.core.font.PdfFont
import io.github.yuroyami.kitepdf.core.font.TextGlyph
import io.github.yuroyami.kitepdf.core.parser.IndirectResolver
import io.github.yuroyami.kitepdf.core.parser.PdfArray
import io.github.yuroyami.kitepdf.core.parser.PdfDictionary
import io.github.yuroyami.kitepdf.core.parser.PdfInt
import io.github.yuroyami.kitepdf.core.parser.PdfName
import io.github.yuroyami.kitepdf.core.parser.PdfObject
import io.github.yuroyami.kitepdf.core.parser.PdfReal
import io.github.yuroyami.kitepdf.core.parser.PdfReference
import io.github.yuroyami.kitepdf.core.parser.PdfStream
import io.github.yuroyami.kitepdf.core.parser.PdfString

/**
 * The content-stream interpreter — translates parsed [Operation]s into
 * `KiteCanvas` draw calls while maintaining the full PDF graphics-state stack
 * (ISO 32000-1 §8 + §9).
 *
 * Architecture mirrors MuPDF's pdf_processor / pdf_op_run.c:
 *   1. Walk operations one by one.
 *   2. Mutate the [GraphicsStack] for state-changing ops.
 *   3. Accumulate path construction in a [KitePath.Builder].
 *   4. On paint operators, hand the path off to the device.
 *   5. Inside `BT…ET`, run the text state machine (Tm/Tlm/Tj/TJ/'/" etc.).
 *
 * The interpreter is *single-pass and stateless w.r.t. previous pages*: every
 * call to [render] starts with a fresh state stack.
 */
public class PageRenderer(
    private val canvas: KiteCanvas,
    private val resolver: IndirectResolver,
) {

    // W/W* push a clip on the canvas, but the canvas keeps its own clip stack
    // separate from the PDF q/Q graphics-state stack. Track how many clips are
    // active so Q can pop exactly the ones pushed since its matching q —
    // otherwise clips leak past Q and can wrongly clip the rest of the page.
    private var activeClipCount = 0
    private val clipSaveStack = ArrayDeque<Int>()

    // W/W* mark the current path as a *pending* clip; per §8.5.4 the clip only
    // takes effect AFTER the next path-painting operator (S/f/B/n/…) has painted.
    // 0 = none, 1 = W (nonzero), 2 = W* (even-odd). Applied and cleared by the
    // painting op via [applyPendingClip].
    private var pendingClip = 0

    // Type3 fonts (T-42): parsed char-proc data per font instance, plus the
    // d1-uncolored flag — while true (inside a d1 glyph proc) every colour
    // operator is a spec no-op so the glyph paints with the caller's fill
    // colour (§9.6.5).
    private val type3Data = HashMap<PdfFont, Type3Data?>()
    private var type3IgnoreColor = false

    // Text render modes 4..7 (T-41): glyph outlines accumulate here in USER
    // space across the whole BT..ET block; ET intersects the union with the
    // clip (§9.3.3: the text clip applies after the text object ends and
    // persists to the enclosing Q, which activeClipCount already models).
    private var pendingTextClip: KitePath.Builder? = null

    /** An XObject with the indirect object number it resolved from (null for
     *  the rare ref-less inline entry) — the number keys the decoded caches. */
    public class XObjectSlot(public val objectNumber: Long?, public val stream: PdfStream)

    /** Parsed form-XObject resources, memoized by the form's object number so a
     *  form drawn N times (repeated stamps/icons) parses its fonts/colorspaces
     *  once. Ref-less forms rebuild each time (rare). */
    private class FormResources(
        val fonts: Map<String, PdfFont>,
        val xobjects: Map<String, XObjectSlot>,
        val colorSpaces: Map<String, ColorSpace>,
        val extGStates: Map<String, ExtGState>,
        val shadings: Map<String, KiteShading>,
        val patterns: Map<String, KitePattern>,
        val properties: Map<String, PdfObject>,
    )
    private val formResourceCache = HashMap<Long, FormResources>()

    // ─── Optional content (layers) ───────────────────────────────────────────
    // Marked-content sections introduced by `BDC /OC <ocg>` are suppressed when
    // the referenced OCG/OCMD is hidden in the document's default configuration
    // (ISO 32000-1 §8.11). markedContentStack tracks every open BMC/BDC so EMC
    // pops the matching one; ocHiddenDepth counts how many open sections are
    // currently hiding content — painting is skipped while it is > 0.
    private val markedContentStack = ArrayDeque<Boolean>()
    private var ocHiddenDepth = 0
    private var optionalContent: io.github.yuroyami.kitepdf.PdfOptionalContent? = null

    /** The page's default (initial) CTM — pattern matrices are relative to it. */
    private var pageBaseCtm: Matrix = Matrix.IDENTITY

    /** Form-XObject nesting depth — guards self/transitively-recursive `Do`. */
    private var formDepth = 0

    /**
     * Operations dispatched for the current page, including every tiling-cell
     * and form-XObject replay. Once past [MAX_DISPATCHED_OPS], [dispatch]
     * becomes a no-op so an adversarial stream (millions of ops, or a small
     * pattern replayed thousands of times) terminates instead of rendering
     * forever. The page finishes with whatever was painted.
     */
    private var dispatchedOps = 0L

    /** True while content must not be painted (inside a hidden OC section). */
    private fun ocHidden(): Boolean = ocHiddenDepth > 0

    public fun render(page: PdfPage, deviceCtm: Matrix = defaultDeviceCtm(page)) {
        val fonts = loadFonts(page.resources)
        val xobjects = loadXObjects(page.resources)
        val colorSpaces = loadColorSpaces(page.resources)
        val extGStates = loadExtGStates(page.resources)
        val shadings = loadShadings(page.resources)
        val patterns = loadPatterns(page.resources, shadings)
        val properties = loadProperties(page.resources)
        val state = GraphicsStack(GraphicsState(ctm = deviceCtm))
        activeClipCount = 0
        clipSaveStack.clear()
        pendingClip = 0
        pageBaseCtm = deviceCtm
        formDepth = 0
        dispatchedOps = 0L
        optionalContent = page.internalDocument.optionalContent
        markedContentStack.clear()
        ocHiddenDepth = 0
        val pathBuilder = KitePath.Builder()
        val ops = ContentStreamParser.parse(page.contentBytes)

        // Size the device surface for the ROTATED page: pageToDeviceBase() maps
        // into [0,rotatedWidth] x [0,rotatedHeight], so width/height must be
        // swapped for /Rotate 90/270 to match.
        canvas.beginPage(page.rotatedWidth, page.rotatedHeight, deviceCtm)
        try {
            for (op in ops) dispatch(op, state, pathBuilder, fonts, xobjects, colorSpaces, extGStates, shadings, patterns, properties)
            // Page content may leave the graphics stack unbalanced (a stray `cm`
            // with no matching q/Q). Annotations must render on a clean page CTM,
            // not that leftover state, else an unbalanced cm skews every annotation.
            // Also drop any clips the page content left active.
            while (activeClipCount > 0) { canvas.popClip(); activeClipCount-- }
            renderAnnotations(page, GraphicsStack(GraphicsState(ctm = deviceCtm)))
        } finally {
            canvas.endPage()
        }
    }

    /** Named property lists declared in /Resources /Properties (for `BDC /OC`). */
    private fun loadProperties(resources: PdfDictionary?): Map<String, PdfObject> =
        resources?.getDict("Properties", resolver)?.map ?: emptyMap()

    /* ─── Optional-content visibility ────────────────────────────────────────── */

    /** Whether a `BDC /OC <operand>` introduces a hidden section. */
    private fun isOcOperandHidden(operand: PdfObject?, properties: Map<String, PdfObject>): Boolean {
        val oc = optionalContent ?: return false
        val target = when (operand) {
            is PdfName -> properties[operand.value]
            else -> operand
        } ?: return false
        return !isOcObjectVisible(target, oc)
    }

    /** Whether an XObject's own `/OC` entry (if any) is currently visible. */
    private fun isXObjectOcHidden(stream: PdfStream): Boolean {
        val oc = optionalContent ?: return false
        val ocObj = stream.dict["OC"] ?: return false
        return !isOcObjectVisible(ocObj, oc)
    }

    /** Resolve an OCG or OCMD object and decide if it is visible by default. */
    private fun isOcObjectVisible(obj: PdfObject, oc: io.github.yuroyami.kitepdf.PdfOptionalContent): Boolean {
        val dict = obj.resolve(resolver) as? PdfDictionary ?: return true
        if (dict.getName("Type") == "OCMD") return evalOcmd(dict, oc)
        // Plain OCG: hidden only if explicitly OFF in the default configuration.
        val id = (obj as? io.github.yuroyami.kitepdf.core.parser.PdfReference)?.objectNumber?.toString()
            ?: return true
        return id !in oc.offByDefault
    }

    /**
     * Evaluate an OCMD (§8.11.2.2). A /VE visibility expression, when present,
     * takes precedence over the /OCGs + /P membership dictionary; only if there
     * is no /VE do we fall back to /OCGs and the /P policy.
     */
    private fun evalOcmd(dict: PdfDictionary, oc: io.github.yuroyami.kitepdf.PdfOptionalContent): Boolean {
        (dict["VE"]?.resolve(resolver) as? PdfArray)?.let { ve ->
            return evalVisibilityExpr(ve, oc)
        }
        val ocgsRaw = dict["OCGs"]
        val refs: List<io.github.yuroyami.kitepdf.core.parser.PdfReference> = when (val r = ocgsRaw?.resolve(resolver)) {
            is PdfArray -> r.mapNotNull { it as? io.github.yuroyami.kitepdf.core.parser.PdfReference }
            else -> listOfNotNull(ocgsRaw as? io.github.yuroyami.kitepdf.core.parser.PdfReference)
        }
        if (refs.isEmpty()) return true
        val visible = refs.map { it.objectNumber.toString() !in oc.offByDefault }
        return when (dict.getName("P")) {
            "AllOn" -> visible.all { it }
            "AnyOff" -> visible.any { !it }
            "AllOff" -> visible.all { !it }
            else -> visible.any { it }   // AnyOn (default)
        }
    }

    /**
     * Evaluate an OCMD /VE visibility expression (§8.11.2.2): an array whose
     * first element is /And, /Or, or /Not and whose remaining elements are
     * either OCG references or nested /VE arrays. Returns whether the expression
     * is currently satisfied (i.e. the content is visible).
     */
    private fun evalVisibilityExpr(expr: PdfArray, oc: io.github.yuroyami.kitepdf.PdfOptionalContent): Boolean {
        val opName = (expr.getOrNull(0) as? PdfName)?.value ?: return true
        val operands = (1 until expr.size).mapNotNull { expr.getOrNull(it) }
        fun evalOperand(o: PdfObject): Boolean = when (val r = o.resolve(resolver)) {
            is PdfArray -> evalVisibilityExpr(r, oc)
            else -> {
                // A bare OCG reference: visible unless OFF in the default config.
                val id = (o as? io.github.yuroyami.kitepdf.core.parser.PdfReference)?.objectNumber?.toString()
                id == null || id !in oc.offByDefault
            }
        }
        return when (opName) {
            "Not" -> operands.firstOrNull()?.let { !evalOperand(it) } ?: true
            "Or" -> operands.any { evalOperand(it) }
            "And" -> operands.all { evalOperand(it) }
            else -> true
        }
    }

    /** Named shadings declared in /Resources /Shading. */
    private fun loadShadings(resources: PdfDictionary?): Map<String, KiteShading> {
        val dict = resources?.getDict("Shading", resolver) ?: return emptyMap()
        return dict.map.mapNotNull { (name, value) ->
            val sh = KiteShading.parse(value, resolver) ?: return@mapNotNull null
            name to sh
        }.toMap()
    }

    /**
     * Named patterns declared in /Resources /Pattern. PatternType 1 (tiling)
     * parses to [KitePattern.Tiling] and [renderTilingPattern] replays its cell
     * content stream across the fill region (bounded by [MAX_TILES]);
     * PatternType 2 (shading) parses to [KitePattern.Shading] and paints
     * through [KiteCanvas.fillShading].
     */
    private fun loadPatterns(
        resources: PdfDictionary?,
        shadings: Map<String, KiteShading>,
    ): Map<String, KitePattern> {
        val dict = resources?.getDict("Pattern", resolver) ?: return emptyMap()
        return dict.map.mapNotNull { (name, value) ->
            val p = KitePattern.parse(value, resolver, shadings) ?: return@mapNotNull null
            name to p
        }.toMap()
    }

    /** Named extended graphics states declared in /Resources /ExtGState. */
    private fun loadExtGStates(resources: PdfDictionary?): Map<String, ExtGState> {
        val dict = resources?.getDict("ExtGState", resolver) ?: return emptyMap()
        return dict.map.mapNotNull { (name, value) ->
            val resolved = value.resolve(resolver) as? PdfDictionary ?: return@mapNotNull null
            name to ExtGState.parse(resolved, resolver)
        }.toMap()
    }

    /**
     * Paint each annotation (ISO 32000-1 §12.5.5). Order: the spec says page
     * content first, then annotations on top. Per annotation:
     *   - If /AP /N is present, render the Form XObject mapped into the
     *     annotation's /Rect (spec §12.5.5 explains the bbox transform).
     *   - Otherwise paint a thin bounding rectangle in the annotation's
     *     colour so the annotation isn't invisible. (Highlight gets a
     *     yellow translucent fill; Link gets a thin border.)
     */
    private fun renderAnnotations(page: io.github.yuroyami.kitepdf.PdfPage, state: GraphicsStack) {
        for (annot in page.annotations) {
            if (annot.isHidden) continue   // /F Hidden or NoView (§12.5.3)
            // Popup annotations are only shown when their parent is opened — never
            // painted inline by a viewer.
            if (annot.subtype == io.github.yuroyami.kitepdf.PdfAnnotation.Subtype.Popup) continue
            val stream = annot.appearanceStream
            if (stream != null) {
                renderAppearanceForRect(stream, annot.rect, state)
            } else {
                synthesizeAppearance(annot, state)
            }
        }
    }

    /**
     * Map a Form XObject appearance to fill the annotation's /Rect, per
     * ISO 32000-1 §12.5.5 Algorithm 8.1:
     *   1. Transform the appearance /BBox corners by the appearance /Matrix.
     *   2. Take the smallest upright rectangle enclosing those corners — the
     *      "transformed appearance box".
     *   3. Compute matrix A mapping that transformed box onto /Rect.
     * We concat A into the CTM; the form's own /Matrix is then applied by
     * [renderFormXObjectInner] when it draws the content (so the two compose to
     * BBox → transformed-box → Rect). Previously /Matrix was ignored, so a
     * rotated/skewed appearance landed off its /Rect.
     */
    private fun renderAppearanceForRect(
        appearance: PdfStream,
        rect: io.github.yuroyami.kitepdf.core.Rectangle,
        state: GraphicsStack,
    ) {
        val bbox = appearance.dict.getArray("BBox")?.let { arr ->
            io.github.yuroyami.kitepdf.core.Rectangle(
                arr.getOrNull(0).toDouble(), arr.getOrNull(1).toDouble(),
                arr.getOrNull(2).toDouble(), arr.getOrNull(3).toDouble(),
            )
        } ?: io.github.yuroyami.kitepdf.core.Rectangle(0.0, 0.0, rect.width, rect.height)

        val matrix = appearance.dict.getArray("Matrix")?.let { arr ->
            Matrix(
                arr.getOrNull(0).toDouble(), arr.getOrNull(1).toDouble(),
                arr.getOrNull(2).toDouble(), arr.getOrNull(3).toDouble(),
                arr.getOrNull(4).toDouble(), arr.getOrNull(5).toDouble(),
            )
        } ?: Matrix.IDENTITY

        // Step 1+2: transform the four BBox corners by /Matrix, enclose upright.
        val corners = listOf(
            matrix.transformPoint(bbox.left, bbox.bottom),
            matrix.transformPoint(bbox.right, bbox.bottom),
            matrix.transformPoint(bbox.right, bbox.top),
            matrix.transformPoint(bbox.left, bbox.top),
        )
        val tbLeft = corners.minOf { it.first }; val tbRight = corners.maxOf { it.first }
        val tbBottom = corners.minOf { it.second }; val tbTop = corners.maxOf { it.second }
        val tbW = tbRight - tbLeft; val tbH = tbTop - tbBottom

        // Step 3: A maps the transformed appearance box onto /Rect.
        val sx = if (tbW != 0.0) rect.width / tbW else 1.0
        val sy = if (tbH != 0.0) rect.height / tbH else 1.0
        val mapping = Matrix(sx, 0.0, 0.0, sy, rect.left - tbLeft * sx, rect.bottom - tbBottom * sy)

        state.save()
        state.replace(state.current.copy(ctm = state.current.ctm.concat(mapping)))
        try {
            renderFormXObject(appearance, state)
        } finally {
            state.restore()
        }
    }

    /**
     * Synthesize an annotation's appearance when it has no `/AP` stream
     * (ISO 32000-1 §12.5.5 says a conforming viewer "should" generate one). Uses
     * the annotation's own geometry (`/QuadPoints`, `/Vertices`/`/L`, `/InkList`)
     * so markup lands on the marked text/region rather than the whole /Rect.
     */
    private fun synthesizeAppearance(
        annot: io.github.yuroyami.kitepdf.PdfAnnotation,
        state: GraphicsStack,
    ) {
        val ctm = state.current.ctm
        val rect = annot.rect
        val quads = annot.quadPoints
        when (annot.subtype) {
            Subtype.Highlight -> {
                val color = annot.color ?: RgbColor(1.0, 1.0, 0.0)
                forEachQuad(quads, rect) { x0, y0, x1, y1 ->
                    val p = KitePath.Builder().apply { rectangle(x0, y0, x1 - x0, y1 - y0) }.build()
                    // Highlights multiply onto the page; approximate with alpha.
                    canvas.fillPath(p, ctm, color, false, alpha = 0.4)
                }
            }
            Subtype.Underline, Subtype.StrikeOut, Subtype.Squiggly -> {
                val color = annot.color ?: RgbColor.BLACK
                forEachQuad(quads, rect) { x0, y0, x1, y1 ->
                    val frac = if (annot.subtype == Subtype.StrikeOut) 0.5 else 0.08
                    val y = y0 + (y1 - y0) * frac
                    val line = KitePath.Builder().apply { moveTo(x0, y); lineTo(x1, y) }.build()
                    canvas.strokePath(line, ctm, color, ((y1 - y0) * 0.06).coerceAtLeast(0.6))
                }
            }
            Subtype.Square -> {
                val p = KitePath.Builder().apply { rectangle(rect.left, rect.bottom, rect.width, rect.height) }.build()
                annot.interiorColor?.let { canvas.fillPath(p, ctm, it, false) }
                canvas.strokePath(p, ctm, annot.color ?: RgbColor.BLACK, 1.0)
            }
            Subtype.Circle -> {
                val p = ellipsePath(rect)
                annot.interiorColor?.let { canvas.fillPath(p, ctm, it, false) }
                canvas.strokePath(p, ctm, annot.color ?: RgbColor.BLACK, 1.0)
            }
            Subtype.Line -> annot.vertices?.let { v ->
                if (v.size >= 4) {
                    val line = KitePath.Builder().apply { moveTo(v[0], v[1]); lineTo(v[2], v[3]) }.build()
                    canvas.strokePath(line, ctm, annot.color ?: RgbColor.BLACK, 1.0)
                }
            }
            Subtype.Polygon, Subtype.PolyLine -> annot.vertices?.let { v ->
                val p = polyPath(v, close = annot.subtype == Subtype.Polygon)
                if (p != null) {
                    if (annot.subtype == Subtype.Polygon) annot.interiorColor?.let { canvas.fillPath(p, ctm, it, false) }
                    canvas.strokePath(p, ctm, annot.color ?: RgbColor.BLACK, 1.0)
                }
            }
            Subtype.Ink -> annot.inkLists?.forEach { stroke ->
                polyPath(stroke, close = false)?.let { canvas.strokePath(it, ctm, annot.color ?: RgbColor.BLACK, 1.0) }
            }
            Subtype.Link -> {
                val p = KitePath.Builder().apply { rectangle(rect.left, rect.bottom, rect.width, rect.height) }.build()
                canvas.strokePath(p, ctm, annot.color ?: RgbColor(0.0, 0.3, 0.8), 0.5)
            }
            else -> { /* other annotations: nothing without /AP */ }
        }
    }

    /** Invoke [block] once per /QuadPoints quad (as a min/max box), or once over
     *  the whole [rect] when no quads are present. */
    private inline fun forEachQuad(
        quads: List<Double>?, rect: io.github.yuroyami.kitepdf.core.Rectangle,
        block: (x0: Double, y0: Double, x1: Double, y1: Double) -> Unit,
    ) {
        if (quads != null && quads.size >= 8) {
            var i = 0
            while (i + 7 < quads.size) {
                val xs = listOf(quads[i], quads[i + 2], quads[i + 4], quads[i + 6])
                val ys = listOf(quads[i + 1], quads[i + 3], quads[i + 5], quads[i + 7])
                block(xs.min(), ys.min(), xs.max(), ys.max())
                i += 8
            }
        } else {
            block(rect.left, rect.bottom, rect.left + rect.width, rect.bottom + rect.height)
        }
    }

    /** Four-Bézier ellipse inscribed in [rect]. */
    private fun ellipsePath(rect: io.github.yuroyami.kitepdf.core.Rectangle): KitePath {
        val cx = rect.left + rect.width / 2; val cy = rect.bottom + rect.height / 2
        val rx = rect.width / 2; val ry = rect.height / 2
        val k = 0.5522847498
        return KitePath.Builder().apply {
            moveTo(cx + rx, cy)
            curveTo(cx + rx, cy + ry * k, cx + rx * k, cy + ry, cx, cy + ry)
            curveTo(cx - rx * k, cy + ry, cx - rx, cy + ry * k, cx - rx, cy)
            curveTo(cx - rx, cy - ry * k, cx - rx * k, cy - ry, cx, cy - ry)
            curveTo(cx + rx * k, cy - ry, cx + rx, cy - ry * k, cx + rx, cy)
            close()
        }.build()
    }

    /** Build a polyline/polygon path from alternating x/y values. */
    private fun polyPath(coords: List<Double>, close: Boolean): KitePath? {
        if (coords.size < 4) return null
        val b = KitePath.Builder()
        b.moveTo(coords[0], coords[1])
        var i = 2
        while (i + 1 < coords.size) { b.lineTo(coords[i], coords[i + 1]); i += 2 }
        if (close) b.close()
        return b.build()
    }

    /** Named colour spaces declared in /Resources /ColorSpace. */
    private fun loadColorSpaces(resources: PdfDictionary?): Map<String, ColorSpace> {
        val csDict = resources?.getDict("ColorSpace", resolver) ?: return emptyMap()
        return csDict.map.mapValues { (_, value) -> ColorSpace.resolve(value, resolver) }
    }

    /**
     * Decode an inline image captured verbatim as `BI … ID <data> EI` (§8.9.7).
     * Parses the abbreviated dictionary, slices the raw data, and builds an
     * [ImageXObject] driven through the normal raster path. [fillColor] tints an
     * inline `/ImageMask` stencil.
     */
    /**
     * Decode an image XObject through the per-document cache (T-12): keyed by
     * the indirect object number, so a logo stamped 40 times (or a background
     * shared by every page) decodes once per document. /ImageMask stencils are
     * tinted by the CURRENT fill colour, so their decoded form is
     * state-dependent: never cached. Ref-less images skip the cache too.
     */
    private fun decodeImageCached(slot: XObjectSlot, fillColor: RgbColor): ImageXObject {
        val doc = resolver as? io.github.yuroyami.kitepdf.PdfDocument
        val key = slot.objectNumber
        val isMask = (slot.stream.dict["ImageMask"] as? io.github.yuroyami.kitepdf.core.parser.PdfBoolean)?.value == true
        if (doc == null || key == null || isMask) {
            doc?.countImageDecode()
            return ImageXObject.from(slot.stream, resolver, fillColor)
        }
        doc.cachedImage(key)?.let { return it }
        doc.countImageDecode()
        return doc.cacheImage(key, ImageXObject.from(slot.stream, resolver, fillColor))
    }

    private fun decodeInlineImage(blob: ByteArray, fillColor: RgbColor): ImageXObject? {
        if (blob.size < 4) return null
        val reader = io.github.yuroyami.kitepdf.core.ByteReader(blob)
        reader.seek(2) // skip "BI"
        val lexer = io.github.yuroyami.kitepdf.core.parser.Lexer(reader)
        val parser = io.github.yuroyami.kitepdf.parser.Parser(lexer)
        val entries = LinkedHashMap<String, PdfObject>()
        while (true) {
            val tok = lexer.nextToken()
            if (tok is io.github.yuroyami.kitepdf.core.parser.Token.Keyword && tok.value == "ID") break
            if (tok == io.github.yuroyami.kitepdf.core.parser.Token.EndOfFile) return null
            if (tok !is io.github.yuroyami.kitepdf.core.parser.Token.Name) return null
            entries[normalizeInlineKey(tok.value)] = runCatching { parser.readObject() }.getOrNull() ?: return null
        }
        var dataStart = reader.pos()
        val w0 = if (dataStart < blob.size) blob[dataStart].toInt() and 0xFF else -1
        if (w0 == ' '.code || w0 == '\n'.code || w0 == '\r'.code || w0 == '\t'.code) dataStart++
        var dataEnd = blob.size - 2 // before the trailing "EI"
        while (dataEnd > dataStart) {
            val c = blob[dataEnd - 1].toInt() and 0xFF
            if (c == ' '.code || c == '\n'.code || c == '\r'.code || c == '\t'.code) dataEnd-- else break
        }
        if (dataEnd < dataStart) return null
        val data = blob.copyOfRange(dataStart, dataEnd)
        entries["Length"] = PdfInt(data.size.toLong())
        val stream = PdfStream(PdfDictionary(entries), data)
        return runCatching { ImageXObject.from(stream, resolver, fillColor) }.getOrNull()
    }

    /** Expand the abbreviated inline-image dictionary keys (§8.9.7 Table 92). */
    private fun normalizeInlineKey(k: String): String = when (k) {
        "W" -> "Width"; "H" -> "Height"; "BPC" -> "BitsPerComponent"
        "CS" -> "ColorSpace"; "F" -> "Filter"; "IM" -> "ImageMask"
        "D" -> "Decode"; "DP" -> "DecodeParms"; "I" -> "Interpolate"
        else -> k
    }

    /**
     * Render a child content stream inside a Form XObject (ISO 32000-1 §8.10).
     * When the form has a `/Group` dict with `/S /Transparency`, the
     * rendering is wrapped in a transparency group so its compositing
     * happens onto an offscreen layer that's blended back at the end.
     */
    private fun renderFormXObject(
        formStream: PdfStream,
        parentState: GraphicsStack,
        /** The form's indirect object number; null (annotation appearances,
         *  ref-less entries) skips the resource cache. */
        objectNumber: Long? = null,
    ) {
        // Recursion guard: a form that (transitively) draws itself would overflow
        // the native stack on malformed/malicious input.
        if (formDepth >= MAX_FORM_DEPTH) return
        formDepth++
        try {
            renderFormXObjectInner(formStream, parentState, objectNumber)
        } finally {
            formDepth--
        }
    }

    private fun renderFormXObjectInner(
        formStream: PdfStream,
        parentState: GraphicsStack,
        objectNumber: Long?,
    ) {
        val formMatrix = formStream.dict.getArray("Matrix")?.let { arr ->
            Matrix(
                arr.getOrNull(0).toDouble(), arr.getOrNull(1).toDouble(),
                arr.getOrNull(2).toDouble(), arr.getOrNull(3).toDouble(),
                arr.getOrNull(4).toDouble(), arr.getOrNull(5).toDouble(),
            )
        } ?: Matrix.IDENTITY
        val bbox = formStream.dict.getArray("BBox")?.let { arr ->
            io.github.yuroyami.kitepdf.core.Rectangle(
                arr.getOrNull(0).toDouble(), arr.getOrNull(1).toDouble(),
                arr.getOrNull(2).toDouble(), arr.getOrNull(3).toDouble(),
            )
        } ?: io.github.yuroyami.kitepdf.core.Rectangle(0.0, 0.0, 1000.0, 1000.0)
        fun buildResources(): FormResources {
            val resources = formStream.dict.getDict("Resources", resolver)
            val sh = loadShadings(resources)
            return FormResources(
                fonts = loadFonts(resources),
                xobjects = loadXObjects(resources),
                colorSpaces = loadColorSpaces(resources),
                extGStates = loadExtGStates(resources),
                shadings = sh,
                patterns = loadPatterns(resources, sh),
                properties = loadProperties(resources),
            )
        }
        // Keyed by object number so lookups don't deep-hash the stream's
        // dictionary (the old PdfStream key hashed the whole map + bytes).
        val res = objectNumber?.let { formResourceCache.getOrPut(it) { buildResources() } }
            ?: buildResources()
        val childFonts = res.fonts
        val childXObjects = res.xobjects
        val childColorSpaces = res.colorSpaces
        val childExtGStates = res.extGStates
        val childShadings = res.shadings
        val childPatterns = res.patterns
        val childProperties = res.properties
        val groupDict = formStream.dict.getDict("Group", resolver)
        val isTransparencyGroup = groupDict?.getName("S") == "Transparency"
        val isolated = (groupDict?.get("I") as? io.github.yuroyami.kitepdf.core.parser.PdfBoolean)?.value ?: false
        val knockout = (groupDict?.get("K") as? io.github.yuroyami.kitepdf.core.parser.PdfBoolean)?.value ?: false

        parentState.save()
        parentState.replace(parentState.current.copy(
            ctm = parentState.current.ctm.concat(formMatrix),
        ))
        val groupOpened = isTransparencyGroup
        if (groupOpened) {
            // The group's constant alpha + blend mode apply ONCE, to the composite
            // of the whole group onto the backdrop (§11.4.5). If we also left them
            // on the state, every paint inside would multiply them again (double
            // application). Hand them to beginTransparencyGroup and reset the
            // in-group state to alpha=1 / Normal so inner paints composite plainly
            // onto the group's transparent backdrop.
            canvas.beginTransparencyGroup(
                bbox = bbox, ctm = parentState.current.ctm,
                isolated = isolated, knockout = knockout,
                alpha = parentState.current.fillAlpha,
                blendMode = parentState.current.blendMode,
            )
            parentState.replace(parentState.current.copy(
                fillAlpha = 1.0, strokeAlpha = 1.0, blendMode = BlendMode.Normal,
            ))
        }
        // Clip the form's content to its /BBox (§8.10.1) so it cannot overdraw
        // outside the intended region.
        val bboxPath = KitePath.Builder().apply {
            rectangle(bbox.left, bbox.bottom, bbox.right - bbox.left, bbox.top - bbox.bottom)
        }.build()
        canvas.pushClip(bboxPath, parentState.current.ctm, false)
        val clipBase = activeClipCount
        // A pending W/W* is scoped to the content stream that issued it; don't let
        // one leak in from (or out to) the caller across the form boundary.
        val savedPendingClip = pendingClip
        pendingClip = 0
        try {
            val bytes = io.github.yuroyami.kitepdf.core.filters.FilterChain.decode(formStream)
            val ops = ContentStreamParser.parse(bytes)
            val pathBuilder = KitePath.Builder()
            for (op in ops) dispatch(op, parentState, pathBuilder, childFonts, childXObjects, childColorSpaces, childExtGStates, childShadings, childPatterns, childProperties)
        } finally {
            pendingClip = savedPendingClip
            // Drop any clips the form's content left unbalanced, then the BBox clip.
            while (activeClipCount > clipBase) { canvas.popClip(); activeClipCount-- }
            canvas.popClip()
            if (groupOpened) canvas.endTransparencyGroup()
            parentState.restore()
        }
    }

    private fun PdfObject?.toDouble(): Double = when (this) {
        is PdfInt -> value.toDouble()
        is PdfReal -> value
        else -> 0.0
    }

    /** Build the page resource → font dictionary, lazily resolving each entry. */
    private fun loadFonts(resources: PdfDictionary?): Map<String, PdfFont> {
        val fonts = resources?.getDict("Font", resolver) ?: return emptyMap()
        return fonts.map.mapValues { (_, ref) ->
            val font = PdfFont.from(ref, resolver)
            if (font.subtype == "Type3" && font !in type3Data) {
                val dict = ref.resolve(resolver) as? PdfDictionary
                type3Data[font] = dict?.let { Type3Data.parse(it, resolver) }
            }
            font
        }
    }

    /** Build the page resource → XObject dictionary. Each entry is a stream. */
    private fun loadXObjects(resources: PdfDictionary?): Map<String, XObjectSlot> {
        val xobjs = resources?.getDict("XObject", resolver) ?: return emptyMap()
        return xobjs.map.mapNotNull { (name, raw) ->
            val resolved = raw.resolve(resolver) as? PdfStream ?: return@mapNotNull null
            // Keep the indirect object number: it keys the per-document decoded
            // caches (T-12). Inline (ref-less) entries decode uncached.
            name to XObjectSlot((raw as? PdfReference)?.objectNumber, resolved)
        }.toMap()
    }

    /**
     * Default device transform: unscaled user-space → device with the origin at
     * the TOP-LEFT (y-down), honouring the display box origin and normalized
     * /Rotate. Delegates to [PdfPage.pageToDeviceBase] so /Rotate, a non-zero
     * MediaBox origin, and CropBox are all folded in (they were previously
     * ignored by the old `Matrix(1,0,0,-1,0,height)`).
     */
    private fun defaultDeviceCtm(page: PdfPage): Matrix =
        page.pageToDeviceBase()

    /* ─── Operator dispatch ──────────────────────────────────────────────── */

    private fun dispatch(
        op: Operation,
        state: GraphicsStack,
        path: KitePath.Builder,
        fonts: Map<String, PdfFont>,
        xobjects: Map<String, XObjectSlot>,
        colorSpaces: Map<String, ColorSpace>,
        extGStates: Map<String, ExtGState>,
        shadings: Map<String, KiteShading>,
        patterns: Map<String, KitePattern>,
        properties: Map<String, PdfObject>,
    ) {
        if (++dispatchedOps > MAX_DISPATCHED_OPS) return
        // d1 (uncolored) Type3 glyph procs must not change colour state.
        if (type3IgnoreColor && op.operator in TYPE3_COLOR_OPS) return
        val a = op.operands
        when (op.operator) {
            // Type3 glyph metrics operators (§9.6.5): d0 declares a coloured
            // glyph (nothing to do — wx/wy come from /Widths); d1 declares an
            // uncoloured one, so colour operators are ignored from here on.
            "d0" -> Unit
            "d1" -> type3IgnoreColor = true
            // ─── State stack ──────────────────────────────────────────────
            "q" -> { state.save(); clipSaveStack.addLast(activeClipCount) }
            "Q" -> {
                state.restore()
                val target = clipSaveStack.removeLastOrNull() ?: 0
                while (activeClipCount > target) { canvas.popClip(); activeClipCount-- }
            }
            "cm" -> {
                val m = Matrix(num(a, 0), num(a, 1), num(a, 2), num(a, 3), num(a, 4), num(a, 5))
                state.replace(state.current.copy(ctm = state.current.ctm.concat(m)))
            }
            // A bare `w` (no operand) must keep the current width, not reset to 0
            // (which would render every subsequent stroke as a hairline). Only
            // update when an operand is actually present.
            "w" -> if (a.isNotEmpty()) state.replace(state.current.copy(lineWidth = num(a, 0)))
            "J" -> state.replace(state.current.copy(lineCap = num(a, 0).toInt()))
            "j" -> state.replace(state.current.copy(lineJoin = num(a, 0).toInt()))
            "M" -> state.replace(state.current.copy(miterLimit = num(a, 0)))
            "d" -> {
                // dash: [ array ] phase d  — array of on/off lengths (user units).
                val arr = a.getOrNull(0) as? io.github.yuroyami.kitepdf.core.parser.PdfArray
                val dashes = arr?.let { ar -> List(ar.size) { ar.getOrNull(it).toDouble() } }
                state.replace(state.current.copy(
                    dashArray = dashes?.takeIf { ds -> ds.isNotEmpty() && ds.any { it > 0.0 } },
                    dashPhase = num(a, 1),
                ))
            }

            // ─── Color ────────────────────────────────────────────────────
            // g/rg/k (and stroke variants) also RESET the active colour space
            // to the corresponding device family (§8.6.8). Otherwise a later bare
            // `sc`/`scn` would still see a stale non-device space and misread the
            // component count.
            "g" -> state.replace(state.current.copy(
                fillColor = RgbColor.gray(num(a, 0)),
                fillColorSpace = ColorSpace.DeviceGray, fillPattern = null,
            ))
            "G" -> state.replace(state.current.copy(
                strokeColor = RgbColor.gray(num(a, 0)),
                strokeColorSpace = ColorSpace.DeviceGray, strokePattern = null,
            ))
            "rg" -> state.replace(state.current.copy(
                fillColor = RgbColor(num(a, 0), num(a, 1), num(a, 2)),
                fillColorSpace = ColorSpace.DeviceRGB, fillPattern = null,
            ))
            "RG" -> state.replace(state.current.copy(
                strokeColor = RgbColor(num(a, 0), num(a, 1), num(a, 2)),
                strokeColorSpace = ColorSpace.DeviceRGB, strokePattern = null,
            ))
            "k" -> state.replace(state.current.copy(
                fillColor = ColorSpace.DeviceCMYK.toRgb(
                    doubleArrayOf(num(a, 0), num(a, 1), num(a, 2), num(a, 3)),
                ),
                fillColorSpace = ColorSpace.DeviceCMYK, fillPattern = null,
            ))
            "K" -> state.replace(state.current.copy(
                strokeColor = ColorSpace.DeviceCMYK.toRgb(
                    doubleArrayOf(num(a, 0), num(a, 1), num(a, 2), num(a, 3)),
                ),
                strokeColorSpace = ColorSpace.DeviceCMYK, strokePattern = null,
            ))
            // cs/CS select the colour space for subsequent sc/scn/SC/SCN. Without them a
            // non-device space (e.g. CoreGraphics' ICCBased-RGB on iOS-generated PDFs) stayed
            // at the default DeviceGray, so `r g b SCN` was read as gray(r) — turning the pink
            // ECG grid white. Per ISO 32000-1 §8.6.8 selecting a space resets the colour to its
            // initial value (black) until the next sc/scn sets components.
            "cs" -> {
                val csp = (a.firstOrNull() as? io.github.yuroyami.kitepdf.core.parser.PdfName)
                    ?.let { namedColorSpace(it.value, colorSpaces) } ?: ColorSpace.DeviceGray
                state.replace(state.current.copy(fillColorSpace = csp, fillColor = csp.defaultColor(), fillPattern = null))
            }
            "CS" -> {
                val csp = (a.firstOrNull() as? io.github.yuroyami.kitepdf.core.parser.PdfName)
                    ?.let { namedColorSpace(it.value, colorSpaces) } ?: ColorSpace.DeviceGray
                state.replace(state.current.copy(strokeColorSpace = csp, strokeColor = csp.defaultColor(), strokePattern = null))
            }

            // ─── Path construction ───────────────────────────────────────
            "m" -> path.moveTo(num(a, 0), num(a, 1))
            "l" -> path.lineTo(num(a, 0), num(a, 1))
            "c" -> path.curveTo(num(a, 0), num(a, 1), num(a, 2), num(a, 3), num(a, 4), num(a, 5))
            "v" -> path.curveToV(num(a, 0), num(a, 1), num(a, 2), num(a, 3))
            "y" -> path.curveToY(num(a, 0), num(a, 1), num(a, 2), num(a, 3))
            "h" -> path.close()
            "re" -> path.rectangle(num(a, 0), num(a, 1), num(a, 2), num(a, 3))

            // ─── Path painting (suppressed inside hidden optional content) ──
            // Each painting operator ends the path object: it paints, then applies
            // any pending W/W* clip (§8.5.4 — the clip uses this same path), then
            // clears the path. `n` paints nothing but still ends the path object.
            "S" -> { if (!ocHidden()) paintStroke(path, state); applyPendingClip(path, state); path.reset() }
            "s" -> { path.close(); if (!ocHidden()) paintStroke(path, state); applyPendingClip(path, state); path.reset() }
            "f", "F" -> { if (!ocHidden()) paintFill(path, state, evenOdd = false); applyPendingClip(path, state); path.reset() }
            "f*" -> { if (!ocHidden()) paintFill(path, state, evenOdd = true); applyPendingClip(path, state); path.reset() }
            "B" -> { if (!ocHidden()) { paintFill(path, state, false); paintStroke(path, state) }; applyPendingClip(path, state); path.reset() }
            "B*" -> { if (!ocHidden()) { paintFill(path, state, true); paintStroke(path, state) }; applyPendingClip(path, state); path.reset() }
            "b" -> { path.close(); if (!ocHidden()) { paintFill(path, state, false); paintStroke(path, state) }; applyPendingClip(path, state); path.reset() }
            "b*" -> { path.close(); if (!ocHidden()) { paintFill(path, state, true); paintStroke(path, state) }; applyPendingClip(path, state); path.reset() }
            "n" -> { applyPendingClip(path, state); path.reset() }

            // ─── Clipping (marked pending; applied after the *next* paint) ──
            "W" -> if (!path.isEmpty()) pendingClip = 1
            "W*" -> if (!path.isEmpty()) pendingClip = 2

            // ─── Text state ──────────────────────────────────────────────
            // BT resets ONLY the text matrices (Tm/Tlm → identity, §9.4.1). All
            // other text-state params — char/word spacing, horizontal scale,
            // leading, rise, render mode, plus the current font/size — persist
            // across text objects (§9.3.1); they belong to the graphics state.
            "BT" -> {
                pendingTextClip = null
                state.mutateText {
                    it.copy(textMatrix = Matrix.IDENTITY, lineMatrix = Matrix.IDENTITY)
                }
            }
            "ET" -> {
                // Apply the accumulated modes-4..7 text clip (T-41).
                val clip = pendingTextClip
                pendingTextClip = null
                if (clip != null) {
                    val built = clip.build()
                    if (!built.isEmpty()) {
                        canvas.pushClip(built, state.current.ctm, evenOdd = false)
                        activeClipCount++
                    }
                }
            }
            "Tf" -> {
                val fontName = (a.getOrNull(0) as? PdfName)?.value
                val fontSize = num(a, 1)
                val resolved = fonts[fontName]
                state.mutateText { it.copy(font = resolved, fontSize = fontSize) }
            }
            "Tc" -> state.mutateText { it.copy(charSpacing = num(a, 0)) }
            "Tw" -> state.mutateText { it.copy(wordSpacing = num(a, 0)) }
            "Tz" -> state.mutateText { it.copy(horizontalScaling = num(a, 0)) }
            "TL" -> state.mutateText { it.copy(leading = num(a, 0)) }
            "Ts" -> state.mutateText { it.copy(rise = num(a, 0)) }
            "Tr" -> state.mutateText { it.copy(renderingMode = num(a, 0).toInt()) }

            // ─── Text positioning ────────────────────────────────────────
            "Td" -> moveText(state, num(a, 0), num(a, 1), setLeading = false)
            "TD" -> moveText(state, num(a, 0), num(a, 1), setLeading = true)
            "Tm" -> state.mutateText {
                val m = Matrix(num(a, 0), num(a, 1), num(a, 2), num(a, 3), num(a, 4), num(a, 5))
                it.copy(textMatrix = m, lineMatrix = m)
            }
            "T*" -> moveText(state, 0.0, -state.current.text.leading, setLeading = false)

            // ─── Text showing ────────────────────────────────────────────
            "Tj" -> (a.firstOrNull() as? PdfString)?.let { showText(state, it.bytes) }
            "'" -> {
                moveText(state, 0.0, -state.current.text.leading, setLeading = false)
                (a.firstOrNull() as? PdfString)?.let { showText(state, it.bytes) }
            }
            "\"" -> {
                state.mutateText { it.copy(wordSpacing = num(a, 0), charSpacing = num(a, 1)) }
                moveText(state, 0.0, -state.current.text.leading, setLeading = false)
                (a.lastOrNull() as? PdfString)?.let { showText(state, it.bytes) }
            }
            "TJ" -> (a.firstOrNull() as? PdfArray)?.let { arr ->
                for (item in arr) when (item) {
                    is PdfString -> showText(state, item.bytes)
                    is PdfReal -> adjustTextX(state, -item.value)
                    is PdfInt -> adjustTextX(state, -item.value.toDouble())
                    else -> { /* ignore */ }
                }
            }
            // ─── Extended graphics state (`gs <name>`) ───────────────────
            "gs" -> {
                val name = (a.firstOrNull() as? PdfName)?.value ?: return
                val ext = extGStates[name] ?: return
                state.replace(state.current.applyExtGState(ext))
            }

            // ─── XObject (Image / Form) ──────────────────────────────────
            "Do" -> {
                val name = (a.firstOrNull() as? PdfName)?.value ?: return
                val slot = xobjects[name] ?: run {
                    kiteWarn { "render: XObject $name missing from /Resources" }
                    return
                }
                // Skip when inside a hidden OC section or the XObject's own /OC is off.
                if (ocHidden() || isXObjectOcHidden(slot.stream)) return
                when (slot.stream.dict.getName("Subtype")) {
                    "Image" -> withSoftMask(state.current) {
                        canvas.drawImage(
                            decodeImageCached(slot, state.current.fillColor),
                            state.current.ctm, state.current.fillAlpha,
                        )
                    }
                    "Form" -> renderFormXObject(slot.stream, state, slot.objectNumber)
                }
            }

            // ─── Shading fill (`sh <name>`) ──────────────────────────────
            "sh" -> {
                if (ocHidden()) return
                val name = (a.firstOrNull() as? PdfName)?.value ?: return
                val shading = shadings[name] ?: return
                val s = state.current
                canvas.fillShading(
                    shading, s.ctm, clipPath = null,
                    alpha = s.fillAlpha, blendMode = s.blendMode,
                )
            }

            // ─── Inline image (BI … ID … EI) ─────────────────────────────
            "BI" -> {
                if (ocHidden()) return
                val blob = op.inlineImage ?: return
                val img = decodeInlineImage(blob, state.current.fillColor) ?: return
                withSoftMask(state.current) {
                    canvas.drawImage(img, state.current.ctm, state.current.fillAlpha)
                }
            }

            // ─── Marked content (optional-content visibility) ────────────
            "BDC" -> {
                val tag = a.getOrNull(0) as? PdfName
                val hidden = tag?.value == "OC" && isOcOperandHidden(a.getOrNull(1), properties)
                markedContentStack.addLast(hidden)
                if (hidden) ocHiddenDepth++
            }
            "BMC" -> markedContentStack.addLast(false)
            "EMC" -> {
                val wasHidden = markedContentStack.removeLastOrNull() ?: false
                if (wasHidden && ocHiddenDepth > 0) ocHiddenDepth--
            }

            // ─── Colour-space-aware fill/stroke ──────────────────────────
            // SCN/scn with a Pattern colour space pushes a pattern name as
            // the last operand. We sniff that here and stash a Shading
            // pattern as the current fill source; non-pattern operands fall
            // back to the existing rgb/cmyk/gray paths.
            "scn" -> handleScnFill(a, patterns, state, stroke = false)
            "SCN" -> handleScnFill(a, patterns, state, stroke = true)
            "sc" -> handleScFill(a, state, stroke = false)
            "SC" -> handleScFill(a, state, stroke = true)

            // Other operators (marked-content BDC/BMC/EMC, etc.)
            // are silently skipped — see ROADMAP.
            else -> { /* unknown */ }
        }
    }

    /**
     * Apply a clip marked by a preceding W/W* now that the path-painting
     * operator has run (§8.5.4). The clip uses the current path at the CTM in
     * effect and intersects the existing clip. Cleared afterwards.
     */
    private fun applyPendingClip(path: KitePath.Builder, state: GraphicsStack) {
        if (pendingClip == 0) return
        val evenOdd = pendingClip == 2
        pendingClip = 0
        if (path.isEmpty()) return
        canvas.pushClip(path.build(), state.current.ctm, evenOdd)
        activeClipCount++
    }

    private fun paintFill(path: KitePath.Builder, state: GraphicsStack, evenOdd: Boolean) {
        if (path.isEmpty()) return
        val s = state.current
        val built = path.build()
        withSoftMask(s) {
            val pat = s.fillPattern
            when {
                // The pattern /Matrix maps pattern space to the page's DEFAULT
                // coordinate system, not the current user space (§8.7.3.1). Use
                // pageBaseCtm — matching the tiling path below — instead of s.ctm.
                pat is KitePattern.Shading -> canvas.fillShading(
                    pat.shading, pageBaseCtm.concat(pat.matrix), clipPath = built,
                    alpha = s.fillAlpha, blendMode = s.blendMode,
                )
                pat is KitePattern.Tiling -> renderTilingPattern(pat, built, s, evenOdd)
                pat != null -> {
                    // Unsupported pattern — skip rather than paint the default
                    // colour, which would flood e.g. a full-page background black.
                }
                else -> canvas.fillPath(
                    built, s.ctm, s.fillColor, evenOdd,
                    alpha = s.fillAlpha, blendMode = s.blendMode,
                )
            }
        }
    }

    private fun paintStroke(path: KitePath.Builder, state: GraphicsStack) {
        if (path.isEmpty()) return
        val s = state.current
        val built = path.build()
        withSoftMask(s) {
            val pat = s.strokePattern
            when {
                // A pattern stroke paints the pattern clipped to the stroked
                // region. We approximate the stroke region by its outline path
                // and fill the pattern into it (mirror of the fill-pattern path).
                // The pattern /Matrix is relative to the page default CTM.
                pat is KitePattern.Shading -> canvas.fillShading(
                    pat.shading, pageBaseCtm.concat(pat.matrix), clipPath = built,
                    alpha = s.strokeAlpha, blendMode = s.blendMode,
                )
                pat is KitePattern.Tiling -> renderTilingPattern(pat, built, s, evenOdd = false)
                pat != null -> {
                    // Unsupported pattern — skip rather than paint a stale colour.
                }
                else -> canvas.strokePath(
                    built, s.ctm, s.strokeColor, s.lineWidth,
                    alpha = s.strokeAlpha, blendMode = s.blendMode,
                    dashArray = s.dashArray, dashPhase = s.dashPhase,
                    lineCap = s.lineCap, lineJoin = s.lineJoin, miterLimit = s.miterLimit,
                )
            }
        }
    }

    /**
     * Fill [clipPath] with a tiling pattern (ISO 32000-1 §8.7.3): clip to the
     * region, then replay the pattern cell's content stream at every
     * `/XStep`,`/YStep` offset that intersects the region. The pattern matrix is
     * relative to the page's default coordinate system. Uncolored patterns
     * (PaintType 2) are painted in the current fill colour.
     */
    private fun renderTilingPattern(
        pat: KitePattern.Tiling, clipPath: KitePath, s: GraphicsState, evenOdd: Boolean,
    ) {
        val xs = pat.xStep
        val ys = pat.yStep
        if (xs == 0.0 || ys == 0.0) return
        val patternCtm = pageBaseCtm.concat(pat.matrix)
        val toPattern = patternCtm.invert() ?: return
        val dev = deviceBounds(clipPath, s.ctm) ?: return

        // Map the region's device-space corners into pattern space to find the
        // range of tile indices that can intersect it.
        val corners = listOf(
            toPattern.transformPoint(dev[0], dev[1]), toPattern.transformPoint(dev[2], dev[1]),
            toPattern.transformPoint(dev[0], dev[3]), toPattern.transformPoint(dev[2], dev[3]),
        )
        val pMinX = corners.minOf { it.first }; val pMaxX = corners.maxOf { it.first }
        val pMinY = corners.minOf { it.second }; val pMaxY = corners.maxOf { it.second }
        val axs = kotlin.math.abs(xs); val ays = kotlin.math.abs(ys)
        val i0 = kotlin.math.floor((pMinX - pat.bbox.right) / axs).toInt()
        val i1 = kotlin.math.ceil((pMaxX - pat.bbox.left) / axs).toInt()
        val j0 = kotlin.math.floor((pMinY - pat.bbox.top) / ays).toInt()
        val j1 = kotlin.math.ceil((pMaxY - pat.bbox.bottom) / ays).toInt()
        val tiles = (i1 - i0 + 1).toLong() * (j1 - j0 + 1).toLong()
        if (tiles <= 0 || tiles > MAX_TILES) return

        val res = pat.resources
        val fonts = loadFonts(res)
        val xobjects = loadXObjects(res)
        val colorSpaces = loadColorSpaces(res)
        val extGStates = loadExtGStates(res)
        val shadings = loadShadings(res)
        val patterns = loadPatterns(res, shadings)
        val properties = loadProperties(res)
        val ops = ContentStreamParser.parse(pat.contentBytes)
        val uncolored = pat.paintType == 2

        canvas.pushClip(clipPath, s.ctm, evenOdd)
        val clipBase = activeClipCount
        // A pending W/W* belongs to the enclosing stream, not the tile cell.
        val savedPendingClip = pendingClip
        pendingClip = 0
        try {
            for (j in j0..j1) for (i in i0..i1) {
                val tileCtm = patternCtm.concat(Matrix.translation(i * xs, j * ys))
                val tileState = GraphicsStack(
                    if (uncolored) GraphicsState(ctm = tileCtm, fillColor = s.fillColor, strokeColor = s.fillColor)
                    else GraphicsState(ctm = tileCtm),
                )
                val tilePath = KitePath.Builder()
                for (op in ops) dispatch(op, tileState, tilePath, fonts, xobjects, colorSpaces, extGStates, shadings, patterns, properties)
                // Drop any clips the tile's content left unbalanced.
                while (activeClipCount > clipBase) { canvas.popClip(); activeClipCount-- }
                pendingClip = 0
            }
        } finally {
            pendingClip = savedPendingClip
            canvas.popClip()
        }
    }

    /** Axis-aligned device-space bounds of [path] under [ctm], or null if empty. */
    private fun deviceBounds(path: KitePath, ctm: Matrix): DoubleArray? {
        var minX = Double.POSITIVE_INFINITY; var minY = Double.POSITIVE_INFINITY
        var maxX = Double.NEGATIVE_INFINITY; var maxY = Double.NEGATIVE_INFINITY
        var any = false
        fun acc(x: Double, y: Double) {
            val (dx, dy) = ctm.transformPoint(x, y)
            if (dx < minX) minX = dx; if (dx > maxX) maxX = dx
            if (dy < minY) minY = dy; if (dy > maxY) maxY = dy
            any = true
        }
        for (seg in path.segments) when (seg) {
            is KitePath.Segment.MoveTo -> acc(seg.x, seg.y)
            is KitePath.Segment.LineTo -> acc(seg.x, seg.y)
            is KitePath.Segment.CurveTo -> { acc(seg.x1, seg.y1); acc(seg.x2, seg.y2); acc(seg.x3, seg.y3) }
            is KitePath.Segment.QuadTo -> { acc(seg.x1, seg.y1); acc(seg.x2, seg.y2) }
            KitePath.Segment.Close -> {}
        }
        return if (any) doubleArrayOf(minX, minY, maxX, maxY) else null
    }

    /**
     * Wrap [paint] in a soft-mask layer when the current graphics state has
     * one active. Without an SMask the lambda is invoked directly. With an
     * SMask, the canvas's [KiteCanvas.applySoftMask] gets two callbacks:
     * one that draws the content, one that re-renders the mask group into
     * a separate layer, blended via `DstIn`.
     */
    private fun withSoftMask(state: GraphicsState, paint: () -> Unit) {
        val mask = state.softMask as? SoftMask.MaskGroup
        if (mask == null) {
            paint(); return
        }
        // The mask group has its own /BBox + /Matrix the renderer should
        // honour. We pass them along so the backend's saveLayer can size
        // the offscreen correctly.
        val maskBBox = mask.group.dict.getArray("BBox")?.let { arr ->
            io.github.yuroyami.kitepdf.core.Rectangle(
                arr.getOrNull(0).toDouble(), arr.getOrNull(1).toDouble(),
                arr.getOrNull(2).toDouble(), arr.getOrNull(3).toDouble(),
            )
        } ?: io.github.yuroyami.kitepdf.core.Rectangle(0.0, 0.0, 0.0, 0.0)
        val maskMatrix = mask.group.dict.getArray("Matrix")?.let { arr ->
            Matrix(
                arr.getOrNull(0).toDouble(), arr.getOrNull(1).toDouble(),
                arr.getOrNull(2).toDouble(), arr.getOrNull(3).toDouble(),
                arr.getOrNull(4).toDouble(), arr.getOrNull(5).toDouble(),
            )
        } ?: Matrix.IDENTITY
        canvas.applySoftMask(
            kind = mask.kind,
            maskBBox = maskBBox,
            maskCtm = state.ctm.concat(maskMatrix),
            render = paint,
            renderMask = { childCanvas ->
                // Recurse into the same renderer pipeline but onto whatever
                // canvas the backend handed us. The mask group's content
                // stream is rendered with a fresh graphics state — the spec
                // says soft masks render onto a transparent backdrop with
                // their own state stack (§11.6.5).
                renderMaskGroup(mask.group, childCanvas, state.ctm)
            },
        )
    }

    private fun renderMaskGroup(formStream: PdfStream, target: KiteCanvas, baseCtm: Matrix) {
        // We need a sub-renderer so the mask paints into [target] rather
        // than the page canvas. The cleanest thing is to construct a
        // throwaway PageRenderer instance and let it run the form-xobject
        // pipeline; that reuses every operator handler and resource walk.
        // The state starts from the CALLER's CTM (device transform included —
        // starting from IDENTITY rendered the mask unflipped and unscaled,
        // which a symmetric 72-dpi fixture hid and the 96-dpi harness caught);
        // the group's own /Matrix is applied by the form pipeline itself, so
        // pre-concatenating it here would double it.
        val sub = PageRenderer(target, resolver)
        val parentState = GraphicsStack(GraphicsState(ctm = baseCtm))
        sub.renderFormXObjectExternally(formStream, parentState)
    }

    /**
     * Public entry to render a form xobject onto this renderer's canvas
     * from an outer caller (the soft-mask path). Body delegates to the
     * existing [renderFormXObject] private path so we don't duplicate
     * resource loading + group setup.
     */
    internal fun renderFormXObjectExternally(formStream: PdfStream, state: GraphicsStack) {
        renderFormXObject(formStream, state)
    }

    /**
     * Handle `scn` (fill) / `SCN` (stroke). When the corresponding colour
     * space is `/Pattern`, the last operand is a `/PatternName`; we look
     * it up and stash it on the graphics state. For non-pattern colour
     * spaces we promote the components to RGB via the active colour space.
     */
    private fun handleScnFill(
        a: List<io.github.yuroyami.kitepdf.core.parser.PdfObject>,
        patterns: Map<String, KitePattern>,
        state: GraphicsStack,
        stroke: Boolean,
    ) {
        // Pattern name is always the last operand for Pattern colour-space.
        val nameOp = a.lastOrNull() as? PdfName
        if (nameOp != null) {
            // Use the parsed pattern when we have it; otherwise mark it
            // Unsupported so the fill is skipped rather than collapsing to the
            // default (black) colour and flooding the region.
            val pat = patterns[nameOp.value] ?: KitePattern.Unsupported
            state.replace(
                if (stroke) state.current.copy(strokePattern = pat)
                else state.current.copy(fillPattern = pat),
            )
            return
        }
        // Numeric operands: dispatch through the active colour space.
        val cs = if (stroke) state.current.strokeColorSpace else state.current.fillColorSpace
        val comps = DoubleArray(a.size) { i ->
            when (val v = a[i]) {
                is io.github.yuroyami.kitepdf.core.parser.PdfInt -> v.value.toDouble()
                is io.github.yuroyami.kitepdf.core.parser.PdfReal -> v.value
                else -> 0.0
            }
        }
        val rgb = cs.toRgb(comps)
        state.replace(
            if (stroke) state.current.copy(strokeColor = rgb, strokePattern = null)
            else state.current.copy(fillColor = rgb, fillPattern = null),
        )
    }

    private fun handleScFill(
        a: List<io.github.yuroyami.kitepdf.core.parser.PdfObject>,
        state: GraphicsStack,
        stroke: Boolean,
    ) {
        // `sc` / `SC` only carry numeric components (no Pattern colour-space).
        val cs = if (stroke) state.current.strokeColorSpace else state.current.fillColorSpace
        val comps = DoubleArray(a.size) { i ->
            when (val v = a[i]) {
                is io.github.yuroyami.kitepdf.core.parser.PdfInt -> v.value.toDouble()
                is io.github.yuroyami.kitepdf.core.parser.PdfReal -> v.value
                else -> 0.0
            }
        }
        val rgb = cs.toRgb(comps)
        state.replace(
            if (stroke) state.current.copy(strokeColor = rgb, strokePattern = null)
            else state.current.copy(fillColor = rgb, fillPattern = null),
        )
    }

    /* ─── Text state machine ─────────────────────────────────────────────── */

    private fun moveText(state: GraphicsStack, tx: Double, ty: Double, setLeading: Boolean) {
        state.mutateText { t ->
            // ISO 32000-1 §9.4.2: Tlm_new = translate(tx,ty) × Tlm (row-vector form),
            // i.e. the offset is in UNSCALED TEXT SPACE — apply the translation first,
            // then the line matrix, so its scale/rotation transform the offset. (concat
            // applies its argument first, so this is lineMatrix.concat(translation).)
            // The reverse order silently works only when Tm has unit scale; with the
            // font size baked into Tm (Tf size 1, scale in Tm) it collapsed line spacing.
            val moved = t.lineMatrix.concat(Matrix.translation(tx, ty))
            t.copy(
                lineMatrix = moved,
                textMatrix = moved,
                leading = if (setLeading) -ty else t.leading,
            )
        }
    }

    /** Show one byte string, calling the canvas per text run and advancing Tm. */
    private fun showText(state: GraphicsStack, bytes: ByteArray) {
        val t = state.current.text
        if (bytes.isEmpty()) return
        val hScale = t.horizontalScaling / 100.0

        val font = t.font
        if (font == null) {
            // Tf named an absent font, so we have no metrics or glyphs. Rather
            // than return WITHOUT advancing Tm (which collapses every following
            // run onto this position), advance by an estimated width of ~0.5em
            // per byte so subsequent text does not overlap. Nothing is painted.
            val estimated = bytes.size * 0.5 * t.fontSize * hScale
            state.mutateText {
                it.copy(textMatrix = it.textMatrix.concat(Matrix.translation(estimated, 0.0)))
            }
            return
        }

        // Combined text-space-to-user-space matrix:
        //   text matrix × CTM, with font size + horizontal scale already
        //   baked in to the per-glyph advance.
        val pageMatrix = state.current.ctm
        val textMatrix = t.textMatrix
        // text-space → device = text matrix THEN current CTM. Since
        // concat(other) applies `other` first, that's pageMatrix.concat(textMatrix)
        // (NOT the reverse, which would apply the device matrix first and fling
        // the text off-page). The text-space pre-transform bakes in the horizontal
        // scaling (Tz) on x and the rise (Ts) on y, so condensed/expanded type
        // renders at the right glyph proportions, not just the right spacing.
        // text-space → user-space (Tm + Tz + Ts, without the CTM). Stroking uses
        // this + s.ctm separately so the stroke width scales by the CTM only, as
        // the spec prescribes; drawText takes the fully-combined finalMatrix.
        val textToUser = textMatrix.concat(Matrix(hScale, 0.0, 0.0, 1.0, 0.0, t.rise))
        val finalMatrix = pageMatrix.concat(textToUser)

        // Type3 fonts draw by replaying char-proc content streams (T-42).
        type3Data[font]?.let { data ->
            showTextType3(state, bytes, t, data, textToUser)
            return
        }

        // Text render mode (Tr, §9.3.3): 3 = invisible; 7 = clip only (no paint).
        //   fill component:   modes 0,2,4,6
        //   stroke component: modes 1,2,5,6
        //   clip component:   modes 4,5,6,7  (accumulate glyph outlines to clip)
        val mode = t.renderingMode
        val doFill = mode == 0 || mode == 2 || mode == 4 || mode == 6
        val doStroke = mode == 1 || mode == 2 || mode == 5 || mode == 6
        // Modes 4..7 accumulate the glyph outlines into a clip applied at ET
        // (§9.3.3, T-41). Mode 7 clips without painting.
        val doClip = mode >= 4
        // ONE glyph layout per run (T-13): fill, stroke, clip and the Tm
        // advance all read the same list. Outlines are resolved only when
        // something below actually consumes them.
        val hidden = ocHidden()
        val resolveOutlines = !hidden &&
            ((doFill && canvas.resolvesGlyphOutlines) ||
                ((doStroke || doClip) && font.hasEmbeddedOutlines))
        val glyphs = font.layoutBytes(bytes, resolveOutlines)

        if (!hidden) {
            if (doClip) accumulateTextClip(glyphs, font, t, textToUser)
            if (doFill) {
                withSoftMask(state.current) {
                    canvas.drawGlyphs(
                        glyphs, t.fontSize, font.unitsPerEm ?: 1000,
                        font.hasEmbeddedOutlines, font.fontSpec, finalMatrix,
                        state.current.fillColor,
                        alpha = state.current.fillAlpha, blendMode = state.current.blendMode,
                    )
                }
            }
            if (doStroke) {
                strokeTextGlyphs(state, font, t, glyphs, textToUser)
            }
        }

        // Advance Tm by the total width of this run. The advance is in text space,
        // so translate first then apply the text matrix (see moveText) — otherwise a
        // size-in-Tm run advances in output space and the next run on the line overlaps.
        val totalAdvance = totalAdvance(glyphs, t)
        state.mutateText {
            it.copy(textMatrix = it.textMatrix.concat(Matrix.translation(totalAdvance, 0.0)))
        }
    }

    /**
     * Stroke the glyph outlines for the current run (render modes 1/2/5/6).
     * Builds each glyph's outline into user space (glyph units → unitScale →
     * pen advance → [textToUser]) and strokes it under s.ctm with the current
     * stroke colour/width, so modes 1/2 actually stroke rather than falling back
     * to a plain fill. Fonts without embedded outlines contribute nothing here.
     */
    private fun strokeTextGlyphs(
        state: GraphicsStack,
        font: PdfFont,
        t: TextState,
        /** The run's glyphs, laid out ONCE by [showText] (outlines resolved). */
        glyphs: List<TextGlyph>,
        textToUser: Matrix,
    ) {
        if (!font.hasEmbeddedOutlines) return
        val upm = font.unitsPerEm ?: 1000
        val unitScale = t.fontSize / upm
        val advanceScale = t.fontSize / 1000.0
        val s = state.current
        var penX = 0.0
        // Build each glyph outline into USER space (glyph units → unitScale →
        // pen advance → text-to-user), then stroke it with s.ctm so the stroke
        // width scales by the CTM only — the pure user-space width the spec wants.
        for (glyph in glyphs) {
            val outline = glyph.outline
            if (outline != null && !outline.isEmpty()) {
                val glyphMatrix = textToUser
                    .concat(Matrix.translation(penX, 0.0))
                    .concat(Matrix(unitScale, 0.0, 0.0, unitScale, 0.0, 0.0))
                val userPath = transformPath(outline, glyphMatrix)
                withSoftMask(s) {
                    canvas.strokePath(
                        userPath, s.ctm, s.strokeColor, s.lineWidth,
                        alpha = s.strokeAlpha, blendMode = s.blendMode,
                        dashArray = s.dashArray, dashPhase = s.dashPhase,
                        lineCap = s.lineCap, lineJoin = s.lineJoin, miterLimit = s.miterLimit,
                    )
                }
            }
            penX += glyph.advanceWidth * advanceScale
        }
    }

    /**
     * Modes 4..7: add this run's glyph shapes to [pendingTextClip] in USER
     * space (same math as [strokeTextGlyphs]). Glyphs without outlines (the
     * system-font fallback) contribute their advance x em box instead — an
     * approximation, but a non-empty clip beats silently clipping everything
     * away. The CTM applies when ET pushes the accumulated path.
     */
    private fun accumulateTextClip(
        glyphs: List<TextGlyph>,
        font: PdfFont,
        t: TextState,
        textToUser: Matrix,
    ) {
        val builder = pendingTextClip ?: KitePath.Builder().also { pendingTextClip = it }
        val upm = font.unitsPerEm ?: 1000
        val unitScale = t.fontSize / upm
        val advanceScale = t.fontSize / 1000.0
        var penX = 0.0
        for (glyph in glyphs) {
            val penMatrix = textToUser.concat(Matrix.translation(penX, 0.0))
            val outline = glyph.outline
            if (outline != null && !outline.isEmpty()) {
                appendPath(
                    builder,
                    transformPath(outline, penMatrix.concat(Matrix(unitScale, 0.0, 0.0, unitScale, 0.0, 0.0))),
                )
            } else if (glyph.advanceWidth > 0.0) {
                val w = glyph.advanceWidth * advanceScale
                val box = KitePath.Builder().apply {
                    rectangle(0.0, -0.2 * t.fontSize, w, t.fontSize)
                }.build()
                appendPath(builder, transformPath(box, penMatrix))
            }
            penX += glyph.advanceWidth * advanceScale
        }
    }

    /** Append every segment of [path] to [b] (subpaths stay separate). */
    private fun appendPath(b: KitePath.Builder, path: KitePath) {
        for (seg in path.segments) when (seg) {
            is KitePath.Segment.MoveTo -> b.moveTo(seg.x, seg.y)
            is KitePath.Segment.LineTo -> b.lineTo(seg.x, seg.y)
            is KitePath.Segment.CurveTo -> b.curveTo(seg.x1, seg.y1, seg.x2, seg.y2, seg.x3, seg.y3)
            is KitePath.Segment.QuadTo -> b.quadTo(seg.x1, seg.y1, seg.x2, seg.y2)
            KitePath.Segment.Close -> b.close()
        }
    }

    /** Apply [m] to every coordinate of [path], returning a new path. */
    private fun transformPath(path: KitePath, m: Matrix): KitePath {
        val b = KitePath.Builder()
        fun p(x: Double, y: Double): Pair<Double, Double> = m.transformPoint(x, y)
        for (seg in path.segments) when (seg) {
            is KitePath.Segment.MoveTo -> { val (x, y) = p(seg.x, seg.y); b.moveTo(x, y) }
            is KitePath.Segment.LineTo -> { val (x, y) = p(seg.x, seg.y); b.lineTo(x, y) }
            is KitePath.Segment.CurveTo -> {
                val (x1, y1) = p(seg.x1, seg.y1); val (x2, y2) = p(seg.x2, seg.y2); val (x3, y3) = p(seg.x3, seg.y3)
                b.curveTo(x1, y1, x2, y2, x3, y3)
            }
            is KitePath.Segment.QuadTo -> {
                val (x1, y1) = p(seg.x1, seg.y1); val (x2, y2) = p(seg.x2, seg.y2)
                b.quadTo(x1, y1, x2, y2)
            }
            KitePath.Segment.Close -> b.close()
        }
        return b.build()
    }

    /** TJ numeric adjustment: shift the text-cursor by [thousandthsOfEm] of em. */
    private fun adjustTextX(state: GraphicsStack, thousandthsOfEm: Double) {
        val t = state.current.text
        val tx = thousandthsOfEm / 1000.0 * t.fontSize * (t.horizontalScaling / 100.0)
        state.mutateText {
            // Text-space offset: translate first, then the text matrix (see moveText).
            it.copy(textMatrix = it.textMatrix.concat(Matrix.translation(tx, 0.0)))
        }
    }

    /**
     * Sum of per-glyph advances, including Tc/Tw/Th adjustments, from the run's
     * already-laid-out [glyphs] (composite Type 0 fonts contributed one glyph
     * per CID code unit there, and [TextGlyph.isWordSpace] carries the
     * single-byte-32 rule, so this matches `forEachGlyphAdvance` exactly).
     */
    private fun totalAdvance(glyphs: List<TextGlyph>, t: TextState): Double {
        var advance = 0.0
        val sizeFactor = t.fontSize / 1000.0
        val hScale = t.horizontalScaling / 100.0
        for (g in glyphs) {
            val perGlyph = g.advanceWidth * sizeFactor +
                t.charSpacing +
                (if (g.isWordSpace) t.wordSpacing else 0.0)
            advance += perGlyph * hScale
        }
        return advance
    }

    /**
     * T-42: show a text run in a Type3 font. Each byte's glyph is a content
     * stream replayed like a small form XObject under
     * `CTM x textToUser x pen x fontSize x FontMatrix`, with the font's own
     * /Resources (absent /Resources fall back to EMPTY maps — the spec's
     * page-resource fallback is a rarely-exercised corner, noted in the
     * ledger). The pen advances by `width x FontMatrix.a x fontSize` plus
     * Tc/Tw, matching §9.6.5's glyph-space widths.
     */
    private fun showTextType3(
        state: GraphicsStack,
        bytes: ByteArray,
        t: TextState,
        data: Type3Data,
        textToUser: Matrix,
    ) {
        val hidden = ocHidden()
        var penX = 0.0
        for (b in bytes) {
            val code = b.toInt() and 0xFF
            val proc = data.nameForCode[code]?.let { data.charProcs[it] }
            if (!hidden && proc != null && formDepth < MAX_FORM_DEPTH) {
                formDepth++
                try {
                    val glyphToUser = textToUser
                        .concat(Matrix.translation(penX, 0.0))
                        .concat(Matrix.scaling(t.fontSize, t.fontSize))
                        .concat(data.fontMatrix)
                    replayType3Proc(proc, data, state, glyphToUser)
                } finally {
                    formDepth--
                }
            }
            var adv = data.widthFor(code) * data.fontMatrix.a * t.fontSize + t.charSpacing
            if (code == 0x20) adv += t.wordSpacing
            penX += adv
        }
        val hScale = t.horizontalScaling / 100.0
        state.mutateText {
            it.copy(textMatrix = it.textMatrix.concat(Matrix.translation(penX * hScale, 0.0)))
        }
    }

    private fun replayType3Proc(
        proc: PdfStream,
        data: Type3Data,
        parentState: GraphicsStack,
        glyphToUser: Matrix,
    ) {
        val res = data.resources
        val sh = loadShadings(res)
        val fonts = loadFonts(res)
        val xobjects = loadXObjects(res)
        val colorSpaces = loadColorSpaces(res)
        val extGStates = loadExtGStates(res)
        val patterns = loadPatterns(res, sh)
        val properties = loadProperties(res)

        parentState.save()
        parentState.replace(parentState.current.copy(
            ctm = parentState.current.ctm.concat(glyphToUser),
        ))
        val savedPendingClip = pendingClip
        pendingClip = 0
        val savedIgnore = type3IgnoreColor
        type3IgnoreColor = false // each proc decides via its own d1
        val clipBase = activeClipCount
        try {
            val ops = ContentStreamParser.parse(
                io.github.yuroyami.kitepdf.core.filters.FilterChain.decode(proc),
            )
            val pathBuilder = KitePath.Builder()
            for (op in ops) dispatch(op, parentState, pathBuilder, fonts, xobjects, colorSpaces, extGStates, sh, patterns, properties)
        } finally {
            type3IgnoreColor = savedIgnore
            pendingClip = savedPendingClip
            while (activeClipCount > clipBase) { canvas.popClip(); activeClipCount-- }
            parentState.restore()
        }
    }

    /* ─── Helpers ────────────────────────────────────────────────────────── */

    private fun num(list: List<PdfObject>, idx: Int): Double = when (val v = list.getOrNull(idx)) {
        is PdfInt -> v.value.toDouble()
        is PdfReal -> v.value
        else -> 0.0
    }

    /** Look up a /ColorSpace name from a Resources entry; fall back to device families. */
    private fun namedColorSpace(name: String, dict: Map<String, ColorSpace>): ColorSpace =
        when (name) {
            "DeviceGray", "G" -> ColorSpace.DeviceGray
            "DeviceRGB", "RGB" -> ColorSpace.DeviceRGB
            "DeviceCMYK", "CMYK" -> ColorSpace.DeviceCMYK
            else -> dict[name] ?: ColorSpace.DeviceGray
        }

    private companion object {
        /** Safety cap on tiling-pattern tile count to bound adversarial inputs. */
        const val MAX_TILES = 20_000L
        /** Max Form-XObject nesting depth before bailing (recursion guard). */
        const val MAX_FORM_DEPTH = 15

        /** Colour operators ignored inside a d1 (uncolored) Type3 glyph (T-42). */
        val TYPE3_COLOR_OPS = setOf("g", "G", "rg", "RG", "k", "K", "cs", "CS", "sc", "SC", "scn", "SCN")
        /**
         * Per-page dispatched-operation budget, counting tiling-pattern and
         * form-XObject replays. A crafted stream can stay under [MAX_TILES]
         * and [MAX_FORM_DEPTH] yet still multiply a few million parsed ops
         * into an effectively unbounded amount of work; this bounds the total.
         */
        const val MAX_DISPATCHED_OPS = 20_000_000L
    }
}
