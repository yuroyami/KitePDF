package io.github.yuroyami.kitepdf.epub.script

/**
 * Unicode Normalization Form C (UAX #15) of Unicode 17, which UTS #46 runs on a host name (#520):
 * the full canonical decomposition, the canonical ordering of combining marks, then the canonical
 * composition, with the Hangul syllables done by arithmetic. NormalizationTest.txt checks it.
 */
internal object Nfc {

    /** [text] in NFC. */
    fun normalize(text: String): String {
        // Nothing below U+0300 decomposes into anything that does not compose back, nor composes with what follows.
        if (text.all { it.code < 0x300 }) return text
        return fromCodePoints(normalize(codePoints(text)))
    }

    /** [cps] in NFC. */
    fun normalize(cps: IntArray): IntArray = compose(decompose(cps))

    /** Whether [cps] is in NFC already. */
    fun isNormalized(cps: IntArray): Boolean = cps.all { it < 0x300 } || normalize(cps).contentEquals(cps)

    /** The Canonical_Combining_Class of [cp]. */
    fun combiningClass(cp: Int): Int {
        val t = tables.classes
        var lo = 0
        var hi = t.size / 3 - 1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            when {
                cp < t[mid * 3] -> hi = mid - 1
                cp > t[mid * 3 + 1] -> lo = mid + 1
                else -> return t[mid * 3 + 2]
            }
        }
        return 0
    }

    /** The full canonical decomposition of [cps], in canonical order. */
    fun decompose(cps: IntArray): IntArray {
        val out = IntBuffer(cps.size + 4)
        for (cp in cps) decomposeInto(cp, out)
        val a = out.toArray()
        // Canonical ordering: a stable sort of each run of nonzero classes, by class.
        for (i in 1 until a.size) {
            val ccc = combiningClass(a[i])
            if (ccc == 0) continue
            var j = i
            while (j > 0 && combiningClass(a[j - 1]) > ccc) {
                val t = a[j]; a[j] = a[j - 1]; a[j - 1] = t
                j--
            }
        }
        return a
    }

    private fun decomposeInto(cp: Int, out: IntBuffer) {
        val s = cp - S_BASE
        if (s in 0 until S_COUNT) {
            out.add(L_BASE + s / N_COUNT)
            out.add(V_BASE + s % N_COUNT / T_COUNT)
            if (s % T_COUNT != 0) out.add(T_BASE + s % T_COUNT)
            return
        }
        val parts = tables.decompositions[cp]
        if (parts == null) out.add(cp) else for (p in parts) decomposeInto(p, out)
    }

    /** The canonical composition of [cps], which are decomposed and in canonical order. */
    private fun compose(cps: IntArray): IntArray {
        val out = IntBuffer(cps.size)
        var starter = -1
        // The class of the last code point kept after the starter, or -1 when none is.
        var last = -1
        for (cp in cps) {
            val ccc = combiningClass(cp)
            if (starter >= 0 && (last == -1 || (last != 0 && last < ccc))) {
                val composite = composite(out[starter], cp)
                if (composite >= 0) {
                    out[starter] = composite
                    continue
                }
            }
            if (ccc == 0) {
                starter = out.size
                last = -1
            } else {
                last = ccc
            }
            out.add(cp)
        }
        return out.toArray()
    }

    /** The primary composite of [a] and [b], or -1. */
    private fun composite(a: Int, b: Int): Int {
        val l = a - L_BASE
        if (l in 0 until L_COUNT) {
            val v = b - V_BASE
            return if (v in 0 until V_COUNT) S_BASE + (l * V_COUNT + v) * T_COUNT else -1
        }
        val s = a - S_BASE
        if (s in 0 until S_COUNT && s % T_COUNT == 0) {
            val t = b - T_BASE
            return if (t in 1 until T_COUNT) a + t else -1
        }
        return tables.compositions[a.toLong() shl 21 or b.toLong()] ?: -1
    }

    private const val S_BASE = 0xAC00
    private const val L_BASE = 0x1100
    private const val V_BASE = 0x1161
    private const val T_BASE = 0x11A7
    private const val L_COUNT = 19
    private const val V_COUNT = 21
    private const val T_COUNT = 28
    private const val N_COUNT = V_COUNT * T_COUNT
    private const val S_COUNT = L_COUNT * N_COUNT

    private class Tables(
        /** The first and last code point and the class of each run of a nonzero class. */
        val classes: IntArray,
        val decompositions: Map<Int, IntArray>,
        /** The primary composite of each pair, keyed by the first code point shifted 21 bits left, or the second. */
        val compositions: Map<Long, Int>,
    )

    private val tables: Tables by lazy {
        val classes = ArrayList<Int>()
        val ccc = Vlq(IdnaData.COMBINING_CLASSES)
        var end = 0
        while (!ccc.done) {
            val first = end + ccc.next()
            val last = first + ccc.next()
            classes += first; classes += last; classes += ccc.next()
            end = last + 1
        }
        val decompositions = HashMap<Int, IntArray>()
        val compositions = HashMap<Long, Int>()
        val d = Vlq(IdnaData.DECOMPOSITIONS)
        var cp = 0
        while (!d.done) {
            cp += d.next()
            val header = d.next()
            val parts = IntArray(header / 2) { cp + Vlq.unzigzag(d.next()) }
            decompositions[cp] = parts
            if (header and 1 == 0 && parts.size == 2) compositions[parts[0].toLong() shl 21 or parts[1].toLong()] = cp
        }
        Tables(classes.toIntArray(), decompositions, compositions)
    }
}

/** A reader of the base64 VLQ numbers of [IdnaData], one after another. */
internal class Vlq(private val text: String) {
    private var at = 0

    val done: Boolean get() = at >= text.length

    fun next(): Int {
        var n = 0
        var shift = 0
        while (true) {
            val d = digit(text[at++])
            n = n or ((d and 31) shl shift)
            if (d and 32 == 0) return n
            shift += 5
        }
    }

    private fun digit(c: Char): Int = when (c) {
        in 'A'..'Z' -> c - 'A'
        in 'a'..'z' -> c - 'a' + 26
        in '0'..'9' -> c - '0' + 52
        '+' -> 62
        else -> 63
    }

    companion object {
        fun unzigzag(n: Int): Int = if (n and 1 == 0) n ushr 1 else -(n ushr 1) - 1
    }
}

/** A growable list of ints, without boxing. */
internal class IntBuffer(capacity: Int = 16) {
    private var items = IntArray(maxOf(capacity, 4))
    var size: Int = 0
        private set

    fun add(value: Int) {
        if (size == items.size) items = items.copyOf(size * 2)
        items[size++] = value
    }

    operator fun get(index: Int): Int = items[index]

    operator fun set(index: Int, value: Int) { items[index] = value }

    fun toArray(): IntArray = items.copyOf(size)
}

/** The code points of [text], a lone surrogate standing for itself. */
internal fun codePoints(text: String): IntArray {
    val out = IntBuffer(text.length)
    var i = 0
    while (i < text.length) {
        val c = text[i]
        if (c.isHighSurrogate() && i + 1 < text.length && text[i + 1].isLowSurrogate()) {
            out.add(((c.code - 0xD800) shl 10) + (text[i + 1].code - 0xDC00) + 0x10000)
            i += 2
        } else {
            out.add(c.code)
            i++
        }
    }
    return out.toArray()
}

/** The string of [cps]. */
internal fun fromCodePoints(cps: IntArray, from: Int = 0, to: Int = cps.size): String {
    val out = StringBuilder(to - from)
    for (i in from until to) appendCodePoint(out, cps[i])
    return out.toString()
}

internal fun appendCodePoint(out: StringBuilder, cp: Int) {
    if (cp < 0x10000) {
        out.append(cp.toChar())
    } else {
        out.append((0xD800 + ((cp - 0x10000) shr 10)).toChar())
        out.append((0xDC00 + ((cp - 0x10000) and 0x3FF)).toChar())
    }
}
