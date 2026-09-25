package io.github.yuroyami.kitepdf.render

import io.github.yuroyami.kitepdf.core.render.applyExtGState

import io.github.yuroyami.kitepdf.core.render.KiteBlendMode
import io.github.yuroyami.kitepdf.core.render.KiteColorSpace
import io.github.yuroyami.kitepdf.core.render.ExtGState
import io.github.yuroyami.kitepdf.core.render.GraphicsStack
import io.github.yuroyami.kitepdf.core.render.GraphicsState
import io.github.yuroyami.kitepdf.core.render.KiteImageData
import io.github.yuroyami.kitepdf.core.render.KiteCanvas
import io.github.yuroyami.kitepdf.core.render.KitePath
import io.github.yuroyami.kitepdf.core.render.KitePattern
import io.github.yuroyami.kitepdf.core.render.KiteShading
import io.github.yuroyami.kitepdf.core.render.KiteMatrix
import io.github.yuroyami.kitepdf.core.render.KiteFunction
import io.github.yuroyami.kitepdf.core.render.KiteMaskTransfer
import io.github.yuroyami.kitepdf.core.render.KiteRenderingIntent
import io.github.yuroyami.kitepdf.core.render.RgbColor
import io.github.yuroyami.kitepdf.core.render.SoftMask
import io.github.yuroyami.kitepdf.core.render.TextState
import io.github.yuroyami.kitepdf.core.render.strokeOutline
import io.github.yuroyami.kitepdf.core.render.withColorRendering

import io.github.yuroyami.kitepdf.core.kiteWarn
import io.github.yuroyami.kitepdf.PdfAnnotation.Subtype
import io.github.yuroyami.kitepdf.PdfPage
import io.github.yuroyami.kitepdf.missingAsNull
import io.github.yuroyami.kitepdf.content.ContentStreamParser
import io.github.yuroyami.kitepdf.content.Operation
import io.github.yuroyami.kitepdf.core.font.PdfFont
import io.github.yuroyami.kitepdf.core.font.PdfVerticalMetrics
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
 * The content-stream interpreter. It translates parsed [Operation]s into
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
    /**
     * The document's live form values, when a reader is filling the form. A widget whose field
     * has a value here is drawn from that value, not from the appearance the file stores, and a
     * field this state hides is not drawn at all. Null renders the file as it arrived.
     */
    private val formState: io.github.yuroyami.kitepdf.PdfFormState? = null,
) {

    /** [PageRenderer] whose render stops between operators once [cancellation] reads true (#188). */
    public constructor(
        canvas: KiteCanvas,
        resolver: IndirectResolver,
        formState: io.github.yuroyami.kitepdf.PdfFormState?,
        cancellation: io.github.yuroyami.kitepdf.core.KiteCancellation?,
    ) : this(canvas, resolver, formState) {
        this.cancellation = cancellation
    }

    /** Stops the render between operators once it reads true (#188). */
    private var cancellation: io.github.yuroyami.kitepdf.core.KiteCancellation? = null


    /** The document's cache of parsed content, when [resolver] is a whole document (#118). */
    private val document: io.github.yuroyami.kitepdf.PdfDocument? = resolver as? io.github.yuroyami.kitepdf.PdfDocument

    /** The operations of the content that object [objectNumber] holds, from the document's cache when there is one. */
    private fun cachedOperations(objectNumber: Long?, parse: () -> List<Operation>): List<Operation> =
        objectNumber?.let { number -> document?.operations(number, parse) } ?: parse()

    // W/W* push a clip on the canvas, but the canvas keeps its own clip stack
    // separate from the PDF q/Q graphics-state stack. Track how many clips are
    // active so Q can pop exactly the ones pushed since its matching q,
    // otherwise clips leak past Q and can wrongly clip the rest of the page.
    private var activeClipCount = 0
    private val clipSaveStack = ArrayDeque<Int>()

    // W/W* mark the current path as a *pending* clip; per §8.5.4 the clip only
    // takes effect AFTER the next path-painting operator (S/f/B/n/…) has painted.
    // 0 = none, 1 = W (nonzero), 2 = W* (even-odd). Applied and cleared by the
    // painting op via [applyPendingClip].
    private var pendingClip = 0

    // Type3 fonts: parsed char-proc data per font instance, plus the
    // d1-uncolored flag. While true (inside a d1 glyph proc) every colour
    // operator is a spec no-op so the glyph paints with the caller's fill
    // colour (§9.6.5).
    private val type3Data = HashMap<PdfFont, Type3Data?>()
    /** Colour operators are ignored: in a d1 Type 3 glyph (9.6.5) and in an uncoloured tiling cell (8.7.3.3). */
    private var type3IgnoreColor = false

    // Text render modes 4..7: glyph outlines accumulate here in USER
    // space across the whole BT..ET block; ET intersects the union with the
    // clip (§9.3.3: the text clip applies after the text object ends and
    // persists to the enclosing Q, which activeClipCount already models).
    private var pendingTextClip: KitePath.Builder? = null

    /** How many Type 3 char procs are running, so a stray `d1` in page content clips nothing. */
    private var type3Depth = 0

    /** An XObject with the indirect object number it resolved from (null for
     *  the rare ref-less inline entry). The number keys the decoded caches. */
    public class XObjectSlot(public val objectNumber: Long?, public val stream: PdfStream)

    /** Parsed form-XObject resources, memoized by the form's object number so a
     *  form drawn N times (repeated stamps/icons) parses its fonts/colorspaces
     *  once. Ref-less forms rebuild each time (rare). */
    private class FormResources(
        val fonts: Map<String, PdfFont>,
        val xobjects: Map<String, XObjectSlot>,
        val colorSpaces: Map<String, KiteColorSpace>,
        val extGStates: Map<String, ExtGState>,
        val shadings: Map<String, KiteShading>,
        val patterns: Map<String, KitePattern>,
        val properties: Map<String, PdfObject>,
    )
    private val formResourceCache = HashMap<Long, FormResources>()

    /** The table of each soft mask transfer function, built once per function. */
    private val maskTransfers = HashMap<KiteFunction, KiteMaskTransfer>()

    // ─── Optional content (layers) ───────────────────────────────────────────
    // Marked-content sections introduced by `BDC /OC <ocg>` are suppressed when
    // the referenced OCG/OCMD is hidden in the document's default configuration
    // (ISO 32000-1 §8.11). markedContentStack tracks every open BMC/BDC so EMC
    // pops the matching one; ocHiddenDepth counts how many open sections are
    // currently hiding content. Painting is skipped while it is > 0.
    private val markedContentStack = ArrayDeque<Boolean>()
    private var ocHiddenDepth = 0

    // Nesting past MAX_MARKED_CONTENT_DEPTH is counted here, not stored (#166).
    private var markedContentOverflow = 0

    /** The deepest marked-content nesting the last render stored. For tests. */
    internal var deepestMarkedContent: Int = 0
        private set

    // Where the content stream being interpreted starts: a Q never pops
    // clipSaveStack, which grows by one per q, below clipSaveFloor, and an EMC
    // never pops markedContentStack below markedContentFloor. See StreamScope.
    private var clipSaveFloor = 0
    private var markedContentFloor = 0

    // The page's /Properties, for a nested stream whose own resources lack the name (#56).
    private var pageProperties: Map<String, PdfObject> = emptyMap()

    /**
     * The face for text whose font is missing from the resources: Helvetica,
     * the substitute ISO 32000-1, 9.6.2.2 asks for and the one MuPDF uses, so
     * the text paints and advances by real widths (#139, #142).
     */
    private val missingFont: PdfFont by lazy {
        PdfFont.from(
            PdfDictionary(linkedMapOf<String, PdfObject>(
                "Type" to PdfName("Font"), "Subtype" to PdfName("Type1"), "BaseFont" to PdfName("Helvetica"),
            )),
            resolver,
        )
    }
    private var optionalContent: io.github.yuroyami.kitepdf.PdfOptionalContent? = null

    /** The page's default (initial) CTM. Pattern matrices are relative to it. */
    private var pageBaseCtm: KiteMatrix = KiteMatrix.IDENTITY

    /**
     * The default space of the content stream being run: the page's, a form's once its
     * /Matrix applies, a tiling cell's or a Type 3 glyph's. A pattern matrix maps to it
     * (ISO 32000-1, 8.7.3.1), so a pattern inside a form moves with the form (#93).
     */
    private var patternBaseCtm: KiteMatrix = KiteMatrix.IDENTITY

    /** The page crop box, so a soft mask whose /BBox cannot be read still covers only the page (#255). */
    private var pageCropBox: io.github.yuroyami.kitepdf.core.KiteRectangle? = null

    /** Form-XObject nesting depth. It guards self/transitively-recursive `Do`. */
    private var formDepth = 0

    /**
     * Operations dispatched for the current page, including every tiling-cell
     * and form-XObject replay. Once past [MAX_DISPATCHED_OPS], [dispatch]
     * becomes a no-op so an adversarial stream (millions of ops, or a small
     * pattern replayed thousands of times) terminates instead of rendering
     * forever. The page finishes with whatever was painted.
     */
    private var dispatchedOps = 0L

    /** True once [cancellation] read true: every later operator is skipped. */
    private var cancelled = false

    /** True while content must not be painted (inside a hidden OC section). */
    private fun ocHidden(): Boolean = ocHiddenDepth > 0

    /**
     * The page's own resources, kept for the fallback the spec asks for: a
     * form XObject or a Type3 char proc with no `/Resources` of its own reads
     * the page's (ISO 32000-1, 7.8.3 and 9.6.5), rather than seeing nothing.
     */
    private var pageResources: PdfDictionary? = null

    /**
     * Paints only the annotations [annotations] accepts, and none of the page's own content.
     *
     * A viewer that keeps a drawn page and redraws its form on top uses this: the page is
     * rasterized once without its widgets, and each changed field is painted over it, so a value
     * a script writes twenty times a second costs twenty small redraws and not twenty pages.
     */
    public fun renderAnnotations(
        page: PdfPage,
        deviceCtm: KiteMatrix = defaultDeviceCtm(page),
        annotations: (io.github.yuroyami.kitepdf.PdfAnnotation) -> Boolean = { true },
    ) {
        pageResources = page.resources
        optionalContent = page.internalDocument.optionalContent
        activeClipCount = 0
        clipSaveStack.clear()
        pendingClip = 0
        pageBaseCtm = deviceCtm
        patternBaseCtm = deviceCtm
        pageCropBox = page.cropBox
        formDepth = 0
        dispatchedOps = 0L
        markedContentStack.clear()
        ocHiddenDepth = 0
        canvas.beginPage(page.rotatedWidth, page.rotatedHeight, deviceCtm)
        try {
            renderAnnotations(page, GraphicsStack(GraphicsState(ctm = deviceCtm)), annotations)
        } finally {
            canvas.endPage()
        }
    }

    /**
     * Paints [page] under [deviceCtm], then the annotations that [annotations]
     * accepts. `{ false }` leaves the page content on its own (#133).
     */
    public fun render(
        page: PdfPage,
        deviceCtm: KiteMatrix = defaultDeviceCtm(page),
        annotations: (io.github.yuroyami.kitepdf.PdfAnnotation) -> Boolean = { true },
    ) {
        pageResources = page.resources
        val fonts = loadFonts(page.resources)
        val xobjects = loadXObjects(page.resources)
        val colorSpaces = loadColorSpaces(page.resources)
        val extGStates = loadExtGStates(page.resources)
        val shadings = loadShadings(page.resources, colorSpaces)
        val patterns = loadPatterns(page.resources, shadings, colorSpaces)
        val properties = loadProperties(page.resources)
        val state = GraphicsStack(GraphicsState(ctm = deviceCtm))
        activeClipCount = 0
        clipSaveStack.clear()
        pendingClip = 0
        pageBaseCtm = deviceCtm
        patternBaseCtm = deviceCtm
        pageCropBox = page.cropBox
        formDepth = 0
        dispatchedOps = 0L
        cancelled = false
        optionalContent = page.internalDocument.optionalContent
        markedContentStack.clear()
        ocHiddenDepth = 0
        markedContentOverflow = 0
        deepestMarkedContent = 0
        clipSaveFloor = 0
        markedContentFloor = 0
        pageProperties = properties
        // A stray d1 on an earlier page must not freeze this page's colours (#52).
        type3IgnoreColor = false
        val pathBuilder = KitePath.Builder()
        val ops = page.operations(colorSpaces)

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
            // Nor may an unclosed q or hidden layer in the page content reach the
            // annotations: their visibility is their own (ISO 32000-1, 12.5.2, #53).
            clipSaveStack.clear()
            clipSaveFloor = 0
            pendingClip = 0
            markedContentStack.clear()
            markedContentFloor = 0
            markedContentOverflow = 0
            ocHiddenDepth = 0
            renderAnnotations(page, GraphicsStack(GraphicsState(ctm = deviceCtm)), annotations)
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
            // A nested stream whose own resources lack the name reads the page's (#56).
            is PdfName -> properties[operand.value] ?: pageProperties[operand.value]
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
        // A missing object is null (ISO 32000-1, 7.3.10), so it hides nothing (#252).
        val dict = missingAsNull { obj.resolve(resolver) } as? PdfDictionary
            ?: return true
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
        (missingAsNull { dict["VE"]?.resolve(resolver) } as? PdfArray)?.let { ve ->
            // ISO 32000-1, 8.11.2.2 bounds no expression, so a cyclic or absurdly
            // deep one counts as visible instead of overflowing the stack (#55).
            return try { evalVisibilityExpr(ve, oc, 0) } catch (_: VisibilityTooDeep) { true }
        }
        val ocgsRaw = dict["OCGs"]
        val refs: List<io.github.yuroyami.kitepdf.core.parser.PdfReference> = when (
            val r = missingAsNull { ocgsRaw?.resolve(resolver) }
        ) {
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
    private fun evalVisibilityExpr(expr: PdfArray, oc: io.github.yuroyami.kitepdf.PdfOptionalContent, depth: Int): Boolean {
        if (depth > MAX_VISIBILITY_DEPTH) throw VisibilityTooDeep()
        val opName = (expr.getOrNull(0) as? PdfName)?.value ?: return true
        val operands = (1 until expr.size).mapNotNull { expr.getOrNull(it) }
        fun evalOperand(o: PdfObject): Boolean = when (val r = missingAsNull { o.resolve(resolver) }) {
            is PdfArray -> evalVisibilityExpr(r, oc, depth + 1)
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

    /** Named shadings declared in /Resources /Shading, each in the default space of [colorSpaces] when it names a device family. */
    private fun loadShadings(resources: PdfDictionary?, colorSpaces: Map<String, KiteColorSpace>): Map<String, KiteShading> {
        val dict = resources?.getDict("Shading", resolver) ?: return emptyMap()
        return dict.map.mapNotNull { (name, value) ->
            val source = DefaultColorSpaces.shading(value, resources, colorSpaces, resolver, outputIntent)
            val sh = KiteShading.parse(source, resolver) ?: return@mapNotNull null
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
        colorSpaces: Map<String, KiteColorSpace>,
    ): Map<String, KitePattern> {
        val dict = resources?.getDict("Pattern", resolver) ?: return emptyMap()
        return dict.map.mapNotNull { (name, value) ->
            val source = DefaultColorSpaces.pattern(value, resources, colorSpaces, resolver, outputIntent)
            val p = KitePattern.parse(source, resolver, shadings) ?: return@mapNotNull null
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
    private class VisibilityTooDeep : RuntimeException()

    private fun renderAnnotations(
        page: io.github.yuroyami.kitepdf.PdfPage,
        state: GraphicsStack,
        accept: (io.github.yuroyami.kitepdf.PdfAnnotation) -> Boolean,
    ) {
        for (annot in page.annotations) {
            if (!accept(annot)) continue
            if (annot.isHidden) continue   // /F Hidden or NoView (§12.5.3)
            // Invisible hides only a non-standard subtype with no handler (§12.5.3, #64).
            if (annot.isInvisible && annot.subtype == Subtype.Other) continue
            // Popup annotations are only shown when their parent is opened, never
            // painted inline by a viewer.
            if (annot.subtype == Subtype.Popup) continue
            // An annotation on a switched-off layer is skipped (§12.5.2, #54).
            val oc = optionalContent
            val ocEntry = annot.raw["OC"]
            if (oc != null && ocEntry != null && !isOcObjectVisible(ocEntry, oc)) continue
            // A form being filled draws from the live value, not from the appearance the file
            // stores, because that one still shows what the field held when it was written.
            val liveAppearance = liveWidgetAppearance(annot)
            val stream = annot.appearanceStream
            when {
                liveAppearance === HIDDEN_WIDGET -> Unit
                liveAppearance != null -> renderAppearanceForRect(liveAppearance, annot.rect, state)
                stream != null -> renderAppearanceForRect(
                    stream, annot.rect, state, noZoom = annot.isNoZoom, opacity = opacityOf(annot),
                )
                // A state-keyed appearance whose /AS names no entry paints nothing (#59).
                hasStateAppearances(annot) -> Unit
                else -> synthesizeAppearance(annot, state)
            }
        }
    }

    /**
     * The appearance a widget gets from [formState], or null when the state says nothing about it
     * and the file's own appearance applies. [HIDDEN_WIDGET] means the state hides the field.
     */
    private fun liveWidgetAppearance(annot: io.github.yuroyami.kitepdf.PdfAnnotation): PdfStream? {
        val state = formState ?: return null
        if (annot.subtype != Subtype.Widget) return null
        val name = io.github.yuroyami.kitepdf.PdfFormField.qualifiedNameOf(annot.raw, resolver) ?: return null
        if (state.isHidden(name)) return HIDDEN_WIDGET
        if (!state.isChanged(name)) return null
        return io.github.yuroyami.kitepdf.writer.FieldAppearance.synthesize(
            annot.raw, annot.rect.width, annot.rect.height, resolver, valueOverride = state.value(name) ?: "",
        )
    }

    /** `/CA`, the annotation's constant opacity (ISO 32000-1, Table 164), 1 when absent. */
    private fun opacityOf(annot: io.github.yuroyami.kitepdf.PdfAnnotation): Double =
        when (val ca = annot.raw["CA"]?.resolve(resolver)) {
            is PdfInt -> ca.value.toDouble()
            is PdfReal -> ca.value
            else -> 1.0
        }.coerceIn(0.0, 1.0)

    private fun hasStateAppearances(annot: io.github.yuroyami.kitepdf.PdfAnnotation): Boolean =
        annot.raw.getDict("AP", resolver)?.get("N")?.resolve(resolver) is PdfDictionary

    /**
     * Map a Form XObject appearance to fill the annotation's /Rect, per
     * ISO 32000-1 §12.5.5 Algorithm 8.1:
     *   1. Transform the appearance /BBox corners by the appearance /Matrix.
     *   2. Take the smallest upright rectangle enclosing those corners, the
     *      "transformed appearance box".
     *   3. Compute matrix A mapping that transformed box onto /Rect.
     * We concat A into the CTM; the form's own /Matrix is then applied by
     * [renderFormXObjectInner] when it draws the content (so the two compose to
     * BBox → transformed-box → Rect). Previously /Matrix was ignored, so a
     * rotated/skewed appearance landed off its /Rect.
     */
    private fun renderAppearanceForRect(
        appearance: PdfStream,
        rect: io.github.yuroyami.kitepdf.core.KiteRectangle,
        state: GraphicsStack,
        noZoom: Boolean = false,
        opacity: Double = 1.0,
    ) {
        val bbox = missingAsNull { appearance.dict.getArray("BBox", resolver) }?.let { arr ->
            io.github.yuroyami.kitepdf.core.KiteRectangle(
                arr.getOrNull(0).toDouble(), arr.getOrNull(1).toDouble(),
                arr.getOrNull(2).toDouble(), arr.getOrNull(3).toDouble(),
            )
        } ?: io.github.yuroyami.kitepdf.core.KiteRectangle(0.0, 0.0, rect.width, rect.height)

        val matrix = missingAsNull { appearance.dict.getArray("Matrix", resolver) }?.let { arr ->
            KiteMatrix(
                arr.getOrNull(0).toDouble(), arr.getOrNull(1).toDouble(),
                arr.getOrNull(2).toDouble(), arr.getOrNull(3).toDouble(),
                arr.getOrNull(4).toDouble(), arr.getOrNull(5).toDouble(),
            )
        } ?: KiteMatrix.IDENTITY

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

        // Step 3: A maps the transformed appearance box onto /Rect. A NoZoom
        // annotation keeps its own size, the rectangle's upper-left corner fixed
        // (§12.5.3, Table 165), which is exact at 72 dpi, as MuPDF does it (#63).
        val mapping = if (noZoom) {
            KiteMatrix(1.0, 0.0, 0.0, 1.0, rect.left - tbLeft, rect.top - tbTop)
        } else {
            val sx = if (tbW != 0.0) rect.width / tbW else 1.0
            val sy = if (tbH != 0.0) rect.height / tbH else 1.0
            KiteMatrix(sx, 0.0, 0.0, sy, rect.left - tbLeft * sx, rect.bottom - tbBottom * sy)
        }

        // /CA applies once, to the finished appearance, not to each paint in it (#163).
        val grouped = opacity < 1.0
        if (grouped) {
            canvas.beginTransparencyGroup(
                bbox = rect, ctm = state.current.ctm, isolated = true, knockout = false,
                alpha = opacity, blendMode = KiteBlendMode.Normal,
            )
        }
        state.save()
        state.replace(state.current.copy(ctm = state.current.ctm.concat(mapping)))
        try {
            renderFormXObject(appearance, state)
        } finally {
            state.restore()
            if (grouped) canvas.endTransparencyGroup()
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
                forEachQuad(quads, rect) { c ->
                    val p = KitePath.Builder().apply {
                        moveTo(c[0], c[1]); lineTo(c[2], c[3]); lineTo(c[4], c[5]); lineTo(c[6], c[7]); close()
                    }.build()
                    // A highlight multiplies onto the page, so text under it stays
                    // black and paper takes the colour, as MuPDF draws it (#60).
                    canvas.fillPath(p, ctm, color, false, blendMode = KiteBlendMode.Multiply)
                }
            }
            Subtype.Underline, Subtype.StrikeOut, Subtype.Squiggly -> {
                val color = annot.color ?: RgbColor.BLACK
                val frac = if (annot.subtype == Subtype.StrikeOut) 0.5 else 0.08
                forEachQuad(quads, rect) { c ->
                    // The line follows the quadrilateral's own bottom edge, raised by
                    // frac of its height, so it stays on rotated text (#61).
                    val line = KitePath.Builder().apply {
                        moveTo(c[6] + (c[0] - c[6]) * frac, c[7] + (c[1] - c[7]) * frac)
                        lineTo(c[4] + (c[2] - c[4]) * frac, c[5] + (c[3] - c[5]) * frac)
                    }.build()
                    val height = kotlin.math.hypot(c[0] - c[6], c[1] - c[7])
                    canvas.strokePath(line, ctm, color, (height * 0.06).coerceAtLeast(0.6))
                }
            }
            // Borders stroke at their declared width and dash (ISO 32000-1, 12.5.4,
            // Table 166), and a square or circle keeps its stroke inside its rectangle (#62).
            Subtype.Square -> {
                val w = borderWidthOf(annot)
                val p = KitePath.Builder().apply {
                    rectangle(rect.left + w / 2, rect.bottom + w / 2, (rect.width - w).coerceAtLeast(0.0), (rect.height - w).coerceAtLeast(0.0))
                }.build()
                annot.interiorColor?.let { canvas.fillPath(p, ctm, it, false) }
                strokeBorder(annot, p, ctm, w)
            }
            Subtype.Circle -> {
                val w = borderWidthOf(annot)
                val inset = io.github.yuroyami.kitepdf.core.KiteRectangle(
                    rect.left + w / 2, rect.bottom + w / 2,
                    maxOf(rect.left + w / 2, rect.right - w / 2), maxOf(rect.bottom + w / 2, rect.top - w / 2),
                )
                val p = ellipsePath(inset)
                annot.interiorColor?.let { canvas.fillPath(p, ctm, it, false) }
                strokeBorder(annot, p, ctm, w)
            }
            Subtype.Line -> annot.vertices?.let { v ->
                if (v.size >= 4) {
                    val line = KitePath.Builder().apply { moveTo(v[0], v[1]); lineTo(v[2], v[3]) }.build()
                    strokeBorder(annot, line, ctm, borderWidthOf(annot))
                }
            }
            Subtype.Polygon, Subtype.PolyLine -> annot.vertices?.let { v ->
                val p = polyPath(v, close = annot.subtype == Subtype.Polygon)
                if (p != null) {
                    if (annot.subtype == Subtype.Polygon) annot.interiorColor?.let { canvas.fillPath(p, ctm, it, false) }
                    strokeBorder(annot, p, ctm, borderWidthOf(annot))
                }
            }
            Subtype.Ink -> annot.inkLists?.forEach { stroke ->
                polyPath(stroke, close = false)?.let { strokeBorder(annot, it, ctm, borderWidthOf(annot)) }
            }
            // A form widget with no /AP: draw its box, its border and its value from the field's
            // own entries, the way §12.7.3.3 describes and MuPDF does on page load. Without this
            // a form written without appearance streams renders as an empty sheet (#127).
            Subtype.Widget -> {
                val appearance = io.github.yuroyami.kitepdf.writer.FieldAppearance.synthesize(
                    annot.raw, rect.width, rect.height, resolver,
                )
                if (appearance != null) renderAppearanceForRect(appearance, rect, state)
            }
            Subtype.Link -> {
                // §12.5.4: a declared width of 0 means "no visible border", which is what a link
                // styled as coloured text asks for. Drawing one anyway put a box around every such
                // link. Null means undeclared, where the spec default of 1 applies, so it still
                // draws. The 0.5 stroke is this renderer's own hairline for the default case.
                val declared = annot.borderWidth
                if (declared == null || declared > 0.0) {
                    val p = KitePath.Builder()
                        .apply { rectangle(rect.left, rect.bottom, rect.width, rect.height) }
                        .build()
                    canvas.strokePath(p, ctm, annot.color ?: RgbColor(0.0, 0.3, 0.8), declared ?: 0.5)
                }
            }
            // Notes, attachments, carets, stamps and free text have no geometry to draw from,
            // so each gets a small appearance of its own, as MuPDF draws them on load (#164).
            Subtype.Text, Subtype.FileAttachment, Subtype.Caret, Subtype.Stamp, Subtype.FreeText ->
                io.github.yuroyami.kitepdf.writer.AnnotationAppearance.synthesize(annot, resolver)?.let {
                    renderAppearanceForRect(it, rect, state, noZoom = annot.isNoZoom, opacity = opacityOf(annot))
                }
            else -> { /* other annotations: nothing without /AP */ }
        }
    }

    /** The declared border width, or the default of 1 (ISO 32000-1, 12.5.4). */
    private fun borderWidthOf(annot: io.github.yuroyami.kitepdf.PdfAnnotation): Double =
        annot.borderWidth?.takeIf { it >= 0.0 } ?: 1.0

    /** Strokes [path] at [width], dashed when the border style says so. A width of 0 draws no border. */
    private fun strokeBorder(annot: io.github.yuroyami.kitepdf.PdfAnnotation, path: KitePath, ctm: KiteMatrix, width: Double) {
        if (width <= 0.0) return
        val dashed = annot.borderStyle == "D" || (annot.borderStyle == null && annot.borderDash != null)
        canvas.strokePath(
            path, ctm, annot.color ?: RgbColor.BLACK, width,
            dashArray = if (dashed) annot.borderDash ?: listOf(3.0) else null,
        )
    }

    /**
     * Invoke [block] once per /QuadPoints quadrilateral, with its corners in path
     * order: upper-left, upper-right, lower-right, lower-left. The array lists
     * them upper-left, upper-right, lower-left, lower-right, which is what
     * producers write whatever ISO 32000-1, Table 179 says. The region is the
     * quadrilateral itself, not its bounding box (#61). With no quads, [rect]
     * is the one quadrilateral.
     */
    private inline fun forEachQuad(
        quads: List<Double>?, rect: io.github.yuroyami.kitepdf.core.KiteRectangle,
        block: (corners: DoubleArray) -> Unit,
    ) {
        if (quads != null && quads.size >= 8) {
            var i = 0
            while (i + 7 < quads.size) {
                block(doubleArrayOf(
                    quads[i], quads[i + 1], quads[i + 2], quads[i + 3],
                    quads[i + 6], quads[i + 7], quads[i + 4], quads[i + 5],
                ))
                i += 8
            }
        } else {
            val top = rect.bottom + rect.height
            val right = rect.left + rect.width
            block(doubleArrayOf(rect.left, top, right, top, right, rect.bottom, rect.left, rect.bottom))
        }
    }

    /** Four-Bézier ellipse inscribed in [rect]. */
    private fun ellipsePath(rect: io.github.yuroyami.kitepdf.core.KiteRectangle): KitePath {
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

    /**
     * Named colour spaces declared in /Resources /ColorSpace. An entry that names a device
     * family selects that family, so the default for it applies (ISO 32000-1, 8.6.5.6).
     */
    /** The CMYK output intent of the document, the DefaultCMYK of resources that name none (#312). */
    private val outputIntent: DefaultColorSpaces.OutputIntent? =
        (resolver as? io.github.yuroyami.kitepdf.PdfDocument)?.cmykOutputIntent

    private fun loadColorSpaces(resources: PdfDictionary?): Map<String, KiteColorSpace> {
        val spaces = DefaultColorSpaces.withOutputIntent(ContentStreamParser.colorSpaces(resources, resolver), outputIntent)
        if (DefaultColorSpaces.KEYS.none { it in spaces }) return spaces
        val entries = runCatching { resources?.getDict("ColorSpace", resolver) }.getOrNull() ?: return spaces
        return spaces.mapValues { (name, space) ->
            val device = if (name in DefaultColorSpaces.KEYS) null else DefaultColorSpaces.deviceFamily(entries[name], resolver)
            if (device != null) DefaultColorSpaces.substitute(device, spaces) else space
        }
    }

    /**
     * Decode an image XObject through the per-document cache: keyed by
     * the indirect object number, so a logo stamped 40 times (or a background
     * shared by every page) decodes once per document. /ImageMask stencils are
     * tinted by the CURRENT fill colour, so their decoded form is
     * state-dependent: never cached. Ref-less images skip the cache too.
     *
     * So do images carrying a `/Mask`. Those are the ink layer of an MRC scan:
     * one use per page, resampled onto the stencil's grid, so caching them
     * would pin tens of megabytes per page of a scanned book for a reuse that
     * never comes. This matches how `/ImageMask` stencils are already treated.
     *
     * An image in a device space that a default of [colorSpaces] replaces skips the cache
     * as well, because the same image can meet other defaults on another page. The output
     * intent is the same on every page, so an image that only it replaces is cached.
     */
    private fun decodeImageCached(slot: XObjectSlot, fillColor: RgbColor, colorSpaces: Map<String, KiteColorSpace>): KiteImageData {
        val doc = resolver as? io.github.yuroyami.kitepdf.PdfDocument
        val key = slot.objectNumber
        val dict = slot.stream.dict
        val stencil = (dict["ImageMask"] as? io.github.yuroyami.kitepdf.core.parser.PdfBoolean)?.value == true
        val masked = stencil || dict["Mask"] != null
        val defaultSpace = if (stencil) null else DefaultColorSpaces.imageSpace(dict["ColorSpace"], colorSpaces, resolver)
        if (defaultSpace != null && doc != null && key != null && !masked && DefaultColorSpaces.onlyOutputIntent(colorSpaces, outputIntent)) {
            doc.cachedImage(key)?.let { return it }
            doc.countImageDecode()
            return doc.cacheImage(key, KiteImageData.from(slot.stream, resolver, fillColor, defaultSpace))
        }
        if (defaultSpace != null) {
            doc?.countImageDecode()
            return KiteImageData.from(slot.stream, resolver, fillColor, defaultSpace)
        }
        if (doc == null || key == null || masked) {
            doc?.countImageDecode()
            return KiteImageData.from(slot.stream, resolver, fillColor)
        }
        doc.cachedImage(key)?.let { return it }
        doc.countImageDecode()
        return doc.cacheImage(key, KiteImageData.from(slot.stream, resolver, fillColor))
    }

    /**
     * Decode an inline image captured verbatim as `BI … ID <data> EI` (§8.9.7).
     * Parses the abbreviated dictionary, slices the raw data, and builds an
     * [KiteImageData] driven through the normal raster path. The fill colour of [s]
     * tints an inline `/ImageMask` stencil, and its intent converts the colours.
     */
    private fun decodeInlineImage(
        blob: ByteArray,
        s: GraphicsState,
        colorSpaces: Map<String, KiteColorSpace>,
    ): KiteImageData? {
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
        // Exact sample length preserves whitespace-valued final samples as well
        // as embedded EI bytes in resource-named spaces (ISO 32000-1, 8.9.7).
        val exactLength = ContentStreamParser.unfilteredDataLength(PdfDictionary(entries), colorSpaces)
        if (exactLength != null && exactLength <= blob.size - 2 - dataStart) {
            dataEnd = dataStart + exactLength
        }
        val data = blob.copyOfRange(dataStart, dataEnd)
        entries["Length"] = PdfInt(data.size.toLong())
        val stream = PdfStream(PdfDictionary(entries), data)
        val colorName = (entries["ColorSpace"] as? PdfName)?.value
        val colorSpace = colorName?.let { namedColorSpace(it, colorSpaces) }
            ?: DefaultColorSpaces.imageSpace(entries["ColorSpace"], colorSpaces, resolver)
        return runCatching { KiteImageData.from(stream, resolver, s.fillColor, colorSpace) }.getOrNull()
            ?.withIntent(imageIntent(stream.dict, s), s.blackPointCompensation)
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

    private fun isTransparencyGroup(form: PdfStream): Boolean =
        form.dict.getDict("Group", resolver)?.getName("S") == "Transparency"

    private fun renderFormXObjectInner(
        formStream: PdfStream,
        parentState: GraphicsStack,
        objectNumber: Long?,
    ) {
        // Both may be indirect arrays (ISO 32000-1, 7.3.10, #273).
        val formMatrix = missingAsNull { formStream.dict.getArray("Matrix", resolver) }?.let { arr ->
            KiteMatrix(
                arr.getOrNull(0).toDouble(), arr.getOrNull(1).toDouble(),
                arr.getOrNull(2).toDouble(), arr.getOrNull(3).toDouble(),
                arr.getOrNull(4).toDouble(), arr.getOrNull(5).toDouble(),
            )
        } ?: KiteMatrix.IDENTITY
        val bbox = missingAsNull { formStream.dict.getArray("BBox", resolver) }?.let { arr ->
            io.github.yuroyami.kitepdf.core.KiteRectangle(
                arr.getOrNull(0).toDouble(), arr.getOrNull(1).toDouble(),
                arr.getOrNull(2).toDouble(), arr.getOrNull(3).toDouble(),
            )
        } ?: io.github.yuroyami.kitepdf.core.KiteRectangle(0.0, 0.0, 1000.0, 1000.0)
        val ownResources = formStream.dict.getDict("Resources", resolver) != null
        fun buildResources(): FormResources {
            val resources = formStream.dict.getDict("Resources", resolver) ?: pageResources
            val colorSpaces = loadColorSpaces(resources)
            val sh = loadShadings(resources, colorSpaces)
            return FormResources(
                fonts = loadFonts(resources),
                xobjects = loadXObjects(resources),
                colorSpaces = colorSpaces,
                extGStates = loadExtGStates(resources),
                shadings = sh,
                patterns = loadPatterns(resources, sh, colorSpaces),
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
        // Isolation only shows when a paint inside blends in a mode other than Normal (#125).
        // Without such a paint, a group that needs a layer anyway takes the cheaper transparent
        // one, and a group at full alpha in Normal paints straight onto the page.
        val isolated = if (formBlends(res.extGStates, res.xobjects)) {
            (groupDict?.get("I") as? io.github.yuroyami.kitepdf.core.parser.PdfBoolean)?.value ?: false
        } else {
            parentState.current.fillAlpha < 1.0 || parentState.current.blendMode != KiteBlendMode.Normal
        }
        val knockout = (groupDict?.get("K") as? io.github.yuroyami.kitepdf.core.parser.PdfBoolean)?.value ?: false

        parentState.save()
        parentState.replace(parentState.current.copy(
            ctm = parentState.current.ctm.concat(formMatrix),
        ))
        val savedPatternBase = patternBaseCtm
        patternBaseCtm = parentState.current.ctm
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
            // ISO 32000-1, 11.6.6: the soft mask resets to None inside the group too.
            parentState.replace(parentState.current.copy(
                fillAlpha = 1.0, strokeAlpha = 1.0, blendMode = KiteBlendMode.Normal,
                softMask = null, softMaskCtm = null,
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
        val scope = openScope()
        try {
            val parse = { ContentStreamParser.parse(io.github.yuroyami.kitepdf.core.filters.FilterChain.decode(formStream), childColorSpaces) }
            // A form with resources of its own parses the same way wherever it is drawn, so its
            // operations come from the document's cache. Without them it reads the page's (#118).
            val ops = if (ownResources) cachedOperations(objectNumber, parse) else parse()
            val pathBuilder = KitePath.Builder()
            for (op in ops) dispatch(op, parentState, pathBuilder, childFonts, childXObjects, childColorSpaces, childExtGStates, childShadings, childPatterns, childProperties)
        } finally {
            closeScope(scope, parentState)
            pendingClip = savedPendingClip
            // Drop any clips the form's content left unbalanced, then the BBox clip.
            while (activeClipCount > clipBase) { canvas.popClip(); activeClipCount-- }
            canvas.popClip()
            if (groupOpened) canvas.endTransparencyGroup()
            patternBaseCtm = savedPatternBase
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
            // caches. Inline (ref-less) entries decode uncached.
            name to XObjectSlot((raw as? PdfReference)?.objectNumber, resolved)
        }.toMap()
    }

    /**
     * Default device transform: unscaled user-space → device with the origin at
     * the TOP-LEFT (y-down), honouring the display box origin and normalized
     * /Rotate. Delegates to [PdfPage.pageToDeviceBase] so /Rotate, a non-zero
     * MediaBox origin, and CropBox are all folded in (they were previously
     * ignored by the old `KiteMatrix(1,0,0,-1,0,height)`).
     */
    private fun defaultDeviceCtm(page: PdfPage): KiteMatrix =
        page.pageToDeviceBase()

    /* ─── Operator dispatch ──────────────────────────────────────────────── */

    private fun dispatch(
        op: Operation,
        state: GraphicsStack,
        path: KitePath.Builder,
        fonts: Map<String, PdfFont>,
        xobjects: Map<String, XObjectSlot>,
        colorSpaces: Map<String, KiteColorSpace>,
        extGStates: Map<String, ExtGState>,
        shadings: Map<String, KiteShading>,
        patterns: Map<String, KitePattern>,
        properties: Map<String, PdfObject>,
    ) {
        if (++dispatchedOps > MAX_DISPATCHED_OPS || cancelled) return
        // A caller that gave up on the render stops it between operators, as MuPDF's cookie does (#188).
        if (dispatchedOps and 31L == 0L && cancellation?.isCancelled() == true) {
            cancelled = true
            return
        }
        // d1 (uncolored) Type3 glyph procs and uncoloured pattern cells must not change colour state.
        if (type3IgnoreColor && op.operator in TYPE3_COLOR_OPS) return
        val a = op.operands
        when (op.operator) {
            // Type3 glyph metrics operators (§9.6.5): d0 declares a coloured
            // glyph (nothing to do: wx/wy come from /Widths); d1 declares an
            // uncoloured one, so colour operators are ignored from here on.
            "d0" -> Unit
            "d1" -> {
                type3IgnoreColor = true
                // The d1 box clips the glyph (ISO 32000-1, 9.6.5, #144). A box with no area clips nothing.
                if (type3Depth > 0 && a.size >= 6) {
                    val llx = num(a, 2); val lly = num(a, 3); val urx = num(a, 4); val ury = num(a, 5)
                    if (urx > llx && ury > lly) {
                        val box = KitePath.Builder().apply { rectangle(llx, lly, urx - llx, ury - lly) }.build()
                        canvas.pushClip(box, state.current.ctm, evenOdd = false)
                        activeClipCount++
                    }
                }
            }
            // ─── State stack ──────────────────────────────────────────────
            "q" -> { state.save(); clipSaveStack.addLast(activeClipCount) }
            "Q" -> {
                // A Q never pops past the start of the stream that issued it (ISO
                // 32000-1, 8.4.4), so a form's extra Q cannot reach its caller (#48).
                if (clipSaveStack.size <= clipSaveFloor) return
                state.restore()
                val target = clipSaveStack.removeLast()
                while (activeClipCount > target) { canvas.popClip(); activeClipCount-- }
            }
            "cm" -> {
                val m = KiteMatrix(num(a, 0), num(a, 1), num(a, 2), num(a, 3), num(a, 4), num(a, 5))
                state.replace(state.current.copy(ctm = state.current.ctm.concat(m)))
            }
            // A bare `w` (no operand) must keep the current width, not reset to 0
            // (which would render every subsequent stroke as a hairline). Only
            // update when an operand is actually present.
            "w" -> if (a.isNotEmpty()) state.replace(state.current.copy(lineWidth = num(a, 0)))
            // A missing operand leaves the parameter alone, as it does for w (#137).
            "J" -> if (a.isNotEmpty()) state.replace(state.current.copy(lineCap = num(a, 0).toInt()))
            "j" -> if (a.isNotEmpty()) state.replace(state.current.copy(lineJoin = num(a, 0).toInt()))
            "M" -> if (a.isNotEmpty()) state.replace(state.current.copy(miterLimit = num(a, 0)))
            "d" -> {
                // dash: [ array ] phase d. The array holds on/off lengths (user units).
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
            "g", "rg", "k" -> {
                val s = state.current
                val space = DefaultColorSpaces.substitute(deviceSpaceOf(op.operator), colorSpaces).withIntent(s.renderingIntent, s.blackPointCompensation)
                state.replace(s.copy(fillColor = deviceColor(space, a), fillColorSpace = space, fillPattern = null, fillComponents = components(space, a)))
            }
            "G", "RG", "K" -> {
                val s = state.current
                val space = DefaultColorSpaces.substitute(deviceSpaceOf(op.operator), colorSpaces).withIntent(s.renderingIntent, s.blackPointCompensation)
                state.replace(s.copy(strokeColor = deviceColor(space, a), strokeColorSpace = space, strokePattern = null, strokeComponents = components(space, a)))
            }
            // cs/CS select the colour space for subsequent sc/scn/SC/SCN. Without them a
            // non-device space (e.g. CoreGraphics' ICCBased-RGB on iOS-generated PDFs) stayed
            // at the default DeviceGray, so `r g b SCN` was read as gray(r), turning the pink
            // ECG grid white. Per ISO 32000-1 §8.6.8 selecting a space resets the colour to its
            // initial value (black) until the next sc/scn sets components.
            "cs" -> {
                val s = state.current
                val name = (a.firstOrNull() as? io.github.yuroyami.kitepdf.core.parser.PdfName)?.value
                val csp = (name?.let { namedColorSpace(it, colorSpaces) } ?: KiteColorSpace.DeviceGray)
                    .withIntent(s.renderingIntent, s.blackPointCompensation)
                state.replace(s.copy(fillColorSpace = csp, fillColor = csp.defaultColor(), fillPattern = initialPattern(name, csp), fillComponents = null))
            }
            "CS" -> {
                val s = state.current
                val name = (a.firstOrNull() as? io.github.yuroyami.kitepdf.core.parser.PdfName)?.value
                val csp = (name?.let { namedColorSpace(it, colorSpaces) } ?: KiteColorSpace.DeviceGray)
                    .withIntent(s.renderingIntent, s.blackPointCompensation)
                state.replace(s.copy(strokeColorSpace = csp, strokeColor = csp.defaultColor(), strokePattern = initialPattern(name, csp), strokeComponents = null))
            }
            // A new intent converts the current colours again, as MuPDF converts them when it paints (#201).
            "ri" -> {
                val name = (a.firstOrNull() as? PdfName)?.value
                state.replace(state.current.withColorRendering(KiteRenderingIntent.fromPdfName(name), state.current.blackPointCompensation))
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
            // any pending W/W* clip (§8.5.4: the clip uses this same path), then
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
            // other text-state params (char/word spacing, horizontal scale,
            // leading, rise, render mode, plus the current font/size) persist
            // across text objects (§9.3.1); they belong to the graphics state.
            "BT" -> {
                pendingTextClip = null
                state.mutateText {
                    it.copy(textMatrix = KiteMatrix.IDENTITY, lineMatrix = KiteMatrix.IDENTITY)
                }
            }
            "ET" -> {
                // Apply the accumulated modes-4..7 text clip.
                val clip = pendingTextClip
                pendingTextClip = null
                if (clip != null) {
                    val built = clip.build()
                    if (!built.isEmpty()) {
                        // Built in device space, so the CTM at ET cannot move it (#146).
                        canvas.pushClip(built, KiteMatrix.IDENTITY, evenOdd = false)
                        activeClipCount++
                    }
                }
            }
            "Tf" -> {
                val fontName = (a.getOrNull(0) as? PdfName)?.value
                val fontSize = num(a, 1)
                val resolved = fonts[fontName] ?: missingFont.also {
                    kiteWarn { "render: font $fontName missing from /Resources" }
                }
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
                val m = KiteMatrix(num(a, 0), num(a, 1), num(a, 2), num(a, 3), num(a, 4), num(a, 5))
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
                    "Image" -> {
                        val image = decodeImageCached(slot, state.current.fillColor, colorSpaces)
                            .withIntent(imageIntent(slot.stream.dict, state.current), state.current.blackPointCompensation)
                        if (paintsNothing(image, state.current)) return
                        withSoftMask(state.current) { paintImage(image, state.current) }
                    }
                    // A transparency group takes the soft mask once, on its composited
                    // result, not once per object inside it (ISO 32000-1, 11.6.6, #66).
                    "Form" -> if (isTransparencyGroup(slot.stream)) {
                        withSoftMask(state.current) { renderFormXObject(slot.stream, state, slot.objectNumber) }
                    } else {
                        renderFormXObject(slot.stream, state, slot.objectNumber)
                    }
                }
            }

            // ─── Shading fill (`sh <name>`) ──────────────────────────────
            "sh" -> {
                if (ocHidden()) return
                val name = (a.firstOrNull() as? PdfName)?.value ?: return
                val shading = shadings[name] ?: return
                val s = state.current
                // sh paints under the active soft mask like every other painting
                // operator (ISO 32000-1, 11.6.5.1, #65).
                withSoftMask(s) {
                    fillShadingInBBox(
                        shading.withIntent(s.renderingIntent, s.blackPointCompensation), s.ctm,
                        clipPath = null, alpha = s.fillAlpha, blendMode = s.blendMode,
                    )
                }
            }

            // ─── Inline image (BI … ID … EI) ─────────────────────────────
            "BI" -> {
                if (ocHidden()) return
                val blob = op.inlineImage ?: return
                val img = decodeInlineImage(blob, state.current, colorSpaces) ?: return
                if (paintsNothing(img, state.current)) return
                withSoftMask(state.current) { paintImage(img, state.current) }
            }

            // ─── Marked content (optional-content visibility) ────────────
            "BDC" -> {
                if (markedContentStack.size >= MAX_MARKED_CONTENT_DEPTH) { markedContentOverflow++; return }
                val tag = a.getOrNull(0) as? PdfName
                val hidden = tag?.value == "OC" && isOcOperandHidden(a.getOrNull(1), properties)
                markedContentStack.addLast(hidden)
                if (hidden) ocHiddenDepth++
                if (markedContentStack.size > deepestMarkedContent) deepestMarkedContent = markedContentStack.size
            }
            "BMC" -> {
                if (markedContentStack.size >= MAX_MARKED_CONTENT_DEPTH) { markedContentOverflow++; return }
                markedContentStack.addLast(false)
                if (markedContentStack.size > deepestMarkedContent) deepestMarkedContent = markedContentStack.size
            }
            "EMC" -> {
                if (markedContentOverflow > 0) { markedContentOverflow--; return }
                if (markedContentStack.size <= markedContentFloor) return
                val wasHidden = markedContentStack.removeLast()
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
            // are silently skipped.
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

    /**
     * The pattern a newly selected colour space starts with. A Pattern space's
     * initial colour is no pattern at all, which paints nothing until `scn`
     * names one (ISO 32000-1, 8.6.8, #148). Any other space starts with none.
     */
    private fun initialPattern(name: String?, csp: KiteColorSpace): KitePattern? =
        if (name == "Pattern" || (csp as? KiteColorSpace.Unsupported)?.name == "Pattern") KitePattern.Unsupported else null

    /**
     * [path] with its degenerate subpaths, those whose every point is the same,
     * settled per ISO 32000-1, 8.5.3.2: round caps paint one as a dot, drawn as
     * a zero-length line so every backend agrees (#135), and butt or square caps
     * paint nothing (#134). Null when nothing is left to stroke.
     */
    private fun strokeable(path: KitePath, lineCap: Int): KitePath? {
        val segs = path.segments
        var start = 0
        var degenerate = false
        while (start < segs.size) {
            val end = subpathEnd(segs, start)
            if (isDegenerate(segs, start, end)) { degenerate = true; break }
            start = end
        }
        if (!degenerate) return path
        val out = ArrayList<KitePath.Segment>(segs.size)
        start = 0
        while (start < segs.size) {
            val end = subpathEnd(segs, start)
            if (!isDegenerate(segs, start, end)) {
                for (k in start until end) out.add(segs[k])
            } else if (lineCap == 1) {
                val p = segs[start] as KitePath.Segment.MoveTo
                out.add(KitePath.Segment.MoveTo(p.x, p.y))
                out.add(KitePath.Segment.LineTo(p.x, p.y))
            }
            start = end
        }
        return if (out.isEmpty()) null else KitePath(out)
    }

    /** Index just past the subpath that starts at [from]: the next move, or the end. */
    private fun subpathEnd(segs: List<KitePath.Segment>, from: Int): Int {
        var k = from + 1
        while (k < segs.size && segs[k] !is KitePath.Segment.MoveTo) k++
        return k
    }

    /** A move followed only by segments that never leave its point. A lone move is not a subpath. */
    private fun isDegenerate(segs: List<KitePath.Segment>, from: Int, end: Int): Boolean {
        val m = segs[from] as? KitePath.Segment.MoveTo ?: return false
        if (end - from < 2) return false
        for (k in from + 1 until end) {
            val stays = when (val s = segs[k]) {
                is KitePath.Segment.LineTo -> s.x == m.x && s.y == m.y
                is KitePath.Segment.CurveTo ->
                    s.x1 == m.x && s.y1 == m.y && s.x2 == m.x && s.y2 == m.y && s.x3 == m.x && s.y3 == m.y
                is KitePath.Segment.QuadTo -> s.x1 == m.x && s.y1 == m.y && s.x2 == m.x && s.y2 == m.y
                is KitePath.Segment.MoveTo, KitePath.Segment.Close -> true
            }
            if (!stays) return false
        }
        return true
    }

    /**
     * An image in a None separation, or a stencil mask painting a None
     * separation's colour, has no effect on the page (ISO 32000-1, 8.6.6.4).
     */
    private fun paintsNothing(image: KiteImageData, s: GraphicsState): Boolean =
        image.resolvedColorSpace?.paintsNothing == true ||
            (image.maskFill != null && s.fillColorSpace.paintsNothing)

    private fun paintFill(path: KitePath.Builder, state: GraphicsStack, evenOdd: Boolean) {
        if (path.isEmpty()) return
        val s = state.current
        // ISO 32000-1, 8.6.6.4: painting in a None separation has no effect (#82).
        if (s.fillColorSpace.paintsNothing) return
        val built = path.build()
        withSoftMask(s) {
            val pat = s.fillPattern
            when {
                // The pattern /Matrix maps pattern space to the page's DEFAULT
                // coordinate system, not the current user space (§8.7.3.1). Use
                // pageBaseCtm, matching the tiling path below, instead of s.ctm.
                pat is KitePattern.Shading -> paintShadingPattern(pat, built, s, evenOdd, stroke = false)
                pat is KitePattern.Tiling -> renderTilingPattern(pat, built, s, evenOdd, s.fillAlpha, s.fillColor)
                pat != null -> {
                    // Unsupported pattern. Skip rather than paint the default
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
        if (s.strokeColorSpace.paintsNothing) return
        val built = strokeable(path.build(), s.lineCap) ?: return
        withSoftMask(s) {
            val pat = s.strokePattern
            when {
                pat is KitePattern.Shading || pat is KitePattern.Tiling -> strokeWithPattern(pat, built, s)
                pat != null -> {
                    // Unsupported pattern. Skip rather than paint a stale colour.
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
     * Strokes [path] in the pattern colour [pat]: the pattern fills the area the stroke
     * covers, with its width, dashes, caps and joins (ISO 32000-1, 8.7.3.1 and 8.5.3.2, #284).
     * The pattern /Matrix is relative to the default space of the stream that paints.
     */
    private fun strokeWithPattern(pat: KitePattern, path: KitePath, s: GraphicsState) {
        val ctm = s.ctm
        val det = kotlin.math.abs(ctm.a * ctm.d - ctm.b * ctm.c)
        if (!(det > 0.0) || !det.isFinite()) return
        // A width of 0 is the thinnest line the device can show, one pixel (8.4.3.2).
        val width = if (s.lineWidth > 0.0) s.lineWidth else 1.0 / kotlin.math.sqrt(det)
        // Curves flatten to a quarter of a device pixel. The Frobenius norm bounds the largest scale of the CTM.
        val scale = kotlin.math.sqrt(ctm.a * ctm.a + ctm.b * ctm.b + ctm.c * ctm.c + ctm.d * ctm.d)
        val outline = path.strokeOutline(
            width, s.lineCap, s.lineJoin, s.miterLimit, s.dashArray, s.dashPhase, tolerance = 0.25 / scale,
        )
        if (outline.isEmpty()) return
        when (pat) {
            is KitePattern.Shading -> paintShadingPattern(pat, outline, s, evenOdd = false, stroke = true)
            is KitePattern.Tiling -> renderTilingPattern(pat, outline, s, evenOdd = false, alpha = s.strokeAlpha, color = s.strokeColor)
            else -> Unit
        }
    }

    /** Paints [shading] clipped to its own /BBox, which lives in shading space (ISO 32000-1, 8.7.4.3, #154). */
    private fun fillShadingInBBox(
        shading: KiteShading, ctm: KiteMatrix, clipPath: KitePath?, alpha: Double, blendMode: KiteBlendMode,
    ) {
        val box = shading.bbox?.takeIf { it.right > it.left && it.top > it.bottom }
        if (box != null) {
            val rect = KitePath.Builder().apply { rectangle(box.left, box.bottom, box.right - box.left, box.top - box.bottom) }.build()
            canvas.pushClip(rect, ctm, evenOdd = false)
        }
        try {
            canvas.fillShading(shading, ctm, clipPath, alpha = alpha, blendMode = blendMode)
        } finally {
            if (box != null) canvas.popClip()
        }
    }

    /**
     * A shading pattern fill. The pattern's own graphics state is in effect
     * (ISO 32000-1, Table 76, #158), its /Background covers the region first
     * (Table 78 limits that to pattern fills, never `sh`, #155), and the
     * shading then paints clipped to its box. The pattern matrix maps to the
     * default space of the stream that paints, like the tiling path.
     */
    private fun paintShadingPattern(pat: KitePattern.Shading, region: KitePath, s: GraphicsState, evenOdd: Boolean, stroke: Boolean) {
        val ps = pat.extGState?.let { s.applyExtGState(it) } ?: s
        val alpha = if (stroke) ps.strokeAlpha else ps.fillAlpha
        pat.shading.background?.let { bg ->
            canvas.fillPath(region, s.ctm, bg, evenOdd, alpha = alpha, blendMode = ps.blendMode)
        }
        // The region is the path under the CTM at the paint operator (8.5.3.1). Only the
        // shading follows the pattern matrix, so the two cannot share one matrix (#93).
        canvas.pushClip(region, s.ctm, evenOdd)
        try {
            val shading = pat.shading.withIntent(ps.renderingIntent, ps.blackPointCompensation)
            fillShadingInBBox(shading, patternBaseCtm.concat(pat.matrix), null, alpha, ps.blendMode)
        } finally {
            canvas.popClip()
        }
    }

    /**
     * Fill [clipPath] with a tiling pattern (ISO 32000-1 §8.7.3): clip to the
     * region, then replay the pattern cell's content stream at every
     * `/XStep`,`/YStep` offset that intersects the region. The pattern matrix is
     * relative to the default space of the stream that paints. An uncoloured
     * pattern (PaintType 2) paints in [color], the colour the `scn` operands gave,
     * and its cell cannot set a colour of its own (ISO 32000-1, 8.7.3.3, #94).
     */
    private fun renderTilingPattern(
        pat: KitePattern.Tiling, clipPath: KitePath, s: GraphicsState, evenOdd: Boolean, alpha: Double,
        color: RgbColor,
    ) {
        val xs = pat.xStep
        val ys = pat.yStep
        if (xs == 0.0 || ys == 0.0) return
        val patternCtm = patternBaseCtm.concat(pat.matrix)
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
        val shadings = loadShadings(res, colorSpaces)
        val patterns = loadPatterns(res, shadings, colorSpaces)
        val properties = loadProperties(res)
        val ops = ContentStreamParser.parse(pat.contentBytes, colorSpaces)
        val uncolored = pat.paintType == 2
        // ISO 32000-1, 8.7.3.1, Table 75: the pattern's box clips each cell, so a
        // cell that draws past it cannot flood the fill (#95).
        val cellBox = KitePath.Builder().apply {
            rectangle(pat.bbox.left, pat.bbox.bottom, pat.bbox.right - pat.bbox.left, pat.bbox.top - pat.bbox.bottom)
        }.build()

        canvas.pushClip(clipPath, s.ctm, evenOdd)
        // The fill's alpha and blend mode apply once, to the whole pattern fill,
        // not again to each cell's own paints (#156).
        val groupBox = if (alpha < 1.0 || s.blendMode != KiteBlendMode.Normal) deviceBounds(clipPath, KiteMatrix.IDENTITY) else null
        if (groupBox != null) {
            canvas.beginTransparencyGroup(
                io.github.yuroyami.kitepdf.core.KiteRectangle(groupBox[0], groupBox[1], groupBox[2], groupBox[3]), s.ctm,
                isolated = true, knockout = false, alpha = alpha, blendMode = s.blendMode,
            )
        }
        val clipBase = activeClipCount
        // A pending W/W* belongs to the enclosing stream, not the tile cell.
        val savedPendingClip = pendingClip
        pendingClip = 0
        val savedPatternBase = patternBaseCtm
        val savedIgnore = type3IgnoreColor
        type3IgnoreColor = uncolored
        try {
            for (j in j0..j1) for (i in i0..i1) {
                val tileCtm = patternCtm.concat(KiteMatrix.translation(i * xs, j * ys))
                patternBaseCtm = tileCtm
                val tileState = GraphicsStack(
                    if (uncolored) GraphicsState(ctm = tileCtm, fillColor = color, strokeColor = color)
                    else GraphicsState(ctm = tileCtm),
                )
                canvas.pushClip(cellBox, tileCtm, false)
                activeClipCount++
                val tilePath = KitePath.Builder()
                val scope = openScope()
                try {
                    for (op in ops) dispatch(op, tileState, tilePath, fonts, xobjects, colorSpaces, extGStates, shadings, patterns, properties)
                } finally {
                    closeScope(scope, null)
                }
                // Drop any clips the tile's content left unbalanced.
                while (activeClipCount > clipBase) { canvas.popClip(); activeClipCount-- }
                pendingClip = 0
            }
        } finally {
            pendingClip = savedPendingClip
            patternBaseCtm = savedPatternBase
            type3IgnoreColor = savedIgnore
            if (groupBox != null) canvas.endTransparencyGroup()
            canvas.popClip()
        }
    }

    /** Axis-aligned device-space bounds of [path] under [ctm], or null if empty. */
    private fun deviceBounds(path: KitePath, ctm: KiteMatrix): DoubleArray? {
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
        val maskMatrix = missingAsNull { mask.group.dict.getArray("Matrix", resolver) }?.let { arr ->
            KiteMatrix(
                arr.getOrNull(0).toDouble(), arr.getOrNull(1).toDouble(),
                arr.getOrNull(2).toDouble(), arr.getOrNull(3).toDouble(),
                arr.getOrNull(4).toDouble(), arr.getOrNull(5).toDouble(),
            )
        } ?: KiteMatrix.IDENTITY
        // The mask lives in the coordinate system in force when gs set it, so a
        // later cm moves the content, never the mask (ISO 32000-1, 11.6.5.2, #67).
        val baseCtm = state.softMaskCtm ?: state.ctm
        val maskCtm = baseCtm.concat(maskMatrix)
        // /TR maps each value of the group to the mask value, and /BC is the backdrop
        // that a luminosity group composites over (ISO 32000-1, 11.6.5.2, Table 144, #68).
        val transfer = mask.transfer?.let { f ->
            maskTransfers.getOrPut(f) { KiteMaskTransfer.of { f.evaluate(doubleArrayOf(it))[0] } }
        }
        val backdrop = if (mask.kind == SoftMask.Kind.Luminosity) maskBackdrop(mask) else null
        // Where the group paints nothing, the mask value is that of the backdrop: black
        // for a luminosity mask, transparent for an alpha mask, then through /TR.
        val backdropLevel = kotlin.math.round((backdrop?.let { 0.30 * it.r + 0.59 * it.g + 0.11 * it.b } ?: 0.0) * 255)
            .toInt().let { transfer?.get(it) ?: it }
        // The box may be an indirect array (ISO 32000-1, 7.3.10). A box that
        // cannot be read must not cut the content away: it covers the page (#255).
        // A backdrop that lets the content through also covers the page.
        val groupBox = missingAsNull { mask.group.dict.getArray("BBox", resolver) }
            ?.takeIf { it.size >= 4 }?.let { arr ->
                io.github.yuroyami.kitepdf.core.KiteRectangle(
                    arr.getOrNull(0).toDouble(), arr.getOrNull(1).toDouble(),
                    arr.getOrNull(2).toDouble(), arr.getOrNull(3).toDouble(),
                )
            }
        val maskBBox = if (groupBox != null && (backdropLevel == 0 || pageCropBox == null)) groupBox else pageBoxIn(maskCtm)
        val renderMask = { childCanvas: KiteCanvas ->
            // A backend starts a luminosity mask on black, so a backdrop of another
            // colour paints over the whole page before the group does. Some backends
            // mask past the mask box, and there the backdrop must show too.
            if (backdrop != null) {
                val b = if (pageCropBox != null) pageBoxIn(maskCtm) else maskBBox
                val box = KitePath.Builder().apply { rectangle(b.left, b.bottom, b.right - b.left, b.top - b.bottom) }.build()
                childCanvas.fillPath(box, maskCtm, backdrop, evenOdd = false, alpha = 1.0, blendMode = KiteBlendMode.Normal)
            }
            // Recurse into the same renderer pipeline but onto whatever
            // canvas the backend handed us. The mask group's content
            // stream is rendered with a fresh graphics state. The spec
            // says soft masks render onto a transparent backdrop with
            // their own state stack (§11.6.5).
            renderMaskGroup(mask.group, childCanvas, baseCtm)
        }
        // Without a transfer function, the overload that every canvas and wrapper knows.
        // A wrapper that delegates with `by` sends the other overload past itself.
        if (transfer == null) {
            canvas.applySoftMask(mask.kind, maskBBox, maskCtm, paint, renderMask)
        } else {
            canvas.applySoftMask(mask.kind, maskBBox, maskCtm, transfer, paint, renderMask)
        }
    }

    /** The /BC colour of a luminosity mask in RGB, or null when it is black or absent. */
    private fun maskBackdrop(mask: SoftMask.MaskGroup): RgbColor? {
        val components = mask.backdrop ?: return null
        // The components are in the colour space of the group (ISO 32000-1, Table 144).
        val cs = missingAsNull { mask.group.dict.getDict("Group", resolver) }?.get("CS")
        val space = cs?.let { missingAsNull { KiteColorSpace.resolve(it, resolver) } } ?: when (components.size) {
            3 -> KiteColorSpace.DeviceRGB
            4 -> KiteColorSpace.DeviceCMYK
            else -> KiteColorSpace.DeviceGray
        }
        val rgb = space.toRgb(DoubleArray(space.componentCount) { components.getOrElse(it) { 0.0 } })
        return rgb.takeUnless { it.r <= 0.0 && it.g <= 0.0 && it.b <= 0.0 }
    }

    /**
     * True when a paint inside a form can blend in a mode other than Normal, so the isolation
     * of its group shows (ISO 32000-1, 11.4.5). With Normal paints alone, an isolated group
     * looks the same painted straight onto the page. Looks into nested forms, four deep.
     */
    private fun formBlends(extGStates: Map<String, ExtGState>, xobjects: Map<String, XObjectSlot>): Boolean {
        if (extGStates.values.any { it.blendMode != null && it.blendMode != KiteBlendMode.Normal }) return true
        return xobjects.values.any { slot -> formDictBlends(slot.stream.dict, depth = 1) }
    }

    /** [formBlends] from the raw dictionary of a nested form, so nothing is loaded for it. */
    private fun formDictBlends(dict: PdfDictionary, depth: Int): Boolean {
        if (depth > 4 || dict.getName("Subtype") != "Form") return false
        val resources = missingAsNull { dict.getDict("Resources", resolver) } ?: return false
        val states = missingAsNull { resources.getDict("ExtGState", resolver) }
        if (states != null && states.map.values.any { v -> (missingAsNull { v.resolve(resolver) } as? PdfDictionary)?.let(::blendsOtherThanNormal) == true }) {
            return true
        }
        val forms = missingAsNull { resources.getDict("XObject", resolver) } ?: return false
        return forms.map.values.any { v ->
            (missingAsNull { v.resolve(resolver) } as? io.github.yuroyami.kitepdf.core.parser.PdfStream)?.let { formDictBlends(it.dict, depth + 1) } == true
        }
    }

    /** True when the /BM of [gs], a name or an array of names, names a mode other than Normal. */
    private fun blendsOtherThanNormal(gs: PdfDictionary): Boolean {
        val bm = gs["BM"] ?: return false
        val names = when (bm) {
            is PdfName -> listOf(bm.value)
            is io.github.yuroyami.kitepdf.core.parser.PdfArray -> bm.items.mapNotNull { (it as? PdfName)?.value }
            else -> emptyList()
        }
        return names.any { it != "Normal" && it != "Compatible" }
    }

    /**
     * Draws [image] with the alpha and the blend mode of [s] (ISO 32000-1, 11.3.5). A Normal
     * image goes through the overload that every canvas and wrapper knows: a wrapper that
     * delegates with `by` sends the other overload past itself (#290).
     */
    private fun paintImage(image: KiteImageData, s: GraphicsState) {
        if (s.blendMode == KiteBlendMode.Normal) {
            canvas.drawImage(image, s.ctm, s.fillAlpha)
        } else {
            canvas.drawImage(image, s.ctm, s.fillAlpha, s.blendMode)
        }
    }

    /** The page crop box in the space [maskCtm] maps to the device, for a mask box that cannot be read. */
    private fun pageBoxIn(maskCtm: KiteMatrix): io.github.yuroyami.kitepdf.core.KiteRectangle {
        val crop = pageCropBox ?: return UNBOUNDED_MASK_BOX
        val toMask = maskCtm.invert()?.concat(pageBaseCtm) ?: return crop
        val xs = DoubleArray(4); val ys = DoubleArray(4)
        for ((i, corner) in listOf(crop.left to crop.bottom, crop.right to crop.bottom, crop.right to crop.top, crop.left to crop.top).withIndex()) {
            xs[i] = toMask.transformX(corner.first, corner.second)
            ys[i] = toMask.transformY(corner.first, corner.second)
        }
        return io.github.yuroyami.kitepdf.core.KiteRectangle(xs.min(), ys.min(), xs.max(), ys.max())
    }

    private fun renderMaskGroup(formStream: PdfStream, target: KiteCanvas, baseCtm: KiteMatrix) {
        // We need a sub-renderer so the mask paints into [target] rather
        // than the page canvas. The cleanest thing is to construct a
        // throwaway PageRenderer instance and let it run the form-xobject
        // pipeline; that reuses every operator handler and resource walk.
        // The state starts from the CALLER's CTM (device transform included:
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
            // With [/Pattern base], the operands before the name are the colour of an
            // uncoloured pattern, in the base space (ISO 32000-1, 8.7.3.3, #94).
            val space = if (stroke) state.current.strokeColorSpace else state.current.fillColorSpace
            val base = (space as? KiteColorSpace.Unsupported)?.patternBase
            val color = if (base != null && a.size > 1) base.toRgb(DoubleArray(a.size - 1) { num(a, it) }) else null
            state.replace(
                if (stroke) state.current.copy(strokePattern = pat, strokeColor = color ?: state.current.strokeColor)
                else state.current.copy(fillPattern = pat, fillColor = color ?: state.current.fillColor),
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
            if (stroke) state.current.copy(strokeColor = rgb, strokePattern = null, strokeComponents = comps)
            else state.current.copy(fillColor = rgb, fillPattern = null, fillComponents = comps),
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
            if (stroke) state.current.copy(strokeColor = rgb, strokePattern = null, strokeComponents = comps)
            else state.current.copy(fillColor = rgb, fillPattern = null, fillComponents = comps),
        )
    }

    /* ─── Text state machine ─────────────────────────────────────────────── */

    private fun moveText(state: GraphicsStack, tx: Double, ty: Double, setLeading: Boolean) {
        state.mutateText { t ->
            // ISO 32000-1 §9.4.2: Tlm_new = translate(tx,ty) × Tlm (row-vector form),
            // i.e. the offset is in UNSCALED TEXT SPACE. Apply the translation first,
            // then the line matrix, so its scale/rotation transform the offset. (concat
            // applies its argument first, so this is lineMatrix.concat(translation).)
            // The reverse order silently works only when Tm has unit scale; with the
            // font size baked into Tm (Tf size 1, scale in Tm) it collapsed line spacing.
            val moved = t.lineMatrix.concat(KiteMatrix.translation(tx, ty))
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

        // Text shown before any Tf uses the missing-font substitute as well.
        val font = t.font ?: missingFont

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
        val textToUser = textMatrix.concat(KiteMatrix(hScale, 0.0, 0.0, 1.0, 0.0, t.rise))
        val finalMatrix = pageMatrix.concat(textToUser)

        // Type3 fonts draw by replaying char-proc content streams.
        type3Data[font]?.let { data ->
            showTextType3(state, font, bytes, t, data, textToUser)
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
        // (§9.3.3). Mode 7 clips without painting.
        val doClip = mode >= 4
        // ONE glyph layout per run: fill, stroke, clip and the Tm
        // advance all read the same list. Outlines are resolved only when
        // something below actually consumes them.
        val hidden = ocHidden()
        val resolveOutlines = !hidden &&
            ((doFill && canvas.resolvesGlyphOutlines) ||
                ((doStroke || doClip) && font.hasOutlines))
        val laidOut = font.layoutBytes(bytes, resolveOutlines)
        // §9.4.4: per-glyph displacement includes Tc, and Tw on single-byte 0x20
        // (layoutBytes already encodes that rule in isWordSpace). Bake both into
        // the glyphs so every pen loop downstream applies them uniformly.
        val glyphs = if (t.charSpacing == 0.0 && t.wordSpacing == 0.0) laidOut
        else laidOut.map {
            it.copy(advanceAdjust = t.charSpacing + (if (it.isWordSpace) t.wordSpacing else 0.0))
        }
        // Writing mode 1 stacks the glyphs down the page, each from its own origin (9.7.4.3).
        val vertical = font.verticalMetrics(bytes)?.takeIf { it.size == glyphs.size && it.isNotEmpty() }
            ?.let { verticalRun(it, glyphs, t, textMatrix, hScale) }

        // One run to the canvas. A vertical run goes glyph by glyph to a canvas that
        // paints, and as one column to a canvas that reads text, so its line runs down.
        fun drawRun(color: RgbColor, alpha: Double, unitsPerEm: Int, hasOutlines: Boolean) {
            when {
                vertical == null -> canvas.drawGlyphs(
                    glyphs, t.fontSize, unitsPerEm, hasOutlines, font.fontSpec, finalMatrix, color,
                    alpha = alpha, blendMode = state.current.blendMode,
                )
                canvas.resolvesGlyphOutlines -> for ((i, glyph) in glyphs.withIndex()) {
                    canvas.drawGlyphs(
                        listOf(glyph), t.fontSize, unitsPerEm, hasOutlines, font.fontSpec,
                        pageMatrix.concat(vertical.placements[i]), color,
                        alpha = alpha, blendMode = state.current.blendMode,
                    )
                }
                else -> canvas.drawGlyphs(
                    vertical.column, t.fontSize, unitsPerEm, hasOutlines, font.fontSpec,
                    pageMatrix.concat(vertical.columnMatrix), color,
                    alpha = alpha, blendMode = state.current.blendMode,
                )
            }
        }

        if (!hidden) {
            // A pattern fills the glyph shapes, as it fills a path (ISO 32000-1, 8.7.3.1 and 9.3.6).
            val fillPattern = state.current.fillPattern
            val patternFill = doFill && canvas.resolvesGlyphOutlines &&
                (fillPattern is KitePattern.Shading || fillPattern is KitePattern.Tiling)
            // A font without a program strokes, clips and fills with a pattern through the
            // outlines of the host face that stands in for it (ISO 32000-1, 9.3.6 and 9.6.2.2, #85).
            val hostShapes = if ((doStroke || doClip || patternFill) && !font.hasOutlines) hostOutlined(glyphs, font) else null
            val shapes = hostShapes ?: glyphs
            val shapeUnits = if (hostShapes != null) HOST_UNITS_PER_EM else font.unitsPerEm ?: 1000
            val placements = vertical?.placements
            if (doClip) accumulateTextClip(shapes, font, t, textToUser, state.current.ctm, shapeUnits, placements)
            if (doFill && !state.current.fillColorSpace.paintsNothing && fillPattern !is KitePattern.Unsupported) {
                // Without any glyph shape, the run keeps the colour the pattern falls back to.
                val shapePath = if (patternFill) glyphShapes(shapes, t, textToUser, shapeUnits, placements) else null
                if (shapePath != null) {
                    paintFill(shapePath, state, evenOdd = false)
                } else {
                    withSoftMask(state.current) {
                        drawRun(state.current.fillColor, state.current.fillAlpha, font.unitsPerEm ?: 1000, font.hasOutlines)
                    }
                }
            } else if (!canvas.resolvesGlyphOutlines) {
                // A canvas that reads text gets the runs that fill nothing too: an OCR layer
                // (mode 3), outlined and clipping text are text all the same (9.3.6, #274).
                drawRun(state.current.fillColor, state.current.fillAlpha, font.unitsPerEm ?: 1000, font.hasOutlines)
            }
            if (doStroke && !state.current.strokeColorSpace.paintsNothing) {
                if (font.hasOutlines || hostShapes != null) {
                    strokeTextGlyphs(state, t, shapes, shapeUnits, textToUser, placements)
                } else if (!doFill && canvas.resolvesGlyphOutlines) {
                    // This canvas has no host outlines, and a filled run beats a blank one.
                    withSoftMask(state.current) {
                        drawRun(state.current.strokeColor, state.current.strokeAlpha, HOST_UNITS_PER_EM, false)
                    }
                }
            }
        }

        // Advance Tm by the total width of this run. The advance is in text space,
        // so translate first then apply the text matrix (see moveText), otherwise a
        // size-in-Tm run advances in output space and the next run on the line overlaps.
        val move = if (vertical != null) KiteMatrix.translation(0.0, vertical.displacement)
        else KiteMatrix.translation(totalAdvance(glyphs, t), 0.0)
        state.mutateText { it.copy(textMatrix = it.textMatrix.concat(move)) }
    }

    /**
     * One run in vertical writing (ISO 32000-1, 9.7.4.3), placed as MuPDF places it.
     * [placements] maps text space to user space for each glyph, with the glyph's
     * horizontal origin at the pen minus its position vector. [displacement] is how far
     * the run moves the pen down the column. [column] and [columnMatrix] are the same
     * run for a canvas that reads text: one line down the page, each glyph advancing by
     * its vertical displacement along a matrix turned a quarter turn clockwise.
     */
    private class VerticalRun(
        val placements: List<KiteMatrix>,
        val displacement: Double,
        val column: List<TextGlyph>,
        val columnMatrix: KiteMatrix,
    )

    private fun verticalRun(
        metrics: List<PdfVerticalMetrics>,
        glyphs: List<TextGlyph>,
        t: TextState,
        textMatrix: KiteMatrix,
        hScale: Double,
    ): VerticalRun {
        // 9.4.4: ty = w1y x Tfs + Tc (+ Tw), and Th applies to tx only, so neither the
        // pen nor the origin shift scales with Tz. The glyph itself still does.
        val size = t.fontSize / 1000.0
        val glyphScale = KiteMatrix(hScale, 0.0, 0.0, 1.0, 0.0, t.rise)
        val placements = ArrayList<KiteMatrix>(glyphs.size)
        var penY = 0.0
        for ((i, glyph) in glyphs.withIndex()) {
            val m = metrics[i]
            val origin = KiteMatrix.translation(-m.originX * kotlin.math.abs(size), penY - m.originY * size)
            placements.add(textMatrix.concat(origin).concat(glyphScale))
            penY += m.displacement * size + glyph.advanceAdjust
        }
        // The reading line sits 0.3 em left of the first glyph's centre line, so a
        // reader's usual 0.2 em descent and 0.8 em ascent cover the column evenly.
        val first = metrics[0]
        val lineX = (glyphs[0].advanceWidth * hScale / 2.0 - first.originX) * size - 0.3 * t.fontSize
        val column = glyphs.mapIndexed { i, glyph ->
            glyph.copy(advanceWidth = -metrics[i].displacement, advanceAdjust = -glyph.advanceAdjust)
        }
        val columnMatrix = textMatrix.concat(KiteMatrix.translation(lineX, t.rise)).concat(QUARTER_TURN_CLOCKWISE)
        return VerticalRun(placements, penY, column, columnMatrix)
    }

    /**
     * The outlines of [glyphs] in user space as one path, placed the way
     * [strokeTextGlyphs] places them, or null when no glyph has an outline.
     */
    private fun glyphShapes(
        glyphs: List<TextGlyph>,
        t: TextState,
        textToUser: KiteMatrix,
        unitsPerEm: Int,
        /** Per-glyph text-to-user matrices of a vertical run, which replace the pen. */
        placements: List<KiteMatrix>?,
    ): KitePath.Builder? {
        val unitScale = t.fontSize / unitsPerEm
        val advanceScale = t.fontSize / 1000.0
        val builder = KitePath.Builder()
        var penX = 0.0
        for ((i, glyph) in glyphs.withIndex()) {
            val outline = glyph.outline
            if (outline != null && !outline.isEmpty()) {
                val toUser = if (placements != null) glyphToUser(placements[i], 0.0, glyph, unitScale)
                else glyphToUser(textToUser, penX, glyph, unitScale)
                appendPath(builder, transformPath(outline, toUser))
            }
            penX += glyph.advanceWidth * advanceScale + glyph.advanceAdjust
        }
        return builder.takeUnless { it.isEmpty() }
    }

    /**
     * Stroke the glyph outlines for the current run (render modes 1/2/5/6).
     * Builds each glyph's outline into user space (glyph units → unitScale →
     * pen advance → [textToUser]) and strokes it under s.ctm with the current
     * stroke colour/width, so modes 1/2 actually stroke rather than falling back
     * to a plain fill. A font without embedded outlines arrives with the host
     * outlines [hostOutlined] gave it, at [unitsPerEm] 1000.
     */
    private fun strokeTextGlyphs(
        state: GraphicsStack,
        t: TextState,
        /** The run's glyphs, laid out ONCE by [showText] (outlines resolved). */
        glyphs: List<TextGlyph>,
        unitsPerEm: Int,
        textToUser: KiteMatrix,
        /** Per-glyph text-to-user matrices of a vertical run, which replace the pen. */
        placements: List<KiteMatrix>? = null,
    ) {
        val s = state.current
        when (val pat = s.strokePattern) {
            null -> Unit
            // The pattern fills the stroke of every glyph at once, as it does for a path (#284).
            is KitePattern.Shading, is KitePattern.Tiling -> {
                val shapes = glyphShapes(glyphs, t, textToUser, unitsPerEm, placements) ?: return
                withSoftMask(s) { strokeWithPattern(pat, shapes.build(), s) }
                return
            }
            // An unsupported pattern paints nothing, as for a path, rather than a stale colour.
            else -> return
        }
        val unitScale = t.fontSize / unitsPerEm
        val advanceScale = t.fontSize / 1000.0
        var penX = 0.0
        // Build each glyph outline into USER space (glyph units → unitScale →
        // pen advance → text-to-user), then stroke it with s.ctm so the stroke
        // width scales by the CTM only, the pure user-space width the spec wants.
        for ((i, glyph) in glyphs.withIndex()) {
            val outline = glyph.outline
            if (outline != null && !outline.isEmpty()) {
                val toUser = if (placements != null) glyphToUser(placements[i], 0.0, glyph, unitScale)
                else glyphToUser(textToUser, penX, glyph, unitScale)
                val userPath = transformPath(outline, toUser)
                withSoftMask(s) {
                    canvas.strokePath(
                        userPath, s.ctm, s.strokeColor, s.lineWidth,
                        alpha = s.strokeAlpha, blendMode = s.blendMode,
                        dashArray = s.dashArray, dashPhase = s.dashPhase,
                        lineCap = s.lineCap, lineJoin = s.lineJoin, miterLimit = s.miterLimit,
                    )
                }
            }
            penX += glyph.advanceWidth * advanceScale + glyph.advanceAdjust
        }
    }

    /**
     * Modes 4..7: add this run's glyph shapes to [pendingTextClip] in device
     * space, so a matrix change before ET cannot move the clip (#146). A font
     * with no embedded outlines adds the host outlines [hostOutlined] gave its
     * glyphs, or an em box per glyph on a canvas without them, since a clip
     * that is too big beats clipping everything away. An empty outline, such
     * as a space's, adds nothing (ISO 32000-1, 9.3.6, #87).
     */
    private fun accumulateTextClip(
        glyphs: List<TextGlyph>,
        font: PdfFont,
        t: TextState,
        textToUser: KiteMatrix,
        ctm: KiteMatrix,
        unitsPerEm: Int,
        /** Per-glyph text-to-user matrices of a vertical run, which replace the pen. */
        placements: List<KiteMatrix>? = null,
    ) {
        val builder = pendingTextClip ?: KitePath.Builder().also { pendingTextClip = it }
        val unitScale = t.fontSize / unitsPerEm
        val advanceScale = t.fontSize / 1000.0
        val textToDevice = ctm.concat(textToUser)
        var penX = 0.0
        for ((i, glyph) in glyphs.withIndex()) {
            // A vertical run places each glyph's horizontal origin itself; otherwise the pen does.
            val placement = placements?.let { ctm.concat(it[i]) }
            val outline = glyph.outline
            if (outline != null) {
                if (!outline.isEmpty()) {
                    val toDevice = if (placement != null) glyphToUser(placement, 0.0, glyph, unitScale)
                    else glyphToUser(textToDevice, penX, glyph, unitScale)
                    appendPath(builder, transformPath(outline, toDevice))
                }
            } else if (!font.hasOutlines && glyph.advanceWidth > 0.0) {
                val w = glyph.advanceWidth * advanceScale
                val box = KitePath.Builder().apply {
                    rectangle(0.0, -0.2 * t.fontSize, w, t.fontSize)
                }.build()
                appendPath(builder, transformPath(box, placement ?: textToDevice.concat(KiteMatrix.translation(penX, 0.0))))
            }
            penX += glyph.advanceWidth * advanceScale + glyph.advanceAdjust
        }
    }

    /**
     * [glyphs] with the outlines of the host face [canvas] draws [font] in, at
     * [HOST_UNITS_PER_EM]. A blank glyph gets an empty outline. Null on a canvas
     * that has no host outlines or does not paint.
     */
    private fun hostOutlined(glyphs: List<TextGlyph>, font: PdfFont): List<TextGlyph>? {
        if (!canvas.resolvesGlyphOutlines) return null
        return glyphs.map { glyph ->
            val outline = if (glyph.text.isBlank()) EMPTY_OUTLINE
            else canvas.hostGlyphOutline(glyph.text, font.fontSpec) ?: return null
            glyph.copy(outline = outline)
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
    private fun transformPath(path: KitePath, m: KiteMatrix): KitePath {
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

    /**
     * TJ numeric adjustment: shift the text-cursor by [thousandthsOfEm] of em. In
     * vertical writing it moves down the column, with no horizontal scaling (9.4.3).
     */
    private fun adjustTextX(state: GraphicsStack, thousandthsOfEm: Double) {
        val t = state.current.text
        val shift = thousandthsOfEm / 1000.0 * t.fontSize
        val move = if (t.font?.isVertical == true) KiteMatrix.translation(0.0, shift)
        else KiteMatrix.translation(shift * (t.horizontalScaling / 100.0), 0.0)
        state.mutateText {
            // Text-space offset: translate first, then the text matrix (see moveText).
            it.copy(textMatrix = it.textMatrix.concat(move))
        }
    }

    /**
     * Sum of per-glyph advances, including Tc/Tw/Th adjustments, from the run's
     * already-laid-out [glyphs]. Tc/Tw arrive baked into
     * [TextGlyph.advanceAdjust] by [showText], so this and every canvas pen
     * loop advance by the same per-glyph amount.
     */
    private fun totalAdvance(glyphs: List<TextGlyph>, t: TextState): Double {
        var advance = 0.0
        val sizeFactor = t.fontSize / 1000.0
        val hScale = t.horizontalScaling / 100.0
        for (g in glyphs) {
            advance += (g.advanceWidth * sizeFactor + g.advanceAdjust) * hScale
        }
        return advance
    }

    /**
     * Show a text run in a Type3 font. Each byte's glyph is a content
     * stream replayed like a small form XObject under
     * `CTM x textToUser x pen x fontSize x FontMatrix`, with the font's own
     * /Resources (a char proc with no /Resources of its own reads the
     * page's, as the spec asks). The pen advances by
     * `width x FontMatrix.a x fontSize` plus
     * Tc/Tw, matching §9.6.5's glyph-space widths.
     */
    private fun showTextType3(
        state: GraphicsStack,
        font: PdfFont,
        bytes: ByteArray,
        t: TextState,
        data: Type3Data,
        textToUser: KiteMatrix,
    ) {
        val hidden = ocHidden()
        // ISO 32000-2, 9.3.6: render modes 3 and 7 draw no Type 3 glyph, though the
        // pen still advances (#86).
        val invisible = t.renderingMode == 3 || t.renderingMode == 7
        // A canvas that reads text cannot read the glyph drawings, so it gets the run as text.
        val readsText = !canvas.resolvesGlyphOutlines
        if (readsText && !hidden) reportType3Text(state, font, bytes, t, data, textToUser)
        var penX = 0.0
        for (b in bytes) {
            val code = b.toInt() and 0xFF
            val proc = data.nameForCode[code]?.let { data.charProcs[it] }
            if (!hidden && !invisible && !readsText && proc != null && formDepth < MAX_FORM_DEPTH) {
                formDepth++
                try {
                    val glyphToUser = textToUser
                        .concat(KiteMatrix.translation(penX, 0.0))
                        .concat(KiteMatrix.scaling(t.fontSize, t.fontSize))
                        .concat(data.fontMatrix)
                    replayType3Proc(proc, data.charProcObjects[data.nameForCode[code]], data, state, glyphToUser)
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
            it.copy(textMatrix = it.textMatrix.concat(KiteMatrix.translation(penX * hScale, 0.0)))
        }
    }

    /**
     * Hands a Type 3 run to a canvas that reads text, with each glyph's text from the
     * font's encoding or `/ToUnicode` and its advance from `/Widths` and `/FontMatrix`,
     * in thousandths of an em (ISO 32000-1, 9.6.5, #274).
     */
    private fun reportType3Text(
        state: GraphicsStack,
        font: PdfFont,
        bytes: ByteArray,
        t: TextState,
        data: Type3Data,
        textToUser: KiteMatrix,
    ) {
        val glyphs = font.layoutBytes(bytes, false).map { glyph ->
            val code = bytes.getOrNull(glyph.byteOffset)?.toInt()?.and(0xFF) ?: 0
            glyph.copy(
                advanceWidth = data.widthFor(code) * data.fontMatrix.a * 1000.0,
                advanceAdjust = t.charSpacing + (if (glyph.isWordSpace) t.wordSpacing else 0.0),
            )
        }
        canvas.drawGlyphs(
            glyphs, t.fontSize, 1000, false, font.fontSpec, state.current.ctm.concat(textToUser),
            state.current.fillColor, alpha = state.current.fillAlpha, blendMode = state.current.blendMode,
        )
    }

    private fun replayType3Proc(
        proc: PdfStream,
        procObject: Long?,
        data: Type3Data,
        parentState: GraphicsStack,
        glyphToUser: KiteMatrix,
    ) {
        val res = data.resources ?: pageResources
        val fonts = loadFonts(res)
        val xobjects = loadXObjects(res)
        val colorSpaces = loadColorSpaces(res)
        val sh = loadShadings(res, colorSpaces)
        val extGStates = loadExtGStates(res)
        val patterns = loadPatterns(res, sh, colorSpaces)
        val properties = loadProperties(res)

        parentState.save()
        parentState.replace(parentState.current.copy(
            ctm = parentState.current.ctm.concat(glyphToUser),
        ))
        val savedPatternBase = patternBaseCtm
        patternBaseCtm = parentState.current.ctm
        val savedPendingClip = pendingClip
        pendingClip = 0
        val savedIgnore = type3IgnoreColor
        type3IgnoreColor = false // each proc decides via its own d1
        type3Depth++
        val clipBase = activeClipCount
        val scope = openScope()
        try {
            val parse = { ContentStreamParser.parse(io.github.yuroyami.kitepdf.core.filters.FilterChain.decode(proc), colorSpaces) }
            // A glyph drawn many times parses once, when its font has resources of its own (#118).
            val ops = if (data.resources != null) cachedOperations(procObject, parse) else parse()
            val pathBuilder = KitePath.Builder()
            for (op in ops) dispatch(op, parentState, pathBuilder, fonts, xobjects, colorSpaces, extGStates, sh, patterns, properties)
        } finally {
            closeScope(scope, parentState)
            type3IgnoreColor = savedIgnore
            type3Depth--
            patternBaseCtm = savedPatternBase
            pendingClip = savedPendingClip
            while (activeClipCount > clipBase) { canvas.popClip(); activeClipCount-- }
            parentState.restore()
        }
    }

    /**
     * What a content stream may leave unbalanced and ISO 32000-1 scopes to it:
     * `q` (8.4.4) and marked content (8.11.3.2). A nested stream, whether a
     * form, a tiling cell or a Type 3 glyph, opens a scope, and closing it undoes
     * whatever the stream left open, so none of it reaches the stream that
     * invoked it (#47, #48, #50, #51).
     */
    private class StreamScope(
        val clipSaves: Int, val clipSaveFloor: Int,
        val marked: Int, val markedFloor: Int, val markedOverflow: Int, val hidden: Int,
    )

    private fun openScope(): StreamScope {
        val scope = StreamScope(
            clipSaveStack.size, clipSaveFloor,
            markedContentStack.size, markedContentFloor, markedContentOverflow, ocHiddenDepth,
        )
        clipSaveFloor = clipSaveStack.size
        markedContentFloor = markedContentStack.size
        markedContentOverflow = 0
        return scope
    }

    /** Closes [scope], popping the frames of [state] that its unmatched `q`s pushed. */
    private fun closeScope(scope: StreamScope, state: GraphicsStack?) {
        while (clipSaveStack.size > scope.clipSaves) {
            clipSaveStack.removeLast()
            state?.restore()
        }
        clipSaveFloor = scope.clipSaveFloor
        while (markedContentStack.size > scope.marked) markedContentStack.removeLast()
        markedContentFloor = scope.markedFloor
        markedContentOverflow = scope.markedOverflow
        ocHiddenDepth = scope.hidden
    }

    /* ─── Helpers ────────────────────────────────────────────────────────── */

    private fun num(list: List<PdfObject>, idx: Int): Double = when (val v = list.getOrNull(idx)) {
        is PdfInt -> v.value.toDouble()
        is PdfReal -> v.value
        else -> 0.0
    }

    /**
     * Look up a /ColorSpace name from a Resources entry; fall back to device families. A
     * device family takes the default for it from [dict] (ISO 32000-1, 8.6.5.6).
     */
    private fun namedColorSpace(name: String, dict: Map<String, KiteColorSpace>): KiteColorSpace =
        when (name) {
            "DeviceGray", "G" -> DefaultColorSpaces.substitute(KiteColorSpace.DeviceGray, dict)
            "DeviceRGB", "RGB" -> DefaultColorSpaces.substitute(KiteColorSpace.DeviceRGB, dict)
            "DeviceCMYK", "CMYK" -> DefaultColorSpaces.substitute(KiteColorSpace.DeviceCMYK, dict)
            else -> dict[name] ?: KiteColorSpace.DeviceGray
        }

    /** The device family that the colour operator [operator] (g, rg, k or a stroking form) selects. */
    private fun deviceSpaceOf(operator: String): KiteColorSpace = when (operator) {
        "g", "G" -> KiteColorSpace.DeviceGray
        "rg", "RG" -> KiteColorSpace.DeviceRGB
        else -> KiteColorSpace.DeviceCMYK
    }

    /** The colour of the operands [a] in [space]. The device families keep their direct forms. */
    /**
     * The operands of a colour operator in [space], which [GraphicsState.withColorRendering]
     * converts again. A device space converts alike for every intent, so it keeps none.
     */
    private fun components(space: KiteColorSpace, a: List<PdfObject>): DoubleArray? = when (space) {
        KiteColorSpace.DeviceGray, KiteColorSpace.DeviceRGB, KiteColorSpace.DeviceCMYK -> null
        else -> DoubleArray(a.size) { num(a, it) }
    }

    /**
     * The intent an image converts through: its own `/Intent` when it has one, else the one
     * of the graphics state, as MuPDF chooses (ISO 32000-1, 8.6.5.8 and Table 89).
     */
    private fun imageIntent(dict: PdfDictionary, s: GraphicsState): KiteRenderingIntent =
        (dict["Intent"] as? PdfName)?.let { KiteRenderingIntent.fromPdfName(it.value) } ?: s.renderingIntent

    private fun deviceColor(space: KiteColorSpace, a: List<PdfObject>): RgbColor = when (space) {
        KiteColorSpace.DeviceGray -> RgbColor.gray(num(a, 0))
        KiteColorSpace.DeviceRGB -> RgbColor(num(a, 0), num(a, 1), num(a, 2))
        else -> space.toRgb(DoubleArray(space.componentCount) { num(a, it) })
    }

    private companion object {
        /** Stands for "the live form state hides this widget", which is not "it has no appearance". */
        val HIDDEN_WIDGET = PdfStream(PdfDictionary(emptyMap()), ByteArray(0))

        /** The glyph space of [KiteCanvas.hostGlyphOutline] outlines. */
        const val HOST_UNITS_PER_EM = 1000

        /** Turns the x axis to point down the page: a reading line for a vertical column. */
        val QUARTER_TURN_CLOCKWISE = KiteMatrix(0.0, -1.0, 1.0, 0.0, 0.0, 0.0)

        /** The outline of a blank glyph: it strokes and clips nothing. */
        val EMPTY_OUTLINE: KitePath = KitePath.Builder().build()

        /** Safety cap on tiling-pattern tile count to bound adversarial inputs. */
        const val MAX_TILES = 20_000L
        /** Max Form-XObject nesting depth before bailing (recursion guard). */
        const val MAX_FORM_DEPTH = 15

        /** Marked-content nesting stored per page, the same bound as the graphics state (#166). */
        const val MAX_MARKED_CONTENT_DEPTH = 4096

        /** A soft mask box that bounds nothing, for a group whose /BBox cannot be read (#255). */
        private val UNBOUNDED_MASK_BOX = io.github.yuroyami.kitepdf.core.KiteRectangle(-1.0e7, -1.0e7, 1.0e7, 1.0e7)

        /** Nesting of an OCMD visibility expression before it counts as visible (#55). */
        const val MAX_VISIBILITY_DEPTH = 32

        /** Colour operators ignored inside a d1 (uncolored) Type3 glyph. */
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

/**
 * Where one glyph's outline lands in user space: the pen position plus the
 * glyph's own offset in font units, the way every canvas places a filled
 * glyph (#141).
 */
internal fun glyphToUser(textToUser: KiteMatrix, penX: Double, glyph: TextGlyph, unitScale: Double): KiteMatrix =
    textToUser
        .concat(KiteMatrix.translation(penX + glyph.xOffset * unitScale, glyph.yOffset * unitScale))
        .concat(KiteMatrix(unitScale, 0.0, 0.0, unitScale, 0.0, 0.0))
