package io.github.yuroyami.kitepdf.core.compression

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * T-10: `Zlib.encode`/`Zlib.decode` round-trip byte-identically on every
 * target, whichever side (platform-native or pure-Kotlin) each call takes,
 * and the two implementations stay mutually decodable.
 */
class PlatformFlateRoundTripTest {

    /** Deterministic pseudo-random bytes (same LCG as other fixtures). */
    private fun noise(n: Int, seed: Long): ByteArray {
        var x = seed
        return ByteArray(n) {
            x = x * 6364136223846793005L + 1442695040888963407L
            (x ushr 33).toByte()
        }
    }

    private fun repetitive(n: Int): ByteArray {
        val phrase = "the quick brown fox jumps over the lazy dog 0123456789 ".encodeToByteArray()
        return ByteArray(n) { phrase[it % phrase.size] }
    }

    private fun roundTrip(payload: ByteArray) {
        val encoded = Zlib.encode(payload)
        assertContentEquals(payload, Zlib.decode(encoded), "round-trip of ${payload.size} bytes")
    }

    @Test
    fun round_trips_small_random() = roundTrip(noise(1 shl 10, 1L))

    @Test
    fun round_trips_1mb_repetitive() = roundTrip(repetitive(1 shl 20))

    @Test
    fun round_trips_6mb_mixed() {
        val payload = repetitive(3 shl 20) + noise(3 shl 20, 7L)
        roundTrip(payload)
    }

    @Test
    fun pure_encoder_output_decodes_and_vice_versa() {
        val payload = repetitive(200_000)

        // Pure-Kotlin-encoded stream (bypassing any fast path) must decode
        // through Zlib.decode whichever implementation that picks.
        val pure = run {
            val body = Deflate.encode(payload)
            val adler = Zlib.adler32(payload)
            byteArrayOf(0x78, 0x9C.toByte()) + body + byteArrayOf(
                ((adler ushr 24) and 0xFF).toByte(),
                ((adler ushr 16) and 0xFF).toByte(),
                ((adler ushr 8) and 0xFF).toByte(),
                (adler and 0xFF).toByte(),
            )
        }
        assertContentEquals(payload, Zlib.decode(pure))

        // Whatever Zlib.encode produced (native dynamic-Huffman blocks on the
        // JVM) must decode through the PURE inflater too.
        val encoded = Zlib.encode(payload)
        val pureDecoded = Inflate.decode(encoded, offset = 2, length = encoded.size - 6)
        assertContentEquals(payload, pureDecoded, "pure inflate reads the fast-path encoder's output")
    }

    @Test
    fun bomb_cap_still_enforced_through_the_fast_path() {
        val encoded = Zlib.encode(ByteArray(1 shl 20)) // 1 MiB of zeros, tiny stream
        assertTrue(encoded.size < 8192, "zeros compress far below the cap")
        assertFailsWith<InflateException> {
            Zlib.decode(encoded, maxOutputBytes = 1000)
        }
    }
}
