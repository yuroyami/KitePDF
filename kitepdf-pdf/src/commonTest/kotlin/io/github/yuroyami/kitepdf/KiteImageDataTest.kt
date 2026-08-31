package io.github.yuroyami.kitepdf

import io.github.yuroyami.kitepdf.core.compression.Zlib
import io.github.yuroyami.kitepdf.core.parser.PdfArray
import io.github.yuroyami.kitepdf.core.parser.PdfBoolean
import io.github.yuroyami.kitepdf.core.parser.PdfDictionary
import io.github.yuroyami.kitepdf.core.parser.PdfInt
import io.github.yuroyami.kitepdf.core.parser.PdfName
import io.github.yuroyami.kitepdf.core.parser.PdfNull
import io.github.yuroyami.kitepdf.core.parser.PdfObject
import io.github.yuroyami.kitepdf.core.parser.PdfStream
import io.github.yuroyami.kitepdf.core.render.KiteImageData
import io.github.yuroyami.kitepdf.core.render.toRgbaBytes
import kotlin.test.Test
import kotlin.test.assertContentEquals
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

    /* ─── Prefix filters ahead of an image codec, ISO 32000-1 7.4 ──────────
       /Filter [/ASCII85Decode /DCTDecode] and similar: the prefix must be
       decoded before the terminal codec runs. Each positive case asserts the
       wrapped image decodes to the same pixels as the bare codec, so the test
       states the property instead of a hardcoded blob. ────────── */

    // 32x24 baseline JPEG, 4:4:4 (no chroma subsampling); a synthetic test
    // vector, not a photograph. Only the bytes matter here, not the picture.
    private val dctBytes = hex(
        "ffd8fffe00104c61766336322e31312e31303000ffdb0043000806060706070808080808080909090a0a0a090909090a0a0a0a0a0a0c0c0c0a0a0a0a0a0a0a0c0c0c0c0d0e0d0d0d0c0d0e0e0f0f0f1212111115151519191fffc4006200010101010100" +
            "0000000000000000000006050207040100030101010000000000000000000000050604030708100002030101000000000000000000000000040531212241110002020301010100000000000000000000042131011341610314ffc0001108001800200301" +
            "1200021200031200ffda000c03010002110311003f00e40bc5d60d978aac25cfdfd05fe8334dea916537ea43ebc5d72365e2ab025bc17960e8c9bd522c26fd487d78bac1b2f155813de0bcb07464dea916137ea43cbc5d72365e2ab0259fb82f2c1d1937" +
            "aa4594dfa93c8bc5d72585fc20de4d93cf49bf520e4f86578bae4b0bf86fbfd271c137ea41c9f0caf175c9617f0df7930e29bf520e5386578bae4b0b9be7efe930e09bf520e4f87fffd9",
    )

    @Test
    fun ascii85_prefix_before_dct_decodes_to_same_pixels_as_bare_dct() {
        val bare = KiteImageData.from(
            stream(width = 32, height = 24, bpc = 8, colorSpace = "DeviceRGB", filter = PdfName("DCTDecode"), bytes = dctBytes),
        )
        val wrapped = KiteImageData.from(
            stream(
                width = 32, height = 24, bpc = 8, colorSpace = "DeviceRGB",
                filter = PdfArray(listOf(PdfName("ASCII85Decode"), PdfName("DCTDecode"))),
                bytes = ascii85Encode(dctBytes),
            ),
        )
        assertEquals(KiteImageData.Kind.RAW, bare.kind, "sanity: the bare fixture must actually decode")
        assertEquals(KiteImageData.Kind.RAW, wrapped.kind)
        assertEquals(bare.width, wrapped.width)
        assertEquals(bare.height, wrapped.height)
        assertContentEquals(bare.pixelBytes, wrapped.pixelBytes)
    }

    // 32x24 JPEG 2000 codestream (part 1, baseline); a synthetic test vector.
    private val jpxBytes = hex(
        "ff4fff51002f000000000020000000180000000000000000000001000000010000000000000000000003070101070101070101ff52000c00000001000602020000ff5c0029227f207ee07ee07ea076f076f076c06f006f006ee067506750676850055005504757d357d35762ff64001100014c" +
            "61766336322e31312e313030ff90000a0000000000b60001ff93c7f80208dfc7f40307257fcfe80405cd000000c7f2060000707fa7f80200047ec3f60283f302000478003bc7ec0c000d032ddeae49a07d6060087f5fc1f702403e70400d01e7dc08fbc1" +
            "f503801bec034dc0d500a1f584800cbf2288dac56bab3fc0f942c0f942801bf45d852d0cbf4a893ac1f20600369cf674ee35a1f28800408e009b23c0298fc0f84381e0c0369d274acfcfc0e8805fa7c53fa062008ca4293fc023005fa7bfffd9",
    )

    @Test
    fun asciihex_prefix_before_jpx_decodes_to_same_pixels_as_bare_jpx() {
        val bare = KiteImageData.from(
            stream(width = 32, height = 24, bpc = 8, colorSpace = "DeviceRGB", filter = PdfName("JPXDecode"), bytes = jpxBytes),
        )
        val wrapped = KiteImageData.from(
            stream(
                width = 32, height = 24, bpc = 8, colorSpace = "DeviceRGB",
                filter = PdfArray(listOf(PdfName("ASCIIHexDecode"), PdfName("JPXDecode"))),
                bytes = asciiHexEncode(jpxBytes),
            ),
        )
        assertEquals(KiteImageData.Kind.RAW, bare.kind, "sanity: the bare fixture must actually decode")
        assertEquals(KiteImageData.Kind.RAW, wrapped.kind)
        assertEquals(bare.width, wrapped.width)
        assertEquals(bare.height, wrapped.height)
        assertContentEquals(bare.pixelBytes, wrapped.pixelBytes)
    }

    // 64x64 black rectangle + circle, Group4-compressed (single MMR strip);
    // the same fixture proven in the native-renderer JBIG2 MMR oracle test.
    private val jbig2G4 = byteArrayOf(
        0x26, 0xA0.toByte(), 0x78, 0x6F, 0xFF.toByte(), 0xFC.toByte(), 0x8A.toByte(), 0x13,
        0xFC.toByte(), 0x82.toByte(), 0x47, 0x82.toByte(), 0x07, 0xE1.toByte(), 0x07, 0xE9.toByte(),
        0xFA.toByte(), 0x7F, 0xFA.toByte(), 0x7F, 0xFF.toByte(), 0xE9.toByte(), 0xFF.toByte(), 0xFF.toByte(),
        0xB5.toByte(), 0xFF.toByte(), 0xFE.toByte(), 0xD7.toByte(), 0xFE.toByte(), 0xD7.toByte(), 0xB5.toByte(), 0xE1.toByte(),
        0x85.toByte(), 0xE0.toByte(), 0xC1.toByte(), 0x78, 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(),
        0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0xFE.toByte(), 0x3F, 0xFF.toByte(),
        0xFF.toByte(), 0xF0.toByte(), 0x01, 0x00, 0x10,
    )

    private fun u32be(v: Int) = byteArrayOf((v ushr 24).toByte(), (v ushr 16).toByte(), (v ushr 8).toByte(), v.toByte())
    private fun u16be(v: Int) = byteArrayOf((v ushr 8).toByte(), v.toByte())

    /** One JBIG2 segment: header (ISO/IEC 14492 7.2) + data. Page association is always 1. */
    private fun jbig2Segment(number: Int, type: Int, data: ByteArray): ByteArray =
        u32be(number) + byteArrayOf(type.toByte(), 0, 1) + u32be(data.size) + data

    private fun jbig2PageInfo(w: Int, h: Int): ByteArray =
        u32be(w) + u32be(h) + u32be(0) + u32be(0) + byteArrayOf(0) + u16be(0)

    /** Immediate generic region (7.4.6), MMR-coded, placed at (0, [y]), combOp OR. */
    private fun jbig2GenericRegion(y: Int): ByteArray =
        u32be(64) + u32be(64) + u32be(0) + u32be(y) + byteArrayOf(0, 1) + jbig2G4

    private fun jbig2PageInfoSegment(w: Int, h: Int) = jbig2Segment(0, 48, jbig2PageInfo(w, h))
    private fun jbig2RegionSegment(number: Int, y: Int) = jbig2Segment(number, 38, jbig2GenericRegion(y))

    @Test
    fun flate_prefix_before_jbig2_finds_globals_aligned_to_its_own_decodeparms_slot() {
        // Reference: page info + both 64x64 regions in one unfiltered stream,
        // stacked into a 64x128 page.
        val reference = KiteImageData.from(
            stream(
                width = 64, height = 128, bpc = 1, colorSpace = "DeviceGray",
                filter = PdfName("JBIG2Decode"),
                bytes = jbig2PageInfoSegment(64, 128) + jbig2RegionSegment(1, 0) + jbig2RegionSegment(2, 64),
            ),
        )
        assertEquals(KiteImageData.Kind.RAW, reference.kind, "sanity: the two-region reference must actually decode")

        // Split: page info + the top region go through /JBIG2Globals; the
        // bottom region is the stream's own bytes, Flate-compressed so the
        // prefix-decode fix is exercised too. /DecodeParms is an ARRAY, and
        // the globals dict sits at index 1, aligned with /JBIG2Decode, not
        // index 0 (which is null, FlateDecode's own slot).
        val globalsStream = PdfStream(dict = PdfDictionary(emptyMap()), rawBytes = jbig2PageInfoSegment(64, 128) + jbig2RegionSegment(1, 0))
        val wrapped = KiteImageData.from(
            stream(
                width = 64, height = 128, bpc = 1, colorSpace = "DeviceGray",
                filter = PdfArray(listOf(PdfName("FlateDecode"), PdfName("JBIG2Decode"))),
                bytes = Zlib.encode(jbig2RegionSegment(2, 64)),
                decodeParms = PdfArray(listOf(PdfNull, PdfDictionary(mapOf("JBIG2Globals" to globalsStream)))),
            ),
        )
        assertEquals(KiteImageData.Kind.RAW, wrapped.kind)
        assertContentEquals(reference.pixelBytes, wrapped.pixelBytes)
    }

    @Test
    fun ascii85_prefix_before_jbig2_finds_globals_from_a_bare_decodeparms_dict() {
        // /DecodeParms as a bare (non-array) dictionary is positionally valid
        // only at index 0 (extractDecodeParms), so on a [/ASCII85Decode
        // /JBIG2Decode] chain it does not sit at JBIG2Decode's own index 1.
        // Spec-noncompliant (ISO 32000-1 7.4 Table 5), but a shape real
        // writers produce; loadJbig2Globals falls back to a content search
        // for it rather than losing the globals (D-5 fix round 1). Same
        // globals stream and same terminal bytes as the array-form case,
        // varying only /DecodeParms's shape, so this isolates that one
        // property.
        val globalsStream = PdfStream(
            dict = PdfDictionary(emptyMap()),
            rawBytes = jbig2PageInfoSegment(64, 128) + jbig2RegionSegment(1, 0),
        )
        val ownBytes = jbig2RegionSegment(2, 64)

        val arrayForm = KiteImageData.from(
            stream(
                width = 64, height = 128, bpc = 1, colorSpace = "DeviceGray",
                filter = PdfArray(listOf(PdfName("ASCII85Decode"), PdfName("JBIG2Decode"))),
                bytes = ascii85Encode(ownBytes),
                decodeParms = PdfArray(listOf(PdfNull, PdfDictionary(mapOf("JBIG2Globals" to globalsStream)))),
            ),
        )
        val bareDictForm = KiteImageData.from(
            stream(
                width = 64, height = 128, bpc = 1, colorSpace = "DeviceGray",
                filter = PdfArray(listOf(PdfName("ASCII85Decode"), PdfName("JBIG2Decode"))),
                bytes = ascii85Encode(ownBytes),
                decodeParms = PdfDictionary(mapOf("JBIG2Globals" to globalsStream)),
            ),
        )

        assertEquals(KiteImageData.Kind.RAW, arrayForm.kind, "sanity: the array-form reference must actually decode")
        assertEquals(KiteImageData.Kind.RAW, bareDictForm.kind)
        assertContentEquals(arrayForm.pixelBytes, bareDictForm.pixelBytes)
    }

    @Test
    fun unsupported_prefix_filter_before_dct_degrades_without_throwing() {
        // /Crypt has no implementation anywhere in the chain. The bytes are
        // not valid JPEG on their own (no filter can undo a Crypt step), so
        // the image must come back as a placeholder, never as an exception
        // out of the page (R6 lenient salvage).
        val garbage = byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8)
        val image = KiteImageData.from(
            stream(
                width = 32, height = 24, bpc = 8, colorSpace = "DeviceRGB",
                filter = PdfArray(listOf(PdfName("Crypt"), PdfName("DCTDecode"))),
                bytes = garbage,
            ),
        )
        assertEquals(KiteImageData.Kind.JPEG, image.kind)
        assertContentEquals(garbage, image.encodedBytes)
    }

    private fun hex(s: String): ByteArray {
        fun digit(c: Char) = when (c) {
            in '0'..'9' -> c - '0'
            in 'a'..'f' -> c - 'a' + 10
            in 'A'..'F' -> c - 'A' + 10
            else -> error("bad hex digit $c")
        }
        return ByteArray(s.length / 2) { i -> ((digit(s[i * 2]) shl 4) or digit(s[i * 2 + 1])).toByte() }
    }

    /** PDF ASCII85Decode's inverse (ISO 32000-1 7.4.3): groups of 4 bytes to 5 chars, zero-padded then truncated. */
    private fun ascii85Encode(data: ByteArray): ByteArray {
        val out = StringBuilder()
        var i = 0
        while (i < data.size) {
            val n = minOf(4, data.size - i)
            var value = 0L
            for (j in 0 until 4) value = (value shl 8) or (if (j < n) (data[i + j].toLong() and 0xFF) else 0L)
            val chars = CharArray(5)
            var v = value
            for (k in 4 downTo 0) { chars[k] = ('!'.code + (v % 85).toInt()).toChar(); v /= 85 }
            out.appendRange(chars, 0, n + 1)
            i += n
        }
        out.append("~>")
        return out.toString().encodeToByteArray()
    }

    /** PDF ASCIIHexDecode's inverse (ISO 32000-1 7.4.2): two hex digits per byte. */
    private fun asciiHexEncode(data: ByteArray): ByteArray {
        val hexChars = "0123456789ABCDEF"
        val sb = StringBuilder(data.size * 2 + 1)
        for (b in data) {
            val v = b.toInt() and 0xFF
            sb.append(hexChars[v ushr 4]); sb.append(hexChars[v and 0xF])
        }
        sb.append('>')
        return sb.toString().encodeToByteArray()
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
        filter: PdfObject, bytes: ByteArray, decodeParms: PdfObject? = null,
    ): PdfStream = PdfStream(
        dict = PdfDictionary(
            linkedMapOf<String, PdfObject>(
                "Type" to PdfName("XObject"),
                "Subtype" to PdfName("Image"),
                "Width" to PdfInt(width.toLong()),
                "Height" to PdfInt(height.toLong()),
                "BitsPerComponent" to PdfInt(bpc.toLong()),
                "ColorSpace" to PdfName(colorSpace),
                "Filter" to filter,
                "Length" to PdfInt(bytes.size.toLong()),
            ).apply { if (decodeParms != null) put("DecodeParms", decodeParms) },
        ),
        rawBytes = bytes,
    )
}
