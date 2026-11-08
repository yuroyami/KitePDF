package io.github.yuroyami.kitepdf.core.render

import kotlin.math.abs
import kotlin.math.pow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotSame
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * A reading theme that asks for it recolours line art, a grey symbol on white or on no paper, as
 * it does text, and leaves photos alone (#458).
 */
class LineArtThemeTest {

    private val themes = listOf(ReaderTheme.Dark, ReaderTheme.Sepia, ReaderTheme.Light)

    /** Whether the pixel at (x, y) of a 32 by 32 square lies on the plus sign of the issue's SVG. */
    private fun onPlus(x: Int, y: Int) = (x in 12 until 20 && y in 4 until 28) || (y in 12 until 20 && x in 4 until 28)

    /** A 32 by 32 plus of [ink], a grey level, on white paper when [opaque], else on none, as RGB with an alpha plane. */
    private fun plus(ink: Int, opaque: Boolean): KiteImageData {
        val rgb = ByteArray(32 * 32 * 3)
        val alpha = ByteArray(32 * 32)
        for (y in 0 until 32) for (x in 0 until 32) {
            val i = y * 32 + x
            val v = if (onPlus(x, y)) ink else 255
            rgb.fill(v.toByte(), 3 * i, 3 * i + 3)
            alpha[i] = if (opaque || onPlus(x, y)) 0xFF.toByte() else 0
        }
        return image(rgb, if (opaque) null else alpha)
    }

    private fun image(rgb: ByteArray, alpha: ByteArray?) = KiteImageData(
        32, 32, 8, "DeviceRGB", KiteImageData.Kind.RAW, ByteArray(0), pixelBytes = rgb,
        softMaskAlpha = alpha, softMaskWidth = if (alpha != null) 32 else 0, softMaskHeight = if (alpha != null) 32 else 0,
        resolvedColorSpace = KiteColorSpace.DeviceRGB,
    )

    /** A photo: a colour gradient, or with [grey] a smooth grey one. */
    private fun photo(grey: Boolean) = image(
        ByteArray(32 * 32 * 3) { i ->
            val p = i / 3
            val (x, y) = p % 32 to p / 32
            (if (grey) (x + y) * 4 else when (i % 3) { 0 -> x * 8; 1 -> y * 8; else -> 128 }).toByte()
        },
        null,
    )

    private fun drawn(theme: ReaderTheme, image: KiteImageData): KiteImageData {
        val canvas = RecordingCanvas()
        theme.wrap(canvas).drawImage(image, KiteMatrix.IDENTITY)
        return if (canvas.calls.isEmpty()) image else (canvas.calls.single() as RecordingCanvas.Call.Image).image
    }

    private fun luminance(c: RgbColor): Double {
        fun lin(v: Double) = if (v <= 0.04045) v / 12.92 else ((v + 0.055) / 1.055).pow(2.4)
        return 0.2126 * lin(c.r) + 0.7152 * lin(c.g) + 0.0722 * lin(c.b)
    }

    private fun contrast(a: RgbColor, b: RgbColor): Double {
        val (x, y) = luminance(a) to luminance(b)
        return (maxOf(x, y) + 0.05) / (minOf(x, y) + 0.05)
    }

    private fun colourAt(image: KiteImageData, x: Int, y: Int): Pair<RgbColor, Int> {
        val rgba = image.toRgbaBytes()!!
        val o = 4 * (y * image.width + x)
        fun c(i: Int) = (rgba[o + i].toInt() and 0xFF) / 255.0
        return RgbColor(c(0), c(1), c(2)) to (rgba[o + 3].toInt() and 0xFF)
    }

    @Test
    fun a_symbol_keeps_the_contrast_of_text_in_every_theme() {
        for (theme in themes) for (ink in listOf(0, 128)) for (opaque in listOf(false, true)) {
            val lineArt = theme.withImages(ReaderImages.LineArt)
            val image = drawn(lineArt, plus(ink, opaque))
            val (symbol, symbolAlpha) = colourAt(image, 16, 16)
            val text = lineArt.mapColor(RgbColor.gray(ink / 255.0))
            assertEquals(255, symbolAlpha)
            assertTrue(abs(luminance(symbol) - luminance(text)) < 0.01, "$theme ink $ink: the symbol is $symbol, text of its grey is $text")
            assertTrue(
                contrast(symbol, theme.background) >= minOf(4.5, contrast(RgbColor.gray(ink / 255.0), RgbColor.WHITE)) - 0.05,
                "$theme ink $ink opaque $opaque: contrast ${contrast(symbol, theme.background)} on the paper",
            )
            val (paper, paperAlpha) = colourAt(image, 0, 0)
            if (opaque) {
                assertEquals(255, paperAlpha)
                assertTrue(contrast(paper, theme.background) < 1.2, "$theme: the white box stays a box of $paper")
            } else {
                assertEquals(0, paperAlpha, "the transparent paper stays transparent")
            }
        }
    }

    @Test
    fun a_photo_keeps_its_colours() {
        val lineArt = ReaderTheme.Dark.withImages(ReaderImages.LineArt)
        for (grey in listOf(false, true)) {
            val photo = photo(grey)
            assertSame(photo, drawn(lineArt, photo), "a photo changed, grey $grey")
        }
    }

    @Test
    fun a_theme_leaves_images_alone_unless_asked() {
        val symbol = plus(0, opaque = false)
        assertSame(symbol, drawn(ReaderTheme.Dark, symbol))
        assertEquals(ReaderImages.Unchanged, ReaderTheme.Dark.images)
        assertTrue(ReaderTheme.Dark != ReaderTheme.Dark.withImages(ReaderImages.LineArt))
        assertEquals(ReaderTheme.Dark.withImages(ReaderImages.LineArt), ReaderTheme.Dark.withImages(ReaderImages.LineArt))
    }

    @Test
    fun a_stencil_mask_paints_with_the_themed_fill() {
        val stencil = KiteImageData(
            8, 1, 1, "DeviceGray", KiteImageData.Kind.RAW, ByteArray(0), pixelBytes = byteArrayOf(0x0F),
            isImageMask = true, maskFill = RgbColor.BLACK,
        )
        val themed = drawn(ReaderTheme.Dark.withImages(ReaderImages.LineArt), stencil)
        assertEquals(ReaderTheme.Dark.mapColor(RgbColor.BLACK), themed.maskFill)
    }

    @Test
    fun a_symbol_drawn_again_reads_its_pixels_once() {
        val lineArt = ReaderTheme.Dark.withImages(ReaderImages.LineArt)
        val symbol = plus(0, opaque = true)
        val first = drawn(lineArt, symbol)
        assertNotSame(symbol, first)
        assertSame(first, drawn(lineArt, symbol))
    }
}
