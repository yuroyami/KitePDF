package io.github.yuroyami.kitepdf.core.render

import io.github.yuroyami.kitepdf.core.Rectangle
import io.github.yuroyami.kitepdf.core.parser.IndirectResolver
import io.github.yuroyami.kitepdf.core.parser.PdfArray
import io.github.yuroyami.kitepdf.core.parser.PdfBoolean
import io.github.yuroyami.kitepdf.core.parser.PdfDictionary
import io.github.yuroyami.kitepdf.core.parser.PdfInt
import io.github.yuroyami.kitepdf.core.parser.PdfName
import io.github.yuroyami.kitepdf.core.parser.PdfObject
import io.github.yuroyami.kitepdf.core.parser.PdfReal
import io.github.yuroyami.kitepdf.core.parser.PdfReference
import io.github.yuroyami.kitepdf.core.parser.PdfStream

/**
 * A PDF shading (ISO 32000-1 §8.7.4). Shadings define smooth color
 * transitions used as fills via the `sh` content-stream operator or via a
 * shading pattern referenced by `SCN`/`scn`.
 *
 * KitePDF renders:
 *   - **Type 1** function-based — a colour function over a 2D domain,
 *     rasterized as a grid of cells (T-40)
 *   - **Type 2** axial — a linear gradient between two points
 *   - **Type 3** radial — a radial gradient between two circles
 *   - **Types 4/5** Gouraud triangle meshes — smoothed by recursive
 *     subdivision with vertex-colour interpolation (T-40)
 *   - **Types 6/7** Coons / tensor patches — tessellated into flat-coloured
 *     quads via Coons boundary evaluation + bilinear corner colours; the
 *     tensor type's four interior points are read but ignored, the documented
 *     MuPDF-level approximation (T-40)
 *
 * Types 1/4/5/6/7 render through [paintComplexShading], shared by every
 * backend (pure `fillPath` emission); 2/3 keep the backends' native gradient
 * brushes. Unparseable shadings become [Unsupported] and paint nothing.
 */
public sealed class KiteShading {

    /** The shading's colour space (DeviceGray / DeviceRGB / DeviceCMYK / Indexed). */
    public abstract val colorSpace: ColorSpace

    /**
     * Optional `/Background` colour — used for regions outside the shading
     * domain when `Extend` is false on the relevant side. Per spec the
     * background is in [colorSpace]; we eager-convert to RGB.
     */
    public abstract val background: RgbColor?

    /** Optional clipping rectangle (`/BBox`) in shading-space. */
    public abstract val bbox: Rectangle?

    /**
     * Type 2 axial shading. Linear gradient between `(x0, y0)` and
     * `(x1, y1)` with `t` running across [domain]. [function] supplies a
     * colour per `t` value; we sample it at a fixed number of stops and
     * hand those to the backend.
     */
    public data class Axial(
        override val colorSpace: ColorSpace,
        override val background: RgbColor?,
        override val bbox: Rectangle?,
        /** [x0, y0, x1, y1] in shading-space. */
        val coords: DoubleArray,
        /** [t0, t1] — domain of [function]. */
        val domain: DoubleArray,
        val function: KiteFunction,
        /** Extend the gradient beyond t0 / t1 with the endpoint colours. */
        val extendStart: Boolean,
        val extendEnd: Boolean,
    ) : KiteShading() {
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
    public data class Radial(
        override val colorSpace: ColorSpace,
        override val background: RgbColor?,
        override val bbox: Rectangle?,
        /** [x0, y0, r0, x1, y1, r1] in shading-space. */
        val coords: DoubleArray,
        val domain: DoubleArray,
        val function: KiteFunction,
        val extendStart: Boolean,
        val extendEnd: Boolean,
    ) : KiteShading() {
        override fun equals(other: Any?): Boolean = other is Radial &&
            colorSpace == other.colorSpace && background == other.background && bbox == other.bbox &&
            coords.contentEquals(other.coords) && domain.contentEquals(other.domain) &&
            function == other.function && extendStart == other.extendStart && extendEnd == other.extendEnd
        override fun hashCode(): Int = 31 * (31 * coords.contentHashCode() + function.hashCode()) +
            domain.contentHashCode()
    }

    /**
     * Type 1: colour as a function of (x, y) over [domain] (x0 x1 y0 y1),
     * mapped into user space by [matrix]. Rendered as a grid of coloured
     * cells by [paintComplexShading].
     */
    public class FunctionBased(
        override val colorSpace: ColorSpace,
        override val background: RgbColor?,
        override val bbox: Rectangle?,
        /** [x0, x1, y0, y1]. */
        public val domain: DoubleArray,
        public val matrix: Matrix,
        private val function: KiteFunction,
    ) : KiteShading() {
        public fun colorAt(x: Double, y: Double): RgbColor =
            colorSpace.toRgb(function.evaluate(doubleArrayOf(x, y)))
    }

    /** One Gouraud triangle: three shading-space vertices with colours. */
    public class MeshTriangle(
        public val x: DoubleArray,
        public val y: DoubleArray,
        public val colors: Array<RgbColor>,
    )

    /** Types 4/5: a triangle mesh with per-vertex colours. */
    public class TriangleMesh(
        override val colorSpace: ColorSpace,
        override val background: RgbColor?,
        override val bbox: Rectangle?,
        public val triangles: List<MeshTriangle>,
    ) : KiteShading()

    /** A flat-coloured tessellation quad from a Coons/tensor patch. */
    public class FlatQuad(
        public val xs: DoubleArray,
        public val ys: DoubleArray,
        public val color: RgbColor,
    )

    /** Types 6/7, pre-tessellated at parse time. */
    public class PatchMesh(
        override val colorSpace: ColorSpace,
        override val background: RgbColor?,
        override val bbox: Rectangle?,
        public val quads: List<FlatQuad>,
    ) : KiteShading()

    /** Shading type we don't render; [sampleStops] returns null, so nothing paints. */
    public data class Unsupported(
        val type: Int,
        override val colorSpace: ColorSpace,
        override val background: RgbColor?,
        override val bbox: Rectangle?,
    ) : KiteShading()

    public companion object {

        /** Parse a /Shading object (dict or stream — stream is only for Types 4–7). */
        public fun parse(obj: PdfObject?, refs: IndirectResolver): KiteShading? {
            val resolved = when (obj) {
                is PdfReference -> refs.resolve(obj)
                else -> obj
            } ?: return null
            return when (resolved) {
                is PdfDictionary -> parseDict(resolved, null, refs)
                is PdfStream -> parseDict(resolved.dict, resolved, refs)
                else -> null
            }
        }

        private fun parseDict(dict: PdfDictionary, stream: PdfStream?, refs: IndirectResolver): KiteShading? {
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
            return runCatching {
                when (type) {
                    1 -> parseFunctionBased(dict, cs, bg, bbox, refs)
                    2 -> parseAxial(dict, cs, bg, bbox, refs)
                    3 -> parseRadial(dict, cs, bg, bbox, refs)
                    4, 5 -> stream?.let { MeshShadingParser.parseTriangles(type, dict, it, cs, bg, bbox, refs) }
                    6, 7 -> stream?.let { MeshShadingParser.parsePatches(type, dict, it, cs, bg, bbox, refs) }
                    else -> null
                }
            }.getOrNull() ?: Unsupported(type, cs, bg, bbox)
        }

        private fun parseFunctionBased(
            dict: PdfDictionary, cs: ColorSpace, bg: RgbColor?,
            bbox: Rectangle?, refs: IndirectResolver,
        ): KiteShading? {
            val function = KiteFunction.parse(dict["Function"], refs) ?: return null
            val domain = (dict.getArray("Domain"))?.let { arr ->
                if (arr.size >= 4) DoubleArray(4) { arr.num(it) } else null
            } ?: doubleArrayOf(0.0, 1.0, 0.0, 1.0)
            val matrix = (dict.getArray("Matrix"))?.let { arr ->
                if (arr.size >= 6) Matrix(arr.num(0), arr.num(1), arr.num(2), arr.num(3), arr.num(4), arr.num(5))
                else null
            } ?: Matrix.IDENTITY
            return FunctionBased(cs, bg, bbox, domain, matrix, function)
        }

        private fun parseAxial(
            dict: PdfDictionary, cs: ColorSpace, bg: RgbColor?,
            bbox: Rectangle?, refs: IndirectResolver,
        ): KiteShading? {
            val coords = (dict.getArray("Coords"))?.let { arr ->
                if (arr.size >= 4) DoubleArray(4) { arr.num(it) } else null
            } ?: return null
            val domain = (dict.getArray("Domain"))?.let { arr ->
                if (arr.size >= 2) DoubleArray(2) { arr.num(it) } else null
            } ?: doubleArrayOf(0.0, 1.0)
            val function = KiteFunction.parse(dict["Function"], refs) ?: return null
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
        ): KiteShading? {
            val coords = (dict.getArray("Coords"))?.let { arr ->
                if (arr.size >= 6) DoubleArray(6) { arr.num(it) } else null
            } ?: return null
            val domain = (dict.getArray("Domain"))?.let { arr ->
                if (arr.size >= 2) DoubleArray(2) { arr.num(it) } else null
            } ?: doubleArrayOf(0.0, 1.0)
            val function = KiteFunction.parse(dict["Function"], refs) ?: return null
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
 * Sample a [KiteShading.Axial] or [KiteShading.Radial] at evenly-spaced
 * stops between `domain[0]` and `domain[1]`. Returns parallel `t` and
 * RGB arrays the backend uses to build a gradient brush.
 */
public fun KiteShading.sampleStops(count: Int = 32): GradientStops? {
    val function: KiteFunction
    val domain: DoubleArray
    val cs: ColorSpace
    when (this) {
        is KiteShading.Axial -> { function = this.function; domain = this.domain; cs = this.colorSpace }
        is KiteShading.Radial -> { function = this.function; domain = this.domain; cs = this.colorSpace }
        else -> return null // 1/4/5/6/7 render via paintComplexShading; Unsupported paints nothing
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
public data class GradientStops(val offsets: DoubleArray, val colors: Array<RgbColor>) {
    override fun equals(other: Any?): Boolean = other is GradientStops &&
        offsets.contentEquals(other.offsets) && colors.contentEquals(other.colors)
    override fun hashCode(): Int = 31 * offsets.contentHashCode() + colors.contentHashCode()
}
