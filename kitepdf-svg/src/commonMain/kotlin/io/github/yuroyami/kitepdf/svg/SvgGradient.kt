package io.github.yuroyami.kitepdf.svg

import io.github.yuroyami.kitepdf.core.css.CssValues
import io.github.yuroyami.kitepdf.core.render.KiteColorSpace
import io.github.yuroyami.kitepdf.core.render.KiteFunction
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
    )

    /**
     * Read the gradient at [el], following one `href` to a gradient that holds
     * the stops (the common "same stops, different geometry" idiom).
     */
    fun parse(el: KiteXmlNode.Element, byId: Map<String, KiteXmlNode.Element>): Parsed? {
        val stops = stopsOf(el, byId) ?: return null
        val fn = functionOf(stops) ?: return null
        val units = attr(el, byId, "gradientunits") ?: attr(el, byId, "gradientUnits")
        val objectBox = units?.trim() != "userSpaceOnUse"
        val transform = attr(el, byId, "gradienttransform") ?: attr(el, byId, "gradientTransform")

        fun n(name: String, fallback: Double): Double {
            val raw = attr(el, byId, name) ?: return fallback
            val s = raw.trim()
            // In objectBoundingBox units a percentage IS the fraction.
            if (s.endsWith("%")) return (s.dropLast(1).toDoubleOrNull() ?: 0.0) / 100.0
            return s.removeSuffix("px").toDoubleOrNull() ?: fallback
        }

        val shading = when (el.tag.lowercase()) {
            "lineargradient" -> KiteShading.Axial(
                colorSpace = KiteColorSpace.DeviceRGB,
                background = null,
                bbox = null,
                coords = doubleArrayOf(n("x1", 0.0), n("y1", 0.0), n("x2", 1.0), n("y2", 0.0)),
                domain = doubleArrayOf(0.0, 1.0),
                function = fn,
                extendStart = true,
                extendEnd = true,
            )
            "radialgradient" -> {
                val cx = n("cx", 0.5)
                val cy = n("cy", 0.5)
                val r = n("r", 0.5)
                KiteShading.Radial(
                    colorSpace = KiteColorSpace.DeviceRGB,
                    background = null,
                    bbox = null,
                    // The focal point (fx, fy) is the inner circle's centre, radius 0.
                    coords = doubleArrayOf(n("fx", cx), n("fy", cy), 0.0, cx, cy, r),
                    domain = doubleArrayOf(0.0, 1.0),
                    function = fn,
                    extendStart = true,
                    extendEnd = true,
                )
            }
            else -> return null
        }
        return Parsed(shading, objectBox, transform)
    }

    private class Stop(val offset: Double, val color: RgbColor)

    /** This gradient's stops, or the referenced gradient's when it has none. */
    private fun stopsOf(el: KiteXmlNode.Element, byId: Map<String, KiteXmlNode.Element>): List<Stop>? {
        val own = el.children.filterIsInstance<KiteXmlNode.Element>()
            .filter { it.tag.lowercase() == "stop" }
            .mapNotNull { stop ->
                val raw = stop.attrs["offset"]?.trim() ?: "0"
                val offset = if (raw.endsWith("%")) {
                    (raw.dropLast(1).toDoubleOrNull() ?: 0.0) / 100.0
                } else {
                    raw.toDoubleOrNull() ?: 0.0
                }
                val color = styleOrAttr(stop, "stop-color")?.let { CssValues.color(it) } ?: RgbColor.BLACK
                Stop(offset.coerceIn(0.0, 1.0), color)
            }
            .sortedBy { it.offset }
        if (own.isNotEmpty()) return own
        val href = hrefOf(el) ?: return null
        val target = byId[href] ?: return null
        if (target === el) return null
        return stopsOf(target, byId)
    }

    /** Stops to a stitched exponential function, PDF's own multi-stop shape. */
    private fun functionOf(stops: List<Stop>): KiteFunction? {
        if (stops.isEmpty()) return null
        if (stops.size == 1) {
            val c = channels(stops[0].color)
            return KiteFunction.Type2(doubleArrayOf(0.0, 1.0), null, c, c, 1.0)
        }
        val subs = ArrayList<KiteFunction>(stops.size - 1)
        val bounds = ArrayList<Double>(stops.size - 2)
        val encode = ArrayList<Double>((stops.size - 1) * 2)
        for (i in 0 until stops.size - 1) {
            subs.add(
                KiteFunction.Type2(
                    doubleArrayOf(0.0, 1.0), null,
                    channels(stops[i].color), channels(stops[i + 1].color), 1.0,
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
        el.attrs[name]?.let { return it }
        val target = hrefOf(el)?.let { byId[it] } ?: return null
        if (target === el) return null
        return attr(target, byId, name)
    }

    /** `href` / `xlink:href` as a bare id; the XML reader has dropped the prefix. */
    private fun hrefOf(el: KiteXmlNode.Element): String? =
        el.attrs["href"]?.trim()?.removePrefix("#")?.takeIf { it.isNotEmpty() }

    /** An SVG presentation value: the `style` declaration wins over the attribute. */
    private fun styleOrAttr(el: KiteXmlNode.Element, name: String): String? {
        el.attrs["style"]?.let { style ->
            for (part in style.split(';')) {
                val at = part.indexOf(':')
                if (at > 0 && part.substring(0, at).trim().equals(name, ignoreCase = true)) {
                    return part.substring(at + 1).trim()
                }
            }
        }
        return el.attrs[name]
    }
}
