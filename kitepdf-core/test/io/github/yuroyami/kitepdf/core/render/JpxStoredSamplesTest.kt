package io.github.yuroyami.kitepdf.core.render

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Which JPEG 2000 files the decoder returns as stored, so a PDF /ColorSpace can read them (#808). */
class JpxStoredSamplesTest {

    private fun box(type: String, body: ByteArray): ByteArray {
        val length = 8 + body.size
        return byteArrayOf((length ushr 24).toByte(), (length ushr 16).toByte(), (length ushr 8).toByte(), length.toByte()) +
            type.encodeToByteArray() + body
    }

    private val signature = box("jP  ", byteArrayOf(0x0D, 0x0A, 0x87.toByte(), 0x0A))
    private val codestream = byteArrayOf(0xFF.toByte(), 0x4F, 0xFF.toByte(), 0x51)

    private fun jp2(vararg header: ByteArray) =
        signature + box("ftyp", "jp2 ".encodeToByteArray() + ByteArray(4) + "jp2 ".encodeToByteArray()) +
            box("jp2h", header.fold(ByteArray(0)) { a, b -> a + b }) + box("jp2c", codestream)

    private fun colr(space: Int) = box("colr", byteArrayOf(1, 0, 0, 0, 0, 0, space.toByte()))

    @Test
    fun a_codestream_and_a_grey_or_srgb_jp2_are_stored_as_they_are() {
        assertTrue(jpxStoresSamples(codestream))
        assertTrue(jpxStoresSamples(jp2(colr(17))))
        assertTrue(jpxStoresSamples(jp2(colr(16))))
        assertTrue(jpxStoresSamples(jp2(box("colr", byteArrayOf(2, 0, 0) + ByteArray(8)))), "an ICC profile is not applied")
    }

    @Test
    fun a_jp2_that_the_decoder_converts_is_not() {
        assertFalse(jpxStoresSamples(jp2(colr(18))), "sYCC")
        assertFalse(jpxStoresSamples(jp2(colr(12))), "CMYK")
        assertFalse(jpxStoresSamples(jp2(colr(17), box("pclr", byteArrayOf(0, 2, 1, 7, 0, 1)))), "a palette")
        assertFalse(jpxStoresSamples(byteArrayOf(1, 2, 3)))
    }
}
