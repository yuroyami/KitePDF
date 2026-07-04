package io.github.yuroyami.kitepdf

import io.github.yuroyami.kitepdf.render.ImageXObject
import io.github.yuroyami.kitepdf.render.toRgbaBytes
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Decode correctness for the pure-Kotlin PNG path. EPUB's render test records the
 * [ImageXObject] without rasterizing it, so pixel accuracy is proven here: build
 * a PNG, decode via [ImageXObject.fromEncodedImage], assemble RGBA via
 * [toRgbaBytes], and check pixels. IDAT uses a STORED deflate block and dummy
 * CRCs — the decoder verifies neither.
 */
class PngDecoderTest {

    // ---- assertions ----------------------------------------------------------

    private fun rgbaOf(png: ByteArray): Pair<ImageXObject, ByteArray> {
        val img = ImageXObject.fromEncodedImage(png) ?: error("PNG failed to decode")
        val rgba = img.toRgbaBytes() ?: error("toRgbaBytes returned null")
        return img to rgba
    }

    private fun ByteArray.px(i: Int): List<Int> =
        (0 until 4).map { this[i * 4 + it].toInt() and 0xFF }

    @Test
    fun grayscale_8bit() {
        val png = png(w = 2, h = 2, bitDepth = 8, colorType = 0, scanlines = byteArrayOf(0, 0, 255.toByte(), 0, 128.toByte(), 64))
        val (img, rgba) = rgbaOf(png)
        assertEquals(2, img.width); assertEquals(2, img.height)
        assertEquals(listOf(0, 0, 0, 255), rgba.px(0))
        assertEquals(listOf(255, 255, 255, 255), rgba.px(1))
        assertEquals(listOf(128, 128, 128, 255), rgba.px(2))
        assertEquals(listOf(64, 64, 64, 255), rgba.px(3))
    }

    @Test
    fun truecolor_8bit_none_filter() {
        // Row: filter=0, then RGB red, RGB blue.
        val png = png(w = 2, h = 1, bitDepth = 8, colorType = 2, scanlines = byteArrayOf(0, 255.toByte(), 0, 0, 0, 0, 255.toByte()))
        val (_, rgba) = rgbaOf(png)
        assertEquals(listOf(255, 0, 0, 255), rgba.px(0))
        assertEquals(listOf(0, 0, 255, 255), rgba.px(1))
    }

    @Test
    fun truecolor_8bit_sub_filter_is_reversed() {
        // filter=1 (Sub): pixel0 (10,20,30) verbatim; pixel1 stored as deltas from the left pixel.
        val sub = byteArrayOf(1, 10, 20, 30, (40 - 10).toByte(), (60 - 20).toByte(), (80 - 30).toByte())
        val png = png(w = 2, h = 1, bitDepth = 8, colorType = 2, scanlines = sub)
        val (_, rgba) = rgbaOf(png)
        assertEquals(listOf(10, 20, 30, 255), rgba.px(0))
        assertEquals(listOf(40, 60, 80, 255), rgba.px(1))
    }

    @Test
    fun truecolor_up_filter_is_reversed() {
        // Two rows; second row filter=2 (Up) stores deltas from the row above.
        val row0 = byteArrayOf(0, 10, 20, 30)
        val row1 = byteArrayOf(2, (50 - 10).toByte(), (60 - 20).toByte(), (70 - 30).toByte())
        val png = png(w = 1, h = 2, bitDepth = 8, colorType = 2, scanlines = row0 + row1)
        val (_, rgba) = rgbaOf(png)
        assertEquals(listOf(10, 20, 30, 255), rgba.px(0))
        assertEquals(listOf(50, 60, 70, 255), rgba.px(1))
    }

    @Test
    fun rgba_8bit_alpha_becomes_softmask() {
        val png = png(w = 2, h = 1, bitDepth = 8, colorType = 6, scanlines = byteArrayOf(0, 255.toByte(), 0, 0, 128.toByte(), 0, 255.toByte(), 0, 255.toByte()))
        val (_, rgba) = rgbaOf(png)
        assertEquals(listOf(255, 0, 0, 128), rgba.px(0))
        assertEquals(listOf(0, 255, 0, 255), rgba.px(1))
    }

    @Test
    fun palette_with_trns_alpha() {
        val plte = byteArrayOf(255.toByte(), 0, 0, 0, 255.toByte(), 0) // idx0 red, idx1 green
        val trns = byteArrayOf(128.toByte()) // idx0 alpha 128; idx1 defaults opaque
        val png = png(
            w = 2, h = 1, bitDepth = 8, colorType = 3,
            scanlines = byteArrayOf(0, 0, 1), // indices 0,1
            extra = chunk("PLTE", plte) + chunk("tRNS", trns),
        )
        val (_, rgba) = rgbaOf(png)
        assertEquals(listOf(255, 0, 0, 128), rgba.px(0))
        assertEquals(listOf(0, 255, 0, 255), rgba.px(1))
    }

    @Test
    fun grayscale_1bit_expands() {
        // 8 px, one byte 0b10101010 -> alternating white/black.
        val png = png(w = 8, h = 1, bitDepth = 1, colorType = 0, scanlines = byteArrayOf(0, 0xAA.toByte()))
        val (_, rgba) = rgbaOf(png)
        assertEquals(listOf(255, 255, 255, 255), rgba.px(0))
        assertEquals(listOf(0, 0, 0, 255), rgba.px(1))
        assertEquals(listOf(255, 255, 255, 255), rgba.px(2))
    }

    @Test
    fun interlaced_is_not_supported() {
        val png = png(w = 2, h = 1, bitDepth = 8, colorType = 2, interlace = 1, scanlines = byteArrayOf(0, 1, 2, 3, 4, 5, 6))
        assertNull(ImageXObject.fromEncodedImage(png))
    }

    @Test
    fun truncated_png_returns_null() {
        val sigOnly = SIGNATURE + byteArrayOf(0, 0, 0, 1)
        assertNull(ImageXObject.fromEncodedImage(sigOnly))
    }

    // ---- PNG assembly --------------------------------------------------------

    private val SIGNATURE = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)

    private fun be32(n: Int) = byteArrayOf((n ushr 24).toByte(), (n ushr 16).toByte(), (n ushr 8).toByte(), n.toByte())

    private fun chunk(type: String, data: ByteArray): ByteArray =
        be32(data.size) + type.encodeToByteArray() + data + byteArrayOf(0, 0, 0, 0) // CRC unchecked

    /** Wrap [data] in a zlib stream with a single STORED deflate block. */
    private fun zlibStore(data: ByteArray): ByteArray {
        val len = data.size
        val nlen = len.inv() and 0xFFFF
        val header = byteArrayOf(0x78, 0x01, 0x01) // zlib CMF/FLG + BFINAL|STORED
        val lens = byteArrayOf((len and 0xFF).toByte(), ((len ushr 8) and 0xFF).toByte(), (nlen and 0xFF).toByte(), ((nlen ushr 8) and 0xFF).toByte())
        return header + lens + data + byteArrayOf(0, 0, 0, 1) // dummy adler32 (unverified)
    }

    private fun png(
        w: Int, h: Int, bitDepth: Int, colorType: Int, scanlines: ByteArray,
        interlace: Int = 0, extra: ByteArray = ByteArray(0),
    ): ByteArray {
        val ihdr = be32(w) + be32(h) + byteArrayOf(bitDepth.toByte(), colorType.toByte(), 0, 0, interlace.toByte())
        return SIGNATURE + chunk("IHDR", ihdr) + extra + chunk("IDAT", zlibStore(scanlines)) + chunk("IEND", ByteArray(0))
    }
}
