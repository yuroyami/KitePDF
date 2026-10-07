package io.github.yuroyami.kitepdf

import io.github.yuroyami.kiteimagecodec.KiteBitmap
import io.github.yuroyami.kiteimagecodec.KiteImageCodec
import io.github.yuroyami.kitepdf.core.parser.PdfArray
import io.github.yuroyami.kitepdf.core.parser.PdfDictionary
import io.github.yuroyami.kitepdf.core.parser.PdfInt
import io.github.yuroyami.kitepdf.core.parser.PdfName
import io.github.yuroyami.kitepdf.core.parser.PdfObject
import io.github.yuroyami.kitepdf.core.parser.PdfStream
import io.github.yuroyami.kitepdf.core.render.KiteImageData
import io.github.yuroyami.kitepdf.core.render.shrinkRgba
import io.github.yuroyami.kitepdf.core.render.toRgbaBytes
import io.github.yuroyami.kitepdf.core.render.toShrunkRgbaBytes
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A PDF's JPEG image without a mask keeps only its encoded data, and a draw decodes it at
 * the size it draws, inside the JPEG's own transform, so the full-size samples never exist (#381).
 */
class LazyImageDecodeTest {

    private val w = 203
    private val h = 157

    /** A smooth picture with a soft band across it, as a photo or a scan is. */
    private val bytes = KiteImageCodec.encodeJpeg(
        KiteBitmap(w, h, IntArray(w * h) { i ->
            val x = i % w
            val y = i / w
            val band = if (abs(x - y) < w / 6) 60 else 0
            (0xFF shl 24) or ((x * 200 / w + band / 2) shl 16) or ((y * 200 / h + 20) shl 8) or ((x + y) * 120 / (w + h) + band)
        }),
        quality = 90,
    )

    /** 64 by 48, arithmetic coded by `cjpeg -arithmetic`, which KiteImageCodec does not decode. */
    private val arithmetic = (
            "ffd8ffe000104a46494600010100000100010000ffdb0043000302020302020303030304030304050805050404050a070706080c0a0c0c0b0a0b0b0d" +
            "0e12100d0e110e0b0b1016101113141515150c0f171816141812141514ffdb00430103040405040509050509140d0b0d141414141414141414141414" +
            "1414141414141414141414141414141414141414141414141414141414141414141414141414ffc90011080030004003012200021101031101ffcc00" +
            "0a0010100501101105ffda000c03010002110311003f00ff00ec33a5bbc682e89363d1364f45f60f645e922977e9f016b7d5b552c9be20db7168bcd2" +
            "b91431e8545dcefcc751ee6805f527a59d21919cd1161b8e54fcf740cb19257b013494a0b9d6c2122f8598a4860c4b7e86f9deecef1030ff00a9b2d8" +
            "3bfe3558071f031cf1861d8e2d948b4ee14de61010c076e6b54750ddf6ddf89c9a2b05bdc4b34a02b1c5be0b63f9a88dd5bb2549500858fe43bb2eef" +
            "52b3c105d1423af2b091635af4b770d2e636d9a5b3cc71276d3716247832a194590ccc14fe558b5de2bc6feb10099e582a8ba584351f0234ecdeb122" +
            "a1467ced33367649d96b2360855dcb892bb5582065cc408090f6d69f7ba35ce54ae61a14de428d4bed34ffd9"
        ).chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    private fun image(data: ByteArray, width: Int = w, height: Int = h, extra: Map<String, PdfObject> = emptyMap()) =
        KiteImageData.from(
            PdfStream(
                dict = PdfDictionary(
                    linkedMapOf<String, PdfObject>(
                        "Type" to PdfName("XObject"), "Subtype" to PdfName("Image"),
                        "Width" to PdfInt(width.toLong()), "Height" to PdfInt(height.toLong()),
                        "BitsPerComponent" to PdfInt(8), "ColorSpace" to PdfName("DeviceRGB"),
                        "Filter" to PdfName("DCTDecode"), "Length" to PdfInt(data.size.toLong()),
                    ).apply { putAll(extra) },
                ),
                rawBytes = data,
            ),
            refs = { null },
        )

    /** The RGBA of KiteImageCodec's own decode with each side divided by [r]. */
    private fun reducedRgba(r: Int): ByteArray {
        val bitmap = KiteImageCodec.decodeReduced(bytes, r)
        return ByteArray(bitmap.argb.size * 4) { i ->
            val p = bitmap.argb[i / 4]
            when (i % 4) {
                0 -> (p shr 16).toByte()
                1 -> (p shr 8).toByte()
                2 -> p.toByte()
                else -> 0xFF.toByte()
            }
        }
    }

    @Test
    fun a_jpeg_keeps_only_its_encoded_data_until_it_draws() {
        val image = image(bytes)
        assertEquals(KiteImageData.Kind.RAW, image.kind)
        assertEquals(w, image.width)
        assertEquals(h, image.height)
        assertEquals(bytes.size.toLong(), image.retainedBytes(), "the image holds decoded samples")
    }

    @Test
    fun a_jpeg_drawn_small_is_the_reduced_decode_of_the_codec() {
        val image = image(bytes)
        // A factor the codec takes whole comes straight from its reduced transform.
        for (r in listOf(2, 4, 8)) {
            assertContentEquals(reducedRgba(r), image.toShrunkRgbaBytes(r, r), "reduced by $r")
        }
        // Past 8, or with two factors, the codec takes the power of two they share and the bands average the rest.
        val reducedBy2 = reducedRgba(2)
        assertContentEquals(shrinkRgba(reducedBy2, (w + 1) / 2, (h + 1) / 2, 4, 1), image.toShrunkRgbaBytes(8, 2))
        val reducedBy8 = reducedRgba(8)
        assertContentEquals(shrinkRgba(reducedBy8, (w + 7) / 8, (h + 7) / 8, 2, 2), image.toShrunkRgbaBytes(16, 16))
    }

    @Test
    fun a_jpeg_drawn_small_stays_close_to_the_full_decode_averaged() {
        val image = image(bytes)
        val full = image.toRgbaBytes()!!
        for ((fx, fy) in listOf(2 to 2, 4 to 4, 8 to 8, 8 to 2, 1 to 4, 16 to 16)) {
            val expected = shrinkRgba(full, w, h, fx, fy)
            val actual = image.toShrunkRgbaBytes(fx, fy)!!
            assertEquals(expected.size, actual.size, "$fx by $fy")
            var total = 0L
            var worst = 0
            for (i in actual.indices) {
                val d = abs((actual[i].toInt() and 0xFF) - (expected[i].toInt() and 0xFF))
                total += d
                worst = maxOf(worst, d)
            }
            val mean = total.toDouble() / actual.size
            assertTrue(mean < 2.0 && worst <= 24, "$fx by $fy: mean $mean, worst $worst")
        }
    }

    @Test
    fun a_copy_of_the_image_keeps_its_encoded_samples() {
        // /Interpolate makes the image a copy of the decoded one, which must still decode.
        val smooth = image(bytes, extra = mapOf("Interpolate" to io.github.yuroyami.kitepdf.core.parser.PdfBoolean(true)))
        assertTrue(smooth.interpolate)
        assertEquals(bytes.size.toLong(), smooth.retainedBytes())
        assertContentEquals(reducedRgba(4), smooth.toShrunkRgbaBytes(4, 4))
    }

    @Test
    fun a_colour_keyed_jpeg_decodes_in_full_when_it_loads() {
        // A colour key compares exact samples, which a reduced decode averages away.
        val keyed = image(bytes, extra = mapOf("Mask" to PdfArray(List(6) { PdfInt(if (it % 2 == 0) 0L else 10L) })))
        assertEquals(w.toLong() * h * 3, keyed.retainedBytes(), "the keyed image does not hold its samples")
    }

    /**
     * [bytes] with a run of its entropy-coded data set to ones, a bit string no Huffman code
     * is (ISO/IEC 10918-1, C.2): the headers read, and the data does not decode.
     */
    private val damaged = bytes.copyOf().also { file ->
        fun u16(at: Int) = ((file[at].toInt() and 0xFF) shl 8) or (file[at + 1].toInt() and 0xFF)
        var at = 2
        while ((file[at + 1].toInt() and 0xFF) != 0xDA) at += 2 + u16(at + 2)
        val data = at + 2 + u16(at + 2)
        val from = data + (file.size - data) / 3
        for (i in from until from + 64 step 2) {
            file[i] = 0xFF.toByte()
            file[i + 1] = 0
        }
    }

    @Test
    fun a_jpeg_whose_data_does_not_decode_loads_without_a_decode_and_keeps_its_file() {
        assertTrue(KiteImageCodec.probe(damaged).isDecodable, "the headers of the damaged file read")
        assertTrue(runCatching { KiteImageCodec.decode(damaged) }.isFailure, "the data of the damaged file decodes")
        val image = image(damaged)
        // A decode when the file loaded would have found the damage and made it a Kind.JPEG image (#475).
        assertEquals(KiteImageData.Kind.RAW, image.kind)
        assertContentEquals(damaged, image.encodedBytes)
        assertEquals(damaged.size.toLong(), image.retainedBytes(), "the file counts once")
        // The first draw finds the damage, and the image draws as a placeholder (#184).
        assertNull(image.toShrunkRgbaBytes(4, 4))
        assertNull(image.toRgbaBytes())
    }

    @Test
    fun a_jpeg_that_the_codec_refuses_keeps_its_file_as_a_jpeg_kind() {
        val image = image(arithmetic, 64, 48)
        assertEquals(KiteImageData.Kind.JPEG, image.kind)
        assertContentEquals(arithmetic, image.encodedBytes)
    }
}
