package io.github.yuroyami.kitepdf.xps

import io.github.yuroyami.kitepdf.core.render.KiteMatrix
import io.github.yuroyami.kitepdf.core.render.KitePath
import io.github.yuroyami.kitepdf.core.xml.KiteXmlNode
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

internal data class XpsGeometry(
    val path: KitePath,
    val evenOdd: Boolean = true,
    val transform: KiteMatrix = KiteMatrix.IDENTITY,
    val fill: KitePath = path,
    val stroke: KitePath = path,
)

/** XPS path markup and abbreviated geometry, ECMA-388 §§11.2, 11.3. */
internal object XpsPaths {
    fun parse(data: String): XpsGeometry {
        val evenOdd = !Regex("^\\s*F\\s*1").containsMatchIn(data)
        return XpsGeometry(abbreviated(data), evenOdd)
    }

    fun parse(el: KiteXmlNode.Element): XpsGeometry? {
        if (el.tag != "pathgeometry") return null
        val transform = el.attrs["transform"]?.let(::matrix)
            ?: el.elements().firstOrNull { it.tag == "pathgeometry.transform" }
                ?.elements()?.firstOrNull()?.attrs?.get("matrix")?.let(::matrix)
            ?: KiteMatrix.IDENTITY
        el.attrs["figures"]?.let {
            val geometry = parse(it)
            return geometry.copy(
                evenOdd = el.attrs["fillrule"]?.let { value -> value != "NonZero" } ?: geometry.evenOdd,
                transform = transform,
            )
        }
        val all = ArrayList<KitePath.Segment>()
        val fill = ArrayList<KitePath.Segment>()
        val stroke = ArrayList<KitePath.Segment>()
        for (figure in el.elements().filter { it.tag == "pathfigure" }) {
            val start = numbers(figure.attrs["startpoint"].orEmpty())
            if (start.size != 2) continue
            var x = start[0]; var y = start[1]
            val figurePath = ArrayList<KitePath.Segment>()
            val figureStroke = ArrayList<KitePath.Segment>()
            var brokenStroke = false
            figurePath.add(KitePath.Segment.MoveTo(x, y))
            figureStroke.add(KitePath.Segment.MoveTo(x, y))
            for (segment in figure.elements()) {
                val values = numbers(segment.attrs["points"] ?: segment.attrs["point"].orEmpty())
                val command = when (segment.tag) {
                    "polylinesegment" -> "L"
                    "polybeziersegment" -> "C"
                    "polyquadraticbeziersegment" -> "Q"
                    "arcsegment" -> {
                        val size = numbers(segment.attrs["size"].orEmpty())
                        if (size.size != 2 || values.size != 2) continue
                        "A ${size[0]},${size[1]} ${segment.number("rotationangle", 0.0)} " +
                            "${if (segment.attrs["islargearc"] == "true") 1 else 0} " +
                            "${if (segment.attrs["sweepdirection"] == "Clockwise") 1 else 0}"
                    }
                    else -> continue
                }
                val piece = abbreviated("M $x,$y $command ${values.joinToString(",")}").segments.drop(1)
                if (piece.isEmpty()) continue
                figurePath.addAll(piece)
                val end = piece.last()
                val point = when (end) {
                    is KitePath.Segment.LineTo -> end.x to end.y
                    is KitePath.Segment.CurveTo -> end.x3 to end.y3
                    is KitePath.Segment.QuadTo -> end.x2 to end.y2
                    else -> x to y
                }
                if (segment.attrs["isstroked"] == "false") {
                    brokenStroke = true
                    figureStroke.add(KitePath.Segment.MoveTo(point.first, point.second))
                } else figureStroke.addAll(piece)
                x = point.first; y = point.second
            }
            if (figure.attrs["isclosed"] == "true") {
                figurePath.add(KitePath.Segment.Close)
                // A MoveTo for an unstroked segment changes the stroke subpath's
                // start, so close explicitly to the original figure start.
                figureStroke.add(if (brokenStroke) KitePath.Segment.LineTo(start[0], start[1]) else KitePath.Segment.Close)
            }
            all.addAll(figurePath)
            stroke.addAll(figureStroke)
            if (figure.attrs["isfilled"] != "false") fill.addAll(figurePath)
        }
        return XpsGeometry(KitePath(all), el.attrs["fillrule"] != "NonZero", transform, KitePath(fill), KitePath(stroke))
    }

    fun abbreviated(d: String): KitePath {
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
                'F' -> { t.num() }
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
                else -> error("unknown XPS path command")
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
        if (x0 == x && y0 == y) return
        var rx = abs(rxIn); var ry = abs(ryIn)
        if (rx == 0.0 || ry == 0.0) { b.lineTo(x, y); return }
        val phi = rotDeg * PI / 180.0
        val cosP = cos(phi); val sinP = sin(phi)
        val dx = (x0 - x) / 2.0; val dy = (y0 - y) / 2.0
        val x1p = cosP * dx + sinP * dy
        val y1p = -sinP * dx + cosP * dy
        val lambda = x1p * x1p / (rx * rx) + y1p * y1p / (ry * ry)
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
            check(i > start) { "missing path coordinate" }
            return s.substring(start, i).toDoubleOrNull()?.takeIf { it.isFinite() }
                ?: error("invalid path coordinate")
        }
    }
}

internal fun numbers(raw: String): List<Double> = raw.trim().split(Regex("[\\s,]+")).mapNotNull {
    it.toDoubleOrNull()?.takeIf(Double::isFinite)
}

internal fun matrix(raw: String): KiteMatrix? = numbers(raw).takeIf { it.size == 6 }?.let {
    KiteMatrix(it[0], it[1], it[2], it[3], it[4], it[5])
}
