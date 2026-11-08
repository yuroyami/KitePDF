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
    internal val decode: List<Double>? = null,
    /** A colour space that is more than a device name: an Indexed or an ICC-based one, or null. */
    internal val space: PdfImageColorSpace? = null,
    /** `/Interpolate true`, a hint to smooth the image when it is drawn larger (#920). */
    internal val interpolate: Boolean = false,
) {
    init {
        require(width > 0 && height > 0) { "image must be non-empty (was ${width}x$height)" }
    }

    public companion object {
        /**
         * Raw samples in any colour space a PDF image takes (ISO 32000-1, 8.9.5, Table 89; #920):
         * [samples] holds [width] by [height] pixels, top row first, each row padded to a whole
         * byte, so its size is `ceil(width * components * bitsPerComponent / 8) * height`.
         *
         * - [colorSpace]: [PdfImageColorSpace.DeviceGray], `DeviceRGB`, `DeviceCMYK`, an
         *   [PdfImageColorSpace.Indexed] palette over one of them, or an
         *   [PdfImageColorSpace.IccBased] profile.
         * - [bitsPerComponent]: 1, 2, 4, 8 or 16, and at most 8 for an Indexed space. A 1-bit page is
         *   an eighth of its 8-bit size.
         * - [decode]: two numbers a component that map the lowest and highest sample (8.9.5.2), for
         *   example `listOf(1.0, 0.0)` for inverted grey; null keeps the default.
         * - [interpolate]: asks a reader to smooth the image when it draws it larger (Table 89).
         *   It is only a hint, which a reader may ignore; KitePDF follows it. Leave it false for pixel
         *   art and barcodes.
         * - [alpha]: an 8-bit alpha plane of `width * height` bytes, written as a `/SMask`.
         *
         * The bytes are kept, not copied, as for [rgb].
         */
        public fun raw(
            samples: ByteArray,
            width: Int,
            height: Int,
            colorSpace: PdfImageColorSpace,
            bitsPerComponent: Int = 8,
            decode: List<Double>? = null,
            interpolate: Boolean = false,
            alpha: ByteArray? = null,
        ): PdfImage {
            require(bitsPerComponent in setOf(1, 2, 4, 8, 16)) { "bits per component are 1, 2, 4, 8 or 16, not $bitsPerComponent" }
            require(colorSpace !is PdfImageColorSpace.Indexed || bitsPerComponent <= 8) { "an Indexed image has at most 8 bits a sample" }
            if (colorSpace is PdfImageColorSpace.Indexed) {
                require(colorSpace.entries <= 1 shl bitsPerComponent) {
                    "a palette of ${colorSpace.entries} colours needs more than $bitsPerComponent bits a sample"
                }
            }
            val components = colorSpace.components
            val expected = (width.toLong() * components * bitsPerComponent + 7) / 8 * height
            require(samples.size.toLong() == expected) {
                "${width}x$height samples of $components components at $bitsPerComponent bits take $expected bytes, not ${samples.size}"
            }
            require(decode == null || decode.size == 2 * components) { "/Decode takes ${2 * components} numbers, not ${decode?.size}" }
            require(alpha == null || alpha.size == width * height) { "the alpha plane takes ${width * height} bytes, not ${alpha?.size}" }
            return PdfImage(
                width, height, (colorSpace as? PdfImageColorSpace.Device)?.name, bitsPerComponent, null, samples, alpha, decode,
                space = colorSpace.takeIf { it !is PdfImageColorSpace.Device }, interpolate = interpolate,
            )
        }

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
            val decode = if (frame.components == 4 && frame.adobe) List(4) { listOf(1.0, 0.0) }.flatten() else null
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

/**
 * The colour space of a [PdfImage.raw] image (ISO 32000-1, 8.6; #920).
 */
public sealed class PdfImageColorSpace {
    /** The number of components a sample has. */
    public abstract val components: Int

    /** A device colour space. */
    public class Device internal constructor(internal val name: String, override val components: Int) : PdfImageColorSpace()

    /**
     * A palette (8.6.6.3): each sample is an index into [palette], which holds the colours one
     * after another in [base], one byte a component. It has 1 to 256 colours.
     */
    public class Indexed(public val base: Device, public val palette: ByteArray) : PdfImageColorSpace() {
        override val components: Int get() = 1

        /** The number of colours in the palette. */
        public val entries: Int get() = palette.size / base.components

        init {
            require(palette.isNotEmpty() && palette.size % base.components == 0) {
                "a palette over ${base.name} is whole colours of ${base.components} bytes, not ${palette.size} bytes"
            }
            require(entries <= 256) { "a palette has at most 256 colours, not $entries" }
        }
    }

    /**
     * An ICC profile (8.6.5.5): [profile] is the profile's bytes, [components] its number of
     * components (1, 3 or 4), and [alternate] the device space a reader without colour management
     * uses, by default the one with as many components.
     */
    public class IccBased(
        public val profile: ByteArray,
        override val components: Int,
        public val alternate: Device = when (components) {
            1 -> DeviceGray
            3 -> DeviceRGB
            4 -> DeviceCMYK
            else -> throw IllegalArgumentException("an ICC profile for an image has 1, 3 or 4 components, not $components")
        },
    ) : PdfImageColorSpace() {
        init {
            require(components in setOf(1, 3, 4)) { "an ICC profile for an image has 1, 3 or 4 components, not $components" }
            require(alternate.components == components) { "the alternate ${alternate.name} does not have $components components" }
        }
    }

    public companion object {
        public val DeviceGray: Device = Device("DeviceGray", 1)
        public val DeviceRGB: Device = Device("DeviceRGB", 3)
        public val DeviceCMYK: Device = Device("DeviceCMYK", 4)
    }
}
