package io.github.yuroyami.kitepdf.core.render

import io.github.yuroyami.kitepdf.core.filters.FilterChain
import io.github.yuroyami.kitepdf.core.parser.IndirectResolver
import io.github.yuroyami.kitepdf.core.parser.PdfArray
import io.github.yuroyami.kitepdf.core.parser.PdfDictionary
import io.github.yuroyami.kitepdf.core.parser.PdfInt
import io.github.yuroyami.kitepdf.core.parser.PdfObject
import io.github.yuroyami.kitepdf.core.parser.PdfReal
import io.github.yuroyami.kitepdf.core.parser.PdfReference
import io.github.yuroyami.kitepdf.core.parser.PdfStream
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.truncate

/**
 * A PDF function (ISO 32000-1 §7.10). Functions map an n-component input to
 * an m-component output, used by shadings, transparency groups, tint transforms
 * and halftone calculations.
 *
 * Supported types:
 *   - **Type 0** sampled (§7.10.2): multidimensional sample table with
 *     multilinear interpolation.
 *   - **Type 2** exponential interpolation (§7.10.3).
 *   - **Type 3** stitching (§7.10.4).
 *   - **Type 4** PostScript calculator (§7.10.5): a small stack interpreter.
 *
 * An array of n single-output functions (as some shadings / tint transforms
 * use) is wrapped in [ArrayCombination].
 */
public sealed class KiteFunction {

    /** Input bounds: paired `[min0, max0, min1, max1, …]`. */
    public abstract val domain: DoubleArray

    /** Optional output bounds; null = unclipped. */
    public abstract val range: DoubleArray?

    /** Number of output components. */
    public abstract val outputCount: Int

    /** Evaluate `f(input) → output`. The output length is [outputCount]. */
    public fun evaluate(input: DoubleArray): DoubleArray {
        val clamped = DoubleArray(input.size)
        for (i in input.indices) {
            val lo = domain.getOrElse(2 * i) { 0.0 }
            val hi = domain.getOrElse(2 * i + 1) { 1.0 }
            clamped[i] = input[i].coerceIn(minOf(lo, hi), maxOf(lo, hi))
        }
        // A corrupt function degrades to zeros instead of aborting the page
        // (#128), and a non-finite output never reaches a colour space (#151).
        val raw = try {
            evaluateInternal(clamped)
        } catch (_: RuntimeException) {
            DoubleArray(outputCount)
        }
        for (i in raw.indices) if (!raw[i].isFinite()) raw[i] = 0.0
        val r = range ?: return raw
        for (i in raw.indices) {
            val lo = r.getOrElse(2 * i) { Double.NEGATIVE_INFINITY }
            val hi = r.getOrElse(2 * i + 1) { Double.POSITIVE_INFINITY }
            raw[i] = raw[i].coerceIn(minOf(lo, hi), maxOf(lo, hi))
        }
        return raw
    }

    protected abstract fun evaluateInternal(input: DoubleArray): DoubleArray

    /**
     * Type 0 sampled function. A regular sample grid (one value per output per
     * grid point) interpolated multilinearly. The common case is 1 input
     * (shading colour ramps) but tint transforms use up to 4.
     */
    public class Type0(
        override val domain: DoubleArray,
        override val range: DoubleArray?,
        private val size: IntArray,
        private val bitsPerSample: Int,
        private val encode: DoubleArray,
        private val decode: DoubleArray,
        private val samples: ByteArray,
    ) : KiteFunction() {
        private val m = size.size
        private val n = (range?.size ?: 2) / 2
        override val outputCount: Int get() = n

        private val maxSample = ((1L shl bitsPerSample) - 1).toDouble()

        override fun evaluateInternal(input: DoubleArray): DoubleArray {
            // Map each input to a (fractional) grid coordinate in [0, size_i - 1].
            val e = DoubleArray(m)
            for (i in 0 until m) {
                val dlo = domain.getOrElse(2 * i) { 0.0 }
                val dhi = domain.getOrElse(2 * i + 1) { 1.0 }
                val elo = encode.getOrElse(2 * i) { 0.0 }
                val ehi = encode.getOrElse(2 * i + 1) { (size[i] - 1).toDouble() }
                val x = input.getOrElse(i) { 0.0 }
                val mapped = if (dhi == dlo) elo else elo + (x - dlo) * (ehi - elo) / (dhi - dlo)
                e[i] = mapped.coerceIn(0.0, (size[i] - 1).toDouble())
            }

            val lo = IntArray(m) { floor(e[it]).toInt() }
            val frac = DoubleArray(m) { e[it] - lo[it] }
            val out = DoubleArray(n)

            // Multilinear interpolation over the 2^m surrounding grid corners.
            val corners = 1 shl m
            for (c in 0 until corners) {
                var weight = 1.0
                val idx = IntArray(m)
                for (i in 0 until m) {
                    val bit = (c ushr i) and 1
                    idx[i] = (lo[i] + bit).coerceAtMost(size[i] - 1)
                    weight *= if (bit == 1) frac[i] else (1.0 - frac[i])
                }
                if (weight == 0.0) continue
                val base = flatIndex(idx) * n
                for (j in 0 until n) {
                    val raw = readSample(base + j)
                    val dlo = decode.getOrElse(2 * j) { 0.0 }
                    val dhi = decode.getOrElse(2 * j + 1) { 1.0 }
                    out[j] += weight * (dlo + raw / maxSample * (dhi - dlo))
                }
            }
            return out
        }

        /** Flat grid index with the first input dimension varying fastest. */
        private fun flatIndex(idx: IntArray): Int {
            var flat = 0
            var stride = 1
            for (i in 0 until m) { flat += idx[i] * stride; stride *= size[i] }
            return flat
        }

        private fun readSample(sampleIndex: Int): Double {
            val bitPos = sampleIndex.toLong() * bitsPerSample
            var v = 0L
            var p = bitPos
            repeat(bitsPerSample) {
                val byteIdx = (p ushr 3).toInt()
                val shift = 7 - (p and 7).toInt()
                val b = if (byteIdx < samples.size) (samples[byteIdx].toInt() shr shift) and 1 else 0
                v = (v shl 1) or b.toLong()
                p++
            }
            return v.toDouble()
        }
    }

    /**
     * Exponential interpolation: `c0 + x^N * (c1 - c0)` per output channel.
     */
    public class Type2(
        override val domain: DoubleArray,
        override val range: DoubleArray?,
        public val c0: DoubleArray,
        public val c1: DoubleArray,
        public val n: Double,
    ) : KiteFunction() {
        override val outputCount: Int get() = c0.size
        override fun evaluateInternal(input: DoubleArray): DoubleArray {
            val x = input.getOrElse(0) { 0.0 }
            val xn = x.pow(n)
            // A /C1 shorter than /C0 takes the spec default of 1 for the rest.
            return DoubleArray(c0.size) { i -> c0[i] + xn * (c1.getOrElse(i) { 1.0 } - c0[i]) }
        }
    }

    /**
     * Stitching function: delegates to one of [functions] by `x`'s position
     * relative to [bounds], remapping `x` via [encode].
     */
    public class Type3(
        override val domain: DoubleArray,
        override val range: DoubleArray?,
        public val functions: List<KiteFunction>,
        public val bounds: DoubleArray,
        public val encode: DoubleArray,
    ) : KiteFunction() {
        // ISO 32000-1, 7.10.4: /Range is optional for a stitching function, so
        // when present it decides the output count, not the first sub-function.
        override val outputCount: Int get() = range?.let { it.size / 2 } ?: functions.firstOrNull()?.outputCount ?: 0
        override fun evaluateInternal(input: DoubleArray): DoubleArray {
            val x = input.getOrElse(0) { 0.0 }
            val lo = domain.getOrElse(0) { 0.0 }
            val hi = domain.getOrElse(1) { 1.0 }
            var idx = 0
            while (idx < bounds.size && x >= bounds[idx]) idx++
            val sub = functions.getOrNull(idx) ?: return DoubleArray(outputCount)
            val subLo = if (idx == 0) lo else bounds[idx - 1]
            val subHi = if (idx == bounds.size) hi else bounds[idx]
            val eLo = encode.getOrElse(2 * idx) { 0.0 }
            val eHi = encode.getOrElse(2 * idx + 1) { 1.0 }
            val remapped = if (subHi == subLo) eLo else eLo + (x - subLo) * (eHi - eLo) / (subHi - subLo)
            val out = sub.evaluate(doubleArrayOf(remapped))
            val n = outputCount
            return if (out.size == n) out else DoubleArray(n) { out.getOrElse(it) { 0.0 } }
        }
    }

    /**
     * Type 4 PostScript calculator function. The program is compiled once into
     * a nested token tree; [evaluateInternal] runs it on a small operand stack.
     */
    public class Type4(
        override val domain: DoubleArray,
        override val range: DoubleArray?,
        private val program: List<Any>,
    ) : KiteFunction() {
        override val outputCount: Int get() = (range?.size ?: 2) / 2

        override fun evaluateInternal(input: DoubleArray): DoubleArray {
            val stack = ArrayList<Any>(16)
            for (v in input) stack.add(v)
            PostScriptCalc.exec(program, stack)
            val n = outputCount
            val out = DoubleArray(n)
            // The last n numbers on the stack are the outputs (out[0] deepest).
            val nums = stack.mapNotNull { PostScriptCalc.number(it) }
            val start = (nums.size - n).coerceAtLeast(0)
            for (j in 0 until n) out[j] = nums.getOrElse(start + j) { 0.0 }
            return out
        }
    }

    /**
     * An array of single-output functions evaluated in parallel, producing one
     * output component each (shadings / tint transforms sometimes use this form).
     */
    public class ArrayCombination(
        override val domain: DoubleArray,
        private val parts: List<KiteFunction>,
    ) : KiteFunction() {
        override val range: DoubleArray? = null
        override val outputCount: Int get() = parts.size
        override fun evaluateInternal(input: DoubleArray): DoubleArray =
            DoubleArray(parts.size) { i -> parts[i].evaluate(input).getOrElse(0) { 0.0 } }
    }

    /** Function type we don't model; returns zeros of the right length. */
    public class Unsupported(
        public val type: Int,
        override val domain: DoubleArray,
        override val range: DoubleArray?,
        override val outputCount: Int,
    ) : KiteFunction() {
        override fun evaluateInternal(input: DoubleArray): DoubleArray = DoubleArray(outputCount)
    }

    public companion object {

        /** Parse a /Function entry (dict, stream, or an array of functions). */
        public fun parse(obj: PdfObject?, refs: IndirectResolver): KiteFunction? {
            val resolved = when (obj) {
                is PdfReference -> refs.resolve(obj)
                else -> obj
            } ?: return null
            return when (resolved) {
                is PdfArray -> {
                    val parts = resolved.mapNotNull { parse(it, refs) }
                    if (parts.isEmpty()) null
                    else ArrayCombination(parts.first().domain, parts)
                }
                is PdfStream -> parseTyped(resolved.dict, resolved, refs)
                is PdfDictionary -> parseTyped(resolved, null, refs)
                else -> null
            }
        }

        private fun parseTyped(dict: PdfDictionary, stream: PdfStream?, refs: IndirectResolver): KiteFunction? {
            val type = dict.getInt("FunctionType")?.toInt() ?: return null
            val domain = dict.getArray("Domain")?.toDoubleArrayOrNull() ?: doubleArrayOf(0.0, 1.0)
            val range = dict.getArray("Range")?.toDoubleArrayOrNull()
            return when (type) {
                0 -> {
                    val s = stream ?: return null
                    val size = dict.getArray("Size")?.let { IntArray(it.size) { i -> it.intAt(i) } } ?: return null
                    val bps = dict.getInt("BitsPerSample")?.toInt() ?: return null
                    val rng = range ?: return null
                    val n = rng.size / 2
                    val encode = dict.getArray("Encode")?.toDoubleArrayOrNull()
                        ?: DoubleArray(size.size * 2) { k -> if (k % 2 == 0) 0.0 else (size[k / 2] - 1).toDouble() }
                    val decode = dict.getArray("Decode")?.toDoubleArrayOrNull() ?: rng.copyOf()
                    val samples = runCatching { FilterChain.decode(s) }.getOrNull() ?: return null
                    if (n == 0) return null
                    Type0(domain, range, size, bps, encode, decode, samples)
                }
                2 -> {
                    val c0 = dict.getArray("C0")?.toDoubleArrayOrNull() ?: doubleArrayOf(0.0)
                    val c1 = dict.getArray("C1")?.toDoubleArrayOrNull() ?: doubleArrayOf(1.0)
                    val n = dict.getReal("N") ?: 1.0
                    Type2(domain, range, c0, c1, n)
                }
                3 -> {
                    val functions = dict.getArray("Functions", refs)?.mapNotNull { parse(it, refs) } ?: emptyList()
                    val bounds = dict.getArray("Bounds")?.toDoubleArrayOrNull() ?: doubleArrayOf()
                    val encode = dict.getArray("Encode")?.toDoubleArrayOrNull() ?: doubleArrayOf()
                    Type3(domain, range, functions, bounds, encode)
                }
                4 -> {
                    val s = stream ?: return Unsupported(4, domain, range, range?.let { it.size / 2 } ?: 1)
                    val src = runCatching { FilterChain.decode(s).decodeToString() }.getOrNull()
                        ?: return Unsupported(4, domain, range, range?.let { it.size / 2 } ?: 1)
                    val program = runCatching { PostScriptCalc.compile(src) }.getOrNull()
                        ?: return Unsupported(4, domain, range, range?.let { it.size / 2 } ?: 1)
                    Type4(domain, range, program)
                }
                else -> Unsupported(type, domain, range, range?.let { it.size / 2 } ?: 1)
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

        private fun PdfArray.intAt(i: Int): Int = when (val v = this[i]) {
            is PdfInt -> v.value.toInt()
            is PdfReal -> v.value.toInt()
            else -> 0
        }
    }
}

/**
 * Minimal PostScript calculator (ISO 32000-1 §7.10.5). Compiles a `{ … }`
 * program into a token tree (numbers, operator names, nested procedure blocks)
 * and executes it on an operand stack of Doubles and blocks.
 */
internal object PostScriptCalc {

    fun compile(source: String): List<Any> {
        val tokens = tokenize(source)
        val pos = intArrayOf(0)
        // Skip to the outer '{'.
        while (pos[0] < tokens.size && tokens[pos[0]] != "{") pos[0]++
        if (pos[0] >= tokens.size) return parseBlockBody(tokens, intArrayOf(0))
        pos[0]++  // consume '{'
        return parseBlockBody(tokens, pos)
    }

    private fun tokenize(s: String): List<String> {
        val out = ArrayList<String>()
        var i = 0
        while (i < s.length) {
            val c = s[i]
            when {
                c == '{' || c == '}' -> { out.add(c.toString()); i++ }
                c == '%' -> { while (i < s.length && s[i] != '\n' && s[i] != '\r') i++ }
                c.isWhitespace() -> i++
                else -> {
                    val start = i
                    while (i < s.length && !s[i].isWhitespace() && s[i] != '{' && s[i] != '}' && s[i] != '%') i++
                    out.add(s.substring(start, i))
                }
            }
        }
        return out
    }

    /** Parse tokens until the matching '}' (or end); returns the block body. */
    private fun parseBlockBody(tokens: List<String>, pos: IntArray): List<Any> {
        val body = ArrayList<Any>()
        while (pos[0] < tokens.size) {
            val t = tokens[pos[0]]
            when (t) {
                "}" -> { pos[0]++; return body }
                "{" -> { pos[0]++; body.add(parseBlockBody(tokens, pos)) }
                else -> {
                    pos[0]++
                    val num = t.toDoubleOrNull()
                    body.add(num ?: t)
                }
            }
        }
        return body
    }

    fun exec(program: List<Any>, stack: ArrayList<Any>) {
        for (tok in program) {
            when (tok) {
                is Double -> stack.add(tok)
                is List<*> -> @Suppress("UNCHECKED_CAST") stack.add(tok as List<Any>)
                is String -> applyOperator(tok, stack)
            }
        }
    }

    // Every pop tolerates an empty stack: ISO 32000-1, 7.10.5 leaves an underflow
    // undefined, and one corrupt program must not abort the page (#128).
    private fun ArrayList<Any>.popAny(): Any = if (isEmpty()) 0.0 else removeAt(size - 1)
    private fun ArrayList<Any>.popNum(): Double = number(popAny()) ?: 0.0
    private fun ArrayList<Any>.popBool(): Boolean = when (val v = popAny()) {
        is Boolean -> v
        is Double -> v != 0.0
        else -> false
    }
    @Suppress("UNCHECKED_CAST")
    private fun ArrayList<Any>.popBlock(): List<Any> = (popAny() as? List<Any>) ?: emptyList()
    /** A stack value as a number: a boolean counts as 1 or 0, a procedure as nothing. */
    fun number(v: Any): Double? = when (v) {
        is Double -> v
        is Boolean -> if (v) 1.0 else 0.0
        else -> null
    }

    private fun applyOperator(op: String, s: ArrayList<Any>) {
        when (op) {
            "add" -> { val b = s.popNum(); val a = s.popNum(); s.add(a + b) }
            "sub" -> { val b = s.popNum(); val a = s.popNum(); s.add(a - b) }
            "mul" -> { val b = s.popNum(); val a = s.popNum(); s.add(a * b) }
            "div" -> { val b = s.popNum(); val a = s.popNum(); s.add(if (b != 0.0) a / b else 0.0) }
            "idiv" -> { val b = s.popNum().toInt(); val a = s.popNum().toInt(); s.add(if (b != 0) (a / b).toDouble() else 0.0) }
            "mod" -> { val b = s.popNum().toInt(); val a = s.popNum().toInt(); s.add(if (b != 0) (a % b).toDouble() else 0.0) }
            "neg" -> s.add(-s.popNum())
            "abs" -> s.add(abs(s.popNum()))
            "sqrt" -> s.add(sqrt(s.popNum().coerceAtLeast(0.0)))
            "sin" -> s.add(sin(s.popNum() * PI / 180.0))
            "cos" -> s.add(cos(s.popNum() * PI / 180.0))
            "atan" -> { val den = s.popNum(); val num = s.popNum(); var a = atan2(num, den) * 180.0 / PI; if (a < 0) a += 360.0; s.add(a) }
            "exp" -> { val e = s.popNum(); val base = s.popNum(); s.add(base.pow(e)) }
            "ln" -> s.add(ln(s.popNum().coerceAtLeast(1e-12)))
            "log" -> s.add(log10(s.popNum().coerceAtLeast(1e-12)))
            "cvi", "truncate" -> s.add(truncate(s.popNum()))
            "cvr" -> { /* already real */ }
            "floor" -> s.add(floor(s.popNum()))
            "ceiling" -> s.add(ceil(s.popNum()))
            "round" -> s.add(s.popNum().roundToInt().toDouble())
            "dup" -> if (s.isNotEmpty()) s.add(s.last())
            "pop" -> if (s.isNotEmpty()) s.removeAt(s.size - 1)
            "exch" -> { val a = s.popAny(); val b = s.popAny(); s.add(a); s.add(b) }
            "copy" -> { val n = s.popNum().toInt(); if (n > 0 && n <= s.size) { val base = s.size - n; for (k in 0 until n) s.add(s[base + k]) } }
            "index" -> { val n = s.popNum().toInt(); if (n >= 0 && n < s.size) s.add(s[s.size - 1 - n]) else s.add(0.0) }
            "roll" -> roll(s)
            "eq" -> { val bb = s.popNum(); val aa = s.popNum(); s.add(aa == bb) }
            "ne" -> { val bb = s.popNum(); val aa = s.popNum(); s.add(aa != bb) }
            "gt" -> { val bb = s.popNum(); val aa = s.popNum(); s.add(aa > bb) }
            "ge" -> { val bb = s.popNum(); val aa = s.popNum(); s.add(aa >= bb) }
            "lt" -> { val bb = s.popNum(); val aa = s.popNum(); s.add(aa < bb) }
            "le" -> { val bb = s.popNum(); val aa = s.popNum(); s.add(aa <= bb) }
            // ISO 32000-1, Table 42: logical on booleans, bitwise on integers.
            "and", "or", "xor" -> {
                val bb = s.popAny()
                val aa = s.popAny()
                if (aa is Boolean && bb is Boolean) {
                    s.add(when (op) { "and" -> aa && bb; "or" -> aa || bb; else -> aa != bb })
                } else {
                    val x = (number(aa) ?: 0.0).toLong()
                    val y = (number(bb) ?: 0.0).toLong()
                    s.add(when (op) { "and" -> x and y; "or" -> x or y; else -> x xor y }.toDouble())
                }
            }
            "not" -> when (val a = s.popAny()) {
                is Boolean -> s.add(!a)
                else -> s.add((number(a) ?: 0.0).toLong().inv().toDouble())
            }
            "bitshift" -> { val sh = s.popNum().toInt(); val v = s.popNum().toLong(); s.add((if (sh >= 0) v shl sh else v shr -sh).toDouble()) }
            "true" -> s.add(true)
            "false" -> s.add(false)
            "if" -> { val proc = s.popBlock(); if (s.popBool()) exec(proc, s) }
            "ifelse" -> { val p2 = s.popBlock(); val p1 = s.popBlock(); exec(if (s.popBool()) p1 else p2, s) }
            else -> { /* unknown operator: ignore */ }
        }
    }

    private fun roll(s: ArrayList<Any>) {
        val j = s.popNum().toInt()
        val n = s.popNum().toInt()
        if (n <= 0 || n > s.size) return
        val base = s.size - n
        val slice = ArrayList(s.subList(base, s.size))
        val shift = ((j % n) + n) % n
        val rolled = ArrayList<Any>(n)
        for (k in 0 until n) rolled.add(slice[(k - shift + n) % n])
        for (k in 0 until n) s[base + k] = rolled[k]
    }
}
