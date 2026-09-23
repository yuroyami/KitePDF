package io.github.yuroyami.kitepdf.svg

import io.github.yuroyami.kitepdf.core.xml.KiteXml
import io.github.yuroyami.kitepdf.core.xml.KiteXmlNode

import io.github.yuroyami.kitepdf.core.KiteRectangle
import io.github.yuroyami.kitepdf.core.font.TextGlyph
import io.github.yuroyami.kitepdf.core.render.KiteBlendMode
import io.github.yuroyami.kitepdf.core.render.sampleStops
import io.github.yuroyami.kitepdf.core.render.KiteMatrix
import io.github.yuroyami.kitepdf.core.render.KiteCanvas
import io.github.yuroyami.kitepdf.core.render.KitePath
import io.github.yuroyami.kitepdf.core.css.CssValues
import io.github.yuroyami.kitepdf.core.font.FontSpec
import io.github.yuroyami.kitepdf.core.font.KiteFontFamily
import io.github.yuroyami.kitepdf.core.render.KiteImageData
import io.github.yuroyami.kitepdf.core.render.RgbColor
import io.github.yuroyami.kitepdf.core.render.SoftMask
import io.github.yuroyami.kitepdf.core.render.spreadOver
import io.github.yuroyami.kitepdf.core.render.strokeOutline
import io.github.yuroyami.kitepdf.core.text.TextEncoding
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.PI

/**
 * An SVG image, painted as vectors into the shared [KiteCanvas] rather than
 * rasterised, so it stays crisp at any scale. This is what draws the
 * illustrations, cover art and diagrams that ship inside EPUBs and comics;
 * [SvgDocument] wraps it when the `.svg` file IS the document.
 *
 * ```kotlin
 * val svg = SvgImage.parse(bytes) ?: return
 * svg.render(canvas, KiteMatrix.scaling(2.0, 2.0))
 * ```
 *
 * Drawn: `<svg>` (width/height/viewBox), `<g>`, `<use>`, `<path>` (every `d`
 * command including elliptical arcs), `<rect>` (+ rx/ry), `<circle>`,
 * `<ellipse>`, `<line>`, `<polyline>`, `<polygon>`, `<text>` and `<image>`;
 * `fill`, `stroke`, `stroke-width`, `opacity`, `fill-opacity`,
 * `stroke-opacity`, `fill-rule`, `display` and `visibility` with inheritance;
 * `transform` (translate/scale/rotate/skewX/skewY/matrix); linear and radial
 * gradients as paint; and `clip-path`. Embedded `<style>` rules support type,
 * universal, class and ID selectors, their compounds and comma lists, with
 * specificity, source order and `!important` (SVG 1.1, section 6).
 * Combinators, attribute/pseudo selectors, CSS escapes, at-rules and external
 * stylesheets are not interpreted.
 *
 * Text is measured against standard-font metrics and drawn through a host
 * typeface, because SVG ships no font file of its own.
 *
 * Not drawn: patterns, masks, filters, and animation.
 */
public class SvgImage private constructor(
    private val root: KiteXmlNode.Element,
    /** Intrinsic size in px (from width/height, else the viewBox extent, else 300x150). */
    public val width: Double,
    public val height: Double,
    private val viewBox: DoubleArray?, // minX, minY, w, h
) {

    private val styles: SvgStyles by lazy { SvgStyles(root) }

    /** Every element carrying an `id`, for `<use>`, gradients and `clip-path`. */
    private val byId: Map<String, KiteXmlNode.Element> by lazy {
        val out = LinkedHashMap<String, KiteXmlNode.Element>()
        val pending = ArrayList<KiteXmlNode.Element>()
        pending.add(root)
        while (pending.isNotEmpty()) {
            val el = pending.removeAt(pending.lastIndex)
            el.attrs["id"]?.let { if (it.isNotEmpty() && it !in out) out[it] = el }
            // Reverse push preserves document order while keeping traversal
            // iterative: hostile SVG nesting must not consume the call stack.
            for (i in el.children.indices.reversed()) {
                (el.children[i] as? KiteXmlNode.Element)?.let(pending::add)
            }
        }
        out
    }

    /**
     * Paint the SVG into [canvas]; [ctm] maps the (0,0)-(width,height) viewport
     * to device space.
     *
     * [loadResource] resolves an `<image href>` that points at another file,
     * relative to wherever this SVG came from. Without it only `data:` images
     * draw, which is what a self-contained file uses.
     */
    public fun render(
        canvas: KiteCanvas,
        ctm: KiteMatrix,
        loadResource: ((String) -> ByteArray?)? = null,
    ) {
        val vb = viewBox
        if (vb == null || vb[2] <= 0 || vb[3] <= 0) {
            walk(root, ctm, Paint(viewport = ctm, viewportWidth = width, viewportHeight = height), canvas, loadResource, depth = 0)
            return
        }
        val fit = viewBoxFit(vb, width, height, root.attrs["preserveAspectRatio"] ?: root.attrs["preserveaspectratio"])
        if (fit.slice) canvas.pushClip(KitePath.Builder().apply { rectangle(0.0, 0.0, width, height) }.build(), ctm, evenOdd = false)
        try {
            walk(root, compose(ctm, fit.matrix), Paint(viewport = ctm, viewportWidth = vb[2], viewportHeight = vb[3]), canvas, loadResource, depth = 0)
        } finally {
            if (fit.slice) canvas.popClip()
        }
    }

    private class Fit(val matrix: KiteMatrix, val slice: Boolean)

    /**
     * The matrix that fits [vb] into a [w] by [h] viewport under
     * `preserveAspectRatio` (SVG 1.1, 7.8). The default, `xMidYMid meet`, is a
     * uniform scale that shows the whole viewBox, centred, so a circle stays a
     * circle (#97). `slice` fills the viewport instead and needs its clip.
     */
    private fun viewBoxFit(vb: DoubleArray, w: Double, h: Double, par: String?): Fit {
        val sx = w / vb[2]
        val sy = h / vb[3]
        val parts = par?.trim()?.split(' ', '\t', '\n')?.filter { it.isNotEmpty() && it != "defer" } ?: emptyList()
        val align = (parts.getOrNull(0) ?: "xMidYMid").lowercase()
        val toOrigin = KiteMatrix.translation(-vb[0], -vb[1])
        if (align == "none") return Fit(compose(KiteMatrix.scaling(sx, sy), toOrigin), slice = false)
        val slice = parts.getOrNull(1)?.lowercase() == "slice"
        val scale = if (slice) maxOf(sx, sy) else minOf(sx, sy)
        val fx = when { "xmin" in align -> 0.0; "xmax" in align -> 1.0; else -> 0.5 }
        val fy = when { "ymin" in align -> 0.0; "ymax" in align -> 1.0; else -> 0.5 }
        val shift = KiteMatrix.translation((w - vb[2] * scale) * fx, (h - vb[3] * scale) * fy)
        return Fit(compose(shift, compose(KiteMatrix.scaling(scale, scale), toOrigin)), slice)
    }

    private data class Paint(
        val fill: RgbColor? = RgbColor.BLACK,
        val stroke: RgbColor? = null,
        val strokeW: Double = 1.0,
        val opacity: Double = 1.0,
        val evenOdd: Boolean = false,
        val current: RgbColor = RgbColor.BLACK,
        /** `url(#id)` paint servers, resolved when the shape is painted. */
        val fillRef: String? = null,
        val strokeRef: String? = null,
        val fillOpacity: Double = 1.0,
        val strokeOpacity: Double = 1.0,
        val fontSize: Double = 16.0,
        val fontSpec: FontSpec = FontSpec.SansSerif,
        val textAnchor: String? = null,
        val visible: Boolean = true,
        val dash: List<Double>? = null,
        val dashOffset: Double = 0.0,
        val lineCap: Int = 0,
        val lineJoin: Int = 0,
        /** SVG's initial `stroke-miterlimit` is 4, not PDF's 10. */
        val miterLimit: Double = 4.0,
        /** The viewport's own matrix, which a group's offscreen bounds are given in. */
        val viewport: KiteMatrix = KiteMatrix.IDENTITY,
        /** Percentage bases in current viewport user units (SVG 2, section 8.9). */
        val viewportWidth: Double = 0.0,
        val viewportHeight: Double = 0.0,
    )

    // The canvas travels as a parameter, exactly like ctm and Paint: a field
    // here made render() non-reentrant, so two concurrent renders of the same
    // SvgImage hijacked each other's destination and silently dropped shapes.
    private fun walk(
        el: KiteXmlNode.Element,
        parentCtm: KiteMatrix,
        parent: Paint,
        canvas: KiteCanvas,
        load: ((String) -> ByteArray?)?,
        depth: Int,
    ) {
        if (depth > MAX_DEPTH) return                       // <use> cycles
        if (isDisplayNone(el)) return
        // A transform may come from a style declaration too, like any presentation property (#182).
        val ctm = styleOrAttr(el, "transform")?.let { compose(parentCtm, parseTransform(it)) } ?: parentCtm
        val container = el.tag.lowercase() in CONTAINERS
        val paint = resolvePaint(el, parent, container)
        // SVG 1.1, 14.5: a container's opacity composites its children once, as
        // one offscreen group, so where two of its shapes overlap no darker seam
        // shows (#91). A single shape's own opacity stays a per-paint alpha.
        val groupAlpha = if (container) (styleOrAttr(el, "opacity")?.toDoubleOrNull() ?: 1.0).coerceIn(0.0, 1.0) else 1.0
        val clip = clipPathOf(el, ctm, paint)
        if (clip != null) canvas.pushClip(clip, KiteMatrix.IDENTITY, evenOdd = false)
        if (groupAlpha < 1.0) {
            canvas.beginTransparencyGroup(
                KiteRectangle(0.0, 0.0, width, height), parent.viewport,
                isolated = true, knockout = false, alpha = groupAlpha, blendMode = KiteBlendMode.Normal,
            )
        }
        try {
            paintElement(el, ctm, paint, canvas, load, depth)
        } finally {
            if (groupAlpha < 1.0) canvas.endTransparencyGroup()
            if (clip != null) canvas.popClip()
        }
    }

    private fun paintElement(
        el: KiteXmlNode.Element,
        ctm: KiteMatrix,
        paint: Paint,
        canvas: KiteCanvas,
        load: ((String) -> ByteArray?)?,
        depth: Int,
    ) {
        when (el.tag.lowercase()) {
            "svg" -> if (depth == 0) {
                for (c in el.children) if (c is KiteXmlNode.Element) walk(c, ctm, paint, canvas, load, depth + 1)
            } else {
                drawNestedSvg(el, ctm, paint, canvas, load, depth)
            }
            "g", "a" ->
                for (c in el.children) if (c is KiteXmlNode.Element) walk(c, ctm, paint, canvas, load, depth + 1)
            "switch" -> firstPassingChild(el)?.let { walk(it, ctm, paint, canvas, load, depth + 1) }
            "use" -> drawUse(el, ctm, paint, canvas, load, depth)
            "image" -> drawImage(el, ctm, paint, canvas, load)
            "text" -> drawText(el, ctm, paint, canvas, depth)
            "path" -> el.attrs["d"]?.let { paintShape(parsePath(it), ctm, paint, canvas) }
            "rect" -> paintShape(rect(el.attrs, paint), ctm, paint, canvas)
            "circle" -> paintShape(
                ellipse(num(el, "cx", paint), num(el, "cy", paint), num(el, "r", paint), num(el, "r", paint)),
                ctm, paint, canvas,
            )
            "ellipse" -> paintShape(
                ellipse(num(el, "cx", paint), num(el, "cy", paint), num(el, "rx", paint), num(el, "ry", paint)),
                ctm, paint, canvas,
            )
            "line" -> paintShape(
                KitePath.Builder().apply {
                    moveTo(num(el, "x1", paint), num(el, "y1", paint))
                    lineTo(num(el, "x2", paint), num(el, "y2", paint))
                }.build(),
                ctm, paint, canvas, forceStroke = true,
            )
            "polyline" -> el.attrs["points"]?.let { paintShape(polyline(it, close = false), ctm, paint, canvas) }
            "polygon" -> el.attrs["points"]?.let { paintShape(polyline(it, close = true), ctm, paint, canvas) }
        }
    }

    /** Display removes the subtree; inherited visibility can be overridden below it. */
    private fun isDisplayNone(el: KiteXmlNode.Element): Boolean =
        styleOrAttr(el, "display")?.trim() == "none"

    /**
     * A nested `<svg>` opens a new viewport (SVG 1.1, 7.9, #176): the box at its
     * x and y, width by height, clips what it holds, and its own viewBox maps
     * into that box. A zero width or height draws nothing. A percentage
     * resolves against its nearest containing viewport.
     */
    private fun drawNestedSvg(
        el: KiteXmlNode.Element,
        ctm: KiteMatrix,
        paint: Paint,
        canvas: KiteCanvas,
        load: ((String) -> ByteArray?)?,
        depth: Int,
    ) {
        fun size(name: String, whole: Double): Double {
            val raw = el.attrs[name]?.trim() ?: return whole
            return parseLen(raw, paint.fontSize, whole)
        }
        val w = size("width", paint.viewportWidth)
        val h = size("height", paint.viewportHeight)
        if (w <= 0.0 || h <= 0.0) return
        val origin = compose(ctm, KiteMatrix.translation(num(el, "x", paint), num(el, "y", paint)))
        val vb = (el.attrs["viewBox"] ?: el.attrs["viewbox"])?.let { numbers(it) }?.takeIf { it.size >= 4 && it[2] > 0 && it[3] > 0 }
        val inner = if (vb == null) origin
        else compose(origin, viewBoxFit(vb, w, h, el.attrs["preserveAspectRatio"] ?: el.attrs["preserveaspectratio"]).matrix)
        val innerPaint = paint.copy(viewportWidth = vb?.get(2) ?: w, viewportHeight = vb?.get(3) ?: h)
        canvas.pushClip(KitePath.Builder().apply { rectangle(0.0, 0.0, w, h) }.build(), origin, evenOdd = false)
        try {
            for (c in el.children) if (c is KiteXmlNode.Element) walk(c, inner, innerPaint, canvas, load, depth + 1)
        } finally {
            canvas.popClip()
        }
    }

    /**
     * `<use href="#id">`: draw the referenced element again, offset by x/y.
     * The reference's own attributes still win over the ones inherited here.
     */
    private fun drawUse(
        el: KiteXmlNode.Element,
        ctm: KiteMatrix,
        paint: Paint,
        canvas: KiteCanvas,
        load: ((String) -> ByteArray?)?,
        depth: Int,
    ) {
        val id = el.attrs["href"]?.trim()?.removePrefix("#")?.takeIf { it.isNotEmpty() } ?: return
        val target = byId[id] ?: return
        val moved = compose(ctm, KiteMatrix.translation(num(el, "x", paint), num(el, "y", paint)))
        // <symbol> is invisible on its own but paints through <use>, as a group.
        if (target.tag.lowercase() == "symbol") {
            for (c in target.children) if (c is KiteXmlNode.Element) walk(c, moved, paint, canvas, load, depth + 1)
        } else {
            walk(target, moved, paint, canvas, load, depth + 1)
        }
    }

    /** `<image>`: a `data:` URI, or another file when the caller can load one. */
    private fun drawImage(
        el: KiteXmlNode.Element,
        ctm: KiteMatrix,
        paint: Paint,
        canvas: KiteCanvas,
        load: ((String) -> ByteArray?)?,
    ) {
        if (!paint.visible) return
        val href = el.attrs["href"]?.trim()?.takeIf { it.isNotEmpty() } ?: return
        val bytes = if (href.startsWith("data:")) dataUri(href) else load?.invoke(href)
        val image = bytes?.let { KiteImageData.fromEncodedImage(it) } ?: return
        // A missing, auto or unreadable size is auto: it takes the intrinsic size,
        // or the other side through the intrinsic aspect ratio (#263). An explicit
        // zero still disables rendering (SVG 2, 12.2, #177).
        val iw = image.width.toDouble()
        val ih = image.height.toDouble()
        val cssW = el.attrs["width"]?.let { imageSizeOrNull(it, paint.fontSize, paint.viewportWidth) }
        val cssH = el.attrs["height"]?.let { imageSizeOrNull(it, paint.fontSize, paint.viewportHeight) }
        val w = cssW ?: cssH?.let { if (ih > 0.0) it * iw / ih else iw } ?: iw
        val h = cssH ?: cssW?.let { if (iw > 0.0) it * ih / iw else ih } ?: ih
        if (w <= 0.0 || h <= 0.0 || !w.isFinite() || !h.isFinite()) return
        // The image's unit square has row 0 at v=1, and SVG's y grows down, so
        // the placement matrix flips y the way a y-down page does.
        val placed = compose(ctm, KiteMatrix(w, 0.0, 0.0, -h, num(el, "x", paint), num(el, "y", paint) + h))
        canvas.drawImage(image, placed, paint.opacity)
    }

    /**
     * One laid-out run of `<text>`: its characters, paint and where it starts. [rotate]
     * turns the run about its start, in degrees clockwise, and only a single-character
     * run has one.
     */
    private class TextRun(
        val paint: Paint, val x: Double, val y: Double, val glyphs: List<TextGlyph>, val width: Double,
        val rotate: Double = 0.0,
    )

    /**
     * Lays out `<text>` and its nested `<tspan>`s as one stream of characters.
     * White space collapses across the whole element, so the one space before
     * a tspan survives and advances the pen (SVG 1.1, 10.15, #98).
     *
     * The x, y, dx, dy and rotate lists of an element give its n-th character its
     * own position, and the nearest element with a value for a character wins. A
     * rotate list repeats its last value (SVG 1.1, 10.4 and 10.5, #181). Each
     * absolute position starts a text chunk, which `text-anchor` aligns as one
     * piece (10.9.1). A nested tspan is laid out as a run of its own.
     */
    private fun layoutText(el: KiteXmlNode.Element, paint: Paint, depth: Int): List<TextRun> {
        class Ch(val char: Char, val paint: Paint) {
            var x: Double? = null
            var y: Double? = null
            var dx: Double? = null
            var dy: Double? = null
            var rotate: Double? = null
            var penX = 0.0
            var penY = 0.0
            var advance = 0.0
        }
        val chars = ArrayList<Ch>()
        var afterSpace = true
        fun collect(node: KiteXmlNode.Element, p: Paint, d: Int) {
            if (d > MAX_DEPTH) return
            val first = chars.size
            for (child in node.children) when (child) {
                is KiteXmlNode.Text -> for (raw in child.text) {
                    val ch = if (raw == '\n' || raw == '\r' || raw == '\t') ' ' else raw
                    if (ch != ' ') { chars.add(Ch(ch, p)); afterSpace = false }
                    else if (!afterSpace) { chars.add(Ch(' ', p)); afterSpace = true }
                }
                is KiteXmlNode.Element -> {
                    if (child.tag.lowercase() != "tspan" || isDisplayNone(child)) continue
                    collect(child, resolvePaint(child, p), d + 1)
                }
            }
            // The children ran first and hold the nearer values, so only gaps are filled.
            val own = chars.subList(first, chars.size)
            fun fill(values: List<Double>?, get: (Ch) -> Double?, set: (Ch, Double) -> Unit) {
                values?.forEachIndexed { i, v -> own.getOrNull(i)?.let { if (get(it) == null) set(it, v) } }
            }
            fill(lengthList(node.attrs["x"], p.fontSize, p.viewportWidth), { it.x }) { c, v -> c.x = v }
            fill(lengthList(node.attrs["y"], p.fontSize, p.viewportHeight), { it.y }) { c, v -> c.y = v }
            fill(lengthList(node.attrs["dx"], p.fontSize, p.viewportWidth), { it.dx }) { c, v -> c.dx = v }
            fill(lengthList(node.attrs["dy"], p.fontSize, p.viewportHeight), { it.dy }) { c, v -> c.dy = v }
            node.attrs["rotate"]?.let { numbers(it) }?.takeIf { it.isNotEmpty() }?.let { angles ->
                fill(List(own.size) { angles[minOf(it, angles.lastIndex)] }, { it.rotate }) { c, v -> c.rotate = v }
            }
        }
        collect(el, paint, depth)
        if (chars.lastOrNull()?.char == ' ') chars.removeAt(chars.lastIndex)

        var penX = 0.0
        var penY = 0.0
        for (c in chars) {
            c.x?.let { penX = it }
            c.y?.let { penY = it }
            penX += c.dx ?: 0.0
            penY += c.dy ?: 0.0
            c.penX = penX
            c.penY = penY
            c.advance = SvgText.advance(c.char, c.paint.fontSpec, c.paint.fontSize)
            penX += c.advance
        }
        // text-anchor moves each chunk by its own advance, as the chunk's first character asks.
        var chunk = 0
        while (chunk < chars.size) {
            var end = chunk + 1
            while (end < chars.size && chars[end].x == null && chars[end].y == null) end++
            val last = chars[end - 1]
            val shift = SvgText.anchorShift(chars[chunk].paint.textAnchor, last.penX + last.advance - chars[chunk].penX)
            for (k in chunk until end) chars[k].penX += shift
            chunk = end
        }

        val runs = ArrayList<TextRun>()
        var i = 0
        while (i < chars.size) {
            val c = chars[i]
            val rotate = c.rotate ?: 0.0
            var j = i + 1
            // A run goes on while nothing moves its next character away from the pen.
            while (j < chars.size && rotate == 0.0 && chars[j].paint === c.paint &&
                chars[j].x == null && chars[j].y == null && (chars[j].dx ?: 0.0) == 0.0 &&
                (chars[j].dy ?: 0.0) == 0.0 && (chars[j].rotate ?: 0.0) == 0.0
            ) j++
            val text = buildString { for (k in i until j) append(chars[k].char) }
            if (text.isNotBlank()) {
                val glyphs = SvgText.glyphs(text, c.paint.fontSpec)
                runs.add(TextRun(c.paint, c.penX, c.penY, glyphs, SvgText.width(glyphs, c.paint.fontSize), rotate))
            }
            i = j
        }
        return runs
    }

    /** Turns a run by its own rotation about its start, or the identity. */
    private fun turnOf(run: TextRun): KiteMatrix {
        if (run.rotate == 0.0) return KiteMatrix.IDENTITY
        val th = run.rotate * PI / 180.0
        val rot = KiteMatrix(cos(th), sin(th), -sin(th), cos(th), 0.0, 0.0)
        return compose(KiteMatrix.translation(run.x, run.y), compose(rot, KiteMatrix.translation(-run.x, -run.y)))
    }

    private fun drawText(
        el: KiteXmlNode.Element,
        ctm: KiteMatrix,
        paint: Paint,
        canvas: KiteCanvas,
        depth: Int,
    ) {
        if (depth > MAX_DEPTH) return
        for (run in layoutText(el, paint, depth)) {
            if (!run.paint.visible) continue
            // Text space is y-up; SVG is y-down, so the run is flipped in place.
            canvas.drawGlyphs(
                run.glyphs, run.paint.fontSize, unitsPerEm = 1000, hasOutlines = false,
                fontSpec = run.paint.fontSpec,
                textToDevice = compose(ctm, compose(turnOf(run), KiteMatrix(1.0, 0.0, 0.0, -1.0, run.x, run.y))),
                color = run.paint.fill ?: RgbColor.BLACK, alpha = run.paint.opacity * run.paint.fillOpacity,
            )
        }
    }

    /** The clip path an element's `clip-path="url(#id)"` names, already in device space. */
    private fun clipPathOf(el: KiteXmlNode.Element, ctm: KiteMatrix, paint: Paint): KitePath? {
        val id = urlRef(styleOrAttr(el, "clip-path")) ?: return null
        val def = byId[id] ?: return null
        if (def.tag.lowercase() != "clippath") return null
        // objectBoundingBox units map the clip's 0..1 box onto the element's own
        // bounds (SVG 1.1, 14.3.5, #174). With no area there is nothing to show.
        var clipCtm = ctm
        if ((def.attrs["clipPathUnits"] ?: def.attrs["clippathunits"])?.trim() == "objectBoundingBox") {
            val b = boundsOfElement(el, paint, 0) ?: return KitePath(emptyList())
            clipCtm = compose(ctm, KiteMatrix(b[2] - b[0], 0.0, 0.0, b[3] - b[1], b[0], b[1]))
        }
        // This adds a user-space transform, not a viewport. Percentages inside
        // clip content still use the viewport's extent (SVG 2, 8.11, #177).
        val clipPaint = clipPaintOf(def, paint)
        val b = KitePath.Builder()
        for (c in def.children) {
            if (c !is KiteXmlNode.Element) continue
            val m = compose(clipCtm, styleOrAttr(c, "transform")?.let { parseTransform(it) } ?: KiteMatrix.IDENTITY)
            for (part in clipShapesOf(c, m, resolvePaint(c, clipPaint))) {
                for (seg in part.segments) when (seg) {
                    is KitePath.Segment.MoveTo -> b.moveTo(seg.x, seg.y)
                    is KitePath.Segment.LineTo -> b.lineTo(seg.x, seg.y)
                    is KitePath.Segment.CurveTo -> b.curveTo(seg.x1, seg.y1, seg.x2, seg.y2, seg.x3, seg.y3)
                    is KitePath.Segment.QuadTo -> b.quadTo(seg.x1, seg.y1, seg.x2, seg.y2)
                    KitePath.Segment.Close -> b.close()
                }
            }
        }
        // A clip path that yields no geometry clips everything away rather than
        // nothing, so content can never flood past a clip it was given (#175).
        return b.build()
    }

    /**
     * The paint a `<clipPath>` gives its children. They inherit from the clip path's
     * own ancestors, not from the element that uses it (SVG 1.1, 14.3.5, #269).
     * Percentages keep [user]'s viewport.
     */
    private fun clipPaintOf(def: KiteXmlNode.Element, user: Paint): Paint {
        val chain = generateSequence(def) { it.parent }.take(MAX_DEPTH).toList().asReversed()
        var p = Paint(viewport = user.viewport, viewportWidth = user.viewportWidth, viewportHeight = user.viewportHeight)
        for (e in chain) p = resolvePaint(e, p, container = true)
        return p
    }

    /** The device-space geometry one child of a `<clipPath>` contributes under [m]. */
    private fun clipShapesOf(c: KiteXmlNode.Element, m: KiteMatrix, paint: Paint): List<KitePath> = when (c.tag.lowercase()) {
        // A use contributes the shape it references, moved by its x and y (#175).
        "use" -> {
            val target = c.attrs["href"]?.trim()?.removePrefix("#")?.let { byId[it] }
            val shape = target?.let { shapeOf(it, resolvePaint(it, paint)) }
            if (target == null || shape == null) {
                emptyList()
            } else {
                val moved = compose(
                    compose(m, KiteMatrix.translation(num(c, "x", paint), num(c, "y", paint))),
                    styleOrAttr(target, "transform")?.let { parseTransform(it) } ?: KiteMatrix.IDENTITY,
                )
                listOf(transformPath(shape, moved))
            }
        }
        // Text has no outlines here, so each run clips to its em box, the same
        // stand-in PDF text clipping uses for a font it has no outlines for.
        "text" -> layoutText(c, paint, 0).map { run ->
            val fs = run.paint.fontSize
            transformPath(KitePath.Builder().apply { rectangle(run.x, run.y - 0.8 * fs, run.width, fs) }.build(), compose(m, turnOf(run)))
        }
        else -> shapeOf(c, paint)?.let { listOf(transformPath(it, m)) } ?: emptyList()
    }

    /** [minX, minY, maxX, maxY] of [el] in its own user space: a shape's, or its children's for a group. */
    private fun boundsOfElement(el: KiteXmlNode.Element, paint: Paint, depth: Int): DoubleArray? {
        if (depth > MAX_DEPTH) return null
        shapeOf(el, paint)?.let { return boundsOf(it) }
        val children = when (el.tag.lowercase()) {
            "g", "svg", "a", "symbol" -> el.children.filterIsInstance<KiteXmlNode.Element>()
            "switch" -> listOfNotNull(firstPassingChild(el))
            "use" -> {
                val target = el.attrs["href"]?.trim()?.removePrefix("#")?.let { byId[it] } ?: return null
                val tb = boundsOfElement(target, resolvePaint(target, paint), depth + 1) ?: return null
                return mapBounds(tb, compose(KiteMatrix.translation(num(el, "x", paint), num(el, "y", paint)), transformOf(target)))
            }
            else -> return null
        }
        var acc: DoubleArray? = null
        for (c in children) {
            val cb = boundsOfElement(c, resolvePaint(c, paint), depth + 1)?.let { mapBounds(it, transformOf(c)) } ?: continue
            val a = acc
            acc = if (a == null) cb else doubleArrayOf(minOf(a[0], cb[0]), minOf(a[1], cb[1]), maxOf(a[2], cb[2]), maxOf(a[3], cb[3]))
        }
        return acc
    }

    private fun transformOf(el: KiteXmlNode.Element): KiteMatrix =
        styleOrAttr(el, "transform")?.let { parseTransform(it) } ?: KiteMatrix.IDENTITY

    /** The box around [b] after [t] moves its four corners. */
    private fun mapBounds(b: DoubleArray, t: KiteMatrix): DoubleArray {
        val xs = DoubleArray(4)
        val ys = DoubleArray(4)
        for ((i, x) in doubleArrayOf(b[0], b[2], b[0], b[2]).withIndex()) {
            val y = if (i < 2) b[1] else b[3]
            xs[i] = t.a * x + t.c * y + t.e
            ys[i] = t.b * x + t.d * y + t.f
        }
        return doubleArrayOf(xs.min(), ys.min(), xs.max(), ys.max())
    }

    /**
     * The one child a `<switch>` renders: the first whose conditional attributes
     * all pass (SVG 1.1, 5.9, #173). This renderer supports no extensions and no
     * foreign objects, so a child that needs either fails and the fallback after
     * it draws. SVG 2 dropped `requiredFeatures`, so it always passes, as in
     * browsers. With no user locale to ask, English is the system language.
     */
    private fun firstPassingChild(sw: KiteXmlNode.Element): KiteXmlNode.Element? {
        for (c in sw.children) {
            if (c !is KiteXmlNode.Element) continue
            val tag = c.tag.lowercase()
            if (tag !in RENDERABLE || tag == "foreignobject") continue
            fun attr(name: String) = c.attrs[name] ?: c.attrs[name.lowercase()]
            if (attr("requiredExtensions") != null) continue
            val languages = attr("systemLanguage")
            if (languages != null && languages.split(',').none { it.trim().lowercase().substringBefore('-') == "en" }) continue
            return c
        }
        return null
    }

    /** The geometry of one shape element, in its own user coordinates. */
    private fun shapeOf(el: KiteXmlNode.Element, paint: Paint): KitePath? = when (el.tag.lowercase()) {
        "path" -> el.attrs["d"]?.let { parsePath(it) }
        "rect" -> rect(el.attrs, paint)
        "circle" -> ellipse(num(el, "cx", paint), num(el, "cy", paint), num(el, "r", paint), num(el, "r", paint))
        "ellipse" -> ellipse(num(el, "cx", paint), num(el, "cy", paint), num(el, "rx", paint), num(el, "ry", paint))
        "polygon" -> el.attrs["points"]?.let { polyline(it, close = true) }
        "polyline" -> el.attrs["points"]?.let { polyline(it, close = false) }
        else -> null
    }

    private fun paintShape(path: KitePath, ctm: KiteMatrix, paint: Paint, canvas: KiteCanvas, forceStroke: Boolean = false) {
        if (!paint.visible || path.segments.isEmpty()) return
        if (!forceStroke) {
            val gradient = paint.fillRef?.let { gradientFor(it, path) }
            if (gradient != null) {
                paintGradient(gradient.first, gradient.second, path, ctm, paint.opacity * paint.fillOpacity, canvas)
            } else {
                paint.fill?.let {
                    canvas.fillPath(path, ctm, it, paint.evenOdd, paint.opacity * paint.fillOpacity, KiteBlendMode.Normal)
                }
            }
        }
        // SVG 1.1, 11.4: a stroke width of 0 paints no stroke. The canvas would draw the PDF
        // hairline for it, one device pixel wide (#292).
        if (paint.strokeW <= 0.0) return
        // A gradient stroke fills the outline of the stroke. Its bounding box units are those
        // of the shape's own geometry (SVG 1.1, 7.11), so the gradient maps as for the fill.
        val strokeGradient = paint.strokeRef?.let { gradientFor(it, path) }
        if (strokeGradient != null) {
            val scale = sqrt(ctm.a * ctm.a + ctm.b * ctm.b + ctm.c * ctm.c + ctm.d * ctm.d)
            val outline = path.strokeOutline(
                paint.strokeW, paint.lineCap, paint.lineJoin, paint.miterLimit, paint.dash, paint.dashOffset,
                tolerance = if (scale > 0.0) 0.25 / scale else 0.1,
            )
            if (!outline.isEmpty()) {
                paintGradient(strokeGradient.first, strokeGradient.second, outline, ctm, paint.opacity * paint.strokeOpacity, canvas)
            }
            return
        }
        val sc = paint.strokeRef?.let { strokeColorOf(it) } ?: paint.stroke ?: if (forceStroke) RgbColor.BLACK else null
        sc?.let {
            canvas.strokePath(
                path, ctm, it, paint.strokeW, paint.opacity * paint.strokeOpacity, KiteBlendMode.Normal,
                dashArray = paint.dash, dashPhase = paint.dashOffset,
                lineCap = paint.lineCap, lineJoin = paint.lineJoin, miterLimit = paint.miterLimit,
            )
        }
    }

    /**
     * Fills [region], a path in user space under [ctm], with the gradient [g], whose
     * space maps to user space by [local]. The spread method continues the gradient over
     * the whole region (SVG 1.1, 13.2.2), and the opacity of the stops fades it through a
     * luminosity soft mask of their alphas (13.2.4). A gradient transform without an
     * inverse paints nothing.
     */
    private fun paintGradient(
        g: SvgGradient.Parsed, local: KiteMatrix, region: KitePath, ctm: KiteMatrix, alpha: Double, canvas: KiteCanvas,
    ) {
        val inverse = local.invert() ?: return
        // The canvas maps the fill region by the gradient's matrix, so the region goes in gradient space.
        val inGradient = transformPath(region, inverse)
        val gradientCtm = compose(ctm, local)
        val b = boundsOf(inGradient) ?: return
        val box = KiteRectangle(b[0], b[1], b[2], b[3])
        val shading = g.shading.spreadOver(g.spread, box)
        val opacity = g.opacity?.spreadOver(g.spread, box)
        if (opacity == null) {
            canvas.fillShading(shading, gradientCtm, inGradient, alpha, KiteBlendMode.Normal)
            return
        }
        canvas.applySoftMask(
            SoftMask.Kind.Luminosity, box, gradientCtm,
            render = { canvas.fillShading(shading, gradientCtm, inGradient, alpha, KiteBlendMode.Normal) },
            renderMask = { it.fillShading(opacity, gradientCtm, inGradient) },
        )
    }

    /**
     * The colour a gradient stroke paints with when the gradient cannot map onto the
     * shape: the gradient's middle. A shape with no area has no bounding box for the
     * gradient, and dropping the stroke made a shape whose only paint it was vanish (#90).
     */
    private fun strokeColorOf(id: String): RgbColor? {
        val def = byId[id] ?: return null
        return SvgGradient.parse(def, byId, styles::value)?.shading?.sampleStops(3)?.colors?.get(1)
    }

    /**
     * The shading a `url(#id)` fill resolves to, plus the matrix from the
     * gradient's space to the shape's user space. `objectBoundingBox` units
     * (the default) map the gradient's 0..1 box onto the shape's own bounds.
     */
    private fun gradientFor(id: String, path: KitePath): Pair<SvgGradient.Parsed, KiteMatrix>? {
        val def = byId[id] ?: return null
        val g = SvgGradient.parse(def, byId, styles::value) ?: return null
        var m = KiteMatrix.IDENTITY
        if (g.objectBoundingBox) {
            val b = boundsOf(path) ?: return null
            m = compose(m, KiteMatrix(b[2] - b[0], 0.0, 0.0, b[3] - b[1], b[0], b[1]))
        }
        g.transform?.let { m = compose(m, parseTransform(it)) }
        return g to m
    }

    /** [minX, minY, maxX, maxY] over the path's points, or null when it has no area. */
    private fun boundsOf(path: KitePath): DoubleArray? {
        var minX = Double.MAX_VALUE
        var minY = Double.MAX_VALUE
        var maxX = -Double.MAX_VALUE
        var maxY = -Double.MAX_VALUE
        fun add(x: Double, y: Double) {
            if (x < minX) minX = x
            if (x > maxX) maxX = x
            if (y < minY) minY = y
            if (y > maxY) maxY = y
        }
        for (seg in path.segments) when (seg) {
            is KitePath.Segment.MoveTo -> add(seg.x, seg.y)
            is KitePath.Segment.LineTo -> add(seg.x, seg.y)
            is KitePath.Segment.CurveTo -> { add(seg.x1, seg.y1); add(seg.x2, seg.y2); add(seg.x3, seg.y3) }
            is KitePath.Segment.QuadTo -> { add(seg.x1, seg.y1); add(seg.x2, seg.y2) }
            else -> {}
        }
        if (maxX <= minX || maxY <= minY) return null
        return doubleArrayOf(minX, minY, maxX, maxY)
    }

    /** [path] with every point pushed through [m]. */
    private fun transformPath(path: KitePath, m: KiteMatrix): KitePath {
        fun tx(x: Double, y: Double) = m.a * x + m.c * y + m.e
        fun ty(x: Double, y: Double) = m.b * x + m.d * y + m.f
        val b = KitePath.Builder()
        for (seg in path.segments) when (seg) {
            is KitePath.Segment.MoveTo -> b.moveTo(tx(seg.x, seg.y), ty(seg.x, seg.y))
            is KitePath.Segment.LineTo -> b.lineTo(tx(seg.x, seg.y), ty(seg.x, seg.y))
            is KitePath.Segment.CurveTo -> b.curveTo(
                tx(seg.x1, seg.y1), ty(seg.x1, seg.y1),
                tx(seg.x2, seg.y2), ty(seg.x2, seg.y2),
                tx(seg.x3, seg.y3), ty(seg.x3, seg.y3),
            )
            is KitePath.Segment.QuadTo -> b.quadTo(
                tx(seg.x1, seg.y1), ty(seg.x1, seg.y1),
                tx(seg.x2, seg.y2), ty(seg.x2, seg.y2),
            )
            else -> b.close()
        }
        return b.build()
    }

    /** The author's cascaded value, including embedded sheets (SVG 1.1, 6.4). */
    private fun styleOrAttr(el: KiteXmlNode.Element, name: String): String? = styles.value(el, name)

    /** The id inside `url(#id)`, or null when the value is not one. */
    private fun urlRef(raw: String?): String? {
        val s = raw?.trim() ?: return null
        if (!s.startsWith("url(")) return null
        return s.removePrefix("url(").substringBefore(')').trim().trim('"', '\'').removePrefix("#")
            .takeIf { it.isNotEmpty() }
    }

    /** The payload of a `data:` URI, Base64 or percent-encoded. */
    private fun dataUri(uri: String): ByteArray? {
        val comma = uri.indexOf(',')
        if (comma < 0) return null
        val meta = uri.substring(5, comma)
        val payload = uri.substring(comma + 1)
        return if (";base64" in meta) decodeBase64(payload) else percentDecode(payload)
    }

    private fun resolvePaint(el: KiteXmlNode.Element, p: Paint, container: Boolean = false): Paint {
        // Inherited paint properties use the parent's computed value, including
        // relative lengths and paint-server references (CSS 2.2, 6.2, #89).
        fun declaration(name: String): String? = styleOrAttr(el, name)?.takeUnless { it.equals("inherit", ignoreCase = true) }
        val current = declaration("color")?.let { CssValues.color(it) } ?: p.current
        val fillRaw = declaration("fill")
        val strokeRaw = declaration("stroke")
        // em lengths resolve against this element's font size, and font-size itself
        // against the parent's (#178).
        val fontSize = declaration("font-size")?.trim()?.let { raw ->
            if (raw.endsWith('%')) raw.dropLast(1).toDoubleOrNull()?.let { p.fontSize * it / 100.0 } else lengthOrNull(raw, p.fontSize)
        }?.takeIf { it > 0.0 && it.isFinite() } ?: p.fontSize
        return Paint(
            fill = paintValue(fillRaw, p.fill, current),
            stroke = paintValue(strokeRaw, p.stroke, current),
            strokeW = declaration("stroke-width")?.let { parseLen(it, fontSize, percentageBase("stroke-width", p)) } ?: p.strokeW,
            // A container's opacity composites it as a group in walk, so only a
            // single shape multiplies its own opacity into the paint (#91).
            opacity = if (container) p.opacity else (declaration("opacity")?.toDoubleOrNull() ?: 1.0) * p.opacity,
            evenOdd = when (declaration("fill-rule")) {
                "evenodd" -> true
                "nonzero" -> false
                else -> p.evenOdd
            },
            current = current,
            fillRef = if (fillRaw != null) urlRef(fillRaw) else p.fillRef,
            strokeRef = if (strokeRaw != null) urlRef(strokeRaw) else p.strokeRef,
            fillOpacity = declaration("fill-opacity")?.toDoubleOrNull() ?: p.fillOpacity,
            strokeOpacity = declaration("stroke-opacity")?.toDoubleOrNull() ?: p.strokeOpacity,
            fontSize = fontSize,
            fontSpec = fontSpecOf(
                declaration("font-family"), declaration("font-weight"),
                declaration("font-style"), p.fontSpec,
            ),
            textAnchor = declaration("text-anchor") ?: p.textAnchor,
            visible = when (declaration("visibility")) {
                "hidden", "collapse" -> false
                "visible" -> true
                else -> p.visible
            },
            // SVG 1.1, 11.4: the stroke properties are inherited like the rest (#92).
            dash = when (val raw = declaration("stroke-dasharray")?.trim()) {
                null, "inherit" -> p.dash
                else -> dashArrayOf(raw, fontSize, percentageBase("stroke-dasharray", p))
            },
            dashOffset = declaration("stroke-dashoffset")?.trim()?.takeIf { it != "inherit" }
                ?.let { parseLen(it, fontSize, percentageBase("stroke-dashoffset", p)) } ?: p.dashOffset,
            lineCap = when (declaration("stroke-linecap")?.trim()) {
                "butt" -> 0
                "round" -> 1
                "square" -> 2
                else -> p.lineCap
            },
            lineJoin = when (declaration("stroke-linejoin")?.trim()) {
                "miter", "miter-clip", "arcs" -> 0
                "round" -> 1
                "bevel" -> 2
                else -> p.lineJoin
            },
            miterLimit = declaration("stroke-miterlimit")?.toDoubleOrNull()?.takeIf { it >= 1.0 } ?: p.miterLimit,
            viewport = p.viewport,
            viewportWidth = p.viewportWidth, viewportHeight = p.viewportHeight,
        )
    }

    /**
     * `stroke-dasharray` (SVG 1.1, 11.4): an odd list repeats to make it even,
     * and `none`, a negative value or an all-zero list means solid.
     */
    private fun dashArrayOf(raw: String, fontSize: Double, reference: Double): List<Double>? {
        val s = raw.trim()
        if (s == "none") return null
        val values = s.split(',', ' ', '\t', '\n').filter { it.isNotBlank() }.map { parseLen(it, fontSize, reference) }
        if (values.isEmpty() || values.any { it < 0.0 } || values.all { it == 0.0 }) return null
        return if (values.size % 2 == 1) values + values else values
    }

    private fun fontSpecOf(family: String?, weight: String?, style: String?, inherited: FontSpec): FontSpec {
        if (family == null && weight == null && style == null) return inherited
        val fam = family?.lowercase()?.let { f ->
            when {
                "mono" in f || "courier" in f -> KiteFontFamily.Monospace
                "sans" in f || "arial" in f || "helvetica" in f -> KiteFontFamily.SansSerif
                "serif" in f || "times" in f || "georgia" in f -> KiteFontFamily.Serif
                else -> null
            }
        } ?: inherited.family
        val bold = weight?.let { it == "bold" || (it.toIntOrNull() ?: 400) >= 600 } ?: inherited.bold
        val italic = style?.let { it == "italic" || it == "oblique" } ?: inherited.italic
        return FontSpec(fam, bold, italic, family ?: inherited.name)
    }

    private fun paintValue(raw: String?, inherited: RgbColor?, current: RgbColor): RgbColor? = when {
        raw == null -> inherited
        raw == "none" -> null
        raw == "currentColor" -> current
        raw.startsWith("url(") -> inherited // gradient/pattern refs: fall back to the inherited solid
        else -> CssValues.color(raw) ?: inherited
    }

    // ---- shape builders (user coordinates) ----------------------------------

    private fun rect(a: Map<String, String>, paint: Paint): KitePath {
        val x = pLen(a, "x", paint); val y = pLen(a, "y", paint)
        val w = pLen(a, "width", paint); val h = pLen(a, "height", paint)
        var rx = a["rx"]?.let { parseLen(it, paint.fontSize, paint.viewportWidth) } ?: -1.0
        var ry = a["ry"]?.let { parseLen(it, paint.fontSize, paint.viewportHeight) } ?: -1.0
        if (rx < 0 && ry >= 0) rx = ry
        if (ry < 0 && rx >= 0) ry = rx
        rx = rx.coerceIn(0.0, w / 2); ry = ry.coerceIn(0.0, h / 2)
        val b = KitePath.Builder()
        if (rx <= 0.0 || ry <= 0.0) {
            b.moveTo(x, y); b.lineTo(x + w, y); b.lineTo(x + w, y + h); b.lineTo(x, y + h); b.close()
        } else {
            val k = 0.5522847498
            b.moveTo(x + rx, y)
            b.lineTo(x + w - rx, y); b.curveTo(x + w - rx + rx * k, y, x + w, y + ry - ry * k, x + w, y + ry)
            b.lineTo(x + w, y + h - ry); b.curveTo(x + w, y + h - ry + ry * k, x + w - rx + rx * k, y + h, x + w - rx, y + h)
            b.lineTo(x + rx, y + h); b.curveTo(x + rx - rx * k, y + h, x, y + h - ry + ry * k, x, y + h - ry)
            b.lineTo(x, y + ry); b.curveTo(x, y + ry - ry * k, x + rx - rx * k, y, x + rx, y)
            b.close()
        }
        return b.build()
    }

    private fun ellipse(cx: Double, cy: Double, rx: Double, ry: Double): KitePath {
        if (rx <= 0 || ry <= 0) return KitePath(emptyList())
        val k = 0.5522847498
        val b = KitePath.Builder()
        b.moveTo(cx + rx, cy)
        b.curveTo(cx + rx, cy + ry * k, cx + rx * k, cy + ry, cx, cy + ry)
        b.curveTo(cx - rx * k, cy + ry, cx - rx, cy + ry * k, cx - rx, cy)
        b.curveTo(cx - rx, cy - ry * k, cx - rx * k, cy - ry, cx, cy - ry)
        b.curveTo(cx + rx * k, cy - ry, cx + rx, cy - ry * k, cx + rx, cy)
        b.close()
        return b.build()
    }

    private fun polyline(points: String, close: Boolean): KitePath {
        val nums = numbers(points)
        val b = KitePath.Builder()
        var i = 0
        var first = true
        while (i + 1 < nums.size) {
            val x = nums[i]; val y = nums[i + 1]; i += 2
            if (first) { b.moveTo(x, y); first = false } else b.lineTo(x, y)
        }
        if (close && !first) b.close()
        return b.build()
    }

    // ---- helpers ------------------------------------------------------------

    private fun num(el: KiteXmlNode.Element, k: String, paint: Paint) = pLen(el.attrs, k, paint)
    private fun pLen(a: Map<String, String>, k: String, paint: Paint) =
        a[k]?.let { parseLen(it, paint.fontSize, percentageBase(k, paint)) } ?: 0.0

    /** SVG 2, section 8.9: x/width, y/height, and normalized-diagonal bases. */
    private fun percentageBase(name: String, paint: Paint): Double = when (name) {
        "x", "x1", "x2", "cx", "rx", "width" -> paint.viewportWidth
        "y", "y1", "y2", "cy", "ry", "height" -> paint.viewportHeight
        else -> sqrt((paint.viewportWidth * paint.viewportWidth + paint.viewportHeight * paint.viewportHeight) / 2.0)
    }

    public companion object {
        /** A `<use>` chain deeper than this is a cycle; stop rather than hang. */
        private const val MAX_DEPTH = 32

        /** Elements whose opacity composites their children as one group. */
        private val CONTAINERS = setOf("svg", "g", "a", "switch", "use")

        /** Direct children a `<switch>` may choose between. */
        private val RENDERABLE = setOf(
            "svg", "g", "a", "switch", "use", "image", "text", "path", "rect",
            "circle", "ellipse", "line", "polyline", "polygon", "foreignobject",
        )

        /** Standard Base64, tolerant of whitespace and missing padding. */
        private fun decodeBase64(text: String): ByteArray? {
            val out = ArrayList<Byte>(text.length * 3 / 4 + 3)
            var acc = 0
            var bits = 0
            for (c in text) {
                if (c == '=') break
                if (c.isWhitespace()) continue
                val v = when (c) {
                    in 'A'..'Z' -> c - 'A'
                    in 'a'..'z' -> c - 'a' + 26
                    in '0'..'9' -> c - '0' + 52
                    '+', '-' -> 62
                    '/', '_' -> 63
                    else -> return null
                }
                acc = (acc shl 6) or v
                bits += 6
                if (bits >= 8) { bits -= 8; out.add(((acc shr bits) and 0xFF).toByte()) }
            }
            return if (out.isEmpty()) null else out.toByteArray()
        }

        private fun percentDecode(text: String): ByteArray {
            val out = ArrayList<Byte>(text.length)
            var i = 0
            while (i < text.length) {
                val c = text[i]
                if (c == '%' && i + 2 < text.length) {
                    val v = text.substring(i + 1, i + 3).toIntOrNull(16)
                    if (v != null) { out.add(v.toByte()); i += 3; continue }
                }
                for (b in c.toString().encodeToByteArray()) out.add(b)
                i++
            }
            return out.toByteArray()
        }

        /** True when [bytes] open like an SVG file. */
        public fun isSvg(bytes: ByteArray): Boolean {
            val head = TextEncoding.decode(bytes.copyOfRange(0, minOf(bytes.size, 512)))
            return head.contains("<svg")
        }

        /** Parse a whole `.svg` file, or null when there is no `<svg>` in it. */
        public fun parse(bytes: ByteArray): SvgImage? {
            val root = runCatching { KiteXml.parse(TextEncoding.decode(bytes)) }.getOrNull() ?: return null
            return findSvg(root)?.let { fromElement(it) }
        }

        /** Build from an already-parsed `<svg>` element (inline SVG in XHTML). */
        public fun fromElement(svg: KiteXmlNode.Element): SvgImage? {
            if (!svg.tag.equals("svg", true)) return null
            // The XHTML parser lower-cases attribute names, so camelCase SVG
            // attributes (viewBox) arrive as "viewbox".
            val vb = (svg.attrs["viewBox"] ?: svg.attrs["viewbox"])?.let { s ->
                val n = numbers(s)
                if (n.size >= 4 && n.take(4).all(Double::isFinite)) {
                    doubleArrayOf(n[0], n[1], n[2], n[3])
                } else {
                    null
                }
            }
            val w = svg.attrs["width"]?.let { lenOrNull(it) } ?: vb?.get(2) ?: 300.0
            val h = svg.attrs["height"]?.let { lenOrNull(it) } ?: vb?.get(3) ?: 150.0
            if (!w.isFinite() || !h.isFinite() || w <= 0 || h <= 0) return null
            return SvgImage(svg, w, h, vb)
        }

        private fun findSvg(el: KiteXmlNode.Element): KiteXmlNode.Element? {
            val pending = ArrayList<KiteXmlNode.Element>()
            pending.add(el)
            while (pending.isNotEmpty()) {
                val current = pending.removeAt(pending.lastIndex)
                if (current.tag.equals("svg", true)) return current
                for (i in current.children.indices.reversed()) {
                    (current.children[i] as? KiteXmlNode.Element)?.let(pending::add)
                }
            }
            return null
        }

        /** A root width or height in user units, or null for a percentage or junk. */
        private fun lenOrNull(raw: String): Double? = lengthOrNull(raw, 16.0)

        /**
         * A list of lengths such as `x="10 20 30"`, or null when the attribute is absent,
         * empty, or holds a value it cannot read (then the attribute is ignored).
         */
        private fun lengthList(raw: String?, fontSize: Double, reference: Double): List<Double>? {
            val parts = raw?.trim()?.split(LIST_SEPARATOR)?.filter { it.isNotEmpty() }?.takeIf { it.isNotEmpty() } ?: return null
            return parts.map { part ->
                if (part.endsWith('%')) {
                    part.dropLast(1).toDoubleOrNull()?.takeIf { it.isFinite() }?.let { it * reference / 100.0 } ?: return null
                } else lengthOrNull(part, fontSize) ?: return null
            }
        }

        private val LIST_SEPARATOR = Regex("[\\s,]+")

        /** An `<image>` width or height in user units, or null for `auto` and for a value it cannot read. */
        private fun imageSizeOrNull(raw: String, fontSize: Double, reference: Double): Double? {
            val s = raw.trim()
            if (s == "auto") return null
            return if (s.endsWith('%')) {
                s.dropLast(1).toDoubleOrNull()?.takeIf { it.isFinite() }?.let { it * reference / 100.0 }
            } else lengthOrNull(s, fontSize)
        }

        private fun parseLen(raw: String, fontSize: Double = 16.0, reference: Double = 0.0): Double {
            val s = raw.trim()
            return if (s.endsWith('%')) {
                s.dropLast(1).toDoubleOrNull()?.takeIf { it.isFinite() }?.let { it * reference / 100.0 } ?: 0.0
            } else lengthOrNull(s, fontSize) ?: 0.0
        }

        /**
         * A length in user units, which are CSS pixels: 96 to the inch, so a point
         * is 4/3 of one (SVG 2, 8.9; CSS Values 3, 6.2), and em is [fontSize] (#178).
         * Null for a percentage, which needs the viewport, and for junk.
         */
        private fun lengthOrNull(raw: String, fontSize: Double): Double? {
            val s = raw.trim()
            s.toDoubleOrNull()?.let { return it }
            val unit = s.takeLastWhile { it.isLetter() || it == '%' }
            val value = s.dropLast(unit.length).trim().toDoubleOrNull() ?: return null
            return when (unit.lowercase()) {
                "px" -> value
                "pt" -> value * 4.0 / 3.0
                "pc" -> value * 16.0
                "in" -> value * 96.0
                "cm" -> value * 96.0 / 2.54
                "mm" -> value * 96.0 / 25.4
                "q" -> value * 96.0 / 101.6
                "em" -> value * fontSize
                "ex" -> value * fontSize / 2.0
                else -> null
            }
        }

        private fun numbers(s: String): DoubleArray {
            val out = ArrayList<Double>()
            var i = 0
            val n = s.length
            while (i < n) {
                val c = s[i]
                if (c.isDigit() || c == '-' || c == '+' || c == '.') {
                    val start = i
                    if (s[i] == '-' || s[i] == '+') i++
                    while (i < n && (s[i].isDigit() || s[i] == '.')) i++
                    if (i < n && (s[i] == 'e' || s[i] == 'E')) { i++; if (i < n && (s[i] == '-' || s[i] == '+')) i++; while (i < n && s[i].isDigit()) i++ }
                    s.substring(start, i).toDoubleOrNull()?.let { out.add(it) }
                } else i++
            }
            return out.toDoubleArray()
        }

        // Compose A ∘ B (apply B first, then A) in PDF affine convention.
        private fun compose(a: KiteMatrix, b: KiteMatrix): KiteMatrix = KiteMatrix(
            a.a * b.a + a.c * b.b,
            a.b * b.a + a.d * b.b,
            a.a * b.c + a.c * b.d,
            a.b * b.c + a.d * b.d,
            a.a * b.e + a.c * b.f + a.e,
            a.b * b.e + a.d * b.f + a.f,
        )

        private fun parseTransform(s: String): KiteMatrix {
            var m = KiteMatrix.IDENTITY
            var i = 0
            while (i < s.length) {
                val open = s.indexOf('(', i)
                if (open < 0) break
                val name = s.substring(i, open).trim().takeLastWhile { !it.isWhitespace() && it != ',' }
                val close = s.indexOf(')', open)
                if (close < 0) break
                val args = numbers(s.substring(open + 1, close))
                val t = when (name) {
                    "translate" -> KiteMatrix.translation(args.getOrElse(0) { 0.0 }, args.getOrElse(1) { 0.0 })
                    "scale" -> KiteMatrix.scaling(args.getOrElse(0) { 1.0 }, args.getOrElse(1) { args.getOrElse(0) { 1.0 } })
                    "rotate" -> {
                        val th = (args.getOrElse(0) { 0.0 }) * PI / 180.0
                        val rot = KiteMatrix(cos(th), sin(th), -sin(th), cos(th), 0.0, 0.0)
                        if (args.size >= 3) compose(KiteMatrix.translation(args[1], args[2]), compose(rot, KiteMatrix.translation(-args[1], -args[2]))) else rot
                    }
                    "matrix" -> if (args.size >= 6) KiteMatrix(args[0], args[1], args[2], args[3], args[4], args[5]) else KiteMatrix.IDENTITY
                    "skewx", "skewX" -> { val t = kotlin.math.tan(args.getOrElse(0) { 0.0 } * PI / 180.0); KiteMatrix(1.0, 0.0, t, 1.0, 0.0, 0.0) }
                    "skewy", "skewY" -> { val t = kotlin.math.tan(args.getOrElse(0) { 0.0 } * PI / 180.0); KiteMatrix(1.0, t, 0.0, 1.0, 0.0, 0.0) }
                    else -> KiteMatrix.IDENTITY
                }
                m = compose(m, t)
                i = close + 1
            }
            return m
        }

        // ---- SVG path `d` parser --------------------------------------------

        private fun parsePath(d: String): KitePath {
            val b = KitePath.Builder()
            val t = PathScanner(d)
            var cx = 0.0; var cy = 0.0     // current point
            var sx = 0.0; var sy = 0.0     // subpath start
            var pcx = 0.0; var pcy = 0.0   // last cubic control (for S)
            var pqx = 0.0; var pqy = 0.0   // last quad control (for T)
            var prev = ' '
            var open = false
            while (t.hasCmd()) {
                val cmd = t.cmd()
                val rel = cmd.isLowerCase()
                when (cmd.uppercaseChar()) {
                    'M' -> {
                        var first = true
                        while (t.hasNum()) {
                            var x = t.num(); var y = t.num()
                            if (rel) { x += cx; y += cy }
                            cx = x; cy = y
                            if (first) { b.moveTo(cx, cy); sx = cx; sy = cy; open = true; first = false } else b.lineTo(cx, cy)
                        }
                    }
                    'L' -> while (t.hasNum()) { var x = t.num(); var y = t.num(); if (rel) { x += cx; y += cy }; cx = x; cy = y; b.lineTo(cx, cy) }
                    'H' -> while (t.hasNum()) { var x = t.num(); if (rel) x += cx; cx = x; b.lineTo(cx, cy) }
                    'V' -> while (t.hasNum()) { var y = t.num(); if (rel) y += cy; cy = y; b.lineTo(cx, cy) }
                    'C' -> while (t.hasNum()) {
                        var x1 = t.num(); var y1 = t.num(); var x2 = t.num(); var y2 = t.num(); var x = t.num(); var y = t.num()
                        if (rel) { x1 += cx; y1 += cy; x2 += cx; y2 += cy; x += cx; y += cy }
                        b.curveTo(x1, y1, x2, y2, x, y); pcx = x2; pcy = y2; cx = x; cy = y
                    }
                    'S' -> while (t.hasNum()) {
                        var x2 = t.num(); var y2 = t.num(); var x = t.num(); var y = t.num()
                        if (rel) { x2 += cx; y2 += cy; x += cx; y += cy }
                        val x1 = if (prev.uppercaseChar() in "CS") 2 * cx - pcx else cx
                        val y1 = if (prev.uppercaseChar() in "CS") 2 * cy - pcy else cy
                        b.curveTo(x1, y1, x2, y2, x, y); pcx = x2; pcy = y2; cx = x; cy = y; prev = cmd
                    }
                    'Q' -> while (t.hasNum()) {
                        var x1 = t.num(); var y1 = t.num(); var x = t.num(); var y = t.num()
                        if (rel) { x1 += cx; y1 += cy; x += cx; y += cy }
                        b.quadTo(x1, y1, x, y); pqx = x1; pqy = y1; cx = x; cy = y
                    }
                    'T' -> while (t.hasNum()) {
                        var x = t.num(); var y = t.num()
                        if (rel) { x += cx; y += cy }
                        val x1 = if (prev.uppercaseChar() in "QT") 2 * cx - pqx else cx
                        val y1 = if (prev.uppercaseChar() in "QT") 2 * cy - pqy else cy
                        b.quadTo(x1, y1, x, y); pqx = x1; pqy = y1; cx = x; cy = y; prev = cmd
                    }
                    'A' -> while (t.hasNum()) {
                        val rx = t.num(); val ry = t.num(); val rot = t.num(); val large = t.num() != 0.0; val sweep = t.num() != 0.0
                        var x = t.num(); var y = t.num()
                        if (rel) { x += cx; y += cy }
                        arcTo(b, cx, cy, rx, ry, rot, large, sweep, x, y); cx = x; cy = y
                    }
                    'Z' -> { if (open) { b.close(); cx = sx; cy = sy; open = false } }
                }
                prev = cmd
            }
            return b.build()
        }

        /** Append an elliptical arc (SVG endpoint parameterisation) as cubic béziers. */
        private fun arcTo(
            b: KitePath.Builder, x0: Double, y0: Double, rxIn: Double, ryIn: Double,
            rotDeg: Double, large: Boolean, sweep: Boolean, x: Double, y: Double,
        ) {
            var rx = abs(rxIn); var ry = abs(ryIn)
            if (rx == 0.0 || ry == 0.0) { b.lineTo(x, y); return }
            val phi = rotDeg * PI / 180.0
            val cosP = cos(phi); val sinP = sin(phi)
            val dx = (x0 - x) / 2.0; val dy = (y0 - y) / 2.0
            val x1p = cosP * dx + sinP * dy
            val y1p = -sinP * dx + cosP * dy
            var lambda = x1p * x1p / (rx * rx) + y1p * y1p / (ry * ry)
            if (lambda > 1.0) { val s = sqrt(lambda); rx *= s; ry *= s }
            val sign = if (large != sweep) 1.0 else -1.0
            var num = rx * rx * ry * ry - rx * rx * y1p * y1p - ry * ry * x1p * x1p
            if (num < 0) num = 0.0
            val den = rx * rx * y1p * y1p + ry * ry * x1p * x1p
            val co = if (den == 0.0) 0.0 else sign * sqrt(num / den)
            val cxp = co * rx * y1p / ry
            val cyp = -co * ry * x1p / rx
            val cxc = cosP * cxp - sinP * cyp + (x0 + x) / 2.0
            val cyc = sinP * cxp + cosP * cyp + (y0 + y) / 2.0
            val t1 = angle(1.0, 0.0, (x1p - cxp) / rx, (y1p - cyp) / ry)
            var dTheta = angle((x1p - cxp) / rx, (y1p - cyp) / ry, (-x1p - cxp) / rx, (-y1p - cyp) / ry)
            if (!sweep && dTheta > 0) dTheta -= 2 * PI
            if (sweep && dTheta < 0) dTheta += 2 * PI
            val segs = ceil(abs(dTheta) / (PI / 2.0)).toInt().coerceAtLeast(1)
            val delta = dTheta / segs
            val tk = 4.0 / 3.0 * kotlin.math.tan(delta / 4.0)
            var theta = t1
            for (s in 0 until segs) {
                val cosT = cos(theta); val sinT = sin(theta)
                val cosT2 = cos(theta + delta); val sinT2 = sin(theta + delta)
                val e1x = cxc + rx * cosP * cosT - ry * sinP * sinT
                val e1y = cyc + rx * sinP * cosT + ry * cosP * sinT
                val e2x = cxc + rx * cosP * cosT2 - ry * sinP * sinT2
                val e2y = cyc + rx * sinP * cosT2 + ry * cosP * sinT2
                val d1x = -rx * cosP * sinT - ry * sinP * cosT
                val d1y = -rx * sinP * sinT + ry * cosP * cosT
                val d2x = -rx * cosP * sinT2 - ry * sinP * cosT2
                val d2y = -rx * sinP * sinT2 + ry * cosP * cosT2
                b.curveTo(e1x + tk * d1x, e1y + tk * d1y, e2x - tk * d2x, e2y - tk * d2y, e2x, e2y)
                theta += delta
            }
        }

        private fun angle(ux: Double, uy: Double, vx: Double, vy: Double): Double {
            val dot = ux * vx + uy * vy
            val len = sqrt((ux * ux + uy * uy) * (vx * vx + vy * vy))
            var a = kotlin.math.acos((dot / len).coerceIn(-1.0, 1.0))
            if (ux * vy - uy * vx < 0) a = -a
            return a
        }
    }

    /** Cursor over an SVG path `d` string: command letters + numbers with SVG's lax separators. */
    private class PathScanner(private val s: String) {
        private var i = 0
        private fun skipSep() { while (i < s.length && (s[i] == ',' || s[i].isWhitespace())) i++ }
        fun hasCmd(): Boolean { skipSep(); return i < s.length }
        fun cmd(): Char { skipSep(); return s[i++] }
        fun hasNum(): Boolean {
            skipSep()
            if (i >= s.length) return false
            val c = s[i]
            return c.isDigit() || c == '-' || c == '+' || c == '.'
        }
        fun num(): Double {
            skipSep()
            val start = i
            if (i < s.length && (s[i] == '-' || s[i] == '+')) i++
            while (i < s.length && (s[i].isDigit() || s[i] == '.')) i++
            if (i < s.length && (s[i] == 'e' || s[i] == 'E')) { i++; if (i < s.length && (s[i] == '-' || s[i] == '+')) i++; while (i < s.length && s[i].isDigit()) i++ }
            return s.substring(start, i).toDoubleOrNull() ?: 0.0
        }
    }

}
