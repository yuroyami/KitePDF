package io.github.yuroyami.kitepdf.epub.script

import io.github.yuroyami.kitepdf.core.render.KiteMatrix
import io.github.yuroyami.kitepdf.core.render.KitePath
import io.github.yuroyami.kitepdf.core.KiteRectangle
import io.github.yuroyami.kitepdf.core.render.strokeOutline
import io.github.yuroyami.kitepdf.core.xml.KiteXmlNode
import io.github.yuroyami.kitepdf.epub.script.CanvasBitmap.Companion.element
import io.github.yuroyami.kitepdf.epub.script.CanvasBitmap.Companion.matrix
import io.github.yuroyami.kitepdf.epub.script.CanvasBitmap.Companion.num
import io.github.yuroyami.kitepdf.epub.script.CanvasBitmap.Companion.pathData
import io.github.yuroyami.kitepdf.svg.SvgImage
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/** What a canvas can draw: an image file of the book, or another canvas as it is now. */
internal sealed class CanvasSource {
    abstract val width: Double
    abstract val height: Double

    /** An image at [href], a path that the chapter's SVG loader resolves. */
    class Image(val href: String, override val width: Double, override val height: Double) : CanvasSource()

    /** Another canvas, as a group from [CanvasBitmap.copyFor], or null when it is blank. */
    class Drawing(val content: KiteXmlNode.Element?, override val width: Double, override val height: Double) : CanvasSource()
}

/** A `CanvasGradient`: its geometry, and its stops in the order they came (HTML, 4.12.5.1.10). */
internal class CanvasGradient(val kind: String, val params: DoubleArray) {
    val stops = ArrayList<Pair<Double, CanvasColor>>()
}

/** A `CanvasPattern`: what it repeats, how, and its own matrix (HTML, 4.12.5.1.11). */
internal class CanvasPattern(val source: CanvasSource, val repetition: String) {
    var transform: KiteMatrix = KiteMatrix.IDENTITY
}

/** A fill or stroke style. [handle] names a gradient or pattern to the script side. */
internal sealed class CanvasPaint {
    class Solid(val color: CanvasColor) : CanvasPaint()
    class Gradient(val gradient: CanvasGradient, val handle: Int) : CanvasPaint()
    class Pattern(val pattern: CanvasPattern, val handle: Int) : CanvasPaint()
}

/** The drawing state that `save` keeps and `restore` brings back (HTML, 4.12.5.1.2). */
internal class CanvasState {
    var transform: KiteMatrix = KiteMatrix.IDENTITY
    var clip: List<CanvasClip> = emptyList()
    var fill: CanvasPaint = CanvasPaint.Solid(CanvasColor.BLACK)
    var stroke: CanvasPaint = CanvasPaint.Solid(CanvasColor.BLACK)
    var lineWidth = 1.0
    var lineCap = "butt"
    var lineJoin = "miter"
    var miterLimit = 10.0
    var dash: List<Double> = emptyList()
    var dashOffset = 0.0
    var alpha = 1.0
    var composite = "source-over"
    var shadowBlur = 0.0
    var shadowColor = CanvasColor.TRANSPARENT
    var shadowX = 0.0
    var shadowY = 0.0
    var font = CanvasFont.DEFAULT
    var textAlign = "start"
    var textBaseline = "alphabetic"
    var direction = "ltr"
    var filter = "none"
    var smoothing = true
    var smoothingQuality = "low"
    var letterSpacing = "0px"
    var wordSpacing = "0px"
    var fontKerning = "auto"
    var fontStretch = "normal"
    var fontVariantCaps = "normal"
    var textRendering = "auto"
    var lang = "inherit"

    fun copy(): CanvasState = CanvasState().also { c ->
        c.transform = transform; c.clip = clip; c.fill = fill; c.stroke = stroke
        c.lineWidth = lineWidth; c.lineCap = lineCap; c.lineJoin = lineJoin; c.miterLimit = miterLimit
        c.dash = dash; c.dashOffset = dashOffset; c.alpha = alpha; c.composite = composite
        c.shadowBlur = shadowBlur; c.shadowColor = shadowColor; c.shadowX = shadowX; c.shadowY = shadowY
        c.font = font; c.textAlign = textAlign; c.textBaseline = textBaseline; c.direction = direction
        c.filter = filter; c.smoothing = smoothing; c.smoothingQuality = smoothingQuality
        c.letterSpacing = letterSpacing; c.wordSpacing = wordSpacing; c.fontKerning = fontKerning
        c.fontStretch = fontStretch; c.fontVariantCaps = fontVariantCaps; c.textRendering = textRendering; c.lang = lang
    }
}

/** An error the script side throws: a DOMException [name], or a `TypeError` or `RangeError`, with its [message]. */
internal class CanvasError(val name: String, val message: String)

/**
 * The 2D context of one canvas element (HTML, 4.12.5.1). It keeps its state and its current
 * path, and draws into [bitmap] as SVG.
 */
internal class CanvasContext(val canvas: KiteXmlNode.Element, private val host: CanvasHost, width: Int, height: Int) {
    val bitmap = CanvasBitmap(width, height)
    var state = CanvasState()
    private val stack = ArrayList<CanvasState>()
    val path = CanvasPath()

    /** Resets the context and wipes the bitmap, as a change of the canvas size does (HTML, 4.12.5.1.1). */
    fun reset(width: Int, height: Int) {
        bitmap.width = width
        bitmap.height = height
        bitmap.clear()
        state = CanvasState()
        stack.clear()
        path.clear()
    }

    fun save() { stack.add(state.copy()) }

    fun restore() {
        if (stack.isNotEmpty()) state = stack.removeAt(stack.lastIndex)
    }

    // ---- transforms ---------------------------------------------------------

    fun transform(m: KiteMatrix) {
        if (!finite(m)) return
        state.transform = state.transform.concat(m)
    }

    fun setTransform(m: KiteMatrix) {
        if (!finite(m)) return
        state.transform = m
    }

    // ---- drawing --------------------------------------------------------------

    /** The paint of [paint] for SVG attribute [prefix], with the global alpha, or null when it paints nothing. */
    private fun paintAttrs(paint: CanvasPaint, prefix: String, attrs: MutableMap<String, String>): Boolean {
        val alpha = state.alpha
        when (paint) {
            is CanvasPaint.Solid -> {
                if (paint.color.a == 0) return false
                attrs[prefix] = paint.color.hex()
                val a = paint.color.alpha * alpha
                if (a < 1.0) attrs["$prefix-opacity"] = num(a)
            }
            is CanvasPaint.Gradient -> {
                val id = gradientDef(paint.gradient) ?: return false
                attrs[prefix] = "url(#$id)"
                if (alpha < 1.0) attrs["$prefix-opacity"] = num(alpha)
            }
            is CanvasPaint.Pattern -> {
                val id = patternDef(paint.pattern) ?: return false
                attrs[prefix] = "url(#$id)"
                if (alpha < 1.0) attrs["$prefix-opacity"] = num(alpha)
            }
        }
        return true
    }

    private fun gradientDef(g: CanvasGradient): String? {
        val p = g.params
        if (g.stops.isEmpty()) return null
        val stops = g.stops.sortedBy { it.first }
        if (g.kind == "conic") return null
        val el = when (g.kind) {
            "linear" -> {
                if (p[0] == p[2] && p[1] == p[3]) return null
                element("linearGradient", "x1" to num(p[0]), "y1" to num(p[1]), "x2" to num(p[2]), "y2" to num(p[3]))
            }
            else -> {
                if (p[0] == p[3] && p[1] == p[4] && p[2] == p[5]) return null
                element(
                    "radialGradient", "fx" to num(p[0]), "fy" to num(p[1]), "fr" to num(p[2]),
                    "cx" to num(p[3]), "cy" to num(p[4]), "r" to num(p[5]),
                )
            }
        }
        el.attrs = el.attrs + ("gradientUnits" to "userSpaceOnUse") + ("gradientTransform" to matrix(state.transform))
        for ((offset, color) in stops) {
            el.children.add(element("stop", "offset" to num(offset), "stop-color" to color.hex(), "stop-opacity" to num(color.alpha)))
        }
        return bitmap.define(el)
    }

    private fun patternDef(p: CanvasPattern): String? {
        val w = p.source.width
        val h = p.source.height
        if (w <= 0 || h <= 0) return null
        val huge = 1e7
        val tileW = if (p.repetition == "repeat" || p.repetition == "repeat-x") w else huge
        val tileH = if (p.repetition == "repeat" || p.repetition == "repeat-y") h else huge
        val el = element(
            "pattern", "patternUnits" to "userSpaceOnUse", "x" to "0", "y" to "0",
            "width" to num(tileW), "height" to num(tileH), "patternTransform" to matrix(state.transform.concat(p.transform)),
        )
        el.children.add(sourceElement(p.source, 0.0, 0.0, w, h, 0.0, 0.0, w, h, "p${bitmap.id()}_") ?: return null)
        return bitmap.define(el)
    }

    /** [source]'s rectangle ([sx], [sy], [sw], [sh]) drawn into ([dx], [dy], [dw], [dh]) as SVG. */
    private fun sourceElement(
        source: CanvasSource, sx: Double, sy: Double, sw: Double, sh: Double,
        dx: Double, dy: Double, dw: Double, dh: Double, prefix: String,
    ): KiteXmlNode.Element? {
        val box = element(
            "svg", "x" to num(dx), "y" to num(dy), "width" to num(dw), "height" to num(dh),
            "viewBox" to "${num(sx)} ${num(sy)} ${num(sw)} ${num(sh)}", "preserveAspectRatio" to "none", "overflow" to "hidden",
        )
        when (source) {
            is CanvasSource.Image -> box.children.add(
                element("image", "href" to source.href, "width" to num(source.width), "height" to num(source.height), "preserveAspectRatio" to "none"),
            )
            is CanvasSource.Drawing -> box.children.add(CanvasBitmap.rename(source.content ?: return null, prefix))
        }
        return box
    }

    fun fill(target: KitePath, evenOdd: Boolean) {
        val attrs = linkedMapOf("d" to pathData(target))
        if (evenOdd) attrs["fill-rule"] = "evenodd"
        val paint = state.fill
        if (paint is CanvasPaint.Gradient && paint.gradient.kind == "conic") {
            emit(conic(paint.gradient, target, evenOdd), target.bounds())
            return
        }
        val paints = paintAttrs(paint, "fill", attrs)
        emit(if (paints) KiteXmlNode.Element("path", attrs) else null, target.bounds())
    }

    fun stroke(target: KitePath) {
        val m = state.transform
        val inverse = m.invert() ?: return
        if (!(state.lineWidth > 0)) return
        val user = pathData(target, inverse)
        val paint = state.stroke
        val bounds = target.bounds()?.let { b ->
            val grow = state.lineWidth * max(m.scaleX(), m.scaleY()) * (if (state.lineJoin == "miter") max(1.0, state.miterLimit) else 1.0)
            KiteRectangle(b.left - grow, b.bottom - grow, b.right + grow, b.top + grow)
        }
        if (paint is CanvasPaint.Gradient && paint.gradient.kind == "conic") {
            val outline = KitePath(target.segments).let { t ->
                val u = transformPath(t, inverse).strokeOutline(state.lineWidth, capOf(), joinOf(), state.miterLimit, state.dash.takeIf { it.isNotEmpty() }, state.dashOffset)
                transformPath(u, m)
            }
            emit(conic(paint.gradient, outline, false), bounds)
            return
        }
        val attrs = linkedMapOf("d" to user, "fill" to "none", "transform" to matrix(m))
        lineAttrs(attrs)
        val paints = paintAttrs(paint, "stroke", attrs)
        emit(if (paints) KiteXmlNode.Element("path", attrs) else null, bounds)
    }

    /** The state's line width, caps, joins and dashes as stroke attributes. */
    private fun lineAttrs(attrs: MutableMap<String, String>) {
        attrs["stroke-width"] = num(state.lineWidth)
        if (state.lineCap != "butt") attrs["stroke-linecap"] = state.lineCap
        if (state.lineJoin != "miter") attrs["stroke-linejoin"] = state.lineJoin
        attrs["stroke-miterlimit"] = num(state.miterLimit)
        if (state.dash.isNotEmpty()) {
            attrs["stroke-dasharray"] = state.dash.joinToString(" ") { num(it) }
            if (state.dashOffset != 0.0) attrs["stroke-dashoffset"] = num(state.dashOffset)
        }
    }

    private fun capOf() = when (state.lineCap) { "round" -> 1; "square" -> 2; else -> 0 }
    private fun joinOf() = when (state.lineJoin) { "round" -> 1; "bevel" -> 2; else -> 0 }

    /** A conic gradient over [shape]: thin wedges of colour around the centre, clipped to the shape. */
    private fun conic(g: CanvasGradient, shape: KitePath, evenOdd: Boolean): KiteXmlNode.Element? {
        if (g.stops.isEmpty()) return null
        val stops = g.stops.sortedBy { it.first }
        val m = state.transform
        val (start, cx, cy) = Triple(g.params[0], g.params[1], g.params[2])
        val inverse = m.invert() ?: return null
        var reach = 0.0
        for ((x, y) in listOf(0.0 to 0.0, bitmap.width.toDouble() to 0.0, 0.0 to bitmap.height.toDouble(), bitmap.width.toDouble() to bitmap.height.toDouble())) {
            reach = max(reach, hypot(inverse.transformX(x, y) - cx, inverse.transformY(x, y) - cy))
        }
        reach = reach * 1.5 + 1
        val clip = element("clipPath", "clipPathUnits" to "userSpaceOnUse")
        clip.children.add(element("path", "d" to pathData(shape), "clip-rule" to if (evenOdd) "evenodd" else "nonzero"))
        val clipId = bitmap.define(clip)
        val group = element("g", "clip-path" to "url(#$clipId)")
        val wedges = element("g", "transform" to matrix(m))
        val n = 360
        for (i in 0 until n) {
            val t0 = i.toDouble() / n
            val t1 = (i + 1).toDouble() / n
            val color = colorAt(stops, (t0 + t1) / 2)
            if (color.a == 0) continue
            // Each wedge reaches a little past its neighbour, so no seam shows between them.
            val a0 = start + t0 * 2 * PI - 0.002
            val a1 = start + t1 * 2 * PI + 0.002
            val d = "M${num(cx)} ${num(cy)} L${num(cx + reach * cos(a0))} ${num(cy + reach * sin(a0))} L${num(cx + reach * cos(a1))} ${num(cy + reach * sin(a1))} Z"
            val attrs = linkedMapOf("d" to d, "fill" to color.hex())
            val a = color.alpha * state.alpha
            if (a < 1.0) attrs["fill-opacity"] = num(a)
            wedges.children.add(KiteXmlNode.Element("path", attrs))
        }
        group.children.add(wedges)
        return group
    }

    private fun colorAt(stops: List<Pair<Double, CanvasColor>>, t: Double): CanvasColor {
        if (t <= stops.first().first) return stops.first().second
        if (t >= stops.last().first) return stops.last().second
        for (i in 1 until stops.size) {
            val (o1, c1) = stops[i]
            val (o0, c0) = stops[i - 1]
            if (t <= o1) {
                val f = if (o1 == o0) 1.0 else (t - o0) / (o1 - o0)
                fun mix(a: Int, b: Int) = (a + (b - a) * f).let { kotlin.math.round(it).toInt() }
                return CanvasColor(mix(c0.r, c1.r), mix(c0.g, c1.g), mix(c0.b, c1.b), mix(c0.a, c1.a))
            }
        }
        return stops.last().second
    }

    /**
     * Puts [el] on the canvas with the filter, the shadow and the composite operation of the
     * state, in the clip (HTML, 4.12.5.1.21). [bounds] is where it draws in canvas pixels, which
     * keeps a shadow's filter small. A null [el] draws nothing, which some operations still use.
     */
    private fun emit(el: KiteXmlNode.Element?, bounds: KiteRectangle?) {
        val op = state.composite
        if (el == null && op in KEEPS_DESTINATION) return
        var node = el ?: element("g")
        if (el != null && state.filter != "none") node = element("g", "style" to "filter:${state.filter}").also { it.children.add(node) }
        if (el != null) shadow(bounds)?.let { id -> node = element("g", "filter" to "url(#$id)").also { it.children.add(node) } }
        host.changed()
        val clip = state.clip
        when (op) {
            "source-over" -> bitmap.draw(node, clip)
            "destination-over" -> bitmap.drawUnder(node, clip)
            "destination-out" -> bitmap.erase(silhouette(node), clip)
            "copy" -> {
                bitmap.erase(coverAll(), clip)
                bitmap.draw(node, clip)
            }
            "multiply", "screen", "overlay", "darken", "lighten", "color-dodge", "color-burn", "hard-light",
            "soft-light", "difference", "exclusion", "hue", "saturation", "color", "luminosity",
            -> {
                bitmap.noteBlend()
                bitmap.draw(element("g", "style" to "mix-blend-mode:$op").also { it.children.add(node) }, clip)
            }
            else -> composite(op, node, clip)
        }
    }

    /** The operations that read the canvas as an image: each source or destination masked by the other's alpha. */
    private fun composite(op: String, source: KiteXmlNode.Element, clip: List<CanvasClip>) {
        bitmap.recompose { dest ->
            val destId = dest?.let { d -> bitmap.define(element("g").also { it.children.addAll(d.children) }) }
            val sourceId = bitmap.define(element("g").also { it.children.add(bitmap.wrapClip(source, clip)) })
            fun use(id: String) = element("use", "href" to "#$id")
            /** [id] masked by the alpha of [by], or by one minus it when [inverse]. */
            fun masked(id: String, by: String?, inverse: Boolean): KiteXmlNode.Element {
                if (by == null) return if (inverse) use(id) else element("g")
                val mask = element(
                    "mask", "maskUnits" to "userSpaceOnUse", "maskContentUnits" to "userSpaceOnUse",
                    "x" to "0", "y" to "0", "width" to "${bitmap.width}", "height" to "${bitmap.height}",
                )
                if (inverse) {
                    mask.children.add(element("rect", "width" to "${bitmap.width}", "height" to "${bitmap.height}", "fill" to "#ffffff"))
                    mask.children.add(silhouette(use(by)))
                } else {
                    mask.attrs = mask.attrs + ("style" to "mask-type:alpha")
                    mask.children.add(use(by))
                }
                val maskId = bitmap.define(mask)
                return element("g", "mask" to "url(#$maskId)").also { it.children.add(use(id)) }
            }
            // Outside the clip the destination stays as it was.
            val outside = if (clip.isEmpty() || destId == null) null else outsideClip(destId, clip)
            val out = element("g")
            when (op) {
                "source-in" -> out.children.add(masked(sourceId, destId, inverse = false))
                "source-out" -> out.children.add(masked(sourceId, destId, inverse = true))
                "source-atop" -> {
                    destId?.let { out.children.add(use(it)) }
                    out.children.add(masked(sourceId, destId, inverse = false))
                }
                "destination-in" -> destId?.let { out.children.add(masked(it, sourceId, inverse = false)) }
                "destination-atop" -> {
                    out.children.add(masked(sourceId, destId, inverse = true))
                    destId?.let { out.children.add(masked(it, sourceId, inverse = false)) }
                }
                "xor" -> {
                    destId?.let { out.children.add(masked(it, sourceId, inverse = true)) }
                    out.children.add(masked(sourceId, destId, inverse = true))
                }
                // `lighter` adds the premultiplied colours, which feComposite's arithmetic does with k2 = k3 = 1.
                "lighter" -> if (destId == null) out.children.add(use(sourceId)) else {
                    val w = "${bitmap.width}"
                    val h = "${bitmap.height}"
                    val filter = element(
                        "filter", "filterUnits" to "userSpaceOnUse", "primitiveUnits" to "userSpaceOnUse",
                        "x" to "0", "y" to "0", "width" to w, "height" to h,
                    )
                    // A canvas adds in sRGB. The property goes on the primitive, since this tree has no parent links.
                    filter.children.add(element("feImage", "href" to "#$sourceId", "x" to "0", "y" to "0", "width" to w, "height" to h, "result" to "source"))
                    filter.children.add(
                        element(
                            "feComposite", "in" to "SourceGraphic", "in2" to "source", "operator" to "arithmetic",
                            "k1" to "0", "k2" to "1", "k3" to "1", "k4" to "0", "color-interpolation-filters" to "sRGB",
                        ),
                    )
                    val id = bitmap.define(filter)
                    out.children.add(element("g", "filter" to "url(#$id)").also { it.children.add(use(destId)) })
                }
                else -> {
                    destId?.let { out.children.add(use(it)) }
                    out.children.add(use(sourceId))
                }
            }
            if (outside != null) {
                val inside = element("g").also { g -> g.children.addAll(out.children) }
                out.children.clear()
                out.children.add(outside)
                out.children.add(bitmap.wrapClip(inside, clip))
            }
            out
        }
    }

    /** The destination [destId] where [clip] does not reach: masked by the clip's area turned black. */
    private fun outsideClip(destId: String, clip: List<CanvasClip>): KiteXmlNode.Element {
        val mask = element(
            "mask", "maskUnits" to "userSpaceOnUse", "maskContentUnits" to "userSpaceOnUse",
            "x" to "0", "y" to "0", "width" to "${bitmap.width}", "height" to "${bitmap.height}",
        )
        mask.children.add(element("rect", "width" to "${bitmap.width}", "height" to "${bitmap.height}", "fill" to "#ffffff"))
        mask.children.add(bitmap.wrapClip(coverAll(), clip))
        val id = bitmap.define(mask)
        return element("g", "mask" to "url(#$id)").also { it.children.add(element("use", "href" to "#$destId")) }
    }

    /** A black rectangle over the whole canvas and past it. */
    private fun coverAll(): KiteXmlNode.Element =
        element("rect", "x" to "-1", "y" to "-1", "width" to "${bitmap.width + 2}", "height" to "${bitmap.height + 2}", "fill" to "#000000")

    /** [node] in black with its own alpha, which a luminance mask reads as how much to take away. */
    private fun silhouette(node: KiteXmlNode.Element): KiteXmlNode.Element {
        val filter = element(
            "filter", "filterUnits" to "userSpaceOnUse", "x" to "0", "y" to "0",
            "width" to "${bitmap.width}", "height" to "${bitmap.height}",
        )
        filter.children.add(element("feColorMatrix", "type" to "matrix", "values" to "0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 1 0"))
        val id = bitmap.define(filter)
        return element("g", "filter" to "url(#$id)").also { it.children.add(node) }
    }

    /** The filter that draws the state's shadow under what it wraps, or null when no shadow shows (HTML, 4.12.5.1.19). */
    private fun shadow(bounds: KiteRectangle?): String? {
        val color = state.shadowColor
        if (color.a == 0 || (state.shadowBlur == 0.0 && state.shadowX == 0.0 && state.shadowY == 0.0)) return null
        val sigma = state.shadowBlur / 2
        val margin = sigma * 3 + 1
        val w = bitmap.width.toDouble()
        val h = bitmap.height.toDouble()
        // The region covers the shape, its shadow and the blur around both, inside the canvas.
        val b = bounds ?: KiteRectangle(0.0, 0.0, w, h)
        val left = max(-margin, min(b.left, b.left + state.shadowX) - margin)
        val top = max(-margin, min(b.bottom, b.bottom + state.shadowY) - margin)
        val right = min(w + margin, max(b.right, b.right + state.shadowX) + margin)
        val bottom = min(h + margin, max(b.top, b.top + state.shadowY) + margin)
        if (right <= left || bottom <= top) return null
        val filter = element(
            "filter", "filterUnits" to "userSpaceOnUse",
            "x" to num(left), "y" to num(top), "width" to num(right - left), "height" to num(bottom - top),
        )
        filter.children.add(
            element(
                "feDropShadow", "dx" to num(state.shadowX), "dy" to num(state.shadowY), "stdDeviation" to num(sigma),
                "flood-color" to color.hex(), "flood-opacity" to num(color.alpha), "color-interpolation-filters" to "sRGB",
            ),
        )
        return bitmap.define(filter)
    }

    fun fillRect(x: Double, y: Double, w: Double, h: Double) {
        if (!finite(x, y, w, h) || w == 0.0 || h == 0.0) return
        fill(rectPath(x, y, w, h), false)
    }

    fun strokeRect(x: Double, y: Double, w: Double, h: Double) {
        if (!finite(x, y, w, h)) return
        if (w == 0.0 && h == 0.0) return
        stroke(rectPath(x, y, w, h))
    }

    /** `clearRect`: a transparent black rectangle, through the clip, with no shadow, alpha or operation. */
    fun clearRect(x: Double, y: Double, w: Double, h: Double) {
        if (!finite(x, y, w, h) || w == 0.0 || h == 0.0) return
        val rect = rectPath(x, y, w, h)
        host.changed()
        if (state.clip.isEmpty() && coversCanvas(rect)) {
            bitmap.clear()
            return
        }
        bitmap.erase(element("path", "d" to pathData(rect), "fill" to "#000000"), state.clip)
    }

    private fun rectPath(x: Double, y: Double, w: Double, h: Double): KitePath = CanvasPath().also { it.rect(state.transform, x, y, w, h) }.path()

    /** Whether [rect], a quadrilateral in canvas pixels, holds the whole canvas. */
    private fun coversCanvas(rect: KitePath): Boolean {
        val w = bitmap.width.toDouble()
        val h = bitmap.height.toDouble()
        return listOf(0.0 to 0.0, w to 0.0, 0.0 to h, w to h).all { (px, py) -> CanvasPath.contains(rect, px, py, false) }
    }

    fun clip(target: KitePath, evenOdd: Boolean) {
        state.clip = state.clip + CanvasClip(target, evenOdd)
    }

    fun isPointInPath(target: KitePath, x: Double, y: Double, evenOdd: Boolean): Boolean = CanvasPath.contains(target, x, y, evenOdd)

    /** [target] in canvas pixels against the stroke the state would draw. */
    fun isPointInStroke(target: KitePath, x: Double, y: Double): Boolean {
        if (!x.isFinite() || !y.isFinite()) return false
        val inverse = state.transform.invert() ?: return false
        val user = transformPath(target, inverse)
        return CanvasPath.strokeContains(
            user, inverse.transformX(x, y), inverse.transformY(x, y), state.lineWidth, capOf(), joinOf(),
            state.miterLimit, state.dash, state.dashOffset,
        )
    }

    // ---- images ---------------------------------------------------------------

    fun drawImage(source: CanvasSource, sx0: Double, sy0: Double, sw0: Double, sh0: Double, dx0: Double, dy0: Double, dw0: Double, dh0: Double) {
        if (!finite(sx0, sy0, sw0, sh0, dx0, dy0, dw0, dh0)) return
        if (sw0 == 0.0 || sh0 == 0.0 || dw0 == 0.0 || dh0 == 0.0) return
        // A rectangle with a negative side is the same rectangle the other way round.
        val sx = min(sx0, sx0 + sw0)
        val sy = min(sy0, sy0 + sh0)
        val dx = min(dx0, dx0 + dw0)
        val dy = min(dy0, dy0 + dh0)
        val box = sourceElement(source, sx, sy, abs(sw0), abs(sh0), dx, dy, abs(dw0), abs(dh0), "i${bitmap.id()}_") ?: return
        val g = element("g", "transform" to matrix(state.transform))
        if (state.alpha < 1.0) g.attrs = g.attrs + ("opacity" to num(state.alpha))
        g.children.add(box)
        val m = state.transform
        val corners = listOf(dx to dy, dx + abs(dw0) to dy, dx to dy + abs(dh0), dx + abs(dw0) to dy + abs(dh0))
        val xs = corners.map { (x, y) -> m.transformX(x, y) }
        val ys = corners.map { (x, y) -> m.transformY(x, y) }
        emit(g, KiteRectangle(xs.min(), ys.min(), xs.max(), ys.max()))
    }

    // ---- text -----------------------------------------------------------------

    /** What `measureText` answers, and where a line starts and sits for `fillText` (HTML, 4.12.5.1.4). */
    class TextLayout(val text: String, val width: Double, val shiftX: Double, val shiftY: Double, val ascent: Double, val descent: Double)

    fun layout(raw: String): TextLayout {
        // Space characters are spaces; the canvas does not collapse them.
        val text = raw.map { if (it == '\t' || it == '\n' || it == '\u000C' || it == '\r') ' ' else it }.joinToString("")
        val font = state.font
        val size = font.sizePx
        val width = SvgImage.textAdvance(text, font.familyList, font.weight.toString(), if (font.italic) "italic" else "normal", size)
        val (ascentEm, descentEm) = metricsOf(font)
        val ascent = ascentEm * size
        val descent = descentEm * size
        val rtl = state.direction == "rtl"
        val shiftX = when (state.textAlign) {
            "center" -> -width / 2
            "right" -> -width
            "end" -> if (rtl) 0.0 else -width
            "start" -> if (rtl) -width else 0.0
            else -> 0.0
        }
        val shiftY = when (state.textBaseline) {
            "top" -> ascent
            "hanging" -> ascent * HANGING
            "middle" -> (ascent - descent) / 2
            "ideographic", "bottom" -> -descent
            else -> 0.0
        }
        return TextLayout(text, width, shiftX, shiftY, ascent, descent)
    }

    /** `measureText` as its twelve numbers, in the order of [METRICS]. */
    fun measure(raw: String): List<Double> {
        val t = layout(raw)
        val ink = t.ascent * INK
        return listOf(
            t.width, -t.shiftX, t.width + t.shiftX,
            t.ascent - t.shiftY, t.descent + t.shiftY,
            if (t.text.isBlank()) 0.0 else ink - t.shiftY, if (t.text.isBlank()) 0.0 else t.descent * INK + t.shiftY,
            0.0, 0.0,
            t.ascent * HANGING - t.shiftY, -t.shiftY, -t.descent - t.shiftY,
        )
    }

    fun drawText(raw: String, x: Double, y: Double, maxWidth: Double?, stroke: Boolean) {
        if (!finite(x, y)) return
        if (maxWidth != null && !(maxWidth > 0)) return
        val t = layout(raw)
        if (t.text.isEmpty()) return
        val scale = if (maxWidth != null && t.width > maxWidth) maxWidth / t.width else 1.0
        val m = state.transform.concat(KiteMatrix(scale, 0.0, 0.0, 1.0, x, y)).concat(KiteMatrix.translation(t.shiftX, t.shiftY))
        val font = state.font
        val attrs = linkedMapOf(
            "font-family" to font.familyList, "font-size" to num(font.sizePx), "transform" to matrix(m),
        )
        if (font.weight != 400) attrs["font-weight"] = font.weight.toString()
        if (font.italic) attrs["font-style"] = "italic"
        val paints = if (stroke) {
            if (!(state.lineWidth > 0)) return
            attrs["fill"] = "none"
            lineAttrs(attrs)
            paintAttrs(state.stroke, "stroke", attrs)
        } else {
            paintAttrs(state.fill, "fill", attrs)
        }
        // A no-break space keeps every space, since SVG text collapses them and drops one at the start.
        val el = KiteXmlNode.Element("text", attrs).also { it.children.add(KiteXmlNode.Text(t.text.replace(' ', ' '))) }
        val left = t.shiftX
        val corners = listOf(left to t.shiftY - t.ascent, left + t.width to t.shiftY - t.ascent, left to t.shiftY + t.descent, left + t.width to t.shiftY + t.descent)
        val base = state.transform.concat(KiteMatrix(scale, 0.0, 0.0, 1.0, x, y))
        val xs = corners.map { (px, py) -> base.transformX(px, py) }
        val ys = corners.map { (px, py) -> base.transformY(px, py) }
        emit(if (paints) el else null, KiteRectangle(xs.min(), ys.min(), xs.max(), ys.max()))
    }

    companion object {
        /** The operations that keep the destination where the source draws nothing. */
        private val KEEPS_DESTINATION = setOf(
            "source-over", "destination-over", "source-atop", "destination-out", "xor", "lighter",
            "multiply", "screen", "overlay", "darken", "lighten", "color-dodge", "color-burn", "hard-light",
            "soft-light", "difference", "exclusion", "hue", "saturation", "color", "luminosity",
        )

        /** The hanging baseline, as a share of the ascent. */
        private const val HANGING = 0.8

        /** How much of the ascent and descent the ink of a typical line fills. */
        private const val INK = 0.8

        /** The names of the twelve numbers of [measure], in order. */
        val METRICS = listOf(
            "width", "actualBoundingBoxLeft", "actualBoundingBoxRight", "fontBoundingBoxAscent", "fontBoundingBoxDescent",
            "actualBoundingBoxAscent", "actualBoundingBoxDescent", "emHeightAscent", "emHeightDescent",
            "hangingBaseline", "alphabeticBaseline", "ideographicBaseline",
        )

        /** The ascent and descent of the font that stands in, in ems: Arial, Times New Roman or Courier New. */
        private fun metricsOf(font: CanvasFont): Pair<Double, Double> {
            val families = font.familyList.lowercase()
            return when {
                "mono" in families || "courier" in families -> 0.833 to 0.300
                "sans" in families || "arial" in families || "helvetica" in families -> 0.905 to 0.212
                "serif" in families || "times" in families || "georgia" in families -> 0.891 to 0.216
                else -> 0.905 to 0.212
            }
        }

        private fun finite(vararg v: Double): Boolean = v.all { it.isFinite() }

        private fun finite(m: KiteMatrix): Boolean = finite(m.a, m.b, m.c, m.d, m.e, m.f)

        fun transformPath(path: KitePath, m: KiteMatrix): KitePath = KitePath(
            path.segments.map { s ->
                when (s) {
                    is KitePath.Segment.MoveTo -> KitePath.Segment.MoveTo(m.transformX(s.x, s.y), m.transformY(s.x, s.y))
                    is KitePath.Segment.LineTo -> KitePath.Segment.LineTo(m.transformX(s.x, s.y), m.transformY(s.x, s.y))
                    is KitePath.Segment.QuadTo -> KitePath.Segment.QuadTo(m.transformX(s.x1, s.y1), m.transformY(s.x1, s.y1), m.transformX(s.x2, s.y2), m.transformY(s.x2, s.y2))
                    is KitePath.Segment.CurveTo -> KitePath.Segment.CurveTo(
                        m.transformX(s.x1, s.y1), m.transformY(s.x1, s.y1), m.transformX(s.x2, s.y2), m.transformY(s.x2, s.y2),
                        m.transformX(s.x3, s.y3), m.transformY(s.x3, s.y3),
                    )
                    KitePath.Segment.Close -> s
                }
            },
        )
    }
}
