package io.github.yuroyami.kitepdf.svg

import io.github.yuroyami.kitepdf.core.css.CssValues
import io.github.yuroyami.kitepdf.core.render.KiteColorSpace
import io.github.yuroyami.kitepdf.core.render.KiteFunction
import io.github.yuroyami.kitepdf.core.render.KiteGradientSpread
import io.github.yuroyami.kitepdf.core.render.KiteShading
import io.github.yuroyami.kitepdf.core.render.RgbColor
import io.github.yuroyami.kitepdf.core.xml.KiteXmlNode

/**
 * Turns `<linearGradient>` / `<radialGradient>` into the shading every backend
 * already paints for PDF, so an SVG gradient uses the platform's own gradient
 * brush rather than a flat approximation.
 *
 * SVG stops become the same shape PDF uses: one exponential segment per pair of
 * neighbouring stops, stitched together over `t` in 0..1.
 */
internal object SvgGradient {

    /** One gradient, with the units it was declared in. */
    class Parsed(
        val shading: KiteShading,
        /** True for the default `objectBoundingBox`: coordinates are 0..1 of the shape. */
        val objectBoundingBox: Boolean,
        /** `gradientTransform`, or null. */
        val transform: String?,
        /** `spreadMethod`: how the gradient continues past its ends (SVG 1.1, 13.2.2). */
        val spread: KiteGradientSpread = KiteGradientSpread.Pad,
        /**
         * The same geometry with the opacity of each stop as a grey level, for a
         * luminosity soft mask. Null when every stop is opaque.
         */
        val opacity: KiteShading? = null,
    )

    /**
     * Read the gradient at [el], following one `href` to a gradient that holds
     * the stops (the common "same stops, different geometry" idiom).
     */
    fun parse(
        el: KiteXmlNode.Element,
        byId: Map<String, KiteXmlNode.Element>,
        declaration: (KiteXmlNode.Element, String) -> String? = SvgStyles::inlineValue,
    ): Parsed? {
        val stops = stopsOf(el, byId, declaration) ?: return null
        val fn = functionOf(stops) { channels(it.color) } ?: return null
        // A stop's opacity multiplies into the alpha of its colour (SVG 1.1, 13.2.4).
        val opacityFn = if (stops.all { it.alpha >= 1.0 }) null else functionOf(stops) { doubleArrayOf(it.alpha, it.alpha, it.alpha) }
        val spread = when ((attr(el, byId, "spreadmethod") ?: attr(el, byId, "spreadMethod"))?.trim()) {
            "reflect" -> KiteGradientSpread.Reflect
            "repeat" -> KiteGradientSpread.Repeat
            else -> KiteGradientSpread.Pad
        }
        val units = attr(el, byId, "gradientunits") ?: attr(el, byId, "gradientUnits")
        val objectBox = units?.trim() != "userSpaceOnUse"
        val transform = attr(el, byId, "gradienttransform") ?: attr(el, byId, "gradientTransform")

        fun n(name: String, fallback: Double): Double {
            val raw = attr(el, byId, name) ?: return fallback
            val s = raw.trim()
            // In objectBoundingBox units a percentage IS the fraction.
            val parsed = if (s.endsWith("%")) {
                s.dropLast(1).toDoubleOrNull()?.div(100.0)
            } else {
                s.removeSuffix("px").toDoubleOrNull()
            }
            return parsed?.takeIf(Double::isFinite) ?: fallback
        }

        fun shading(function: KiteFunction): KiteShading? = when (el.tag.lowercase()) {
            "lineargradient" -> KiteShading.Axial(
                colorSpace = KiteColorSpace.DeviceRGB,
                background = null,
                bbox = null,
                coords = doubleArrayOf(n("x1", 0.0), n("y1", 0.0), n("x2", 1.0), n("y2", 0.0)),
                domain = doubleArrayOf(0.0, 1.0),
                function = function,
                extendStart = true,
                extendEnd = true,
            )
            "radialgradient" -> {
                val cx = n("cx", 0.5)
                val cy = n("cy", 0.5)
                val r = n("r", 0.5).takeIf { it >= 0.0 } ?: 0.5
                KiteShading.Radial(
                    colorSpace = KiteColorSpace.DeviceRGB,
                    background = null,
                    bbox = null,
                    // The focal point (fx, fy) is the inner circle's centre, radius 0.
                    coords = doubleArrayOf(n("fx", cx), n("fy", cy), 0.0, cx, cy, r),
                    domain = doubleArrayOf(0.0, 1.0),
                    function = function,
                    extendStart = true,
                    extendEnd = true,
                )
            }
            else -> null
        }
        return Parsed(shading(fn) ?: return null, objectBox, transform, spread, opacityFn?.let(::shading))
    }

    private class Stop(val offset: Double, val color: RgbColor, val alpha: Double)

    /** This gradient's stops, or the first referenced gradient that owns some. */
    private fun stopsOf(
        el: KiteXmlNode.Element,
        byId: Map<String, KiteXmlNode.Element>,
        declaration: (KiteXmlNode.Element, String) -> String?,
    ): List<Stop>? {
        val seen = HashSet<KiteXmlNode.Element>()
        var current: KiteXmlNode.Element? = el
        while (current != null && seen.add(current)) {
            val own = current.children.filterIsInstance<KiteXmlNode.Element>()
                .filter { it.tag.lowercase() == "stop" }
                .mapNotNull { stop ->
                    val raw = stop.attrs["offset"]?.trim() ?: "0"
                    val parsed = if (raw.endsWith("%")) {
                        (raw.dropLast(1).toDoubleOrNull() ?: 0.0) / 100.0
                    } else {
                        raw.toDoubleOrNull() ?: 0.0
                    }
                    val offset = parsed.takeIf(Double::isFinite) ?: 0.0
                    val colorRaw = declaration(stop, "stop-color")
                    val color = colorRaw?.let { CssValues.color(it) } ?: RgbColor.BLACK
                    val alpha = (colorRaw?.let { CssValues.alpha(it) } ?: 1.0) * opacityOf(declaration(stop, "stop-opacity"))
                    Stop(offset.coerceIn(0.0, 1.0), color, alpha)
                }
                .sortedBy { it.offset }
            if (own.isNotEmpty()) return own
            current = hrefOf(current)?.let(byId::get)
        }
        return null
    }

    /** A `stop-opacity` from 0 to 1, as a number or a percentage. Anything else is opaque. */
    private fun opacityOf(raw: String?): Double {
        val s = raw?.trim() ?: return 1.0
        val value = if (s.endsWith("%")) s.dropLast(1).toDoubleOrNull()?.div(100.0) else s.toDoubleOrNull()
        return value?.takeIf(Double::isFinite)?.coerceIn(0.0, 1.0) ?: 1.0
    }

    /** Stops to a stitched exponential function, PDF's own multi-stop shape, with [values] of each stop. */
    private fun functionOf(stops: List<Stop>, values: (Stop) -> DoubleArray): KiteFunction? {
        if (stops.isEmpty()) return null
        if (stops.size == 1) {
            val c = values(stops[0])
            return KiteFunction.Type2(doubleArrayOf(0.0, 1.0), null, c, c, 1.0)
        }
        val subs = ArrayList<KiteFunction>(stops.size - 1)
        val bounds = ArrayList<Double>(stops.size - 2)
        val encode = ArrayList<Double>((stops.size - 1) * 2)
        for (i in 0 until stops.size - 1) {
            subs.add(
                KiteFunction.Type2(
                    doubleArrayOf(0.0, 1.0), null,
                    values(stops[i]), values(stops[i + 1]), 1.0,
                ),
            )
            if (i > 0) bounds.add(stops[i].offset)
            encode.add(0.0); encode.add(1.0)
        }
        return KiteFunction.Type3(
            domain = doubleArrayOf(0.0, 1.0),
            range = null,
            functions = subs,
            bounds = bounds.toDoubleArray(),
            encode = encode.toDoubleArray(),
        )
    }

    private fun channels(c: RgbColor) = doubleArrayOf(c.r, c.g, c.b)

    /** An attribute of this gradient, or of the one it references. */
    private fun attr(el: KiteXmlNode.Element, byId: Map<String, KiteXmlNode.Element>, name: String): String? {
        val seen = HashSet<KiteXmlNode.Element>()
        var current: KiteXmlNode.Element? = el
        while (current != null && seen.add(current)) {
            current.attrs[name]?.let { return it }
            current = hrefOf(current)?.let(byId::get)
        }
        return null
    }

    /** `href` / `xlink:href` as a bare id; the XML reader has dropped the prefix. */
    private fun hrefOf(el: KiteXmlNode.Element): String? =
        el.attrs["href"]?.trim()?.removePrefix("#")?.takeIf { it.isNotEmpty() }

}
