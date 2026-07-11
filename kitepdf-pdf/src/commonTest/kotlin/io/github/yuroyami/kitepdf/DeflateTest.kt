package io.github.yuroyami.kitepdf

import io.github.yuroyami.kitepdf.core.compression.Deflate
import io.github.yuroyami.kitepdf.core.compression.Inflate
import io.github.yuroyami.kitepdf.core.compression.Zlib
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Round-trips the [Deflate] encoder back through the existing [Inflate]/[Zlib]
 * decoder. If the encoder emits a single wrong bit, the decoder catches it —
 * which also proves the encoder and decoder agree on bit-packing order and the
 * canonical fixed-Huffman code assignment.
 */
class DeflateTest {

    private fun assertDeflateRoundTrips(data: ByteArray) {
        val inflated = Inflate.decode(Deflate.encode(data))
        assertTrue(data.contentEquals(inflated), "bare DEFLATE round-trip failed (${data.size} bytes)")
        val unzipped = Zlib.decode(Zlib.encode(data)) // also verifies the Adler-32 trailer
        assertTrue(data.contentEquals(unzipped), "zlib round-trip failed (${data.size} bytes)")
    }

    @Test fun round_trips_empty() = assertDeflateRoundTrips(ByteArray(0))

    @Test fun round_trips_single_byte() = assertDeflateRoundTrips(byteArrayOf(0x42))

    @Test fun round_trips_short_text() =
        assertDeflateRoundTrips("BT /F1 18 Tf 72 720 Td (Hello, KitePDF!) Tj ET".encodeToByteArray())

    @Test fun round_trips_all_byte_values() =
        assertDeflateRoundTrips(ByteArray(256) { it.toByte() })

    @Test fun round_trips_long_run_of_one_byte() =
        assertDeflateRoundTrips(ByteArray(5000) { 0x7A })

    @Test fun round_trips_repetitive_text_and_actually_compresses() {
        val line = "The quick brown fox jumps over the lazy dog. "
        val data = (line.repeat(400)).encodeToByteArray()
        assertDeflateRoundTrips(data)
        val compressed = Zlib.encode(data)
        assertTrue(
            compressed.size < data.size / 4,
            "expected strong compression on repetitive text: ${compressed.size} vs ${data.size}",
        )
    }

    @Test fun round_trips_incompressible_random_data() {
        // High-entropy data won't shrink, but it must still round-trip exactly.
        assertDeflateRoundTrips(Random(1234).nextBytes(8192))
    }

    @Test fun round_trips_data_larger_than_window() {
        // Mixed repetitive + random, well past the 32 KiB window, to exercise
        // distance bounds and multi-window matching.
        val rnd = Random(99)
        val chunk = rnd.nextBytes(1024)
        val builder = ArrayList<Byte>(200_000)
        repeat(150) { for (b in chunk) builder.add(b) } // repeats far beyond window
        builder.addAll(rnd.nextBytes(20_000).toList())
        assertDeflateRoundTrips(builder.toByteArray())
    }

    @Test fun adler32_is_correct_for_known_vector() {
        // RFC 1950 / well-known: Adler-32("Wikipedia") == 0x11E60398.
        assertEquals(0x11E60398, Zlib.adler32("Wikipedia".encodeToByteArray()))
    }
}
