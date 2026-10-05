package io.github.yuroyami.kitepdf.epub.css

import io.github.yuroyami.kitepdf.core.css.CssValues
import io.github.yuroyami.kitepdf.core.render.RgbColor
import kotlin.math.PI
import kotlin.math.atan2

/** A length in points, or a fraction of a reference for a percentage. Unlike [CssRadius], it can be negative. */
internal class CssOffset(val value: Double, val percent: Boolean) {
    fun resolve(reference: Double): Double = if (percent) value * reference else value

    companion object {
        val ZERO = CssOffset(0.0, false)
        val HALF = CssOffset(0.5, true)
        val FULL = CssOffset(1.0, true)
    }
}

/** The image a `background-image` paints over the background colour: a file, or a linear gradient (#28). */
internal sealed class CssBackgroundImage {
    /** A file, by a url that a stylesheet made absolute with a leading slash, or relative to the document. */
    class Url(val url: String) : CssBackgroundImage()

    /**
     * A `linear-gradient`. [angle] is in degrees, 0 pointing up and turning clockwise; [corner]
     * replaces it for `to top right` and the like, as -1 or 1 across and down, since that angle
     * depends on the box.
     */
    class LinearGradient(val angle: Double, val corner: Pair<Int, Int>?, val stops: List<GradientStop>) : CssBackgroundImage() {
        /** The angle for a box of [w] by [h]. */
        fun angleFor(w: Double, h: Double): Double {
            val (sx, sy) = corner ?: return angle
            // CSS Images 3, 3.1.1: the line is perpendicular to the diagonal between the other two corners.
            return atan2(sx * h, -sy * w) * 180.0 / PI
        }
    }
}

/**
 * One colour stop of a gradient: its colour, or null for `currentColor`, which takes the colour
 * of the box it paints (#503); its alpha; and its position along the line, or null to space it out.
 */
internal class GradientStop(val color: RgbColor?, val alpha: Double, val position: CssOffset?)

/** `background-size`: `cover`, `contain`, or a width and a height, each null for `auto`. */
internal class CssBackgroundSize(val cover: Boolean = false, val contain: Boolean = false, val width: CssOffset? = null, val height: CssOffset? = null)

/** One background layer the style paints: its image, size, position and repeat (CSS Backgrounds 3, 3). */
internal class CssBackgroundLayer(
    val image: CssBackgroundImage,
    val size: CssBackgroundSize = CssBackgroundSize(),
    val x: CssOffset = CssOffset.ZERO,
    val y: CssOffset = CssOffset.ZERO,
    val repeatX: Boolean = true,
    val repeatY: Boolean = true,
) {
    fun with(
        size: CssBackgroundSize = this.size,
        x: CssOffset = this.x,
        y: CssOffset = this.y,
        repeatX: Boolean = this.repeatX,
        repeatY: Boolean = this.repeatY,
    ): CssBackgroundLayer = CssBackgroundLayer(image, size, x, y, repeatX, repeatY)
}

/** Reads the background values. Lengths go through [length], which knows the element's font. */
internal class BackgroundParser(private val length: (String) -> Double?) {

    /**
     * Each layer of a `background-image` list, the first on top: null for `none` and for an
     * image this engine does not paint, which keeps its place in the list (#503).
     */
    fun images(value: String): List<CssBackgroundImage?> = layers(value).map(::image)

    /** One image of a `background-image` list, or null for `none` and for images this engine does not paint. */
    fun image(layer: String): CssBackgroundImage? {
        val token = layer.trim()
        val lower = token.lowercase()
        return when {
            lower.startsWith("url(") -> urlOf(token)?.let { CssBackgroundImage.Url(it) }
            lower.startsWith("linear-gradient(") -> gradient(token.substring(token.indexOf('(') + 1, token.lastIndexOf(')').coerceAtLeast(token.indexOf('(') + 1)))
            else -> null
        }
    }

    /** The `background-size` of each layer, or null when one does not read. */
    fun sizes(value: String): List<CssBackgroundSize>? = layers(value).map { size(it) ?: return null }.ifEmpty { null }

    /** The `background-position` of each layer, or null when one does not read. */
    fun positions(value: String): List<Pair<CssOffset, CssOffset>>? = layers(value).map { position(it) ?: return null }.ifEmpty { null }

    /** The `background-position-x` or `-y` of each layer, or null when one does not read. */
    fun offsets(value: String): List<CssOffset>? = layers(value).map { offset(it) ?: return null }.ifEmpty { null }

    /** The `background-repeat` of each layer, or null when one does not read. */
    fun repeats(value: String): List<Pair<Boolean, Boolean>>? = layers(value).map { repeat(it) ?: return null }.ifEmpty { null }

    /** The comma-separated layers of a background value. A list of none reads as no value. */
    private fun layers(value: String): List<String> = CssParser.splitTopLevel(value, ',').map { it.trim() }

    private fun urlOf(token: String): String? {
        val inner = token.substring(token.indexOf('(') + 1, token.lastIndexOf(')').takeIf { it > 0 } ?: return null).trim()
        return inner.trim('"', '\'').trim().takeIf { it.isNotEmpty() }
    }

    /** A `linear-gradient` body: a direction, then the colour stops. Null when it does not read. */
    fun gradient(body: String): CssBackgroundImage.LinearGradient? {
        val parts = CssParser.splitTopLevel(body, ',').map { it.trim() }.filter { it.isNotEmpty() }
        if (parts.isEmpty()) return null
        var angle = 180.0
        var corner: Pair<Int, Int>? = null
        var from = 0
        val head = parts[0].lowercase()
        if (head.startsWith("to ")) {
            val words = head.removePrefix("to ").split(' ').filter { it.isNotEmpty() }
            var sx = 0
            var sy = 0
            for (w in words) when (w) {
                "left" -> sx = -1
                "right" -> sx = 1
                "top" -> sy = -1
                "bottom" -> sy = 1
                else -> return null
            }
            when {
                sx != 0 && sy != 0 -> corner = sx to sy
                sx != 0 -> angle = if (sx > 0) 90.0 else 270.0
                sy != 0 -> angle = if (sy > 0) 180.0 else 0.0
                else -> return null
            }
            from = 1
        } else {
            angleOf(head)?.let { angle = it; from = 1 }
        }
        val stops = ArrayList<GradientStop>()
        for (part in parts.drop(from)) {
            val tokens = CssParser.splitTopLevel(part, ' ').map { it.trim() }.filter { it.isNotEmpty() }
            val colorToken = tokens.firstOrNull { it.equals("currentcolor", ignoreCase = true) || CssValues.alpha(it) != null } ?: return null
            val current = colorToken.equals("currentcolor", ignoreCase = true)
            val color = if (current) null else CssValues.color(colorToken) ?: RgbColor(0.0, 0.0, 0.0)
            val alpha = if (current) 1.0 else CssValues.alpha(colorToken) ?: 1.0
            val positions = tokens.filter { it !== colorToken }.map { offset(it) ?: return null }
            // A stop with two positions is two stops of one colour.
            if (positions.isEmpty()) stops += GradientStop(color, alpha, null)
            for (p in positions.take(2)) stops += GradientStop(color, alpha, p)
        }
        return if (stops.size >= 2) CssBackgroundImage.LinearGradient(angle, corner, stops) else null
    }

    /** An angle in degrees: `deg`, `grad`, `rad` or `turn`, or a bare 0. */
    private fun angleOf(token: String): Double? {
        fun num(suffix: String) = token.removeSuffix(suffix).trim().toDoubleOrNull()
        return when {
            token.endsWith("deg") -> num("deg")
            token.endsWith("grad") -> num("grad")?.times(0.9)
            token.endsWith("rad") -> num("rad")?.times(180.0 / PI)
            token.endsWith("turn") -> num("turn")?.times(360.0)
            token == "0" -> 0.0
            else -> null
        }
    }

    /** A length or a percentage. */
    fun offset(token: String): CssOffset? {
        val t = token.trim()
        if (t.endsWith('%')) return t.dropLast(1).trim().toDoubleOrNull()?.let { CssOffset(it / 100.0, true) }
        return length(t)?.let { CssOffset(it, false) }
    }

    /** `background-size`, or null when it does not read. */
    fun size(value: String): CssBackgroundSize? {
        val v = value.trim().lowercase()
        if (v == "cover") return CssBackgroundSize(cover = true)
        if (v == "contain") return CssBackgroundSize(contain = true)
        val parts = v.split(' ').filter { it.isNotEmpty() }
        if (parts.size !in 1..2) return null
        val sizes = parts.map { p -> if (p == "auto") null else (offset(p) ?: return null) }
        return CssBackgroundSize(width = sizes[0], height = sizes.getOrNull(1))
    }

    /** `background-position` with one or two values, keywords or offsets, as x then y. */
    fun position(value: String): Pair<CssOffset, CssOffset>? {
        val parts = value.trim().lowercase().split(' ').filter { it.isNotEmpty() }
        if (parts.size !in 1..2) return null
        var x: CssOffset? = null
        var y: CssOffset? = null
        val rest = ArrayList<CssOffset>()
        for (p in parts) when (p) {
            "left" -> x = CssOffset.ZERO
            "right" -> x = CssOffset.FULL
            "top" -> y = CssOffset.ZERO
            "bottom" -> y = CssOffset.FULL
            "center" -> rest += CssOffset.HALF
            else -> rest += offset(p) ?: return null
        }
        // Offsets without a keyword fill x first, then y, and a lone value centres the other axis.
        for (o in rest) if (x == null) x = o else if (y == null) y = o else return null
        return (x ?: CssOffset.HALF) to (y ?: CssOffset.HALF)
    }

    /** `background-repeat`: repeat across, and repeat down. Space and round repeat. */
    fun repeat(value: String): Pair<Boolean, Boolean>? = when (value.trim().lowercase()) {
        "no-repeat", "no-repeat no-repeat" -> false to false
        "repeat-x", "repeat no-repeat" -> true to false
        "repeat-y", "no-repeat repeat" -> false to true
        "repeat", "space", "round", "repeat repeat" -> true to true
        else -> null
    }
}
