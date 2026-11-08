package io.github.yuroyami.kitepdf.core.render

import io.github.yuroyami.kitepdf.core.parser.IndirectResolver
import io.github.yuroyami.kitepdf.core.parser.PdfArray
import io.github.yuroyami.kitepdf.core.parser.PdfBoolean
import io.github.yuroyami.kitepdf.core.parser.PdfDictionary
import io.github.yuroyami.kitepdf.core.parser.PdfInt
import io.github.yuroyami.kitepdf.core.parser.PdfName
import io.github.yuroyami.kitepdf.core.parser.PdfObject
import io.github.yuroyami.kitepdf.core.parser.PdfReal
import io.github.yuroyami.kitepdf.core.parser.PdfReference
import io.github.yuroyami.kitepdf.core.parser.PdfStream
import io.github.yuroyami.kitepdf.core.parser.withDirectValues

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
 *   - `/RI` `/UseBlackPtComp`: rendering intent and black point compensation
 *   - `/OP` `/op` `/OPM`: overprint for strokes and fills, and the overprint mode
 *   - `/AIS`, `/SA`, `/Font`: accepted but ignored (rare)
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
    /** `/RI`, or null when the dictionary sets no rendering intent. */
    val renderingIntent: KiteRenderingIntent? = null,
    /**
     * `/UseBlackPtComp` of ISO 32000-2, 8.4.5: true for `/ON`, false for `/OFF` and `/Default`,
     * as MuPDF reads it, or null when the dictionary does not set it.
     */
    val blackPointCompensation: Boolean? = null,
    /** `/OP`: overprint for strokes, or null when the dictionary does not set it. */
    val overprintStroke: Boolean? = null,
    /** `/op`: overprint for fills and other paints. When it is absent, `/OP` sets it too (ISO 32000-1, Table 58). */
    val overprintFill: Boolean? = null,
    /** `/OPM`: the overprint mode, 0 or 1, or null when the dictionary does not set it. */
    val overprintMode: Int? = null,
    /** True when `/TR2` or `/TR` sets the transfer function, which [transfer] then holds, null for the identity (#909). */
    val setsTransfer: Boolean = false,
    /** The transfer function that `/TR2`, or else `/TR`, sets; null for `/Identity` and `/Default`. */
    val transfer: KiteTransfer? = null,
) {

    public companion object {

        public fun parse(dictionary: PdfDictionary, refs: IndirectResolver): ExtGState {
            // Its numbers and names may be indirect objects (ISO 32000-1, 7.3.10; #777).
            val dict = dictionary.withDirectValues(refs)
            val fillAlpha = dict.getReal("ca")
            val strokeAlpha = dict.getReal("CA")
            val blendMode = when (val bm = dict["BM"]) {
                is PdfName -> KiteBlendMode.parse(bm.value)
                // Table 58: the first mode in the array that the reader recognizes, or Normal when
                // none is; the first name alone made /BM [/Foo /Multiply] blend as Normal (#782).
                is PdfArray -> bm.firstNotNullOfOrNull { (it as? PdfName)?.value?.let(KiteBlendMode::parseOrNull) } ?: KiteBlendMode.Normal
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
            val overprint = (deref(dict["OP"], refs) as? PdfBoolean)?.value
            // /TR2 wins over /TR (ISO 32000-1, Table 58). An entry that does not read sets nothing.
            val transferSetting = (dict["TR2"]?.let { graphicsTransfer(it, refs) } ?: dict["TR"]?.let { graphicsTransfer(it, refs) })
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
                renderingIntent = (deref(dict["RI"], refs) as? PdfName)?.let { KiteRenderingIntent.fromPdfName(it.value) },
                blackPointCompensation = (deref(dict["UseBlackPtComp"], refs) as? PdfName)?.let { it.value == "ON" },
                overprintStroke = overprint,
                overprintFill = (deref(dict["op"], refs) as? PdfBoolean)?.value ?: overprint,
                // MuPDF reads any mode other than 0 as mode 1.
                overprintMode = (deref(dict["OPM"], refs) as? PdfInt)?.let { if (it.value == 0L) 0 else 1 },
                setsTransfer = transferSetting != null,
                transfer = transferSetting?.getOrNull(),
            )
        }

        /**
         * A transfer function of the graphics state (#909): a success holding null for the names
         * `/Identity` and `/Default`, the tables of one function or of an array of four, and null for
         * an entry that does not read, which leaves the transfer as it was.
         */
        private fun graphicsTransfer(value: PdfObject, refs: IndirectResolver): Result<KiteTransfer?>? {
            fun table(o: PdfObject?): KiteMaskTransfer? {
                val r = deref(o, refs) ?: return null
                if (r is PdfName) return if (r.value == "Identity" || r.value == "Default") KiteMaskTransfer.of { it } else null
                val f = KiteFunction.parse(r, refs)?.takeIf { it.outputCount == 1 } ?: return null
                return KiteMaskTransfer.of { f.evaluate(doubleArrayOf(it))[0] }
            }
            return when (val v = deref(value, refs)) {
                is PdfName -> if (v.value == "Identity" || v.value == "Default") Result.success(null) else null
                is PdfArray -> {
                    if (v.size < 4) return null
                    val tables = (0 until 3).map { table(v[it]) ?: return null }
                    Result.success(KiteTransfer(tables[0], tables[1], tables[2]))
                }
                null -> null
                else -> table(v)?.let { Result.success(KiteTransfer(it, it, it)) }
            }
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

    /**
     * What of the mask group becomes the mask value, and with which weights a luminosity mask reads
     * a colour. A PDF luminosity mask (ISO 32000-1, 11.5.3) weighs a colour as 10.3.2 and `Lum` in
     * 11.3.5.3 do; a CSS luminance mask, which an SVG `mask` element makes, weighs it with the
     * `luminanceToAlpha` row of Filter Effects 1 (CSS Masking 1, 7.1; #799, #821).
     */
    public enum class Kind(
        /** The weight of red in the mask value; 0 for [Alpha]. */
        public val red: Double,
        /** The weight of green in the mask value; 0 for [Alpha]. */
        public val green: Double,
        /** The weight of blue in the mask value; 0 for [Alpha]. */
        public val blue: Double,
    ) {
        /** The luminosity of the group's colour, 0.30 R + 0.59 G + 0.11 B, over its backdrop. */
        Luminosity(0.30, 0.59, 0.11),

        /** The group's alpha. */
        Alpha(0.0, 0.0, 0.0),

        /** The luminance of the content, 0.2125 R + 0.7154 G + 0.0721 B, as CSS Masking 1 computes it. */
        CssLuminance(0.2125, 0.7154, 0.0721),
        ;

        /** True when the colour of the group, not its alpha, makes the mask. */
        public val readsColor: Boolean get() = this != Alpha

        /** The mask value from 0 to 255 of a colour whose channels run from 0 to 255. */
        public fun levelOf(r: Int, g: Int, b: Int): Int = (r * red + g * green + b * blue + 0.5).toInt().coerceIn(0, 255)
    }
}
