package io.github.yuroyami.kitepdf.render

import io.github.yuroyami.kitepdf.core.KiteRectangle
import io.github.yuroyami.kitepdf.core.render.GraphicsState
import io.github.yuroyami.kitepdf.core.render.KiteBlendMode
import io.github.yuroyami.kitepdf.core.render.KiteCanvas
import io.github.yuroyami.kitepdf.core.render.KiteColorSpace
import io.github.yuroyami.kitepdf.core.render.KiteMatrix
import io.github.yuroyami.kitepdf.core.render.KiteRaster
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Overprint for a DeviceCMYK paint (ISO 32000-1, 8.6.7, #201). With overprint on and
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

    /**
     * The inks of the fill or the stroke of [s], when it is a DeviceCMYK paint that overprints
     * under mode 1, or null. Mode 1 applies to the current colour in DeviceCMYK only, not to an
     * image or a shading (ISO 32000-1, 8.6.7). A document's CMYK [outputIntent] stands for
     * DeviceCMYK, and the inks are the device's, so mode 1 applies to it too, as in MuPDF. Any
     * other ICC space overprints as mode 0, which for four inks paints them all.
     */
    fun inks(s: GraphicsState, fill: Boolean, outputIntent: KiteColorSpace?): DoubleArray? {
        val on = if (fill) s.overprintFill else s.overprintStroke
        if (!on || s.overprintMode != 1) return null
        val space = if (fill) s.fillColorSpace else s.strokeColorSpace
        val device = space == KiteColorSpace.DeviceCMYK ||
            (outputIntent != null && space === outputIntent.withIntent(s.renderingIntent, s.blackPointCompensation))
        if (!device) return null
        // No components is the initial colour of the space, black ink only.
        val components = (if (fill) s.fillComponents else s.strokeComponents) ?: return doubleArrayOf(0.0, 0.0, 0.0, 1.0)
        if (components.size != 4) return null
        return DoubleArray(4) { components[it].coerceIn(0.0, 1.0) }
    }

    /**
     * Paints [content], the paint alone, over the backdrop of [region] under overprint: where
     * the paint covers a pixel, the pixel takes the paint's [inks], with its zero inks taken from
     * the backdrop, converted through [space], at the paint's own coverage. [region] is in the
     * space that [ctm] maps to the device. Returns false, having drawn nothing, when the canvas
     * cannot read its backdrop; the caller then paints as usual.
     */
    fun paint(
        canvas: KiteCanvas, region: KiteRectangle, ctm: KiteMatrix, space: KiteColorSpace, inks: DoubleArray,
        blendMode: KiteBlendMode, content: () -> Unit,
    ): Boolean = canvas.rasterStep(region, ctm) { scope ->
        val backdrop = scope.backdrop() ?: return@rasterStep false
        val alone = scope.render(null, content)
        val out = KiteRaster(scope.width, scope.height)
        val mixed = DoubleArray(4)
        val solver = InkSolver(space)
        // A flat backdrop converts once: the result of each backdrop colour is kept.
        val converted = HashMap<Int, Int>()
        for (i in out.pixels.indices) {
            val coverage = alone.pixels[i] ushr 24
            if (coverage == 0) continue
            val under = backdrop.pixels[i]
            val rgb = converted[under] ?: run {
                solver.inksOf(under, mixed)
                for (k in 0 until 4) if (inks[k] != 0.0) mixed[k] = inks[k]
                val c = space.toRgb(mixed)
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

    private fun level(v: Double): Int = (v.coerceIn(0.0, 1.0) * 255).roundToInt()

    /** Backdrop colours whose result is kept at once; an image under the paint starts the map again. */
    private const val MAX_CONVERTED = 4096

    /**
     * Finds the inks that [space] converts to a backdrop colour. It starts from the conversion
     * of ISO 32000-1, 10.3.5, with full undercolour removal, and refines cyan, magenta and yellow
     * by Newton steps with black held: once at no black, once at the estimate's black. The closer
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
                // The step that zeroes the residual, by Cramer's rule; a flat spot ends the search.
                val det = det3(jacobian, -1)
                if (abs(det) < 1e-12) return error
                for (j in 0 until 3) trial[j] = det3(jacobian, j) / det
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

        /** The determinant of [m], with its column [swap] replaced by the negated residual, or of [m] itself when [swap] is -1. */
        private fun det3(m: Array<DoubleArray>, swap: Int): Double {
            fun at(r: Int, c: Int) = if (c == swap) -residual[r] else m[r][c]
            return at(0, 0) * (at(1, 1) * at(2, 2) - at(1, 2) * at(2, 1)) -
                at(0, 1) * (at(1, 0) * at(2, 2) - at(1, 2) * at(2, 0)) +
                at(0, 2) * (at(1, 0) * at(2, 1) - at(1, 1) * at(2, 0))
        }

        private companion object {
            const val ITERATIONS = 12
            const val STEP = 1e-3
            /** Half a level of 0..255: the conversion cannot show a closer match. */
            const val CLOSE_ENOUGH = 0.5 / 255
            const val MAX_SOLVES = 20_000
        }
    }
}
