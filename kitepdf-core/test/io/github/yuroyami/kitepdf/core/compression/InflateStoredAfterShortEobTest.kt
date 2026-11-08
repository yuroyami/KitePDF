package io.github.yuroyami.kitepdf.core.compression

import kotlin.test.Test
import kotlin.test.assertEquals

class InflateStoredAfterShortEobTest {

    /**
     * A dynamic block whose end-of-block code is 1 bit long and whose longest code is 12 bits,
     * then a stored block. The symbol peek buffers whole bytes past the end-of-block code, and
     * the stored block must start at the next byte boundary of the input, not after the
     * buffered bytes (RFC 1951, 3.2.4; #794). The streams differ in the literals before
     * end-of-block, which moves the bit position. Built by hand; Python's zlib decodes each.
     */
    @Test
    fun storedBlockAfterAShortEndOfBlockCodeReadsItsLength() {
        val head = intArrayOf(4, -63, -127, -115, 36, 73, -110, 4, 65, -38, -44, 60, -78, 122, -10, -2, -7, -89, 71, 68)
        val stored = intArrayOf(7, 0, -8, -1, 115, 116, 111, 114, 101, 100, 33)
        val cases = listOf(
            "A" to intArrayOf(18),
            "AB" to intArrayOf(-102, 0),
            "ABC" to intArrayOf(-38, 9),
            "B" to intArrayOf(38),
            "BB" to intArrayOf(54, 1),
            "C" to intArrayOf(78, 0),
            "AAAA" to intArrayOf(-86, 4),
        )
        for ((text, tail) in cases) {
            val stream = (head + tail + stored).map { it.toByte() }.toByteArray()
            assertEquals(text + "stored!", Inflate.decode(stream).decodeToString(), "literals $text")
        }
    }
}
