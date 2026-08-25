package io.github.yuroyami.kitepdf.core.render

import kotlin.math.pow

/**
 * The part of an ICC profile a document reader actually needs: the
 * matrix/TRC shape that RGB and grey profiles use.
 *
 * An ICC profile says how a file's numbers map to real colour. Ignoring it is
 * what makes a photo tagged AdobeRGB look flat when it is drawn as if it were
 * sRGB. This reads the tone curves and the colorant matrix, which is all a
 * matrix/TRC profile has, and converts through XYZ.
 *
 * Profiles that describe themselves with lookup tables (`A2B0`, `mft1`,
 * `mft2`, `mAB`) are not read: [parse] returns null and the caller keeps its
 * device-colour fallback. Those are mostly CMYK press profiles, where the
 * device fallback is already what every other reader shows.
 */
public class IccProfile internal constructor(
    /** 1 for grey, 3 for RGB. */
    public val componentCount: Int,
    /** Tone curve per channel: encoded value in, linear light out. */
    private val curves: List<IccCurve>,
    /**
     * Column-major colorant matrix (X, Y, Z per channel) taking linear channel
     * values to D50 XYZ. Null for a grey profile, which uses its white point.
     */
    private val colorants: DoubleArray?,
    /** Media white point, D50-relative. */
    private val whitePoint: DoubleArray,
) {

    /**
     * True when this profile IS sRGB (or near enough), so converting through
     * it would only add rounding. Most PDFs tag their colours "sRGB
     * IEC61966-2.1", and for those the honest transform is no transform: the
     * round trip through XYZ costs about a level of 0..255 and buys nothing.
     */
    public val isIdentity: Boolean by lazy {
        PROBES.all { probe ->
            val input = DoubleArray(componentCount) { probe[it % probe.size] }
            val out = toRgb(input)
            val want = if (componentCount == 1) DoubleArray(3) { input[0] } else input
            maxOf(
                kotlin.math.abs(out.r - want[0]),
                kotlin.math.abs(out.g - want[1]),
                kotlin.math.abs(out.b - want[2]),
            ) < IDENTITY_TOLERANCE
        }
    }

    /** Convert [components] (0..1 per channel) to sRGB. */
    public fun toRgb(components: DoubleArray): RgbColor {
        if (colorants == null) {
            val g = curves.firstOrNull()?.eval(components.getOrElse(0) { 0.0 }) ?: 0.0
            return xyzD50ToSrgb(whitePoint[0] * g, whitePoint[1] * g, whitePoint[2] * g)
        }
        val r = curves.getOrNull(0)?.eval(components.getOrElse(0) { 0.0 }) ?: 0.0
        val g = curves.getOrNull(1)?.eval(components.getOrElse(1) { 0.0 }) ?: 0.0
        val b = curves.getOrNull(2)?.eval(components.getOrElse(2) { 0.0 }) ?: 0.0
        val x = colorants[0] * r + colorants[3] * g + colorants[6] * b
        val y = colorants[1] * r + colorants[4] * g + colorants[7] * b
        val z = colorants[2] * r + colorants[5] * g + colorants[8] * b
        return xyzD50ToSrgb(x, y, z)
    }

    public companion object {

        /** Colours the identity check is measured on. */
        private val PROBES = listOf(
            doubleArrayOf(0.0, 0.0, 0.0),
            doubleArrayOf(1.0, 1.0, 1.0),
            doubleArrayOf(1.0, 0.0, 0.0),
            doubleArrayOf(0.0, 1.0, 0.0),
            doubleArrayOf(0.0, 0.0, 1.0),
            doubleArrayOf(0.5, 0.5, 0.5),
            doubleArrayOf(0.25, 0.6, 0.9),
        )

        /**
         * How close to identity counts as identity, in 0..255 levels. The two
         * cases are far apart, so the exact figure barely matters: a real sRGB
         * profile measures 0.9 levels off (pure rounding through XYZ), while
         * AdobeRGB, the nearest thing to a near-miss, measures 64.
         */
        private const val IDENTITY_TOLERANCE = 1.5 / 255.0

        /**
         * Read [bytes] as an ICC profile, or null when it is not one this can
         * use (a lookup-table profile, a colour space other than RGB or grey,
         * or anything malformed).
         */
        public fun parse(bytes: ByteArray): IccProfile? {
            if (bytes.size < 132) return null
            // Header: size(4) cmm(4) version(4) class(4) space(4) pcs(4) ...
            val space = tag(bytes, 16)
            val components = when (space) {
                "RGB " -> 3
                "GRAY" -> 1
                else -> return null
            }
            val count = u32(bytes, 128).toInt()
            if (count <= 0 || count > 1024) return null
            val tags = HashMap<String, Pair<Int, Int>>(count)
            for (i in 0 until count) {
                val at = 132 + i * 12
                if (at + 12 > bytes.size) return null
                val off = u32(bytes, at + 4).toInt()
                val len = u32(bytes, at + 8).toInt()
                if (off < 0 || len < 0 || off + len > bytes.size) continue
                tags[tag(bytes, at)] = off to len
            }
            // A profile that describes itself with a lookup table needs the
            // full pipeline; say no rather than half-render it.
            if ("A2B0" in tags && "rXYZ" !in tags && "kTRC" !in tags) return null

            val white = tags["wtpt"]?.let { xyzTag(bytes, it.first, it.second) }
                ?: doubleArrayOf(0.9642, 1.0, 0.8249)   // D50, the PCS white

            if (components == 1) {
                val curve = tags["kTRC"]?.let { curveTag(bytes, it.first, it.second) } ?: return null
                return IccProfile(1, listOf(curve), null, white)
            }
            val r = tags["rXYZ"]?.let { xyzTag(bytes, it.first, it.second) } ?: return null
            val g = tags["gXYZ"]?.let { xyzTag(bytes, it.first, it.second) } ?: return null
            val b = tags["bXYZ"]?.let { xyzTag(bytes, it.first, it.second) } ?: return null
            val curves = listOf("rTRC", "gTRC", "bTRC").map { name ->
                tags[name]?.let { curveTag(bytes, it.first, it.second) } ?: IccCurve.Gamma(1.0)
            }
            return IccProfile(3, curves, doubleArrayOf(r[0], r[1], r[2], g[0], g[1], g[2], b[0], b[1], b[2]), white)
        }

        /** `XYZType`: a signature, four reserved bytes, then s15Fixed16 X, Y, Z. */
        private fun xyzTag(b: ByteArray, off: Int, len: Int): DoubleArray? {
            if (len < 20 || off + 20 > b.size) return null
            if (tag(b, off) != "XYZ ") return null
            return doubleArrayOf(s15f16(b, off + 8), s15f16(b, off + 12), s15f16(b, off + 16))
        }

        /** `curveType` (`curv`) or `parametricCurveType` (`para`). */
        private fun curveTag(b: ByteArray, off: Int, len: Int): IccCurve? {
            if (off + 12 > b.size) return null
            return when (tag(b, off)) {
                "curv" -> {
                    val n = u32(b, off + 8).toInt()
                    when {
                        n == 0 -> IccCurve.Gamma(1.0)                       // identity
                        n == 1 -> IccCurve.Gamma(u16(b, off + 12) / 256.0)  // u8Fixed8 gamma
                        n < 0 || off + 12 + n * 2 > b.size || n > 1 shl 16 -> null
                        else -> IccCurve.Table(DoubleArray(n) { u16(b, off + 12 + it * 2) / 65535.0 })
                    }
                }
                "para" -> parametric(b, off, len)
                else -> null
            }
        }

        /** ICC parametric curve types 0..4, the shapes sRGB-like profiles use. */
        private fun parametric(b: ByteArray, off: Int, len: Int): IccCurve? {
            if (off + 12 > b.size) return null
            val type = u16(b, off + 8)
            val need = when (type) { 0 -> 1; 1 -> 3; 2 -> 4; 3 -> 5; 4 -> 7; else -> return null }
            if (off + 12 + need * 4 > b.size || len < 12 + need * 4) return null
            val p = DoubleArray(need) { s15f16(b, off + 12 + it * 4) }
            return IccCurve.Parametric(type, p)
        }

        private fun tag(b: ByteArray, at: Int): String =
            buildString(4) { for (i in 0 until 4) append((b[at + i].toInt() and 0xFF).toChar()) }

        private fun u16(b: ByteArray, at: Int): Int =
            ((b[at].toInt() and 0xFF) shl 8) or (b[at + 1].toInt() and 0xFF)

        private fun u32(b: ByteArray, at: Int): Long =
            ((b[at].toLong() and 0xFF) shl 24) or ((b[at + 1].toLong() and 0xFF) shl 16) or
                ((b[at + 2].toLong() and 0xFF) shl 8) or (b[at + 3].toLong() and 0xFF)

        /** s15Fixed16: a signed 32-bit value with 16 fraction bits. */
        private fun s15f16(b: ByteArray, at: Int): Double = u32(b, at).toInt() / 65536.0

        /**
         * The PCS is D50; sRGB is D65. Bradford-adapt, then use the shared
         * XYZ-to-sRGB conversion.
         */
        private fun xyzD50ToSrgb(x: Double, y: Double, z: Double): RgbColor {
            val x65 = 0.9555766 * x - 0.0230393 * y + 0.0631636 * z
            val y65 = -0.0282895 * x + 1.0099416 * y + 0.0210077 * z
            val z65 = 0.0122982 * x - 0.0204830 * y + 1.3299098 * z
            var r = x65 * 3.2404542 - y65 * 1.5371385 - z65 * 0.4985314
            var g = -x65 * 0.9692660 + y65 * 1.8760108 + z65 * 0.0415560
            var b = x65 * 0.0556434 - y65 * 0.2040259 + z65 * 1.0572252
            fun encode(c: Double): Double {
                val cc = c.coerceIn(0.0, 1.0)
                return if (cc <= 0.0031308) 12.92 * cc else 1.055 * cc.pow(1.0 / 2.4) - 0.055
            }
            r = encode(r); g = encode(g); b = encode(b)
            return RgbColor(r.coerceIn(0.0, 1.0), g.coerceIn(0.0, 1.0), b.coerceIn(0.0, 1.0))
        }
    }
}

/** One channel's tone curve: encoded value in (0..1), linear light out. */
internal sealed class IccCurve {
    abstract fun eval(x: Double): Double

    class Gamma(private val g: Double) : IccCurve() {
        override fun eval(x: Double): Double = x.coerceIn(0.0, 1.0).pow(g)
    }

    /** Sampled curve, linearly interpolated between entries. */
    class Table(private val values: DoubleArray) : IccCurve() {
        override fun eval(x: Double): Double {
            if (values.isEmpty()) return x
            if (values.size == 1) return values[0]
            val t = x.coerceIn(0.0, 1.0) * (values.size - 1)
            val i = t.toInt().coerceAtMost(values.size - 2)
            val f = t - i
            return values[i] * (1 - f) + values[i + 1] * f
        }
    }

    /** ICC parametric types 0..4 (g, a, b, c, d, e, f), in that parameter order. */
    class Parametric(private val type: Int, private val p: DoubleArray) : IccCurve() {
        override fun eval(x: Double): Double {
            val v = x.coerceIn(0.0, 1.0)
            fun at(i: Int) = p.getOrElse(i) { 0.0 }
            val g = at(0)
            return when (type) {
                0 -> v.pow(g)
                1 -> { val a = at(1); val b = at(2); if (v >= -b / a) (a * v + b).pow(g) else 0.0 }
                2 -> { val a = at(1); val b = at(2); val c = at(3); if (v >= -b / a) (a * v + b).pow(g) + c else c }
                3 -> { val a = at(1); val b = at(2); val c = at(3); val d = at(4); if (v >= d) (a * v + b).pow(g) else c * v }
                4 -> {
                    val a = at(1); val b = at(2); val c = at(3); val d = at(4); val e = at(5); val f = at(6)
                    if (v >= d) (a * v + b).pow(g) + e else c * v + f
                }
                else -> v
            }.coerceIn(0.0, 1.0)
        }
    }
}
