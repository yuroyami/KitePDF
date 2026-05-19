package com.yuroyami.kitepdf.core

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

    fun size(): Int = written

    fun toByteArray(): ByteArray = buf.copyOf(written)

    private fun grow(minCapacity: Int) {
        var newCap = (buf.size * 2).coerceAtLeast(16)
        while (newCap < minCapacity) newCap *= 2
        buf = buf.copyOf(newCap)
    }
}
