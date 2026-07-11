package io.github.yuroyami.kitepdf

import io.github.yuroyami.kitepdf.core.parser.PdfArray
import io.github.yuroyami.kitepdf.core.parser.PdfDictionary
import io.github.yuroyami.kitepdf.core.parser.PdfInt
import io.github.yuroyami.kitepdf.core.parser.PdfName
import io.github.yuroyami.kitepdf.core.parser.PdfStream
import io.github.yuroyami.kitepdf.core.render.ImageXObject
import io.github.yuroyami.kitepdf.core.render.toRgbaBytes
import kotlin.test.Test
import kotlin.test.assertEquals

class ImageXObjectTest {

    @Test
    fun jpeg_filter_classifies_as_jpeg() {
        val image = ImageXObject.from(
            stream(
                width = 200, height = 100, bpc = 8, colorSpace = "DeviceRGB",
                filter = PdfName("DCTDecode"),
                bytes = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte()),
            ),
        )
        assertEquals(ImageXObject.Kind.JPEG, image.kind)
        assertEquals(200, image.width)
        assertEquals(100, image.height)
        assertEquals(4, image.encodedBytes.size)
    }

    @Test
    fun flate_alone_classifies_as_raw() {
        // FlateDecode of literal "ABC" — the bytes don't matter for classification.
        val image = ImageXObject.from(
            stream(
                width = 1, height = 1, bpc = 8, colorSpace = "DeviceGray",
                filter = PdfName("FlateDecode"),
                bytes = byteArrayOf(
                    0x78, 0xDA.toByte(), 0x73, 0x74, 0x72, 0x06, 0x00,
                    0x01, 0xB3.toByte(), 0x00, 0xD3.toByte(),
                ),
            ),
        )
        assertEquals(ImageXObject.Kind.RAW, image.kind)
    }

    @Test
    fun jbig2_filter_classifies_as_jbig2() {
        val image = ImageXObject.from(
            stream(
                width = 10, height = 10, bpc = 1, colorSpace = "DeviceGray",
                filter = PdfName("JBIG2Decode"), bytes = ByteArray(4),
            ),
        )
        assertEquals(ImageXObject.Kind.JBIG2, image.kind)
    }

    @Test
    fun ccitt_filter_now_decodes_as_raw() {
        // CCITTFaxDecode is in the filter chain, so the image is RAW pixel data
        // (decoded by FilterChain), not a deferred-codec kind.
        val image = ImageXObject.from(
            stream(
                width = 10, height = 10, bpc = 1, colorSpace = "DeviceGray",
                filter = PdfName("CCITTFaxDecode"), bytes = ByteArray(4),
            ),
        )
        assertEquals(ImageXObject.Kind.RAW, image.kind)
    }

    @Test
    fun indexed_image_resolves_palette_colors() {
        // [/Indexed /DeviceRGB 1 <FF0000 00FF00>] — index 0 = red, 1 = green.
        val palette = byteArrayOf(0xFF.toByte(), 0, 0, 0, 0xFF.toByte(), 0)
        val base = PdfStream(
            dict = PdfDictionary(linkedMapOf(
                "Type" to PdfName("XObject"), "Subtype" to PdfName("Image"),
                "Width" to PdfInt(2), "Height" to PdfInt(1),
                "BitsPerComponent" to PdfInt(8),
                "ColorSpace" to PdfArray(listOf(
                    PdfName("Indexed"), PdfName("DeviceRGB"), PdfInt(1),
                    io.github.yuroyami.kitepdf.core.parser.PdfString(palette),
                )),
                "Length" to PdfInt(2),
            )),
            rawBytes = byteArrayOf(0x00, 0x01),
        )
        val image = ImageXObject.from(base, refs = { null })
        val rgba = image.toRgbaBytes()!!
        assertEquals(0xFF, rgba[0].toInt() and 0xFF) // pixel0 red R
        assertEquals(0x00, rgba[1].toInt() and 0xFF)
        assertEquals(0x00, rgba[6].toInt() and 0xFF) // pixel1 green R
        assertEquals(0xFF, rgba[5].toInt() and 0xFF) // pixel1 green G
    }

    @Test
    fun image_mask_paints_fill_color_where_sample_is_zero() {
        // 8×1 /ImageMask, one byte 0b10101010: MSB(bit0)=1→transparent, bit1=0→paint.
        val base = PdfStream(
            dict = PdfDictionary(linkedMapOf(
                "Type" to PdfName("XObject"), "Subtype" to PdfName("Image"),
                "Width" to PdfInt(8), "Height" to PdfInt(1),
                "ImageMask" to io.github.yuroyami.kitepdf.core.parser.PdfBoolean(true),
                "Length" to PdfInt(1),
            )),
            rawBytes = byteArrayOf(0b10101010.toByte()),
        )
        val blue = io.github.yuroyami.kitepdf.core.render.RgbColor(0.0, 0.0, 1.0)
        val rgba = ImageXObject.from(base, fillColor = blue).toRgbaBytes()!!
        // pixel0 (bit 1) → transparent
        assertEquals(0x00, rgba[3].toInt() and 0xFF)
        // pixel1 (bit 0) → painted blue, opaque
        assertEquals(0xFF, rgba[7].toInt() and 0xFF)
        assertEquals(0xFF, rgba[6].toInt() and 0xFF) // blue channel of pixel1
    }

    @Test
    fun four_bit_gray_unpacks_per_sample() {
        // 4×1 DeviceGray @ 4bpc: samples 0,15,15,0 packed in [0x0F, 0xF0].
        val base = PdfStream(
            dict = PdfDictionary(linkedMapOf(
                "Type" to PdfName("XObject"), "Subtype" to PdfName("Image"),
                "Width" to PdfInt(4), "Height" to PdfInt(1),
                "BitsPerComponent" to PdfInt(4), "ColorSpace" to PdfName("DeviceGray"),
                "Length" to PdfInt(2),
            )),
            rawBytes = byteArrayOf(0x0F, 0xF0.toByte()),
        )
        val rgba = ImageXObject.from(base, refs = { null }).toRgbaBytes()!!
        assertEquals(0x00, rgba[0].toInt() and 0xFF)  // sample 0 → black
        assertEquals(0xFF, rgba[4].toInt() and 0xFF)  // sample 15 → white
        assertEquals(0xFF, rgba[8].toInt() and 0xFF)  // sample 15 → white
        assertEquals(0x00, rgba[12].toInt() and 0xFF) // sample 0 → black
    }

    @Test
    fun decode_array_inverts_gray() {
        // DeviceGray with /Decode [1 0]: sample 0 → white, 255 → black.
        val base = PdfStream(
            dict = PdfDictionary(linkedMapOf(
                "Type" to PdfName("XObject"), "Subtype" to PdfName("Image"),
                "Width" to PdfInt(2), "Height" to PdfInt(1),
                "BitsPerComponent" to PdfInt(8), "ColorSpace" to PdfName("DeviceGray"),
                "Decode" to PdfArray(listOf(PdfInt(1), PdfInt(0))),
                "Length" to PdfInt(2),
            )),
            rawBytes = byteArrayOf(0x00, 0xFF.toByte()),
        )
        val rgba = ImageXObject.from(base, refs = { null }).toRgbaBytes()!!
        assertEquals(0xFF, rgba[0].toInt() and 0xFF) // sample 0 → white
        assertEquals(0x00, rgba[4].toInt() and 0xFF) // sample 255 → black
    }

    @Test
    fun array_colorspace_takes_first_name() {
        val image = ImageXObject.from(
            PdfStream(
                dict = PdfDictionary(linkedMapOf(
                    "Type" to PdfName("XObject"),
                    "Subtype" to PdfName("Image"),
                    "Width" to PdfInt(50),
                    "Height" to PdfInt(50),
                    "BitsPerComponent" to PdfInt(8),
                    "ColorSpace" to PdfArray(listOf(PdfName("ICCBased"), PdfName("placeholder"))),
                    "Filter" to PdfName("DCTDecode"),
                    "Length" to PdfInt(0),
                )),
                rawBytes = ByteArray(0),
            ),
        )
        assertEquals("ICCBased", image.colorSpace)
    }

    @Test
    fun smask_is_parsed_and_applied_as_per_pixel_alpha() {
        // 2×1 DeviceGray /SMask: pixel0 transparent (0x00), pixel1 opaque (0xFF).
        val smask = PdfStream(
            dict = PdfDictionary(linkedMapOf(
                "Type" to PdfName("XObject"),
                "Subtype" to PdfName("Image"),
                "Width" to PdfInt(2),
                "Height" to PdfInt(1),
                "BitsPerComponent" to PdfInt(8),
                "ColorSpace" to PdfName("DeviceGray"),
                "Length" to PdfInt(2),
            )),
            rawBytes = byteArrayOf(0x00, 0xFF.toByte()),
        )
        // 2×1 DeviceRGB base (red, green), embedded SMask carries the transparency.
        val base = PdfStream(
            dict = PdfDictionary(linkedMapOf(
                "Type" to PdfName("XObject"),
                "Subtype" to PdfName("Image"),
                "Width" to PdfInt(2),
                "Height" to PdfInt(1),
                "BitsPerComponent" to PdfInt(8),
                "ColorSpace" to PdfName("DeviceRGB"),
                "Length" to PdfInt(6),
                "SMask" to smask,
            )),
            rawBytes = byteArrayOf(
                0xFF.toByte(), 0x00, 0x00, // pixel0 red
                0x00, 0xFF.toByte(), 0x00, // pixel1 green
            ),
        )

        val image = ImageXObject.from(base)
        assertEquals(ImageXObject.Kind.RAW, image.kind)
        assertEquals(2, image.softMaskWidth)
        assertEquals(1, image.softMaskHeight)

        val rgba = image.toRgbaBytes()!!
        // Alpha comes from the SMask: pixel0 transparent, pixel1 opaque.
        assertEquals(0x00, rgba[3].toInt() and 0xFF)
        assertEquals(0xFF, rgba[7].toInt() and 0xFF)
        // RGB is preserved.
        assertEquals(0xFF, rgba[0].toInt() and 0xFF) // pixel0 red
        assertEquals(0xFF, rgba[5].toInt() and 0xFF) // pixel1 green
    }

    private fun stream(
        width: Int, height: Int, bpc: Int, colorSpace: String,
        filter: PdfName, bytes: ByteArray,
    ): PdfStream = PdfStream(
        dict = PdfDictionary(linkedMapOf(
            "Type" to PdfName("XObject"),
            "Subtype" to PdfName("Image"),
            "Width" to PdfInt(width.toLong()),
            "Height" to PdfInt(height.toLong()),
            "BitsPerComponent" to PdfInt(bpc.toLong()),
            "ColorSpace" to PdfName(colorSpace),
            "Filter" to filter,
            "Length" to PdfInt(bytes.size.toLong()),
        )),
        rawBytes = bytes,
    )
}
