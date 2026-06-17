package io.github.yuroyami.kitepdf.core

/**
 * Grow-on-demand byte buffer. Pure Kotlin (no platform OutputStream classes),
 * so it works in commonMain across all targets — and it's faster than
 * `mutableListOf<Byte>()` because it stores into a contiguous primitive array.
 *
 * Public because consumers building or assembling byte streams (e.g. demo
 * PDF generators in tests/samples) need the same primitive.
 */
class ByteArrayBuilder(initialCapacity: Int = 64) {
    private var buf: ByteArray = ByteArray(initialCapacity.coerceAtLeast(16))
    private var written: Int = 0

    fun append(b: Byte) {
        if (written == buf.size) grow(written + 1)
        buf[written++] = b
    }

    fun append(bytes: ByteArray, offset: Int = 0, length: Int = bytes.size - offset) {
        if (length == 0) return
        if (written + length > buf.size) grow(written + length)
        bytes.copyInto(buf, written, offset, offset + length)
        written += length
    }

    /**
     * Append the low byte of each char in [s] directly — for pure-ASCII tokens
     * (PDF keywords, numbers, operators). Avoids the transient `ByteArray` that
     * `s.encodeToByteArray()` allocates per call. Callers must guarantee ASCII.
     */
    fun appendAscii(s: String) {
        val n = s.length
        if (n == 0) return
        if (written + n > buf.size) grow(written + n)
        var w = written
        for (i in 0 until n) buf[w++] = s[i].code.toByte()
        written = w
    }

    /**
     * Append the base-10 ASCII representation of [value] directly into the
     * buffer — no intermediate `String`. Used by the serializer for object
     * numbers, generations and `/Length`, which are written per object.
     */
    fun appendLong(value: Long) {
        if (value == 0L) { append('0'.code.toByte()); return }
        // Work in non-positive space so Long.MIN_VALUE stays representable.
        var v = if (value > 0) -value else value
        var digits = 0
        var t = v
        while (t != 0L) { t /= 10; digits++ }
        val sign = if (value < 0) 1 else 0
        val total = digits + sign
        if (written + total > buf.size) grow(written + total)
        if (sign == 1) buf[written] = '-'.code.toByte()
        var idx = written + total          // one past the last digit slot
        while (v != 0L) {
            idx--
            buf[idx] = ('0'.code - (v % 10).toInt()).toByte()  // v % 10 is <= 0
            v /= 10
        }
        written += total
    }

    /** Append [count] copies of [b] in one reserved span (no per-byte grow check). */
    fun appendFill(b: Byte, count: Int) {
        if (count <= 0) return
        if (written + count > buf.size) grow(written + count)
        buf.fill(b, written, written + count)
        written += count
    }

    fun size(): Int = written

    fun toByteArray(): ByteArray = buf.copyOf(written)

    private fun grow(minCapacity: Int) {
        var newCap = (buf.size * 2).coerceAtLeast(16)
        while (newCap < minCapacity) newCap *= 2
        buf = buf.copyOf(newCap)
    }
}
