package io.github.yuroyami.kitepdf.core.render

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
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

    /** A codestream head of [components] components: SOC, then SIZ up to Csiz. */
    private fun siz(components: Int) = byteArrayOf(0xFF.toByte(), 0x4F, 0xFF.toByte(), 0x51) + ByteArray(36) +
        byteArrayOf((components ushr 8).toByte(), components.toByte()) + ByteArray(8)

    private fun jp2With(components: Int, vararg header: ByteArray) =
        signature + box("jp2h", header.fold(ByteArray(0)) { a, b -> a + b }) + box("jp2c", siz(components))

    @Test
    fun the_headers_tell_the_decoded_space_without_a_decode() {
        // #744: the space comes from SIZ and the JP2 header boxes, so the load decodes nothing.
        assertEquals("DeviceGray", jpxDecodedSpace(siz(1)))
        assertEquals("DeviceRGB", jpxDecodedSpace(siz(3)))
        assertEquals("DeviceGray", jpxDecodedSpace(jp2With(1, colr(17))))
        assertEquals("DeviceRGB", jpxDecodedSpace(jp2With(3, colr(18))), "sYCC converts to RGB")
        assertEquals("DeviceRGB", jpxDecodedSpace(jp2With(4, colr(12))), "CMYK converts to RGB")
        assertEquals("DeviceRGB", jpxDecodedSpace(jp2With(1, colr(16), box("pclr", byteArrayOf(0, 2, 3, 7, 7, 7) + ByteArray(6)))))
        assertEquals("DeviceRGB", jpxDecodedSpace(jp2With(3, box("colr", byteArrayOf(2, 0, 0) + ByteArray(8)))), "an ICC profile")
    }

    @Test
    fun headers_that_leave_the_space_open_say_so() {
        assertNull(jpxDecodedSpace(siz(4)), "four components may be colour and opacity")
        assertNull(jpxDecodedSpace(siz(2)))
        assertNull(jpxDecodedSpace(jp2With(3, colr(16), box("cdef", ByteArray(2)))))
        assertNull(jpxDecodedSpace(siz(3).copyOf(20)), "cut short")
    }
}
