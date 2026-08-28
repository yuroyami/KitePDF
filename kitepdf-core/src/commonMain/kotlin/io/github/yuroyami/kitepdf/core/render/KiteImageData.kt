package io.github.yuroyami.kitepdf.core.render

import io.github.yuroyami.kiteimage.ImageFormat
import io.github.yuroyami.kiteimage.KiteImage
import io.github.yuroyami.kiteimage.codec.Jbig2Decoder
import io.github.yuroyami.kiteimage.codec.JpxDecoder
import io.github.yuroyami.kitepdf.core.kiteWarn
import io.github.yuroyami.kitepdf.core.filters.FilterChain
import io.github.yuroyami.kitepdf.core.filters.TerminalDecodeResult
import io.github.yuroyami.kitepdf.core.parser.IndirectResolver
import io.github.yuroyami.kitepdf.core.parser.PdfArray
import io.github.yuroyami.kitepdf.core.parser.PdfBoolean
import io.github.yuroyami.kitepdf.core.parser.PdfDictionary
import io.github.yuroyami.kitepdf.core.parser.PdfInt
import io.github.yuroyami.kitepdf.core.parser.PdfName
import io.github.yuroyami.kitepdf.core.parser.PdfNull
import io.github.yuroyami.kitepdf.core.parser.PdfObject
import io.github.yuroyami.kitepdf.core.parser.PdfReal
import io.github.yuroyami.kitepdf.core.parser.PdfStream

/**
 * One image a document handler produced, ready for a [KiteCanvas]: the samples
 * plus everything needed to read them (colour space, bit depth, `/Decode`,
 * masks). PDF fills it from a `/XObject` `/Image` resource entry (ISO 32000-1
 * §8.9.5); EPUB fills it from a decoded PNG, JPEG or GIF.
 *
 * The decoded byte buffer's interpretation depends on the filter chain:
 *
 *   - `DCTDecode` → JPEG file in [encodedBytes]; decoded by the host platform's
 *     image loader (see `ImageDecoder` in `:kitepdf-compose-viewer`).
 *   - `FlateDecode` / `LZWDecode` / `CCITTFaxDecode` / ASCII / RunLength → pixel
 *     samples already decoded into [pixelBytes]; [toRgbaBytes] assembles RGBA
 *     using [resolvedColorSpace], [bitsPerComponent], and [decode].
 *   - `JBIG2Decode` → decoded in pure Kotlin ([Jbig2Decoder], the generic-region
 *     arithmetic path) into a 1-bpc DeviceGray RAW image; unsupported JBIG2
 *     flavours fall back to [Kind.JBIG2] with the payload in [encodedBytes].
 *   - `JPXDecode` (JPEG 2000) → decoded in pure Kotlin ([JpxDecoder], part 1
 *     baseline) into an 8-bpc RAW image; unsupported flavours fall back to
 *     [Kind.JPEG2000] with the payload in [encodedBytes].
 *
 * Callers should switch on [kind] to pick the right rendering path. Stencil masks
 * (`/ImageMask true`) carry [isImageMask] and are tinted by [maskFill].
 *
 * Transparency reaches the raster path in three forms: `/SMask` and a stencil
 * `/Mask` both arrive as [softMaskAlpha], and a colour-key `/Mask` arrives as
 * [colorKeyMask]. `/SMask` wins when an image carries both.
 */
public class KiteImageData internal constructor(
    public val width: Int,
    public val height: Int,
    public val bitsPerComponent: Int,
    public val colorSpace: String,
    public val kind: Kind,
    /** Encoded bytes, for kinds that defer decoding to a platform image loader. */
    public val encodedBytes: ByteArray,
    /** Pixel bytes, populated for [Kind.RAW] (already run through the filter chain). */
    public val pixelBytes: ByteArray? = null,
    /**
     * Soft-mask alpha (ISO 32000-1 §11.6.5.2), normalised to 8-bit grayscale:
     * one byte per pixel, 0 = transparent, 255 = opaque, row-major over
     * [softMaskWidth]×[softMaskHeight]. Null when the image carries neither a
     * `/SMask` nor a stencil `/Mask`.
     *
     * A stencil `/Mask` (§8.9.6) is flattened into this same plane, so both
     * masking forms composite through one path. The plane may be larger or
     * smaller than the image; the raster path resamples it.
     */
    public val softMaskAlpha: ByteArray? = null,
    public val softMaskWidth: Int = 0,
    public val softMaskHeight: Int = 0,
    /**
     * The image's colour space resolved against the document (Indexed palettes,
     * ICCBased component counts, etc.). Null for stencil masks and when it could
     * not be resolved (then [toRgbaBytes] infers a device space from the data).
     */
    public val resolvedColorSpace: KiteColorSpace? = null,
    /** `/Decode` array (per-component min/max remap), or null for the identity map. */
    public val decode: DoubleArray? = null,
    /** True for `/ImageMask` stencils: 1-bpc, painted with [maskFill]. */
    public val isImageMask: Boolean = false,
    /** Fill colour to tint an [isImageMask] stencil (the graphics-state fill colour). */
    public val maskFill: RgbColor? = null,
    /**
     * Colour-key `/Mask` ranges (ISO 32000-1 §8.9.6): 2 × n integers, a min
     * and a max per colour component, in the image's SOURCE sample values
     * (before `/Decode` and colour conversion). A pixel whose every component
     * falls inside its range is fully transparent. Null unless the image carries
     * an array-valued `/Mask` and no `/SMask`.
     */
    public val colorKeyMask: IntArray? = null,
) {

    public enum class Kind {
        /** Pixel data already flat in [pixelBytes] (Flate/LZW/CCITT/ASCII/RLE). */
        RAW,
        /** JPEG-encoded; [encodedBytes] is a complete JFIF/EXIF file. */
        JPEG,
        /** Unused: CCITT decodes through the filter chain into [RAW]. Kept for API stability. */
        CCITT,
        /** JBIG2-encoded payload the pure-Kotlin decoder could not handle (MMR/Huffman/halftone). */
        JBIG2,
        /** JPEG 2000-encoded; not decoded yet. */
        JPEG2000,
        /** Filter chain not recognised; backends should render a placeholder. */
        UNKNOWN,
    }

    public companion object {

        /**
         * Pull a stream from a /XObject /Image resource entry into a [KiteImageData].
         * [refs] (when provided) resolves indirect `/ColorSpace`, `/SMask` and
         * `/Mask` references; [fillColor] tints `/ImageMask` stencils (pass the
         * current graphics-state fill colour).
         */
        public fun from(
            stream: PdfStream,
            refs: IndirectResolver? = null,
            fillColor: RgbColor? = null,
        ): KiteImageData {
            val dict = stream.dict
            val width = positiveDimension(dict, "Width") ?: 0
            val height = positiveDimension(dict, "Height") ?: 0
            val isMask = (dict["ImageMask"] as? PdfBoolean)?.value == true ||
                (dict["IM"] as? PdfBoolean)?.value == true
            val bpc = if (isMask) {
                1
            } else {
                when (val declared = dict.getInt("BitsPerComponent")) {
                    null -> 8
                    1L, 2L, 4L, 8L, 16L -> declared.toInt()
                    else -> 0 // preserve the distinction between absent and invalid
                }
            }
            val csObj = dict["ColorSpace"] ?: dict["CS"]
            val cs = colorSpaceName(csObj)
            val resolvedCs = if (isMask) null else resolveColorSpace(csObj, refs)
            val decodeArr = readDecode(dict["Decode"] ?: dict["D"])

            // `/SMask` wins over `/Mask` when an image carries both, so exactly
            // one of the two masking forms is ever loaded.
            val hasSMask = hasSoftMask(dict)
            val (alpha, smW, smH) =
                if (hasSMask) loadSoftMask(dict, refs) else loadStencilMask(dict, refs)
            val colorKey = if (hasSMask) null else loadColorKeyMask(dict, refs)

            val filters = extractFilterNames(dict["Filter"] ?: dict["F"])
            val kind = pickKind(filters)
            val image = when (kind) {
                Kind.RAW -> KiteImageData(
                    width, height, bpc, cs, kind,
                    encodedBytes = ByteArray(0),
                    // Decode failures (truncated/garbled streams) degrade to a
                    // placeholder rather than aborting the whole page.
                    pixelBytes = runCatching { FilterChain.decode(stream) }.onFailure { e ->
                        kiteWarn { "image: decode fell back to placeholder: ${e.message}" }
                    }.getOrNull(),
                    softMaskAlpha = alpha, softMaskWidth = smW, softMaskHeight = smH,
                    resolvedColorSpace = resolvedCs, decode = decodeArr,
                    isImageMask = isMask, maskFill = fillColor, colorKeyMask = colorKey,
                )
                // JPEG (`DCTDecode`): decode in pure Kotlin to a colour-managed
                // RAW image so it renders on every backend AND picks up the
                // `/SMask` alpha via [toRgbaBytes] (the old platform path ignored
                // it). Falls back to the encoded [Kind.JPEG] path when the native
                // decoder can't handle the stream (arithmetic / 12-bit / etc.).
                Kind.JPEG -> {
                    // Prefix filters, e.g. /Filter [/ASCII85Decode /DCTDecode], must
                    // be undone before the bytes are a JFIF file at all (D-5).
                    val terminal = terminalBytesOf(stream)
                    val bm = runCatching { KiteImage.decode(terminal.bytes) }.getOrNull()
                    if (bm != null) KiteImageData(
                        bm.width, bm.height, 8, "DeviceRGB", Kind.RAW,
                        encodedBytes = ByteArray(0), pixelBytes = bm.toRgbBytes(),
                        softMaskAlpha = alpha, softMaskWidth = smW, softMaskHeight = smH,
                        resolvedColorSpace = KiteColorSpace.DeviceRGB,
                        isImageMask = isMask, maskFill = fillColor, colorKeyMask = colorKey,
                    ) else KiteImageData(
                        width, height, bpc, cs, kind, terminal.bytes,
                        softMaskAlpha = alpha, softMaskWidth = smW, softMaskHeight = smH,
                        resolvedColorSpace = resolvedCs, decode = decodeArr,
                        isImageMask = isMask, maskFill = fillColor, colorKeyMask = colorKey,
                    )
                }
                // JBIG2 (`JBIG2Decode`): pure-Kotlin bilevel decode (§6 arithmetic path)
                // into a 1-bpc DeviceGray RAW image. Needs the shared `/JBIG2Globals`
                // stream from `/DecodeParms`. Falls back to the encoded kind on failure.
                Kind.JBIG2 -> {
                    // Same prefix-filter requirement as JPEG above, plus the
                    // globals lookup keyed off the JBIG2 filter's own
                    // /DecodeParms entry (see loadJbig2Globals) (D-5).
                    val terminal = terminalBytesOf(stream)
                    val globals = loadJbig2Globals(terminal.terminalParams, dict["DecodeParms"], refs)
                    val decoded = runCatching { Jbig2Decoder.decode(terminal.bytes, globals, width, height) }.getOrNull()
                    if (decoded != null) KiteImageData(
                        width, height, 1, "DeviceGray", Kind.RAW,
                        encodedBytes = ByteArray(0), pixelBytes = decoded,
                        softMaskAlpha = alpha, softMaskWidth = smW, softMaskHeight = smH,
                        resolvedColorSpace = if (isMask) null else KiteColorSpace.DeviceGray,
                        decode = decodeArr, isImageMask = isMask, maskFill = fillColor, colorKeyMask = colorKey,
                    ) else KiteImageData(
                        width, height, bpc, cs, kind, terminal.bytes,
                        softMaskAlpha = alpha, softMaskWidth = smW, softMaskHeight = smH,
                        resolvedColorSpace = resolvedCs, decode = decodeArr,
                        isImageMask = isMask, maskFill = fillColor, colorKeyMask = colorKey,
                    )
                }
                // JPX (`JPXDecode`): pure-Kotlin JPEG 2000 decode into an
                // 8-bpc RAW image. A cdef opacity channel becomes the soft-mask
                // alpha when /SMaskInData asks for it. Unsupported flavours fall
                // back to the encoded kind (platform code may still handle them).
                Kind.JPEG2000 -> {
                    // Same prefix-filter requirement as JPEG above (D-5).
                    val terminal = terminalBytesOf(stream)
                    val raw = runCatching { JpxDecoder.decode(terminal.bytes) }.getOrNull()
                    if (raw != null) {
                        val smaskRaw = dict["SMaskInData"]
                        val smaskInData = ((if (smaskRaw is io.github.yuroyami.kitepdf.core.parser.PdfReference) refs?.resolve(smaskRaw) else smaskRaw) as? PdfInt)
                            ?.value?.toInt() ?: 0
                        val useAlpha = smaskInData != 0 && raw.alpha != null
                        KiteImageData(
                            raw.width, raw.height, 8, raw.colorSpace, Kind.RAW,
                            encodedBytes = ByteArray(0), pixelBytes = raw.pixelBytes,
                            softMaskAlpha = if (useAlpha) raw.alpha else alpha,
                            softMaskWidth = if (useAlpha) raw.width else smW,
                            softMaskHeight = if (useAlpha) raw.height else smH,
                            resolvedColorSpace = if (isMask) null else {
                                if (raw.colorSpace == "DeviceRGB") KiteColorSpace.DeviceRGB else KiteColorSpace.DeviceGray
                            },
                            isImageMask = isMask, maskFill = fillColor, colorKeyMask = colorKey,
                        )
                    } else KiteImageData(
                        width, height, bpc, cs, kind, terminal.bytes,
                        softMaskAlpha = alpha, softMaskWidth = smW, softMaskHeight = smH,
                        resolvedColorSpace = resolvedCs, decode = decodeArr,
                        isImageMask = isMask, maskFill = fillColor, colorKeyMask = colorKey,
                    )
                }
                // For the remaining encoded kinds, hand the raw bytes through:
                // platform code (or a future native decoder) interprets them.
                else -> KiteImageData(
                    width, height, bpc, cs, kind, stream.rawBytes,
                    softMaskAlpha = alpha, softMaskWidth = smW, softMaskHeight = smH,
                    resolvedColorSpace = resolvedCs, decode = decodeArr,
                    isImageMask = isMask, maskFill = fillColor, colorKeyMask = colorKey,
                )
            }
            // A stencil is usually finer than the layer it masks, so the composite
            // is built on the stencil's grid. `/SMask` images keep the behaviour
            // they have always had.
            return if (hasSMask) image else image.alignedToStencilGrid()
        }

        /**
         * Build an image from a self-contained encoded file, as shipped by EPUB /
         * CBZ / SVG `<image>` (rather than pulled from a PDF `/XObject` stream).
         * The format and pixel dimensions are sniffed from the bytes.
         *
         * PNG, GIF, BMP, JPEG and JPEG 2000 are decoded in pure Kotlin by the
         * shared KiteImage engine into a [Kind.RAW] image that renders on
         * every backend. A JPEG the native decoder can't handle (arithmetic coding,
         * 12-bit) falls back to the host platform's loader ([Kind.JPEG] with the
         * file in [encodedBytes]). Unrecognised formats return null, so callers
         * degrade gracefully by skipping the image.
         */
        public fun fromEncodedImage(bytes: ByteArray): KiteImageData? {
            return when (ImageFormat.sniff(bytes)) {
                ImageFormat.PNG, ImageFormat.GIF, ImageFormat.BMP, ImageFormat.JP2 ->
                    runCatching { KiteImage.decode(bytes) }.getOrNull()?.toKiteImageData()
                ImageFormat.JPEG -> {
                    runCatching { KiteImage.decode(bytes) }.getOrNull()?.let { return it.toKiteImageData() }
                    // Streams KiteImage can't handle (arithmetic coding, 12-bit)
                    // defer to the host platform's loader.
                    val (w, h) = jpegSize(bytes) ?: return null
                    if (w <= 0 || h <= 0) return null
                    KiteImageData(
                        width = w, height = h, bitsPerComponent = 8,
                        colorSpace = "DeviceRGB", kind = Kind.JPEG, encodedBytes = bytes,
                    )
                }
                else -> null
            }
        }

        /**
         * Pixel size of a JPEG from its first SOF marker, or null if [b] is not a
         * JPEG. Walks the segment markers rather than assuming SOF sits right after
         * SOI (real files carry APPn/DQT segments first).
         */
        private fun jpegSize(b: ByteArray): Pair<Int, Int>? {
            if (b.size < 4 || (b[0].toInt() and 0xFF) != 0xFF || (b[1].toInt() and 0xFF) != 0xD8) return null
            var i = 2
            while (i + 1 < b.size) {
                if ((b[i].toInt() and 0xFF) != 0xFF) { i++; continue }
                var marker = b[i + 1].toInt() and 0xFF
                i += 2
                while (marker == 0xFF && i < b.size) { marker = b[i].toInt() and 0xFF; i++ } // fill bytes
                if (marker == 0xD8 || marker == 0xD9 || marker in 0xD0..0xD7) continue // no length payload
                if (i + 1 >= b.size) break
                val len = ((b[i].toInt() and 0xFF) shl 8) or (b[i + 1].toInt() and 0xFF)
                // SOF0..SOF15 carry the frame size, except DHT(C4)/JPG(C8)/DAC(CC).
                if (marker in 0xC0..0xCF && marker != 0xC4 && marker != 0xC8 && marker != 0xCC) {
                    if (i + 6 >= b.size) return null
                    val height = ((b[i + 3].toInt() and 0xFF) shl 8) or (b[i + 4].toInt() and 0xFF)
                    val width = ((b[i + 5].toInt() and 0xFF) shl 8) or (b[i + 6].toInt() and 0xFF)
                    return width to height
                }
                if (len < 2) break
                i += len
            }
            return null
        }

        private fun resolveColorSpace(obj: PdfObject?, refs: IndirectResolver?): KiteColorSpace? {
            if (obj == null) return null
            return if (refs != null) {
                runCatching { KiteColorSpace.resolve(obj, refs) }.getOrNull()
            } else {
                // Without a resolver we can only recognise the device families by
                // name (Indexed/ICCBased need the document to fetch their data).
                when (colorSpaceName(obj)) {
                    "DeviceRGB", "RGB", "CalRGB" -> KiteColorSpace.DeviceRGB
                    "DeviceGray", "G", "CalGray" -> KiteColorSpace.DeviceGray
                    "DeviceCMYK", "CMYK" -> KiteColorSpace.DeviceCMYK
                    else -> null
                }
            }
        }

        private fun readDecode(obj: PdfObject?): DoubleArray? {
            val arr = obj as? PdfArray ?: return null
            if (arr.isEmpty()) return null
            return DoubleArray(arr.size) { i ->
                when (val v = arr[i]) {
                    is PdfReal -> v.value
                    is PdfInt -> v.value.toDouble()
                    else -> 0.0
                }
            }
        }

        /** Convert an untrusted PDF integer without wrapping a Long into Int. */
        private fun positiveDimension(dict: PdfDictionary, key: String): Int? =
            dict.getInt(key)
                ?.takeIf { it in 1L..Int.MAX_VALUE.toLong() }
                ?.toInt()

        /**
         * Decode an image's `/SMask` (ISO 32000-1 §11.6.5.2), a DeviceGray image
         * whose samples ARE the base image's per-pixel alpha, into a normalised
         * 8-bit grayscale buffer (0 = transparent, 255 = opaque).
         *
         * Scope: RAW (Flate/LZW/CCITT/…) DeviceGray masks at 1 or 8 bpc. A
         * DCT/JPX-encoded mask (needs a platform decoder) is skipped, leaving the
         * image opaque.
         */
        private fun loadSoftMask(
            dict: PdfDictionary,
            refs: IndirectResolver?,
        ): Triple<ByteArray?, Int, Int> {
            val none = Triple<ByteArray?, Int, Int>(null, 0, 0)
            val raw = dict["SMask"] ?: return none
            val mask = when {
                raw is PdfStream -> raw
                refs != null -> runCatching { raw.resolve(refs) }.getOrNull() as? PdfStream ?: return none
                else -> return none
            }
            val mdict = mask.dict
            val mw = positiveDimension(mdict, "Width") ?: return none
            val mh = positiveDimension(mdict, "Height") ?: return none
            val sampleCount = mw.toLong() * mh
            if (sampleCount > MAX_MASK_SAMPLES) return none
            if (pickKind(extractFilterNames(mdict["Filter"])) != Kind.RAW) return none
            val bytes = runCatching { FilterChain.decode(mask) }.getOrNull() ?: return none
            val alpha = when (mdict.getInt("BitsPerComponent") ?: 8L) {
                8L -> if (bytes.size.toLong() >= sampleCount) bytes.copyOf(sampleCount.toInt()) else return none
                1L -> expand1BitToGray(bytes, mw, mh) ?: return none
                else -> return none
            }
            return Triple(alpha, mw, mh)
        }

        /**
         * True when the image dictionary carries a `/SMask` worth reading. A
         * present-but-empty entry (`null`, `/None`) counts as absent, so a
         * `/Mask` next to it is still honoured.
         */
        private fun hasSoftMask(dict: PdfDictionary): Boolean {
            val v = dict["SMask"] ?: return false
            return v !== PdfNull && (v as? PdfName)?.value != "None"
        }

        /**
         * Decode a stencil `/Mask` (ISO 32000-1 §8.9.6) into the same 8-bit
         * alpha plane [loadSoftMask] produces, so both masking forms composite
         * through one tested path.
         *
         * The mask is a 1-bit image XObject that says which of the base image's
         * pixels may be painted. With the default `/Decode [0 1]` a 0 sample
         * paints (alpha 255) and a 1 sample is masked out (alpha 0); `/Decode
         * [1 0]` on the mask stream swaps the two. The mask has its own
         * resolution, which the raster path resamples.
         *
         * Scope: masks the filter chain decodes (Flate, LZW, CCITT, …) and
         * JBIG2 masks, which is what scanner output carries. Anything else, a
         * mask that fails to decode, or an absurd size returns no mask, leaving
         * the image painted unmasked rather than blanking the page.
         */
        private fun loadStencilMask(
            dict: PdfDictionary,
            refs: IndirectResolver?,
        ): Triple<ByteArray?, Int, Int> {
            val none = Triple<ByteArray?, Int, Int>(null, 0, 0)
            val raw = dict["Mask"] ?: return none
            val mask = when {
                raw is PdfStream -> raw
                refs != null -> runCatching { raw.resolve(refs) }.getOrNull() as? PdfStream ?: return none
                else -> return none
            }
            val mdict = mask.dict
            val isStencil = (mdict["ImageMask"] as? PdfBoolean)?.value == true ||
                (mdict["IM"] as? PdfBoolean)?.value == true
            // §8.9.6 requires /ImageMask true; tolerate a missing flag only
            // when the stream is 1-bit anyway, and never guess at deeper data.
            if (!isStencil && (mdict.getInt("BitsPerComponent") ?: 8L) != 1L) return none
            val mw = positiveDimension(mdict, "Width") ?: return none
            val mh = positiveDimension(mdict, "Height") ?: return none
            // Untrusted dimensions: refuse to allocate a plane no real scan needs.
            val sampleCount = mw.toLong() * mh
            if (sampleCount > MAX_MASK_SAMPLES) return none
            val bits = decodeStencilBits(mask, mdict, mw, mh, refs) ?: return none
            val rowBytes = (mw + 7) / 8
            if (bits.size < rowBytes.toLong() * mh) return none
            val invert = readDecode(mdict["Decode"] ?: mdict["D"])
                ?.let { it.size >= 2 && it[0] == 1.0 } == true
            val alpha = ByteArray(sampleCount.toInt())
            var o = 0
            for (y in 0 until mh) {
                val rowStart = y * rowBytes
                for (x in 0 until mw) {
                    val bit = (bits[rowStart + (x ushr 3)].toInt() shr (7 - (x and 7))) and 1
                    val masked = if (invert) bit == 0 else bit == 1
                    alpha[o++] = if (masked) 0x00 else 0xFF.toByte()
                }
            }
            return Triple(alpha, mw, mh)
        }

        /** The stencil's 1-bit samples, packed MSB-first per byte-aligned row. */
        private fun decodeStencilBits(
            mask: PdfStream,
            mdict: PdfDictionary,
            mw: Int,
            mh: Int,
            refs: IndirectResolver?,
        ): ByteArray? = when (pickKind(extractFilterNames(mdict["Filter"] ?: mdict["F"]))) {
            Kind.RAW -> runCatching { FilterChain.decode(mask) }.getOrNull()
            // JBIG2 stencils are the common case in MRC scans. The decoder
            // already emits PDF's convention (0 = the marked, painted sample).
            // Same prefix-filter requirement as an image's own JBIG2Decode (D-5).
            Kind.JBIG2 -> {
                val terminal = terminalBytesOf(mask)
                val globals = loadJbig2Globals(terminal.terminalParams, mdict["DecodeParms"], refs)
                runCatching { Jbig2Decoder.decode(terminal.bytes, globals, mw, mh) }.getOrNull()
            }
            else -> null
        }

        /**
         * [FilterChain.decodeToTerminal], defensively: a malformed `/Filter` or
         * `/DecodeParms` shape falls back to the stream's own raw bytes and no
         * terminal params, the same as an unfiltered stream, rather than
         * aborting the image.
         */
        private fun terminalBytesOf(stream: PdfStream): TerminalDecodeResult =
            runCatching { FilterChain.decodeToTerminal(stream) }
                .getOrElse { TerminalDecodeResult(stream.rawBytes, null) }

        /**
         * Read a colour-key `/Mask` (ISO 32000-1 §8.9.6): an array of 2 × n
         * source-sample bounds. Anything malformed (odd length, non-integer
         * entries) is dropped, leaving the image opaque.
         */
        private fun loadColorKeyMask(dict: PdfDictionary, refs: IndirectResolver?): IntArray? {
            val raw = dict["Mask"] ?: return null
            val arr = when {
                raw is PdfArray -> raw
                refs != null -> runCatching { raw.resolve(refs) }.getOrNull() as? PdfArray ?: return null
                else -> return null
            }
            if (arr.isEmpty() || arr.size % 2 != 0) return null
            val out = IntArray(arr.size)
            for (i in arr.indices) out[i] = (arr[i] as? PdfInt)?.value?.toInt() ?: return null
            return out
        }

        /**
         * Resample a stencil-masked image up onto the stencil's own pixel grid
         * when the stencil is the finer of the two.
         *
         * Both are mapped to the same unit square, so either grid composites
         * faithfully, but they are not equally good: MRC scans (the layered
         * form scanners produce) put a 300 dpi stencil over a 75 dpi block of
         * ink, and resampling the stencil DOWN to the ink would throw away the
         * glyph shapes that carry the text. Resampling the ink UP loses
         * nothing, because a flat ink layer holds no detail of its own.
         *
         * Only 8-bpc [Kind.RAW] samples move (which covers JPX, JPEG and Flate
         * ink layers). Anything else keeps its grid and is composited by the
         * raster path's nearest-neighbour remap, which still paints the right
         * pixels, just more coarsely. [MAX_ALIGNED_SAMPLES] caps the work,
         * since both sizes come from an untrusted file.
         */
        private fun KiteImageData.alignedToStencilGrid(): KiteImageData {
            val src = pixelBytes ?: return this
            if (softMaskAlpha == null || kind != Kind.RAW || isImageMask) return this
            if (bitsPerComponent != 8 || width <= 0 || height <= 0) return this
            val mw = softMaskWidth
            val mh = softMaskHeight
            if (mw <= width || mh <= height) return this
            if (mw.toLong() * mh > MAX_ALIGNED_SAMPLES) return this
            val comps = resolvedColorSpace?.componentCount ?: (src.size / (width * height))
            if (comps !in 1..4 || src.size < width.toLong() * height * comps) return this
            val out = ByteArray(mw * mh * comps)
            // Precomputed source column per target column: one array read per
            // pixel instead of an integer divide.
            val colMap = IntArray(mw) { x -> x * width / mw }
            var o = 0
            for (y in 0 until mh) {
                val rowBase = (y * height / mh) * width
                for (x in 0 until mw) {
                    var s = (rowBase + colMap[x]) * comps
                    repeat(comps) { out[o++] = src[s++] }
                }
            }
            return KiteImageData(
                width = mw, height = mh, bitsPerComponent = 8, colorSpace = colorSpace,
                kind = Kind.RAW, encodedBytes = ByteArray(0), pixelBytes = out,
                softMaskAlpha = softMaskAlpha, softMaskWidth = mw, softMaskHeight = mh,
                resolvedColorSpace = resolvedColorSpace, decode = decode,
                isImageMask = false, maskFill = maskFill, colorKeyMask = colorKeyMask,
            )
        }

        /** Ceiling on a stencil's sample count (a 600 dpi A4 page is ~35 M). */
        private const val MAX_MASK_SAMPLES = 40_000_000L

        /** Ceiling on the composite grid an image may be resampled up to. */
        private const val MAX_ALIGNED_SAMPLES = 16_000_000L

        private fun expand1BitToGray(raw: ByteArray, w: Int, h: Int): ByteArray? {
            val rowBytes = ((w.toLong() + 7L) / 8L).toInt() // 1-bit rows are byte-aligned
            if (raw.size.toLong() < rowBytes.toLong() * h) return null
            val sampleCount = w.toLong() * h
            if (sampleCount > MAX_MASK_SAMPLES) return null
            val out = ByteArray(sampleCount.toInt())
            var o = 0
            for (y in 0 until h) {
                val rowStart = y * rowBytes
                for (x in 0 until w) {
                    val bit = (raw[rowStart + (x ushr 3)].toInt() shr (7 - (x and 7))) and 1
                    out[o++] = if (bit == 1) 0xFF.toByte() else 0x00
                }
            }
            return out
        }

        /**
         * The shared JBIG2 globals stream, decoded. [parms] is the JBIG2
         * filter's own `/DecodeParms` entry, already picked out by
         * [terminalBytesOf] / [FilterChain.decodeToTerminal] at the position
         * aligned with `/JBIG2Decode` in `/Filter` (ISO 32000-1 §7.4, Table
         * 5), so a chain such as `[/FlateDecode /JBIG2Decode]` with
         * `/DecodeParms [null << /JBIG2Globals 7 0 R >>]` reads the second
         * entry rather than guessing at the whole array's shape here too.
         *
         * A bare (non-array) `/DecodeParms` is positionally valid only at
         * index 0 (`extractDecodeParms`), so [parms] is null whenever a
         * writer puts a bare dictionary on a chain where JBIG2Decode is not
         * the first filter, out of spec but not rare. When that happens,
         * [rawDecodeParms], the stream's whole unresolved `/DecodeParms`
         * value, is searched by content for a `/JBIG2Globals` key instead:
         * the same recovery the pre-D-5 code did unconditionally, now used
         * only once the positional read comes up empty, so a correctly
         * positioned array is never second-guessed.
         */
        private fun loadJbig2Globals(
            parms: PdfDictionary?,
            rawDecodeParms: PdfObject?,
            refs: IndirectResolver?,
        ): ByteArray? {
            fun res(o: PdfObject?) = if (refs != null && o != null) runCatching { o.resolve(refs) }.getOrNull() else o
            val found = parms ?: when (val d = res(rawDecodeParms)) {
                is PdfDictionary -> d
                is PdfArray -> d.mapNotNull { res(it) as? PdfDictionary }.firstOrNull { it["JBIG2Globals"] != null }
                else -> null
            } ?: return null
            val gs = res(found["JBIG2Globals"]) as? PdfStream ?: return null
            return runCatching { FilterChain.decode(gs) }.getOrNull() ?: gs.rawBytes
        }

        private fun colorSpaceName(obj: PdfObject?): String = when (obj) {
            is PdfName -> obj.value
            is PdfArray -> (obj.firstOrNull() as? PdfName)?.value ?: "DeviceRGB"
            else -> "DeviceRGB"
        }

        private fun extractFilterNames(value: PdfObject?): List<String> = when (value) {
            null -> emptyList()
            is PdfName -> listOf(value.value)
            is PdfArray -> value.mapNotNull { (it as? PdfName)?.value }
            else -> emptyList()
        }

        private fun pickKind(filters: List<String>): Kind {
            // The outermost filter (last in the chain) decides what the bytes look like.
            for (filter in filters.reversed()) {
                when (filter) {
                    "DCTDecode", "DCT" -> return Kind.JPEG
                    "JPXDecode" -> return Kind.JPEG2000
                    "JBIG2Decode" -> return Kind.JBIG2
                    // CCITTFaxDecode is decoded by the filter chain → raw pixels.
                    else -> { /* raw-wrapper or unknown: keep scanning */ }
                }
            }
            return if (filters.isEmpty()) Kind.RAW
            else if (filters.all { it in OK_RAW_WRAPPERS }) Kind.RAW
            else Kind.UNKNOWN
        }

        private val OK_RAW_WRAPPERS = setOf(
            "FlateDecode", "Fl", "ASCIIHexDecode", "AHx", "ASCII85Decode", "A85",
            "RunLengthDecode", "RL", "LZWDecode", "LZW", "CCITTFaxDecode", "CCF",
        )
    }
}
