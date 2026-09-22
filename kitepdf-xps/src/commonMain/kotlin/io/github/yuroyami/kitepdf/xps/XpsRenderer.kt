package io.github.yuroyami.kitepdf.xps

import io.github.yuroyami.kitepdf.core.KiteRectangle
import io.github.yuroyami.kitepdf.core.KiteStructuredText
import io.github.yuroyami.kitepdf.core.KiteTextBlock
import io.github.yuroyami.kitepdf.core.KiteTextLine
import io.github.yuroyami.kitepdf.core.kiteWarn
import io.github.yuroyami.kitepdf.core.render.KiteCanvas
import io.github.yuroyami.kitepdf.core.render.KiteMatrix
import io.github.yuroyami.kitepdf.core.render.KitePath
import io.github.yuroyami.kitepdf.core.render.NoopCanvas
import io.github.yuroyami.kitepdf.core.render.RgbColor
import io.github.yuroyami.kitepdf.core.render.SoftMask
import io.github.yuroyami.kitepdf.core.xml.KiteXmlNode

internal data class XpsResource(val node: KiteXmlNode.Element, val base: String)
internal typealias XpsResources = Map<String, XpsResource>

/** A page-wide work ceiling also bounds recursive resource fan-out. */
internal class XpsRenderBudget {
    var remaining: Int = 100_000
        private set
    fun take(count: Int = 1): Boolean {
        if (count > remaining) { remaining = 0; return false }
        remaining -= count
        return true
    }
}

/** FixedPage/Canvas/Path/Glyphs traversal, ECMA-388 §§10-14 and §16. */
internal class XpsRenderer(private val packageData: XpsPackage, private val pagePart: String) {
    private var pageBox = KiteRectangle(0.0, 0.0, 816.0, 1056.0)
    private var pageCtm = KiteMatrix.IDENTITY
    private var textLines: MutableList<KiteTextLine>? = null
    private val budget = XpsRenderBudget()
    private val activeVisuals = HashSet<KiteXmlNode.Element>()
    private val brushes = XpsBrushes(packageData, budget) { resource, canvas, ctm, scope, depth ->
        if (activeVisuals.add(resource.node)) {
            try { walk(resource.node, resource.base, scope, canvas, ctm, depth) }
            finally { activeVisuals.remove(resource.node) }
        } else kiteWarn { "xps: recursive visual brush skipped" }
    }

    fun render(root: KiteXmlNode.Element, canvas: KiteCanvas, ctm: KiteMatrix) {
        pageBox = KiteRectangle(0.0, 0.0, root.number("width", 816.0), root.number("height", 1056.0))
        pageCtm = ctm
        walk(root, pagePart, emptyMap(), canvas, ctm, 0)
    }

    fun text(root: KiteXmlNode.Element, ctm: KiteMatrix): KiteStructuredText {
        val lines = ArrayList<KiteTextLine>()
        textLines = lines
        render(root, NoopCanvas, ctm)
        return KiteStructuredText(lines.map { KiteTextBlock(listOf(it)) })
    }

    private fun walk(
        el: KiteXmlNode.Element, base: String, inherited: XpsResources,
        canvas: KiteCanvas, parent: KiteMatrix, depth: Int,
    ) {
        if (depth > 64 || !budget.take()) { kiteWarn { "xps: drawing work limit" }; return }
        try {
            val scope = resources(el, base, inherited)
            val ctm = parent.concat(transform(el, scope, "rendertransform"))
            val clip = geometry(el, "clip", scope)
            val opacity = el.number("opacity", 1.0).coerceIn(0.0, 1.0)
            // A clip is the geometry's fill area, without its unfilled figures (ECMA-388, 11.2.1, #268).
            if (clip != null) canvas.pushClip(clip.fill, ctm.concat(clip.transform), clip.evenOdd)
            try {
                if (opacity < 1.0) canvas.beginTransparencyGroup(pageBox, pageCtm, isolated = true, alpha = opacity)
                try {
                    val paint = {
                        when (el.tag) {
                            "fixedpage", "canvas" -> for (child in el.elements()) {
                                if ('.' !in child.tag) walk(child, base, scope, canvas, ctm, depth + 1)
                            }
                            "path" -> drawPath(el, base, scope, canvas, ctm, depth)
                            "glyphs" -> drawGlyphs(el, base, scope, canvas, ctm, depth)
                            "alternatecontent" -> {
                                // Unknown extension choices are not understood. The
                                // standard fallback is the usable content, MCE §10.
                                val fallback = el.elements().firstOrNull { it.tag == "fallback" }
                                for (child in fallback?.elements().orEmpty()) walk(child, base, scope, canvas, ctm, depth + 1)
                            }
                        }
                    }
                    val mask = property(el, "opacitymask", base, scope)
                    if (mask != null && textLines == null) {
                        canvas.applySoftMask(SoftMask.Kind.Alpha, pageBox, pageCtm, paint) { maskCanvas ->
                            val inverse = ctm.invert()
                            val maskPath = rectangle(pageBox).let { if (inverse == null) it else transformPath(it, inverse.concat(pageCtm)) }
                            brushes.fill(mask, maskPath, false, maskCanvas, ctm, scope, depth + 1)
                        }
                    } else paint()
                } finally { if (opacity < 1.0) canvas.endTransparencyGroup() }
            } finally { if (clip != null) canvas.popClip() }
        } catch (_: Exception) {
            kiteWarn { "xps: skipped unreadable ${el.tag} element" }
        }
    }

    private fun drawPath(
        el: KiteXmlNode.Element, base: String, scope: XpsResources,
        canvas: KiteCanvas, ctm: KiteMatrix, depth: Int,
    ) {
        if (textLines != null) return
        val geo = geometry(el, "data", scope) ?: return
        val fillPath = transformPath(geo.fill, geo.transform)
        val strokePath = transformPath(geo.stroke, geo.transform)
        property(el, "fill", base, scope)?.let { brushes.fill(it, fillPath, geo.evenOdd, canvas, ctm, scope, depth + 1) }
        val stroke = property(el, "stroke", base, scope)?.let { brushes.solid(it) } ?: return
        val width = el.number("strokethickness", 1.0).coerceAtLeast(0.0)
        canvas.strokePath(
            strokePath, ctm, stroke.color, width, alpha = stroke.alpha,
            dashArray = el.attrs["strokedasharray"]?.let(::numbers)?.map { it * width },
            dashPhase = el.number("strokedashoffset", 0.0) * width,
            lineCap = when (el.attrs["strokestartlinecap"]) { "Round" -> 1; "Square" -> 2; else -> 0 },
            lineJoin = when (el.attrs["strokelinejoin"]) { "Round" -> 1; "Bevel" -> 2; else -> 0 },
            miterLimit = el.number("strokemiterlimit", 10.0),
        )
    }

    private fun drawGlyphs(
        el: KiteXmlNode.Element, base: String, scope: XpsResources,
        canvas: KiteCanvas, ctm: KiteMatrix, depth: Int,
    ) {
        val font = el.attrs["fonturi"]?.let { packageData.font(base, it) }
        val run = layoutGlyphs(el, font, textLines == null && canvas.resolvesGlyphOutlines, budget.remaining)
        budget.take(run.glyphs.size)
        if (run.fontSize <= 0) return
        textLines?.let { lines -> extract(run, ctm)?.let(lines::add); return }
        val brush = property(el, "fill", base, scope) ?: return
        val solid = brushes.solid(brush)
        // Substitute text has no outline to fill with the brush. It stays readable in
        // one colour that stands for the brush (lenient salvage, #267).
        val substitute by lazy { brushes.representative(brush) }
        for (position in run.glyphs) {
            val transform = ctm.concat(position.transform)
            val glyph = position.glyph
            val outline = glyph.outline
            if (solid != null) {
                canvas.drawGlyphs(listOf(glyph), run.fontSize, run.unitsPerEm, run.embedded, run.spec,
                    transform, solid.color, solid.alpha)
                if (run.embedded && run.spec.bold && outline != null) {
                    canvas.strokePath(outline, transform.concat(KiteMatrix.scaling(
                        run.fontSize / run.unitsPerEm, run.fontSize / run.unitsPerEm)),
                        solid.color, run.unitsPerEm * 0.02, alpha = solid.alpha,
                    )
                }
            } else if (outline != null) {
                val path = transformPath(outline, position.transform.concat(
                    KiteMatrix.scaling(run.fontSize / run.unitsPerEm, run.fontSize / run.unitsPerEm)))
                brushes.fill(brush, path, false, canvas, ctm, scope, depth + 1)
            } else {
                canvas.drawGlyphs(listOf(glyph), run.fontSize, run.unitsPerEm, run.embedded, run.spec,
                    transform, substitute.color, substitute.alpha)
            }
        }
    }

    private fun extract(run: XpsGlyphRun, ctm: KiteMatrix): KiteTextLine? {
        val text = StringBuilder()
        val edges = ArrayList<Double>()
        val points = ArrayList<Pair<Double, Double>>()
        var last = 0.0
        var at = 0
        while (at < run.glyphs.size) {
            val position = run.glyphs[at]
            val chars = position.glyph.text
            var end = at + 1
            while (end < run.glyphs.size && run.glyphs[end].glyph.text.isEmpty()) end++
            if (chars.isEmpty()) { at = end; continue }
            val tail = run.glyphs[end - 1]
            val from = ctm.transformPoint(position.penX, position.penY).first
            val to = ctm.transformPoint(tail.penX + tail.advance, tail.penY).first
            for (i in chars.indices) edges.add(from + (to - from) * i / chars.length)
            last = to
            text.append(chars)
            for (index in at until end) {
                val placed = run.glyphs[index]
                val transform = ctm.concat(placed.transform)
                val width = placed.glyph.advanceWidth * run.fontSize / 1000.0
                for (x in listOf(0.0, width)) {
                    points.add(transform.transformPoint(x, -run.fontSize * 0.2))
                    points.add(transform.transformPoint(x, run.fontSize * 0.8))
                }
                points.add(ctm.transformPoint(placed.penX, placed.penY))
                points.add(ctm.transformPoint(placed.penX + placed.advance, placed.penY))
            }
            at = end
        }
        if (text.isEmpty()) return null
        edges.add(last)
        return KiteTextLine(text.toString(), KiteRectangle(points.minOf { it.first }, points.minOf { it.second },
            points.maxOf { it.first }, points.maxOf { it.second }), edges.toDoubleArray())
    }

    private fun resources(el: KiteXmlNode.Element, base: String, parent: XpsResources): XpsResources {
        val local = el.elements().firstOrNull { it.tag == "${el.tag}.resources" } ?: return parent
        val result = parent.toMutableMap()
        fun read(dictionary: KiteXmlNode.Element, dictionaryBase: String, seen: Set<String>, depth: Int) {
            if (depth > 32 || !budget.take()) return
            val source = dictionary.attrs["source"]?.let { resolvePart(dictionaryBase, it) }
            if (source != null && source.lowercase() !in seen) {
                packageData.xml(source)?.let { read(it, source, seen + source.lowercase(), depth + 1) }
            }
            for (child in dictionary.elements()) {
                if (child.tag == "resourcedictionary.mergeddictionaries") {
                    for (nested in child.elements()) read(nested, dictionaryBase, seen, depth + 1)
                } else child.attrs["key"]?.let { result[it] = XpsResource(child, dictionaryBase) }
            }
        }
        for (dictionary in local.elements()) read(dictionary, base, emptySet(), 0)
        return result
    }
}

internal fun resourceKey(raw: String): String? = raw.trim().takeIf {
    it.startsWith("{StaticResource ") && it.endsWith('}')
}?.removePrefix("{StaticResource ")?.dropLast(1)?.trim()

internal fun property(el: KiteXmlNode.Element, name: String, base: String, scope: XpsResources): XpsResource? {
    el.attrs[name]?.let { raw ->
        resourceKey(raw)?.let { return scope[it] }
        return XpsResource(KiteXmlNode.Element("solidcolorbrush", mapOf("color" to raw)), base)
    }
    return el.elements().firstOrNull { it.tag == "${el.tag}.$name" }?.elements()?.firstOrNull()?.let { XpsResource(it, base) }
}

internal fun transform(el: KiteXmlNode.Element, scope: XpsResources, name: String = "transform"): KiteMatrix {
    el.attrs[name]?.let { raw ->
        matrix(raw)?.let { return it }
        resourceKey(raw)?.let { key -> scope[key]?.node?.attrs?.get("matrix")?.let(::matrix)?.let { return it } }
    }
    return el.elements().firstOrNull { it.tag == "${el.tag}.$name" }?.elements()?.firstOrNull()
        ?.attrs?.get("matrix")?.let(::matrix) ?: KiteMatrix.IDENTITY
}

private fun geometry(el: KiteXmlNode.Element, name: String, scope: XpsResources): XpsGeometry? {
    el.attrs[name]?.let { raw ->
        resourceKey(raw)?.let { key -> return scope[key]?.node?.let(XpsPaths::parse) }
        return XpsPaths.parse(raw)
    }
    return el.elements().firstOrNull { it.tag == "${el.tag}.$name" }?.elements()?.firstOrNull()?.let(XpsPaths::parse)
}

internal fun rectangle(box: KiteRectangle): KitePath = KitePath.Builder().apply {
    rectangle(box.left, box.bottom, box.width, box.height)
}.build()

internal fun transformPath(path: KitePath, matrix: KiteMatrix): KitePath = KitePath(path.segments.map { segment ->
    fun p(x: Double, y: Double) = matrix.transformPoint(x, y)
    when (segment) {
        is KitePath.Segment.MoveTo -> p(segment.x, segment.y).let { KitePath.Segment.MoveTo(it.first, it.second) }
        is KitePath.Segment.LineTo -> p(segment.x, segment.y).let { KitePath.Segment.LineTo(it.first, it.second) }
        is KitePath.Segment.QuadTo -> {
            val a = p(segment.x1, segment.y1); val b = p(segment.x2, segment.y2)
            KitePath.Segment.QuadTo(a.first, a.second, b.first, b.second)
        }
        is KitePath.Segment.CurveTo -> {
            val a = p(segment.x1, segment.y1); val b = p(segment.x2, segment.y2); val c = p(segment.x3, segment.y3)
            KitePath.Segment.CurveTo(a.first, a.second, b.first, b.second, c.first, c.second)
        }
        KitePath.Segment.Close -> KitePath.Segment.Close
    }
})

internal fun bounds(path: KitePath): KiteRectangle? {
    val points = ArrayList<Pair<Double, Double>>()
    for (segment in path.segments) when (segment) {
        is KitePath.Segment.MoveTo -> points.add(segment.x to segment.y)
        is KitePath.Segment.LineTo -> points.add(segment.x to segment.y)
        is KitePath.Segment.QuadTo -> { points.add(segment.x1 to segment.y1); points.add(segment.x2 to segment.y2) }
        is KitePath.Segment.CurveTo -> {
            points.add(segment.x1 to segment.y1); points.add(segment.x2 to segment.y2); points.add(segment.x3 to segment.y3)
        }
        KitePath.Segment.Close -> Unit
    }
    return if (points.isEmpty()) null else KiteRectangle(points.minOf { it.first }, points.minOf { it.second },
        points.maxOf { it.first }, points.maxOf { it.second })
}
