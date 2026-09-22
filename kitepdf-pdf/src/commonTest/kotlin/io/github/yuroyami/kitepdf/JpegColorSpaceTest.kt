package io.github.yuroyami.kitepdf

import io.github.yuroyami.kitepdf.core.parser.PdfArray
import io.github.yuroyami.kitepdf.core.parser.PdfDictionary
import io.github.yuroyami.kitepdf.core.parser.PdfInt
import io.github.yuroyami.kitepdf.core.parser.PdfName
import io.github.yuroyami.kitepdf.core.parser.PdfObject
import io.github.yuroyami.kitepdf.core.parser.PdfReal
import io.github.yuroyami.kitepdf.core.parser.PdfStream
import io.github.yuroyami.kitepdf.core.render.KiteColorSpace
import io.github.yuroyami.kitepdf.core.render.KiteImageData
import io.github.yuroyami.kitepdf.core.render.toRgbaBytes
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * A JPEG keeps the colour space and the /Decode array of its image dictionary
 * (ISO 32000-1, 8.9.5.2, #72), and 8-bit images in other spaces convert through
 * tables that match the full conversion.
 */
class JpegColorSpaceTest {

    /** Solid red, 8 by 8, three components. */
    private val red = hex(
        "ffd8ffe000104a46494600010200000100010000ffdb00430001010101010101010101010101010101010101010101010101010101010101010101010101010101010101010101010101010101010101" +
            "010101010101010101ffdb00430101010101010101010101010101010101010101010101010101010101010101010101010101010101010101010101010101010101010101010101010101010101ffc0" +
            "0011080008000803012200021101031101ffc4001f0000010501010101010100000000000000000102030405060708090a0bffc400b5100002010303020403050504040000017d010203000411051221" +
            "31410613516107227114328191a1082342b1c11552d1f02433627282090a161718191a25262728292a3435363738393a434445464748494a535455565758595a636465666768696a737475767778797a" +
            "838485868788898a92939495969798999aa2a3a4a5a6a7a8a9aab2b3b4b5b6b7b8b9bac2c3c4c5c6c7c8c9cad2d3d4d5d6d7d8d9dae1e2e3e4e5e6e7e8e9eaf1f2f3f4f5f6f7f8f9faffc4001f010003" +
            "0101010101010101010000000000000102030405060708090a0bffc400b51100020102040403040705040400010277000102031104052131061241510761711322328108144291a1b1c109233352f015" +
            "6272d10a162434e125f11718191a262728292a35363738393a434445464748494a535455565758595a636465666768696a737475767778797a82838485868788898a92939495969798999aa2a3a4a5a6" +
            "a7a8a9aab2b3b4b5b6b7b8b9bac2c3c4c5c6c7c8c9cad2d3d4d5d6d7d8d9dae2e3e4e5e6e7e8e9eaf2f3f4f5f6f7f8f9faffda000c03010002110311003f00fc5fa28a2bfca73fefe0ffd9",
    )

    /** A grey ramp, 16 by 16, one component: column x holds 17 * x. */
    private val ramp = hex(
        "ffd8ffe000104a46494600010200000100010000ffdb00430001010101010101010101010101010101010101010101010101010101010101010101010101010101010101010101010101010101010101" +
            "010101010101010101ffc0000b080010001001011100ffc4001f0000010501010101010100000000000000000102030405060708090a0bffc400b5100002010303020403050504040000017d01020300" +
            "041105122131410613516107227114328191a1082342b1c11552d1f02433627282090a161718191a25262728292a3435363738393a434445464748494a535455565758595a636465666768696a737475" +
            "767778797a838485868788898a92939495969798999aa2a3a4a5a6a7a8a9aab2b3b4b5b6b7b8b9bac2c3c4c5c6c7c8c9cad2d3d4d5d6d7d8d9dae1e2e3e4e5e6e7e8e9eaf1f2f3f4f5f6f7f8f9faffda" +
            "0008010100003f00fe76ff00e0993ff32f7fdbaffec95fe881ff0004c9ff00997bfedd7ff64aff003bff00f8264ffccbdff6ebff00b257fa207fc1327fe65eff00b75ffd92bfffd9",
    )

    private fun jpeg(bytes: ByteArray, w: Int, h: Int, colorSpace: PdfObject, decode: List<Double>? = null) =
        KiteImageData.from(
            PdfStream(
                dict = PdfDictionary(
                    linkedMapOf<String, PdfObject>(
                        "Type" to PdfName("XObject"), "Subtype" to PdfName("Image"),
                        "Width" to PdfInt(w.toLong()), "Height" to PdfInt(h.toLong()),
                        "BitsPerComponent" to PdfInt(8), "ColorSpace" to colorSpace,
                        "Filter" to PdfName("DCTDecode"), "Length" to PdfInt(bytes.size.toLong()),
                    ).apply { if (decode != null) put("Decode", PdfArray(decode.map { PdfReal(it) })) },
                ),
                rawBytes = bytes,
            ),
            refs = { null },
        )

    private fun pixel(rgba: ByteArray, w: Int, x: Int, y: Int) =
        IntArray(3) { rgba[(y * w + x) * 4 + it].toInt() and 0xFF }

    @Test
    fun a_jpeg_follows_its_decode_array() {
        val plain = jpeg(red, 8, 8, PdfName("DeviceRGB")).toRgbaBytes()!!
        val inverted = jpeg(red, 8, 8, PdfName("DeviceRGB"), listOf(1.0, 0.0, 1.0, 0.0, 1.0, 0.0)).toRgbaBytes()!!
        val p = pixel(plain, 8, 4, 4)
        val q = pixel(inverted, 8, 4, 4)
        assertTrue(p[0] > 245 && p[1] < 10 && p[2] < 10, "red: ${p.toList()}")
        for (c in 0..2) assertEquals(255 - p[c], q[c], "channel $c is inverted")
    }

    @Test
    fun a_grey_jpeg_in_a_spot_colour_space_takes_the_ink_colour() {
        val spot = PdfArray(
            listOf(
                PdfName("Separation"), PdfName("Spot"), PdfName("DeviceRGB"),
                PdfDictionary(
                    linkedMapOf<String, PdfObject>(
                        "FunctionType" to PdfInt(2), "Domain" to PdfArray(listOf(PdfInt(0), PdfInt(1))),
                        "C0" to PdfArray(listOf(PdfInt(1), PdfInt(1), PdfInt(1))),
                        "C1" to PdfArray(listOf(PdfReal(0.9), PdfReal(0.1), PdfReal(0.1))),
                        "N" to PdfInt(1),
                    ),
                ),
            ),
        )
        val image = jpeg(ramp, 16, 16, spot)
        assertEquals(16 * 16, image.pixelBytes!!.size, "one sample per pixel")
        val rgba = image.toRgbaBytes()!!
        // Tint 0 is paper white and tint 1 is the ink.
        val paper = pixel(rgba, 16, 0, 8)
        val ink = pixel(rgba, 16, 15, 8)
        assertTrue(paper.all { it > 245 }, "paper: ${paper.toList()}")
        assertTrue(abs(ink[0] - 230) <= 4 && abs(ink[1] - 26) <= 4 && abs(ink[2] - 26) <= 4, "ink: ${ink.toList()}")
    }

    @Test
    fun a_grey_jpeg_keeps_one_byte_per_pixel() {
        val image = jpeg(ramp, 16, 16, PdfName("DeviceGray"))
        assertSame(KiteColorSpace.DeviceGray, image.resolvedColorSpace)
        assertEquals(16 * 16, image.pixelBytes!!.size)
        val p = pixel(image.toRgbaBytes()!!, 16, 8, 8)
        assertTrue(abs(p[0] - 136) <= 3 && p[0] == p[1] && p[1] == p[2], "grey: ${p.toList()}")
    }

    @Test
    fun a_jpeg_whose_components_do_not_match_its_space_stays_rgb() {
        // A red JPEG declared grey keeps its decoded colour rather than losing two channels.
        val image = jpeg(red, 8, 8, PdfName("DeviceGray"))
        assertSame(KiteColorSpace.DeviceRGB, image.resolvedColorSpace)
        val p = pixel(image.toRgbaBytes()!!, 8, 4, 4)
        assertTrue(p[0] > 245 && p[1] < 10, "red: ${p.toList()}")
    }

    @Test
    fun the_colour_table_matches_the_full_conversion() {
        // CalRGB with a gamma and a matrix is smooth but far from linear, like an ICC profile.
        val calRgb = PdfArray(
            listOf(
                PdfName("CalRGB"),
                PdfDictionary(
                    linkedMapOf<String, PdfObject>(
                        "WhitePoint" to PdfArray(listOf(PdfReal(0.9505), PdfInt(1), PdfReal(1.089))),
                        "Gamma" to PdfArray(listOf(PdfReal(1.8), PdfReal(2.2), PdfReal(2.6))),
                        "Matrix" to PdfArray(
                            listOf(0.4497, 0.2446, 0.0252, 0.3163, 0.6720, 0.1412, 0.1845, 0.0833, 0.9227).map { PdfReal(it) },
                        ),
                    ),
                ),
            ),
        )
        val cs = KiteColorSpace.resolve(calRgb) { null }
        // Every combination of 16 levels per channel, one pixel each.
        val levels = IntArray(16) { it * 17 }
        val samples = ByteArray(16 * 16 * 16 * 3)
        var i = 0
        for (r in levels) for (g in levels) for (b in levels) { samples[i++] = r.toByte(); samples[i++] = g.toByte(); samples[i++] = b.toByte() }
        val image = KiteImageData.from(
            PdfStream(
                dict = PdfDictionary(
                    linkedMapOf<String, PdfObject>(
                        "Type" to PdfName("XObject"), "Subtype" to PdfName("Image"),
                        "Width" to PdfInt(4096), "Height" to PdfInt(1), "BitsPerComponent" to PdfInt(8),
                        "ColorSpace" to calRgb, "Length" to PdfInt(samples.size.toLong()),
                    ),
                ),
                rawBytes = samples,
            ),
            refs = { null },
        )
        val rgba = image.toRgbaBytes()!!
        var worst = 0
        for (p in 0 until 4096) {
            val want = cs.toRgb(DoubleArray(3) { (samples[p * 3 + it].toInt() and 0xFF) / 255.0 })
            val got = IntArray(3) { rgba[p * 4 + it].toInt() and 0xFF }
            worst = maxOf(worst, abs(got[0] - (want.r * 255).roundToInt()), abs(got[1] - (want.g * 255).roundToInt()), abs(got[2] - (want.b * 255).roundToInt()))
        }
        assertTrue(worst <= 1, "the tables are $worst levels from the full conversion")
    }

    private fun hex(s: String) = ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
}
