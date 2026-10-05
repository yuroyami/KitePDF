package io.github.yuroyami.kitepdf.epub

import io.github.yuroyami.kitepdf.core.KiteRole

import io.github.yuroyami.kitepdf.svg.SvgImage

import io.github.yuroyami.kitepdf.core.css.CssValues
import io.github.yuroyami.kitepdf.core.xml.KiteXmlNode

import io.github.yuroyami.kitepdf.epub.css.BorderStyle
import io.github.yuroyami.kitepdf.epub.css.ComputedStyle
import io.github.yuroyami.kitepdf.epub.css.CssBackground
import io.github.yuroyami.kitepdf.epub.css.CssFloat
import io.github.yuroyami.kitepdf.epub.css.Display
import io.github.yuroyami.kitepdf.epub.css.TextAlign
import io.github.yuroyami.kitepdf.epub.css.WritingMode
import io.github.yuroyami.kitepdf.epub.css.Edge
import io.github.yuroyami.kitepdf.epub.css.ListType
import io.github.yuroyami.kitepdf.epub.css.ObjectFit
import io.github.yuroyami.kitepdf.epub.css.PseudoContent
import io.github.yuroyami.kitepdf.epub.css.PseudoSide
import io.github.yuroyami.kitepdf.epub.css.StyleResolver
import io.github.yuroyami.kitepdf.epub.css.TextTransform
import io.github.yuroyami.kitepdf.epub.css.WhiteSpaceMode
import io.github.yuroyami.kitepdf.epub.css.WordBreak
import io.github.yuroyami.kitepdf.core.render.RgbColor

/**
 * Builds the CSS [LayoutBox] tree from the DOM, driven by the cascade
 * ([StyleResolver]). Each block-level element becomes a [BlockBox]; its inline
 * content (text + inline elements) is gathered into anonymous [TextBlockBox]
 * children, interleaved with nested block boxes and images. `display:none`
 * subtrees are dropped; list-item markers come from `list-style-type` + an
 * ordinal. Whitespace collapses except under `white-space: pre*`.
 */
internal class BoxBuilder(
    private val resolver: StyleResolver,
    /** Zip path of the document being built; the base for `#fragment` links. */
    private val docPath: String = "",
    /** The media type that the manifest gives a zip path, for an `<object>` without a `type` (#40). */
    private val mediaTypeOf: (String) -> String? = { null },
    /**
     * True in a chapter that scripts run in: each text run then keeps the element its text belongs
     * to, so a tap finds the element a script listens on (#41). Off elsewhere, where a run that
     * spans two elements stays one run.
     */
    private val tracksElements: Boolean = false,
    private val resolveHref: (String) -> String,
) {
    fun build(root: KiteXmlNode.Element): BlockBox =
        buildBlock(root, resolver.initial(), emptyList(), marker = null, markerColor = BLACK, isRoot = true)

    /**
     * [build] as a [Run] of steps, each the next child of a block element or the end of a block,
     * so that a caller can stop between two steps and go on later (#389).
     */
    fun start(root: KiteXmlNode.Element): Run = Run(root)

    /** One build of [start], a step at a time. */
    inner class Run internal constructor(root: KiteXmlNode.Element) {
        private val frames = arrayListOf(BuildFrame(root, resolver.initial(), emptyList(), marker = null, markerColor = BLACK, isRoot = true))

        /** The root's box, once [step] has returned true. */
        var box: BlockBox? = null
            private set

        /** Takes the next step, and returns true once [box] is built. */
        fun step(): Boolean {
            if (box == null) box = stepFrames(frames)
            return box != null
        }
    }

    private fun buildBlock(
        el: KiteXmlNode.Element,
        style: ComputedStyle,
        ancestors: List<KiteXmlNode.Element>,
        marker: String?,
        markerColor: RgbColor,
        isRoot: Boolean = false,
        parentSem: BoxSemantics? = null,
        anonymous: Boolean = false,
    ): BlockBox {
        val frames = arrayListOf(BuildFrame(el, style, ancestors, marker, markerColor, isRoot, parentSem, anonymous))
        while (true) stepFrames(frames)?.let { return it }
    }

    /**
     * One step of [frames]: the next child of the innermost block element, or the end of that
     * block, which its parent then hoists. A block child opens a frame of its own instead of a
     * call, so a [Run] can stop between any two steps (#389). Returns the outermost box once its
     * frame has closed.
     */
    private fun stepFrames(frames: ArrayList<BuildFrame>): BlockBox? {
        val frame = frames.last()
        val children = frame.el.children
        if (frame.next < children.size) {
            frame.place(children[frame.next++])?.let(frames::add)
            return null
        }
        frames.removeAt(frames.lastIndex)
        val box = frame.close()
        val parent = frames.lastOrNull() ?: return box
        parent.hoist(listOf(box))
        return null
    }

    /**
     * A block element whose children are building: what [buildBlock] kept in its locals. A block
     * or list-item child opens a frame of its own, and its box joins this one's children as
     * [hoist] puts it, once that frame has closed.
     */
    private inner class BuildFrame(
        val el: KiteXmlNode.Element,
        private val style: ComputedStyle,
        private val ancestors: List<KiteXmlNode.Element>,
        marker: String?,
        private val markerColor: RgbColor,
        private val isRoot: Boolean = false,
        /** The ancestor's semantics: `aria-hidden` and `epub:type` reach down. */
        parentSem: BoxSemantics? = null,
        /** An anonymous box: [el] is a stand-in, so it is no ancestor of its children and has no pseudo. */
        private val anonymous: Boolean = false,
    ) {
        private val children = ArrayList<LayoutBox>()
        private val inl = Inline()
        private var pendingMarker = marker
        private val childAncestors = if (isRoot || anonymous) ancestors else listOf(el) + ancestors
        private var ordinal = el.attrs["start"]?.toIntOrNull() ?: 1
        // Ids seen on inline descendants (they get no box of their own). The
        // first block-level box hoisted after an id claims it, so a footnote
        // target like <span id><div>note</div></span> anchors to the note
        // itself; ids still waiting at the end attach to this block, as they
        // always did.
        private val pendingAnchors = ArrayList<String>()

        // The element's own accessibility facts, shared by every box it makes.
        private val sem = BoxSemantics.of(el.tag, el.attrs, parentSem)

        /** The index of the next child of [el] to place. */
        var next = 0

        init {
            // A pronunciation on the block covers the text it holds itself (#39).
            speechHint(el, ancestors)?.let(inl::beginSpeech)
            // The block's text carries the ids of the block and the elements around it (#36).
            inl.beginIds((listOf(el) + ancestors).asReversed().mapNotNull { e -> e.attrs["id"]?.takeIf { it.isNotBlank() } })
            // Text right inside the block belongs to the block (#41).
            if (tracksElements && !isRoot && !anonymous) inl.beginElement(el)
            injectPseudo(PseudoSide.BEFORE)
        }

        fun flush() {
            if (inl.hasContent()) {
                children.add(TextBlockBox(style, inl.take(), pendingMarker, markerColor).also { it.semantics = sem })
                pendingMarker = null
            } else {
                inl.reset()
            }
        }

        // CSS 2.1, 9.2.1.1: a block inside an inline splits the inline. The
        // pending inline runs flush first (keeping document order), the block
        // lands as a sibling, and the inline flow resumes after it. Ids that
        // were waiting on an inline are claimed by this block so a fragment
        // link lands on the block, not on the top of this container. (A
        // hoisted table cannot carry anchors; its ids keep waiting.)
        fun hoist(boxes: List<LayoutBox>) {
            flush()
            if (pendingAnchors.isNotEmpty()) {
                (boxes.firstOrNull() as? BlockBox)?.let {
                    it.anchors += pendingAnchors
                    pendingAnchors.clear()
                }
            }
            children.addAll(boxes)
        }

        // ::before generated content precedes the element's own children. A
        // block-display pseudo becomes a synthetic block child; anything else
        // flows inline.
        private fun injectPseudo(side: PseudoSide) {
            if (isRoot || anonymous) return
            val pc = resolver.computePseudo(el, ancestors, style, side) ?: return
            if (pc.style.display == Display.BLOCK || pc.style.display == Display.FLEX || pc.style.display == Display.GRID) { flush(); children.add(pseudoBlock(pc)) }
            else inl.appendText(pc.text, pc.style)
        }

        /** Places [child]. A block or list-item element comes back as the frame that builds it. */
        fun place(child: KiteXmlNode): BuildFrame? {
            when (child) {
                is KiteXmlNode.Text -> inl.appendText(child.text, style)
                is KiteXmlNode.Comment -> {}
                is KiteXmlNode.Element -> {
                    if (child.tag == "br") { inl.addBreak(); return null }
                    if (child.tag == "img" || child.tag == "image") {
                        // An image's id is an anchor, as an inline element's is, so a link or a fragment finds its page (#36).
                        child.attrs["id"]?.takeIf { it.isNotBlank() }?.let(pendingAnchors::add)
                        val src = child.attrs["src"] ?: child.attrs["href"] ?: child.attrs["xlink:href"]
                        if (src != null && src.isNotBlank()) {
                            val cs = resolver.compute(child, childAncestors, style)
                            // A hidden image generates no box: it is not decoded, drawn or given room
                            // (CSS Display 3, 2.5, #424).
                            if (cs.display == Display.NONE) return null
                            val aw = child.attrs["width"]?.trim()?.removeSuffix("px")?.toDoubleOrNull()
                            val ah = child.attrs["height"]?.trim()?.removeSuffix("px")?.toDoubleOrNull()
                            // img is inline by default (CSS): it flows on the line
                            // unless the author blocks or floats it.
                            if ((cs.display == Display.INLINE || cs.display == Display.INLINE_BLOCK) &&
                                cs.cssFloat == CssFloat.NONE
                            ) {
                                inl.addImage(
                                    resolveHref(src), style, cs.widthPt ?: aw?.times(0.75), cs.heightPt ?: ah?.times(0.75), child.attrs["alt"], cs.objectFit,
                                    element = child.takeIf { tracksElements },
                                )
                            } else {
                                flush()
                                // The attributes are CSS pixels, 0.75pt each, in block mode too (#112).
                                children.add(
                                    ImageBox(cs, resolveHref(src), attrWidth = aw?.times(0.75), attrHeight = ah?.times(0.75)).also {
                                        it.semantics = imageSemantics(child, sem)
                                        it.source = child
                                    },
                                )
                            }
                        }
                        return null
                    }
                    if (child.tag == "video" || child.tag == "audio") {
                        val cs = resolver.compute(child, childAncestors, style)
                        if (cs.display != Display.NONE) mediaBox(child, cs, sem)?.let { box -> flush(); children.add(box) }
                        return null
                    }
                    if (child.tag == "math") {
                        val cs = resolver.compute(child, childAncestors, style)
                        if (cs.display == Display.NONE) return null
                        child.attrs["id"]?.takeIf { it.isNotBlank() }?.let(pendingAnchors::add)
                        val math = MathParser.parse(child)
                        when {
                            // The layout sets formulas horizontally only, so vertical text keeps the linear text.
                            style.writingMode != WritingMode.HORIZONTAL -> inl.appendText(math.readingText, cs)
                            math.display || cs.display == Display.BLOCK -> { flush(); children.add(mathBlock(math, cs)) }
                            else -> inl.addMath(math, cs)
                        }
                        return null
                    }
                    if (child.tag == "iframe" || child.tag == "object") {
                        val cs = resolver.compute(child, childAncestors, style)
                        if (cs.display == Display.NONE) return null
                        val box = embedBox(child, cs, childAncestors, sem)
                        if (box != null) {
                            box.source = child
                            flush()
                            children.add(box)
                            return null
                        }
                        // Any other object shows its fallback children, as it did.
                    }
                    if (child.tag == "svg") { // inline SVG: paint as a vector image box
                        val cs = resolver.compute(child, childAncestors, style)
                        // A hidden sprite sheet or glyph cache generates no box (CSS 2.1, 9.2.4, #275).
                        if (cs.display != Display.NONE) SvgImage.fromElement(child, resolver.svgHostStyle(child))?.let {
                            flush()
                            // Its width and height attributes are its intrinsic size, as on the line (#565).
                            val image = ImageBox(
                                cs, "", it,
                                attrWidth = svgSizePt(child.attrs["width"], cs), attrHeight = svgSizePt(child.attrs["height"], cs),
                            )
                            children.add(image.also { box -> box.semantics = svgSemantics(child, sem); box.source = child })
                        }
                        return null
                    }
                    val cs = resolver.compute(child, childAncestors, style)
                    when (cs.display) {
                        Display.NONE -> {}
                        Display.INLINE, Display.INLINE_BLOCK -> processInline(child, cs, childAncestors, inl, pendingAnchors, ::hoist, sem)
                        Display.TABLE -> hoist(buildTable(child, cs, childAncestors, sem))
                        Display.LIST_ITEM -> return BuildFrame(child, cs, childAncestors, marker(cs, ordinal++), cs.color, parentSem = sem)
                        // BLOCK, plus stray table parts outside a table: treat as blocks (no text lost).
                        else -> return BuildFrame(child, cs, childAncestors, null, BLACK, parentSem = sem)
                    }
                }
            }
            return null
        }

        /** Ends the block: its ::after content, its last text, and its box. */
        fun close(): BlockBox {
            injectPseudo(PseudoSide.AFTER)
            flush()
            return BlockBox(style, children).also { box ->
                if (!isRoot && !anonymous) box.source = el
                el.attrs["id"]?.let(box.anchors::add)
                if (el.tag == "a") el.attrs["name"]?.let(box.anchors::add) // legacy anchor
                // A block-level link, such as a flex item or `a { display: block }`, covers its whole box (#33).
                if (el.tag == "a") el.attrs["href"]?.takeIf { it.isNotBlank() }?.let { box.linkHref = resolveLink(it) }
                box.anchors += pendingAnchors
                box.semantics = sem
            }
        }
    }

    /** An image announces its `alt` (or `aria-label`); `alt=""` means decorative. */
    /**
     * The box of a `<video>` or an `<audio>` element (#29). The poster fills it, else a placeholder
     * does. The element's children are the path for a reader that plays nothing, so they are not
     * built. An audio element without controls is not rendered (HTML, 4.8.10).
     */
    private fun mediaBox(el: KiteXmlNode.Element, cs: ComputedStyle, parentSem: BoxSemantics?): ImageBox? {
        val video = el.tag == "video"
        if (!video && "controls" !in el.attrs) return null
        fun href(src: String) = if (MEDIA_SCHEME.containsMatchIn(src)) src.trim() else resolveHref(src)
        val sources = buildList {
            el.attrs["src"]?.takeIf { it.isNotBlank() }?.let { add(EpubMediaSource(href(it), el.attrs["type"])) }
            for (source in el.children) {
                if (source !is KiteXmlNode.Element || source.tag != "source") continue
                source.attrs["src"]?.takeIf { it.isNotBlank() }?.let { add(EpubMediaSource(href(it), source.attrs["type"])) }
            }
        }
        val poster = el.attrs["poster"]?.takeIf { video && it.isNotBlank() }?.let { resolveHref(it) }
        val aw = el.attrs["width"]?.trim()?.removeSuffix("px")?.toDoubleOrNull()?.times(0.75)
        val ah = el.attrs["height"]?.trim()?.removeSuffix("px")?.toDoubleOrNull()?.times(0.75)
        return ImageBox(cs, poster ?: "", attrWidth = aw, attrHeight = ah).also {
            it.media = MediaInfo(
                if (video) EpubMediaKind.VIDEO else EpubMediaKind.AUDIO, sources, poster,
                controls = "controls" in el.attrs, autoplay = "autoplay" in el.attrs,
                loop = "loop" in el.attrs, muted = "muted" in el.attrs, id = el.attrs["id"],
            )
            it.semantics = BoxSemantics.of(el.tag, el.attrs, parentSem)
            it.source = el
        }
    }

    /**
     * The box of an `<iframe>`, or of an `<object>` that embeds an HTML or XHTML document, else
     * null (#40). A frame keeps an empty box. An object's box holds its fallback children, and is
     * at least as large as the element asks. Both default to 300 by 150 CSS pixels, as in a
     * browser, and a script-less reader paints nothing else there.
     */
    private fun embedBox(
        el: KiteXmlNode.Element,
        cs: ComputedStyle,
        ancestors: List<KiteXmlNode.Element>,
        parentSem: BoxSemantics?,
    ): LayoutBox? {
        val frame = el.tag == "iframe"
        val raw = (if (frame) el.attrs["src"] else el.attrs["data"])?.trim().orEmpty()
        val url = MEDIA_SCHEME.containsMatchIn(raw)
        val href = if (raw.isEmpty() || url) raw else resolveHref(raw)
        val type = el.attrs["type"]?.substringBefore(';')?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }
            ?: href.takeIf { it.isNotEmpty() && !url }?.let { mediaTypeOf(it.substringBefore('#')) }?.lowercase()
        if (!frame && type !in EMBED_DOCUMENT_TYPES && !(type == null && isDocumentPath(href))) return null
        val aw = el.attrs["width"]?.trim()?.removeSuffix("px")?.toDoubleOrNull()?.times(0.75)
        val ah = el.attrs["height"]?.trim()?.removeSuffix("px")?.toDoubleOrNull()?.times(0.75)
        val info = EmbedInfo(if (frame) EpubEmbedKind.FRAME else EpubEmbedKind.OBJECT, href, type, el.attrs["id"])
        if (frame) {
            return ImageBox(cs, "", attrWidth = aw, attrHeight = ah).also {
                it.embed = info
                it.semantics = BoxSemantics.of(el.tag, el.attrs, parentSem)
            }
        }
        val sized = cs.copy(
            display = Display.BLOCK,
            widthPt = cs.widthPt ?: aw ?: EMBED_DEFAULT_WIDTH_PT,
            heightPt = cs.heightPt ?: ah ?: EMBED_DEFAULT_HEIGHT_PT,
        )
        return buildBlock(el, sized, ancestors, null, BLACK, parentSem = parentSem).also { it.embed = info }
    }

    /**
     * The pronunciation [el] gives its text, `ssml:ph`, in the alphabet of the nearest
     * `ssml:alphabet` on it or above it, or null (#39). The parser keeps local names, so the
     * attributes read as `ph` and `alphabet`.
     */
    private fun speechHint(el: KiteXmlNode.Element, ancestors: List<KiteXmlNode.Element>): SpeechHint? {
        val phoneme = el.attrs["ph"]?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val alphabet = (el.attrs["alphabet"] ?: ancestors.firstNotNullOfOrNull { it.attrs["alphabet"] })?.trim()
        return SpeechHint(phoneme, alphabet?.takeIf { it.isNotEmpty() })
    }

    private fun imageSemantics(el: KiteXmlNode.Element, parentSem: BoxSemantics?): BoxSemantics {
        val base = BoxSemantics.of(el.tag, el.attrs, parentSem)
        val alt = el.attrs["alt"]
        return BoxSemantics(
            role = KiteRole.IMAGE,
            label = base?.label ?: alt?.takeIf { it.isNotBlank() },
            epubType = base?.epubType,
            hidden = base?.hidden == true || alt?.isEmpty() == true,
        )
    }

    /**
     * An `<svg>` reads as an image named by its `aria-label`, else by its own `<title>`,
     * else by its `<desc>`, which is how an SVG chapter announces itself (#26).
     */
    private fun svgSemantics(el: KiteXmlNode.Element, parentSem: BoxSemantics?): BoxSemantics {
        val base = BoxSemantics.of(el.tag, el.attrs, parentSem)
        fun childText(tag: String): String? = el.children.firstNotNullOfOrNull { c ->
            (c as? KiteXmlNode.Element)?.takeIf { it.tag == tag }?.let { it.textContent().replace(WHITESPACE, " ").trim() }
        }?.takeIf { it.isNotEmpty() }
        return BoxSemantics(
            role = KiteRole.IMAGE,
            label = base?.label ?: childText("title") ?: childText("desc"),
            epubType = base?.epubType,
            hidden = base?.hidden == true,
        )
    }

    /**
     * The `width` or `height` attribute of an inline `<svg>` in points: a bare number is
     * CSS pixels, and `em` and `ex` follow the element's own font. Null for a percentage.
     */
    private fun svgSizePt(raw: String?, style: ComputedStyle): Double? {
        val s = raw?.trim()?.takeIf { it.isNotEmpty() && !it.endsWith('%') } ?: return null
        val pt = s.toDoubleOrNull()?.times(0.75) ?: CssValues.length(s, style.fontSizePt, resolver.initial().fontSizePt, 0.0)
        return pt?.takeIf { it.isFinite() && it > 0.0 }
    }

    /** A synthetic block child holding a `display:block` pseudo's content. */
    private fun pseudoBlock(pc: PseudoContent): BlockBox {
        val run = InlineRun(
            text = pc.text, fontSizePt = pc.style.fontSizePt,
            bold = pc.style.bold, italic = pc.style.italic, family = pc.style.fontFamily,
            color = pc.style.color, valign = pc.style.verticalAlign, underline = pc.style.underline,
            fontFamilyNames = pc.style.fontFamilyNames,
            lineThrough = pc.style.lineThrough,
            overline = pc.style.overline,
        )
        return BlockBox(pc.style, listOf(TextBlockBox(pc.style, listOf(run))))
    }

    /**
     * Build a `display:table` element, flattening row groups. Returns the
     * caption blocks (extracted, laid ABOVE the table) followed by the
     * [TableBox]; `<col>`/`<colgroup>` widths pin their columns; under
     * `border-collapse: collapse` each shared cell edge is painted once.
     */
    private fun buildTable(
        el: KiteXmlNode.Element,
        style: ComputedStyle,
        ancestors: List<KiteXmlNode.Element>,
        parentSem: BoxSemantics? = null,
    ): List<LayoutBox> {
        val rows = ArrayList<TableRowBox>()
        val captions = ArrayList<BlockBox>()
        val childAncestors = listOf(el) + ancestors
        // A row group passes its own style down, so its rows inherit from it (CSS 2.1, 6.2, #463).
        fun addRowsFrom(container: KiteXmlNode.Element, containerAncestors: List<KiteXmlNode.Element>, containerStyle: ComputedStyle) {
            val anc = listOf(container) + containerAncestors
            // A run of children that are not rows goes in one anonymous row (CSS 2.1, 17.2.1, #453).
            val stray = ArrayList<KiteXmlNode>()
            fun flushStray() {
                if (stray.any { !it.isTableWhiteSpace() }) {
                    val rowStyle = resolver.anonymous(containerStyle, Display.TABLE_ROW)
                    rows.add(TableRowBox(rowStyle, rowCells(stray, anc, rowStyle, parentSem)))
                }
                stray.clear()
            }
            for (c in container.children) {
                if (c !is KiteXmlNode.Element) { stray.add(c); continue }
                if (c.tag == "caption") {
                    captions.add(buildBlock(c, resolver.compute(c, anc, containerStyle), anc, null, BLACK, parentSem = parentSem))
                    continue
                }
                val cs = resolver.compute(c, anc, containerStyle)
                when (cs.display) {
                    Display.NONE -> {}
                    Display.TABLE_ROW -> { flushStray(); rows.add(buildRow(c, cs, anc, parentSem)) }
                    Display.TABLE_ROW_GROUP -> { flushStray(); addRowsFrom(c, anc, cs) }
                    else -> stray.add(c)
                }
            }
            flushStray()
        }
        addRowsFrom(el, ancestors, style)
        placeCells(rows)
        val table = if (style.borderCollapse) {
            // The table's own border joins the collapse, and a collapsed table has no padding (CSS 2.1, 17.6.2).
            TableBox(
                style.copy(
                    borderTop = Edge.NONE, borderRight = Edge.NONE, borderBottom = Edge.NONE, borderLeft = Edge.NONE,
                    paddingTopPt = 0.0, paddingRightPt = 0.0, paddingBottomPt = 0.0, paddingLeftPt = 0.0,
                ),
                collapseBorders(rows, style),
                scanColWidths(el, style, childAncestors),
            )
        } else {
            TableBox(style, rows, scanColWidths(el, style, childAncestors))
        }
        table.source = el
        return captions + table
    }

    /** `<col span width>` / `<colgroup width>` -> column index -> width (pt). */
    private fun scanColWidths(el: KiteXmlNode.Element, style: ComputedStyle, ancestors: List<KiteXmlNode.Element>): Map<Int, Double> {
        val out = HashMap<Int, Double>()
        var idx = 0
        fun widthOf(c: KiteXmlNode.Element): Double? =
            resolver.compute(c, ancestors, style).widthPt
                ?: c.attrs["width"]?.trim()?.removeSuffix("px")?.toDoubleOrNull()?.let { it * 0.75 }
        fun scan(container: KiteXmlNode.Element) {
            for (c in container.children) {
                if (c !is KiteXmlNode.Element) continue
                when (c.tag) {
                    "col" -> {
                        val span = c.attrs["span"]?.toIntOrNull()?.coerceAtLeast(1) ?: 1
                        val w = widthOf(c)
                        repeat(span) { if (w != null) out[idx] = w; idx++ }
                    }
                    "colgroup" -> {
                        if (c.children.any { it is KiteXmlNode.Element && it.tag == "col" }) scan(c)
                        else {
                            val span = c.attrs["span"]?.toIntOrNull()?.coerceAtLeast(1) ?: 1
                            val w = widthOf(c)
                            repeat(span) { if (w != null) out[idx] = w; idx++ }
                        }
                    }
                }
            }
        }
        scan(el)
        return out
    }

    /**
     * `border-collapse: collapse` (CSS 2.1, 17.6.2.1). Where two cells share an edge, one
     * border shows, chosen by [collapsedWins]; the other side drops its border. An edge on
     * the outside of the grid meets the table's own border in [table] instead, and takes
     * the winner of the two, since the table paints no border of its own. A side that
     * meets several cells keeps its border when it wins against at least one of them, so a
     * spanning cell leaves no gap. Cells are rebuilt with the adjusted styles (styles are
     * immutable).
     */
    private fun collapseBorders(rows: List<TableRowBox>, table: ComputedStyle): List<TableRowBox> {
        // Grid of covering cells (spans fill every slot they touch).
        val grid = HashMap<Long, BlockBox>()
        fun key(r: Int, c: Int) = r.toLong() * 100_000L + c
        var rowCount = 0
        var colCount = 0
        for (row in rows) for (cell in row.cells) {
            for (dr in 0 until cell.rowspan) for (dc in 0 until cell.colspan) {
                grid.getOrPut(key(cell.gridRow + dr, cell.gridCol + dc)) { cell }
            }
            rowCount = maxOf(rowCount, cell.gridRow + cell.rowspan)
            colCount = maxOf(colCount, cell.gridCol + cell.colspan)
        }
        /** The distinct cells other than [cell] in the slots [slots]. */
        fun neighbours(cell: BlockBox, slots: List<Long>): List<BlockBox> =
            slots.mapNotNull { grid[it] }.filter { it !== cell }.distinct()

        /**
         * The border that [cell] shows on one side: [own] when it wins against at least one
         * of the [facing] edges, which hold ties since they lie above or to the left when
         * [facingHoldsTies]; the table's [outer] edge decides on the outside of the grid.
         */
        fun resolve(own: Edge, facing: List<Edge>, facingHoldsTies: Boolean, outer: Edge?): Edge = when {
            facing.isNotEmpty() -> {
                val wins = facing.any { other -> if (facingHoldsTies) collapsedWins(own, other) else !collapsedWins(other, own) }
                if (wins) own else Edge.NONE
            }
            // A cell's border wins a tie against the table's.
            outer != null -> if (collapsedWins(outer, own)) outer else own
            else -> own
        }

        return rows.map { row ->
            TableRowBox(
                row.style,
                row.cells.map { cell ->
                    val s = cell.style
                    val cols = (cell.gridCol until cell.gridCol + cell.colspan).toList()
                    val rowsSpanned = (cell.gridRow until cell.gridRow + cell.rowspan).toList()
                    val above = neighbours(cell, cols.map { key(cell.gridRow - 1, it) })
                    val below = neighbours(cell, cols.map { key(cell.gridRow + cell.rowspan, it) })
                    val left = neighbours(cell, rowsSpanned.map { key(it, cell.gridCol - 1) })
                    val right = neighbours(cell, rowsSpanned.map { key(it, cell.gridCol + cell.colspan) })
                    val ns = s.copy(
                        borderTop = resolve(s.borderTop, above.map { it.style.borderBottom }, true, table.borderTop.takeIf { cell.gridRow == 0 }),
                        borderLeft = resolve(s.borderLeft, left.map { it.style.borderRight }, true, table.borderLeft.takeIf { cell.gridCol == 0 }),
                        borderBottom = resolve(
                            s.borderBottom, below.map { it.style.borderTop }, false,
                            table.borderBottom.takeIf { cell.gridRow + cell.rowspan == rowCount },
                        ),
                        borderRight = resolve(
                            s.borderRight, right.map { it.style.borderLeft }, false,
                            table.borderRight.takeIf { cell.gridCol + cell.colspan == colCount },
                        ),
                    )
                    if (ns == s) cell else BlockBox(ns, cell.children).also {
                        it.colspan = cell.colspan; it.rowspan = cell.rowspan
                        it.gridRow = cell.gridRow; it.gridCol = cell.gridCol
                        it.anchors += cell.anchors
                        it.source = cell.source
                    }
                },
            )
        }
    }

    /** Assign each cell a grid (row, col), skipping cells occupied by rowspans from above. */
    private fun placeCells(rows: List<TableRowBox>) {
        val occupied = HashSet<Long>()
        fun key(r: Int, c: Int) = r.toLong() * 100_000L + c
        for ((r, row) in rows.withIndex()) {
            var col = 0
            for (cell in row.cells) {
                while (occupied.contains(key(r, col))) col++
                cell.gridRow = r; cell.gridCol = col
                for (dr in 0 until cell.rowspan) for (dc in 0 until cell.colspan) occupied.add(key(r + dr, col + dc))
                col += cell.colspan
            }
        }
    }

    private fun buildRow(
        el: KiteXmlNode.Element,
        style: ComputedStyle,
        ancestors: List<KiteXmlNode.Element>,
        parentSem: BoxSemantics? = null,
    ): TableRowBox {
        return TableRowBox(style, rowCells(el.children, listOf(el) + ancestors, style, parentSem))
    }

    /**
     * The cells of a row whose children are [nodes]. A run of children that are not cells goes in
     * one anonymous cell, and white space between cells makes no box (CSS 2.1, 17.2.1, #453).
     */
    private fun rowCells(
        nodes: List<KiteXmlNode>,
        ancestors: List<KiteXmlNode.Element>,
        rowStyle: ComputedStyle,
        parentSem: BoxSemantics?,
    ): List<BlockBox> {
        val cells = ArrayList<BlockBox>()
        val stray = ArrayList<KiteXmlNode>()
        fun flushStray() {
            if (stray.any { !it.isTableWhiteSpace() }) {
                val holder = KiteXmlNode.Element("", emptyMap(), ArrayList(stray))
                val cellStyle = resolver.anonymous(rowStyle, Display.TABLE_CELL)
                cells.add(buildBlock(holder, cellStyle, ancestors, null, BLACK, parentSem = parentSem, anonymous = true))
            }
            stray.clear()
        }
        for (c in nodes) {
            if (c !is KiteXmlNode.Element) { stray.add(c); continue }
            val cs = resolver.compute(c, ancestors, rowStyle)
            when (cs.display) {
                Display.NONE -> {}
                Display.TABLE_CELL -> {
                    flushStray()
                    cells.add(buildBlock(c, cs, ancestors, null, BLACK, parentSem = parentSem).also { cell ->
                        cell.colspan = c.attrs["colspan"]?.toIntOrNull()?.coerceAtLeast(1) ?: 1
                        cell.rowspan = c.attrs["rowspan"]?.toIntOrNull()?.coerceAtLeast(1) ?: 1
                    })
                }
                else -> stray.add(c)
            }
        }
        flushStray()
        return cells
    }

    /** Text of CSS white space only, which a table drops between its parts (CSS 2.1, 17.2.1, step 1). */
    private fun KiteXmlNode.isTableWhiteSpace(): Boolean =
        this is KiteXmlNode.Text && text.all { it == ' ' || it == '\t' || it == '\n' || it == '\r' || it == '\u000C' }

    private fun processInline(
        el: KiteXmlNode.Element,
        style: ComputedStyle,
        ancestors: List<KiteXmlNode.Element>,
        inl: Inline,
        anchorSink: MutableList<String>,
        hoist: (List<LayoutBox>) -> Unit,
        parentSem: BoxSemantics? = null,
    ) {
        // Inline elements never get a box; their ids wait for the next hoisted
        // block, and attach to the enclosing block when none follows.
        el.attrs["id"]?.let(anchorSink::add)
        if (el.tag == "a") el.attrs["name"]?.let(anchorSink::add)

        val link = if (el.tag == "a") el.attrs["href"]?.takeIf { it.isNotBlank() }?.let(::resolveLink) else null
        if (link != null) inl.beginLink(link)
        val speech = speechHint(el, ancestors)
        if (speech != null) inl.beginSpeech(speech)
        val id = el.attrs["id"]?.takeIf { it.isNotBlank() }
        if (id != null) inl.beginId(id)
        if (tracksElements) inl.beginElement(el)
        val background = inl.beginBackground(style.backgroundColor)
        try {
            if (el.tag == "ruby") { processRuby(el, style, ancestors, inl, anchorSink, hoist, parentSem); return }
            // Inline generated content flows with the element's own runs (a
            // block-display pseudo inside an inline element is treated inline).
            resolver.computePseudo(el, ancestors, style, PseudoSide.BEFORE)?.let { inl.appendText(it.text, it.style) }
            val childAncestors = listOf(el) + ancestors
            for (child in el.children) when (child) {
                is KiteXmlNode.Text -> inl.appendText(child.text, style)
                is KiteXmlNode.Comment -> {}
                is KiteXmlNode.Element -> {
                    if (child.tag == "br") { inl.addBreak(); continue }
                    if (child.tag == "img" || child.tag == "image") {
                        // Inline image: flows on the line, bottom on the baseline.
                        child.attrs["id"]?.takeIf { it.isNotBlank() }?.let(anchorSink::add)
                        val src = child.attrs["src"] ?: child.attrs["href"] ?: child.attrs["xlink:href"]
                        if (src != null && src.isNotBlank()) {
                            val cs = resolver.compute(child, childAncestors, style)
                            // A hidden image generates no box, in inline content too (#424).
                            if (cs.display == Display.NONE) continue
                            val aw = child.attrs["width"]?.trim()?.removeSuffix("px")?.toDoubleOrNull()?.times(0.75)
                            val ah = child.attrs["height"]?.trim()?.removeSuffix("px")?.toDoubleOrNull()?.times(0.75)
                            inl.addImage(resolveHref(src), style, cs.widthPt ?: aw, cs.heightPt ?: ah, child.attrs["alt"], cs.objectFit, element = child.takeIf { tracksElements })
                        }
                        continue
                    }
                    if (child.tag == "video" || child.tag == "audio") {
                        // A media element takes a block of its own, as a block image does.
                        val cs = resolver.compute(child, childAncestors, style)
                        if (cs.display != Display.NONE) mediaBox(child, cs, parentSem)?.let { hoist(listOf(it)) }
                        continue
                    }
                    if (child.tag == "math") {
                        val cs = resolver.compute(child, childAncestors, style)
                        if (cs.display == Display.NONE) continue
                        child.attrs["id"]?.takeIf { it.isNotBlank() }?.let(anchorSink::add)
                        val math = MathParser.parse(child)
                        when {
                            style.writingMode != WritingMode.HORIZONTAL -> inl.appendText(math.readingText, cs)
                            // A display formula inside a paragraph takes a block of its own, centred.
                            math.display || cs.display == Display.BLOCK -> hoist(listOf(mathBlock(math, cs)))
                            else -> inl.addMath(math, cs)
                        }
                        continue
                    }
                    if (child.tag == "iframe" || child.tag == "object") {
                        // An embedded document takes a block of its own, as a media element does.
                        val cs = resolver.compute(child, childAncestors, style)
                        if (cs.display == Display.NONE) continue
                        val box = embedBox(child, cs, childAncestors, parentSem)
                        if (box != null) {
                            box.source = child
                            hoist(linked(listOf(box), inl))
                            continue
                        }
                    }
                    if (child.tag == "svg") {
                        // An <svg> in inline content is an inline replaced element, like an
                        // <img>: it flows on the line (CSS 2.1, 10.3.2, #275).
                        val cs = resolver.compute(child, childAncestors, style)
                        if (cs.display != Display.NONE) SvgImage.fromElement(child, resolver.svgHostStyle(child))?.let { svg ->
                            val sem = svgSemantics(child, parentSem)
                            inl.addImage(
                                "", style, cs.widthPt ?: svgSizePt(child.attrs["width"], cs), cs.heightPt ?: svgSizePt(child.attrs["height"], cs),
                                alt = if (sem.hidden) "" else sem.label, objectFit = cs.objectFit, svg = svg,
                                element = child.takeIf { tracksElements },
                            )
                        }
                        continue
                    }
                    val cs = resolver.compute(child, childAncestors, style)
                    when (cs.display) {
                        Display.NONE -> {}
                        Display.INLINE, Display.INLINE_BLOCK ->
                            processInline(child, cs, childAncestors, inl, anchorSink, hoist, parentSem)
                        Display.TABLE -> hoist(linked(buildTable(child, cs, childAncestors, parentSem), inl))
                        // CSS 2.1, 9.2.1.1: a block inside an inline is hoisted
                        // to a sibling box; the inline runs resume after it. A
                        // list item hoisted from inline flow gets no marker, a
                        // deliberate simplification (a bare <li> inside a
                        // <span> is not a list).
                        else -> hoist(linked(listOf(buildBlock(child, cs, childAncestors, null, BLACK, parentSem = parentSem)), inl))
                    }
                }
            }
            resolver.computePseudo(el, ancestors, style, PseudoSide.AFTER)?.let { inl.appendText(it.text, it.style) }
        } finally {
            inl.endBackground(background)
            if (tracksElements) inl.endElement()
            if (id != null) inl.endId()
            if (speech != null) inl.endSpeech()
            if (link != null) inl.endLink()
        }
    }

    /** Blocks lifted out of an inline `<a href>` stay part of that link (#214). */
    private fun linked(boxes: List<LayoutBox>, inl: Inline): List<LayoutBox> {
        inl.activeLink?.let { href -> for (b in boxes) if (b.linkHref == null) b.linkHref = href }
        return boxes
    }

    /**
     * Resolve an `<a href>`: external URLs (any scheme) stay verbatim; a bare
     * `#fragment` targets this document; a relative path resolves against the
     * document's directory, keeping its fragment.
     */
    private fun resolveLink(href: String): String = resolveLinkHref(href, docPath, resolveHref)

    /**
     * `<ruby>`: the base (text / `<rb>` / other inline children) flows normally
     * but tagged as one ruby group; every `<rt>` contributes to the reading
     * painted above it; `<rp>` fallback punctuation is dropped. Multiple `<rt>`
     * segments (jukugo ruby) merge into one reading over the whole base, a
     * documented simplification.
     */
    private fun processRuby(
        el: KiteXmlNode.Element,
        style: ComputedStyle,
        ancestors: List<KiteXmlNode.Element>,
        inl: Inline,
        anchorSink: MutableList<String>,
        hoist: (List<LayoutBox>) -> Unit,
        parentSem: BoxSemantics? = null,
    ) {
        val childAncestors = listOf(el) + ancestors
        val reading = StringBuilder()
        fun collectText(e: KiteXmlNode.Element) {
            for (c in e.children) when (c) {
                is KiteXmlNode.Text -> reading.append(c.text)
                is KiteXmlNode.Comment -> {}
                is KiteXmlNode.Element -> if (c.tag != "rp") collectText(c)
            }
        }
        for (c in el.children) if (c is KiteXmlNode.Element && c.tag == "rt") collectText(c)
        val readingText = reading.toString().replace(WHITESPACE, " ").trim()

        inl.beginRuby(readingText.takeIf { it.isNotEmpty() })
        try {
            for (c in el.children) when (c) {
                is KiteXmlNode.Text -> inl.appendText(c.text, style)
                is KiteXmlNode.Comment -> {}
                is KiteXmlNode.Element -> when {
                    c.tag == "rt" || c.tag == "rp" -> {}
                    c.tag == "br" -> inl.addBreak()
                    else -> {
                        val cs = resolver.compute(c, childAncestors, style)
                        if (cs.display != Display.NONE) processInline(c, cs, childAncestors, inl, anchorSink, hoist, parentSem)
                    }
                }
            }
        } finally {
            inl.endRuby()
        }
    }

    /** Per-block inline-run accumulator with HTML whitespace collapsing. */
    private class Inline {
        private var runs = ArrayList<InlineRun>()
        private var pendingSpace = false
        private var pendingSpaceRun: InlineRun? = null
        private var backgroundColor: CssBackground? = null

        fun beginBackground(color: CssBackground?): CssBackground? {
            val previous = backgroundColor
            if (color != null) backgroundColor = color
            return previous
        }

        fun endBackground(previous: CssBackground?) { backgroundColor = previous }
        private var blockHasContent = false
        private var lastWasBreak = false
        // Active <ruby> group: runs made between beginRuby/endRuby carry the id +
        // reading so the layout can keep the base together and paint the reading.
        private var rubyGroup = -1
        private var rubyText: String? = null
        private var nextRubyId = 0

        fun beginRuby(reading: String?) {
            if (reading != null) { rubyGroup = nextRubyId++; rubyText = reading }
        }

        fun endRuby() { rubyGroup = -1; rubyText = null }

        // Active <a href>: nested anchors save/restore the enclosing target.
        private val linkStack = ArrayDeque<String?>()
        private var linkHref: String? = null

        fun beginLink(href: String) { linkStack.addLast(linkHref); linkHref = href }

        fun endLink() { linkHref = linkStack.removeLastOrNull() }

        // Active ssml:ph: the element's pronunciation, one instance per element (#39).
        private val speechStack = ArrayDeque<SpeechHint?>()
        private var speech: SpeechHint? = null

        fun beginSpeech(hint: SpeechHint) { speechStack.addLast(speech); speech = hint }

        fun endSpeech() { speech = speechStack.removeLastOrNull() }

        // The innermost element around the text, in a chapter that scripts run in (#41).
        private val elementStack = ArrayDeque<KiteXmlNode.Element?>()
        private var element: KiteXmlNode.Element? = null

        fun beginElement(el: KiteXmlNode.Element) { elementStack.addLast(element); element = el }

        fun endElement() { element = elementStack.removeLastOrNull() }

        // The ids of the elements around the text, one list per element, outermost first (#36).
        private val idStack = ArrayDeque<List<String>>()
        private var ids: List<String> = emptyList()

        fun beginIds(base: List<String>) { ids = base }

        fun beginId(id: String) { idStack.addLast(ids); ids = ids + id }

        fun endId() { ids = idStack.removeLastOrNull() ?: ids }

        /** The `<a href>` target in force, which a block lifted out of the link inherits. */
        val activeLink: String? get() = linkHref

        fun hasContent() = runs.any { it.text.isNotEmpty() || it.hardBreak }

        fun take(): List<InlineRun> {
            val r = runs; runs = ArrayList(); reset(); return r
        }

        fun reset() { runs = ArrayList(); pendingSpace = false; pendingSpaceRun = null; blockHasContent = false; lastWasBreak = false }

        fun addBreak() {
            runs.add(InlineRun("", fontSizePt = 0.0, hardBreak = true))
            pendingSpace = false; lastWasBreak = true
        }

        /** A `<math>` element: one U+FFFC run carrying the formula (#32). */
        fun addMath(math: MathRoot, style: ComputedStyle) {
            if (pendingSpace && blockHasContent && !lastWasBreak) {
                runs.add(pendingSpaceRun ?: makeRun(" ", style))
            }
            pendingSpace = false; lastWasBreak = false; blockHasContent = true
            runs.add(makeRun("\uFFFC", style).copy(math = math))
        }

        /** An inline `<img>`: one U+FFFC run carrying the source + size hints. */
        fun addImage(
            src: String, style: ComputedStyle, cssW: Double?, cssH: Double?, alt: String? = null,
            objectFit: ObjectFit = ObjectFit.FILL, svg: SvgImage? = null,
            /** The `<img>` or `<svg>` itself, in a chapter that scripts run in (#41). */
            element: KiteXmlNode.Element? = null,
        ) {
            if (pendingSpace && blockHasContent && !lastWasBreak) {
                runs.add(pendingSpaceRun ?: makeRun(" ", style))
            }
            pendingSpace = false; lastWasBreak = false; blockHasContent = true
            runs.add(
                makeRun("￼", style).copy(
                    imageSrc = src, imageSvg = svg, imageCssW = cssW, imageCssH = cssH, imageAlt = alt, imageObjectFit = objectFit,
                    element = element ?: this.element,
                ),
            )
        }

        fun appendText(raw: String, style: ComputedStyle) {
            if (raw.isEmpty()) return
            if (style.whiteSpace == WhiteSpaceMode.PRE || style.whiteSpace == WhiteSpaceMode.PRE_WRAP || style.whiteSpace == WhiteSpaceMode.PRE_LINE) {
                runs.add(makeRun(transformPre(raw, style.textTransform, style.fullWidth), style))
                blockHasContent = true; pendingSpace = false; lastWasBreak = false
                return
            }
            val b = StringBuilder(raw.length)
            var at = 0
            while (at < raw.length) {
                val cp = codePointAt(raw, at)
                at += charCount(cp)
                if (collapsible(cp)) {
                    if (!pendingSpace) pendingSpaceRun = makeRun(" ", style)
                    pendingSpace = true
                } else {
                    // Word boundary BEFORE consuming pendingSpace: capitalize needs it,
                    // and it must survive across appendText calls (runs split mid-word).
                    val boundary = pendingSpace || !blockHasContent || lastWasBreak
                    if (pendingSpace && blockHasContent && !lastWasBreak) {
                        // A space between two elements paints with the element that holds
                        // it, so it needs a run of its own only when its lines or background
                        // differ from the text after it. Otherwise it joins that text, which
                        // keeps the line height and the copied space unchanged (#259).
                        val space = pendingSpaceRun
                        if (b.isEmpty() && space != null && !samePaint(space, makeRun("", style))) runs.add(space)
                        else b.append(' ')
                    }
                    pendingSpace = false; lastWasBreak = false
                    appendCodePoint(b, transform(cp, style.textTransform, style.fullWidth, boundary)); blockHasContent = true
                }
            }
            if (b.isNotEmpty()) runs.add(makeRun(b.toString(), style))
        }

        /**
         * The case of [cp] that [tt] asks for, one code point at a time, so a letter outside the BMP
         * changes too (#322), then its full-width form when [wide] asks for it (#508).
         */
        private fun transform(cp: Int, tt: TextTransform, wide: Boolean, wordBoundary: Boolean): Int {
            val cased = when (tt) {
                TextTransform.NONE -> cp
                TextTransform.UPPERCASE -> CaseMapping.uppercase(cp)
                TextTransform.LOWERCASE -> CaseMapping.lowercase(cp)
                TextTransform.CAPITALIZE -> if (wordBoundary) CaseMapping.titlecase(cp) else cp
            }
            return if (wide) FullWidth.of(cased) else cased
        }

        /**
         * True for white space that collapses: U+0020, the tab and the line breaks (CSS Text 3,
         * 4.1.1). A no-break space, an ideographic space and the other space separators keep
         * their width wherever they stand (#577).
         */
        private fun collapsible(cp: Int): Boolean =
            cp < 0x10000 && cp.toChar().isWhitespace() && (cp == 0x20 || cp.toChar().category != CharCategory.SPACE_SEPARATOR)

        /** Transform preserved-whitespace text: word boundaries follow whitespace. */
        private fun transformPre(raw: String, tt: TextTransform, wide: Boolean): String {
            if (tt == TextTransform.NONE && !wide) return raw
            val sb = StringBuilder(raw.length)
            var boundary = true
            var at = 0
            while (at < raw.length) {
                val cp = codePointAt(raw, at)
                at += charCount(cp)
                appendCodePoint(sb, transform(cp, tt, wide, boundary))
                boundary = cp < 0x10000 && cp.toChar().isWhitespace()
            }
            return sb.toString()
        }

        private fun samePaint(a: InlineRun, b: InlineRun): Boolean =
            a.underline == b.underline && a.lineThrough == b.lineThrough && a.overline == b.overline && a.backgroundColor == b.backgroundColor

        private fun makeRun(text: String, style: ComputedStyle) = InlineRun(
            text = text, fontSizePt = style.fontSizePt,
            bold = style.bold, italic = style.italic, family = style.fontFamily,
            color = style.color, valign = style.verticalAlign, underline = style.underline,
            fontFamilyNames = style.fontFamilyNames,
            rubyGroup = rubyGroup, rubyText = rubyText,
            href = linkHref,
            speech = speech,
            ids = ids,
            element = element,
            letterSpacingPt = style.letterSpacingPt, wordSpacingPt = style.wordSpacingPt,
            smallCaps = style.smallCaps,
            overflowWrap = style.overflowWrap || style.wordBreak == WordBreak.BREAK_WORD,
            breakAll = style.wordBreak == WordBreak.BREAK_ALL,
            keepAll = style.wordBreak == WordBreak.KEEP_ALL,
            lineBreak = style.lineBreak,
            textOrientation = style.textOrientation,
            lineThrough = style.lineThrough,
            overline = style.overline,
            backgroundColor = style.backgroundColor.takeIf { style.display == Display.INLINE || style.display == Display.INLINE_BLOCK }
                ?: backgroundColor,
        )
    }

    /** A display formula: a block of its own, the formula centred on its line (#32). */
    private fun mathBlock(math: MathRoot, cs: ComputedStyle): BlockBox {
        val inl = Inline()
        inl.addMath(MathRoot(math.body, display = true, alt = math.alt), cs)
        // Half an em above and below, as a reading system sets display math apart, unless the book
        // sets more. A text block has no margins of its own, so a block around it holds them.
        val room = cs.fontSizePt * 0.5
        val block = cs.copy(display = Display.BLOCK, marginTopPt = maxOf(cs.marginTopPt, room), marginBottomPt = maxOf(cs.marginBottomPt, room))
        return BlockBox(block, listOf(TextBlockBox(cs.copy(display = Display.BLOCK, textAlign = TextAlign.CENTER), inl.take())))
    }

    private fun marker(style: ComputedStyle, ordinal: Int): String? = when (style.listType) {
        ListType.NONE -> null
        ListType.DISC -> "•"
        ListType.CIRCLE -> "◦"
        ListType.SQUARE -> "▪"
        ListType.DECIMAL -> "$ordinal."
        ListType.LOWER_ROMAN -> roman(ordinal).lowercase() + "."
        ListType.UPPER_ROMAN -> roman(ordinal) + "."
        ListType.LOWER_ALPHA -> alpha(ordinal).lowercase() + "."
        ListType.UPPER_ALPHA -> alpha(ordinal) + "."
    }

    private fun roman(n: Int): String {
        if (n !in 1..3999) return n.toString()
        val vals = intArrayOf(1000, 900, 500, 400, 100, 90, 50, 40, 10, 9, 5, 4, 1)
        val syms = arrayOf("M", "CM", "D", "CD", "C", "XC", "L", "XL", "X", "IX", "V", "IV", "I")
        val sb = StringBuilder(); var x = n
        for (i in vals.indices) while (x >= vals[i]) { sb.append(syms[i]); x -= vals[i] }
        return sb.toString()
    }

    private fun alpha(n: Int): String {
        if (n < 1) return n.toString()
        val sb = StringBuilder(); var x = n
        while (x > 0) { x--; sb.append('A' + (x % 26)); x /= 26 }
        return sb.reverse().toString()
    }

    private companion object {
        val BLACK = RgbColor(0.0, 0.0, 0.0)
        val WHITESPACE = Regex("\\s+")
    }
}

/**
 * An `<a href>` as the layout stores it: `zipPath#fragment` for a place in the book,
 * and the href itself for an external URL. [docPath] is the document the link sits in,
 * and [resolveHref] resolves a relative path against that document's folder.
 */
internal fun resolveLinkHref(href: String, docPath: String, resolveHref: (String) -> String): String {
    val h = href.trim()
    if (URI_SCHEME.containsMatchIn(h)) return h
    val path = h.substringBefore('#')
    val frag = h.substringAfter('#', "")
    val resolved = if (path.isEmpty()) docPath else resolveHref(path)
    return if (frag.isEmpty()) resolved else "$resolved#$frag"
}

/** A URI scheme prefix (`https:`, `mailto:`, ...): the href is external. */
private val URI_SCHEME = Regex("^[a-zA-Z][a-zA-Z0-9+.-]*:")

/**
 * Whether [challenger] shows instead of [holder] where two collapsed borders meet
 * (CSS 2.1, 17.6.2.1). `hidden` wins over everything, and `none` loses to everything.
 * Then the wider border wins, then the style listed first in [BorderStyle]. A full tie
 * leaves [holder], which is the border above or to the left, or the cell's own border
 * against the table's.
 */
internal fun collapsedWins(challenger: Edge, holder: Edge): Boolean = when {
    holder.style == BorderStyle.HIDDEN -> false
    challenger.style == BorderStyle.HIDDEN -> true
    !challenger.visible -> false
    !holder.visible -> true
    challenger.width != holder.width -> challenger.width > holder.width
    else -> challenger.style.ordinal < holder.style.ordinal
}

/** A media source with a scheme, such as https, which stays a URL instead of a zip path. */
private val MEDIA_SCHEME = Regex("^[a-zA-Z][a-zA-Z0-9+.-]*:")

/** Whether [href] names an HTML or XHTML document by its extension. */
private fun isDocumentPath(href: String): Boolean {
    val path = href.substringBefore('#').substringBefore('?').lowercase()
    return path.endsWith(".xhtml") || path.endsWith(".html") || path.endsWith(".htm") || path.endsWith(".xht")
}
