package io.github.yuroyami.kitepdf.render

import io.github.yuroyami.kitepdf.core.render.KiteRaster
import kotlin.math.roundToInt

/**
 * The pixel work of a non-isolated transparency group (ISO 32000-1, 11.4.8, #308). The paints
 * of such a group composite with its backdrop, and before the group composites with its own
 * alpha and blend mode, the backdrop's part comes out again:
 *
 *     αg × C = αn × Cn − α0 × (1 − αg) × C0
 *
 * where C0 and α0 are the backdrop, Cn and αn the group painted over a copy of it, and αg the
 * alpha of the group alone. That is the spec's C = Cn + (Cn − C0) × (α0 / αg − α0), multiplied
 * through by αg, which keeps it exact where αg is small. Every raster is straight ARGB.
 */
internal object GroupRasters {

    /** The colour and alpha of the group, with the backdrop's part taken out, from [backdrop], [over] and [alone]. */
    fun withoutBackdrop(backdrop: KiteRaster, over: KiteRaster, alone: KiteRaster): KiteRaster {
        val out = KiteRaster(backdrop.width, backdrop.height)
        val premultiplied = FloatArray(3)
        for (i in out.pixels.indices) {
            val ag = (alone.pixels[i] ushr 24) / 255f
            if (ag <= 0f) continue
            removeBackdrop(over.pixels[i], backdrop.pixels[i], ag, premultiplied)
            out.pixels[i] = straight(premultiplied, ag)
        }
        return out
    }

    /**
     * The premultiplied colour of one element of a group over the backdrop, with the backdrop's
     * part taken out: αR × CR − α0 × (1 − αs) × C0, from [over], the element over the backdrop,
     * [backdrop], and [alpha], the element's own alpha αs.
     */
    private fun removeBackdrop(over: Int, backdrop: Int, alpha: Float, into: FloatArray) {
        val an = (over ushr 24) / 255f
        val a0 = (backdrop ushr 24) / 255f
        for (c in 0 until 3) {
            val shift = 16 - 8 * c
            val cn = ((over ushr shift) and 0xFF) / 255f
            val c0 = ((backdrop ushr shift) and 0xFF) / 255f
            into[c] = an * cn - a0 * (1 - alpha) * c0
        }
    }

    /** A premultiplied colour over [alpha] as one straight ARGB pixel. */
    private fun straight(premultiplied: FloatArray, alpha: Float): Int {
        val a = alpha.coerceIn(0f, 1f)
        if (a <= 0f) return 0
        fun channel(c: Int) = (premultiplied[c] / a).coerceIn(0f, 1f).times(255f).roundToInt()
        return ((a * 255f).roundToInt() shl 24) or (channel(0) shl 16) or (channel(1) shl 8) or channel(2)
    }

    /**
     * A non-isolated knockout group, built one elementary object at a time (ISO 32000-1, 11.4.6
     * and 11.4.8). Each element composites with the group's initial backdrop, not with what the
     * elements before it left, and replaces them within its shape f:
     *
     *     G = (1 − f) × G + αR × CR − α0 × (1 − αs) × C0
     *     αg = (1 − f) × αg + αs
     *
     * G is the group's premultiplied colour with the backdrop's part taken out, so the group
     * then composites as any group does. Expanding the spec's own formulas, which keep the
     * backdrop in, gives these.
     */
    class Knockout(private val backdrop: KiteRaster) {
        private val color = FloatArray(backdrop.pixels.size * 3)
        private val alpha = FloatArray(backdrop.pixels.size)

        /**
         * Adds one element: [over] is the element painted over a copy of the backdrop, [shape]
         * the element at full alpha in Normal, whose alpha is its shape, and [alone] the
         * element on its own, whose alpha is αs.
         */
        fun add(over: KiteRaster, shape: KiteRaster, alone: KiteRaster) {
            val premultiplied = FloatArray(3)
            for (i in alpha.indices) {
                val f = (shape.pixels[i] ushr 24) / 255f
                if (f <= 0f) continue
                val a = (alone.pixels[i] ushr 24) / 255f
                removeBackdrop(over.pixels[i], backdrop.pixels[i], a, premultiplied)
                for (c in 0 until 3) color[3 * i + c] = (1 - f) * color[3 * i + c] + premultiplied[c]
                alpha[i] = (1 - f) * alpha[i] + a
            }
        }

        /** The group so far, straight, ready to composite onto the backdrop. */
        fun result(): KiteRaster {
            val out = KiteRaster(backdrop.width, backdrop.height)
            val premultiplied = FloatArray(3)
            for (i in alpha.indices) {
                if (alpha[i] <= 0f) continue
                for (c in 0 until 3) premultiplied[c] = color[3 * i + c]
                out.pixels[i] = straight(premultiplied, alpha[i])
            }
            return out
        }
    }
}
