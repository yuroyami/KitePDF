package io.github.yuroyami.kitepdf.writer

import io.github.yuroyami.kitepdf.KitePDF
import io.github.yuroyami.kitepdf.core.render.KiteMatrix
import io.github.yuroyami.kitepdf.core.render.RecordingCanvas
import io.github.yuroyami.kitepdf.core.render.toRgbaBytes
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Round-trips two writer primitives (image XObjects and clipping)
 * through the parser/renderer: build a PDF with [PdfBuilder], reopen it with
 * KitePDF, render to a [RecordingCanvas], and assert the pixels/operators
 * survive. Proves the bytes [PdfBuilder] emits are something KitePDF itself can
 * read back (the whole point of generating reports with the writer).
 */
class WriterImageClipTest {

    @Test
    fun rgba_image_round_trips_with_colors_and_soft_mask() {
        // 2×2 RGBA: red, green, blue, transparent-black.
        val pixels = byteArrayOf(
            0xFF.toByte(), 0, 0, 0xFF.toByte(),
            0, 0xFF.toByte(), 0, 0xFF.toByte(),
            0, 0, 0xFF.toByte(), 0xFF.toByte(),
            0, 0, 0, 0x00,
        )
        val image = PdfImage.rgba(pixels, width = 2, height = 2)
        val bytes = PdfBuilder()
            .page(width = 100.0, height = 100.0) {
                drawImage(image, x = 10.0, y = 20.0, width = 40.0, height = 50.0)
            }
            .build()

        val doc = KitePDF.open(bytes)
        val canvas = RecordingCanvas()
        doc.pages[0].renderTo(canvas, KiteMatrix.IDENTITY)

        val images = canvas.calls.filterIsInstance<RecordingCanvas.Call.Image>()
        assertEquals(1, images.size, "expected exactly one image draw")
        val img = images[0].image
        assertEquals(2, img.width)
        assertEquals(2, img.height)
        assertEquals("DeviceRGB", img.colorSpace)

        // Placement: cm = [w 0 0 h x y] → recorded ctm carries the rect.
        assertEquals(40.0, images[0].ctm.a, 1e-6)
        assertEquals(50.0, images[0].ctm.d, 1e-6)
        assertEquals(10.0, images[0].ctm.e, 1e-6)
        assertEquals(20.0, images[0].ctm.f, 1e-6)

        // Colour samples survived FlateDecode round-trip (RGB, alpha stripped to SMask).
        val rgb = assertNotNull(img.pixelBytes, "RAW image should expose pixelBytes")
        assertEquals(2 * 2 * 3, rgb.size)
        assertEquals(0xFF.toByte(), rgb[0]); assertEquals(0, rgb[1]); assertEquals(0, rgb[2]) // red
        assertEquals(0, rgb[3]); assertEquals(0xFF.toByte(), rgb[4]); assertEquals(0, rgb[5]) // green

        // Alpha became a /SMask: 3 opaque + 1 transparent.
        val mask = assertNotNull(img.softMaskAlpha, "rgba with a transparent pixel must produce a SMask")
        assertEquals(4, mask.size)
        assertEquals(0xFF.toByte(), mask[0])
        assertEquals(0x00, mask[3])
    }

    @Test
    fun opaque_rgba_omits_soft_mask() {
        val pixels = ByteArray(2 * 2 * 4) { i -> if (i % 4 == 3) 0xFF.toByte() else 0x40 }
        val image = PdfImage.rgba(pixels, 2, 2)
        val bytes = PdfBuilder().page(width = 50.0, height = 50.0) {
            drawImage(image, 0.0, 0.0, 50.0, 50.0)
        }.build()
        val doc = KitePDF.open(bytes)
        val canvas = RecordingCanvas()
        doc.pages[0].renderTo(canvas, KiteMatrix.IDENTITY)
        val img = canvas.calls.filterIsInstance<RecordingCanvas.Call.Image>().single().image
        assertTrue(img.softMaskAlpha == null, "fully-opaque image must not carry a SMask")
    }

    @Test
    fun clip_emits_push_clip_with_rectangle() {
        val bytes = PdfBuilder()
            .page(width = 100.0, height = 100.0) {
                save()
                rectangle(10.0, 10.0, 30.0, 30.0)
                clip()
                endPath()
                setFillRgb(1.0, 0.0, 0.0)
                rectangle(0.0, 0.0, 100.0, 100.0)
                fill()
                restore()
            }
            .build()

        val doc = KitePDF.open(bytes)
        val canvas = RecordingCanvas()
        doc.pages[0].renderTo(canvas, KiteMatrix.IDENTITY)

        val clips = canvas.calls.filterIsInstance<RecordingCanvas.Call.PushClip>()
        assertEquals(1, clips.size, "clip() should produce one pushClip")
        assertTrue(!clips[0].evenOdd, "clip() is nonzero-winding (W), not even-odd")
        // The clip path is the 30×30 rectangle (M + 3 L + Close).
        assertEquals(5, clips[0].path.segments.size)
        // The fill after the clip still renders.
        assertEquals(1, canvas.calls.filterIsInstance<RecordingCanvas.Call.Fill>().size)
    }

    private fun hex(s: String) = ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }

    /** 16 x 8 CMYK JPEG from Pillow 12.3, pure red (C 0, M 255, Y 255, K 0), with an Adobe APP14 marker. */
    private val cmykJpeg = hex(
            "ffd8ffee000e41646f626500640000000000ffdb004300020101010101020101010202020202040302020202050404030406" +
            "0506060605060606070908060709070606080b08090a0a0a0a0a06080b0c0b0a0c090a0a0affc00014080008001004431100" +
            "4d11005911004b1100ffc4001f0000010501010101010100000000000000000102030405060708090a0bffc400b510000201" +
            "0303020403050504040000017d01020300041105122131410613516107227114328191a1082342b1c11552d1f02433627282" +
            "090a161718191a25262728292a3435363738393a434445464748494a535455565758595a636465666768696a737475767778" +
            "797a838485868788898a92939495969798999aa2a3a4a5a6a7a8a9aab2b3b4b5b6b7b8b9bac2c3c4c5c6c7c8c9cad2d3d4d5" +
            "d6d7d8d9dae1e2e3e4e5e6e7e8e9eaf1f2f3f4f5f6f7f8f9faffda000e0443004d0059004b00003f00fdfcafe7febf9ffafd" +
            "fca28a28afffd9",
    )

    /** 16 x 8 greyscale JPEG from Pillow 12.3, every sample 100. */
    private val greyJpeg = hex(
            "ffd8ffe000104a46494600010100000100010000ffdb00430002010101010102010101020202020204030202020205040403" +
            "04060506060605060606070908060709070606080b08090a0a0a0a0a06080b0c0b0a0c090a0a0affc0000b08000800100101" +
            "1100ffc4001f0000010501010101010100000000000000000102030405060708090a0bffc400b51000020103030204030505" +
            "04040000017d01020300041105122131410613516107227114328191a1082342b1c11552d1f02433627282090a161718191a" +
            "25262728292a3435363738393a434445464748494a535455565758595a636465666768696a737475767778797a8384858687" +
            "88898a92939495969798999aa2a3a4a5a6a7a8a9aab2b3b4b5b6b7b8b9bac2c3c4c5c6c7c8c9cad2d3d4d5d6d7d8d9dae1e2" +
            "e3e4e5e6e7e8e9eaf1f2f3f4f5f6f7f8f9faffda0008010100003f00f0fa2bffd9",
    )

    /** 16 x 8 lossless greyscale JP2 from Pillow 12.3, every sample 100. */
    private val greyJp2 = hex(
            "0000000c6a5020200d0a870a00000014667479706a703220000000006a7032200000002d6a70326800000016696864720000" +
            "0008000000100001070700000000000f636f6c7201000000000011000000926a703263ff4fff510029000000000010000000" +
            "080000000000000000000000100000000800000000000000000001070101ff52000c00000001000304040001ff5c000d4040" +
            "484850484850484850ff640025000143726561746564206279204f70656e4a5045472076657273696f6e20322e352e34ff90" +
            "000a0000000000170001ff93c3e70608515f808080ffd9",
    )

    private fun imageDict(image: PdfImage): io.github.yuroyami.kitepdf.core.parser.PdfDictionary {
        val bytes = PdfBuilder().page(width = 20.0, height = 20.0) { drawImage(image, 0.0, 0.0, 16.0, 8.0) }.build()
        val doc = KitePDF.open(bytes)
        return doc.pages[0].resources!!.getDict("XObject", doc)!!.map.values.single().resolve(doc)
            .let { it as io.github.yuroyami.kitepdf.core.parser.PdfStream }.dict
    }

    @Test
    fun jpeg_passthrough_preserves_encoded_bytes() {
        val image = PdfImage.jpeg(greyJpeg)
        val bytes = PdfBuilder().page(width = 20.0, height = 20.0) {
            drawImage(image, 0.0, 0.0, 16.0, 8.0)
        }.build()
        val doc = KitePDF.open(bytes)
        val canvas = RecordingCanvas()
        doc.pages[0].renderTo(canvas, KiteMatrix.IDENTITY)
        assertEquals(16, canvas.calls.filterIsInstance<RecordingCanvas.Call.Image>().single().image.width)
        val dict = imageDict(image)
        assertEquals("DCTDecode", dict.getName("Filter"))
        assertEquals("DeviceGray", dict.getName("ColorSpace"), "one component is grey (ISO 32000-1, Table 89; #745)")
    }

    @Test
    fun a_cmyk_jpeg_is_declared_cmyk_with_the_inverted_decode_of_an_adobe_file() {
        val dict = imageDict(PdfImage.jpeg(cmykJpeg))
        assertEquals("DeviceCMYK", dict.getName("ColorSpace"))
        assertEquals("[1, 0, 1, 0, 1, 0, 1, 0]", dict.getArray("Decode")?.map { (it as io.github.yuroyami.kitepdf.core.parser.PdfInt).value }.toString())
        assertEquals(16L, (dict["Width"] as io.github.yuroyami.kitepdf.core.parser.PdfInt).value)
        // And it draws red, as Poppler draws the same dictionary.
        val bytes = PdfBuilder().page(width = 16.0, height = 8.0) { drawImage(PdfImage.jpeg(cmykJpeg), 0.0, 0.0, 16.0, 8.0) }.build()
        val canvas = RecordingCanvas()
        KitePDF.open(bytes).pages[0].renderTo(canvas, KiteMatrix.IDENTITY)
        val rgba = assertNotNull(canvas.calls.filterIsInstance<RecordingCanvas.Call.Image>().single().image.toRgbaBytes())
        assertTrue((rgba[0].toInt() and 0xFF) > 200 && (rgba[1].toInt() and 0xFF) < 80, "red, not ${rgba.take(3)}")
    }

    @Test
    fun a_jpeg_whose_frame_differs_from_the_size_given_is_refused() {
        assertFailsWith<IllegalArgumentException> { PdfImage.jpeg(cmykJpeg, 640, 320) }
        assertFailsWith<IllegalArgumentException> { PdfImage.jpeg(cmykJpeg, 16, 8, grayscale = true) }
        assertFailsWith<IllegalArgumentException> { PdfImage.jpeg(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 1, 2, 3, 0xFF.toByte(), 0xD9.toByte())) }
        assertEquals("DeviceGray", imageDict(PdfImage.jpeg(greyJpeg, 16, 8, grayscale = true)).getName("ColorSpace"))
    }

    @Test
    fun a_jpx_image_leaves_the_colour_space_to_its_data_unless_asked() {
        assertNull(imageDict(PdfImage.jpx(greyJp2, 16, 8))["ColorSpace"], "the JPEG 2000 data's own space applies")
        assertEquals("DeviceGray", imageDict(PdfImage.jpx(greyJp2, 16, 8, "DeviceGray")).getName("ColorSpace"))
    }

    @Test
    fun same_image_instance_shared_across_pages() {
        val image = PdfImage.gray(ByteArray(4) { 0x7F }, 2, 2)
        val bytes = PdfBuilder()
            .page(width = 50.0, height = 50.0) { drawImage(image, 0.0, 0.0, 10.0, 10.0) }
            .page(width = 50.0, height = 50.0) { drawImage(image, 5.0, 5.0, 10.0, 10.0) }
            .build()
        // Both pages parse and each draws the (shared) image.
        val doc = KitePDF.open(bytes)
        for (p in 0 until 2) {
            val canvas = RecordingCanvas()
            doc.pages[p].renderTo(canvas, KiteMatrix.IDENTITY)
            assertEquals(1, canvas.calls.filterIsInstance<RecordingCanvas.Call.Image>().size, "page $p missing image")
        }
    }
}
