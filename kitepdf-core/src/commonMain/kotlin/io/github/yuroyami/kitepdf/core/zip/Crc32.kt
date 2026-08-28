package io.github.yuroyami.kitepdf.core.zip

/**
 * CRC-32 (IEEE 802.3, polynomial `0xEDB88320`), the checksum ZIP stores for
 * every entry. Table-driven, pure Kotlin, same result on every target.
 *
 * ```kotlin
 * Crc32.of("123456789".encodeToByteArray())   // 0xCBF43926
 * ```
 */
public object Crc32 {

    private val TABLE = IntArray(256) { i ->
        var c = i
        repeat(8) { c = if (c and 1 != 0) (c ushr 1) xor 0xEDB88320.toInt() else c ushr 1 }
        c
    }

    /** Checksum of [bytes] between [from] (inclusive) and [to] (exclusive). */
    public fun of(bytes: ByteArray, from: Int = 0, to: Int = bytes.size): Long {
        var state = INITIAL_STATE
        for (i in from until to) state = update(state, bytes[i])
        return finish(state)
    }

    internal const val INITIAL_STATE: Int = -1

    internal fun update(state: Int, byte: Byte): Int =
        TABLE[(state xor byte.toInt()) and 0xFF] xor (state ushr 8)

    internal fun finish(state: Int): Long = state.inv().toLong() and 0xFFFFFFFFL
}
