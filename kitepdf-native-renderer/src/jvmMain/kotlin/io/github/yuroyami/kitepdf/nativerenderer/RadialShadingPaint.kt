package io.github.yuroyami.kitepdf.nativerenderer

import io.github.yuroyami.kitepdf.core.render.GradientStops
import java.awt.Paint
import java.awt.PaintContext
import java.awt.Rectangle
import java.awt.RenderingHints
import java.awt.Transparency
import java.awt.geom.AffineTransform
import java.awt.geom.NoninvertibleTransformException
import java.awt.geom.Rectangle2D
import java.awt.image.ColorModel
import java.awt.image.Raster
import kotlin.math.sqrt

/**
 * A radial shading between two circles (ISO 32000-1, 8.7.4.5.4) as an AWT paint.
 *
 * [coords] are `x0 y0 r0 x1 y1 r1` in shading space, and [toDevice] maps shading
 * space to user space. Each pixel takes the colour of the largest s whose circle
 * passes through it, among the s that [extendStart] and [extendEnd] allow and
 * whose radius is not negative. A pixel on no such circle stays transparent.
 * AWT has no gradient between two circles of its own.
 */
internal class RadialShadingPaint(
    private val coords: DoubleArray,
    private val extendStart: Boolean,
    private val extendEnd: Boolean,
    stops: GradientStops,
    private val toDevice: AffineTransform,
) : Paint {

    /** Opaque ARGB colours for s from 0 to 1, one per stop. */
    private val ramp = IntArray(stops.colors.size) { i ->
        val c = stops.colors[i]
        fun ch(v: Double) = (v.coerceIn(0.0, 1.0) * 255 + 0.5).toInt()
        (0xFF shl 24) or (ch(c.r) shl 16) or (ch(c.g) shl 8) or ch(c.b)
    }

    private val x0 = coords[0]
    private val y0 = coords[1]
    private val r0 = coords[2]
    private val dx = coords[3] - coords[0]
    private val dy = coords[4] - coords[1]
    private val dr = coords[5] - coords[2]
    private val a = dx * dx + dy * dy - dr * dr

    override fun getTransparency(): Int = Transparency.TRANSLUCENT

    override fun createContext(
        cm: ColorModel?, deviceBounds: Rectangle?, userBounds: Rectangle2D?,
        xform: AffineTransform, hints: RenderingHints?,
    ): PaintContext {
        val toShading = try {
            AffineTransform(xform).apply { concatenate(toDevice) }.createInverse()
        } catch (_: NoninvertibleTransformException) {
            null
        }
        return Context(toShading)
    }

    private fun allowed(s: Double): Boolean =
        s.isFinite() && r0 + s * dr >= 0.0 && (s >= 0.0 || extendStart) && (s <= 1.0 || extendEnd)

    /**
     * The shading parameter s at shading-space point ([px], [py]), or NaN where
     * nothing paints. The point lies on circle s when
     * `a s^2 - 2 b s + c = 0`, and the larger allowed root wins.
     */
    private fun parameterAt(px: Double, py: Double): Double {
        val qx = px - x0
        val qy = py - y0
        val b = qx * dx + qy * dy + r0 * dr
        val c = qx * qx + qy * qy - r0 * r0
        if (a == 0.0) {
            if (b == 0.0) return Double.NaN
            val s = c / (2 * b)
            return if (allowed(s)) s else Double.NaN
        }
        val disc = b * b - a * c
        if (disc < 0.0) return Double.NaN
        // The two roots in a form that stays exact when a is small.
        val q = if (b >= 0.0) b + sqrt(disc) else b - sqrt(disc)
        val s1 = q / a
        val s2 = if (q != 0.0) c / q else s1
        val hi = maxOf(s1, s2)
        val lo = minOf(s1, s2)
        return when {
            allowed(hi) -> hi
            allowed(lo) -> lo
            else -> Double.NaN
        }
    }

    /** The colour at [s], clamped to the ramp and interpolated between its stops. */
    private fun colorAt(s: Double): Int {
        val pos = s.coerceIn(0.0, 1.0) * (ramp.size - 1)
        val i = pos.toInt().coerceAtMost(ramp.size - 2)
        val f = pos - i
        val p = ramp[i]
        val n = ramp[i + 1]
        fun mix(shift: Int): Int {
            val u = (p ushr shift) and 0xFF
            val v = (n ushr shift) and 0xFF
            return ((u + (v - u) * f) + 0.5).toInt() shl shift
        }
        return (0xFF shl 24) or mix(16) or mix(8) or mix(0)
    }

    private inner class Context(private val toShading: AffineTransform?) : PaintContext {
        private val model = ColorModel.getRGBdefault()

        override fun dispose() {}

        override fun getColorModel(): ColorModel = model

        override fun getRaster(x: Int, y: Int, w: Int, h: Int): Raster {
            val raster = model.createCompatibleWritableRaster(w, h)
            val m = toShading ?: return raster
            val pixels = IntArray(w * h)
            // Sample each pixel at its centre.
            for (j in 0 until h) {
                val uy = y + j + 0.5
                for (i in 0 until w) {
                    val ux = x + i + 0.5
                    val s = parameterAt(
                        m.scaleX * ux + m.shearX * uy + m.translateX,
                        m.shearY * ux + m.scaleY * uy + m.translateY,
                    )
                    if (!s.isNaN()) pixels[j * w + i] = colorAt(s)
                }
            }
            raster.setDataElements(0, 0, w, h, pixels)
            return raster
        }
    }
}
