package io.github.yuroyami.kitepdf.nativerenderer

import io.github.yuroyami.kitepdf.core.render.BlendMode
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
            // Reused per-pixel blend-result buffer — avoids allocating a Triple
            // (plus boxed Floats) on every pixel of the compositing loop.
            val blendRgb = FloatArray(3)

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

                    blendInto(sr, sg, sb, dr, dg, db, mode, blendRgb)
                    val br = blendRgb[0]; val bg = blendRgb[1]; val bb = blendRgb[2]

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

        /** Write blend(src, dst) for [mode] into [o] (size 3) — no allocation. */
        private fun blendInto(
            sr: Float, sg: Float, sb: Float,
            dr: Float, dg: Float, db: Float,
            mode: BlendMode,
            o: FloatArray,
        ) {
            when (mode) {
                BlendMode.Normal -> { o[0] = sr; o[1] = sg; o[2] = sb }
                BlendMode.Multiply -> { o[0] = sr * dr; o[1] = sg * dg; o[2] = sb * db }
                BlendMode.Screen -> {
                    o[0] = 1 - (1 - sr) * (1 - dr)
                    o[1] = 1 - (1 - sg) * (1 - dg)
                    o[2] = 1 - (1 - sb) * (1 - db)
                }
                BlendMode.Overlay -> { o[0] = overlay(dr, sr); o[1] = overlay(dg, sg); o[2] = overlay(db, sb) }
                BlendMode.Darken -> { o[0] = minOf(sr, dr); o[1] = minOf(sg, dg); o[2] = minOf(sb, db) }
                BlendMode.Lighten -> { o[0] = maxOf(sr, dr); o[1] = maxOf(sg, dg); o[2] = maxOf(sb, db) }
                BlendMode.ColorDodge -> { o[0] = colorDodge(dr, sr); o[1] = colorDodge(dg, sg); o[2] = colorDodge(db, sb) }
                BlendMode.ColorBurn -> { o[0] = colorBurn(dr, sr); o[1] = colorBurn(dg, sg); o[2] = colorBurn(db, sb) }
                BlendMode.HardLight -> { o[0] = overlay(sr, dr); o[1] = overlay(sg, dg); o[2] = overlay(sb, db) }
                BlendMode.SoftLight -> { o[0] = softLight(dr, sr); o[1] = softLight(dg, sg); o[2] = softLight(db, sb) }
                BlendMode.Difference -> {
                    o[0] = kotlin.math.abs(dr - sr); o[1] = kotlin.math.abs(dg - sg); o[2] = kotlin.math.abs(db - sb)
                }
                BlendMode.Exclusion -> {
                    o[0] = dr + sr - 2 * dr * sr; o[1] = dg + sg - 2 * dg * sg; o[2] = db + sb - 2 * db * sb
                }
                // Non-separable modes (Hue/Saturation/Color/Luminosity) — operate
                // on HSL triples in place. Implemented per ISO 32000-1 §11.3.5.3.
                BlendMode.Hue -> {
                    o[0] = sr; o[1] = sg; o[2] = sb
                    setSatInPlace(o, sat(dr, dg, db)); setLumInPlace(o, lum(dr, dg, db))
                }
                BlendMode.Saturation -> {
                    o[0] = dr; o[1] = dg; o[2] = db
                    setSatInPlace(o, sat(sr, sg, sb)); setLumInPlace(o, lum(dr, dg, db))
                }
                BlendMode.Color -> { o[0] = sr; o[1] = sg; o[2] = sb; setLumInPlace(o, lum(dr, dg, db)) }
                BlendMode.Luminosity -> { o[0] = dr; o[1] = dg; o[2] = db; setLumInPlace(o, lum(sr, sg, sb)) }
            }
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

        /** Clip [c] (size 3) back into gamut in place (ISO 32000-1 §11.3.5.3). */
        private fun clipColorInPlace(c: FloatArray) {
            val r = c[0]; val g = c[1]; val b = c[2]
            val l = lum(r, g, b)
            val n = minOf(r, minOf(g, b))
            val x = maxOf(r, maxOf(g, b))
            when {
                n < 0 -> {
                    val k = l / (l - n)
                    c[0] = l + (r - l) * k
                    c[1] = l + (g - l) * k
                    c[2] = l + (b - l) * k
                }
                x > 1 -> {
                    val k = (1 - l) / (x - l)
                    c[0] = l + (r - l) * k
                    c[1] = l + (g - l) * k
                    c[2] = l + (b - l) * k
                }
            }
        }

        private fun setLumInPlace(c: FloatArray, l: Float) {
            val d = l - lum(c[0], c[1], c[2])
            c[0] += d; c[1] += d; c[2] += d
            clipColorInPlace(c)
        }

        private fun setSatInPlace(c: FloatArray, s: Float) {
            // Per spec: rewrite the (min, mid, max) channels into (0, s*(mid-min)/(max-min), s).
            // Find min/mid/max indices via a 3-element sorting network (no allocation).
            var mn = 0; var md = 1; var mx = 2
            if (c[mn] > c[md]) { val t = mn; mn = md; md = t }
            if (c[md] > c[mx]) { val t = md; md = mx; mx = t }
            if (c[mn] > c[md]) { val t = mn; mn = md; md = t }
            val cMax = c[mx]; val cMin = c[mn]
            if (cMax > cMin) {
                c[md] = ((c[md] - cMin) * s) / (cMax - cMin)
                c[mx] = s
            } else {
                c[md] = 0f
                c[mx] = 0f
            }
            c[mn] = 0f
        }
    }
}
