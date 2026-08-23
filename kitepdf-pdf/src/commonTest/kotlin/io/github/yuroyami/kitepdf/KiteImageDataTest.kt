package io.github.yuroyami.kitepdf

import io.github.yuroyami.kitepdf.core.parser.PdfArray
import io.github.yuroyami.kitepdf.core.parser.PdfBoolean
import io.github.yuroyami.kitepdf.core.parser.PdfDictionary
import io.github.yuroyami.kitepdf.core.parser.PdfInt
import io.github.yuroyami.kitepdf.core.parser.PdfName
import io.github.yuroyami.kitepdf.core.parser.PdfObject
import io.github.yuroyami.kitepdf.core.parser.PdfStream
import io.github.yuroyami.kitepdf.core.render.KiteImageData
import io.github.yuroyami.kitepdf.core.render.toRgbaBytes
import kotlin.test.Test
import kotlin.test.assertEquals

class KiteImageDataTest {

    @Test
    fun jpeg_filter_classifies_as_jpeg() {
        val image = KiteImageData.from(
            stream(
                width = 200, height = 100, bpc = 8, colorSpace = "DeviceRGB",
                filter = PdfName("DCTDecode"),
                bytes = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte()),
            ),
        )
        assertEquals(KiteImageData.Kind.JPEG, image.kind)
        assertEquals(200, image.width)
        assertEquals(100, image.height)
        assertEquals(4, image.encodedBytes.size)
    }

    @Test
    fun flate_alone_classifies_as_raw() {
        // FlateDecode of literal "ABC". The bytes don't matter for classification.
        val image = KiteImageData.from(
            stream(
                width = 1, height = 1, bpc = 8, colorSpace = "DeviceGray",
                filter = PdfName("FlateDecode"),
                bytes = byteArrayOf(
                    0x78, 0xDA.toByte(), 0x73, 0x74, 0x72, 0x06, 0x00,
                    0x01, 0xB3.toByte(), 0x00, 0xD3.toByte(),
                ),
            ),
        )
        assertEquals(KiteImageData.Kind.RAW, image.kind)
    }

    @Test
    fun jbig2_filter_classifies_as_jbig2() {
        val image = KiteImageData.from(
            stream(
                width = 10, height = 10, bpc = 1, colorSpace = "DeviceGray",
                filter = PdfName("JBIG2Decode"), bytes = ByteArray(4),
            ),
        )
        assertEquals(KiteImageData.Kind.JBIG2, image.kind)
    }

    @Test
    fun ccitt_filter_now_decodes_as_raw() {
        // CCITTFaxDecode is in the filter chain, so the image is RAW pixel data
        // (decoded by FilterChain), not a deferred-codec kind.
        val image = KiteImageData.from(
            stream(
                width = 10, height = 10, bpc = 1, colorSpace = "DeviceGray",
                filter = PdfName("CCITTFaxDecode"), bytes = ByteArray(4),
            ),
        )
        assertEquals(KiteImageData.Kind.RAW, image.kind)
    }

    @Test
    fun indexed_image_resolves_palette_colors() {
        // [/Indexed /DeviceRGB 1 <FF0000 00FF00>]: index 0 = red, 1 = green.
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
        val image = KiteImageData.from(base, refs = { null })
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
        val rgba = KiteImageData.from(base, fillColor = blue).toRgbaBytes()!!
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
        val rgba = KiteImageData.from(base, refs = { null }).toRgbaBytes()!!
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
        val rgba = KiteImageData.from(base, refs = { null }).toRgbaBytes()!!
        assertEquals(0xFF, rgba[0].toInt() and 0xFF) // sample 0 → white
        assertEquals(0x00, rgba[4].toInt() and 0xFF) // sample 255 → black
    }

    @Test
    fun array_colorspace_takes_first_name() {
        val image = KiteImageData.from(
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

        val image = KiteImageData.from(base)
        assertEquals(KiteImageData.Kind.RAW, image.kind)
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

    /* ─── /Mask, ISO 32000-1 §8.9.6 ─────────────────────────────────────── */

    @Test
    fun stencil_mask_hides_the_samples_it_marks() {
        // Stencil 0b10……: sample 1 masks the pixel out, sample 0 lets it paint.
        val rgba = KiteImageData.from(grayPair(mask = stencil(2, 1, byteArrayOf(0b10000000.toByte())))).toRgbaBytes()!!
        assertEquals(0x00, rgba[3].toInt() and 0xFF) // pixel0: masked out
        assertEquals(0xFF, rgba[7].toInt() and 0xFF) // pixel1: painted
        assertEquals(0x40, rgba[4].toInt() and 0xFF) // and the grey it paints survives
    }

    @Test
    fun stencil_mask_decode_one_zero_swaps_the_polarity() {
        val mask = stencil(2, 1, byteArrayOf(0b10000000.toByte()), decode = listOf(1, 0))
        val rgba = KiteImageData.from(grayPair(mask = mask)).toRgbaBytes()!!
        assertEquals(0xFF, rgba[3].toInt() and 0xFF) // pixel0: now painted
        assertEquals(0x00, rgba[7].toInt() and 0xFF) // pixel1: now masked out
    }

    @Test
    fun stencil_mask_finer_than_its_image_composites_on_the_stencil_grid() {
        // 8×4 stencil over a 2×1 image, the MRC scan shape in miniature: the
        // left half is masked out, the right half paints. The composite must
        // keep the stencil's detail, so it lands on the stencil's grid.
        val rows = ByteArray(4) { 0b11110000.toByte() }
        val image = KiteImageData.from(grayPair(mask = stencil(8, 4, rows)))
        assertEquals(8, image.width)
        assertEquals(4, image.height)
        val rgba = image.toRgbaBytes()!!
        fun alphaAt(x: Int, y: Int) = rgba[(y * 8 + x) * 4 + 3].toInt() and 0xFF
        fun grayAt(x: Int, y: Int) = rgba[(y * 8 + x) * 4].toInt() and 0xFF
        assertEquals(0x00, alphaAt(0, 0))
        assertEquals(0x00, alphaAt(3, 3))
        assertEquals(0xFF, alphaAt(4, 0))
        assertEquals(0xFF, alphaAt(7, 3))
        assertEquals(0x00, grayAt(0, 0)) // left column samples the image's pixel0
        assertEquals(0x40, grayAt(7, 0)) // right column samples pixel1
    }

    @Test
    fun stencil_mask_coarser_than_its_image_is_resampled_up() {
        // A 1×1 stencil covering a 2×1 image masks both of its pixels, and the
        // image keeps its own grid.
        val image = KiteImageData.from(grayPair(mask = stencil(1, 1, byteArrayOf(0b10000000.toByte()))))
        assertEquals(2, image.width)
        val rgba = image.toRgbaBytes()!!
        assertEquals(0x00, rgba[3].toInt() and 0xFF)
        assertEquals(0x00, rgba[7].toInt() and 0xFF)
    }

    @Test
    fun color_key_mask_clears_pixels_inside_every_range() {
        // 2×1 DeviceRGB (red, green) with /Mask [250 255 0 5 0 5]: red falls
        // inside all three ranges, green does not.
        val base = PdfStream(
            dict = PdfDictionary(linkedMapOf(
                "Type" to PdfName("XObject"), "Subtype" to PdfName("Image"),
                "Width" to PdfInt(2), "Height" to PdfInt(1),
                "BitsPerComponent" to PdfInt(8), "ColorSpace" to PdfName("DeviceRGB"),
                "Mask" to PdfArray(listOf(250, 255, 0, 5, 0, 5).map { PdfInt(it.toLong()) }),
                "Length" to PdfInt(6),
            )),
            rawBytes = byteArrayOf(0xFF.toByte(), 0x00, 0x00, 0x00, 0xFF.toByte(), 0x00),
        )
        val rgba = KiteImageData.from(base, refs = { null }).toRgbaBytes()!!
        assertEquals(0x00, rgba[3].toInt() and 0xFF) // red keyed out
        assertEquals(0xFF, rgba[7].toInt() and 0xFF) // green kept
        assertEquals(0xFF, rgba[5].toInt() and 0xFF) // green channel untouched
    }

    @Test
    fun color_key_mask_of_the_wrong_arity_is_ignored() {
        // Two bounds for a three-component image describe nothing; the image
        // stays opaque rather than guessing which component they belong to.
        val base = PdfStream(
            dict = PdfDictionary(linkedMapOf(
                "Type" to PdfName("XObject"), "Subtype" to PdfName("Image"),
                "Width" to PdfInt(2), "Height" to PdfInt(1),
                "BitsPerComponent" to PdfInt(8), "ColorSpace" to PdfName("DeviceRGB"),
                "Mask" to PdfArray(listOf(PdfInt(0), PdfInt(255))),
                "Length" to PdfInt(6),
            )),
            rawBytes = byteArrayOf(0xFF.toByte(), 0x00, 0x00, 0x00, 0xFF.toByte(), 0x00),
        )
        val rgba = KiteImageData.from(base, refs = { null }).toRgbaBytes()!!
        assertEquals(0xFF, rgba[3].toInt() and 0xFF)
        assertEquals(0xFF, rgba[7].toInt() and 0xFF)
    }

    @Test
    fun smask_wins_when_an_image_carries_both_masks() {
        // The /SMask says "pixel0 transparent, pixel1 opaque"; the /Mask stencil
        // says the exact opposite. The /SMask is the one that counts.
        val smask = PdfStream(
            dict = PdfDictionary(linkedMapOf(
                "Type" to PdfName("XObject"), "Subtype" to PdfName("Image"),
                "Width" to PdfInt(2), "Height" to PdfInt(1),
                "BitsPerComponent" to PdfInt(8), "ColorSpace" to PdfName("DeviceGray"),
                "Length" to PdfInt(2),
            )),
            rawBytes = byteArrayOf(0x00, 0xFF.toByte()),
        )
        val image = KiteImageData.from(
            grayPair(mask = stencil(2, 1, byteArrayOf(0b01000000)), extra = mapOf("SMask" to smask)),
        )
        val rgba = image.toRgbaBytes()!!
        assertEquals(0x00, rgba[3].toInt() and 0xFF)
        assertEquals(0xFF, rgba[7].toInt() and 0xFF)
    }

    @Test
    fun undecodable_mask_leaves_the_image_painted() {
        // A JBIG2 stencil the decoder cannot read must degrade to "no mask",
        // never to a blank page and never to an exception.
        val broken = PdfStream(
            dict = PdfDictionary(linkedMapOf(
                "Type" to PdfName("XObject"), "Subtype" to PdfName("Image"),
                "Width" to PdfInt(2), "Height" to PdfInt(1),
                "ImageMask" to PdfBoolean(true), "Filter" to PdfName("JBIG2Decode"),
                "Length" to PdfInt(4),
            )),
            rawBytes = byteArrayOf(0x01, 0x02, 0x03, 0x04),
        )
        val rgba = KiteImageData.from(grayPair(mask = broken)).toRgbaBytes()!!
        assertEquals(0xFF, rgba[3].toInt() and 0xFF)
        assertEquals(0xFF, rgba[7].toInt() and 0xFF)
        assertEquals(0x00, rgba[0].toInt() and 0xFF)
        assertEquals(0x40, rgba[4].toInt() and 0xFF)
    }

    @Test
    fun mask_with_a_truncated_stream_leaves_the_image_painted() {
        // Declares 8×4 but ships one byte: too short to be a stencil.
        val short = stencil(8, 4, byteArrayOf(0b11110000.toByte()))
        val image = KiteImageData.from(grayPair(mask = short))
        assertEquals(2, image.width, "a rejected mask must not move the image's grid")
        val rgba = image.toRgbaBytes()!!
        assertEquals(0xFF, rgba[3].toInt() and 0xFF)
        assertEquals(0xFF, rgba[7].toInt() and 0xFF)
    }

    /** A 2×1 8-bpc DeviceGray image (black, mid grey) carrying [mask] as `/Mask`. */
    private fun grayPair(mask: PdfObject, extra: Map<String, PdfObject> = emptyMap()): PdfStream = PdfStream(
        dict = PdfDictionary(
            linkedMapOf<String, PdfObject>(
                "Type" to PdfName("XObject"), "Subtype" to PdfName("Image"),
                "Width" to PdfInt(2), "Height" to PdfInt(1),
                "BitsPerComponent" to PdfInt(8), "ColorSpace" to PdfName("DeviceGray"),
                "Mask" to mask, "Length" to PdfInt(2),
            ).apply { putAll(extra) },
        ),
        rawBytes = byteArrayOf(0x00, 0x40),
    )

    /** A stencil-mask image XObject: 1 bpc, `/ImageMask true`, rows byte-aligned. */
    private fun stencil(
        width: Int, height: Int, bits: ByteArray, decode: List<Int>? = null,
    ): PdfStream = PdfStream(
        dict = PdfDictionary(
            linkedMapOf<String, PdfObject>(
                "Type" to PdfName("XObject"), "Subtype" to PdfName("Image"),
                "Width" to PdfInt(width.toLong()), "Height" to PdfInt(height.toLong()),
                "ImageMask" to PdfBoolean(true), "BitsPerComponent" to PdfInt(1),
                "Length" to PdfInt(bits.size.toLong()),
            ).apply {
                if (decode != null) put("Decode", PdfArray(decode.map { PdfInt(it.toLong()) }))
            },
        ),
        rawBytes = bits,
    )

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
