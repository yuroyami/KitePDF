package io.github.yuroyami.kitepdf.core.render

import kotlin.io.encoding.Base64
import kotlin.math.roundToInt

/**
 * DeviceCMYK to sRGB as PDFium converts it. The table approximates the Adobe conversion
 * from US Web Coated (SWOP) to sRGB, and it lands within a level or two of the ICC
 * conversion of MuPDF. ISO 32000-1, 8.6.4.4 leaves this conversion to the reader, so the
 * reference is what the two engines agree on (#299).
 *
 * [AdobeCmykTable] holds the colours of a grid of 9 points on each axis. A colour between
 * grid points starts from the nearest point and adds the change along each axis toward the
 * next point. This is PDFium's `AdobeCmykToStandardRgb`, fixed-point arithmetic included.
 */
internal object AdobeCmyk {

    private val table: ByteArray by lazy { Base64.decode(AdobeCmykTable.base64) }

    /** The sRGB colour of the 8-bit inks [c], [m], [y] and [k], packed as 0xRRGGBB. */
    fun rgb(c: Int, m: Int, y: Int, k: Int): Int {
        val t = table
        val fixC = c shl 8
        val fixM = m shl 8
        val fixY = y shl 8
        val fixK = k shl 8
        val ci = (fixC + 4096) shr 13
        val mi = (fixM + 4096) shr 13
        val yi = (fixY + 4096) shr 13
        val ki = (fixK + 4096) shr 13
        val start = index(ci, mi, yi, ki)
        // One step along each axis, from the nearest grid point toward its neighbour on that axis.
        val c1 = next(fixC, ci)
        val m1 = next(fixM, mi)
        val y1 = next(fixY, yi)
        val k1 = next(fixK, ki)
        val nc = index(c1, mi, yi, ki)
        val nm = index(ci, m1, yi, ki)
        val ny = index(ci, mi, y1, ki)
        val nk = index(ci, mi, yi, k1)
        val rc = (fixC - (ci shl 13)) * (ci - c1)
        val rm = (fixM - (mi shl 13)) * (mi - m1)
        val ry = (fixY - (yi shl 13)) * (yi - y1)
        val rk = (fixK - (ki shl 13)) * (ki - k1)
        var rgb = 0
        for (ch in 0..2) {
            val s0 = start + ch
            val fix = ((t[s0].toInt() and 0xFF) shl 8) +
                step(t, s0, nc + ch, rc) + step(t, s0, nm + ch, rm) + step(t, s0, ny + ch, ry) + step(t, s0, nk + ch, rk)
            rgb = (rgb shl 8) or channel(fix)
        }
        return rgb
    }

    /** [rgb] for inks from 0 to 1, each rounded to 8 bits first, as PDFium does. */
    fun toRgb(c: Double, m: Double, y: Double, k: Double): RgbColor {
        val p = rgb(byte(c), byte(m), byte(y), byte(k))
        return RgbColor(((p shr 16) and 0xFF) / 255.0, ((p shr 8) and 0xFF) / 255.0, (p and 0xFF) / 255.0)
    }

    /** [v] times 255, rounded half up: the `roundf` that PDFium matches in float arithmetic. */
    private fun byte(v: Double): Int = (v.coerceIn(0.0, 1.0) * 255.0).roundToInt()

    /** The grid point next to [i] on one axis: the one below the ink when [i] rounded up, else the one above. */
    private fun next(fix: Int, i: Int): Int {
        val lower = fix shr 13
        if (lower != i) return lower
        return if (lower == 8) lower - 1 else lower + 1
    }

    /** The change from the grid point at [from] toward the one at [to], at [rate], as PDFium rounds it. */
    private fun step(t: ByteArray, from: Int, to: Int, rate: Int): Int =
        ((t[from].toInt() and 0xFF) - (t[to].toInt() and 0xFF)) * rate / 32

    private fun channel(fix: Int): Int = (fix.coerceAtLeast(0) shr 8).coerceAtMost(255)

    private fun index(c: Int, m: Int, y: Int, k: Int): Int = (729 * c + 81 * m + 9 * y + k) * 3
}
