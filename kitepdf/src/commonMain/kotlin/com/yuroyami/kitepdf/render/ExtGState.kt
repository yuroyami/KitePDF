package com.yuroyami.kitepdf.render

import com.yuroyami.kitepdf.parser.IndirectResolver
import com.yuroyami.kitepdf.parser.PdfArray
import com.yuroyami.kitepdf.parser.PdfDictionary
import com.yuroyami.kitepdf.parser.PdfInt
import com.yuroyami.kitepdf.parser.PdfName
import com.yuroyami.kitepdf.parser.PdfReal
import com.yuroyami.kitepdf.parser.PdfStream

/**
 * Parsed Extended Graphics State (ISO 32000-1 §8.4.5).
 *
 * Each `/ExtGState /<name>` resource is a dict of state-modifier entries.
 * The `gs` content-stream operator merges them into the current
 * [GraphicsState]. We extract only the fields we actually act on:
 *
 *   - `/CA` — stroke alpha (0..1)
 *   - `/ca` — fill alpha (0..1)
 *   - `/BM` — blend mode (name or array of names)
 *   - `/SMask` — soft-mask dict ("None" / Mask dict)
 *   - `/LW` `/LC` `/LJ` `/ML` `/D` — line state we honor where Compose has equivalents
 *   - `/AIS`, `/SA`, `/OP`, `/op`, `/OPM`, `/Font`, `/RI` — accepted but ignored (rare)
 *
 * Missing fields stay at their previous values; that's the spec's
 * "ExtGState modifies the current state" rule.
 */
data class ExtGState(
    val fillAlpha: Double? = null,
    val strokeAlpha: Double? = null,
    val blendMode: BlendMode? = null,
    val softMask: SoftMask? = null,
    val lineWidth: Double? = null,
) {

    companion object {

        fun parse(dict: PdfDictionary, refs: IndirectResolver): ExtGState {
            val fillAlpha = dict.getReal("ca")
            val strokeAlpha = dict.getReal("CA")
            val blendMode = when (val bm = dict["BM"]) {
                is PdfName -> BlendMode.parse(bm.value)
                is PdfArray -> BlendMode.parse((bm.firstOrNull() as? PdfName)?.value)
                else -> null
            }
            val smask = parseSoftMask(dict["SMask"], refs)
            val lw = dict.getReal("LW")
            return ExtGState(
                fillAlpha = fillAlpha?.coerceIn(0.0, 1.0),
                strokeAlpha = strokeAlpha?.coerceIn(0.0, 1.0),
                blendMode = blendMode,
                softMask = smask,
                lineWidth = lw,
            )
        }

        private fun parseSoftMask(value: Any?, refs: IndirectResolver): SoftMask? {
            return when (value) {
                is PdfName -> if (value.value == "None") SoftMask.None else null
                is PdfDictionary -> {
                    val subtype = value.getName("S") ?: "Luminosity"
                    val groupObj = value["G"]
                    val groupStream = when (groupObj) {
                        is PdfStream -> groupObj
                        is com.yuroyami.kitepdf.parser.PdfReference ->
                            (refs.resolve(groupObj) as? PdfStream)
                        else -> null
                    }
                    groupStream?.let {
                        val kind = if (subtype == "Alpha") SoftMask.Kind.Alpha else SoftMask.Kind.Luminosity
                        SoftMask.MaskGroup(kind, it)
                    }
                }
                else -> null
            }
        }
    }
}

/**
 * Soft mask source (ISO 32000-1 §11.6.5). Either explicitly disabled (`None`)
 * or a [MaskGroup] containing a Form XObject whose rendered luminosity or
 * alpha channel becomes the per-pixel mask.
 */
sealed class SoftMask {
    object None : SoftMask()
    data class MaskGroup(val kind: Kind, val group: PdfStream) : SoftMask()
    enum class Kind { Luminosity, Alpha }
}
