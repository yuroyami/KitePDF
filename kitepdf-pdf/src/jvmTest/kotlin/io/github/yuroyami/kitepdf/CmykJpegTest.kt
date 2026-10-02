package io.github.yuroyami.kitepdf

import io.github.yuroyami.kiteimagecodec.KiteImageCodec
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
import io.github.yuroyami.kitepdf.core.render.toShrunkRgbaBytes
import java.awt.image.DataBuffer
import java.awt.image.Raster
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import javax.imageio.IIOImage
import javax.imageio.ImageIO
import javax.imageio.ImageWriteParam
import javax.imageio.metadata.IIOMetadata
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import org.w3c.dom.Element

/**
 * A four-component JPEG in a PDF stores CMYK ink as it is. DCTDecode returns the CMYK samples
 * (ISO 32000-1, 7.4.8, Table 13), `/Decode` maps them (8.9.5.2), and the image's colour space
 * converts them (#470). A standalone Photoshop JPEG stores the ink inverted, and a PDF does not.
 *
 * The JDK writes a four-band raster with no colour conversion and no APP markers, so each test
 * stores the bands it wants and adds the Adobe marker it wants.
 */
class CmykJpegTest {

    /** A [w] by [h] JPEG that stores [bands] in its four components, with an Adobe marker for [adobe], or none. */
    private fun jpeg(
        w: Int,
        h: Int,
        adobe: Int?,
        progressive: Boolean = false,
        subsampled: Boolean = false,
        bands: (Int, Int) -> IntArray,
    ): ByteArray {
        val raster = Raster.createInterleavedRaster(DataBuffer.TYPE_BYTE, w, h, 4, null)
        for (y in 0 until h) for (x in 0 until w) raster.setPixel(x, y, bands(x, y))
        var encoded = write(raster, null, progressive)
        if (subsampled) {
            // The first and the fourth component at full size, the middle two at half, as a YCCK file from Photoshop.
            val reader = ImageIO.getImageReadersByFormatName("jpeg").next()
            reader.setInput(ImageIO.createImageInputStream(ByteArrayInputStream(encoded)))
            val metadata = reader.getImageMetadata(0)
            reader.dispose()
            val format = "javax_imageio_jpeg_image_1.0"
            val tree = metadata.getAsTree(format) as Element
            val specs = tree.getElementsByTagName("componentSpec")
            for (k in 0 until specs.length) {
                val factor = if (k == 0 || k == 3) "2" else "1"
                (specs.item(k) as Element).setAttribute("HsamplingFactor", factor)
                (specs.item(k) as Element).setAttribute("VsamplingFactor", factor)
            }
            metadata.setFromTree(format, tree)
            encoded = write(raster, metadata, progressive)
        }
        if (adobe == null) return encoded
        val app14 = byteArrayOf(-1, 0xEE.toByte(), 0, 14, 'A'.code.toByte(), 'd'.code.toByte(), 'o'.code.toByte(), 'b'.code.toByte(), 'e'.code.toByte(), 0, 100, 0, 0, 0, 0, adobe.toByte())
        return encoded.copyOfRange(0, 2) + app14 + encoded.copyOfRange(2, encoded.size)
    }

    private fun write(raster: Raster, metadata: IIOMetadata?, progressive: Boolean): ByteArray {
        val writer = ImageIO.getImageWritersByFormatName("jpeg").next()
        val param = writer.defaultWriteParam
        param.compressionMode = ImageWriteParam.MODE_EXPLICIT
        param.compressionQuality = 1.0f
        if (progressive) param.progressiveMode = ImageWriteParam.MODE_DEFAULT
        val out = ByteArrayOutputStream()
        ImageIO.createImageOutputStream(out).use { ios ->
            writer.output = ios
            writer.write(null, IIOImage(raster, null, metadata), param)
        }
        writer.dispose()
        return out.toByteArray()
    }

    /** The YCCK bands that store [c], [m], [y] and [k]: YCbCr of the inverted ink, then K (libjpeg, jccolor.c). */
    private fun ycck(c: Int, m: Int, y: Int, k: Int): IntArray {
        val r = 255 - c
        val g = 255 - m
        val b = 255 - y
        return intArrayOf(
            (0.299 * r + 0.587 * g + 0.114 * b).roundToInt().coerceIn(0, 255),
            (-0.16874 * r - 0.33126 * g + 0.5 * b + 128).roundToInt().coerceIn(0, 255),
            (0.5 * r - 0.41869 * g - 0.08131 * b + 128).roundToInt().coerceIn(0, 255),
            k,
        )
    }

    private fun image(
        data: ByteArray,
        w: Int,
        h: Int,
        colorSpace: String = "DeviceCMYK",
        decode: List<Double>? = null,
        extra: Map<String, PdfObject> = emptyMap(),
    ) = KiteImageData.from(
        PdfStream(
            dict = PdfDictionary(
                linkedMapOf<String, PdfObject>(
                    "Type" to PdfName("XObject"), "Subtype" to PdfName("Image"),
                    "Width" to PdfInt(w.toLong()), "Height" to PdfInt(h.toLong()),
                    "BitsPerComponent" to PdfInt(8), "ColorSpace" to PdfName(colorSpace),
                    "Filter" to PdfName("DCTDecode"), "Length" to PdfInt(data.size.toLong()),
                ).apply {
                    if (decode != null) put("Decode", PdfArray(decode.map { PdfReal(it) }))
                    putAll(extra)
                },
            ),
            rawBytes = data,
        ),
        refs = { null },
    )

    /** The RGB that DeviceCMYK gives for these 8-bit ink values. */
    private fun cmyk(c: Int, m: Int, y: Int, k: Int): IntArray {
        val rgb = KiteColorSpace.DeviceCMYK.toRgb(doubleArrayOf(c / 255.0, m / 255.0, y / 255.0, k / 255.0))
        return intArrayOf((rgb.r * 255).roundToInt(), (rgb.g * 255).roundToInt(), (rgb.b * 255).roundToInt())
    }

    private fun pixel(rgba: ByteArray, w: Int, x: Int, y: Int) = IntArray(3) { rgba[(y * w + x) * 4 + it].toInt() and 0xFF }

    private fun assertNear(want: IntArray, got: IntArray, tolerance: Int, what: String) =
        assertTrue((0..2).all { abs(want[it] - got[it]) <= tolerance }, "$what: want ${want.toList()}, got ${got.toList()}")

    @Test
    fun a_cmyk_jpeg_without_ink_draws_white() {
        val rgba = image(jpeg(16, 16, adobe = 0) { _, _ -> intArrayOf(0, 0, 0, 0) }, 16, 16).toRgbaBytes()!!
        assertNear(cmyk(0, 0, 0, 0), pixel(rgba, 16, 8, 8), 3, "no ink")
        assertTrue(pixel(rgba, 16, 8, 8).all { it > 245 }, "no ink is paper white: ${pixel(rgba, 16, 8, 8).toList()}")
    }

    @Test
    fun a_cmyk_jpeg_draws_its_ink() {
        val image = image(jpeg(16, 16, adobe = 0) { _, _ -> intArrayOf(200, 50, 0, 0) }, 16, 16)
        assertSame(KiteColorSpace.DeviceCMYK, image.resolvedColorSpace)
        assertNear(cmyk(200, 50, 0, 0), pixel(image.toRgbaBytes()!!, 16, 8, 8), 3, "cyan with a little magenta")
    }

    @Test
    fun a_ycck_jpeg_draws_its_ink() {
        val image = image(jpeg(16, 16, adobe = 2) { _, _ -> ycck(200, 50, 0, 0) }, 16, 16)
        val samples = image.pixelBytes!!
        val ink = IntArray(4) { samples[(8 * 16 + 8) * 4 + it].toInt() and 0xFF }
        assertNear(intArrayOf(200, 50, 0), ink, 2, "the CMY samples")
        assertEquals(0, ink[3], "the K sample")
        assertNear(cmyk(200, 50, 0, 0), pixel(image.toRgbaBytes()!!, 16, 8, 8), 4, "cyan with a little magenta")
    }

    @Test
    fun a_subsampled_progressive_ycck_jpeg_draws_its_ink() {
        // Two patches meet on a 16-pixel boundary, so the upsampled chroma is flat inside each one.
        val data = jpeg(32, 32, adobe = 2, progressive = true, subsampled = true) { x, _ ->
            if (x < 16) ycck(200, 50, 0, 0) else ycck(0, 120, 220, 40)
        }
        val rgba = image(data, 32, 32).toRgbaBytes()!!
        assertNear(cmyk(200, 50, 0, 0), pixel(rgba, 32, 4, 16), 4, "the left patch")
        assertNear(cmyk(0, 120, 220, 40), pixel(rgba, 32, 27, 16), 4, "the right patch")
    }

    @Test
    fun a_cmyk_jpeg_without_an_adobe_marker_holds_cmyk_unless_its_parameters_say_ycck() {
        // ISO 32000-1, Table 13: /ColorTransform defaults to 0 for four components.
        val plain = image(jpeg(16, 16, adobe = null) { _, _ -> intArrayOf(200, 50, 0, 0) }, 16, 16)
        assertNear(cmyk(200, 50, 0, 0), pixel(plain.toRgbaBytes()!!, 16, 8, 8), 3, "no marker")
        val transformed = image(
            jpeg(16, 16, adobe = null) { _, _ -> ycck(200, 50, 0, 0) }, 16, 16,
            extra = mapOf("DecodeParms" to PdfDictionary(linkedMapOf<String, PdfObject>("ColorTransform" to PdfInt(1)))),
        )
        assertNear(cmyk(200, 50, 0, 0), pixel(transformed.toRgbaBytes()!!, 16, 8, 8), 4, "/ColorTransform 1")
    }

    @Test
    fun an_inverting_decode_array_inverts_the_ink() {
        val data = jpeg(16, 16, adobe = 0) { _, _ -> intArrayOf(0, 0, 0, 0) }
        val plain = pixel(image(data, 16, 16).toRgbaBytes()!!, 16, 8, 8)
        val inverted = pixel(image(data, 16, 16, decode = List(4) { listOf(1.0, 0.0) }.flatten()).toRgbaBytes()!!, 16, 8, 8)
        assertNear(cmyk(0, 0, 0, 0), plain, 3, "no ink")
        assertNear(cmyk(255, 255, 255, 255), inverted, 3, "all ink")
    }

    @Test
    fun each_sample_is_the_component_the_codec_decodes() {
        // KiteImageCodec multiplies C, M and Y by K over 255, so with K at 255 its RGB is the
        // first three components, and with C at 255 its red is the fourth.
        val w = 40
        val h = 24
        for (progressive in listOf(false, true)) for (subsampled in listOf(false, true)) {
            val what = "progressive $progressive, subsampled $subsampled"
            val cmy = jpeg(w, h, adobe = 0, progressive, subsampled) { x, y -> intArrayOf(x * 6, y * 10, (x + y) * 4, 255) }
            val k = jpeg(w, h, adobe = 0, progressive, subsampled) { x, y -> intArrayOf(255, 255, 255, x * 5 + y * 2) }
            val cmyCodec = KiteImageCodec.decode(cmy).argb
            val kCodec = KiteImageCodec.decode(k).argb
            val cmySamples = assertNotNull(image(cmy, w, h).pixelBytes)
            val kSamples = assertNotNull(image(k, w, h).pixelBytes)
            assertEquals(w * h * 4, cmySamples.size, what)
            for (i in 0 until w * h) {
                val p = cmyCodec[i]
                val got = IntArray(4) { cmySamples[i * 4 + it].toInt() and 0xFF }
                assertEquals(listOf((p shr 16) and 0xFF, (p shr 8) and 0xFF, p and 0xFF, 255), got.toList(), "$what, pixel $i of C, M and Y")
                val q = kCodec[i]
                assertEquals((q shr 16) and 0xFF, kSamples[i * 4 + 3].toInt() and 0xFF, "$what, pixel $i of K")
            }
        }
    }

    @Test
    fun a_cmyk_jpeg_decodes_at_the_size_it_draws_and_in_full_under_a_mask() {
        val data = jpeg(64, 64, adobe = 0) { _, _ -> intArrayOf(200, 50, 0, 0) }
        val want = cmyk(200, 50, 0, 0)
        // Without a mask the image keeps its encoded data and decodes reduced (#381).
        val lazy = image(data, 64, 64)
        assertTrue(lazy.retainedBytes() < 64 * 64, "the image holds ${lazy.retainedBytes()} bytes")
        val small = lazy.toShrunkRgbaBytes(4, 4)!!
        assertEquals(16 * 16 * 4, small.size)
        assertNear(want, pixel(small, 16, 8, 8), 3, "a quarter of the size")
        // A soft mask needs the full samples now.
        val mask = PdfStream(
            dict = PdfDictionary(
                linkedMapOf<String, PdfObject>(
                    "Type" to PdfName("XObject"), "Subtype" to PdfName("Image"),
                    "Width" to PdfInt(1), "Height" to PdfInt(1), "BitsPerComponent" to PdfInt(8),
                    "ColorSpace" to PdfName("DeviceGray"), "Length" to PdfInt(1),
                ),
            ),
            rawBytes = byteArrayOf(-1),
        )
        val masked = image(data, 64, 64, extra = mapOf("SMask" to mask))
        assertEquals(64 * 64 * 4, masked.pixelBytes!!.size)
        assertNear(want, pixel(masked.toRgbaBytes()!!, 64, 32, 32), 3, "under a soft mask")
    }
}
