package io.github.yuroyami.kitepdf.core.render

import io.github.yuroyami.kitepdf.core.parser.IndirectResolver
import io.github.yuroyami.kitepdf.core.parser.PdfArray
import io.github.yuroyami.kitepdf.core.parser.PdfDictionary
import io.github.yuroyami.kitepdf.core.parser.PdfInt
import io.github.yuroyami.kitepdf.core.parser.PdfName
import io.github.yuroyami.kitepdf.core.parser.PdfObject
import io.github.yuroyami.kitepdf.core.parser.PdfReal
import io.github.yuroyami.kitepdf.core.parser.PdfReference
import io.github.yuroyami.kitepdf.core.parser.PdfStream

/**
 * Parsed Extended Graphics State (ISO 32000-1 §8.4.5).
 *
 * Each `/ExtGState /<name>` resource is a dict of state-modifier entries.
 * The `gs` content-stream operator merges them into the current
 * [GraphicsState]. We extract only the fields we actually act on:
 *
 *   - `/CA`: stroke alpha (0..1)
 *   - `/ca`: fill alpha (0..1)
 *   - `/BM`: blend mode (name or array of names)
 *   - `/SMask`: soft-mask dict ("None" / Mask dict)
 *   - `/LW` `/LC` `/LJ` `/ML` `/D`: line width, cap, join, miter limit and dash
 *   - `/AIS`, `/SA`, `/OP`, `/op`, `/OPM`, `/Font`, `/RI`: accepted but ignored (rare)
 *
 * Missing fields stay at their previous values; that's the spec's
 * "ExtGState modifies the current state" rule.
 */
public data class ExtGState(
    val fillAlpha: Double? = null,
    val strokeAlpha: Double? = null,
    val blendMode: KiteBlendMode? = null,
    val softMask: SoftMask? = null,
    val lineWidth: Double? = null,
    val lineCap: Int? = null,
    val lineJoin: Int? = null,
    val miterLimit: Double? = null,
    /** `/D`'s dash array, or null when the dictionary sets no dash. Empty means solid. */
    val dashArray: List<Double>? = null,
    /** `/D`'s dash phase, read together with [dashArray]. */
    val dashPhase: Double = 0.0,
) {

    public companion object {

        public fun parse(dict: PdfDictionary, refs: IndirectResolver): ExtGState {
            val fillAlpha = dict.getReal("ca")
            val strokeAlpha = dict.getReal("CA")
            val blendMode = when (val bm = dict["BM"]) {
                is PdfName -> KiteBlendMode.parse(bm.value)
                is PdfArray -> KiteBlendMode.parse((bm.firstOrNull() as? PdfName)?.value)
                else -> null
            }
            val smask = parseSoftMask(dict["SMask"], refs)
            val lw = dict.getReal("LW")
            // /D is [dashArray dashPhase] (ISO 32000-1, 8.4.5, Table 58, #107).
            val dash = dict.getArray("D", refs)
            fun number(o: Any?): Double = when (o) {
                is io.github.yuroyami.kitepdf.core.parser.PdfInt -> o.value.toDouble()
                is io.github.yuroyami.kitepdf.core.parser.PdfReal -> o.value
                else -> 0.0
            }
            val dashArray = (dash?.getOrNull(0)?.resolve(refs) as? PdfArray)?.map { number(it) }
            return ExtGState(
                fillAlpha = fillAlpha?.coerceIn(0.0, 1.0),
                strokeAlpha = strokeAlpha?.coerceIn(0.0, 1.0),
                blendMode = blendMode,
                softMask = smask,
                lineWidth = lw,
                lineCap = dict.getInt("LC")?.toInt(),
                lineJoin = dict.getInt("LJ")?.toInt(),
                miterLimit = dict.getReal("ML"),
                dashArray = dashArray,
                dashPhase = number(dash?.getOrNull(1)),
            )
        }

        private fun parseSoftMask(value: PdfObject?, refs: IndirectResolver): SoftMask? {
            // The mask dictionary may be an indirect object (ISO 32000-1, 7.3.10).
            return when (val mask = deref(value, refs)) {
                is PdfName -> if (mask.value == "None") SoftMask.None else null
                is PdfDictionary -> {
                    val subtype = mask.getName("S") ?: "Luminosity"
                    val groupStream = deref(mask["G"], refs) as? PdfStream
                    groupStream?.let {
                        val kind = if (subtype == "Alpha") SoftMask.Kind.Alpha else SoftMask.Kind.Luminosity
                        // /BC and /TR (ISO 32000-1, 11.6.5.2, Table 144, #68).
                        val backdrop = (deref(mask["BC"], refs) as? PdfArray)?.map { c ->
                            when (val n = deref(c, refs)) {
                                is PdfInt -> n.value.toDouble()
                                is PdfReal -> n.value
                                else -> 0.0
                            }
                        }
                        SoftMask.MaskGroup(kind, it, backdrop, parseTransfer(mask["TR"], refs))
                    }
                }
                else -> null
            }
        }

        /**
         * The /TR function of a soft mask, or null for the identity. The name /Identity and a
         * function without exactly one output both mean the identity (ISO 32000-1, Table 144).
         */
        private fun parseTransfer(value: PdfObject?, refs: IndirectResolver): KiteFunction? {
            val resolved = deref(value, refs) ?: return null
            if (resolved is PdfName) return null
            return KiteFunction.parse(resolved, refs)?.takeIf { it.outputCount == 1 }
        }

        /** [value], or the object it refers to. A reference to a missing object reads as null. */
        private fun deref(value: PdfObject?, refs: IndirectResolver): PdfObject? =
            if (value is PdfReference) refs.resolve(value) else value
    }
}

/**
 * Soft mask source (ISO 32000-1 §11.6.5). Either explicitly disabled (`None`)
 * or a [MaskGroup] containing a Form XObject whose rendered luminosity or
 * alpha channel becomes the per-pixel mask.
 */
public sealed class SoftMask {
    public object None : SoftMask()

    /**
     * A mask made from the transparency group [group] (ISO 32000-1, 11.6.5.2, Table 144).
     *
     * @property backdrop The /BC components: the colour, in the colour space of the group,
     *   that a luminosity mask composites the group over. Null means black.
     * @property transfer The /TR function that maps each value of the group, its alpha or
     *   its luminosity, to the mask value. Null means the identity.
     */
    public data class MaskGroup(
        val kind: Kind,
        val group: PdfStream,
        val backdrop: List<Double>? = null,
        val transfer: KiteFunction? = null,
    ) : SoftMask()

    public enum class Kind { Luminosity, Alpha }
}
