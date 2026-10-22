package io.github.yuroyami.kitepdf.epub.script

import io.github.yuroyami.kitepdf.core.text.Bidi

/**
 * Unicode IDNA Compatibility Processing (UTS #46) of Unicode 17, the step of the URL Standard
 * that turns a host name outside ASCII into the ASCII a resolver looks up (#520):
 * `Ｇｏ.com` is `go.com` and `faß.example` is `xn--fa-hia.example`.
 *
 * [toAscii] maps each code point by the IDNA mapping table, normalizes to NFC, then checks each
 * label against the validity criteria, the ContextJ rules of RFC 5892 for the joiners and the
 * bidi rule of RFC 5893, and writes a label outside ASCII in Punycode (RFC 3492). Every test of
 * IdnaTestV2.txt of Unicode 17 passes.
 */
internal object Idna {

    /**
     * ToASCII of UTS #46, 4.2, or null when it records an error. The URL Standard asks for
     * CheckBidi and CheckJoiners alone, the defaults here.
     */
    fun toAscii(
        domain: String,
        checkHyphens: Boolean = false,
        checkBidi: Boolean = true,
        checkJoiners: Boolean = true,
        useStd3AsciiRules: Boolean = false,
        transitional: Boolean = false,
        verifyDnsLength: Boolean = false,
        ignoreInvalidPunycode: Boolean = false,
    ): String? {
        val labels = process(codePoints(domain), checkHyphens, checkBidi, checkJoiners, useStd3AsciiRules, transitional, ignoreInvalidPunycode)
            ?: return null
        val out = ArrayList<String>(labels.size)
        for (label in labels) {
            out += if (label.all { it < 0x80 }) fromCodePoints(label) else "xn--" + (Punycode.encode(label) ?: return null)
        }
        if (verifyDnsLength) {
            val total = out.sumOf { it.length } + out.size - 1
            if (total !in 1..253 || out.any { it.length !in 1..63 }) return null
        }
        return out.joinToString(".")
    }

    /** The processing steps of UTS #46, 4: the labels of [domain] in Unicode, or null when it records an error. */
    private fun process(
        domain: IntArray,
        checkHyphens: Boolean,
        checkBidi: Boolean,
        checkJoiners: Boolean,
        useStd3AsciiRules: Boolean,
        transitional: Boolean,
        ignoreInvalidPunycode: Boolean,
    ): List<IntArray>? {
        // 1. Map.
        val mapped = IntBuffer(domain.size)
        for (cp in domain) {
            when (status(cp)) {
                IGNORED -> Unit
                MAPPED -> if (transitional && cp == 0x1E9E) { mapped.add('s'.code); mapped.add('s'.code) } else for (m in mapping(cp)) mapped.add(m)
                DEVIATION -> if (transitional) for (m in mapping(cp)) mapped.add(m) else mapped.add(cp)
                else -> mapped.add(cp)
            }
        }
        // 2. Normalize, 3. break into labels.
        val labels = split(Nfc.normalize(mapped.toArray()))
        // 4. Convert and validate.
        var error = false
        val out = ArrayList<IntArray>(labels.size)
        for (label in labels) {
            if (startsWithAce(label)) {
                if (label.any { it >= 0x80 }) { error = true; out += label; continue }
                val decoded = Punycode.decode(label, 4)
                if (decoded == null) {
                    if (!ignoreInvalidPunycode) { error = true; out += label; continue }
                    out += label
                    continue
                }
                if (decoded.isEmpty() || decoded.all { it < 0x80 }) error = true
                if (!valid(decoded, false, checkHyphens, checkJoiners, useStd3AsciiRules)) error = true
                out += decoded
            } else {
                if (!valid(label, transitional, checkHyphens, checkJoiners, useStd3AsciiRules)) error = true
                out += label
            }
        }
        if (checkBidi && out.any { l -> l.any { isRtl(Bidi.classify(it)) || Bidi.classify(it) == Bidi.AN } }) {
            if (out.any { it.isNotEmpty() && !satisfiesBidiRule(it) }) error = true
        }
        return if (error) null else out
    }

    private fun split(cps: IntArray): List<IntArray> {
        val out = ArrayList<IntArray>()
        var start = 0
        for (i in cps.indices) {
            if (cps[i] == '.'.code) {
                out += cps.copyOfRange(start, i)
                start = i + 1
            }
        }
        out += cps.copyOfRange(start, cps.size)
        return out
    }

    private fun startsWithAce(label: IntArray): Boolean =
        label.size >= 4 && label[0] == 'x'.code && label[1] == 'n'.code && label[2] == '-'.code && label[3] == '-'.code

    /** The validity criteria of UTS #46, 4.1, for a label, which holds when it is empty. */
    private fun valid(label: IntArray, transitional: Boolean, checkHyphens: Boolean, checkJoiners: Boolean, std3: Boolean): Boolean {
        if (label.isEmpty()) return true
        if (!Nfc.isNormalized(label)) return false
        val hyphen = '-'.code
        if (checkHyphens) {
            if (label.size >= 4 && label[2] == hyphen && label[3] == hyphen) return false
            if (label.first() == hyphen || label.last() == hyphen) return false
        } else if (startsWithAce(label)) {
            return false
        }
        if (label.any { it == '.'.code }) return false
        if (isMark(label[0])) return false
        for (cp in label) {
            val status = status(cp)
            if (status != VALID && (transitional || status != DEVIATION)) return false
            if (std3 && cp < 0x80 && cp != hyphen && cp !in 'a'.code..'z'.code && cp !in '0'.code..'9'.code) return false
        }
        if (checkJoiners && !satisfiesContextJ(label)) return false
        return true
    }

    /** The ContextJ rules of RFC 5892, appendix A.1 and A.2, for the joiners of [label]. */
    private fun satisfiesContextJ(label: IntArray): Boolean {
        for (i in label.indices) {
            val cp = label[i]
            if (cp != ZWNJ && cp != ZWJ) continue
            if (i > 0 && Nfc.combiningClass(label[i - 1]) == VIRAMA) continue
            if (cp == ZWJ) return false
            // ZWNJ: (Joining_Type L or D) (T)* ZWNJ (T)* (R or D).
            var j = i - 1
            while (j >= 0 && joiningType(label[j]) == JT_T) j--
            if (j < 0 || joiningType(label[j]).let { it != JT_L && it != JT_D }) return false
            var k = i + 1
            while (k < label.size && joiningType(label[k]) == JT_T) k++
            if (k >= label.size || joiningType(label[k]).let { it != JT_R && it != JT_D }) return false
        }
        return true
    }

    private fun isRtl(type: Int) = type == Bidi.R || type == Bidi.AL

    /** The six conditions of the bidi rule of RFC 5893, section 2. */
    private fun satisfiesBidiRule(label: IntArray): Boolean {
        val types = IntArray(label.size) { Bidi.classify(label[it]) }
        val first = types[0]
        // The last code point that is not NSM.
        var end = types.size - 1
        while (end > 0 && types[end] == Bidi.NSM) end--
        return when {
            isRtl(first) -> {
                val allowed = setOf(Bidi.R, Bidi.AL, Bidi.AN, Bidi.EN, Bidi.ES, Bidi.CS, Bidi.ET, Bidi.ON, Bidi.BN, Bidi.NSM)
                types.all { it in allowed } &&
                    types[end].let { it == Bidi.R || it == Bidi.AL || it == Bidi.EN || it == Bidi.AN } &&
                    !(Bidi.EN in types && Bidi.AN in types)
            }
            first == Bidi.L -> {
                val allowed = setOf(Bidi.L, Bidi.EN, Bidi.ES, Bidi.CS, Bidi.ET, Bidi.ON, Bidi.BN, Bidi.NSM)
                types.all { it in allowed } && types[end].let { it == Bidi.L || it == Bidi.EN }
            }
            else -> false
        }
    }

    private const val ZWNJ = 0x200C
    private const val ZWJ = 0x200D
    private const val VIRAMA = 9

    // The statuses of the IDNA mapping table, as kinds of run of IdnaData.MAPPING.
    private const val VALID = 0
    private const val IGNORED = 1
    private const val DISALLOWED = 2
    private const val MAPPED = 3
    private const val SHIFTED = 4
    private const val ALTERNATING = 5
    private const val DEVIATION = 6

    /** The status of [cp]: [VALID], [IGNORED], [DISALLOWED], [MAPPED] or [DEVIATION]. */
    fun status(cp: Int): Int {
        val run = run(cp)
        return when (val kind = table.kinds[run]) {
            SHIFTED -> MAPPED
            ALTERNATING -> if ((cp - table.starts[run]) % 2 == 0) MAPPED else VALID
            else -> kind
        }
    }

    /** What [cp] maps to, whose status is [MAPPED] or [DEVIATION]. */
    private fun mapping(cp: Int): IntArray {
        val run = run(cp)
        return when (table.kinds[run]) {
            SHIFTED, ALTERNATING -> intArrayOf(cp + table.values[run])
            else -> table.pool[table.values[run]]
        }
    }

    private fun run(cp: Int): Int {
        val starts = table.starts
        var lo = 0
        var hi = starts.size - 1
        while (lo < hi) {
            val mid = (lo + hi + 1) ushr 1
            if (starts[mid] <= cp) lo = mid else hi = mid - 1
        }
        return lo
    }

    private fun isMark(cp: Int): Boolean = inRuns(table.marks, cp) >= 0

    private const val JT_L = 1
    private const val JT_R = 2
    private const val JT_D = 3
    private const val JT_T = 4

    private fun joiningType(cp: Int): Int {
        val at = inRuns(table.joining, cp)
        return if (at < 0) 0 else table.joiningTypes[at]
    }

    /** The index of the run of [runs], first and last code point after each other, that holds [cp], or -1. */
    private fun inRuns(runs: IntArray, cp: Int): Int {
        var lo = 0
        var hi = runs.size / 2 - 1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            when {
                cp < runs[mid * 2] -> hi = mid - 1
                cp > runs[mid * 2 + 1] -> lo = mid + 1
                else -> return mid
            }
        }
        return -1
    }

    private class Table(
        val starts: IntArray,
        val kinds: IntArray,
        /** The distance of a shifted or alternating run, or the index in [pool] of a mapped or deviation one. */
        val values: IntArray,
        val pool: List<IntArray>,
        val marks: IntArray,
        val joining: IntArray,
        val joiningTypes: IntArray,
    )

    private val table: Table by lazy {
        val pool = ArrayList<IntArray>()
        val p = Vlq(IdnaData.MAPPING_POOL)
        while (!p.done) {
            var last = 0
            pool += IntArray(p.next()) { last += Vlq.unzigzag(p.next()); last }
        }
        val starts = IntBuffer(9000)
        val kinds = IntBuffer(9000)
        val values = IntBuffer(9000)
        val m = Vlq(IdnaData.MAPPING)
        var start = 0
        while (!m.done) {
            start += m.next()
            val kind = m.next()
            starts.add(start)
            kinds.add(kind)
            values.add(
                when (kind) {
                    SHIFTED, ALTERNATING -> Vlq.unzigzag(m.next())
                    MAPPED, DEVIATION -> m.next()
                    else -> 0
                },
            )
        }
        fun runs(text: String, withValue: Boolean, values: IntBuffer?): IntArray {
            val out = IntBuffer()
            val r = Vlq(text)
            var end = 0
            while (!r.done) {
                val first = end + r.next()
                val last = first + r.next()
                out.add(first); out.add(last)
                if (withValue) values!!.add(r.next())
                end = last + 1
            }
            return out.toArray()
        }
        val joiningTypes = IntBuffer()
        Table(
            starts.toArray(), kinds.toArray(), values.toArray(), pool,
            runs(IdnaData.MARKS, false, null),
            runs(IdnaData.JOINING_TYPES, true, joiningTypes),
            joiningTypes.toArray(),
        )
    }
}

/** Punycode, RFC 3492, the encoding of a label outside ASCII in the letters, digits and hyphen of DNS. */
internal object Punycode {
    private const val BASE = 36
    private const val T_MIN = 1
    private const val T_MAX = 26
    private const val SKEW = 38
    private const val DAMP = 700
    private const val INITIAL_BIAS = 72
    private const val INITIAL_N = 128

    private fun adapt(delta: Int, points: Int, first: Boolean): Int {
        var d = if (first) delta / DAMP else delta / 2
        d += d / points
        var k = 0
        while (d > (BASE - T_MIN) * T_MAX / 2) {
            d /= BASE - T_MIN
            k += BASE
        }
        return k + (BASE - T_MIN + 1) * d / (d + SKEW)
    }

    private fun digit(d: Int): Char = if (d < 26) 'a' + d else '0' + (d - 26)

    /** The Punycode of [cps], without the `xn--` prefix, or null when it overflows. */
    fun encode(cps: IntArray): String? {
        val out = StringBuilder()
        for (cp in cps) if (cp < 0x80) out.append(cp.toChar())
        val basic = out.length
        var handled = basic
        if (basic > 0) out.append('-')
        var n = INITIAL_N
        var delta = 0L
        var bias = INITIAL_BIAS
        while (handled < cps.size) {
            val m = cps.filter { it >= n }.min()
            delta += (m - n).toLong() * (handled + 1)
            if (delta > Int.MAX_VALUE) return null
            n = m
            for (cp in cps) {
                if (cp < n) {
                    delta++
                    if (delta > Int.MAX_VALUE) return null
                }
                if (cp == n) {
                    var q = delta.toInt()
                    var k = BASE
                    while (true) {
                        val t = if (k <= bias) T_MIN else if (k >= bias + T_MAX) T_MAX else k - bias
                        if (q < t) break
                        out.append(digit(t + (q - t) % (BASE - t)))
                        q = (q - t) / (BASE - t)
                        k += BASE
                    }
                    out.append(digit(q))
                    bias = adapt(delta.toInt(), handled + 1, handled == basic)
                    delta = 0
                    handled++
                }
            }
            delta++
            n++
        }
        return out.toString()
    }

    /** The code points that the Punycode of [label] from [from] on stands for, or null when it is not valid Punycode. */
    fun decode(label: IntArray, from: Int): IntArray? {
        val input = label.copyOfRange(from, label.size)
        val out = ArrayList<Int>(input.size)
        // The basic code points come before the last delimiter, when there is one.
        val b = maxOf(input.lastIndexOf('-'.code), 0)
        for (j in 0 until b) {
            if (input[j] >= 0x80) return null
            out += input[j]
        }
        var n = INITIAL_N
        var i = 0
        var bias = INITIAL_BIAS
        var at = if (b > 0) b + 1 else 0
        while (at < input.size) {
            val old = i
            var w = 1
            var k = BASE
            while (true) {
                if (at >= input.size) return null
                val c = input[at++]
                val d = when (c) {
                    in 'a'.code..'z'.code -> c - 'a'.code
                    in 'A'.code..'Z'.code -> c - 'A'.code
                    in '0'.code..'9'.code -> c - '0'.code + 26
                    else -> return null
                }
                if (d > (Int.MAX_VALUE - i) / w) return null
                i += d * w
                val t = if (k <= bias) T_MIN else if (k >= bias + T_MAX) T_MAX else k - bias
                if (d < t) break
                if (w > Int.MAX_VALUE / (BASE - t)) return null
                w *= BASE - t
                k += BASE
            }
            val points = out.size + 1
            bias = adapt(i - old, points, old == 0)
            if (i / points > Int.MAX_VALUE - n) return null
            n += i / points
            i %= points
            if (n > 0x10FFFF) return null
            out.add(i, n)
            i++
        }
        return out.toIntArray()
    }
}
