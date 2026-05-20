package com.yuroyami.kitepdf.render

import com.yuroyami.kitepdf.PdfPage
import com.yuroyami.kitepdf.content.ContentStreamParser
import com.yuroyami.kitepdf.content.Operation
import com.yuroyami.kitepdf.font.PdfFont
import com.yuroyami.kitepdf.parser.IndirectResolver
import com.yuroyami.kitepdf.parser.PdfArray
import com.yuroyami.kitepdf.parser.PdfDictionary
import com.yuroyami.kitepdf.parser.PdfInt
import com.yuroyami.kitepdf.parser.PdfName
import com.yuroyami.kitepdf.parser.PdfObject
import com.yuroyami.kitepdf.parser.PdfReal
import com.yuroyami.kitepdf.parser.PdfStream
import com.yuroyami.kitepdf.parser.PdfString

/**
 * The content-stream interpreter — translates parsed [Operation]s into
 * `PdfCanvas` draw calls while maintaining the full PDF graphics-state stack
 * (ISO 32000-1 §8 + §9).
 *
 * Architecture mirrors MuPDF's pdf_processor / pdf_op_run.c:
 *   1. Walk operations one by one.
 *   2. Mutate the [GraphicsStack] for state-changing ops.
 *   3. Accumulate path construction in a [PdfPath.Builder].
 *   4. On paint operators, hand the path off to the device.
 *   5. Inside `BT…ET`, run the text state machine (Tm/Tlm/Tj/TJ/'/" etc.).
 *
 * The interpreter is *single-pass and stateless w.r.t. previous pages*: every
 * call to [render] starts with a fresh state stack.
 */
class PageRenderer(
    private val canvas: PdfCanvas,
    private val resolver: IndirectResolver,
) {

    // W/W* push a clip on the canvas, but the canvas keeps its own clip stack
    // separate from the PDF q/Q graphics-state stack. Track how many clips are
    // active so Q can pop exactly the ones pushed since its matching q —
    // otherwise clips leak past Q and can wrongly clip the rest of the page.
    private var activeClipCount = 0
    private val clipSaveStack = ArrayDeque<Int>()

    fun render(page: PdfPage, deviceCtm: Matrix = defaultDeviceCtm(page)) {
        val fonts = loadFonts(page.resources)
        val xobjects = loadXObjects(page.resources)
        val colorSpaces = loadColorSpaces(page.resources)
        val extGStates = loadExtGStates(page.resources)
        val shadings = loadShadings(page.resources)
        val patterns = loadPatterns(page.resources, shadings)
        val state = GraphicsStack(GraphicsState(ctm = deviceCtm))
        activeClipCount = 0
        clipSaveStack.clear()
        val pathBuilder = PdfPath.Builder()
        val ops = ContentStreamParser.parse(page.contentBytes)

        canvas.beginPage(page.width, page.height, deviceCtm)
        try {
            for (op in ops) dispatch(op, state, pathBuilder, fonts, xobjects, colorSpaces, extGStates, shadings, patterns)
            renderAnnotations(page, state)
        } finally {
            canvas.endPage()
        }
    }

    /** Named shadings declared in /Resources /Shading. */
    private fun loadShadings(resources: PdfDictionary?): Map<String, PdfShading> {
        val dict = resources?.getDict("Shading", resolver) ?: return emptyMap()
        return dict.map.mapNotNull { (name, value) ->
            val sh = PdfShading.parse(value, resolver) ?: return@mapNotNull null
            name to sh
        }.toMap()
    }

    /**
     * Named patterns declared in /Resources /Pattern. PatternType 2
     * (shading pattern) is the case KitePDF renders today; PatternType 1
     * (tiling pattern) parses to [PdfPattern.Tiling] but the renderer
     * still falls back to its background colour.
     */
    private fun loadPatterns(
        resources: PdfDictionary?,
        shadings: Map<String, PdfShading>,
    ): Map<String, PdfPattern> {
        val dict = resources?.getDict("Pattern", resolver) ?: return emptyMap()
        return dict.map.mapNotNull { (name, value) ->
            val p = PdfPattern.parse(value, resolver, shadings) ?: return@mapNotNull null
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
    private fun renderAnnotations(page: com.yuroyami.kitepdf.PdfPage, state: GraphicsStack) {
        for (annot in page.annotations) {
            val stream = annot.appearanceStream
            if (stream != null) {
                renderAppearanceForRect(stream, annot.rect, state)
            } else {
                renderAnnotationPlaceholder(annot, state)
            }
        }
    }

    /**
     * Map a Form XObject appearance to fill the annotation's /Rect. We compute
     * the affine that takes the appearance /BBox to the annotation /Rect (spec
     * §12.5.5, Algorithm 14) and concatenate it into the CTM.
     */
    private fun renderAppearanceForRect(
        appearance: PdfStream,
        rect: com.yuroyami.kitepdf.Rectangle,
        state: GraphicsStack,
    ) {
        val bbox = appearance.dict.getArray("BBox")?.let { arr ->
            com.yuroyami.kitepdf.Rectangle(
                arr.getOrNull(0).toDouble(), arr.getOrNull(1).toDouble(),
                arr.getOrNull(2).toDouble(), arr.getOrNull(3).toDouble(),
            )
        } ?: com.yuroyami.kitepdf.Rectangle(0.0, 0.0, rect.width, rect.height)

        val sx = if (bbox.width != 0.0) rect.width / bbox.width else 1.0
        val sy = if (bbox.height != 0.0) rect.height / bbox.height else 1.0
        val mapping = Matrix(sx, 0.0, 0.0, sy, rect.left - bbox.left * sx, rect.bottom - bbox.bottom * sy)

        state.save()
        state.replace(state.current.copy(ctm = state.current.ctm.concat(mapping)))
        try {
            renderFormXObject(appearance, state)
        } finally {
            state.restore()
        }
    }

    private fun renderAnnotationPlaceholder(
        annot: com.yuroyami.kitepdf.PdfAnnotation,
        state: GraphicsStack,
    ) {
        val rect = annot.rect
        val rectPath = PdfPath.Builder().apply {
            rectangle(rect.left, rect.bottom, rect.width, rect.height)
        }.build()
        when (annot.subtype) {
            com.yuroyami.kitepdf.PdfAnnotation.Subtype.Highlight -> {
                // Yellow translucent fill.
                canvas.fillPath(rectPath, state.current.ctm, annot.color ?: RgbColor(1.0, 1.0, 0.0), false)
            }
            com.yuroyami.kitepdf.PdfAnnotation.Subtype.Underline,
            com.yuroyami.kitepdf.PdfAnnotation.Subtype.StrikeOut -> {
                val line = PdfPath.Builder().apply {
                    val y = if (annot.subtype == com.yuroyami.kitepdf.PdfAnnotation.Subtype.Underline) rect.bottom else (rect.bottom + rect.height / 2)
                    moveTo(rect.left, y); lineTo(rect.left + rect.width, y)
                }.build()
                canvas.strokePath(line, state.current.ctm, annot.color ?: RgbColor.BLACK, 1.0)
            }
            com.yuroyami.kitepdf.PdfAnnotation.Subtype.Link -> {
                // Thin border so the user can see the clickable area.
                canvas.strokePath(rectPath, state.current.ctm, annot.color ?: RgbColor(0.0, 0.3, 0.8), 0.5)
            }
            else -> { /* other annotations: render nothing without /AP */ }
        }
    }

    /** Named colour spaces declared in /Resources /ColorSpace. */
    private fun loadColorSpaces(resources: PdfDictionary?): Map<String, ColorSpace> {
        val csDict = resources?.getDict("ColorSpace", resolver) ?: return emptyMap()
        return csDict.map.mapValues { (_, value) -> ColorSpace.resolve(value, resolver) }
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
    ) {
        val formMatrix = formStream.dict.getArray("Matrix")?.let { arr ->
            Matrix(
                arr.getOrNull(0).toDouble(), arr.getOrNull(1).toDouble(),
                arr.getOrNull(2).toDouble(), arr.getOrNull(3).toDouble(),
                arr.getOrNull(4).toDouble(), arr.getOrNull(5).toDouble(),
            )
        } ?: Matrix.IDENTITY
        val bbox = formStream.dict.getArray("BBox")?.let { arr ->
            com.yuroyami.kitepdf.Rectangle(
                arr.getOrNull(0).toDouble(), arr.getOrNull(1).toDouble(),
                arr.getOrNull(2).toDouble(), arr.getOrNull(3).toDouble(),
            )
        } ?: com.yuroyami.kitepdf.Rectangle(0.0, 0.0, 1000.0, 1000.0)
        val resources = formStream.dict.getDict("Resources", resolver)
        val childFonts = loadFonts(resources)
        val childXObjects = loadXObjects(resources)
        val childColorSpaces = loadColorSpaces(resources)
        val childExtGStates = loadExtGStates(resources)
        val childShadings = loadShadings(resources)
        val childPatterns = loadPatterns(resources, childShadings)
        val groupDict = formStream.dict.getDict("Group", resolver)
        val isTransparencyGroup = groupDict?.getName("S") == "Transparency"
        val isolated = (groupDict?.get("I") as? com.yuroyami.kitepdf.parser.PdfBoolean)?.value ?: false
        val knockout = (groupDict?.get("K") as? com.yuroyami.kitepdf.parser.PdfBoolean)?.value ?: false

        parentState.save()
        parentState.replace(parentState.current.copy(
            ctm = parentState.current.ctm.concat(formMatrix),
        ))
        val groupOpened = isTransparencyGroup
        if (groupOpened) {
            canvas.beginTransparencyGroup(
                bbox = bbox, ctm = parentState.current.ctm,
                isolated = isolated, knockout = knockout,
                alpha = parentState.current.fillAlpha,
                blendMode = parentState.current.blendMode,
            )
        }
        try {
            val bytes = com.yuroyami.kitepdf.filters.FilterChain.decode(formStream)
            val ops = ContentStreamParser.parse(bytes)
            val pathBuilder = PdfPath.Builder()
            for (op in ops) dispatch(op, parentState, pathBuilder, childFonts, childXObjects, childColorSpaces, childExtGStates, childShadings, childPatterns)
        } finally {
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
        return fonts.map.mapValues { (_, ref) -> PdfFont.from(ref, resolver) }
    }

    /** Build the page resource → XObject dictionary. Each entry is a stream. */
    private fun loadXObjects(resources: PdfDictionary?): Map<String, PdfStream> {
        val xobjs = resources?.getDict("XObject", resolver) ?: return emptyMap()
        return xobjs.map.mapNotNull { (name, raw) ->
            val resolved = raw.resolve(resolver) as? PdfStream ?: return@mapNotNull null
            name to resolved
        }.toMap()
    }

    /** Default device transform: flip Y so origin is top-left, no scaling. */
    private fun defaultDeviceCtm(page: PdfPage): Matrix =
        Matrix(1.0, 0.0, 0.0, -1.0, 0.0, page.height)

    /* ─── Operator dispatch ──────────────────────────────────────────────── */

    private fun dispatch(
        op: Operation,
        state: GraphicsStack,
        path: PdfPath.Builder,
        fonts: Map<String, PdfFont>,
        xobjects: Map<String, PdfStream>,
        colorSpaces: Map<String, ColorSpace>,
        extGStates: Map<String, ExtGState>,
        shadings: Map<String, PdfShading>,
        patterns: Map<String, PdfPattern>,
    ) {
        val a = op.operands
        when (op.operator) {
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
            "w" -> state.replace(state.current.copy(lineWidth = num(a, 0)))
            "d" -> {
                // dash: [ array ] phase d  — array of on/off lengths (user units).
                val arr = a.getOrNull(0) as? com.yuroyami.kitepdf.parser.PdfArray
                val dashes = arr?.let { ar -> List(ar.size) { ar.getOrNull(it).toDouble() } }
                state.replace(state.current.copy(
                    dashArray = dashes?.takeIf { ds -> ds.isNotEmpty() && ds.any { it > 0.0 } },
                    dashPhase = num(a, 1),
                ))
            }

            // ─── Color ────────────────────────────────────────────────────
            "g" -> state.replace(state.current.copy(fillColor = RgbColor.gray(num(a, 0)), fillPattern = null))
            "G" -> state.replace(state.current.copy(strokeColor = RgbColor.gray(num(a, 0)), strokePattern = null))
            "rg" -> state.replace(state.current.copy(
                fillColor = RgbColor(num(a, 0), num(a, 1), num(a, 2)), fillPattern = null,
            ))
            "RG" -> state.replace(state.current.copy(
                strokeColor = RgbColor(num(a, 0), num(a, 1), num(a, 2)), strokePattern = null,
            ))
            "k" -> state.replace(state.current.copy(
                fillColor = ColorSpace.DeviceCMYK.toRgb(
                    doubleArrayOf(num(a, 0), num(a, 1), num(a, 2), num(a, 3)),
                ),
                fillPattern = null,
            ))
            "K" -> state.replace(state.current.copy(
                strokeColor = ColorSpace.DeviceCMYK.toRgb(
                    doubleArrayOf(num(a, 0), num(a, 1), num(a, 2), num(a, 3)),
                ),
                strokePattern = null,
            ))

            // ─── Path construction ───────────────────────────────────────
            "m" -> path.moveTo(num(a, 0), num(a, 1))
            "l" -> path.lineTo(num(a, 0), num(a, 1))
            "c" -> path.curveTo(num(a, 0), num(a, 1), num(a, 2), num(a, 3), num(a, 4), num(a, 5))
            "v" -> path.curveToV(num(a, 0), num(a, 1), num(a, 2), num(a, 3))
            "y" -> path.curveToY(num(a, 0), num(a, 1), num(a, 2), num(a, 3))
            "h" -> path.close()
            "re" -> path.rectangle(num(a, 0), num(a, 1), num(a, 2), num(a, 3))

            // ─── Path painting ────────────────────────────────────────────
            "S" -> { paintStroke(path, state); path.reset() }
            "s" -> { path.close(); paintStroke(path, state); path.reset() }
            "f", "F" -> { paintFill(path, state, evenOdd = false); path.reset() }
            "f*" -> { paintFill(path, state, evenOdd = true); path.reset() }
            "B" -> { paintFill(path, state, false); paintStroke(path, state); path.reset() }
            "B*" -> { paintFill(path, state, true); paintStroke(path, state); path.reset() }
            "b" -> { path.close(); paintFill(path, state, false); paintStroke(path, state); path.reset() }
            "b*" -> { path.close(); paintFill(path, state, true); paintStroke(path, state); path.reset() }
            "n" -> path.reset()

            // ─── Clipping (push, applied on the *next* paint per spec) ────
            "W" -> if (!path.isEmpty()) { canvas.pushClip(path.build(), state.current.ctm, false); activeClipCount++ }
            "W*" -> if (!path.isEmpty()) { canvas.pushClip(path.build(), state.current.ctm, true); activeClipCount++ }

            // ─── Text state ──────────────────────────────────────────────
            "BT" -> state.mutateText { TextState(font = it.font, fontSize = it.fontSize) }
            "ET" -> { /* no-op: text state stays in current GraphicsState */ }
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
                val stream = xobjects[name] ?: return
                when (stream.dict.getName("Subtype")) {
                    "Image" -> withSoftMask(state.current) {
                        canvas.drawImage(ImageXObject.from(stream), state.current.ctm, state.current.fillAlpha)
                    }
                    "Form" -> renderFormXObject(stream, state)
                }
            }

            // ─── Shading fill (`sh <name>`) ──────────────────────────────
            "sh" -> {
                val name = (a.firstOrNull() as? PdfName)?.value ?: return
                val shading = shadings[name] ?: return
                val s = state.current
                canvas.fillShading(
                    shading, s.ctm, clipPath = null,
                    alpha = s.fillAlpha, blendMode = s.blendMode,
                )
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

    private fun paintFill(path: PdfPath.Builder, state: GraphicsStack, evenOdd: Boolean) {
        if (path.isEmpty()) return
        val s = state.current
        val built = path.build()
        withSoftMask(s) {
            val pat = s.fillPattern
            when {
                pat is PdfPattern.Shading -> canvas.fillShading(
                    pat.shading, s.ctm.concat(pat.matrix), clipPath = built,
                    alpha = s.fillAlpha, blendMode = s.blendMode,
                )
                pat != null -> {
                    // Tiling / unsupported pattern — not renderable yet. Skip
                    // rather than paint the default colour, which would flood
                    // e.g. a full-page background pattern solid black.
                }
                else -> canvas.fillPath(
                    built, s.ctm, s.fillColor, evenOdd,
                    alpha = s.fillAlpha, blendMode = s.blendMode,
                )
            }
        }
    }

    private fun paintStroke(path: PdfPath.Builder, state: GraphicsStack) {
        if (path.isEmpty()) return
        val s = state.current
        withSoftMask(s) {
            canvas.strokePath(
                path.build(), s.ctm, s.strokeColor, s.lineWidth,
                alpha = s.strokeAlpha, blendMode = s.blendMode,
                dashArray = s.dashArray, dashPhase = s.dashPhase,
            )
        }
    }

    /**
     * Wrap [paint] in a soft-mask layer when the current graphics state has
     * one active. Without an SMask the lambda is invoked directly. With an
     * SMask, the canvas's [PdfCanvas.applySoftMask] gets two callbacks:
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
            com.yuroyami.kitepdf.Rectangle(
                arr.getOrNull(0).toDouble(), arr.getOrNull(1).toDouble(),
                arr.getOrNull(2).toDouble(), arr.getOrNull(3).toDouble(),
            )
        } ?: com.yuroyami.kitepdf.Rectangle(0.0, 0.0, 0.0, 0.0)
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
                renderMaskGroup(mask.group, childCanvas, maskMatrix)
            },
        )
    }

    private fun renderMaskGroup(formStream: PdfStream, target: PdfCanvas, formMatrix: Matrix) {
        // We need a sub-renderer so the mask paints into [target] rather
        // than the page canvas. The cleanest thing is to construct a
        // throwaway PageRenderer instance and let it run the form-xobject
        // pipeline; that reuses every operator handler and resource walk.
        val sub = PageRenderer(target, resolver)
        val parentState = GraphicsStack(GraphicsState(ctm = Matrix.IDENTITY.concat(formMatrix)))
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
        a: List<com.yuroyami.kitepdf.parser.PdfObject>,
        patterns: Map<String, PdfPattern>,
        state: GraphicsStack,
        stroke: Boolean,
    ) {
        // Pattern name is always the last operand for Pattern colour-space.
        val nameOp = a.lastOrNull() as? PdfName
        if (nameOp != null) {
            // Use the parsed pattern when we have it; otherwise mark it
            // Unsupported so the fill is skipped rather than collapsing to the
            // default (black) colour and flooding the region.
            val pat = patterns[nameOp.value] ?: PdfPattern.Unsupported
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
                is com.yuroyami.kitepdf.parser.PdfInt -> v.value.toDouble()
                is com.yuroyami.kitepdf.parser.PdfReal -> v.value
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
        a: List<com.yuroyami.kitepdf.parser.PdfObject>,
        state: GraphicsStack,
        stroke: Boolean,
    ) {
        // `sc` / `SC` only carry numeric components (no Pattern colour-space).
        val cs = if (stroke) state.current.strokeColorSpace else state.current.fillColorSpace
        val comps = DoubleArray(a.size) { i ->
            when (val v = a[i]) {
                is com.yuroyami.kitepdf.parser.PdfInt -> v.value.toDouble()
                is com.yuroyami.kitepdf.parser.PdfReal -> v.value
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
            val moved = Matrix.translation(tx, ty).concat(t.lineMatrix)
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
        val font = t.font ?: return
        if (bytes.isEmpty()) return

        // Combined text-space-to-user-space matrix:
        //   text matrix × CTM, with font size + horizontal scale already
        //   baked in to the per-glyph advance.
        val pageMatrix = state.current.ctm
        val textMatrix = t.textMatrix
        // text-space → device = text matrix THEN current CTM. Since
        // concat(other) applies `other` first, that's pageMatrix.concat(textMatrix)
        // (NOT the reverse, which would apply the device matrix first and fling
        // the text off-page).
        val finalMatrix = pageMatrix.concat(textMatrix)
            .translate(0.0, t.rise)

        withSoftMask(state.current) {
            canvas.drawText(
                bytes, font, t.fontSize, finalMatrix, state.current.fillColor,
                alpha = state.current.fillAlpha, blendMode = state.current.blendMode,
            )
        }

        // Advance Tm by the total width of this run.
        val totalAdvance = totalAdvance(bytes, t, font)
        state.mutateText {
            it.copy(textMatrix = Matrix.translation(totalAdvance, 0.0).concat(it.textMatrix))
        }
    }

    /** TJ numeric adjustment: shift the text-cursor by [thousandthsOfEm] of em. */
    private fun adjustTextX(state: GraphicsStack, thousandthsOfEm: Double) {
        val t = state.current.text
        val tx = thousandthsOfEm / 1000.0 * t.fontSize * (t.horizontalScaling / 100.0)
        state.mutateText {
            it.copy(textMatrix = Matrix.translation(tx, 0.0).concat(it.textMatrix))
        }
    }

    /**
     * Sum of per-glyph advances for [bytes], including Tc/Tw/Th adjustments.
     * Walks via [PdfFont.layoutBytes] so composite Type 0 fonts contribute
     * one advance per CID code unit (typically 2 bytes), not one per byte.
     */
    private fun totalAdvance(bytes: ByteArray, t: TextState, font: PdfFont): Double {
        var advance = 0.0
        val sizeFactor = t.fontSize / 1000.0
        val hScale = t.horizontalScaling / 100.0
        for (glyph in font.layoutBytes(bytes)) {
            val perGlyph = glyph.advanceWidth * sizeFactor +
                t.charSpacing +
                (if (glyph.isWordSpace) t.wordSpacing else 0.0)
            advance += perGlyph * hScale
        }
        return advance
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
}
