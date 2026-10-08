package io.github.yuroyami.kitepdf.core.render

import io.github.yuroyami.imagekodec.ImageKodec
import io.github.yuroyami.imagekodec.codec.JpxDecoder

/**
 * The samples of a JPEG or JPEG 2000 image, kept encoded until a draw needs them (#381). A draw
 * decodes them at the size it needs: ImageKodec shrinks a JPEG inside its inverse DCT and
 * drops the finest wavelet levels of a JPEG 2000 image, so the full-size samples never exist
 * for an image drawn smaller than its pixels. Nothing is kept between decodes.
 */
internal class KiteImageSamples private constructor(
    private val bytes: ByteArray,
    /** The width of the full-size samples. */
    val width: Int,
    /** The height of the full-size samples. */
    val height: Int,
    private val jpx: Boolean,
    /** For a JPEG: one grey sample a pixel, else three RGB samples. A JPEG 2000 image decides itself. */
    private val gray: Boolean,
    /** For a four-component JPEG of a PDF: its headers, so that it decodes to four CMYK samples a pixel (#470). */
    private val ink: JpegInk.Layout?,
    /** The colour space the samples come in, `DeviceGray`, `DeviceRGB` or `DeviceCMYK`. */
    val colorSpace: String,
) {
    /** The bytes this holds: the encoded image. */
    val encodedSize: Int get() = bytes.size

    /** True when [other] is the very array of the encoded image this holds, so it is not counted twice. */
    fun holds(other: ByteArray): Boolean = other === bytes

    /**
     * True once the data failed to decode. Every reduction reads all of the entropy-coded data,
     * so a file that failed once fails again, and a draw goes straight to its fallback. A race
     * only costs one more decode that fails.
     */
    private var failed = false

    /** The samples with each side divided by [reduction], 1, 2, 4 or 8, or null when they do not decode. */
    fun decode(reduction: Int): Decoded? {
        if (failed) return null
        val decoded = try {
            if (jpx) {
                JpxDecoder.decode(bytes, reduction)?.let { Decoded(it.width, it.height, it.pixelBytes) }
            } else if (ink != null) {
                JpegInk.decode(bytes, ink, reduction)
            } else {
                val bitmap = ImageKodec.decodeReduced(bytes, reduction)
                Decoded(bitmap.width, bitmap.height, if (gray) bitmap.toGrayBytes() else bitmap.toRgbBytes())
            }
        } catch (damaged: Exception) {
            null
        } catch (outOfMemory: Throwable) {
            // Running out of memory at this size says nothing of the data: a smaller decode may fit.
            return null
        }
        if (decoded == null) failed = true
        return decoded
    }

    class Decoded(val width: Int, val height: Int, val bytes: ByteArray)

    companion object {
        /**
         * [bytes], a JPEG, as samples that decode on demand, or null when its headers name a
         * coding ImageKodec does not decode. Nothing decodes here:
         * a check at an eighth read every entropy-coded byte, about a third of the page's render
         * time, and the draw read them again (#475). A file whose data then fails to decode fails
         * at its first draw, and the image draws as a placeholder (#184). With [ink], the samples
         * are CMYK.
         */
        fun jpeg(bytes: ByteArray, gray: Boolean, ink: JpegInk.Layout? = null): KiteImageSamples? {
            val info = runCatching { ImageKodec.probe(bytes) }.getOrNull() ?: return null
            if (!info.isDecodable || info.width <= 0 || info.height <= 0) return null
            val space = if (ink != null) "DeviceCMYK" else if (gray) "DeviceGray" else "DeviceRGB"
            return KiteImageSamples(bytes, info.width, info.height, jpx = false, gray, ink, space)
        }

        /**
         * [bytes], a JPEG 2000 image without an opacity channel, as samples that decode on
         * demand, or null when it does not decode or carries opacity. A decode at an eighth
         * checks it and tells whether it comes out grey or RGB.
         */
        fun jpx(bytes: ByteArray): KiteImageSamples? {
            val info = runCatching { ImageKodec.probe(bytes) }.getOrNull() ?: return null
            if (!info.isDecodable || info.width <= 0 || info.height <= 0) return null
            val check = runCatching { JpxDecoder.decode(bytes, 8) }.getOrNull() ?: return null
            if (check.alpha != null) return null
            return KiteImageSamples(bytes, info.width, info.height, jpx = true, gray = false, ink = null, check.colorSpace)
        }
    }
}
