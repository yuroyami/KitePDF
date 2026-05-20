package com.yuroyami.kitepdf.nativerenderer

import com.yuroyami.kitepdf.render.BlendMode
import java.awt.Composite
import java.awt.CompositeContext
import java.awt.RenderingHints
import java.awt.image.ColorModel
import java.awt.image.Raster
import java.awt.image.WritableRaster

/**
 * Pixel-level Java2D [Composite] implementing PDF's 16 blend modes
 * (ISO 32000-1 §11.3.5). Java2D's built-in `AlphaComposite` only covers
 * Porter-Duff alpha-compositing; the separable PDF blend modes (Multiply,
 * Screen, Overlay, …) and non-separable ones (Hue, Saturation, Color,
 * Luminosity) require this manual implementation.
 *
 * Each blend mode applies its formula per-RGB channel, then SrcOver-blends
 * with the supplied [alpha]. Non-separable modes operate in the HSL
 * colour space; we follow the spec formulas literally (§11.3.5.3).
 */
internal class PdfBlendComposite(
    private val mode: BlendMode,
    private val alpha: Float,
) : Composite {

    override fun createContext(
        srcColorModel: ColorModel,
        dstColorModel: ColorModel,
        hints: RenderingHints?,
    ): CompositeContext = Context(mode, alpha)

    private class Context(
        private val mode: BlendMode,
        private val alpha: Float,
    ) : CompositeContext {

        override fun compose(src: Raster, dstIn: Raster, dstOut: WritableRaster) {
            val width = minOf(src.width, dstIn.width, dstOut.width)
            val height = minOf(src.height, dstIn.height, dstOut.height)

            val srcPx = IntArray(4)
            val dstPx = IntArray(4)
            val out = IntArray(4)

            for (y in 0 until height) {
                for (x in 0 until width) {
                    src.getPixel(x, y, srcPx)
                    dstIn.getPixel(x, y, dstPx)

                    // Premultiply by alpha and normalise to [0..1].
                    val srcA = (srcPx[3] / 255f) * alpha
                    val sr = srcPx[0] / 255f
                    val sg = srcPx[1] / 255f
                    val sb = srcPx[2] / 255f
                    val dstA = dstPx[3] / 255f
                    val dr = dstPx[0] / 255f
                    val dg = dstPx[1] / 255f
                    val db = dstPx[2] / 255f

                    val (br, bg, bb) = blend(sr, sg, sb, dr, dg, db, mode)

                    // PDF spec §11.3.6: result = (1 - srcA) * dst + srcA * blend(src, dst)
                    val outR = (1 - srcA) * dr + srcA * br
                    val outG = (1 - srcA) * dg + srcA * bg
                    val outB = (1 - srcA) * db + srcA * bb
                    val outA = srcA + dstA * (1 - srcA)

                    out[0] = (outR * 255).toInt().coerceIn(0, 255)
                    out[1] = (outG * 255).toInt().coerceIn(0, 255)
                    out[2] = (outB * 255).toInt().coerceIn(0, 255)
                    out[3] = (outA * 255).toInt().coerceIn(0, 255)
                    dstOut.setPixel(x, y, out)
                }
            }
        }

        override fun dispose() {}

        private fun blend(
            sr: Float, sg: Float, sb: Float,
            dr: Float, dg: Float, db: Float,
            mode: BlendMode,
        ): Triple<Float, Float, Float> = when (mode) {
            BlendMode.Normal -> Triple(sr, sg, sb)
            BlendMode.Multiply -> Triple(sr * dr, sg * dg, sb * db)
            BlendMode.Screen -> Triple(
                1 - (1 - sr) * (1 - dr),
                1 - (1 - sg) * (1 - dg),
                1 - (1 - sb) * (1 - db),
            )
            BlendMode.Overlay -> Triple(overlay(dr, sr), overlay(dg, sg), overlay(db, sb))
            BlendMode.Darken -> Triple(minOf(sr, dr), minOf(sg, dg), minOf(sb, db))
            BlendMode.Lighten -> Triple(maxOf(sr, dr), maxOf(sg, dg), maxOf(sb, db))
            BlendMode.ColorDodge -> Triple(colorDodge(dr, sr), colorDodge(dg, sg), colorDodge(db, sb))
            BlendMode.ColorBurn -> Triple(colorBurn(dr, sr), colorBurn(dg, sg), colorBurn(db, sb))
            BlendMode.HardLight -> Triple(overlay(sr, dr), overlay(sg, dg), overlay(sb, db))
            BlendMode.SoftLight -> Triple(softLight(dr, sr), softLight(dg, sg), softLight(db, sb))
            BlendMode.Difference -> Triple(
                kotlin.math.abs(dr - sr), kotlin.math.abs(dg - sg), kotlin.math.abs(db - sb),
            )
            BlendMode.Exclusion -> Triple(
                dr + sr - 2 * dr * sr, dg + sg - 2 * dg * sg, db + sb - 2 * db * sb,
            )
            // Non-separable modes (Hue/Saturation/Color/Luminosity) — operate
            // on HSL triples. Implemented per ISO 32000-1 §11.3.5.3.
            BlendMode.Hue -> setLum(setSat(Triple(sr, sg, sb), sat(dr, dg, db)), lum(dr, dg, db))
            BlendMode.Saturation -> setLum(setSat(Triple(dr, dg, db), sat(sr, sg, sb)), lum(dr, dg, db))
            BlendMode.Color -> setLum(Triple(sr, sg, sb), lum(dr, dg, db))
            BlendMode.Luminosity -> setLum(Triple(dr, dg, db), lum(sr, sg, sb))
        }

        /* ─── Separable blend helpers ─────────────────────────────────────── */

        private fun overlay(b: Float, s: Float): Float =
            if (b < 0.5f) 2 * b * s else 1 - 2 * (1 - b) * (1 - s)

        private fun colorDodge(b: Float, s: Float): Float = when {
            b == 0f -> 0f
            s == 1f -> 1f
            else -> minOf(1f, b / (1 - s))
        }

        private fun colorBurn(b: Float, s: Float): Float = when {
            b == 1f -> 1f
            s == 0f -> 0f
            else -> 1 - minOf(1f, (1 - b) / s)
        }

        private fun softLight(b: Float, s: Float): Float {
            val d = if (b <= 0.25f) ((16 * b - 12) * b + 4) * b else kotlin.math.sqrt(b)
            return if (s <= 0.5f) b - (1 - 2 * s) * b * (1 - b) else b + (2 * s - 1) * (d - b)
        }

        /* ─── Non-separable HSL helpers ───────────────────────────────────── */

        private fun lum(r: Float, g: Float, b: Float): Float = 0.3f * r + 0.59f * g + 0.11f * b
        private fun sat(r: Float, g: Float, b: Float): Float = maxOf(r, g, b) - minOf(r, g, b)

        private fun clipColor(t: Triple<Float, Float, Float>): Triple<Float, Float, Float> {
            val (r, g, b) = t
            val l = lum(r, g, b)
            val n = minOf(r, minOf(g, b))
            val x = maxOf(r, maxOf(g, b))
            return when {
                n < 0 -> Triple(
                    l + (r - l) * l / (l - n),
                    l + (g - l) * l / (l - n),
                    l + (b - l) * l / (l - n),
                )
                x > 1 -> Triple(
                    l + (r - l) * (1 - l) / (x - l),
                    l + (g - l) * (1 - l) / (x - l),
                    l + (b - l) * (1 - l) / (x - l),
                )
                else -> t
            }
        }

        private fun setLum(c: Triple<Float, Float, Float>, l: Float): Triple<Float, Float, Float> {
            val d = l - lum(c.first, c.second, c.third)
            return clipColor(Triple(c.first + d, c.second + d, c.third + d))
        }

        private fun setSat(c: Triple<Float, Float, Float>, s: Float): Triple<Float, Float, Float> {
            // Per spec: rewrite the (min, mid, max) channels into (0, s*(mid-min)/(max-min), s).
            val a = floatArrayOf(c.first, c.second, c.third)
            val sortedIdx = (0..2).sortedBy { a[it] }
            val mn = sortedIdx[0]; val md = sortedIdx[1]; val mx = sortedIdx[2]
            val cMax = a[mx]; val cMin = a[mn]
            if (cMax > cMin) {
                a[md] = ((a[md] - cMin) * s) / (cMax - cMin)
                a[mx] = s
            } else {
                a[md] = 0f
                a[mx] = 0f
            }
            a[mn] = 0f
            return Triple(a[0], a[1], a[2])
        }
    }
}
