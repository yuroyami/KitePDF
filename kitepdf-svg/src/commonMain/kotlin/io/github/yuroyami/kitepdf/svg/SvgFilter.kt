package io.github.yuroyami.kitepdf.svg

import io.github.yuroyami.kitepdf.core.render.KiteMatrix
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * One filter primitive of a `<filter>` (Filter Effects 1, 15), with its lengths already in the
 * user space of the filtered element. [input] and [input2] are its `in` and `in2`, null for
 * the default, and [x], [y], [width] and [height] its subregion, null where the default applies.
 * [linear] is its `color-interpolation-filters`: true for linearRGB.
 */
internal sealed class FilterPrimitive {
    var input: String? = null
    var input2: String? = null
    var result: String? = null
    var x: Double? = null
    var y: Double? = null
    var width: Double? = null
    var height: Double? = null
    var linear: Boolean = true

    /** The images this primitive reads: none for a primitive that makes its own. */
    open val inputs: List<String?> get() = listOf(input)

    class Blur(val sx: Double, val sy: Double) : FilterPrimitive()
    class Offset(val dx: Double, val dy: Double) : FilterPrimitive()

    /** A straight sRGB colour at an opacity. */
    class Flood(val rgb: FloatArray, val opacity: Double) : FilterPrimitive() {
        override val inputs: List<String?> get() = emptyList()
    }

    class Merge(val nodes: List<String?>) : FilterPrimitive() {
        override val inputs: List<String?> get() = nodes
    }

    class Blend(val mode: String) : FilterPrimitive() {
        override val inputs: List<String?> get() = listOf(input, input2)
    }

    class Composite(val operator: String, val k: DoubleArray) : FilterPrimitive() {
        override val inputs: List<String?> get() = listOf(input, input2)
    }

    /** A colour matrix of 20 values, or null for a primitive that passes its input through. */
    class ColorMatrix(val matrix: DoubleArray?) : FilterPrimitive()
    class ComponentTransfer(val functions: Array<((Float) -> Float)?>) : FilterPrimitive()
    class DropShadow(val dx: Double, val dy: Double, val sx: Double, val sy: Double, val rgb: FloatArray, val opacity: Double) : FilterPrimitive()
    class Morphology(val rx: Double, val ry: Double, val erode: Boolean) : FilterPrimitive()
    class Tile : FilterPrimitive()

    /** feImage of [href], an image file or `#id` of an element, fitted into its subregion by [preserveAspectRatio]. */
    class Image(val href: String, val preserveAspectRatio: String?) : FilterPrimitive() {
        override val inputs: List<String?> get() = emptyList()
    }

    class Turbulence(
        val baseX: Double, val baseY: Double, val octaves: Int, val seed: Double, val stitch: Boolean, val fractal: Boolean,
    ) : FilterPrimitive() {
        override val inputs: List<String?> get() = emptyList()
    }

    class Convolve(
        val orderX: Int, val orderY: Int, val kernel: DoubleArray?, val divisor: Double, val bias: Double,
        val targetX: Int, val targetY: Int, val edgeMode: String, val preserveAlpha: Boolean,
    ) : FilterPrimitive()

    class Displacement(val scale: Double, val xChannel: Int, val yChannel: Int) : FilterPrimitive() {
        override val inputs: List<String?> get() = listOf(input, input2)
    }

    /** A light in user space: x, y, z, and for a spot light the point it aims at, its exponent and cone. */
    class Lighting(
        val specular: Boolean, val surfaceScale: Double, val constant: Double, val exponent: Double,
        val rgb: FloatArray, val light: LightSource?,
    ) : FilterPrimitive()

    class LightSource(
        val kind: String, val azimuth: Double, val elevation: Double,
        val x: Double, val y: Double, val z: Double, val px: Double, val py: Double, val pz: Double,
        val exponent: Double, val coneAngle: Double?,
    )
}

/**
 * The run of one filter over the pixels of a raster step: [source] is the element as it drew,
 * in sRGB, [toPixels] maps the element's user space to those pixels, and [region] is the
 * filter region in user space as left, top, right and bottom.
 */
internal class FilterContext(
    val source: FilterImage,
    val toPixels: KiteMatrix,
    val region: DoubleArray,
    /** The element's fill and stroke as straight sRGB with alpha, for FillPaint and StrokePaint. */
    val fillPaint: FloatArray?,
    val strokePaint: FloatArray?,
    /** Draws an feImage into an image of this run's size, given its subregion in user space as left, top, right and bottom. */
    val images: (FilterPrimitive.Image, DoubleArray) -> FilterImage?,
) {
    val width: Int get() = source.width
    val height: Int get() = source.height

    /** How many pixels one user unit along x and along y covers. */
    val scaleX: Double = sqrt(toPixels.a * toPixels.a + toPixels.b * toPixels.b)
    val scaleY: Double = sqrt(toPixels.c * toPixels.c + toPixels.d * toPixels.d)

    /** The pixels of a user-space box given as left, top, right and bottom. */
    fun pixels(box: DoubleArray): PixelRect {
        val xs = DoubleArray(4)
        val ys = DoubleArray(4)
        var k = 0
        for (x in doubleArrayOf(box[0], box[2])) for (y in doubleArrayOf(box[1], box[3])) {
            xs[k] = toPixels.transformX(x, y); ys[k] = toPixels.transformY(x, y); k++
        }
        return PixelRect.around(xs.min(), ys.min(), xs.max(), ys.max()).intersect(PixelRect(0, 0, width, height))
    }

    /** One result: its image and its subregion in user space. */
    private class Result(val image: FilterImage, val box: DoubleArray)

    /**
     * Runs [primitives] in order and gives the last result, cut to the filter region. A
     * reference to a result that does not exist reads as no reference (15.7.2).
     */
    fun run(primitives: List<FilterPrimitive>): FilterImage {
        val named = HashMap<String, Result>()
        var last: Result? = null
        fun standard(name: String): Result? = when (name) {
            "SourceGraphic" -> Result(source, region)
            "SourceAlpha" -> Result(alphaOf(source), region)
            // No backdrop reaches a filter in SVG 2, so the background inputs are transparent black.
            "BackgroundImage", "BackgroundAlpha" -> Result(FilterImage(width, height, source.linear), region)
            "FillPaint" -> Result(paintImage(fillPaint), region)
            "StrokePaint" -> Result(paintImage(strokePaint), region)
            else -> null
        }
        fun read(name: String?): Pair<Result, Boolean> {
            if (name != null) {
                standard(name)?.let { return it to true }
                named[name]?.let { return it to false }
            }
            last?.let { return it to false }
            return Result(source, region) to true
        }
        for (p in primitives) {
            val inputs = p.inputs.map { read(it) }
            // The default subregion is the union of the inputs' subregions, or the filter region
            // when there is none, when one is a standard input, and for feTile, which fills it (15.7.3).
            val default = if (inputs.isEmpty() || inputs.any { it.second } || p is FilterPrimitive.Tile) region else inputs.map { it.first.box }.reduce { a, b ->
                doubleArrayOf(minOf(a[0], b[0]), minOf(a[1], b[1]), maxOf(a[2], b[2]), maxOf(a[3], b[3]))
            }
            val left = p.x ?: default[0]
            val top = p.y ?: default[1]
            val box = doubleArrayOf(left, top, left + (p.width ?: (default[2] - default[0])), top + (p.height ?: (default[3] - default[1])))
            val subregion = pixels(box)
            val image = apply(p, inputs.map { it.first }, subregion)
            image.keepOnly(subregion)
            val result = Result(image, box)
            p.result?.let { named[it] = result }
            last = result
        }
        val out = (last?.image ?: FilterImage(width, height, false)).let { if (it === source) it.copy() else it }
        out.keepOnly(pixels(region))
        return out
    }

    private fun apply(p: FilterPrimitive, inputs: List<Result>, subregion: PixelRect): FilterImage {
        fun input(k: Int) = inputs[k].image.inSpace(p.linear)
        return when (p) {
            is FilterPrimitive.Blur ->
                if (p.sx < 0.0 || p.sy < 0.0 || (p.sx == 0.0 && p.sy == 0.0)) input(0).copy()
                else FilterOps.blur(input(0), p.sx * scaleX, p.sy * scaleY)
            is FilterPrimitive.Offset -> {
                val src = inputs[0].image
                FilterOps.offset(src, toPixels.a * p.dx + toPixels.c * p.dy, toPixels.b * p.dx + toPixels.d * p.dy)
            }
            is FilterPrimitive.Flood -> {
                val c = color(p.rgb, p.linear)
                FilterOps.flood(width, height, p.linear, c[0], c[1], c[2], p.opacity.toFloat().coerceIn(0f, 1f), subregion)
            }
            is FilterPrimitive.Merge -> {
                var out = FilterImage(width, height, p.linear)
                for (k in inputs.indices) out = FilterOps.over(input(k), out)
                out
            }
            is FilterPrimitive.Blend -> FilterOps.blend(input(0), input(1), p.mode)
            is FilterPrimitive.Composite -> FilterOps.composite(input(0), input(1), p.operator, p.k)
            is FilterPrimitive.ColorMatrix -> p.matrix?.let { FilterOps.colorMatrix(input(0), it) } ?: input(0).copy()
            is FilterPrimitive.ComponentTransfer -> FilterOps.componentTransfer(input(0), p.functions)
            is FilterPrimitive.DropShadow -> {
                val src = input(0)
                var shadow = alphaOf(src)
                if (p.sx > 0.0 || p.sy > 0.0) shadow = FilterOps.blur(shadow, maxOf(p.sx, 0.0) * scaleX, maxOf(p.sy, 0.0) * scaleY)
                shadow = FilterOps.offset(shadow, toPixels.a * p.dx + toPixels.c * p.dy, toPixels.b * p.dx + toPixels.d * p.dy)
                val c = color(p.rgb, p.linear)
                val flood = FilterOps.flood(width, height, p.linear, c[0], c[1], c[2], p.opacity.toFloat().coerceIn(0f, 1f), PixelRect(0, 0, width, height))
                FilterOps.over(src, FilterOps.composite(flood, shadow, "in", DoubleArray(4)))
            }
            is FilterPrimitive.Morphology -> {
                if (p.rx <= 0.0 || p.ry <= 0.0) {
                    input(0).copy()
                } else {
                    val rx = (p.rx * scaleX).roundToInt().coerceIn(0, MAX_MORPHOLOGY_RADIUS)
                    val ry = (p.ry * scaleY).roundToInt().coerceIn(0, MAX_MORPHOLOGY_RADIUS)
                    FilterOps.morphology(input(0), rx, ry, p.erode)
                }
            }
            is FilterPrimitive.Tile -> FilterOps.tile(inputs[0].image, pixels(inputs[0].box))
            is FilterPrimitive.Image -> {
                val box = doubleArrayOf(
                    toUserLeft(subregion), toUserTop(subregion), toUserRight(subregion), toUserBottom(subregion),
                )
                (images(p, box) ?: FilterImage(width, height, false)).inSpace(p.linear)
            }
            is FilterPrimitive.Turbulence -> turbulence(p, subregion)
            is FilterPrimitive.Convolve -> {
                val kernel = p.kernel
                if (kernel == null) input(0).copy()
                else FilterOps.convolve(input(0), p.orderX, p.orderY, kernel, p.divisor, p.bias, p.targetX, p.targetY, p.edgeMode, p.preserveAlpha)
            }
            is FilterPrimitive.Displacement ->
                FilterOps.displace(inputs[0].image, input(1), p.scale * scaleX, p.scale * scaleY, p.xChannel, p.yChannel)
            is FilterPrimitive.Lighting -> {
                val source = p.light ?: return FilterImage(width, height, p.linear)
                val light = when (source.kind) {
                    "fedistantlight" -> FilterLight.Distant(source.azimuth, source.elevation)
                    "fepointlight" -> Lighting.toPixels(toPixels, source.x, source.y, source.z).let { FilterLight.Point(it[0], it[1], it[2]) }
                    else -> {
                        val at = Lighting.toPixels(toPixels, source.x, source.y, source.z)
                        val to = Lighting.toPixels(toPixels, source.px, source.py, source.pz)
                        FilterLight.Spot(at[0], at[1], at[2], to[0], to[1], to[2], source.exponent, source.coneAngle)
                    }
                }
                Lighting.light(input(0), light, color(p.rgb, p.linear), p.surfaceScale, p.specular, p.constant, p.exponent)
            }
        }
    }

    // The subregion's corners back in user space, for a primitive that draws into it.
    private val fromPixels: KiteMatrix? by lazy { toPixels.invert() }
    private fun toUserLeft(r: PixelRect) = fromPixels?.transformX(r.x0.toDouble(), r.y0.toDouble()) ?: 0.0
    private fun toUserTop(r: PixelRect) = fromPixels?.transformY(r.x0.toDouble(), r.y0.toDouble()) ?: 0.0
    private fun toUserRight(r: PixelRect) = fromPixels?.transformX(r.x1.toDouble(), r.y1.toDouble()) ?: 0.0
    private fun toUserBottom(r: PixelRect) = fromPixels?.transformY(r.x1.toDouble(), r.y1.toDouble()) ?: 0.0

    private fun turbulence(p: FilterPrimitive.Turbulence, subregion: PixelRect): FilterImage {
        val out = FilterImage(width, height, p.linear)
        val inverse = fromPixels ?: return out
        val octaves = p.octaves.coerceIn(0, MAX_OCTAVES)
        // The tile that stitching makes seamless is the subregion in user space.
        val tile = if (p.stitch) doubleArrayOf(toUserLeft(subregion), toUserTop(subregion),
            toUserRight(subregion) - toUserLeft(subregion), toUserBottom(subregion) - toUserTop(subregion)) else null
        val field = Turbulence(p.seed).Field(p.baseX, p.baseY, octaves, p.fractal, tile)
        val noise = DoubleArray(4)
        for (y in subregion.y0 until subregion.y1) for (x in subregion.x0 until subregion.x1) {
            field.at(inverse.transformX(x + 0.5, y + 0.5), inverse.transformY(x + 0.5, y + 0.5), noise)
            val i = (y * width + x) * 4
            val a = noise[3].toFloat().coerceIn(0f, 1f)
            for (c in 0 until 3) out.data[i + c] = noise[c].toFloat().coerceIn(0f, 1f) * a
            out.data[i + 3] = a
        }
        return out
    }

    /** A straight sRGB colour in the space of a primitive. */
    private fun color(rgb: FloatArray, linear: Boolean): FloatArray =
        if (!linear) rgb else FloatArray(3) { FilterImage.toLinear(rgb[it]) }

    /** An image of the whole step in a flat paint, or transparent without one. */
    private fun paintImage(rgba: FloatArray?): FilterImage {
        if (rgba == null) return FilterImage(width, height, false)
        return FilterOps.flood(width, height, false, rgba[0], rgba[1], rgba[2], rgba[3], PixelRect(0, 0, width, height))
    }

    private fun alphaOf(image: FilterImage): FilterImage {
        val out = FilterImage(image.width, image.height, image.linear)
        for (i in 3 until image.data.size step 4) out.data[i] = image.data[i]
        return out
    }

    companion object {
        /** The widest erosion or dilation in pixels. */
        const val MAX_MORPHOLOGY_RADIUS = 1024

        /** The most octaves of turbulence: the tenth adds less than one level of 8-bit colour. */
        const val MAX_OCTAVES = 10

        /** The colour matrices of feColorMatrix (15.11) and of the filter functions (13). */
        fun saturate(s: Double): DoubleArray = doubleArrayOf(
            0.213 + 0.787 * s, 0.715 - 0.715 * s, 0.072 - 0.072 * s, 0.0, 0.0,
            0.213 - 0.213 * s, 0.715 + 0.285 * s, 0.072 - 0.072 * s, 0.0, 0.0,
            0.213 - 0.213 * s, 0.715 - 0.715 * s, 0.072 + 0.928 * s, 0.0, 0.0,
            0.0, 0.0, 0.0, 1.0, 0.0,
        )

        fun hueRotate(degrees: Double): DoubleArray {
            val r = degrees * kotlin.math.PI / 180
            val c = cos(r)
            val s = sin(r)
            return doubleArrayOf(
                0.213 + c * 0.787 - s * 0.213, 0.715 - c * 0.715 - s * 0.715, 0.072 - c * 0.072 + s * 0.928, 0.0, 0.0,
                0.213 - c * 0.213 + s * 0.143, 0.715 + c * 0.285 + s * 0.140, 0.072 - c * 0.072 - s * 0.283, 0.0, 0.0,
                0.213 - c * 0.213 - s * 0.787, 0.715 - c * 0.715 + s * 0.715, 0.072 + c * 0.928 + s * 0.072, 0.0, 0.0,
                0.0, 0.0, 0.0, 1.0, 0.0,
            )
        }

        val LUMINANCE_TO_ALPHA = doubleArrayOf(
            0.0, 0.0, 0.0, 0.0, 0.0,
            0.0, 0.0, 0.0, 0.0, 0.0,
            0.0, 0.0, 0.0, 0.0, 0.0,
            0.2125, 0.7154, 0.0721, 0.0, 0.0,
        )

        fun grayscale(amount: Double): DoubleArray {
            val a = 1 - amount.coerceIn(0.0, 1.0)
            return doubleArrayOf(
                0.2126 + 0.7874 * a, 0.7152 - 0.7152 * a, 0.0722 - 0.0722 * a, 0.0, 0.0,
                0.2126 - 0.2126 * a, 0.7152 + 0.2848 * a, 0.0722 - 0.0722 * a, 0.0, 0.0,
                0.2126 - 0.2126 * a, 0.7152 - 0.7152 * a, 0.0722 + 0.9278 * a, 0.0, 0.0,
                0.0, 0.0, 0.0, 1.0, 0.0,
            )
        }

        fun sepia(amount: Double): DoubleArray {
            val a = 1 - amount.coerceIn(0.0, 1.0)
            return doubleArrayOf(
                0.393 + 0.607 * a, 0.769 - 0.769 * a, 0.189 - 0.189 * a, 0.0, 0.0,
                0.349 - 0.349 * a, 0.686 + 0.314 * a, 0.168 - 0.168 * a, 0.0, 0.0,
                0.272 - 0.272 * a, 0.534 - 0.534 * a, 0.131 + 0.869 * a, 0.0, 0.0,
                0.0, 0.0, 0.0, 1.0, 0.0,
            )
        }

        /**
         * A transfer function of feComponentTransfer (15.13) of [type], or null for the
         * identity, which is also what a table without values gives.
         */
        fun transfer(type: String, values: DoubleArray, slope: Double, intercept: Double, amplitude: Double, exponent: Double, offset: Double): ((Float) -> Float)? =
            when (type) {
                "table" -> if (values.isEmpty()) null else { c ->
                    val n = values.size - 1
                    if (n == 0 || c >= 1f) values[n].toFloat() else {
                        val k = floor(c * n).toInt().coerceIn(0, n - 1)
                        (values[k] + (c - k.toDouble() / n) * n * (values[k + 1] - values[k])).toFloat()
                    }
                }
                "discrete" -> if (values.isEmpty()) null else { c ->
                    val n = values.size
                    values[floor(c * n).toInt().coerceIn(0, n - 1)].toFloat()
                }
                "linear" -> { c -> (slope * c + intercept).toFloat() }
                "gamma" -> { c -> (amplitude * c.toDouble().pow(exponent) + offset).toFloat() }
                else -> null
            }
    }
}
