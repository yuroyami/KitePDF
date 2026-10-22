package io.github.yuroyami.kitepdf.render

import io.github.yuroyami.kitepdf.core.KiteRectangle
import io.github.yuroyami.kitepdf.core.parser.PdfArray
import io.github.yuroyami.kitepdf.core.parser.PdfBoolean
import io.github.yuroyami.kitepdf.core.parser.PdfDictionary
import io.github.yuroyami.kitepdf.core.parser.PdfInt
import io.github.yuroyami.kitepdf.core.parser.PdfName
import io.github.yuroyami.kitepdf.core.parser.PdfObject
import io.github.yuroyami.kitepdf.core.parser.PdfReal
import io.github.yuroyami.kitepdf.core.parser.PdfStream
import io.github.yuroyami.kitepdf.core.render.GraphicsState
import io.github.yuroyami.kitepdf.core.render.KiteBlendMode
import io.github.yuroyami.kitepdf.core.render.KiteCanvas
import io.github.yuroyami.kitepdf.core.render.KiteColorSpace
import io.github.yuroyami.kitepdf.core.render.KiteFunction
import io.github.yuroyami.kitepdf.core.render.KiteImageData
import io.github.yuroyami.kitepdf.core.render.KiteMatrix
import io.github.yuroyami.kitepdf.core.render.KiteRaster
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Overprint for DeviceCMYK and named-colour paints (ISO 32000-1, 8.6.7, #201, #625). With overprint on and
 * overprint mode 1, an ink that the paint sets to zero stays as the backdrop has it, so cyan
 * over yellow prints green. MuPDF draws such a page in CMYK and converts it at the end. Here
 * each overprinting paint runs as a raster step: it reads the backdrop in sRGB, finds the inks
 * that the paint's own colour space turns into that colour, combines them with the paint's,
 * and converts the result through the same space.
 *
 * The inks of the backdrop are gone once it is sRGB, so they are found again. Four inks give
 * one colour in many ways. The search tries no black and the estimate's black, and keeps the
 * closer match. A backdrop whose real black differs from the one found comes out close, not exact.
 */
internal object Overprint {

    /** The colourants a paint sets, and the CMYK space used to simulate them on an RGB canvas. */
    class Paint(val space: KiteColorSpace, val components: DoubleArray, val named: NamedInks? = null) {
        val paintsNothing: Boolean get() = components.all { it == 0.0 } && (named == null || named.onlySpots)
        val replacesAll: Boolean get() = named == null && components.none { it == 0.0 }

        fun mix(into: DoubleArray) {
            if (named != null) named.mix(components, into)
            else for (k in 0 until 4) if (components[k] != 0.0) into[k] = components[k]
        }
    }

    /**
     * DeviceCMYK current colours protect zero inks in mode 1. Named colourants protect the
     * process inks they do not name in either mode (ISO 32000-1, 8.6.7, #625). A CMYK output
     * intent stands for DeviceCMYK; an unrelated ICC space does not acquire mode 1 semantics.
     */
    fun inks(s: GraphicsState, fill: Boolean, outputIntent: KiteColorSpace?): Paint? {
        if (!(if (fill) s.overprintFill else s.overprintStroke)) return null
        val space = if (fill) s.fillColorSpace else s.strokeColorSpace
        val components = if (fill) s.fillComponents else s.strokeComponents
        if (space is KiteColorSpace.DeviceN) {
            val named = namedInks(space, outputIntent?.withIntent(s.renderingIntent, s.blackPointCompensation)) ?: return null
            return Paint(named.space, components ?: DoubleArray(space.componentCount) { 1.0 }, named)
        }
        if (s.overprintMode != 1) return null
        val device = space == KiteColorSpace.DeviceCMYK ||
            (outputIntent != null && space === outputIntent.withIntent(s.renderingIntent, s.blackPointCompensation))
        if (!device) return null
        val values = components ?: doubleArrayOf(0.0, 0.0, 0.0, 1.0)
        if (values.size != 4) return null
        return Paint(space, DoubleArray(4) { values[it].coerceIn(0.0, 1.0) })
    }

    /**
     * Process names replace that ink, even at zero tint. A spot leaves the process plates
     * alone; its equivalent CMYK inks are added when the result is displayed, as MuPDF's
     * separation compositor does. RGB cannot retain a separate plate for a later paint of
     * the same spot, just as it cannot retain the backdrop's original black generation.
     */
    class NamedInks(val space: KiteColorSpace, private val process: IntArray, private val spots: Array<DoubleArray>) {
        val onlySpots: Boolean get() = process.all { it < 0 }

        fun mix(tints: DoubleArray, into: DoubleArray) {
            for (i in process.indices) {
                val k = process[i]
                if (k >= 0) into[k] = tints.getOrElse(i) { 0.0 }.coerceIn(0.0, 1.0)
            }
            for (i in spots.indices) {
                val tint = tints.getOrElse(i) { 0.0 }.coerceIn(0.0, 1.0)
                for (k in 0 until 4) into[k] = (into[k] + tint * spots[i][k]).coerceIn(0.0, 1.0)
            }
        }
    }

    private fun namedInks(source: KiteColorSpace.DeviceN, outputIntent: KiteColorSpace?): NamedInks? {
        // /All paints every plate normally, and /None never paints (8.6.6.4). Do not turn an
        // invalid DeviceN into an unbounded allocation while salvaging a damaged document.
        if (source.names.size != source.componentCount || source.componentCount !in 1..32 || "All" in source.names) return null
        val target = outputIntent ?: KiteColorSpace.DeviceCMYK
        val names = listOf("Cyan", "Magenta", "Yellow", "Black")
        val process = IntArray(source.componentCount) { names.indexOf(source.names[it]) }
        val solver = InkSolver(target)
        val spots = Array(source.componentCount) { i ->
            val result = DoubleArray(4)
            if (process[i] < 0 && source.names[i] != "None") {
                val solid = source.tintTransform.evaluate(DoubleArray(source.componentCount) { if (it == i) 1.0 else 0.0 })
                if (source.alternate === target || source.alternate === KiteColorSpace.DeviceCMYK) {
                    for (k in 0 until 4) result[k] = solid.getOrElse(k) { 0.0 }.coerceIn(0.0, 1.0)
                } else {
                    val rgb = source.alternate.toRgb(solid)
                    solver.inksOf((0xFF shl 24) or (level(rgb.r) shl 16) or (level(rgb.g) shl 8) or level(rgb.b), result)
                }
            }
            result
        }
        return NamedInks(target, process, spots)
    }

    /** Paints a flat colour through the original coverage, alpha, clipping and blend mode. */
    fun paint(
        canvas: KiteCanvas, region: KiteRectangle, ctm: KiteMatrix, ink: Paint,
        blendMode: KiteBlendMode, content: () -> Unit,
    ): Boolean = canvas.rasterStep(region, ctm) { scope ->
        val backdrop = scope.backdrop() ?: return@rasterStep false
        val alone = scope.render(null, content)
        val out = KiteRaster(scope.width, scope.height)
        val mixed = DoubleArray(4)
        val solver = InkSolver(ink.space)
        val converted = HashMap<Int, Int>()
        for (i in out.pixels.indices) {
            val coverage = alone.pixels[i] ushr 24
            if (coverage == 0) continue
            val under = backdrop.pixels[i]
            val rgb = converted[under] ?: run {
                solver.inksOf(under, mixed)
                ink.mix(mixed)
                val c = ink.space.toRgb(mixed)
                val value = (level(c.r) shl 16) or (level(c.g) shl 8) or level(c.b)
                if (converted.size >= MAX_CONVERTED) converted.clear()
                converted[under] = value
                value
            }
            out.pixels[i] = (coverage shl 24) or rgb
        }
        scope.draw(out, 1.0, blendMode)
        true
    }

    /**
     * Named-colour images overprint in either mode. Their tint samples, including /Decode
     * and interpolation, must survive: RGB alone cannot tell a process ink from a spot.
     * Render each tint as a grey RGB plane through the backend's own image sampler. The
     * normal image supplies coverage, including its colour key and soft mask.
     */
    fun image(canvas: KiteCanvas, image: KiteImageData, s: GraphicsState, outputIntent: KiteColorSpace?, content: () -> Unit): Boolean {
        if (!s.overprintFill || image.isImageMask || image.kind != KiteImageData.Kind.RAW) return false
        val source = image.resolvedColorSpace as? KiteColorSpace.DeviceN ?: return false
        val inks = namedInks(source, outputIntent?.withIntent(s.renderingIntent, s.blackPointCompensation)) ?: return false
        val bytes = image.pixelBytes ?: return false
        return canvas.rasterStep(KiteRectangle(0.0, 0.0, 1.0, 1.0), s.ctm) { scope ->
            val backdrop = scope.backdrop() ?: return@rasterStep false
            val alone = scope.render(null, content)
            val tints = Array(source.componentCount) { component ->
                val channelSpace = KiteColorSpace.DeviceN(source.componentCount, KiteColorSpace.DeviceRGB,
                    KiteFunction.Type4(DoubleArray(2 * source.componentCount) { (it % 2).toDouble() },
                        doubleArrayOf(0.0, 1.0, 0.0, 1.0, 0.0, 1.0),
                        listOf((source.componentCount - component - 1).toDouble(), "index", "dup", "dup")), source.names)
                val entries = linkedMapOf<String, PdfObject>(
                    "Width" to PdfInt(image.width.toLong()), "Height" to PdfInt(image.height.toLong()),
                    "BitsPerComponent" to PdfInt(image.bitsPerComponent.toLong()), "ColorSpace" to PdfName("DeviceN"),
                    "Interpolate" to PdfBoolean(image.interpolate),
                )
                image.decode?.let { entries["Decode"] = PdfArray(it.map(::PdfReal)) }
                val channel = KiteImageData.from(PdfStream(PdfDictionary(entries), bytes), null, null, channelSpace)
                val plane = scope.render(null) { canvas.drawImage(channel, s.ctm, 1.0) }
                ByteArray(plane.pixels.size) { ((plane.pixels[it] ushr 16) and 255).toByte() }
            }
            val solver = InkSolver(inks.space)
            val converted = HashMap<Int, DoubleArray>()
            val mixed = DoubleArray(4)
            val values = DoubleArray(source.componentCount)
            val out = KiteRaster(scope.width, scope.height)
            for (i in out.pixels.indices) {
                val coverage = alone.pixels[i] ushr 24
                if (coverage == 0) continue
                val under = backdrop.pixels[i]
                val base = converted[under] ?: DoubleArray(4).also {
                    solver.inksOf(under, it)
                    if (converted.size >= MAX_CONVERTED) converted.clear()
                    converted[under] = it
                }
                base.copyInto(mixed)
                for (k in values.indices) values[k] = (tints[k][i].toInt() and 255) / 255.0
                inks.mix(values, mixed)
                val c = inks.space.toRgb(mixed)
                out.pixels[i] = (coverage shl 24) or (level(c.r) shl 16) or (level(c.g) shl 8) or level(c.b)
            }
            scope.draw(out, 1.0, s.blendMode)
            true
        }
    }

    private fun level(v: Double): Int = (v.coerceIn(0.0, 1.0) * 255).roundToInt()

    /** Backdrop colours whose result is kept at once; an image under the paint starts the map again. */
    private const val MAX_CONVERTED = 4096

    /**
     * Finds the inks that [space] converts to a backdrop colour. It starts from the conversion
     * of ISO 32000-1, 10.3.5, with full undercolour removal, and refines cyan, magenta and yellow
     * by damped least-squares steps with black held: once at no black, once at the estimate's black. The closer
     * result wins.
     */
    private class InkSolver(private val space: KiteColorSpace) {

        /** Searches made so far. Past [MAX_SOLVES] the estimate stands, so a photo under a large paint stays quick. */
        private var solves = 0
        private val target = DoubleArray(3)
        private val noBlack = DoubleArray(4)
        private val probe = DoubleArray(4)
        private val residual = DoubleArray(3)
        private val trial = DoubleArray(3)
        private val scratch = DoubleArray(3)
        private val jacobian = Array(3) { DoubleArray(3) }
        private val normal = Array(3) { DoubleArray(3) }
        private val gradient = DoubleArray(3)

        /** The inks of the sRGB pixel [argb], into [into]. A pixel that is not opaque is first laid over paper white. */
        fun inksOf(argb: Int, into: DoubleArray) {
            val a = (argb ushr 24) / 255.0
            target[0] = ((argb ushr 16) and 0xFF) / 255.0 * a + (1 - a)
            target[1] = ((argb ushr 8) and 0xFF) / 255.0 * a + (1 - a)
            target[2] = (argb and 0xFF) / 255.0 * a + (1 - a)
            val k = 1 - maxOf(target[0], target[1], target[2])
            for (c in 0 until 3) into[c] = ((1 - target[c]) - k).coerceIn(0.0, 1.0)
            into[3] = k.coerceIn(0.0, 1.0)
            if (solves >= MAX_SOLVES) return
            solves++
            for (c in 0 until 3) noBlack[c] = (1 - target[c]).coerceIn(0.0, 1.0)
            noBlack[3] = 0.0
            val withBlack = refine(into)
            if (refine(noBlack) <= withBlack) noBlack.copyInto(into)
        }

        /** Refines the first three of [inks] in place, and returns the largest channel error left. */
        private fun refine(inks: DoubleArray): Double {
            var error = errorOf(inks, residual)
            repeat(ITERATIONS) {
                if (error < CLOSE_ENOUGH) return error
                for (j in 0 until 3) {
                    inks.copyInto(probe)
                    val h = if (probe[j] > 1 - STEP) -STEP else STEP
                    probe[j] += h
                    val c = space.toRgb(probe)
                    jacobian[0][j] = (c.r - target[0] - residual[0]) / h
                    jacobian[1][j] = (c.g - target[1] - residual[1]) / h
                    jacobian[2][j] = (c.b - target[2] - residual[2]) / h
                }
                // At a gamut boundary, one RGB channel can be clipped and its derivative
                // zero. Damped least squares still refines the other channels; inverting
                // the raw Jacobian would stop at a saturated cyan backdrop (#625).
                for (r in 0 until 3) {
                    gradient[r] = (0 until 3).sumOf { jacobian[it][r] * residual[it] }
                    for (c in 0 until 3) {
                        normal[r][c] = (0 until 3).sumOf { jacobian[it][r] * jacobian[it][c] } +
                            if (r == c) 1e-6 else 0.0
                    }
                }
                val det = det3(normal, -1)
                if (abs(det) < 1e-18) return error
                for (j in 0 until 3) trial[j] = det3(normal, j) / det
                // A step that does not help is halved, twice at most.
                var scale = 1.0
                var improved = false
                repeat(3) {
                    if (improved) return@repeat
                    inks.copyInto(probe)
                    for (j in 0 until 3) probe[j] = (inks[j] + scale * trial[j]).coerceIn(0.0, 1.0)
                    val next = errorOf(probe, scratch)
                    if (next < error) {
                        probe.copyInto(inks)
                        error = errorOf(inks, residual)
                        improved = true
                    } else {
                        scale /= 2
                    }
                }
                if (!improved) return error
            }
            return error
        }

        /** The colour of [inks] less the target, into [out], and the largest part of it. */
        private fun errorOf(inks: DoubleArray, out: DoubleArray): Double {
            val c = space.toRgb(inks)
            out[0] = c.r - target[0]
            out[1] = c.g - target[1]
            out[2] = c.b - target[2]
            return maxOf(abs(out[0]), abs(out[1]), abs(out[2]))
        }

        /** The determinant of [m], with its column [swap] replaced by the negated gradient, or of [m] itself when [swap] is -1. */
        private fun det3(m: Array<DoubleArray>, swap: Int): Double {
            fun at(r: Int, c: Int) = if (c == swap) -gradient[r] else m[r][c]
            return at(0, 0) * (at(1, 1) * at(2, 2) - at(1, 2) * at(2, 1)) -
                at(0, 1) * (at(1, 0) * at(2, 2) - at(1, 2) * at(2, 0)) +
                at(0, 2) * (at(1, 0) * at(2, 1) - at(1, 1) * at(2, 0))
        }

        private companion object {
            const val ITERATIONS = 12
            // DeviceCMYK rounds inks to bytes. A sub-byte difference can have no
            // derivative at all; probe several levels even for an ICC-backed space.
            const val STEP = 1.0 / 64
            /** Half a level of 0..255: the conversion cannot show a closer match. */
            const val CLOSE_ENOUGH = 0.5 / 255
            const val MAX_SOLVES = 20_000
        }
    }
}
