package com.yuroyami.kitepdf

import com.yuroyami.kitepdf.parser.PdfArray
import com.yuroyami.kitepdf.parser.PdfDictionary
import com.yuroyami.kitepdf.parser.PdfInt
import com.yuroyami.kitepdf.parser.PdfName
import com.yuroyami.kitepdf.parser.PdfStream
import com.yuroyami.kitepdf.render.ImageXObject
import com.yuroyami.kitepdf.render.toRgbaBytes
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
    fun ccitt_filter_classifies_as_ccitt() {
        val image = ImageXObject.from(
            stream(
                width = 10, height = 10, bpc = 1, colorSpace = "DeviceGray",
                filter = PdfName("CCITTFaxDecode"), bytes = ByteArray(4),
            ),
        )
        assertEquals(ImageXObject.Kind.CCITT, image.kind)
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
