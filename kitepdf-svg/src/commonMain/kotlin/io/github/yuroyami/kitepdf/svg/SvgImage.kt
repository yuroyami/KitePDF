package io.github.yuroyami.kitepdf.svg

import io.github.yuroyami.kitepdf.core.xml.KiteXml
import io.github.yuroyami.kitepdf.core.xml.KiteXmlNode

import io.github.yuroyami.kitepdf.core.render.KiteBlendMode
import io.github.yuroyami.kitepdf.core.render.KiteMatrix
import io.github.yuroyami.kitepdf.core.render.KiteCanvas
import io.github.yuroyami.kitepdf.core.render.KitePath
import io.github.yuroyami.kitepdf.core.css.CssValues
import io.github.yuroyami.kitepdf.core.font.FontSpec
import io.github.yuroyami.kitepdf.core.font.KiteFontFamily
import io.github.yuroyami.kitepdf.core.render.KiteImageData
import io.github.yuroyami.kitepdf.core.render.KiteShading
import io.github.yuroyami.kitepdf.core.render.RgbColor
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
 * gradients as paint; and `clip-path`.
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

    /** Every element carrying an `id`, for `<use>`, gradients and `clip-path`. */
    private val byId: Map<String, KiteXmlNode.Element> by lazy {
        val out = LinkedHashMap<String, KiteXmlNode.Element>()
        fun scan(el: KiteXmlNode.Element) {
            el.attrs["id"]?.let { if (it.isNotEmpty()) out.putIfAbsent(it, el) }
            for (c in el.children) if (c is KiteXmlNode.Element) scan(c)
        }
        scan(root)
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
        val base = if (vb != null && vb[2] > 0 && vb[3] > 0) {
            // viewBox coords -> viewport: translate(-min) then scale(size/vb).
            compose(ctm, compose(KiteMatrix.scaling(width / vb[2], height / vb[3]), KiteMatrix.translation(-vb[0], -vb[1])))
        } else ctm
        walk(root, base, Paint(), canvas, loadResource, depth = 0)
    }

    private class Paint(
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
        if (isHidden(el)) return
        val ctm = el.attrs["transform"]?.let { compose(parentCtm, parseTransform(it)) } ?: parentCtm
        val paint = resolvePaint(el.attrs, parent)
        val clip = clipPathOf(el, ctm)
        if (clip != null) canvas.pushClip(clip, KiteMatrix.IDENTITY, evenOdd = false)
        try {
            paintElement(el, ctm, paint, canvas, load, depth)
        } finally {
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
            "svg", "g", "a", "switch" ->
                for (c in el.children) if (c is KiteXmlNode.Element) walk(c, ctm, paint, canvas, load, depth + 1)
            "use" -> drawUse(el, ctm, paint, canvas, load, depth)
            "image" -> drawImage(el, ctm, paint, canvas, load)
            "text" -> drawText(el, ctm, paint, canvas, depth)
            "path" -> el.attrs["d"]?.let { paintShape(parsePath(it), ctm, paint, canvas) }
            "rect" -> paintShape(rect(el.attrs), ctm, paint, canvas)
            "circle" -> paintShape(ellipse(num(el, "cx"), num(el, "cy"), num(el, "r"), num(el, "r")), ctm, paint, canvas)
            "ellipse" -> paintShape(ellipse(num(el, "cx"), num(el, "cy"), num(el, "rx"), num(el, "ry")), ctm, paint, canvas)
            "line" -> paintShape(
                KitePath.Builder().apply { moveTo(num(el, "x1"), num(el, "y1")); lineTo(num(el, "x2"), num(el, "y2")) }.build(),
                ctm, paint, canvas, forceStroke = true,
            )
            "polyline" -> el.attrs["points"]?.let { paintShape(polyline(it, close = false), ctm, paint, canvas) }
            "polygon" -> el.attrs["points"]?.let { paintShape(polyline(it, close = true), ctm, paint, canvas) }
        }
    }

    /** `display:none` and `visibility:hidden` keep an element off the canvas. */
    private fun isHidden(el: KiteXmlNode.Element): Boolean =
        styleOrAttr(el, "display")?.trim() == "none" ||
            styleOrAttr(el, "visibility")?.trim().let { it == "hidden" || it == "collapse" }

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
        val moved = compose(ctm, KiteMatrix.translation(num(el, "x"), num(el, "y")))
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
        val href = el.attrs["href"]?.trim()?.takeIf { it.isNotEmpty() } ?: return
        val bytes = if (href.startsWith("data:")) dataUri(href) else load?.invoke(href)
        val image = bytes?.let { KiteImageData.fromEncodedImage(it) } ?: return
        val w = num(el, "width").takeIf { it > 0 } ?: image.width.toDouble()
        val h = num(el, "height").takeIf { it > 0 } ?: image.height.toDouble()
        // The image's unit square has row 0 at v=1, and SVG's y grows down, so
        // the placement matrix flips y the way a y-down page does.
        val placed = compose(ctm, KiteMatrix(w, 0.0, 0.0, -h, num(el, "x"), num(el, "y") + h))
        canvas.drawImage(image, placed, paint.opacity)
    }

    /**
     * `<text>` and its `<tspan>` children, laid out on one line from the
     * element's own (x, y) anchor. Per-tspan x/y move the pen.
     */
    private fun drawText(
        el: KiteXmlNode.Element,
        ctm: KiteMatrix,
        paint: Paint,
        canvas: KiteCanvas,
        depth: Int,
    ) {
        if (depth > MAX_DEPTH) return
        var penX = num(el, "x")
        val penY = num(el, "y")

        fun show(text: String, p: Paint, x: Double, y: Double): Double {
            val trimmed = text.replace('\n', ' ').replace('\t', ' ')
            if (trimmed.isBlank()) return 0.0
            val glyphs = SvgText.glyphs(trimmed.trim(), p.fontSpec)
            val runWidth = SvgText.width(glyphs, p.fontSize)
            val startX = x + SvgText.anchorShift(p.textAnchor, runWidth)
            // Text space is y-up; SVG is y-down, so the run is flipped in place.
            val textToDevice = compose(ctm, KiteMatrix(1.0, 0.0, 0.0, -1.0, startX, y))
            canvas.drawGlyphs(
                glyphs, p.fontSize, unitsPerEm = 1000, hasOutlines = false,
                fontSpec = p.fontSpec, textToDevice = textToDevice,
                color = p.fill ?: RgbColor.BLACK, alpha = p.opacity * p.fillOpacity,
            )
            return runWidth
        }

        for (child in el.children) when (child) {
            is KiteXmlNode.Text -> penX += show(child.text, paint, penX, penY)
            is KiteXmlNode.Element -> {
                if (child.tag.lowercase() != "tspan" || isHidden(child)) continue
                val sub = resolvePaint(child.attrs, paint)
                val sx = child.attrs["x"]?.let { parseLen(it) } ?: penX
                val sy = child.attrs["y"]?.let { parseLen(it) } ?: penY
                val text = child.children.filterIsInstance<KiteXmlNode.Text>().joinToString("") { it.text }
                penX = sx + show(text, sub, sx, sy)
            }
        }
    }

    /** The clip path an element's `clip-path="url(#id)"` names, already in device space. */
    private fun clipPathOf(el: KiteXmlNode.Element, ctm: KiteMatrix): KitePath? {
        val id = urlRef(styleOrAttr(el, "clip-path")) ?: return null
        val def = byId[id] ?: return null
        if (def.tag.lowercase() != "clippath") return null
        val b = KitePath.Builder()
        var any = false
        for (c in def.children) {
            if (c !is KiteXmlNode.Element) continue
            val sub = shapeOf(c) ?: continue
            // The clip is built in device space so one pushClip covers the lot.
            for (seg in transformPath(sub, compose(ctm, c.attrs["transform"]?.let { parseTransform(it) } ?: KiteMatrix.IDENTITY)).segments) {
                when (seg) {
                    is KitePath.Segment.MoveTo -> b.moveTo(seg.x, seg.y)
                    is KitePath.Segment.LineTo -> b.lineTo(seg.x, seg.y)
                    is KitePath.Segment.CurveTo -> b.curveTo(seg.x1, seg.y1, seg.x2, seg.y2, seg.x3, seg.y3)
                    is KitePath.Segment.QuadTo -> b.quadTo(seg.x1, seg.y1, seg.x2, seg.y2)
                    KitePath.Segment.Close -> b.close()
                }
            }
            any = true
        }
        return if (any) b.build() else null
    }

    /** The geometry of one shape element, in its own user coordinates. */
    private fun shapeOf(el: KiteXmlNode.Element): KitePath? = when (el.tag.lowercase()) {
        "path" -> el.attrs["d"]?.let { parsePath(it) }
        "rect" -> rect(el.attrs)
        "circle" -> ellipse(num(el, "cx"), num(el, "cy"), num(el, "r"), num(el, "r"))
        "ellipse" -> ellipse(num(el, "cx"), num(el, "cy"), num(el, "rx"), num(el, "ry"))
        "polygon" -> el.attrs["points"]?.let { polyline(it, close = true) }
        "polyline" -> el.attrs["points"]?.let { polyline(it, close = false) }
        else -> null
    }

    private fun paintShape(path: KitePath, ctm: KiteMatrix, paint: Paint, canvas: KiteCanvas, forceStroke: Boolean = false) {
        if (path.segments.isEmpty()) return
        if (!forceStroke) {
            val gradient = paint.fillRef?.let { gradientFor(it, path, ctm) }
            if (gradient != null) {
                canvas.fillShading(
                    gradient.first, gradient.second, transformPath(path, ctm),
                    paint.opacity * paint.fillOpacity, KiteBlendMode.Normal,
                )
            } else {
                paint.fill?.let {
                    canvas.fillPath(path, ctm, it, paint.evenOdd, paint.opacity * paint.fillOpacity, KiteBlendMode.Normal)
                }
            }
        }
        val sc = paint.stroke ?: if (forceStroke) RgbColor.BLACK else null
        sc?.let {
            canvas.strokePath(path, ctm, it, paint.strokeW, paint.opacity * paint.strokeOpacity, KiteBlendMode.Normal)
        }
    }

    /**
     * The shading a `url(#id)` fill resolves to, plus the matrix that puts it
     * where the shape is. `objectBoundingBox` units (the default) map the
     * gradient's 0..1 box onto the shape's own bounds.
     */
    private fun gradientFor(id: String, path: KitePath, ctm: KiteMatrix): Pair<KiteShading, KiteMatrix>? {
        val def = byId[id] ?: return null
        val g = SvgGradient.parse(def, byId) ?: return null
        var m = ctm
        if (g.objectBoundingBox) {
            val b = boundsOf(path) ?: return null
            m = compose(m, KiteMatrix(b[2] - b[0], 0.0, 0.0, b[3] - b[1], b[0], b[1]))
        }
        g.transform?.let { m = compose(m, parseTransform(it)) }
        return g.shading to m
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

    /** An SVG presentation value: a `style` declaration wins over the attribute. */
    private fun styleOrAttr(el: KiteXmlNode.Element, name: String): String? = declaration(el.attrs, name)

    private fun declaration(a: Map<String, String>, name: String): String? {
        a["style"]?.let { style ->
            for (part in style.split(';')) {
                val at = part.indexOf(':')
                if (at > 0 && part.substring(0, at).trim() == name) return part.substring(at + 1).trim()
            }
        }
        return a[name]
    }

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

    private fun resolvePaint(a: Map<String, String>, p: Paint): Paint {
        val current = declaration(a, "color")?.let { CssValues.color(it) } ?: p.current
        val fillRaw = declaration(a, "fill")
        val strokeRaw = declaration(a, "stroke")
        return Paint(
            fill = paintValue(fillRaw, p.fill, current),
            stroke = paintValue(strokeRaw, p.stroke, current),
            strokeW = declaration(a, "stroke-width")?.let { parseLen(it) } ?: p.strokeW,
            opacity = (declaration(a, "opacity")?.toDoubleOrNull() ?: 1.0) * p.opacity,
            evenOdd = when (declaration(a, "fill-rule")) {
                "evenodd" -> true
                "nonzero" -> false
                else -> p.evenOdd
            },
            current = current,
            fillRef = if (fillRaw != null) urlRef(fillRaw) else p.fillRef,
            strokeRef = if (strokeRaw != null) urlRef(strokeRaw) else p.strokeRef,
            fillOpacity = declaration(a, "fill-opacity")?.toDoubleOrNull() ?: p.fillOpacity,
            strokeOpacity = declaration(a, "stroke-opacity")?.toDoubleOrNull() ?: p.strokeOpacity,
            fontSize = declaration(a, "font-size")?.let { parseLen(it) } ?: p.fontSize,
            fontSpec = fontSpecOf(
                declaration(a, "font-family"), declaration(a, "font-weight"),
                declaration(a, "font-style"), p.fontSpec,
            ),
            textAnchor = declaration(a, "text-anchor") ?: p.textAnchor,
        )
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

    private fun rect(a: Map<String, String>): KitePath {
        val x = pLen(a, "x"); val y = pLen(a, "y"); val w = pLen(a, "width"); val h = pLen(a, "height")
        var rx = a["rx"]?.let { parseLen(it) } ?: -1.0
        var ry = a["ry"]?.let { parseLen(it) } ?: -1.0
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

    private fun num(el: KiteXmlNode.Element, k: String) = el.attrs[k]?.let { parseLen(it) } ?: 0.0
    private fun pLen(a: Map<String, String>, k: String) = a[k]?.let { parseLen(it) } ?: 0.0
    private fun parseLen(raw: String): Double {
        val s = raw.trim().removeSuffix("px")
        return s.toDoubleOrNull() ?: CssValues.length(raw, 12.0, 16.0, 0.0) ?: 0.0
    }

    public companion object {
        /** A `<use>` chain deeper than this is a cycle; stop rather than hang. */
        private const val MAX_DEPTH = 32

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
                val n = numbers(s); if (n.size >= 4) doubleArrayOf(n[0], n[1], n[2], n[3]) else null
            }
            val w = svg.attrs["width"]?.let { lenOrNull(it) } ?: vb?.get(2) ?: 300.0
            val h = svg.attrs["height"]?.let { lenOrNull(it) } ?: vb?.get(3) ?: 150.0
            if (w <= 0 || h <= 0) return null
            return SvgImage(svg, w, h, vb)
        }

        private fun findSvg(el: KiteXmlNode.Element): KiteXmlNode.Element? {
            if (el.tag.equals("svg", true)) return el
            for (c in el.children) if (c is KiteXmlNode.Element) findSvg(c)?.let { return it }
            return null
        }

        private fun lenOrNull(raw: String): Double? {
            val s = raw.trim().removeSuffix("px").removeSuffix("pt")
            return s.toDoubleOrNull()
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
