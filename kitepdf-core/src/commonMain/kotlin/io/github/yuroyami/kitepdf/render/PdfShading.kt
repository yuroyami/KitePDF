package io.github.yuroyami.kitepdf.render

import io.github.yuroyami.kitepdf.Rectangle
import io.github.yuroyami.kitepdf.parser.IndirectResolver
import io.github.yuroyami.kitepdf.parser.PdfArray
import io.github.yuroyami.kitepdf.parser.PdfBoolean
import io.github.yuroyami.kitepdf.parser.PdfDictionary
import io.github.yuroyami.kitepdf.parser.PdfInt
import io.github.yuroyami.kitepdf.parser.PdfName
import io.github.yuroyami.kitepdf.parser.PdfObject
import io.github.yuroyami.kitepdf.parser.PdfReal
import io.github.yuroyami.kitepdf.parser.PdfReference
import io.github.yuroyami.kitepdf.parser.PdfStream

/**
 * A PDF shading (ISO 32000-1 §8.7.4). Shadings define smooth color
 * transitions used as fills via the `sh` content-stream operator or via a
 * shading pattern referenced by `SCN`/`scn`.
 *
 * KitePDF renders:
 *   - **Type 2** axial — a linear gradient between two points
 *   - **Type 3** radial — a radial gradient between two circles
 *
 * Types 1 (function-based), 4 (free-form Gouraud), 5 (lattice-form Gouraud),
 * 6 (Coons patch), and 7 (tensor-product patch) parse to [Unsupported];
 * [sampleStops] returns null for them, so backends paint nothing (transparent).
 * Rendering them is roadmap work (audit T-40).
 */
sealed class PdfShading {

    /** The shading's colour space (DeviceGray / DeviceRGB / DeviceCMYK / Indexed). */
    abstract val colorSpace: ColorSpace

    /**
     * Optional `/Background` colour — used for regions outside the shading
     * domain when `Extend` is false on the relevant side. Per spec the
     * background is in [colorSpace]; we eager-convert to RGB.
     */
    abstract val background: RgbColor?

    /** Optional clipping rectangle (`/BBox`) in shading-space. */
    abstract val bbox: Rectangle?

    /**
     * Type 2 axial shading. Linear gradient between `(x0, y0)` and
     * `(x1, y1)` with `t` running across [domain]. [function] supplies a
     * colour per `t` value; we sample it at a fixed number of stops and
     * hand those to the backend.
     */
    data class Axial(
        override val colorSpace: ColorSpace,
        override val background: RgbColor?,
        override val bbox: Rectangle?,
        /** [x0, y0, x1, y1] in shading-space. */
        val coords: DoubleArray,
        /** [t0, t1] — domain of [function]. */
        val domain: DoubleArray,
        val function: PdfFunction,
        /** Extend the gradient beyond t0 / t1 with the endpoint colours. */
        val extendStart: Boolean,
        val extendEnd: Boolean,
    ) : PdfShading() {
        override fun equals(other: Any?): Boolean = other is Axial &&
            colorSpace == other.colorSpace && background == other.background && bbox == other.bbox &&
            coords.contentEquals(other.coords) && domain.contentEquals(other.domain) &&
            function == other.function && extendStart == other.extendStart && extendEnd == other.extendEnd
        override fun hashCode(): Int {
            var h = colorSpace.hashCode()
            h = 31 * h + (background?.hashCode() ?: 0)
            h = 31 * h + (bbox?.hashCode() ?: 0)
            h = 31 * h + coords.contentHashCode()
            h = 31 * h + domain.contentHashCode()
            h = 31 * h + function.hashCode()
            h = 31 * h + extendStart.hashCode()
            h = 31 * h + extendEnd.hashCode()
            return h
        }
    }

    /**
     * Type 3 radial shading. Gradient between two circles:
     * `(x0, y0, r0)` and `(x1, y1, r1)` with `t` running across [domain].
     */
    data class Radial(
        override val colorSpace: ColorSpace,
        override val background: RgbColor?,
        override val bbox: Rectangle?,
        /** [x0, y0, r0, x1, y1, r1] in shading-space. */
        val coords: DoubleArray,
        val domain: DoubleArray,
        val function: PdfFunction,
        val extendStart: Boolean,
        val extendEnd: Boolean,
    ) : PdfShading() {
        override fun equals(other: Any?): Boolean = other is Radial &&
            colorSpace == other.colorSpace && background == other.background && bbox == other.bbox &&
            coords.contentEquals(other.coords) && domain.contentEquals(other.domain) &&
            function == other.function && extendStart == other.extendStart && extendEnd == other.extendEnd
        override fun hashCode(): Int = 31 * (31 * coords.contentHashCode() + function.hashCode()) +
            domain.contentHashCode()
    }

    /** Shading type we don't render; [sampleStops] returns null, so nothing paints. */
    data class Unsupported(
        val type: Int,
        override val colorSpace: ColorSpace,
        override val background: RgbColor?,
        override val bbox: Rectangle?,
    ) : PdfShading()

    companion object {

        /** Parse a /Shading object (dict or stream — stream is only for Types 4–7). */
        fun parse(obj: PdfObject?, refs: IndirectResolver): PdfShading? {
            val resolved = when (obj) {
                is PdfReference -> refs.resolve(obj)
                else -> obj
            } ?: return null
            val dict = when (resolved) {
                is PdfDictionary -> resolved
                is PdfStream -> resolved.dict
                else -> return null
            }
            return parseDict(dict, refs)
        }

        private fun parseDict(dict: PdfDictionary, refs: IndirectResolver): PdfShading? {
            val type = dict.getInt("ShadingType")?.toInt() ?: return null
            val cs = ColorSpace.resolve(dict["ColorSpace"], refs)
            val bbox = (dict.getArray("BBox"))?.let { arr ->
                if (arr.size >= 4) Rectangle(arr.num(0), arr.num(1), arr.num(2), arr.num(3))
                else null
            }
            val bg = (dict.getArray("Background"))?.let { arr ->
                val comps = DoubleArray(arr.size) { i -> arr.num(i) }
                cs.toRgb(comps)
            }
            return when (type) {
                2 -> parseAxial(dict, cs, bg, bbox, refs)
                3 -> parseRadial(dict, cs, bg, bbox, refs)
                else -> Unsupported(type, cs, bg, bbox)
            }
        }

        private fun parseAxial(
            dict: PdfDictionary, cs: ColorSpace, bg: RgbColor?,
            bbox: Rectangle?, refs: IndirectResolver,
        ): PdfShading? {
            val coords = (dict.getArray("Coords"))?.let { arr ->
                if (arr.size >= 4) DoubleArray(4) { arr.num(it) } else null
            } ?: return null
            val domain = (dict.getArray("Domain"))?.let { arr ->
                if (arr.size >= 2) DoubleArray(2) { arr.num(it) } else null
            } ?: doubleArrayOf(0.0, 1.0)
            val function = PdfFunction.parse(dict["Function"], refs) ?: return null
            val extend = (dict.getArray("Extend"))?.let { arr ->
                if (arr.size >= 2)
                    Pair((arr[0] as? PdfBoolean)?.value ?: false, (arr[1] as? PdfBoolean)?.value ?: false)
                else null
            } ?: (false to false)
            return Axial(cs, bg, bbox, coords, domain, function, extend.first, extend.second)
        }

        private fun parseRadial(
            dict: PdfDictionary, cs: ColorSpace, bg: RgbColor?,
            bbox: Rectangle?, refs: IndirectResolver,
        ): PdfShading? {
            val coords = (dict.getArray("Coords"))?.let { arr ->
                if (arr.size >= 6) DoubleArray(6) { arr.num(it) } else null
            } ?: return null
            val domain = (dict.getArray("Domain"))?.let { arr ->
                if (arr.size >= 2) DoubleArray(2) { arr.num(it) } else null
            } ?: doubleArrayOf(0.0, 1.0)
            val function = PdfFunction.parse(dict["Function"], refs) ?: return null
            val extend = (dict.getArray("Extend"))?.let { arr ->
                if (arr.size >= 2)
                    Pair((arr[0] as? PdfBoolean)?.value ?: false, (arr[1] as? PdfBoolean)?.value ?: false)
                else null
            } ?: (false to false)
            return Radial(cs, bg, bbox, coords, domain, function, extend.first, extend.second)
        }
    }
}

private fun PdfArray.num(i: Int): Double = when (val v = this[i]) {
    is PdfReal -> v.value
    is PdfInt -> v.value.toDouble()
    else -> 0.0
}

/**
 * Sample a [PdfShading.Axial] or [PdfShading.Radial] at evenly-spaced
 * stops between `domain[0]` and `domain[1]`. Returns parallel `t` and
 * RGB arrays the backend uses to build a gradient brush.
 */
fun PdfShading.sampleStops(count: Int = 32): GradientStops? {
    val function: PdfFunction
    val domain: DoubleArray
    val cs: ColorSpace
    when (this) {
        is PdfShading.Axial -> { function = this.function; domain = this.domain; cs = this.colorSpace }
        is PdfShading.Radial -> { function = this.function; domain = this.domain; cs = this.colorSpace }
        is PdfShading.Unsupported -> return null
    }
    val n = count.coerceAtLeast(2)
    val ts = DoubleArray(n)
    val colors = arrayOfNulls<RgbColor>(n)
    val t0 = domain[0]
    val t1 = domain[1]
    val input = DoubleArray(1)  // reused across stops (evaluate reads, doesn't retain)
    for (i in 0 until n) {
        val frac = i.toDouble() / (n - 1)
        ts[i] = frac  // normalised offset 0..1 for the backend gradient
        input[0] = t0 + frac * (t1 - t0)
        colors[i] = cs.toRgb(function.evaluate(input))
    }
    @Suppress("UNCHECKED_CAST")
    return GradientStops(ts, colors as Array<RgbColor>)
}

/** Parallel offset/colour arrays describing a sampled gradient. */
data class GradientStops(val offsets: DoubleArray, val colors: Array<RgbColor>) {
    override fun equals(other: Any?): Boolean = other is GradientStops &&
        offsets.contentEquals(other.offsets) && colors.contentEquals(other.colors)
    override fun hashCode(): Int = 31 * offsets.contentHashCode() + colors.contentHashCode()
}
