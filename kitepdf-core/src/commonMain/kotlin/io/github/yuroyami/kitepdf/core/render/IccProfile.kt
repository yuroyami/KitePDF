package io.github.yuroyami.kitepdf.core.render

import io.github.yuroyami.kitepdf.core.withLock
import kotlin.math.pow

/**
 * The part of an ICC profile a document reader actually needs: how the colours of a grey,
 * RGB or CMYK profile map to sRGB.
 *
 * An ICC profile says how a file's numbers map to real colour. Ignoring it is
 * what makes a photo tagged AdobeRGB look flat when it is drawn as if it were
 * sRGB. A matrix/TRC profile has tone curves and a colorant matrix, and converts
 * through XYZ. A lookup-table profile, which most CMYK press profiles are, converts
 * through its `A2B1` or `A2B0` table with the relative colorimetric intent and black
 * point compensation, as MuPDF does through Little CMS (#200).
 */
public class IccProfile internal constructor(
    /** 1 for grey, 3 for RGB, 4 for CMYK. */
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
    /** The transform of a lookup-table profile, which takes over from [curves] and [colorants]. */
    private val lut: IccLutTransform? = null,
) {

    /**
     * True when this profile IS sRGB (or near enough), so converting through
     * it would only add rounding. Most PDFs tag their colours "sRGB
     * IEC61966-2.1", and for those the honest transform is no transform: the
     * round trip through XYZ costs about a level of 0..255 and buys nothing.
     */
    public val isIdentity: Boolean by lazy {
        if (componentCount == 4) return@lazy false
        PROBES.all { probe ->
            val input = DoubleArray(componentCount) { probe[it % probe.size] }
            val out = lut?.exact(input) ?: toRgb(input)
            val want = if (componentCount == 1) DoubleArray(3) { input[0] } else input
            maxOf(
                kotlin.math.abs(out.r - want[0]),
                kotlin.math.abs(out.g - want[1]),
                kotlin.math.abs(out.b - want[2]),
            ) < IDENTITY_TOLERANCE
        }
    }

    /** The curves and matrix of an RGB profile, which the raster path runs from tables. Null for grey. */
    internal val curveMatrix: CurveMatrix? by lazy {
        if (lut != null) return@lazy null
        val c = colorants ?: return@lazy null
        if (curves.size < 3) return@lazy null
        // The colorants are column-major: X = c[0] r + c[3] g + c[6] b.
        val toXyz = DoubleArray(9) { i -> c[3 * (i % 3) + i / 3] }
        CurveMatrix(List(3) { k -> curves[k]::eval }, times3(D50_TO_LINEAR_SRGB, toXyz))
    }

    /** 8-bit CMYK inks as 0xRRGGBB through the table of a CMYK profile, or null for any other profile. */
    internal fun cmyk8(c: Int, m: Int, y: Int, k: Int): Int? = if (componentCount == 4) lut?.rgb8(c, m, y, k) else null

    /** Convert [components] (0..1 per channel) to sRGB. */
    public fun toRgb(components: DoubleArray): RgbColor {
        lut?.let { return it.toRgb(components) }
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
         * use (a colour space other than grey, RGB or CMYK, a table this cannot
         * read, or anything malformed).
         *
         * The renderer resolves colour spaces on every render, and the grid of a CMYK
         * table takes tens of milliseconds to build, so the last profiles read are kept,
         * keyed by their bytes, and a profile that repeats is read once (#200).
         */
        public fun parse(bytes: ByteArray): IccProfile? {
            val key = ProfileKey(bytes.size, fnv64(bytes))
            cacheLock.withLock { if (key in cache) return cache.getValue(key).also { cache.remove(key); cache[key] = it } }
            val profile = parseUncached(bytes)
            cacheLock.withLock {
                cache[key] = profile
                if (cache.size > CACHED_PROFILES) cache.remove(cache.keys.first())
            }
            return profile
        }

        private data class ProfileKey(val size: Int, val hash: Long)

        /** The profiles read last, in order from the one used least recently. */
        private val cache = LinkedHashMap<ProfileKey, IccProfile?>()
        private val cacheLock = io.github.yuroyami.kitepdf.core.KiteLock()
        private const val CACHED_PROFILES = 16

        /** FNV-1a over [bytes]: equal profiles share a key, and different ones almost never do. */
        private fun fnv64(bytes: ByteArray): Long {
            var h = -0x340d631b7bdddcdbL
            for (b in bytes) h = (h xor (b.toLong() and 0xFF)) * 0x100000001b3L
            return h
        }

        private fun parseUncached(bytes: ByteArray): IccProfile? {
            if (bytes.size < 132) return null
            // Header: size(4) cmm(4) version(4) class(4) space(4) pcs(4) ...
            val space = tag(bytes, 16)
            val components = when (space) {
                "RGB " -> 3
                "GRAY" -> 1
                "CMYK" -> 4
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
            val white = tags["wtpt"]?.let { xyzTag(bytes, it.first, it.second) }
                ?: doubleArrayOf(0.9642, 1.0, 0.8249)   // D50, the PCS white

            // Little CMS reads a table before a matrix: A2B1 for the relative colorimetric
            // intent of ISO 32000-1, 8.6.5.8, else A2B0 (#200).
            lutTransform(bytes, tags, components)?.let { return IccProfile(components, emptyList(), null, white, it) }
            if (components == 4) return null
            // A table that cannot be read leaves only the matrix; without one, say no
            // rather than half-render the profile.
            if (("A2B0" in tags || "A2B1" in tags) && "rXYZ" !in tags && "kTRC" !in tags) return null

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

        /**
         * The transform through the relative colorimetric table of a lookup-table profile,
         * with its black point for black point compensation, or null when it has none.
         */
        private fun lutTransform(bytes: ByteArray, tags: Map<String, Pair<Int, Int>>, components: Int): IccLutTransform? {
            val pcsIsLab = when (tag(bytes, 20)) {
                "Lab " -> true
                "XYZ " -> false
                else -> return null
            }
            fun table(name: String, atoB: Boolean): IccLut? =
                tags[name]?.let { (off, len) -> IccLut.read(bytes, off, len, atoB, pcsIsLab) }
            val a2b1 = table("A2B1", atoB = true)?.takeIf { it.inputs == components && it.outputs == 3 }
            val relative = a2b1 ?: table("A2B0", atoB = true)?.takeIf { it.inputs == components && it.outputs == 3 } ?: return null
            val black = blackPoint(bytes, components, relative, hasRelative = a2b1 != null, b2a0 = { table("B2A0", atoB = false) })
            return IccLutTransform(components, relative, black)
        }

        /**
         * The black point of a lookup-table profile, as Little CMS detects it for black
         * point compensation with the relative colorimetric intent (cmsDetectBlackPoint).
         * A CMYK printer profile maps Lab black through its perceptual table and back; any
         * other profile converts its darkest colour. The point is neutral, with L* at most
         * 50, and zero when the profile has no table for the intent.
         */
        private fun blackPoint(
            bytes: ByteArray, components: Int, relative: IccLut, hasRelative: Boolean, b2a0: () -> IccLut?,
        ): DoubleArray {
            val none = doubleArrayOf(0.0, 0.0, 0.0)
            val device = if (components == 4 && tag(bytes, 12) == "prtr") {
                val back = b2a0()?.takeIf { it.inputs == 3 && it.outputs == 4 } ?: return none
                back.eval(encodePcs(labToXyz(0.0, 0.0, 0.0), back.pcsEncoding))
            } else {
                if (!hasRelative) return none
                DoubleArray(components) { if (components == 4) 1.0 else 0.0 }
            }
            val xyz = decodePcs(relative.eval(device), relative.pcsEncoding)
            val l = xyzToLab(xyz[0], xyz[1], xyz[2])[0].coerceIn(0.0, 50.0)
            return labToXyz(l, 0.0, 0.0)
        }

        /** Internal: the curve tag at [at], for [IccLut]. */
        internal fun curveAt(b: ByteArray, at: Int, len: Int): IccCurve? = curveTag(b, at, len)

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
        internal fun xyzD50ToSrgb(x: Double, y: Double, z: Double): RgbColor {
            val m = D50_TO_LINEAR_SRGB
            return RgbColor(
                srgbEncode(m[0] * x + m[1] * y + m[2] * z),
                srgbEncode(m[3] * x + m[4] * y + m[5] * z),
                srgbEncode(m[6] * x + m[7] * y + m[8] * z),
            )
        }

        /**
         * D50 XYZ to linear sRGB: [D50_TO_D65], then [XYZ_TO_SRGB], each row scaled so the
         * connection-space white lands on 1. The two matrices were built for a D50 a little
         * off the one Little CMS uses, so white came out 0.04 percent short in blue (#200).
         */
        private val D50_TO_LINEAR_SRGB: DoubleArray by lazy {
            val m = times3(XYZ_TO_SRGB, D50_TO_D65)
            DoubleArray(9) { i ->
                val r = i / 3
                m[i] / (m[3 * r] * PCS_WHITE[0] + m[3 * r + 1] * PCS_WHITE[1] + m[3 * r + 2] * PCS_WHITE[2])
            }
        }

        /** Bradford adaptation from the D50 connection space to D65, row-major. */
        private val D50_TO_D65 = doubleArrayOf(
            0.9555766, -0.0230393, 0.0631636,
            -0.0282895, 1.0099416, 0.0210077,
            0.0122982, -0.0204830, 1.3299098,
        )

        /** D65 XYZ to linear sRGB, row-major. */
        private val XYZ_TO_SRGB = doubleArrayOf(
            3.2404542, -1.5371385, -0.4985314,
            -0.9692660, 1.8760108, 0.0415560,
            0.0556434, -0.2040259, 1.0572252,
        )
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
