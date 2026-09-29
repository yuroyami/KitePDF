package io.github.yuroyami.kitepdf.core.render

import io.github.yuroyami.kiteimagecodec.KiteImageCodec
import io.github.yuroyami.kiteimagecodec.codec.JpxDecoder

/**
 * The samples of a JPEG or JPEG 2000 image, kept encoded until a draw needs them (#381). A draw
 * decodes them at the size it needs: KiteImageCodec shrinks a JPEG inside its inverse DCT and
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
    /** The colour space the samples come in, `DeviceGray` or `DeviceRGB`. */
    val colorSpace: String,
) {
    /** The bytes this holds: the encoded image. */
    val encodedSize: Int get() = bytes.size

    /** The samples with each side divided by [reduction], 1, 2, 4 or 8, or null when they do not decode. */
    fun decode(reduction: Int): Decoded? = runCatching {
        if (jpx) {
            JpxDecoder.decode(bytes, reduction)?.let { Decoded(it.width, it.height, it.pixelBytes) }
        } else {
            val bitmap = KiteImageCodec.decodeReduced(bytes, reduction)
            Decoded(bitmap.width, bitmap.height, if (gray) bitmap.toGrayBytes() else bitmap.toRgbBytes())
        }
    }.getOrNull()

    class Decoded(val width: Int, val height: Int, val bytes: ByteArray)

    companion object {
        /**
         * [bytes], a JPEG, as samples that decode on demand, or null when KiteImageCodec cannot
         * decode it. A decode at an eighth reads every entropy-coded byte, so it fails where the
         * full decode would, and the caller can still hand the file to the platform decoder.
         */
        fun jpeg(bytes: ByteArray, gray: Boolean): KiteImageSamples? {
            val info = runCatching { KiteImageCodec.probe(bytes) }.getOrNull() ?: return null
            if (!info.isDecodable || info.width <= 0 || info.height <= 0) return null
            val samples = KiteImageSamples(bytes, info.width, info.height, jpx = false, gray, if (gray) "DeviceGray" else "DeviceRGB")
            return samples.takeIf { it.decode(8) != null }
        }

        /**
         * [bytes], a JPEG 2000 image without an opacity channel, as samples that decode on
         * demand, or null when it does not decode or carries opacity. A decode at an eighth
         * checks it and tells whether it comes out grey or RGB.
         */
        fun jpx(bytes: ByteArray): KiteImageSamples? {
            val info = runCatching { KiteImageCodec.probe(bytes) }.getOrNull() ?: return null
            if (!info.isDecodable || info.width <= 0 || info.height <= 0) return null
            val check = runCatching { JpxDecoder.decode(bytes, 8) }.getOrNull() ?: return null
            if (check.alpha != null) return null
            return KiteImageSamples(bytes, info.width, info.height, jpx = true, gray = false, check.colorSpace)
        }
    }
}
