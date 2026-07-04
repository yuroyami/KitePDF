package io.github.yuroyami.kitepdf.epub

import io.github.yuroyami.kitepdf.epub.css.CssValues
import io.github.yuroyami.kitepdf.render.BlendMode
import io.github.yuroyami.kitepdf.render.Matrix
import io.github.yuroyami.kitepdf.render.PdfCanvas
import io.github.yuroyami.kitepdf.render.PdfPath
import io.github.yuroyami.kitepdf.render.RgbColor
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.PI

/**
 * A minimal SVG document renderer — enough to draw the illustrations, cover art
 * and diagrams that ship in EPUBs (and to seed a future `:kitepdf-svg` handler;
 * the reusable path/transform parsing wants promoting to core then). Parses the
 * SVG XML into the shared [HtmlNode] tree and paints shapes straight into the
 * core [PdfCanvas] as vectors (crisp at any scale), not a rasterised bitmap.
 *
 * Supported: `<svg>` (width/height/viewBox), `<g>`, `<path>` (all `d` commands
 * incl. elliptical arcs), `<rect>` (+ rx/ry), `<circle>`, `<ellipse>`, `<line>`,
 * `<polyline>`, `<polygon>`; `fill`/`stroke`/`stroke-width`/`opacity`/`fill-rule`
 * with inheritance; `transform` (translate/scale/rotate/skewX/skewY/matrix).
 * Not yet: `<text>`, gradients/patterns (filled with their fallback solid or
 * skipped), `clipPath`, filters, `<use>`, nested `<image>`.
 */
internal class SvgImage private constructor(
    private val root: HtmlNode.Element,
    /** Intrinsic size in px (from width/height, else the viewBox extent, else 300x150). */
    val width: Double,
    val height: Double,
    private val viewBox: DoubleArray?, // minX, minY, w, h
) {

    /** Paint the SVG into [canvas]; [ctm] maps the (0,0)-(width,height) viewport to device. */
    fun render(canvas: PdfCanvas, ctm: Matrix) {
        val vb = viewBox
        val base = if (vb != null && vb[2] > 0 && vb[3] > 0) {
            // viewBox coords -> viewport: translate(-min) then scale(size/vb).
            compose(ctm, compose(Matrix.scaling(width / vb[2], height / vb[3]), Matrix.translation(-vb[0], -vb[1])))
        } else ctm
        canvas0 = canvas
        try { walk(root, base, Paint()) } finally { canvas0 = null }
    }

    private class Paint(
        val fill: RgbColor? = RgbColor.BLACK,
        val stroke: RgbColor? = null,
        val strokeW: Double = 1.0,
        val opacity: Double = 1.0,
        val evenOdd: Boolean = false,
        val current: RgbColor = RgbColor.BLACK,
    )

    private fun walk(el: HtmlNode.Element, parentCtm: Matrix, parent: Paint) {
        val ctm = el.attrs["transform"]?.let { compose(parentCtm, parseTransform(it)) } ?: parentCtm
        val paint = resolvePaint(el.attrs, parent)
        when (el.tag.lowercase()) {
            "svg", "g", "a", "switch" -> for (c in el.children) if (c is HtmlNode.Element) walk(c, ctm, paint)
            "path" -> el.attrs["d"]?.let { paintShape(parsePath(it), ctm, paint) }
            "rect" -> paintShape(rect(el.attrs), ctm, paint)
            "circle" -> paintShape(ellipse(num(el, "cx"), num(el, "cy"), num(el, "r"), num(el, "r")), ctm, paint)
            "ellipse" -> paintShape(ellipse(num(el, "cx"), num(el, "cy"), num(el, "rx"), num(el, "ry")), ctm, paint)
            "line" -> paintShape(
                PdfPath.Builder().apply { moveTo(num(el, "x1"), num(el, "y1")); lineTo(num(el, "x2"), num(el, "y2")) }.build(),
                ctm, paint, forceStroke = true,
            )
            "polyline" -> el.attrs["points"]?.let { paintShape(polyline(it, close = false), ctm, paint) }
            "polygon" -> el.attrs["points"]?.let { paintShape(polyline(it, close = true), ctm, paint) }
        }
    }

    private fun paintShape(path: PdfPath, ctm: Matrix, paint: Paint, forceStroke: Boolean = false) {
        if (path.segments.isEmpty()) return
        paint.fill?.let { if (!forceStroke) canvas0?.let { c -> c.fillPath(path, ctm, it, paint.evenOdd, paint.opacity, BlendMode.Normal) } }
        val sc = paint.stroke ?: if (forceStroke) RgbColor.BLACK else null
        sc?.let { canvas0?.strokePath(path, ctm, it, paint.strokeW, paint.opacity, BlendMode.Normal) }
    }

    // paintShape needs the canvas; thread it via a field set for the duration of render().
    private var canvas0: PdfCanvas? = null

    private fun resolvePaint(a: Map<String, String>, p: Paint): Paint {
        val current = a["color"]?.let { CssValues.color(it) } ?: p.current
        return Paint(
            fill = paintValue(a["fill"], p.fill, current),
            stroke = paintValue(a["stroke"], p.stroke, current),
            strokeW = a["stroke-width"]?.let { parseLen(it) } ?: p.strokeW,
            opacity = (a["opacity"]?.toDoubleOrNull() ?: 1.0) * p.opacity,
            evenOdd = when (a["fill-rule"]) { "evenodd" -> true; "nonzero" -> false; else -> p.evenOdd },
            current = current,
        )
    }

    private fun paintValue(raw: String?, inherited: RgbColor?, current: RgbColor): RgbColor? = when {
        raw == null -> inherited
        raw == "none" -> null
        raw == "currentColor" -> current
        raw.startsWith("url(") -> inherited // gradient/pattern refs: fall back to the inherited solid
        else -> CssValues.color(raw) ?: inherited
    }

    // ---- shape builders (user coordinates) ----------------------------------

    private fun rect(a: Map<String, String>): PdfPath {
        val x = pLen(a, "x"); val y = pLen(a, "y"); val w = pLen(a, "width"); val h = pLen(a, "height")
        var rx = a["rx"]?.let { parseLen(it) } ?: -1.0
        var ry = a["ry"]?.let { parseLen(it) } ?: -1.0
        if (rx < 0 && ry >= 0) rx = ry
        if (ry < 0 && rx >= 0) ry = rx
        rx = rx.coerceIn(0.0, w / 2); ry = ry.coerceIn(0.0, h / 2)
        val b = PdfPath.Builder()
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

    private fun ellipse(cx: Double, cy: Double, rx: Double, ry: Double): PdfPath {
        if (rx <= 0 || ry <= 0) return PdfPath(emptyList())
        val k = 0.5522847498
        val b = PdfPath.Builder()
        b.moveTo(cx + rx, cy)
        b.curveTo(cx + rx, cy + ry * k, cx + rx * k, cy + ry, cx, cy + ry)
        b.curveTo(cx - rx * k, cy + ry, cx - rx, cy + ry * k, cx - rx, cy)
        b.curveTo(cx - rx, cy - ry * k, cx - rx * k, cy - ry, cx, cy - ry)
        b.curveTo(cx + rx * k, cy - ry, cx + rx, cy - ry * k, cx + rx, cy)
        b.close()
        return b.build()
    }

    private fun polyline(points: String, close: Boolean): PdfPath {
        val nums = numbers(points)
        val b = PdfPath.Builder()
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

    private fun num(el: HtmlNode.Element, k: String) = el.attrs[k]?.let { parseLen(it) } ?: 0.0
    private fun pLen(a: Map<String, String>, k: String) = a[k]?.let { parseLen(it) } ?: 0.0
    private fun parseLen(raw: String): Double {
        val s = raw.trim().removeSuffix("px")
        return s.toDoubleOrNull() ?: CssValues.length(raw, 12.0, 16.0, 0.0) ?: 0.0
    }

    companion object {
        fun isSvg(bytes: ByteArray): Boolean {
            val head = bytes.decodeToString(0, minOf(bytes.size, 512))
            return head.contains("<svg")
        }

        /** Parse a whole `.svg` file (or a spine SVG document). */
        fun parse(bytes: ByteArray): SvgImage? {
            val root = runCatching { HtmlParser.parse(bytes.decodeToString()) }.getOrNull() ?: return null
            return findSvg(root)?.let { fromElement(it) }
        }

        /** Build from an already-parsed `<svg>` element (inline SVG in XHTML content). */
        fun fromElement(svg: HtmlNode.Element): SvgImage? {
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

        private fun findSvg(el: HtmlNode.Element): HtmlNode.Element? {
            if (el.tag.equals("svg", true)) return el
            for (c in el.children) if (c is HtmlNode.Element) findSvg(c)?.let { return it }
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
        private fun compose(a: Matrix, b: Matrix): Matrix = Matrix(
            a.a * b.a + a.c * b.b,
            a.b * b.a + a.d * b.b,
            a.a * b.c + a.c * b.d,
            a.b * b.c + a.d * b.d,
            a.a * b.e + a.c * b.f + a.e,
            a.b * b.e + a.d * b.f + a.f,
        )

        private fun parseTransform(s: String): Matrix {
            var m = Matrix.IDENTITY
            var i = 0
            while (i < s.length) {
                val open = s.indexOf('(', i)
                if (open < 0) break
                val name = s.substring(i, open).trim().takeLastWhile { !it.isWhitespace() && it != ',' }
                val close = s.indexOf(')', open)
                if (close < 0) break
                val args = numbers(s.substring(open + 1, close))
                val t = when (name) {
                    "translate" -> Matrix.translation(args.getOrElse(0) { 0.0 }, args.getOrElse(1) { 0.0 })
                    "scale" -> Matrix.scaling(args.getOrElse(0) { 1.0 }, args.getOrElse(1) { args.getOrElse(0) { 1.0 } })
                    "rotate" -> {
                        val th = (args.getOrElse(0) { 0.0 }) * PI / 180.0
                        val rot = Matrix(cos(th), sin(th), -sin(th), cos(th), 0.0, 0.0)
                        if (args.size >= 3) compose(Matrix.translation(args[1], args[2]), compose(rot, Matrix.translation(-args[1], -args[2]))) else rot
                    }
                    "matrix" -> if (args.size >= 6) Matrix(args[0], args[1], args[2], args[3], args[4], args[5]) else Matrix.IDENTITY
                    "skewx", "skewX" -> { val t = kotlin.math.tan(args.getOrElse(0) { 0.0 } * PI / 180.0); Matrix(1.0, 0.0, t, 1.0, 0.0, 0.0) }
                    "skewy", "skewY" -> { val t = kotlin.math.tan(args.getOrElse(0) { 0.0 } * PI / 180.0); Matrix(1.0, t, 0.0, 1.0, 0.0, 0.0) }
                    else -> Matrix.IDENTITY
                }
                m = compose(m, t)
                i = close + 1
            }
            return m
        }

        // ---- SVG path `d` parser --------------------------------------------

        private fun parsePath(d: String): PdfPath {
            val b = PdfPath.Builder()
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
            b: PdfPath.Builder, x0: Double, y0: Double, rxIn: Double, ryIn: Double,
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
