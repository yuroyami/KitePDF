package io.github.yuroyami.kitepdf.core.render

import io.github.yuroyami.kitepdf.core.zip.Crc32
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * An image file that declares its colour draws in it, converted to sRGB as a browser draws it: a
 * PNG whose gAMA says linear light shows a stored grey of 128 as 188 (#833). Without the
 * conversion, for a container that names a profile of its own, the samples stay as stored.
 */
class EncodedImageColorTest {

    private fun be(n: Int) = byteArrayOf((n ushr 24).toByte(), (n ushr 16).toByte(), (n ushr 8).toByte(), n.toByte())

    private fun chunk(type: String, body: ByteArray): ByteArray {
        val t = type.encodeToByteArray()
        return be(body.size) + t + body + be(Crc32.of(t + body).toInt())
    }

    /** A 1 x 1 RGB PNG of grey 128, with gAMA 100000, linear light, and its pixel stored with no compression. */
    private fun linearGreyPng(): ByteArray {
        val raw = byteArrayOf(0, 128.toByte(), 128.toByte(), 128.toByte())
        // A zlib stream of one stored block, and its Adler-32.
        var a = 1; var b = 0
        for (x in raw) { a = (a + (x.toInt() and 0xFF)) % 65521; b = (b + a) % 65521 }
        val zlib = byteArrayOf(0x78, 0x01, 1, raw.size.toByte(), 0, raw.size.inv().toByte(), 0xFF.toByte()) + raw + be((b shl 16) or a)
        return byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(), 13, 10, 26, 10) +
            chunk("IHDR", be(1) + be(1) + byteArrayOf(8, 2, 0, 0, 0)) + chunk("gAMA", be(100000)) + chunk("IDAT", zlib) + chunk("IEND", ByteArray(0))
    }

    @Test
    fun a_png_draws_in_the_colour_its_gama_declares() {
        val png = linearGreyPng()
        val converted = KiteImageData.fromEncodedImage(png)!!.toRgbaBytes()!!
        assertTrue(kotlin.math.abs((converted[0].toInt() and 0xFF) - 188) <= 1, "converted: ${converted[0].toInt() and 0xFF}")
        val stored = KiteImageData.fromEncodedImage(png, applyOrientation = true, convertColor = false)!!.toRgbaBytes()!!
        assertEquals(128, stored[0].toInt() and 0xFF)
    }
}
