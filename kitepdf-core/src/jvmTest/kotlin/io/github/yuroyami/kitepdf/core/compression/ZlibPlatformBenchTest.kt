package io.github.yuroyami.kitepdf.core.compression

import java.util.zip.Deflater
import java.util.zip.Inflater
import kotlin.system.measureNanoTime
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * T-10 acceptance benchmark: `Zlib.decode` (through the platform fast path)
 * within 2x of raw `java.util.zip` on a 6 MB fixture. Numbers print to the
 * test log for the progress ledger; the 2x bound is deliberately loose
 * because both sides run the same native zlib, so it only fails if the
 * wrapper regresses badly.
 */
class ZlibPlatformBenchTest {

    private fun fixture(): ByteArray {
        val phrase = "content stream operators q Q cm BT Tf Td Tj ET re f S gs Do 0.123 456.7 ".encodeToByteArray()
        var x = 42L
        return ByteArray(6 shl 20) {
            if (it % 4 == 3) {
                x = x * 6364136223846793005L + 1442695040888963407L
                (x ushr 33).toByte()
            } else phrase[it % phrase.size]
        }
    }

    private fun rawJdkInflate(stream: ByteArray, expectSize: Int): ByteArray {
        val inf = Inflater(false)
        inf.setInput(stream)
        val out = ByteArray(expectSize)
        var total = 0
        while (!inf.finished()) total += inf.inflate(out, total, out.size - total)
        inf.end()
        return out
    }

    @Test
    fun decode_within_2x_of_raw_java_util_zip() {
        val payload = fixture()
        val stream = run {
            // Encode with the JDK directly so both sides decode identical bytes.
            val d = Deflater(6, false)
            d.setInput(payload)
            d.finish()
            val buf = ByteArray(payload.size + 1024)
            var n = 0
            while (!d.finished()) n += d.deflate(buf, n, buf.size - n)
            d.end()
            buf.copyOf(n)
        }

        // Warm-up both paths, then time the medians of 5 runs.
        repeat(2) {
            Zlib.decode(stream)
            rawJdkInflate(stream, payload.size)
        }
        fun median(runs: List<Long>) = runs.sorted()[runs.size / 2]
        val ours = median((1..5).map { measureNanoTime { Zlib.decode(stream) } })
        val jdk = median((1..5).map { measureNanoTime { rawJdkInflate(stream, payload.size) } })

        val ratio = ours.toDouble() / jdk.toDouble()
        println("[T-10 bench] 6MB inflate: Zlib.decode=${ours / 1_000_000.0}ms raw java.util.zip=${jdk / 1_000_000.0}ms ratio=${(ratio * 100).toInt() / 100.0}")
        assertTrue(ratio <= 2.0, "Zlib.decode is ${ratio}x of raw java.util.zip (budget: 2x)")
    }

    @Test
    fun encode_output_within_budget_of_zlib_level_6() {
        val payload = fixture()
        val ours = Zlib.encode(payload)
        val jdk = run {
            val d = Deflater(6, false)
            d.setInput(payload)
            d.finish()
            val buf = ByteArray(payload.size + 1024)
            var n = 0
            while (!d.finished()) n += d.deflate(buf, n, buf.size - n)
            d.end()
            buf.copyOf(n)
        }
        val ratio = ours.size.toDouble() / jdk.size.toDouble()
        println("[T-10 bench] 6MB deflate size: ours=${ours.size} zlib6=${jdk.size} ratio=${(ratio * 1000).toInt() / 1000.0}")
        assertTrue(ratio <= 1.25, "encoded size is ${ratio}x of zlib level 6 (budget: 1.25x)")
    }
}
