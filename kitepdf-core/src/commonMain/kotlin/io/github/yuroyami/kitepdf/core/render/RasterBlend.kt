package io.github.yuroyami.kitepdf.core.render

import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * The blend modes of ISO 32000-1, 11.3.5, on premultiplied colours from 0 to 1, for
 * [KiteRasterCanvas]. Compositing and Blending 1 gives the same formulas for CSS.
 */
internal object RasterBlend {

    /**
     * Composites the premultiplied source ([sr], [sg], [sb], [sa]) onto the premultiplied pixel
     * [dst] in [mode], and answers the new pixel, premultiplied ARGB.
     */
    fun over(dst: Int, sr: Float, sg: Float, sb: Float, sa: Float, mode: KiteBlendMode): Int {
        val da = (dst ushr 24) / 255f
        val dr = (dst ushr 16 and 255) / 255f
        val dg = (dst ushr 8 and 255) / 255f
        val db = (dst and 255) / 255f
        if (mode == KiteBlendMode.Normal || da == 0f) {
            val k = 1f - sa
            return pack(sr + dr * k, sg + dg * k, sb + db * k, sa + da * k)
        }
        if (sa == 0f) return dst
        // Each side unpremultiplied, for the blend function.
        val cr = sr / sa; val cg = sg / sa; val cb = sb / sa
        val br = dr / da; val bg = dg / da; val bb = db / da
        val mixed = FloatArray(3)
        blend(mode, br, bg, bb, cr, cg, cb, mixed)
        val both = sa * da
        val r = (1 - da) * sr + (1 - sa) * dr + both * mixed[0]
        val g = (1 - da) * sg + (1 - sa) * dg + both * mixed[1]
        val b = (1 - da) * sb + (1 - sa) * db + both * mixed[2]
        return pack(r, g, b, sa + da - both)
    }

    /** B(backdrop, source) for each channel into [out], all unpremultiplied. */
    fun blend(mode: KiteBlendMode, br: Float, bg: Float, bb: Float, sr: Float, sg: Float, sb: Float, out: FloatArray) {
        when (mode) {
            KiteBlendMode.Hue -> { setLum(setSat(sr, sg, sb, sat(br, bg, bb), out), lum(br, bg, bb), out); return }
            KiteBlendMode.Saturation -> { setSat(br, bg, bb, sat(sr, sg, sb), out); setLum(out, lum(br, bg, bb), out); return }
            KiteBlendMode.Color -> { out[0] = sr; out[1] = sg; out[2] = sb; setLum(out, lum(br, bg, bb), out); return }
            KiteBlendMode.Luminosity -> { out[0] = br; out[1] = bg; out[2] = bb; setLum(out, lum(sr, sg, sb), out); return }
            else -> {
                out[0] = separable(mode, br, sr)
                out[1] = separable(mode, bg, sg)
                out[2] = separable(mode, bb, sb)
            }
        }
    }

    private fun separable(mode: KiteBlendMode, b: Float, s: Float): Float = when (mode) {
        KiteBlendMode.Multiply -> b * s
        KiteBlendMode.Screen -> b + s - b * s
        KiteBlendMode.Overlay -> hardLight(s, b)
        KiteBlendMode.Darken -> min(b, s)
        KiteBlendMode.Lighten -> max(b, s)
        KiteBlendMode.ColorDodge -> when {
            b == 0f -> 0f
            s >= 1f -> 1f
            else -> min(1f, b / (1 - s))
        }
        KiteBlendMode.ColorBurn -> when {
            b >= 1f -> 1f
            s <= 0f -> 0f
            else -> 1 - min(1f, (1 - b) / s)
        }
        KiteBlendMode.HardLight -> hardLight(b, s)
        KiteBlendMode.SoftLight -> if (s <= 0.5f) {
            b - (1 - 2 * s) * b * (1 - b)
        } else {
            val d = if (b <= 0.25f) ((16 * b - 12) * b + 4) * b else sqrt(b)
            b + (2 * s - 1) * (d - b)
        }
        KiteBlendMode.Difference -> kotlin.math.abs(b - s)
        KiteBlendMode.Exclusion -> b + s - 2 * b * s
        else -> s
    }

    private fun hardLight(b: Float, s: Float): Float =
        if (s <= 0.5f) b * 2 * s else { val t = 2 * s - 1; b + t - b * t }

    private fun lum(r: Float, g: Float, b: Float): Float = 0.3f * r + 0.59f * g + 0.11f * b

    private fun sat(r: Float, g: Float, b: Float): Float = max(r, max(g, b)) - min(r, min(g, b))

    /** SetLum of ISO 32000-1, 11.3.5.3: ([c]) moved to luminosity [l], clipped into range, into [out]. */
    private fun setLum(c: FloatArray, l: Float, out: FloatArray) {
        val d = l - lum(c[0], c[1], c[2])
        var r = c[0] + d; var g = c[1] + d; var b = c[2] + d
        val lu = lum(r, g, b)
        val n = min(r, min(g, b))
        val x = max(r, max(g, b))
        if (n < 0f) {
            r = lu + (r - lu) * lu / (lu - n); g = lu + (g - lu) * lu / (lu - n); b = lu + (b - lu) * lu / (lu - n)
        }
        if (x > 1f) {
            r = lu + (r - lu) * (1 - lu) / (x - lu); g = lu + (g - lu) * (1 - lu) / (x - lu); b = lu + (b - lu) * (1 - lu) / (x - lu)
        }
        out[0] = r; out[1] = g; out[2] = b
    }

    /** SetSat of ISO 32000-1, 11.3.5.3: ([r], [g], [b]) with saturation [s], into [out], which it also answers. */
    private fun setSat(r: Float, g: Float, b: Float, s: Float, out: FloatArray): FloatArray {
        val x = max(r, max(g, b))
        val n = min(r, min(g, b))
        fun scale(c: Float): Float = when {
            x == n -> 0f
            c == x -> s
            c == n -> 0f
            else -> (c - n) * s / (x - n)
        }
        out[0] = scale(r); out[1] = scale(g); out[2] = scale(b)
        return out
    }

    /** A premultiplied pixel from channels from 0 to 1, each rounded to a byte and kept under the alpha. */
    fun pack(r: Float, g: Float, b: Float, a: Float): Int {
        val ai = (a.coerceIn(0f, 1f) * 255f).roundToInt()
        val ri = (r * 255f).roundToInt().coerceIn(0, ai)
        val gi = (g * 255f).roundToInt().coerceIn(0, ai)
        val bi = (b * 255f).roundToInt().coerceIn(0, ai)
        return (ai shl 24) or (ri shl 16) or (gi shl 8) or bi
    }

    /** A straight ARGB pixel as premultiplied, each channel rounded. */
    fun premultiply(argb: Int): Int {
        val a = argb ushr 24
        if (a == 255) return argb
        if (a == 0) return 0
        val r = ((argb ushr 16 and 255) * a + 127) / 255
        val g = ((argb ushr 8 and 255) * a + 127) / 255
        val b = ((argb and 255) * a + 127) / 255
        return (a shl 24) or (r shl 16) or (g shl 8) or b
    }

    /** A premultiplied pixel as straight ARGB, each channel rounded. */
    fun unpremultiply(p: Int): Int {
        val a = p ushr 24
        if (a == 255 || a == 0) return if (a == 0) 0 else p
        val r = min(255, ((p ushr 16 and 255) * 255 + a / 2) / a)
        val g = min(255, ((p ushr 8 and 255) * 255 + a / 2) / a)
        val b = min(255, ((p and 255) * 255 + a / 2) / a)
        return (a shl 24) or (r shl 16) or (g shl 8) or b
    }
}
