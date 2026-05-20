package com.yuroyami.kitepdf.render

import com.yuroyami.kitepdf.parser.IndirectResolver
import com.yuroyami.kitepdf.parser.PdfArray
import com.yuroyami.kitepdf.parser.PdfDictionary
import com.yuroyami.kitepdf.parser.PdfInt
import com.yuroyami.kitepdf.parser.PdfObject
import com.yuroyami.kitepdf.parser.PdfReal
import com.yuroyami.kitepdf.parser.PdfReference
import com.yuroyami.kitepdf.parser.PdfStream
import kotlin.math.pow

/**
 * A PDF function (ISO 32000-1 §7.10). Functions map an n-component input to
 * an m-component output, typically used by shadings, transparency groups,
 * tint transforms, and halftone phase calculations.
 *
 * KitePDF v0.0.x supports:
 *   - **Type 2** (exponential interpolation, §7.10.3) — the most common case;
 *     used heavily by axial/radial shadings.
 *   - **Type 3** (stitching, §7.10.4) — a piecewise combinator that glues
 *     subordinate functions back-to-back.
 *
 * Type 0 (sampled) and Type 4 (PostScript calculator) parse but evaluate to
 * zeros; they're called out in the README as future work.
 */
sealed class PdfFunction {

    /** Input bounds — paired `[min0, max0, min1, max1, …]`. */
    abstract val domain: DoubleArray

    /** Optional output bounds; null = unclipped. */
    abstract val range: DoubleArray?

    /** Number of output components. */
    abstract val outputCount: Int

    /** Evaluate `f(input) → output`. The output length is [outputCount]. */
    fun evaluate(input: DoubleArray): DoubleArray {
        val clamped = DoubleArray(input.size)
        for (i in input.indices) {
            val lo = domain.getOrElse(2 * i) { 0.0 }
            val hi = domain.getOrElse(2 * i + 1) { 1.0 }
            clamped[i] = input[i].coerceIn(lo, hi)
        }
        val raw = evaluateInternal(clamped)
        val r = range ?: return raw
        for (i in raw.indices) {
            val lo = r.getOrElse(2 * i) { Double.NEGATIVE_INFINITY }
            val hi = r.getOrElse(2 * i + 1) { Double.POSITIVE_INFINITY }
            raw[i] = raw[i].coerceIn(lo, hi)
        }
        return raw
    }

    protected abstract fun evaluateInternal(input: DoubleArray): DoubleArray

    /**
     * Exponential interpolation. Maps a single-component input `x` in
     * `[domain[0], domain[1]]` to `c0 + x^N * (c1 - c0)` per output channel.
     * The `N` ("exponent") of 1 yields linear interpolation; >1 biases
     * toward `c0`; <1 toward `c1`.
     */
    data class Type2(
        override val domain: DoubleArray,
        override val range: DoubleArray?,
        val c0: DoubleArray,
        val c1: DoubleArray,
        val n: Double,
    ) : PdfFunction() {
        override val outputCount: Int get() = c0.size

        override fun evaluateInternal(input: DoubleArray): DoubleArray {
            val x = input.getOrElse(0) { 0.0 }
            val xn = x.pow(n)
            return DoubleArray(c0.size) { i -> c0[i] + xn * (c1[i] - c0[i]) }
        }

        override fun equals(other: Any?): Boolean =
            other is Type2 && domain.contentEquals(other.domain) &&
                (range?.contentEquals(other.range ?: doubleArrayOf()) ?: (other.range == null)) &&
                c0.contentEquals(other.c0) && c1.contentEquals(other.c1) && n == other.n
        override fun hashCode(): Int {
            var h = domain.contentHashCode()
            h = 31 * h + (range?.contentHashCode() ?: 0)
            h = 31 * h + c0.contentHashCode()
            h = 31 * h + c1.contentHashCode()
            h = 31 * h + n.hashCode()
            return h
        }
    }

    /**
     * Stitching function. A piecewise function that delegates to one of
     * [functions] based on `x`'s position relative to [bounds], remapping
     * `x` into the corresponding subfunction's domain via [encode].
     */
    data class Type3(
        override val domain: DoubleArray,
        override val range: DoubleArray?,
        val functions: List<PdfFunction>,
        /** k-1 inner breakpoints; outer bounds come from [domain]. */
        val bounds: DoubleArray,
        /** 2k-long re-mapping pairs: `[in0_lo, in0_hi, in1_lo, in1_hi, …]`. */
        val encode: DoubleArray,
    ) : PdfFunction() {
        override val outputCount: Int get() = functions.firstOrNull()?.outputCount ?: 0

        override fun evaluateInternal(input: DoubleArray): DoubleArray {
            val x = input.getOrElse(0) { 0.0 }
            val lo = domain.getOrElse(0) { 0.0 }
            val hi = domain.getOrElse(1) { 1.0 }
            // Find the subfunction index for x.
            var idx = 0
            while (idx < bounds.size && x >= bounds[idx]) idx++
            val sub = functions.getOrNull(idx) ?: return DoubleArray(outputCount)
            // Remap x into the subfunction's expected input range.
            val subLo = if (idx == 0) lo else bounds[idx - 1]
            val subHi = if (idx == bounds.size) hi else bounds[idx]
            val eLo = encode.getOrElse(2 * idx) { 0.0 }
            val eHi = encode.getOrElse(2 * idx + 1) { 1.0 }
            val remapped = if (subHi == subLo) eLo
                else eLo + (x - subLo) * (eHi - eLo) / (subHi - subLo)
            return sub.evaluate(doubleArrayOf(remapped))
        }

        override fun equals(other: Any?): Boolean = other is Type3 &&
            domain.contentEquals(other.domain) && functions == other.functions &&
            bounds.contentEquals(other.bounds) && encode.contentEquals(other.encode)
        override fun hashCode(): Int = 31 * (31 * domain.contentHashCode() +
            functions.hashCode()) + bounds.contentHashCode()
    }

    /**
     * Placeholder for function types we don't fully evaluate (Type 0 sampled,
     * Type 4 PostScript calculator). Returns an all-zero output of the right
     * length so callers don't crash; shadings using these degrade to flat
     * black (or to the background color, if any).
     */
    data class Unsupported(
        val type: Int,
        override val domain: DoubleArray,
        override val range: DoubleArray?,
        override val outputCount: Int,
    ) : PdfFunction() {
        override fun evaluateInternal(input: DoubleArray): DoubleArray = DoubleArray(outputCount)
    }

    companion object {

        /** Parse a /Function entry. Returns null if the object isn't a function. */
        fun parse(obj: PdfObject?, refs: IndirectResolver): PdfFunction? {
            val resolved = when (obj) {
                is PdfReference -> refs.resolve(obj)
                else -> obj
            } ?: return null
            return when (resolved) {
                is PdfStream -> parseDict(resolved.dict, refs)
                is PdfDictionary -> parseDict(resolved, refs)
                else -> null
            }
        }

        private fun parseDict(dict: PdfDictionary, refs: IndirectResolver): PdfFunction? {
            val type = dict.getInt("FunctionType")?.toInt() ?: return null
            val domain = (dict.getArray("Domain"))?.toDoubleArrayOrNull() ?: doubleArrayOf(0.0, 1.0)
            val range = (dict.getArray("Range"))?.toDoubleArrayOrNull()
            return when (type) {
                2 -> {
                    val c0 = (dict.getArray("C0"))?.toDoubleArrayOrNull() ?: doubleArrayOf(0.0)
                    val c1 = (dict.getArray("C1"))?.toDoubleArrayOrNull() ?: doubleArrayOf(1.0)
                    val n = (dict.getReal("N")) ?: 1.0
                    Type2(domain, range, c0, c1, n)
                }
                3 -> {
                    val functions = (dict.getArray("Functions", refs))?.mapNotNull { parse(it, refs) }
                        ?: emptyList()
                    val bounds = (dict.getArray("Bounds"))?.toDoubleArrayOrNull() ?: doubleArrayOf()
                    val encode = (dict.getArray("Encode"))?.toDoubleArrayOrNull() ?: doubleArrayOf()
                    val output = functions.firstOrNull()?.outputCount ?: 0
                    Type3(domain, range, functions, bounds, encode).also { check(it.outputCount == output) }
                }
                else -> Unsupported(
                    type, domain, range,
                    outputCount = range?.let { it.size / 2 } ?: 1,
                )
            }
        }

        private fun PdfArray.toDoubleArrayOrNull(): DoubleArray? {
            val out = DoubleArray(size)
            for (i in 0 until size) {
                out[i] = when (val v = this[i]) {
                    is PdfReal -> v.value
                    is PdfInt -> v.value.toDouble()
                    else -> return null
                }
            }
            return out
        }
    }
}
