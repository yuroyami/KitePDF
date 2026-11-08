package io.github.yuroyami.kitepdf.writer

/**
 * An image ready to embed in a [PdfBuilder] page via
 * [ContentStreamBuilder.drawImage].
 *
 * Two kinds:
 *
 *  - **Raw samples** ([rgba] / [rgb] / [gray]): uncompressed 8-bit pixel
 *    bytes. Embedded as a `DeviceRGB`/`DeviceGray` image XObject with
 *    `/FlateDecode`. [rgba] splits the alpha channel into a `/SMask` so
 *    transparency is preserved (e.g. a logo with a cut-out background).
 *  - **JPEG passthrough** ([jpeg]): the encoded JPEG bytes are stored
 *    verbatim with `/DCTDecode`; every conformant PDF reader decodes JPEG
 *    natively, so nothing is re-encoded. JPEG carries no alpha.
 *
 * Instances are compared by identity: pass the same `PdfImage` to multiple
 * pages and it's embedded once and shared across them.
 *
 * Array ownership: [rgb], [gray], [jpeg] and [jpx] keep a reference to the
 * caller's array without copying (a copy would double peak memory for large
 * images), and the bytes are only read when [PdfBuilder.build] serializes
 * the document. Do not modify or reuse the array until `build()` returns;
 * a caller that decodes frames into one scratch buffer would otherwise
 * embed the last frame on every page. [rgba] converts into fresh arrays as
 * a side effect of the SMask split, so it has no such constraint.
 *
 * Pixels are laid out top row first, left to right, the natural raster order.
 * The PDF image-space flip is handled by [ContentStreamBuilder.drawImage].
 */
public class PdfImage private constructor(
    internal val width: Int,
    internal val height: Int,
    /** The device colour space name, or null to leave `/ColorSpace` out (a JPEG 2000 image's own applies). */
    internal val colorSpace: String?,
    internal val bitsPerComponent: Int,
    /** `null` → raw samples (FlateDecode); otherwise the PDF filter name, e.g. `DCTDecode`. */
    internal val filter: String?,
    /** Colour samples in component order (no alpha). */
    internal val samples: ByteArray,
    /** Optional 8-bit alpha plane (`width*height` bytes) → emitted as a `/SMask`. */
    internal val alpha: ByteArray?,
    /** `/Decode`, or null for the default. */
    internal val decode: List<Int>? = null,
) {
    init {
        require(width > 0 && height > 0) { "image must be non-empty (was ${width}x$height)" }
    }

    public companion object {
        /**
         * 8-bit RGBA, 4 bytes per pixel (R,G,B,A). The alpha channel becomes a
         * `/SMask`; if every pixel is fully opaque the mask is omitted.
         */
        public fun rgba(pixels: ByteArray, width: Int, height: Int): PdfImage {
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
        public fun rgb(pixels: ByteArray, width: Int, height: Int): PdfImage {
            val expected = width * height * 3
            require(pixels.size == expected) { "rgb expects $expected bytes (${width}x$height×3), got ${pixels.size}" }
            return PdfImage(width, height, "DeviceRGB", 8, null, pixels, null)
        }

        /** 8-bit grayscale, 1 byte per pixel. No transparency. */
        public fun gray(pixels: ByteArray, width: Int, height: Int): PdfImage {
            val expected = width * height
            require(pixels.size == expected) { "gray expects $expected bytes (${width}x$height), got ${pixels.size}" }
            return PdfImage(width, height, "DeviceGray", 8, null, pixels, null)
        }

        /**
         * Embed encoded JPEG bytes directly via `/DCTDecode` (no re-encode). The colour space comes
         * from the frame header: one component is `DeviceGray`, three `DeviceRGB` and four
         * `DeviceCMYK` (ISO 32000-1, 8.9.5, Table 89). A four-component JPEG with an Adobe APP14
         * marker stores its samples inverted, as Adobe applications write them, so it gets
         * `/Decode [1 0 1 0 1 0 1 0]` (#745).
         *
         * @throws IllegalArgumentException when [jpegBytes] has no frame header, or the frame has
         *   another component count.
         */
        public fun jpeg(jpegBytes: ByteArray): PdfImage {
            val frame = requireNotNull(JpegHeader.read(jpegBytes)) { "not a JPEG with a baseline, extended or progressive frame header" }
            val space = when (frame.components) {
                1 -> "DeviceGray"
                3 -> "DeviceRGB"
                4 -> "DeviceCMYK"
                else -> throw IllegalArgumentException("a JPEG with ${frame.components} components has no PDF device colour space")
            }
            val decode = if (frame.components == 4 && frame.adobe) List(4) { listOf(1, 0) }.flatten() else null
            return PdfImage(frame.width, frame.height, space, 8, "DCTDecode", jpegBytes, null, decode)
        }

        /**
         * As the other [jpeg], and checks that the JPEG's own frame is [width] by [height], and that it
         * has one component when [grayscale] is set.
         *
         * @throws IllegalArgumentException when the frame differs.
         */
        public fun jpeg(jpegBytes: ByteArray, width: Int, height: Int, grayscale: Boolean = false): PdfImage {
            val image = jpeg(jpegBytes)
            require(image.width == width && image.height == height) {
                "the JPEG is ${image.width} x ${image.height}, not $width x $height"
            }
            require(!grayscale || image.colorSpace == "DeviceGray") { "the JPEG has colour components, not one grey one" }
            return image
        }

        /**
         * Embed a JPEG 2000 (`.jp2`) file or codestream directly via `/JPXDecode` (no re-encode). The
         * dictionary names no `/ColorSpace`, so the colour space in the JPEG 2000 data applies
         * (ISO 32000-1, 8.9.5, Table 89; #745).
         */
        public fun jpx(jp2Bytes: ByteArray, width: Int, height: Int): PdfImage =
            PdfImage(width, height, null, 8, "JPXDecode", jp2Bytes, null)

        /**
         * As the other [jpx], with [colorSpace] (a device colour space name) in the dictionary, which
         * overrides the colour space in the JPEG 2000 data.
         */
        public fun jpx(jp2Bytes: ByteArray, width: Int, height: Int, colorSpace: String): PdfImage =
            PdfImage(width, height, colorSpace, 8, "JPXDecode", jp2Bytes, null)
    }
}
