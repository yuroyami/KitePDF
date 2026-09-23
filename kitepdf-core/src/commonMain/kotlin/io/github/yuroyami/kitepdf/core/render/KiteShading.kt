package io.github.yuroyami.kitepdf.core.render

import io.github.yuroyami.kitepdf.core.KiteRectangle
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
 *   - **Type 1** function-based: a colour function over a 2D domain,
 *     rasterized as a grid of cells
 *   - **Type 2** axial: a linear gradient between two points
 *   - **Type 3** radial: a radial gradient between two circles
 *   - **Types 4/5** Gouraud triangle meshes: the colour is interpolated
 *     across each triangle
 *   - **Types 6/7** Coons and tensor-product patches: the exact tensor-product
 *     surface, split into small triangles in device space
 *
 * Types 1/4/5/6/7 render through [paintComplexShading], shared by every
 * backend: type 1 as a grid of `fillPath` cells, and a mesh as one image drawn
 * with `drawImage`. Types 2/3 keep the backends' native gradient brushes.
 * Unparseable shadings become [Unsupported] and paint nothing.
 */
public sealed class KiteShading {

    /** The shading's colour space (DeviceGray / DeviceRGB / DeviceCMYK / Indexed). */
    public abstract val colorSpace: KiteColorSpace

    /**
     * Optional `/Background` colour, used for regions outside the shading
     * domain when `Extend` is false on the relevant side. Per spec the
     * background is in [colorSpace]; we eager-convert to RGB.
     */
    public abstract val background: RgbColor?

    /** Optional clipping rectangle (`/BBox`) in shading-space. */
    public abstract val bbox: KiteRectangle?

    /**
     * Type 2 axial shading. Linear gradient between `(x0, y0)` and
     * `(x1, y1)` with `t` running across [domain]. [function] supplies a
     * colour per `t` value; we sample it at a fixed number of stops and
     * hand those to the backend.
     */
    public data class Axial(
        override val colorSpace: KiteColorSpace,
        override val background: RgbColor?,
        override val bbox: KiteRectangle?,
        /** [x0, y0, x1, y1] in shading-space. */
        val coords: DoubleArray,
        /** [t0, t1]: domain of [function]. */
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
        override val colorSpace: KiteColorSpace,
        override val background: RgbColor?,
        override val bbox: KiteRectangle?,
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
        override val colorSpace: KiteColorSpace,
        override val background: RgbColor?,
        override val bbox: KiteRectangle?,
        /** [x0, x1, y0, y1]. */
        public val domain: DoubleArray,
        public val matrix: KiteMatrix,
        private val function: KiteFunction,
    ) : KiteShading() {
        public fun colorAt(x: Double, y: Double): RgbColor =
            colorSpace.toRgb(function.evaluate(doubleArrayOf(x, y)))
    }

    /**
     * One Gouraud triangle: three shading-space vertices with colours. In a mesh with a
     * /Function, [t] holds the parametric value of each vertex. A point inside the triangle
     * then takes its colour from the interpolated t, not from the interpolated [colors]
     * (ISO 32000-1, 8.7.4.5.5).
     */
    public class MeshTriangle(
        public val x: DoubleArray,
        public val y: DoubleArray,
        public val colors: Array<RgbColor>,
        public val t: DoubleArray? = null,
    )

    /** Types 4/5: a triangle mesh with per-vertex colours (ISO 32000-1, 8.7.4.5.5 and 8.7.4.5.6). */
    public class TriangleMesh(
        override val colorSpace: KiteColorSpace,
        override val background: RgbColor?,
        override val bbox: KiteRectangle?,
        public val triangles: List<MeshTriangle>,
        /** The colours of t when the mesh has a /Function, or null when its vertices carry colours. */
        public val colorTable: MeshColorTable? = null,
    ) : KiteShading()

    /**
     * One patch of a type 6 or 7 mesh as a tensor-product surface (ISO 32000-1, 8.7.4.5.8).
     * [x] and [y] hold the 16 control points in shading space, p(i, j) at index 4 i + j, and
     * [colors] the colours of the corners p(0,0), p(0,3), p(3,3) and p(3,0). A Coons patch of
     * type 6 gets its 4 interior points from its boundary, by the equations of 8.7.4.5.8, and
     * the tensor-product surface of those points is the Coons surface.
     */
    public class MeshPatch(
        public val x: DoubleArray,
        public val y: DoubleArray,
        public val colors: Array<RgbColor>,
        /** The parametric value of each corner when the mesh has a /Function, else null. */
        public val t: DoubleArray? = null,
    )

    /** Types 6/7: a mesh of Coons or tensor-product patches (ISO 32000-1, 8.7.4.5.7 and 8.7.4.5.8). */
    public class PatchMesh(
        override val colorSpace: KiteColorSpace,
        override val background: RgbColor?,
        override val bbox: KiteRectangle?,
        public val patches: List<MeshPatch>,
        /** The colours of t when the mesh has a /Function, or null when its corners carry colours. */
        public val colorTable: MeshColorTable? = null,
    ) : KiteShading()

    /**
     * The colours of the parametric value t in a mesh with a /Function: the function sampled
     * at 256 even steps from [t0] to [t1], the /Decode range of t, as MuPDF samples it. A point
     * of the mesh interpolates t first and then looks its colour up here.
     */
    public class MeshColorTable(public val t0: Double, public val t1: Double, public val colors: Array<RgbColor>) {
        /** The colour of [t]. */
        public fun colorAt(t: Double): RgbColor = colors[indexOf(t)]

        /** The entry of [t]: the fraction of the range, truncated to a step of 1/255, as MuPDF truncates it. */
        internal fun indexOf(t: Double): Int {
            val f = (t - t0) / (t1 - t0) * 255.0
            return if (f.isNaN()) 0 else f.coerceIn(0.0, (colors.size - 1).toDouble()).toInt()
        }
    }

    /** Shading type we don't render; [sampleStops] returns null, so nothing paints. */
    public data class Unsupported(
        val type: Int,
        override val colorSpace: KiteColorSpace,
        override val background: RgbColor?,
        override val bbox: KiteRectangle?,
    ) : KiteShading()

    public companion object {

        /** Parse a /Shading object (a dict, or a stream for Types 4–7 only). */
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
            val cs = KiteColorSpace.resolve(dict["ColorSpace"], refs)
            val bbox = (dict.getArray("BBox"))?.let { arr ->
                if (arr.size >= 4) KiteRectangle(arr.num(0), arr.num(1), arr.num(2), arr.num(3))
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
            dict: PdfDictionary, cs: KiteColorSpace, bg: RgbColor?,
            bbox: KiteRectangle?, refs: IndirectResolver,
        ): KiteShading? {
            val function = KiteFunction.parse(dict["Function"], refs) ?: return null
            val domain = (dict.getArray("Domain"))?.let { arr ->
                if (arr.size >= 4) DoubleArray(4) { arr.num(it) } else null
            } ?: doubleArrayOf(0.0, 1.0, 0.0, 1.0)
            val matrix = (dict.getArray("Matrix"))?.let { arr ->
                if (arr.size >= 6) KiteMatrix(arr.num(0), arr.num(1), arr.num(2), arr.num(3), arr.num(4), arr.num(5))
                else null
            } ?: KiteMatrix.IDENTITY
            return FunctionBased(cs, bg, bbox, domain, matrix, function)
        }

        private fun parseAxial(
            dict: PdfDictionary, cs: KiteColorSpace, bg: RgbColor?,
            bbox: KiteRectangle?, refs: IndirectResolver,
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
            dict: PdfDictionary, cs: KiteColorSpace, bg: RgbColor?,
            bbox: KiteRectangle?, refs: IndirectResolver,
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
 * RGB arrays the backend uses to build a gradient brush. The default of 256
 * matches MuPDF, so a function with many narrow bands keeps them all (#153).
 */
public fun KiteShading.sampleStops(count: Int = 256): GradientStops? {
    val function: KiteFunction
    val domain: DoubleArray
    val cs: KiteColorSpace
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
