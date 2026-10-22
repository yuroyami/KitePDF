package io.github.yuroyami.kitepdf.core.render

/** What a [ReaderTheme] does to the images of a page (#458). */
public enum class ReaderImages {
    /** Every image keeps its own colours, so a photo never changes. The default. */
    Unchanged,

    /**
     * An image that is line art takes the theme's colours, as text does. Line art is an image whose
     * visible pixels are all grey and fall into one or two narrow bands of lightness, such as a black
     * or grey symbol or equation on white paper or on none. A stencil mask, which paints with the fill
     * colour, counts too. A photo and coloured artwork keep their own colours.
     */
    LineArt,
}

/** The most pixels an image may have for [themedLineArt] to read it. */
private const val MAX_LINE_ART_PIXELS = 16_000_000L

/** About how many pixels the line-art test reads of a large image. */
private const val LINE_ART_SAMPLES = 65_536

/**
 * This image with the colours of line art mapped through [mapColor], a theme's colour map, or this
 * image itself when it is not line art (#458). The answer for the last theme is kept, so a page
 * drawn again reads its images once.
 */
internal fun KiteImageData.themedLineArt(mapColor: (RgbColor) -> RgbColor, key: String): KiteImageData {
    lineArt?.let { (map, image) -> if (map === mapColor) return image }
    val themed = if (isImageMask) {
        copy(maskFill = mapColor(maskFill ?: RgbColor.BLACK), identity = bitmapIdentity.themed(key))
    } else {
        recolouredLineArt(mapColor, key) ?: this
    }
    lineArt = mapColor to themed
    return themed
}

/** The recoloured image when this one is line art, else null. */
private fun KiteImageData.recolouredLineArt(mapColor: (RgbColor) -> RgbColor, key: String): KiteImageData? {
    if (kind != KiteImageData.Kind.RAW || width <= 0 || height <= 0) return null
    val pixels = width.toLong() * height
    if (pixels > MAX_LINE_ART_PIXELS) return null
    val rgba = toRgbaBytes() ?: return null
    if (!isLineArt(rgba, pixels.toInt())) return null
    // Each grey level maps once, through the theme, as a colour of text or a fill would.
    val levels = ByteArray(256 * 3)
    for (v in 0 until 256) {
        val c = mapColor(RgbColor.gray(v / 255.0))
        levels[3 * v] = channel(c.r)
        levels[3 * v + 1] = channel(c.g)
        levels[3 * v + 2] = channel(c.b)
    }
    val n = pixels.toInt()
    val rgb = ByteArray(n * 3)
    var opaque = true
    for (i in 0 until n) {
        val v = grey(rgba, 4 * i)
        levels.copyInto(rgb, 3 * i, 3 * v, 3 * v + 3)
        if (rgba[4 * i + 3] != 0xFF.toByte()) opaque = false
    }
    val alpha = if (opaque) null else ByteArray(n) { rgba[4 * it + 3] }
    return KiteImageData(
        width, height, 8, "DeviceRGB", KiteImageData.Kind.RAW, ByteArray(0), pixelBytes = rgb,
        softMaskAlpha = alpha, softMaskWidth = if (alpha != null) width else 0, softMaskHeight = if (alpha != null) height else 0,
        resolvedColorSpace = KiteColorSpace.DeviceRGB, interpolate = interpolate,
    ).withIdentity(bitmapIdentity.themed(key))
}

/**
 * Whether the [count] pixels of [rgba] are line art: each visible pixel is grey, and two bands of 16
 * grey levels hold nine tenths of the visible weight. Antialiased edges of a symbol keep its grey
 * and lose only alpha, so they stay in its band. A large image is read at a stride.
 */
private fun isLineArt(rgba: ByteArray, count: Int): Boolean {
    val bands = LongArray(16)
    var total = 0L
    val step = maxOf(1, count / LINE_ART_SAMPLES)
    var i = 0
    while (i < count) {
        val o = 4 * i
        val a = rgba[o + 3].toInt() and 0xFF
        if (a >= VISIBLE) {
            val r = rgba[o].toInt() and 0xFF
            val g = rgba[o + 1].toInt() and 0xFF
            val b = rgba[o + 2].toInt() and 0xFF
            if (maxOf(r, g, b) - minOf(r, g, b) > GREY_SPREAD) return false
            bands[grey(rgba, o) ushr 4] += a.toLong()
            total += a
        }
        i += step
    }
    if (total == 0L) return false
    bands.sort()
    return (bands[15] + bands[14]) * 10 >= total * 9
}

/** The grey level of the pixel at [o] in [rgba], from its luminance. */
private fun grey(rgba: ByteArray, o: Int): Int {
    val r = rgba[o].toInt() and 0xFF
    val g = rgba[o + 1].toInt() and 0xFF
    val b = rgba[o + 2].toInt() and 0xFF
    return (r * 2126 + g * 7152 + b * 722 + 5000) / 10000
}

private fun channel(v: Double): Byte = (v.coerceIn(0.0, 1.0) * 255.0 + 0.5).toInt().toByte()

/** The alpha below which a pixel does not count, and the most its channels may differ to count as grey. */
private const val VISIBLE = 32
private const val GREY_SPREAD = 16
