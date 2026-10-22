package io.github.yuroyami.kitepdf.core

/**
 * Grow-on-demand byte buffer. Pure Kotlin (no platform OutputStream classes),
 * so it works in commonMain across all targets, and it's faster than
 * `mutableListOf<Byte>()` because it stores into a contiguous primitive array.
 *
 * Public because consumers building or assembling byte streams (e.g. demo
 * PDF generators in tests/samples) need the same primitive.
 */
public class ByteArrayBuilder(initialCapacity: Int = 64) {
    init {
        require(initialCapacity >= 0) { "initialCapacity must be >= 0" }
    }

    private var buf: ByteArray = ByteArray(initialCapacity.coerceAtLeast(16))
    private var written: Int = 0

    public fun append(b: Byte) {
        reserve(1)
        buf[written++] = b
    }

    public fun append(bytes: ByteArray, offset: Int = 0, length: Int = bytes.size - offset) {
        require(offset >= 0 && length >= 0 && offset <= bytes.size - length) {
            "offset/length are outside the source array"
        }
        if (length == 0) return
        reserve(length)
        bytes.copyInto(buf, written, offset, offset + length)
        written += length
    }

    /**
     * Append the low byte of each char in [s] directly, for pure-ASCII tokens
     * (PDF keywords, numbers, operators). Avoids the transient `ByteArray` that
     * `s.encodeToByteArray()` allocates per call. Callers must guarantee ASCII.
     */
    public fun appendAscii(s: String) {
        val n = s.length
        if (n == 0) return
        reserve(n)
        var w = written
        for (i in 0 until n) buf[w++] = s[i].code.toByte()
        written = w
    }

    /**
     * Append the base-10 ASCII representation of [value] directly into the
     * buffer, with no intermediate `String`. Used by the serializer for object
     * numbers, generations and `/Length`, which are written per object.
     */
    public fun appendLong(value: Long) {
        if (value == 0L) { append('0'.code.toByte()); return }
        // Work in non-positive space so Long.MIN_VALUE stays representable.
        var v = if (value > 0) -value else value
        var digits = 0
        var t = v
        while (t != 0L) { t /= 10; digits++ }
        val sign = if (value < 0) 1 else 0
        val total = digits + sign
        reserve(total)
        if (sign == 1) buf[written] = '-'.code.toByte()
        var idx = written + total          // one past the last digit slot
        while (v != 0L) {
            idx--
            buf[idx] = ('0'.code - (v % 10).toInt()).toByte()  // v % 10 is <= 0
            v /= 10
        }
        written += total
    }

    /** Append a 16-bit value big-endian (network order), for SFNT/binary writers. */
    public fun appendU16BE(value: Int) {
        append((value ushr 8).toByte())
        append(value.toByte())
    }

    /** Append a 32-bit value big-endian (network order), for SFNT/binary writers. */
    public fun appendU32BE(value: Int) {
        append((value ushr 24).toByte())
        append((value ushr 16).toByte())
        append((value ushr 8).toByte())
        append(value.toByte())
    }

    /** Append [count] copies of [b] in one reserved span (no per-byte grow check). */
    public fun appendFill(b: Byte, count: Int) {
        require(count >= 0) { "count must be >= 0" }
        if (count == 0) return
        reserve(count)
        buf.fill(b, written, written + count)
        written += count
    }

    public fun size(): Int = written

    public fun toByteArray(): ByteArray = buf.copyOf(written)

    private fun reserve(additional: Int) {
        if (additional > Int.MAX_VALUE - written) {
            throw IllegalStateException("byte buffer exceeds the platform array limit")
        }
        val required = written + additional
        if (required > buf.size) grow(required)
    }

    private fun grow(minCapacity: Int) {
        var newCap = buf.size.coerceAtLeast(16)
        while (newCap < minCapacity) {
            val doubled = newCap.toLong() * 2L
            newCap = if (doubled >= Int.MAX_VALUE) Int.MAX_VALUE else doubled.toInt()
        }
        buf = buf.copyOf(newCap)
    }
}
