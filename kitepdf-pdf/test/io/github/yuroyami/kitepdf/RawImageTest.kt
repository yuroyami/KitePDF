package io.github.yuroyami.kitepdf

import io.github.yuroyami.kitepdf.core.parser.PdfArray
import io.github.yuroyami.kitepdf.core.parser.PdfBoolean
import io.github.yuroyami.kitepdf.core.parser.PdfInt
import io.github.yuroyami.kitepdf.core.parser.PdfName
import io.github.yuroyami.kitepdf.core.parser.PdfStream
import io.github.yuroyami.kitepdf.core.render.KiteMatrix
import io.github.yuroyami.kitepdf.core.render.KiteRasterCanvas
import io.github.yuroyami.kitepdf.core.render.RgbColor
import io.github.yuroyami.kitepdf.writer.PdfBuilder
import io.github.yuroyami.kitepdf.writer.PdfImage
import io.github.yuroyami.kitepdf.writer.PdfImageColorSpace
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** PdfImage.raw writes CMYK, 1 to 16 bits, palettes, /Decode and /Interpolate (ISO 32000-1, 8.9.5, Table 89; #920). */
class RawImageTest {

    /** A 100 by 100 page that draws [image] over all of it, read back and rendered. */
    private fun page(image: PdfImage): Pair<PdfStream, Int> {
        val pdf = PdfBuilder().page(100.0, 100.0) { drawImage(image, 0.0, 0.0, 100.0, 100.0) }.build()
        val doc = PdfDocument.open(pdf)
        val xobjects = doc.pages[0].resources!!.getDict("XObject", doc)!!
        val stream = xobjects[xobjects.keys.single()]!!.resolve(doc) as PdfStream
        val canvas = KiteRasterCanvas(100, 100)
        canvas.clear(RgbColor.WHITE)
        doc.pages[0].renderTo(canvas, KiteMatrix.IDENTITY)
        return stream to canvas.toRaster()[50, 50]
    }

    private fun rgb(p: Int) = Triple((p shr 16) and 0xFF, (p shr 8) and 0xFF, p and 0xFF)

    private fun near(expected: Triple<Int, Int, Int>, got: Int, tolerance: Int = 40) {
        val (r, g, b) = rgb(got)
        assertTrue(kotlin.math.abs(r - expected.first) <= tolerance && kotlin.math.abs(g - expected.second) <= tolerance && kotlin.math.abs(b - expected.third) <= tolerance, "expected $expected, got ${rgb(got)}")
    }

    @Test
    fun cmyk_eight_bit() {
        // Full cyan.
        val (dict, pixel) = page(PdfImage.raw(ByteArray(2 * 2 * 4) { if (it % 4 == 0) -1 else 0 }, 2, 2, PdfImageColorSpace.DeviceCMYK))
        assertEquals(PdfName("DeviceCMYK"), dict.dict["ColorSpace"])
        assertEquals(PdfInt(8), dict.dict["BitsPerComponent"])
        val (r, g, b) = rgb(pixel)
        assertTrue(r < 80 && g > 120 && b > 180, "cyan, got ${rgb(pixel)}")
    }

    @Test
    fun gray_sixteen_bit() {
        // 0x8000 of 0xFFFF is mid grey.
        val (dict, pixel) = page(PdfImage.raw(ByteArray(2 * 2 * 2) { if (it % 2 == 0) 0x80.toByte() else 0 }, 2, 2, PdfImageColorSpace.DeviceGray, bitsPerComponent = 16))
        assertEquals(PdfInt(16), dict.dict["BitsPerComponent"])
        near(Triple(128, 128, 128), pixel)
    }

    @Test
    fun gray_one_bit_padded_rows() {
        // 10 pixels a row take 2 bytes. Every bit 0 is black.
        val (dict, pixel) = page(PdfImage.raw(ByteArray(2 * 10), 10, 10, PdfImageColorSpace.DeviceGray, bitsPerComponent = 1))
        assertEquals(PdfInt(1), dict.dict["BitsPerComponent"])
        near(Triple(0, 0, 0), pixel, tolerance = 5)
    }

    @Test
    fun indexed_rgb_with_four_colours() {
        val palette = byteArrayOf(-1, 0, 0, 0, -1, 0, 0, 0, -1, 0, 0, 0)
        // 2 bits a sample, every sample index 1: green.
        val (dict, pixel) = page(PdfImage.raw(ByteArray(4) { 0b01010101 }, 4, 4, PdfImageColorSpace.Indexed(PdfImageColorSpace.DeviceRGB, palette), bitsPerComponent = 2))
        val space = dict.dict["ColorSpace"] as PdfArray
        assertEquals(listOf(PdfName("Indexed"), PdfName("DeviceRGB"), PdfInt(3)), space.items.take(3))
        near(Triple(0, 255, 0), pixel, tolerance = 5)
    }

    @Test
    fun rgb_with_interpolate() {
        val (dict, pixel) = page(PdfImage.raw(ByteArray(2 * 2 * 3) { if (it % 3 == 2) -1 else 0 }, 2, 2, PdfImageColorSpace.DeviceRGB, interpolate = true))
        assertEquals(PdfBoolean(true), dict.dict["Interpolate"])
        near(Triple(0, 0, 255), pixel, tolerance = 5)
    }

    @Test
    fun gray_with_an_inverting_decode() {
        // Samples of 0 under /Decode [1 0] read as white.
        val (dict, pixel) = page(PdfImage.raw(ByteArray(4), 2, 2, PdfImageColorSpace.DeviceGray, decode = listOf(1.0, 0.0)))
        assertEquals(listOf(PdfInt(1), PdfInt(0)), (dict.dict["Decode"] as PdfArray).items)
        near(Triple(255, 255, 255), pixel, tolerance = 5)
    }

    @Test
    fun an_icc_space_names_its_profile_and_alternate() {
        val (dict, _) = page(PdfImage.raw(ByteArray(12), 2, 2, PdfImageColorSpace.IccBased(ByteArray(128), 3)))
        val space = dict.dict["ColorSpace"] as PdfArray
        assertEquals(PdfName("ICCBased"), space.items[0])
    }

    @Test
    fun the_checks_match_the_spec() {
        assertFailsWith<IllegalArgumentException> { PdfImage.raw(ByteArray(4), 2, 2, PdfImageColorSpace.DeviceGray, bitsPerComponent = 3) }
        assertFailsWith<IllegalArgumentException> { PdfImage.raw(ByteArray(3), 2, 2, PdfImageColorSpace.DeviceGray) }
        assertFailsWith<IllegalArgumentException> { PdfImage.raw(ByteArray(8), 2, 2, PdfImageColorSpace.Indexed(PdfImageColorSpace.DeviceGray, ByteArray(4)), bitsPerComponent = 16) }
        assertFailsWith<IllegalArgumentException> { PdfImage.raw(ByteArray(4), 2, 2, PdfImageColorSpace.DeviceGray, decode = listOf(1.0)) }
        assertFailsWith<IllegalArgumentException> { PdfImageColorSpace.Indexed(PdfImageColorSpace.DeviceRGB, ByteArray(4)) }
        // Five colours do not fit in 2 bits.
        assertFailsWith<IllegalArgumentException> { PdfImage.raw(ByteArray(4), 4, 4, PdfImageColorSpace.Indexed(PdfImageColorSpace.DeviceGray, ByteArray(5)), bitsPerComponent = 2) }
    }
}
