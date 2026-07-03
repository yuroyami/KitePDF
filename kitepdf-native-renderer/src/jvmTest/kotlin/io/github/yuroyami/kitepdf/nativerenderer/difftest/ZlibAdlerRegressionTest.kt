package io.github.yuroyami.kitepdf.nativerenderer.difftest

import io.github.yuroyami.kitepdf.compression.Zlib
import java.io.ByteArrayOutputStream
import java.util.zip.Inflater
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertTrue

/**
 * Regression: [Zlib.encode] must produce streams a *strict* zlib decoder
 * accepts. KitePDF's own [Zlib.decode] recomputes the Adler-32 the same way it
 * was written, so it can't catch a checksum bug — `java.util.zip.Inflater`
 * (which verifies the trailer against the inflated bytes) can.
 *
 * The historical bug: Adler-32's `b` accumulator overflowed a signed Kotlin
 * `Int` within an NMAX run, corrupting the checksum on inputs larger than a few
 * MB — exactly the size of an embedded font program. Small inputs hid it.
 */
class ZlibAdlerRegressionTest {

    private fun strictInflate(zlib: ByteArray): ByteArray {
        val inf = Inflater()                       // zlib-wrapped, verifies Adler-32
        inf.setInput(zlib)
        val out = ByteArrayOutputStream(zlib.size * 3)
        val buf = ByteArray(64 * 1024)
        while (!inf.finished()) {
            val n = inf.inflate(buf)
            if (n == 0 && inf.needsInput()) break
            out.write(buf, 0, n)
        }
        inf.end()                                  // throws if the Adler-32 check failed
        return out.toByteArray()
    }

    @Test
    fun encode_large_input_passes_strict_zlib_adler_check() {
        // > a few MB, with byte values that actually move both Adler sums.
        val data = ByteArray(6_000_000) { ((it * 31 + 7) and 0xFF).toByte() }
        val encoded = Zlib.encode(data)
        assertContentEquals(data, strictInflate(encoded), "strict zlib round-trip mismatch")
    }

    @Test
    fun encode_small_input_still_round_trips() {
        val data = "The quick brown fox jumps over the lazy dog. ".repeat(4).toByteArray()
        val encoded = Zlib.encode(data)
        assertContentEquals(data, strictInflate(encoded))
        assertTrue(Zlib.decode(encoded).contentEquals(data), "KitePDF self round-trip failed")
    }
}
