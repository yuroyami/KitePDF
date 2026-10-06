package io.github.yuroyami.kitepdf.epub.script

import io.github.yuroyami.kitepdf.core.font.FontSpec
import io.github.yuroyami.kitepdf.core.render.KiteMatrix
import io.github.yuroyami.kitepdf.core.render.KitePath
import io.github.yuroyami.kitepdf.core.render.KiteRaster
import io.github.yuroyami.kitepdf.core.render.KiteRasterCanvas
import io.github.yuroyami.kitepdf.core.render.encodeJpeg
import io.github.yuroyami.kitepdf.core.render.encodePng
import io.github.yuroyami.kitepdf.core.render.toImageData
import io.github.yuroyami.kitepdf.core.xml.KiteXmlNode
import io.github.yuroyami.kitepdf.svg.SvgImage
import kotlin.io.encoding.Base64
import kotlin.math.roundToInt

/**
 * The canvases of one chapter's scripts: each 2D context, and the paths, gradients and patterns
 * the scripts made, by the handle the script side knows them by (#501).
 *
 * The prelude calls [call] as `K.cv(canvas, op, ...)` for a context and `K.cv(-1, op, ...)` for a
 * `Path2D` or a gradient. An answer that is a list starting with [ERROR] is an error to throw.
 */
internal class CanvasHost(
    private val nodeOf: (Int) -> KiteXmlNode?,
    /** The canvas's `color`, which `currentcolor` names. */
    private val currentColor: (KiteXmlNode.Element) -> CanvasColor,
    /** The canvas's font size in pixels, or 10 when it is not in the document. */
    private val fontBase: (KiteXmlNode.Element) -> Double,
    /** An image element as a source, or null when it has nothing to draw. */
    private val imageOf: (KiteXmlNode.Element) -> CanvasSource?,
    /** Whether the element is an HTML element with this local name. */
    private val isHtml: (KiteXmlNode.Element, String) -> Boolean,
    /** Notes that a canvas changed, so the layout takes the tree again. */
    val changed: () -> Unit,
    /** The pixels that `putImageData` wrote, which the page paints from (#610). */
    private val images: CanvasImages = CanvasImages(),
    /** A file of the book by the href that an image of a canvas names, to read the pixels back. */
    private val load: (String) -> ByteArray? = { null },
    /** Text outlines in the host's fonts, to read back the pixels of text; without them text reads as blank. */
    private val fontOutlines: ((String, FontSpec) -> KitePath?)? = null,
) {
    private val owner = images.owner()

    /** Each canvas's pixels, with the version of its drawing they show. */
    private val rasters = HashMap<CanvasContext, Pair<Int, KiteRaster>>()

    /** The canvas images that each canvas's last drawing for the layout names, which must stay. */
    private val shown = HashMap<KiteXmlNode.Element, Set<String>>()

    private val contexts = HashMap<KiteXmlNode.Element, CanvasContext>()
    private val paths = HashMap<Int, CanvasPath>()
    private val gradients = HashMap<Int, CanvasGradient>()
    private val patterns = HashMap<Int, CanvasPattern>()
    private var nextHandle = 1

    /** The size of [canvas] from its attributes, 300 by 150 when they are missing or wrong (HTML, 4.12.5). */
    fun sizeOf(canvas: KiteXmlNode.Element): Pair<Int, Int> =
        dimension(canvas.attrs["width"], 300) to dimension(canvas.attrs["height"], 150)

    private fun dimension(raw: String?, fallback: Int): Int {
        val s = raw?.trimStart(' ', '\t', '\n', '\u000C', '\r') ?: return fallback
        val digits = s.takeWhile { it.isDigit() }
        if (digits.isEmpty()) return fallback
        val v = digits.trimStart('0').ifEmpty { "0" }
        if (v.length > 10) return fallback
        return v.toLong().takeIf { it <= Int.MAX_VALUE }?.toInt() ?: fallback
    }

    /** A canvas's attribute changed: its size, set even to what it was, resets its context (HTML, 4.12.5). */
    fun attributeSet(el: KiteXmlNode.Element, localName: String) {
        if (localName != "width" && localName != "height") return
        val context = contexts[el] ?: return
        val (w, h) = sizeOf(el)
        context.reset(w, h)
        changed()
    }

    /** What a canvas shows, as an `<svg>` for the layout: its drawing, or nothing at its size. */
    fun svgOf(canvas: KiteXmlNode.Element): KiteXmlNode.Element {
        contexts[canvas]?.let { c ->
            val svg = c.bitmap.snapshot()
            shown[canvas] = HashSet<String>().also { CanvasBitmap.imagesIn(svg, it) }
            return svg
        }
        val (w, h) = sizeOf(canvas)
        return CanvasBitmap(w, h).snapshot()
    }

    fun call(args: List<Any?>): Any? {
        val target = (args.getOrNull(0) as? Number)?.toInt() ?: -1
        val op = args.getOrNull(1) as? String ?: return null
        if (target < 0) return free(op, args)
        val canvas = nodeOf(target) as? KiteXmlNode.Element ?: return null
        if (op == "open") {
            if (canvas !in contexts) {
                val (w, h) = sizeOf(canvas)
                contexts[canvas] = CanvasContext(canvas, this, w, h)
                changed()
            }
            return null
        }
        if (op == "toDataURL" || op == "encode") {
            val file = encode(contexts[canvas], canvas, s(args, 2), args.getOrNull(3))
            if (op == "encode") return file?.let { (type, bytes) -> listOf(type, bytes.latin1()) }
            return file?.let { (type, bytes) -> "data:$type;base64," + Base64.encode(bytes) } ?: "data:,"
        }
        val c = contexts[canvas] ?: return null
        return context(c, canvas, op, args)
    }

    /** The canvas's pixels, premultiplied, drawn from its SVG and kept until it changes. */
    private fun rasterOf(c: CanvasContext): KiteRaster {
        val b = c.bitmap
        rasters[c]?.let { (version, raster) -> if (version == b.version) return raster }
        val canvas = KiteRasterCanvas(b.width, b.height, fontOutlines)
        if (!b.isBlank) SvgImage.fromElement(b.snapshot(), null, images::get)?.render(canvas, KiteMatrix.IDENTITY, load)
        return canvas.toPremultipliedRaster().also { rasters[c] = b.version to it }
    }

    /**
     * `getImageData`: the rectangle at ([x], [y]), transparent outside the canvas, as bytes in a
     * string, or as numbers from 0 to 1 when [floats] (HTML, 4.12.5.1.16).
     */
    private fun pixels(c: CanvasContext, x: Int, y: Int, w: Int, h: Int, floats: Boolean, p3: Boolean): Any {
        val r = rasterOf(c)
        return if (floats) CanvasPixels.readFloats(r, x, y, w, h, p3) else CanvasPixels.read(r, x, y, w, h, p3)
    }

    /**
     * `putImageData`: writes [w] by [h] pixels of [data] at ([x], [y]) as they are: no transform,
     * alpha, composite or clip applies (HTML, 4.12.5.1.16). The canvas's drawing becomes one image.
     */
    private fun put(c: CanvasContext, data: Any?, x: Int, y: Int, w: Int, h: Int, p3: Boolean) {
        val base = rasterOf(c).copy()
        if (!CanvasPixels.write(base, data, x, y, w, h, p3)) return
        c.bitmap.replaceWith(images.add(owner, CanvasPixels.straightened(base).toImageData()))
        rasters[c] = c.bitmap.version to base
        changed()
        collect()
    }

    /** Forgets the canvas images that no canvas, pattern or drawing in the layout names any more. */
    private fun collect() {
        if (images.count(owner) <= KEEP_IMAGES) return
        val live = HashSet<String>()
        for (c in contexts.values) c.bitmap.canvasImages(live)
        for (p in patterns.values) (p.source as? CanvasSource.Drawing)?.content?.let { CanvasBitmap.imagesIn(it, live) }
        for (set in shown.values) live.addAll(set)
        images.retainOnly(owner, live)
    }

    /**
     * The canvas as a file for `toDataURL` and `toBlob`: its type and bytes, a JPEG when [type]
     * asks for one and a PNG otherwise, or null when the canvas has no pixels. A [quality] from 0
     * to 1 sets the JPEG's, 0.92 when it is missing or out of range.
     */
    private fun encode(c: CanvasContext?, canvas: KiteXmlNode.Element, type: String, quality: Any?): Pair<String, ByteArray>? {
        val raster = if (c != null) CanvasPixels.straightened(rasterOf(c)) else sizeOf(canvas).let { (w, h) -> KiteRaster(w, h) }
        if (raster.width == 0 || raster.height == 0) return null
        if (type.lowercase() != "image/jpeg") return "image/png" to raster.encodePng()
        val q = (quality as? Number)?.toDouble()?.takeIf { it in 0.0..1.0 } ?: 0.92
        return "image/jpeg" to raster.encodeJpeg((q * 100).roundToInt())
    }

    private fun ByteArray.latin1(): String = CharArray(size) { (this[it].toInt() and 255).toChar() }.concatToString()

    private fun d(args: List<Any?>, i: Int): Double = (args.getOrNull(i) as? Number)?.toDouble() ?: Double.NaN
    private fun s(args: List<Any?>, i: Int): String = args.getOrNull(i)?.toString().orEmpty()
    private fun handle(args: List<Any?>, i: Int): Int = (args.getOrNull(i) as? Number)?.toInt() ?: -1

    private fun matrixAt(args: List<Any?>, i: Int) = KiteMatrix(d(args, i), d(args, i + 1), d(args, i + 2), d(args, i + 3), d(args, i + 4), d(args, i + 5))

    /** The path an operation names: a `Path2D` by handle, with the context's transform, or the context's own. */
    private fun targetPath(c: CanvasContext, h: Int): KitePath =
        paths[h]?.let { CanvasContext.transformPath(it.path(), c.state.transform) } ?: c.path.path()

    private fun context(c: CanvasContext, canvas: KiteXmlNode.Element, op: String, args: List<Any?>): Any? {
        val st = c.state
        val m = st.transform
        when (op) {
            "get" -> return get(st, s(args, 2))
            "set" -> { set(c, canvas, s(args, 2), args.getOrNull(3)); return null }
            "getStyle" -> return when (val p = if (s(args, 2) == "fill") st.fill else st.stroke) {
                is CanvasPaint.Solid -> p.color.serialize()
                is CanvasPaint.Gradient -> listOf("gradient", p.handle.toDouble())
                is CanvasPaint.Pattern -> listOf("pattern", p.handle.toDouble())
            }
            "setStyle" -> {
                val fill = s(args, 2) == "fill"
                val paint: CanvasPaint = when (s(args, 3)) {
                    "gradient" -> gradients[handle(args, 4)]?.let { CanvasPaint.Gradient(it, handle(args, 4)) } ?: return null
                    "pattern" -> patterns[handle(args, 4)]?.let { CanvasPaint.Pattern(it, handle(args, 4)) } ?: return null
                    else -> CanvasPaint.Solid(CanvasColor.parse(s(args, 4)) { currentColor(canvas) } ?: return null)
                }
                if (fill) st.fill = paint else st.stroke = paint
                return null
            }
            "save" -> c.save()
            "restore" -> c.restore()
            "reset" -> { val (w, h) = sizeOf(canvas); c.reset(w, h); changed() }
            "translate" -> c.transform(KiteMatrix(1.0, 0.0, 0.0, 1.0, d(args, 2), d(args, 3)))
            "scale" -> c.transform(KiteMatrix(d(args, 2), 0.0, 0.0, d(args, 3), 0.0, 0.0))
            "rotate" -> {
                val a = d(args, 2)
                if (a.isFinite()) c.transform(KiteMatrix(kotlin.math.cos(a), kotlin.math.sin(a), -kotlin.math.sin(a), kotlin.math.cos(a), 0.0, 0.0))
            }
            "transform" -> c.transform(matrixAt(args, 2))
            "setTransform" -> c.setTransform(matrixAt(args, 2))
            "resetTransform" -> c.setTransform(KiteMatrix.IDENTITY)
            "getTransform" -> return listOf(m.a, m.b, m.c, m.d, m.e, m.f)
            "setDash" -> {
                val list = (args.getOrNull(2) as? List<*>)?.map { (it as? Number)?.toDouble() ?: Double.NaN } ?: return null
                if (list.any { !it.isFinite() || it < 0 }) return null
                st.dash = if (list.size % 2 == 1) list + list else list
            }
            "getDash" -> return st.dash
            "beginPath" -> c.path.clear()
            "fill" -> c.fill(targetPath(c, handle(args, 2)), s(args, 3) == "evenodd")
            "stroke" -> c.stroke(targetPath(c, handle(args, 2)))
            "clip" -> c.clip(targetPath(c, handle(args, 2)), s(args, 3) == "evenodd")
            "isPointInPath" -> return c.isPointInPath(targetPath(c, handle(args, 2)), d(args, 3), d(args, 4), s(args, 5) == "evenodd")
            "isPointInStroke" -> return c.isPointInStroke(targetPath(c, handle(args, 2)), d(args, 3), d(args, 4))
            "fillRect" -> c.fillRect(d(args, 2), d(args, 3), d(args, 4), d(args, 5))
            "strokeRect" -> c.strokeRect(d(args, 2), d(args, 3), d(args, 4), d(args, 5))
            "clearRect" -> c.clearRect(d(args, 2), d(args, 3), d(args, 4), d(args, 5))
            "fillText", "strokeText" -> c.drawText(s(args, 2), d(args, 3), d(args, 4), (args.getOrNull(5) as? Number)?.toDouble(), op == "strokeText")
            "measureText" -> return c.measure(s(args, 2))
            "getImageData" -> return pixels(
                c, handle(args, 2), handle(args, 3), handle(args, 4), handle(args, 5), args.getOrNull(6) == true, args.getOrNull(7) == true,
            )
            "putImageData" -> put(c, args.getOrNull(2), handle(args, 3), handle(args, 4), handle(args, 5), handle(args, 6), args.getOrNull(7) == true)
            "drawImage" -> return drawImage(c, args)
            "pattern" -> return pattern(args)
            else -> return pathOp(c.path, m, op, args, 2)
        }
        return null
    }

    private fun get(st: CanvasState, name: String): Any? = when (name) {
        "lineWidth" -> st.lineWidth
        "lineCap" -> st.lineCap
        "lineJoin" -> st.lineJoin
        "miterLimit" -> st.miterLimit
        "lineDashOffset" -> st.dashOffset
        "globalAlpha" -> st.alpha
        "globalCompositeOperation" -> st.composite
        "shadowBlur" -> st.shadowBlur
        "shadowColor" -> st.shadowColor.serialize()
        "shadowOffsetX" -> st.shadowX
        "shadowOffsetY" -> st.shadowY
        "font" -> st.font.serialize()
        "textAlign" -> st.textAlign
        "textBaseline" -> st.textBaseline
        "direction" -> if (st.direction == "inherit") "ltr" else st.direction
        "filter" -> st.filter
        "imageSmoothingEnabled" -> st.smoothing
        "imageSmoothingQuality" -> st.smoothingQuality
        "letterSpacing" -> st.letterSpacing
        "wordSpacing" -> st.wordSpacing
        "fontKerning" -> st.fontKerning
        "fontStretch" -> st.fontStretch
        "fontVariantCaps" -> st.fontVariantCaps
        "textRendering" -> st.textRendering
        "lang" -> st.lang
        else -> null
    }

    /** Sets an attribute of the state, ignoring a value the attribute does not take (HTML, 4.12.5.1). */
    private fun set(c: CanvasContext, canvas: KiteXmlNode.Element, name: String, value: Any?) {
        val st = c.state
        val n = (value as? Number)?.toDouble() ?: Double.NaN
        val t = value?.toString().orEmpty()
        when (name) {
            "lineWidth" -> if (n.isFinite() && n > 0) st.lineWidth = n
            "miterLimit" -> if (n.isFinite() && n > 0) st.miterLimit = n
            "lineDashOffset" -> if (n.isFinite()) st.dashOffset = n
            "globalAlpha" -> if (n.isFinite() && n >= 0 && n <= 1) st.alpha = n
            "shadowBlur" -> if (n.isFinite() && n >= 0) st.shadowBlur = n
            "shadowOffsetX" -> if (n.isFinite()) st.shadowX = n
            "shadowOffsetY" -> if (n.isFinite()) st.shadowY = n
            "lineCap" -> if (t in CanvasKeywords.LINE_CAP) st.lineCap = t
            "lineJoin" -> if (t in CanvasKeywords.LINE_JOIN) st.lineJoin = t
            "globalCompositeOperation" -> if (t in CanvasKeywords.COMPOSITE) st.composite = t
            "textAlign" -> if (t in CanvasKeywords.TEXT_ALIGN) st.textAlign = t
            "textBaseline" -> if (t in CanvasKeywords.TEXT_BASELINE) st.textBaseline = t
            "direction" -> if (t in CanvasKeywords.DIRECTION) st.direction = t
            "imageSmoothingEnabled" -> st.smoothing = value == true
            "imageSmoothingQuality" -> if (t in CanvasKeywords.SMOOTHING_QUALITY) st.smoothingQuality = t
            "filter" -> if (CanvasKeywords.isFilter(t)) st.filter = t
            "letterSpacing" -> if (CanvasKeywords.isLength(t)) st.letterSpacing = t
            "wordSpacing" -> if (CanvasKeywords.isLength(t)) st.wordSpacing = t
            "fontKerning" -> if (t in CanvasKeywords.FONT_KERNING) st.fontKerning = t
            "fontStretch" -> if (t in CanvasKeywords.FONT_STRETCH) st.fontStretch = t
            "fontVariantCaps" -> if (t in CanvasKeywords.FONT_VARIANT_CAPS) st.fontVariantCaps = t
            "textRendering" -> if (t in CanvasKeywords.TEXT_RENDERING) st.textRendering = t
            "lang" -> st.lang = t
            "shadowColor" -> CanvasColor.parse(t) { currentColor(canvas) }?.let { st.shadowColor = it }
            "font" -> CanvasFont.parse(t) { fontBase(canvas) }?.let { st.font = it }
        }
    }

    /** A path operation on [path], whose points go through [m]. */
    private fun pathOp(path: CanvasPath, m: KiteMatrix, op: String, args: List<Any?>, at: Int): Any? {
        fun a(i: Int) = d(args, at + i)
        when (op) {
            "moveTo" -> path.moveTo(m, a(0), a(1))
            "lineTo" -> path.lineTo(m, a(0), a(1))
            "quadraticCurveTo" -> path.quadraticCurveTo(m, a(0), a(1), a(2), a(3))
            "bezierCurveTo" -> path.bezierCurveTo(m, a(0), a(1), a(2), a(3), a(4), a(5))
            "arcTo" -> path.arcTo(m, a(0), a(1), a(2), a(3), a(4))
            "arc" -> path.ellipse(m, a(0), a(1), a(2), a(2), 0.0, a(3), a(4), args.getOrNull(at + 5) == true)
            "ellipse" -> path.ellipse(m, a(0), a(1), a(2), a(3), a(4), a(5), a(6), args.getOrNull(at + 7) == true)
            "rect" -> path.rect(m, a(0), a(1), a(2), a(3))
            "roundRect" -> {
                val flat = (args.getOrNull(at + 4) as? List<*>)?.map { (it as? Number)?.toDouble() ?: Double.NaN } ?: return null
                path.roundRect(m, a(0), a(1), a(2), a(3), flat.chunked(2) { doubleArrayOf(it[0], it[1]) })
            }
            "closePath" -> path.closePath()
        }
        return null
    }

    /** Operations without a context: Path2D, gradients and patterns. */
    private fun free(op: String, args: List<Any?>): Any? {
        when (op) {
            "newPath" -> {
                val path = CanvasPath()
                when (val from = args.getOrNull(2)) {
                    is Number -> paths[from.toInt()]?.let { path.append(it, null) }
                    is String -> path.appendParsed(SvgImage.parsePathData(from))
                }
                return store(paths, path)
            }
            "freePath" -> paths.remove(handle(args, 2))
            "addPath" -> {
                val into = paths[handle(args, 2)] ?: return null
                val from = paths[handle(args, 3)] ?: return null
                val m = matrixAt(args, 4)
                if (listOf(m.a, m.b, m.c, m.d, m.e, m.f).all { it.isFinite() }) into.append(from.copy(), m)
            }
            "gradient" -> {
                val kind = s(args, 2)
                val params = (3 until 3 + (if (kind == "linear") 4 else if (kind == "radial") 6 else 3)).map { d(args, it) }.toDoubleArray()
                return store(gradients, CanvasGradient(kind, params))
            }
            "stop" -> {
                val g = gradients[handle(args, 2)] ?: return false
                val color = CanvasColor.parse(s(args, 4)) { CanvasColor.BLACK } ?: return false
                g.stops.add(d(args, 3) to color)
                return true
            }
            "patternTransform" -> patterns[handle(args, 2)]?.transform = matrixAt(args, 3)
            else -> paths[handle(args, 2)]?.let { return pathOp(it, KiteMatrix.IDENTITY, op, args, 3) }
        }
        return null
    }

    private fun <T> store(map: HashMap<Int, T>, value: T): Double {
        val h = nextHandle++
        map[h] = value
        return h.toDouble()
    }

    /** The source an element gives, an error when it is a canvas with no area, or null when it has nothing to draw. */
    private fun sourceOf(el: KiteXmlNode.Element): Any? {
        if (isHtml(el, "canvas")) {
            val (w, h) = sizeOf(el)
            if (w == 0 || h == 0) return listOf(ERROR, "InvalidStateError", "The image argument is a canvas element with a width or height of 0.")
            return CanvasSource.Drawing(contexts[el]?.bitmap?.copyFor(""), w.toDouble(), h.toDouble())
        }
        return imageOf(el)
    }

    private fun drawImage(c: CanvasContext, args: List<Any?>): Any? {
        val el = nodeOf(handle(args, 2)) as? KiteXmlNode.Element ?: return null
        val source = when (val s = sourceOf(el)) {
            is CanvasSource -> s
            null -> return null
            else -> return s
        }
        val n = handle(args, 3)
        val v = (4 until 4 + n).map { d(args, it) }
        when (n) {
            2 -> c.drawImage(source, 0.0, 0.0, source.width, source.height, v[0], v[1], source.width, source.height)
            4 -> c.drawImage(source, 0.0, 0.0, source.width, source.height, v[0], v[1], v[2], v[3])
            8 -> c.drawImage(source, v[0], v[1], v[2], v[3], v[4], v[5], v[6], v[7])
        }
        return null
    }

    private fun pattern(args: List<Any?>): Any? {
        val el = nodeOf(handle(args, 2)) as? KiteXmlNode.Element ?: return null
        val source = when (val s = sourceOf(el)) {
            is CanvasSource -> s
            null -> return null
            else -> return s
        }
        val repetition = s(args, 3).ifEmpty { "repeat" }
        return store(patterns, CanvasPattern(source, repetition))
    }

    companion object {
        /** The first item of an answer that is an error to throw. */
        const val ERROR = "\u0000error"

        /** Canvas images a set of canvases keeps before it looks for ones nothing names. */
        const val KEEP_IMAGES = 4
    }
}
