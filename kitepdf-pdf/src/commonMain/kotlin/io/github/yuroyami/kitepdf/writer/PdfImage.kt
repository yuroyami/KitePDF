package io.github.yuroyami.kitepdf.writer

/**
 * An image ready to embed in a [PdfBuilder] page via
 * [ContentStreamBuilder.drawImage].
 *
 * Two kinds:
 *
 *  - **Raw samples** ([rgba] / [rgb] / [gray]) — uncompressed 8-bit pixel
 *    bytes. Embedded as a `DeviceRGB`/`DeviceGray` image XObject with
 *    `/FlateDecode`. [rgba] splits the alpha channel into a `/SMask` so
 *    transparency is preserved (e.g. a logo with a cut-out background).
 *  - **JPEG passthrough** ([jpeg]) — the encoded JPEG bytes are stored
 *    verbatim with `/DCTDecode`; every conformant PDF reader decodes JPEG
 *    natively, so nothing is re-encoded. JPEG carries no alpha.
 *
 * Instances are compared by identity: pass the same `PdfImage` to multiple
 * pages and it's embedded once and shared across them.
 *
 * Pixels are laid out top row first, left to right — the natural raster order.
 * The PDF image-space flip is handled by [ContentStreamBuilder.drawImage].
 */
class PdfImage private constructor(
    internal val width: Int,
    internal val height: Int,
    internal val colorSpace: String,
    internal val bitsPerComponent: Int,
    /** `null` → raw samples (FlateDecode); otherwise the PDF filter name, e.g. `DCTDecode`. */
    internal val filter: String?,
    /** Colour samples in component order (no alpha). */
    internal val samples: ByteArray,
    /** Optional 8-bit alpha plane (`width*height` bytes) → emitted as a `/SMask`. */
    internal val alpha: ByteArray?,
) {
    init {
        require(width > 0 && height > 0) { "image must be non-empty (was ${width}x$height)" }
    }

    companion object {
        /**
         * 8-bit RGBA, 4 bytes per pixel (R,G,B,A). The alpha channel becomes a
         * `/SMask`; if every pixel is fully opaque the mask is omitted.
         */
        fun rgba(pixels: ByteArray, width: Int, height: Int): PdfImage {
            val n = width * height
            require(pixels.size == n * 4) { "rgba expects ${n * 4} bytes (${width}x$height×4), got ${pixels.size}" }
            val rgb = ByteArray(n * 3)
            val a = ByteArray(n)
            var s = 0
            var d = 0
            for (i in 0 until n) {
                rgb[d++] = pixels[s]
                rgb[d++] = pixels[s + 1]
                rgb[d++] = pixels[s + 2]
                a[i] = pixels[s + 3]
                s += 4
            }
            val opaque = a.all { it == 0xFF.toByte() }
            return PdfImage(width, height, "DeviceRGB", 8, null, rgb, if (opaque) null else a)
        }

        /** 8-bit RGB, 3 bytes per pixel (R,G,B). No transparency. */
        fun rgb(pixels: ByteArray, width: Int, height: Int): PdfImage {
            val expected = width * height * 3
            require(pixels.size == expected) { "rgb expects $expected bytes (${width}x$height×3), got ${pixels.size}" }
            return PdfImage(width, height, "DeviceRGB", 8, null, pixels, null)
        }

        /** 8-bit grayscale, 1 byte per pixel. No transparency. */
        fun gray(pixels: ByteArray, width: Int, height: Int): PdfImage {
            val expected = width * height
            require(pixels.size == expected) { "gray expects $expected bytes (${width}x$height), got ${pixels.size}" }
            return PdfImage(width, height, "DeviceGray", 8, null, pixels, null)
        }

        /**
         * Embed encoded JPEG bytes directly via `/DCTDecode` (no re-encode).
         * [width]/[height] must match the JPEG's own dimensions. Set [grayscale]
         * for a single-component (DeviceGray) JPEG.
         */
        fun jpeg(jpegBytes: ByteArray, width: Int, height: Int, grayscale: Boolean = false): PdfImage =
            PdfImage(
                width, height,
                if (grayscale) "DeviceGray" else "DeviceRGB",
                8, "DCTDecode", jpegBytes, null,
            )
    }
}
