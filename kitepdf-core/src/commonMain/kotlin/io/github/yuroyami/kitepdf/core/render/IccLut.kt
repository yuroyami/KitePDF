package io.github.yuroyami.kitepdf.core.render

import kotlin.math.pow

/**
 * One lookup-table transform of an ICC profile (ICC.1:2010, 10.8, 10.9, 10.10 and 10.12):
 * the `lut8Type` (`mft1`), `lut16Type` (`mft2`), `lutAtoBType` (`mAB `) and `lutBtoAType`
 * (`mBA `) tags. Values run through it normalized to 0..1, in the order of [stages].
 *
 * The grid interpolates as Little CMS does, which MuPDF and PDFium both use: linear for one
 * input, bilinear for two, tetrahedral for three, and for more inputs linear along the first
 * between two evaluations of the rest (#200).
 */
internal class IccLut(
    val inputs: Int,
    val outputs: Int,
    private val stages: List<Stage>,
    /** How the connection-space side of the table encodes its values. */
    val pcsEncoding: PcsEncoding,
) {

    /** One step of a table: curves per channel, a matrix with offsets, or a grid. */
    sealed class Stage {
        abstract fun apply(v: DoubleArray): DoubleArray

        class Curves(private val curves: List<IccCurve>) : Stage() {
            override fun apply(v: DoubleArray): DoubleArray = DoubleArray(v.size) { i -> curves.getOrNull(i)?.eval(v[i]) ?: v[i] }
        }

        /** 3 by 3, row-major, then three offsets, all in the normalized domain. */
        class Matrix(private val m: DoubleArray) : Stage() {
            override fun apply(v: DoubleArray): DoubleArray = DoubleArray(3) { r ->
                (m[3 * r] * v[0] + m[3 * r + 1] * v[1] + m[3 * r + 2] * v[2] + m[9 + r]).coerceIn(0.0, 1.0)
            }
        }

        class Grid(private val clut: Clut) : Stage() {
            override fun apply(v: DoubleArray): DoubleArray = clut.eval(v)
        }
    }

    fun eval(input: DoubleArray): DoubleArray {
        var v = DoubleArray(inputs) { input.getOrElse(it) { 0.0 }.coerceIn(0.0, 1.0) }
        for (stage in stages) v = stage.apply(v)
        return v
    }

    /** The encodings of the connection space that the table types use. */
    enum class PcsEncoding { LAB_V2, LAB_V4, XYZ }

    /**
     * A grid of [outputs] values at each of the points of [points] per input, the first
     * input varying slowest (ICC.1:2010, 10.9). Values are normalized to 0..1.
     */
    class Clut(private val points: IntArray, private val outputs: Int, private val values: DoubleArray) {
        private val inputs = points.size

        /** The distance in [values] between neighbours along each input. */
        private val stride = IntArray(inputs).also { s ->
            var step = outputs
            for (d in inputs - 1 downTo 0) {
                s[d] = step
                step *= points[d]
            }
        }

        fun eval(v: DoubleArray): DoubleArray {
            val out = DoubleArray(outputs)
            eval(v, 0, 0, out)
            return out
        }

        /** Interpolates over the inputs from [dim] on, at [base] in [values], into [out]. */
        private fun eval(v: DoubleArray, dim: Int, base: Int, out: DoubleArray) {
            val left = inputs - dim
            when {
                left <= 0 -> for (o in 0 until outputs) out[o] = values[base + o]
                left == 3 -> tetrahedral(v, dim, base, out)
                left == 2 -> bilinear(v, dim, base, out)
                else -> {
                    // One input, or the first of four or more: linear between the two
                    // evaluations of the rest, as Little CMS's Eval4Inputs does.
                    val (i0, f) = cell(v[dim], points[dim])
                    val low = DoubleArray(outputs)
                    eval(v, dim + 1, base + i0 * stride[dim], low)
                    if (f == 0.0) {
                        low.copyInto(out)
                        return
                    }
                    val high = DoubleArray(outputs)
                    eval(v, dim + 1, base + (i0 + 1) * stride[dim], high)
                    for (o in 0 until outputs) out[o] = low[o] + (high[o] - low[o]) * f
                }
            }
        }

        private fun bilinear(v: DoubleArray, dim: Int, base: Int, out: DoubleArray) {
            val (x0, fx) = cell(v[dim], points[dim])
            val (y0, fy) = cell(v[dim + 1], points[dim + 1])
            val sx = if (fx == 0.0) 0 else stride[dim]
            val sy = if (fy == 0.0) 0 else stride[dim + 1]
            val p = base + x0 * stride[dim] + y0 * stride[dim + 1]
            for (o in 0 until outputs) {
                val a = values[p + o] + (values[p + sy + o] - values[p + o]) * fy
                val b = values[p + sx + o] + (values[p + sx + sy + o] - values[p + sx + o]) * fy
                out[o] = a + (b - a) * fx
            }
        }

        /** Little CMS's TetrahedralInterp: the cube splits into six tetrahedra by the order of the fractions. */
        private fun tetrahedral(v: DoubleArray, dim: Int, base: Int, out: DoubleArray) {
            val (x0, rx) = cell(v[dim], points[dim])
            val (y0, ry) = cell(v[dim + 1], points[dim + 1])
            val (z0, rz) = cell(v[dim + 2], points[dim + 2])
            val sx = if (rx == 0.0) 0 else stride[dim]
            val sy = if (ry == 0.0) 0 else stride[dim + 1]
            val sz = if (rz == 0.0) 0 else stride[dim + 2]
            val p = base + x0 * stride[dim] + y0 * stride[dim + 1] + z0 * stride[dim + 2]
            for (o in 0 until outputs) {
                fun at(dx: Int, dy: Int, dz: Int) = values[p + dx + dy + dz + o]
                val c0 = at(0, 0, 0)
                val c1: Double
                val c2: Double
                val c3: Double
                when {
                    rx >= ry && ry >= rz -> {
                        c1 = at(sx, 0, 0) - c0; c2 = at(sx, sy, 0) - at(sx, 0, 0); c3 = at(sx, sy, sz) - at(sx, sy, 0)
                    }
                    rx >= rz && rz >= ry -> {
                        c1 = at(sx, 0, 0) - c0; c2 = at(sx, sy, sz) - at(sx, 0, sz); c3 = at(sx, 0, sz) - at(sx, 0, 0)
                    }
                    rz >= rx && rx >= ry -> {
                        c1 = at(sx, 0, sz) - at(0, 0, sz); c2 = at(sx, sy, sz) - at(sx, 0, sz); c3 = at(0, 0, sz) - c0
                    }
                    ry >= rx && rx >= rz -> {
                        c1 = at(sx, sy, 0) - at(0, sy, 0); c2 = at(0, sy, 0) - c0; c3 = at(sx, sy, sz) - at(sx, sy, 0)
                    }
                    ry >= rz && rz >= rx -> {
                        c1 = at(sx, sy, sz) - at(0, sy, sz); c2 = at(0, sy, 0) - c0; c3 = at(0, sy, sz) - at(0, sy, 0)
                    }
                    rz >= ry && ry >= rx -> {
                        c1 = at(sx, sy, sz) - at(0, sy, sz); c2 = at(0, sy, sz) - at(0, 0, sz); c3 = at(0, 0, sz) - c0
                    }
                    else -> {
                        c1 = 0.0; c2 = 0.0; c3 = 0.0
                    }
                }
                out[o] = c0 + c1 * rx + c2 * ry + c3 * rz
            }
        }

        /**
         * [eval] for a grid of four inputs and three outputs, as 0xRRGGBB, without allocating:
         * the path of an 8-bit CMYK image. The first input is linear between two tetrahedral
         * evaluations of the rest, as in [eval].
         */
        fun evalRgb4(a: Double, b: Double, c: Double, d: Double): Int {
            val n0 = points[0]; val n1 = points[1]; val n2 = points[2]; val n3 = points[3]
            val t0 = a.coerceIn(0.0, 1.0) * (n0 - 1); val i0 = t0.toInt().coerceAtMost(n0 - 2); val f0 = t0 - i0
            val t1 = b.coerceIn(0.0, 1.0) * (n1 - 1); val i1 = t1.toInt().coerceAtMost(n1 - 2); val f1 = t1 - i1
            val t2 = c.coerceIn(0.0, 1.0) * (n2 - 1); val i2 = t2.toInt().coerceAtMost(n2 - 2); val f2 = t2 - i2
            val t3 = d.coerceIn(0.0, 1.0) * (n3 - 1); val i3 = t3.toInt().coerceAtMost(n3 - 2); val f3 = t3 - i3
            val p = i0 * stride[0] + i1 * stride[1] + i2 * stride[2] + i3 * stride[3]
            var rgb = 0
            for (o in 0 until 3) {
                val low = tetra(p + o, stride[1], stride[2], stride[3], f1, f2, f3)
                val v = if (f0 == 0.0) low else low + (tetra(p + stride[0] + o, stride[1], stride[2], stride[3], f1, f2, f3) - low) * f0
                rgb = (rgb shl 8) or (v * 255.0 + 0.5).toInt().coerceIn(0, 255)
            }
            return rgb
        }

        /** The tetrahedral interpolation of [tetrahedral] for one output at [p], with neighbours [sx], [sy] and [sz] away. */
        private fun tetra(p: Int, sx: Int, sy: Int, sz: Int, rx: Double, ry: Double, rz: Double): Double {
            val c0 = values[p]
            return when {
                rx >= ry && ry >= rz -> c0 + (values[p + sx] - c0) * rx + (values[p + sx + sy] - values[p + sx]) * ry + (values[p + sx + sy + sz] - values[p + sx + sy]) * rz
                rx >= rz && rz >= ry -> c0 + (values[p + sx] - c0) * rx + (values[p + sx + sy + sz] - values[p + sx + sz]) * ry + (values[p + sx + sz] - values[p + sx]) * rz
                rz >= rx && rx >= ry -> c0 + (values[p + sx + sz] - values[p + sz]) * rx + (values[p + sx + sy + sz] - values[p + sx + sz]) * ry + (values[p + sz] - c0) * rz
                ry >= rx && rx >= rz -> c0 + (values[p + sx + sy] - values[p + sy]) * rx + (values[p + sy] - c0) * ry + (values[p + sx + sy + sz] - values[p + sx + sy]) * rz
                ry >= rz && rz >= rx -> c0 + (values[p + sx + sy + sz] - values[p + sy + sz]) * rx + (values[p + sy] - c0) * ry + (values[p + sy + sz] - values[p + sy]) * rz
                else -> c0 + (values[p + sx + sy + sz] - values[p + sy + sz]) * rx + (values[p + sy + sz] - values[p + sz]) * ry + (values[p + sz] - c0) * rz
            }
        }

        /** The grid cell below [x] along an axis of [n] points, and the fraction into it. */
        private fun cell(x: Double, n: Int): Pair<Int, Double> {
            if (n <= 1) return 0 to 0.0
            val t = x.coerceIn(0.0, 1.0) * (n - 1)
            val i = t.toInt().coerceAtMost(n - 2)
            val f = t - i
            // At the top of the axis the next point does not exist; stay on the last one.
            return if (f >= 1.0) (n - 1) to 0.0 else i to f
        }
    }

    companion object {

        /**
         * The table tag at [off], or null when it is not one of the four types or is cut
         * short. [atoB] says which way the table runs, which decides the order of the stages
         * of `mAB ` and `mBA `. [pcsIsLab] says whether the connection space is Lab.
         */
        fun read(b: ByteArray, off: Int, len: Int, atoB: Boolean, pcsIsLab: Boolean): IccLut? {
            if (off < 0 || len < 32 || off + len > b.size) return null
            return when (sig(b, off)) {
                "mft2" -> readLut16(b, off, len, pcsIsLab)
                "mft1" -> readLut8(b, off, len, pcsIsLab)
                "mAB ", "mBA " -> readLutAB(b, off, len, atoB, pcsIsLab)
                else -> null
            }
        }

        /** `lut16Type`: input tables, a grid and output tables of 16-bit values. Lab uses the legacy encoding. */
        private fun readLut16(b: ByteArray, off: Int, len: Int, pcsIsLab: Boolean): IccLut? {
            val i = u8(b, off + 8)
            val o = u8(b, off + 9)
            val g = u8(b, off + 10)
            if (i !in 1..MAX_INPUTS || o !in 1..MAX_OUTPUTS || g < 2) return null
            val n = u16(b, off + 48)
            val m = u16(b, off + 50)
            if (n < 2 || m < 2) return null
            val gridSize = gridCount(g, i) ?: return null
            var at = off + 52
            val need = 52L + 2L * (i.toLong() * n + gridSize * o + o.toLong() * m)
            if (need > len) return null
            val inCurves = List(i) { k -> IccCurve.Table(DoubleArray(n) { u16(b, at + 2 * (k * n + it)) / 65535.0 }) }
            at += 2 * i * n
            val grid = DoubleArray((gridSize * o).toInt()) { u16(b, at + 2 * it) / 65535.0 }
            at += 2 * (gridSize * o).toInt()
            val outCurves = List(o) { k -> IccCurve.Table(DoubleArray(m) { u16(b, at + 2 * (k * m + it)) / 65535.0 }) }
            val stages = listOf(Stage.Curves(inCurves), Stage.Grid(Clut(IntArray(i) { g }, o, grid)), Stage.Curves(outCurves))
            return IccLut(i, o, stages, if (pcsIsLab) PcsEncoding.LAB_V2 else PcsEncoding.XYZ)
        }

        /** `lut8Type`: the same shape as [readLut16] with 8-bit values and 256 entries a table. */
        private fun readLut8(b: ByteArray, off: Int, len: Int, pcsIsLab: Boolean): IccLut? {
            val i = u8(b, off + 8)
            val o = u8(b, off + 9)
            val g = u8(b, off + 10)
            if (i !in 1..MAX_INPUTS || o !in 1..MAX_OUTPUTS || g < 2) return null
            val gridSize = gridCount(g, i) ?: return null
            var at = off + 48
            val need = 48L + i * 256L + gridSize * o + o * 256L
            if (need > len) return null
            val inCurves = List(i) { k -> IccCurve.Table(DoubleArray(256) { u8(b, at + k * 256 + it) / 255.0 }) }
            at += i * 256
            val grid = DoubleArray((gridSize * o).toInt()) { u8(b, at + it) / 255.0 }
            at += (gridSize * o).toInt()
            val outCurves = List(o) { k -> IccCurve.Table(DoubleArray(256) { u8(b, at + k * 256 + it) / 255.0 }) }
            val stages = listOf(Stage.Curves(inCurves), Stage.Grid(Clut(IntArray(i) { g }, o, grid)), Stage.Curves(outCurves))
            // An 8-bit Lab value spans 0 to 255 for L* 0 to 100, which is the version 4 encoding.
            return IccLut(i, o, stages, if (pcsIsLab) PcsEncoding.LAB_V4 else PcsEncoding.XYZ)
        }

        /**
         * `lutAtoBType` and `lutBtoAType`. Device to connection space runs A curves, grid, M
         * curves, matrix and B curves; the other way runs the same stages backwards.
         */
        private fun readLutAB(b: ByteArray, off: Int, len: Int, atoB: Boolean, pcsIsLab: Boolean): IccLut? {
            val i = u8(b, off + 8)
            val o = u8(b, off + 9)
            if (i !in 1..MAX_INPUTS || o !in 1..MAX_OUTPUTS) return null
            val bOff = u32(b, off + 12)
            val matrixOff = u32(b, off + 16)
            val mOff = u32(b, off + 20)
            val clutOff = u32(b, off + 24)
            val aOff = u32(b, off + 28)
            // The device side has the A curves and the grid, the connection side three channels.
            val device = if (atoB) i else o
            val pcs = if (atoB) o else i
            if (pcs != 3 || bOff == 0L) return null
            val bCurves = curves(b, off, len, bOff, 3) ?: return null
            val mCurves = if (mOff != 0L) curves(b, off, len, mOff, 3) ?: return null else null
            val matrix = if (matrixOff != 0L) matrix(b, off, len, matrixOff) ?: return null else null
            val aCurves = if (aOff != 0L) curves(b, off, len, aOff, device) ?: return null else null
            val grid = if (clutOff != 0L) {
                val inputsOfGrid = if (atoB) i else 3
                val outputsOfGrid = if (atoB) 3 else o
                clut(b, off, len, clutOff, inputsOfGrid, outputsOfGrid) ?: return null
            } else {
                null
            }
            val forward = listOfNotNull(
                aCurves?.let { Stage.Curves(it) },
                grid?.let { Stage.Grid(it) },
                mCurves?.let { Stage.Curves(it) },
                matrix?.let { Stage.Matrix(it) },
                Stage.Curves(bCurves),
            )
            val stages = if (atoB) forward else forward.asReversed()
            return IccLut(i, o, stages, if (pcsIsLab) PcsEncoding.LAB_V4 else PcsEncoding.XYZ)
        }

        /** [count] curves, each `curv` or `para`, one after the other on 4-byte boundaries. */
        private fun curves(b: ByteArray, tagOff: Int, len: Int, rel: Long, count: Int): List<IccCurve>? {
            var at = tagOff + rel.toInt()
            val end = tagOff + len
            val out = ArrayList<IccCurve>(count)
            repeat(count) {
                if (at + 12 > end) return null
                val size = when (sig(b, at)) {
                    "curv" -> 12 + 2 * u32(b, at + 8).toInt().coerceAtLeast(0)
                    "para" -> 12 + 4 * when (u16(b, at + 8)) { 0 -> 1; 1 -> 3; 2 -> 4; 3 -> 5; 4 -> 7; else -> return null }
                    else -> return null
                }
                if (at + size > end) return null
                out += IccProfile.curveAt(b, at, size) ?: return null
                at += (size + 3) and 3.inv()
            }
            return out
        }

        /** Nine s15Fixed16 coefficients, row-major, then three offsets. */
        private fun matrix(b: ByteArray, tagOff: Int, len: Int, rel: Long): DoubleArray? {
            val at = tagOff + rel.toInt()
            if (at + 48 > tagOff + len) return null
            return DoubleArray(12) { s15f16(b, at + 4 * it) }
        }

        /** 16 bytes of points per input, a precision byte of 1 or 2, three padding bytes, then the values. */
        private fun clut(b: ByteArray, tagOff: Int, len: Int, rel: Long, inputs: Int, outputs: Int): Clut? {
            val at = tagOff + rel.toInt()
            if (at + 20 > tagOff + len || inputs > 16) return null
            val points = IntArray(inputs) { u8(b, at + it) }
            if (points.any { it < 2 }) return null
            var count = 1L
            for (p in points) {
                count *= p
                if (count > MAX_GRID) return null
            }
            val precision = u8(b, at + 16)
            val data = at + 20
            val total = (count * outputs).toInt()
            val values = when (precision) {
                1 -> if (data + total > tagOff + len) return null else DoubleArray(total) { u8(b, data + it) / 255.0 }
                2 -> if (data + 2L * total > tagOff + len) return null else DoubleArray(total) { u16(b, data + 2 * it) / 65535.0 }
                else -> return null
            }
            return Clut(points, outputs, values)
        }

        private fun gridCount(g: Int, inputs: Int): Long? {
            var count = 1L
            repeat(inputs) {
                count *= g
                if (count > MAX_GRID) return null
            }
            return count
        }

        /** Grid points a table may hold: 17 points a side for 5 inputs is 1.4 million. */
        private const val MAX_GRID = 1_500_000L
        private const val MAX_INPUTS = 8
        private const val MAX_OUTPUTS = 8

        private fun sig(b: ByteArray, at: Int): String =
            buildString(4) { for (i in 0 until 4) append((b[at + i].toInt() and 0xFF).toChar()) }

        private fun u8(b: ByteArray, at: Int): Int = b[at].toInt() and 0xFF

        private fun u16(b: ByteArray, at: Int): Int = (u8(b, at) shl 8) or u8(b, at + 1)

        private fun u32(b: ByteArray, at: Int): Long =
            (u8(b, at).toLong() shl 24) or (u8(b, at + 1).toLong() shl 16) or (u8(b, at + 2).toLong() shl 8) or u8(b, at + 3).toLong()

        private fun s15f16(b: ByteArray, at: Int): Double = u32(b, at).toInt() / 65536.0
    }
}

/** The D50 white of the connection space, as Little CMS writes it. */
internal val PCS_WHITE = doubleArrayOf(0.9642, 1.0, 0.8249)

/** CIE L*a*b* to XYZ, both relative to the D50 white of the connection space. */
internal fun labToXyz(l: Double, a: Double, b: Double): DoubleArray {
    val fy = (l + 16.0) / 116.0
    val fx = fy + a / 500.0
    val fz = fy - b / 200.0
    fun finv(t: Double) = if (t > 6.0 / 29.0) t * t * t else 108.0 / 841.0 * (t - 4.0 / 29.0)
    return doubleArrayOf(PCS_WHITE[0] * finv(fx), PCS_WHITE[1] * finv(fy), PCS_WHITE[2] * finv(fz))
}

/** XYZ to CIE L*a*b*, both relative to the D50 white of the connection space. */
internal fun xyzToLab(x: Double, y: Double, z: Double): DoubleArray {
    fun f(t: Double) = if (t > 216.0 / 24389.0) t.pow(1.0 / 3.0) else (841.0 / 108.0) * t + 4.0 / 29.0
    val fx = f(x / PCS_WHITE[0])
    val fy = f(y / PCS_WHITE[1])
    val fz = f(z / PCS_WHITE[2])
    return doubleArrayOf(116.0 * fy - 16.0, 500.0 * (fx - fy), 200.0 * (fy - fz))
}

/** The connection-space value [v] of a table, normalized, as D50 XYZ. */
internal fun decodePcs(v: DoubleArray, encoding: IccLut.PcsEncoding): DoubleArray = when (encoding) {
    // The legacy 16-bit Lab of lut16Type puts L* 100 at FF00 and a* 0 at 8000.
    IccLut.PcsEncoding.LAB_V2 -> labToXyz(v[0] * 65535.0 / 65280.0 * 100.0, v[1] * 65535.0 / 256.0 - 128.0, v[2] * 65535.0 / 256.0 - 128.0)
    IccLut.PcsEncoding.LAB_V4 -> labToXyz(v[0] * 100.0, v[1] * 255.0 - 128.0, v[2] * 255.0 - 128.0)
    // u1Fixed15: 8000 is 1.0.
    IccLut.PcsEncoding.XYZ -> DoubleArray(3) { v[it] * 65535.0 / 32768.0 }
}

/** D50 [xyz] as the normalized connection-space value a table of [encoding] reads. */
internal fun encodePcs(xyz: DoubleArray, encoding: IccLut.PcsEncoding): DoubleArray = when (encoding) {
    IccLut.PcsEncoding.XYZ -> DoubleArray(3) { (xyz[it] * 32768.0 / 65535.0).coerceIn(0.0, 1.0) }
    else -> {
        val lab = xyzToLab(xyz[0], xyz[1], xyz[2])
        if (encoding == IccLut.PcsEncoding.LAB_V2) {
            doubleArrayOf(lab[0] / 100.0 * 65280.0 / 65535.0, (lab[1] + 128.0) * 256.0 / 65535.0, (lab[2] + 128.0) * 256.0 / 65535.0)
        } else {
            doubleArrayOf(lab[0] / 100.0, (lab[1] + 128.0) / 255.0, (lab[2] + 128.0) / 255.0)
        }.map { it.coerceIn(0.0, 1.0) }.toDoubleArray()
    }
}

/**
 * A lookup-table profile's colours in sRGB: [toPcs], then black point compensation from
 * [blackPoint] to the black of sRGB, then the D50 to sRGB conversion. Little CMS computes
 * the same transform once on a grid and interpolates in it, so [toRgb] does too. MuPDF asks
 * for the low-resolution grid: 33 points for one input, and 17 points a side otherwise.
 */
internal class IccLutTransform(
    private val inputs: Int,
    private val toPcs: IccLut,
    /** D50 XYZ; zero when the profile has no black point to compensate. */
    private val blackPoint: DoubleArray,
    /**
     * For the absolute colorimetric intent, the factor per XYZ channel from the connection
     * space to the white of the medium, which then takes the place of black point compensation.
     */
    private val absoluteScale: DoubleArray? = null,
    /** False for the absolute colorimetric intent, whose white is meant to miss sRGB white. */
    private val fixWhite: Boolean = true,
) {
    /** The transform evaluated through every stage, without the grid. */
    fun exact(components: DoubleArray): RgbColor {
        val xyz = decodePcs(toPcs.eval(components), toPcs.pcsEncoding)
        val c = absoluteScale?.let { s -> DoubleArray(3) { k -> xyz[k] * s[k] } }
            // Little CMS's ComputeBlackPointCompensation: black moves to zero and the white stays.
            ?: DoubleArray(3) { k -> PCS_WHITE[k] * (xyz[k] - blackPoint[k]) / (PCS_WHITE[k] - blackPoint[k]) }
        return IccProfile.xyzD50ToSrgb(c[0], c[1], c[2])
    }

    private val grid: IccLut.Clut by lazy {
        val n = if (inputs == 1) 33 else 17
        var count = 1
        repeat(inputs) { count *= n }
        val values = DoubleArray(count * 3)
        val input = DoubleArray(inputs)
        for (index in 0 until count) {
            // The first input varies slowest, as the grid of a profile does.
            var rest = index
            for (d in inputs - 1 downTo 0) {
                input[d] = (rest % n) / (n - 1).toDouble()
                rest /= n
            }
            // Little CMS keeps the grid in 16 bits, so a white that lands a hair under 1 is 1.
            val rgb = exact(input)
            values[3 * index] = sixteenBits(rgb.r)
            values[3 * index + 1] = sixteenBits(rgb.g)
            values[3 * index + 2] = sixteenBits(rgb.b)
        }
        // Little CMS's FixWhiteMisalignment: the white of the input, no ink for CMYK and full
        // light otherwise, lands on sRGB white exactly, unless a channel is far off.
        val white = 3 * (if (inputs == 4) 0 else count - 1)
        if (fixWhite && (0..2).all { 1.0 - values[white + it] <= WHITE_FIXUP_LIMIT }) for (k in 0..2) values[white + k] = 1.0
        IccLut.Clut(IntArray(inputs) { n }, 3, values)
    }

    private fun sixteenBits(v: Double): Double = kotlin.math.round(v.coerceIn(0.0, 1.0) * 65535.0) / 65535.0

    private companion object {
        /** Little CMS leaves a white more than F000 of 16 bits away alone. */
        const val WHITE_FIXUP_LIMIT = 0xF000 / 65535.0
    }

    /** [toRgb] of 8-bit CMYK inks as 0xRRGGBB, without allocating; only for four inputs. */
    fun rgb8(c: Int, m: Int, y: Int, k: Int): Int = grid.evalRgb4(c / 255.0, m / 255.0, y / 255.0, k / 255.0)

    fun toRgb(components: DoubleArray): RgbColor {
        val v = grid.eval(DoubleArray(inputs) { components.getOrElse(it) { 0.0 } })
        return RgbColor(v[0].coerceIn(0.0, 1.0), v[1].coerceIn(0.0, 1.0), v[2].coerceIn(0.0, 1.0))
    }
}
