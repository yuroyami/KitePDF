package io.github.yuroyami.kitepdf.core.render

import io.github.yuroyami.imagekodec.ImageKodec

/**
 * The CMYK samples of a four-component JPEG in a PDF (#473). DCTDecode returns CMYK, either as
 * stored or converted back from YCCK (ISO 32000-1, 7.4.8, Table 13). ImageKodec exposes the stored
 * components in one decode, before its standalone JPEG conversion inverts and multiplies the ink.
 */
internal object JpegInk {

    /** The PDF's transform, used only when the JPEG has no Adobe marker. */
    class Layout(val colorTransform: Int?)

    /** Identify four components without decoding the image or walking its entropy-coded data. */
    fun layout(jpeg: ByteArray, colorTransform: Int?): Layout? {
        if (jpeg.size < 4 || u8(jpeg, 0) != 0xFF || u8(jpeg, 1) != 0xD8) return null
        var i = 2
        while (i + 1 < jpeg.size) {
            if (u8(jpeg, i) != 0xFF) return null
            var marker = u8(jpeg, i + 1)
            i += 2
            while (marker == 0xFF && i < jpeg.size) marker = u8(jpeg, i++)
            if (marker == 0xD9 || marker == 0xDA) return null
            if (marker == 0x01 || marker == 0xD8 || marker in 0xD0..0xD7) continue
            if (i + 1 >= jpeg.size) return null
            val length = (u8(jpeg, i) shl 8) or u8(jpeg, i + 1)
            if (length < 2 || length > jpeg.size - i) return null
            // SOF0..SOF15, except DHT, JPG and DAC. The codec checks the coding and precision.
            if (marker in 0xC0..0xCF && marker != 0xC4 && marker != 0xC8 && marker != 0xCC) {
                return if (length >= 20 && u8(jpeg, i + 7) == 4) Layout(colorTransform) else null
            }
            i += length
        }
        return null
    }

    /** CMYK samples at [reduction] (1, 2, 4 or 8), or null when the JPEG does not decode. */
    fun decode(jpeg: ByteArray, layout: Layout, reduction: Int): KiteImageSamples.Decoded? {
        val decoded = runCatching { ImageKodec.decodeJpegComponents(jpeg, reduction) }.getOrNull() ?: return null
        if (decoded.componentCount != 4) return null
        // The last Adobe marker overrides /ColorTransform, as in MuPDF. With neither, four
        // components mean CMYK (ISO 32000-1, Table 13). /Decode still applies after this step.
        val transform = if (decoded.adobeTransform >= 0) decoded.adobeTransform else layout.colorTransform ?: 0
        val samples = if (transform == 0) decoded.samples else decoded.samples.copyOf().also { ycckToCmyk(it) }
        return KiteImageSamples.Decoded(decoded.width, decoded.height, samples)
    }

    /**
     * YCCK to CMYK in place: YCbCr to RGB, then each of R, G and B inverted, with K as is. These are
     * the fixed-point tables of libjpeg (jdcolor.c), which MuPDF decodes with.
     */
    private fun ycckToCmyk(samples: ByteArray) {
        var i = 0
        while (i + 3 < samples.size) {
            val y = samples[i].toInt() and 0xFF
            val cb = (samples[i + 1].toInt() and 0xFF) - 128
            val cr = (samples[i + 2].toInt() and 0xFF) - 128
            val r = y + ((91881 * cr + HALF) shr 16)
            val g = y + ((-22554 * cb - 46802 * cr + HALF) shr 16)
            val b = y + ((116130 * cb + HALF) shr 16)
            samples[i] = (255 - r).coerceIn(0, 255).toByte()
            samples[i + 1] = (255 - g).coerceIn(0, 255).toByte()
            samples[i + 2] = (255 - b).coerceIn(0, 255).toByte()
            i += 4
        }
    }

    private fun u8(b: ByteArray, i: Int): Int = b[i].toInt() and 0xFF

    private const val HALF = 1 shl 15
}
