package io.github.yuroyami.kitepdf.core.render

import io.github.yuroyami.kitepdf.core.KiteLock
import io.github.yuroyami.kitepdf.core.render.IccProfile.Companion.curveAt
import io.github.yuroyami.kitepdf.core.render.IccProfile.Companion.tag
import io.github.yuroyami.kitepdf.core.render.IccProfile.Companion.u32
import io.github.yuroyami.kitepdf.core.render.IccProfile.Companion.xyzTag
import io.github.yuroyami.kitepdf.core.withLock
import kotlin.math.abs

/**
 * The bytes and tag table of an ICC profile, from which [build] makes the transform of any
 * rendering intent the way Little CMS makes it for MuPDF (#201): the table of the intent,
 * the black point of the intent for black point compensation, and for the absolute intent
 * the white of the medium.
 */
internal class IccSource private constructor(
    private val bytes: ByteArray,
    private val tags: Map<String, Pair<Int, Int>>,
    /** 1 for grey, 3 for RGB, 4 for CMYK. */
    val components: Int,
    /** The version of the header, for example 0x02100000. */
    private val version: Long,
    /** The class of the header: `prtr` for a printer, `mntr` for a display, and so on. */
    private val deviceClass: String,
    /** True for a Lab connection space, false for XYZ, and null for neither, which has no usable table. */
    private val pcsIsLab: Boolean?,
) {

    /**
     * How the transform of one intent is made: the tag of its table, or null for the matrix,
     * the black point to compensate, the scale of the absolute intent, and the white fix-up.
     */
    data class Plan(val table: String?, val black: List<Double>, val scale: List<Double>?, val fixWhite: Boolean)

    /** The plan of the relative colorimetric intent with black point compensation, the one [IccProfile.parse] builds. */
    val defaultPlan: Plan by lazy { plan(KiteRenderingIntent.RelativeColorimetric, blackPointCompensation = true) }

    fun plan(intent: KiteRenderingIntent, blackPointCompensation: Boolean): Plan {
        val absolute = intent == KiteRenderingIntent.AbsoluteColorimetric
        val table = inputTableTag(intent)
        // Little CMS compensates no black point for the absolute intent, and none through a matrix here.
        val black = if (table != null && blackPointCompensation && !absolute) blackPoint(intent) else NONE
        return Plan(table, black.toList(), if (absolute) absoluteScale()?.toList() else null, fixWhite = !absolute)
    }

    /** The profile of [plan], or null when the profile has neither a table nor a matrix to use. */
    fun build(plan: Plan, root: IccProfile?): IccProfile? {
        val keep = this.takeIf { root == null }
        val scale = plan.scale?.toDoubleArray()
        plan.table?.let { name ->
            val table = table(name) ?: return null
            val transform = IccLutTransform(components, table, plan.black.toDoubleArray(), scale, plan.fixWhite)
            return IccProfile(components, emptyList(), null, null, transform, keep, root)
        }
        if (components == 4) return null
        // A table that cannot be read leaves only the matrix; without one, say no
        // rather than half-render the profile.
        if (("A2B0" in tags || "A2B1" in tags) && "rXYZ" !in tags && "kTRC" !in tags) return null
        val (curves, colorants) = matrix() ?: return null
        return IccProfile(components, curves, colorants, scale, null, keep, root)
    }

    /* ─── Tables ───────────────────────────────────────────────────────────── */

    private val tables = HashMap<String, IccLut?>()
    private val tablesLock = KiteLock()

    /** The device-to-connection-space table [name], read once, or null when it cannot be used. */
    private fun table(name: String): IccLut? = tablesLock.withLock {
        tables.getOrPut(name) {
            val lab = pcsIsLab ?: return@getOrPut null
            tags[name]?.let { (off, len) -> IccLut.read(bytes, off, len, atoB = true, pcsIsLab = lab) }
                ?.takeIf { it.inputs == components && it.outputs == 3 }
        }
    }

    /**
     * The tag of the table that converts [intent]: the intent's own, else `A2B0`, as Little
     * CMS picks it (cmsio1.c, _cmsReadInputLUT). Null when neither can be used, which leaves
     * the matrix.
     */
    private fun inputTableTag(intent: KiteRenderingIntent): String? =
        listOf(A2B[intent.ordinal], "A2B0").firstOrNull { table(it) != null }

    private fun inputTable(intent: KiteRenderingIntent): IccLut? = inputTableTag(intent)?.let(::table)

    /** True when the profile has a matrix and tone curves, whatever tables it also has. */
    private val isMatrixShaper: Boolean
        get() = when (components) {
            1 -> "kTRC" in tags
            3 -> listOf("rXYZ", "gXYZ", "bXYZ", "rTRC", "gTRC", "bTRC").all { it in tags }
            else -> false
        }

    /** The tone curves and colorant matrix of a grey or RGB profile. */
    private fun matrix(): Pair<List<IccCurve>, DoubleArray?>? {
        if (components == 1) {
            val curve = tags["kTRC"]?.let { curveAt(bytes, it.first, it.second) } ?: return null
            return listOf(curve) to null
        }
        val r = tags["rXYZ"]?.let { xyzTag(bytes, it.first, it.second) } ?: return null
        val g = tags["gXYZ"]?.let { xyzTag(bytes, it.first, it.second) } ?: return null
        val b = tags["bXYZ"]?.let { xyzTag(bytes, it.first, it.second) } ?: return null
        val curves = listOf("rTRC", "gTRC", "bTRC").map { name ->
            tags[name]?.let { curveAt(bytes, it.first, it.second) } ?: IccCurve.Gamma(1.0)
        }
        return curves to doubleArrayOf(r[0], r[1], r[2], g[0], g[1], g[2], b[0], b[1], b[2])
    }

    /* ─── The white of the medium ──────────────────────────────────────────── */

    /**
     * The factor per XYZ channel from the connection space to the white of the medium, or
     * null when the medium is D50. MuPDF's sRGB profile is a version 2 display profile, so
     * its own white reads as D50 (cmscnvrt.c, ComputeAbsoluteIntent).
     */
    private fun absoluteScale(): DoubleArray? {
        val white = mediaWhite()
        val scale = DoubleArray(3) { white[it] / PCS_WHITE[it] }
        return scale.takeIf { s -> s.any { abs(it - 1.0) > 1e-6 } }
    }

    /** The `wtpt` tag, as Little CMS reads it: D50 when it is missing, and for a version 2 display profile. */
    private fun mediaWhite(): DoubleArray {
        val white = tags["wtpt"]?.let { xyzTag(bytes, it.first, it.second) } ?: return PCS_WHITE
        return if (version < VERSION_4 && deviceClass == "mntr") PCS_WHITE else white
    }

    /* ─── The black point (cmssamp.c) ─────────────────────────────────────── */

    /** The black point of [intent], as cmsDetectBlackPoint finds it. */
    private fun blackPoint(intent: KiteRenderingIntent): DoubleArray {
        if (deviceClass == "link" || deviceClass == "abst" || deviceClass == "nmcl") return NONE
        val perceptual = intent == KiteRenderingIntent.Perceptual || intent == KiteRenderingIntent.Saturation
        // A version 4 profile has a fixed black for these two intents.
        if (version >= VERSION_4 && perceptual) {
            return if (isMatrixShaper) darkerColorant(KiteRenderingIntent.RelativeColorimetric) else PERCEPTUAL_BLACK
        }
        // A press profile's darkest ink is often ink-limited, so its black comes from Lab black.
        if (intent == KiteRenderingIntent.RelativeColorimetric && deviceClass == "prtr" && components == 4) return perceptualBlack()
        return darkerColorant(intent)
    }

    /** The colour of the darkest device value through [intent], made neutral with L* at most 50. */
    private fun darkerColorant(intent: KiteRenderingIntent): DoubleArray {
        if (A2B[intent.ordinal] !in tags && !isMatrixShaper) return NONE
        val black = DoubleArray(components) { if (components == 4) 1.0 else 0.0 }
        val xyz = inputTable(intent)?.let { decodePcs(it.eval(black), it.pcsEncoding) } ?: matrixXyz(black) ?: return NONE
        val l = xyzToLab(xyz[0], xyz[1], xyz[2])[0]
        // A black lighter than L* 95 is a negative profile, whose black point is zero.
        val clipped = when {
            l > 95.0 || l < 0.0 -> 0.0
            l > 50.0 -> 50.0
            else -> l
        }
        return labToXyz(clipped, 0.0, 0.0)
    }

    /** Lab black through the perceptual table back to ink, then forward through the relative one. */
    private fun perceptualBlack(): DoubleArray {
        if ("A2B0" !in tags && !isMatrixShaper) return NONE
        val lab = pcsIsLab ?: return NONE
        val back = tags["B2A0"]?.let { (off, len) -> IccLut.read(bytes, off, len, atoB = false, pcsIsLab = lab) }
            ?.takeIf { it.inputs == 3 && it.outputs == components } ?: return NONE
        val device = back.eval(encodePcs(labToXyz(0.0, 0.0, 0.0), back.pcsEncoding))
        val forward = inputTable(KiteRenderingIntent.RelativeColorimetric) ?: return NONE
        val xyz = decodePcs(forward.eval(device), forward.pcsEncoding)
        return labToXyz(xyzToLab(xyz[0], xyz[1], xyz[2])[0].coerceAtMost(50.0), 0.0, 0.0)
    }

    /** D50 XYZ of [v] through the tone curves and matrix, or null for a profile without them. */
    private fun matrixXyz(v: DoubleArray): DoubleArray? {
        val (curves, c) = matrix() ?: return null
        if (c == null) return DoubleArray(3) { PCS_WHITE[it] * curves[0].eval(v[0]) }
        val lin = DoubleArray(3) { curves[it].eval(v[it]) }
        return DoubleArray(3) { k -> c[k] * lin[0] + c[3 + k] * lin[1] + c[6 + k] * lin[2] }
    }

    companion object {
        /** The device-to-connection-space tag of each intent, in ICC order; the absolute intent reads `A2B1`. */
        private val A2B = arrayOf("A2B0", "A2B1", "A2B2", "A2B1")

        /** Little CMS's black for the perceptual and saturation intents of a version 4 profile. */
        private val PERCEPTUAL_BLACK = doubleArrayOf(0.00336, 0.0034731, 0.00287)

        private val NONE = doubleArrayOf(0.0, 0.0, 0.0)
        private const val VERSION_4 = 0x04000000L

        /** The header and tag table of [bytes], or null when it is not a grey, RGB or CMYK profile. */
        fun read(bytes: ByteArray): IccSource? {
            if (bytes.size < 132) return null
            // Header: size(4) cmm(4) version(4) class(4) space(4) pcs(4) ...
            val components = when (tag(bytes, 16)) {
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
            val pcsIsLab = when (tag(bytes, 20)) {
                "Lab " -> true
                "XYZ " -> false
                else -> null
            }
            return IccSource(bytes, tags, components, u32(bytes, 8), tag(bytes, 12), pcsIsLab)
        }
    }
}
