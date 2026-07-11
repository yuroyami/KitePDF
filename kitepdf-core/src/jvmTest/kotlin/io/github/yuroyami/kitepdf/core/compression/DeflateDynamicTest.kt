package io.github.yuroyami.kitepdf.core.compression

import java.util.zip.Deflater as JdkDeflater
import java.util.zip.Inflater as JdkInflater
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertTrue

/**
 * T-11: the pure-Kotlin encoder's dynamic-Huffman blocks. Every payload must
 * decode byte-identically through BOTH our [Inflate] and the strict
 * `java.util.zip.Inflater` (the external correctness oracle), and the output
 * ratio must land within 1.25x of zlib level 6.
 */
class DeflateDynamicTest {

    private fun jdkInflateRaw(stream: ByteArray, hint: Int): ByteArray {
        val inf = JdkInflater(true)
        inf.setInput(stream)
        var buf = ByteArray(maxOf(hint, 64))
        var total = 0
        while (!inf.finished()) {
            if (total == buf.size) buf = buf.copyOf(buf.size * 2)
            val n = inf.inflate(buf, total, buf.size - total)
            if (n == 0 && inf.needsInput()) break
            total += n
        }
        inf.end()
        return buf.copyOf(total)
    }

    private fun jdkDeflate6(data: ByteArray): ByteArray {
        val d = JdkDeflater(6, true)
        d.setInput(data)
        d.finish()
        var buf = ByteArray(maxOf(data.size / 2, 1024))
        var total = 0
        while (!d.finished()) {
            if (total == buf.size) buf = buf.copyOf(buf.size * 2)
            total += d.deflate(buf, total, buf.size - total)
        }
        d.end()
        return buf.copyOf(total)
    }

    private fun noise(n: Int, seed: Long): ByteArray {
        var x = seed
        return ByteArray(n) {
            x = x * 6364136223846793005L + 1442695040888963407L
            (x ushr 33).toByte()
        }
    }

    private fun repetitive(n: Int): ByteArray {
        val phrase = "BT /F1 12 Tf 72 700 Td (dynamic huffman) Tj ET 0 0 612 792 re f ".encodeToByteArray()
        return ByteArray(n) { phrase[it % phrase.size] }
    }

    private fun roundTrip(payload: ByteArray) {
        val encoded = Deflate.encode(payload)
        assertContentEquals(payload, Inflate.decode(encoded), "pure inflate, n=${payload.size}")
        assertContentEquals(payload, jdkInflateRaw(encoded, payload.size), "java.util.zip strict, n=${payload.size}")
    }

    @Test
    fun round_trips_through_both_decoders() {
        roundTrip(ByteArray(0))
        roundTrip(byteArrayOf(42))
        roundTrip("hello dynamic huffman world".encodeToByteArray())
        roundTrip(ByteArray(200_000)) // all zeros: one long match chain
        roundTrip(repetitive(1 shl 20))
        roundTrip(noise(1 shl 20, 9L)) // incompressible: stored blocks win
        roundTrip(repetitive(3 shl 20) + noise(3 shl 20, 5L))
        // Exercise the 64Ki-token block boundary: > 64Ki literals of noise.
        roundTrip(noise((1 shl 16) + 1234, 11L))
    }

    @Test
    fun ratio_within_budget_of_zlib_level_6() {
        val payload = repetitive(3 shl 20) + noise(3 shl 20, 7L)
        val ours = Deflate.encode(payload)
        val jdk = jdkDeflate6(payload)
        val ratio = ours.size.toDouble() / jdk.size
        println("[T-11 bench] 6MB pure deflate: ours=${ours.size} zlib6=${jdk.size} ratio=${(ratio * 1000).toInt() / 1000.0}")
        assertTrue(ratio <= 1.25, "pure encoder is ${ratio}x of zlib level 6 (budget 1.25x)")
    }

    @Test
    fun incompressible_data_does_not_expand_meaningfully() {
        val payload = noise(1 shl 20, 3L)
        val ours = Deflate.encode(payload)
        // Stored blocks cap the overhead at ~5 bytes per 64 KiB chunk.
        assertTrue(
            ours.size <= payload.size + payload.size / 1000 + 64,
            "stored fallback keeps incompressible data at ~1x (got ${ours.size} for ${payload.size})",
        )
    }
}
