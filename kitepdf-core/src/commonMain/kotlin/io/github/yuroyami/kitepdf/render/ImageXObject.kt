package io.github.yuroyami.kitepdf.render

import io.github.yuroyami.kitepdf.filters.FilterChain
import io.github.yuroyami.kitepdf.parser.IndirectResolver
import io.github.yuroyami.kitepdf.parser.PdfArray
import io.github.yuroyami.kitepdf.parser.PdfBoolean
import io.github.yuroyami.kitepdf.parser.PdfDictionary
import io.github.yuroyami.kitepdf.parser.PdfInt
import io.github.yuroyami.kitepdf.parser.PdfName
import io.github.yuroyami.kitepdf.parser.PdfObject
import io.github.yuroyami.kitepdf.parser.PdfReal
import io.github.yuroyami.kitepdf.parser.PdfStream

/**
 * An XObject Image extracted from a `/XObject` resource entry (ISO 32000-1 §8.9.5).
 *
 * The decoded byte buffer's interpretation depends on the filter chain:
 *
 *   - `DCTDecode` → JPEG file in [encodedBytes]; decoded by the host platform's
 *     image loader (see `ImageDecoder` in `:kitepdf-compose`).
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
 */
public class ImageXObject internal constructor(
    public val width: Int,
    public val height: Int,
    public val bitsPerComponent: Int,
    public val colorSpace: String,
    public val kind: Kind,
    /** Encoded bytes — for kinds that defer decoding to a platform image loader. */
    public val encodedBytes: ByteArray,
    /** Pixel bytes — populated for [Kind.RAW] (already run through the filter chain). */
    public val pixelBytes: ByteArray? = null,
    /**
     * Soft-mask alpha (ISO 32000-1 §11.6.5.2), normalised to 8-bit grayscale —
     * one byte per pixel, 0 = transparent, 255 = opaque, row-major over
     * [softMaskWidth]×[softMaskHeight]. Null when the image carries no `/SMask`.
     */
    public val softMaskAlpha: ByteArray? = null,
    public val softMaskWidth: Int = 0,
    public val softMaskHeight: Int = 0,
    /**
     * The image's colour space resolved against the document (Indexed palettes,
     * ICCBased component counts, etc.). Null for stencil masks and when it could
     * not be resolved (then [toRgbaBytes] infers a device space from the data).
     */
    public val resolvedColorSpace: ColorSpace? = null,
    /** `/Decode` array (per-component min/max remap), or null for the identity map. */
    public val decode: DoubleArray? = null,
    /** True for `/ImageMask` stencils — 1-bpc, painted with [maskFill]. */
    public val isImageMask: Boolean = false,
    /** Fill colour to tint an [isImageMask] stencil (the graphics-state fill colour). */
    public val maskFill: RgbColor? = null,
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
         * Pull a stream from a /XObject /Image resource entry into an [ImageXObject].
         * [refs] (when provided) resolves indirect `/ColorSpace` and `/SMask`
         * references; [fillColor] tints `/ImageMask` stencils (pass the current
         * graphics-state fill colour).
         */
        public fun from(
            stream: PdfStream,
            refs: IndirectResolver? = null,
            fillColor: RgbColor? = null,
        ): ImageXObject {
            val dict = stream.dict
            val width = dict.getInt("Width")?.toInt() ?: 0
            val height = dict.getInt("Height")?.toInt() ?: 0
            val isMask = (dict["ImageMask"] as? PdfBoolean)?.value == true ||
                (dict["IM"] as? PdfBoolean)?.value == true
            val bpc = if (isMask) 1 else dict.getInt("BitsPerComponent")?.toInt() ?: 8
            val csObj = dict["ColorSpace"] ?: dict["CS"]
            val cs = colorSpaceName(csObj)
            val resolvedCs = if (isMask) null else resolveColorSpace(csObj, refs)
            val decodeArr = readDecode(dict["Decode"] ?: dict["D"])

            val (alpha, smW, smH) = loadSoftMask(dict, refs)

            val filters = extractFilterNames(dict["Filter"] ?: dict["F"])
            val kind = pickKind(filters)
            return when (kind) {
                Kind.RAW -> ImageXObject(
                    width, height, bpc, cs, kind,
                    encodedBytes = ByteArray(0),
                    // Decode failures (truncated/garbled streams) degrade to a
                    // placeholder rather than aborting the whole page.
                    pixelBytes = runCatching { FilterChain.decode(stream) }.getOrNull(),
                    softMaskAlpha = alpha, softMaskWidth = smW, softMaskHeight = smH,
                    resolvedColorSpace = resolvedCs, decode = decodeArr,
                    isImageMask = isMask, maskFill = fillColor,
                )
                // JPEG (`DCTDecode`): decode in pure Kotlin to a colour-managed
                // RAW image so it renders on every backend AND picks up the
                // `/SMask` alpha via [toRgbaBytes] (the old platform path ignored
                // it). Falls back to the encoded [Kind.JPEG] path when the native
                // decoder can't handle the stream (arithmetic / 12-bit / etc.).
                Kind.JPEG -> {
                    val raw = runCatching { JpegDecoder.decode(stream.rawBytes) }.getOrNull()
                    if (raw != null) ImageXObject(
                        raw.width, raw.height, 8, raw.colorSpace, Kind.RAW,
                        encodedBytes = ByteArray(0), pixelBytes = raw.pixelBytes,
                        softMaskAlpha = alpha, softMaskWidth = smW, softMaskHeight = smH,
                        resolvedColorSpace = raw.resolvedColorSpace,
                        isImageMask = isMask, maskFill = fillColor,
                    ) else ImageXObject(
                        width, height, bpc, cs, kind, stream.rawBytes,
                        softMaskAlpha = alpha, softMaskWidth = smW, softMaskHeight = smH,
                        resolvedColorSpace = resolvedCs, decode = decodeArr,
                        isImageMask = isMask, maskFill = fillColor,
                    )
                }
                // JBIG2 (`JBIG2Decode`): pure-Kotlin bilevel decode (§6 arithmetic path)
                // into a 1-bpc DeviceGray RAW image. Needs the shared `/JBIG2Globals`
                // stream from `/DecodeParms`. Falls back to the encoded kind on failure.
                Kind.JBIG2 -> {
                    val globals = loadJbig2Globals(dict, refs)
                    val decoded = runCatching { Jbig2Decoder.decode(stream.rawBytes, globals, width, height) }.getOrNull()
                    if (decoded != null) ImageXObject(
                        width, height, 1, "DeviceGray", Kind.RAW,
                        encodedBytes = ByteArray(0), pixelBytes = decoded,
                        softMaskAlpha = alpha, softMaskWidth = smW, softMaskHeight = smH,
                        resolvedColorSpace = if (isMask) null else ColorSpace.DeviceGray,
                        decode = decodeArr, isImageMask = isMask, maskFill = fillColor,
                    ) else ImageXObject(
                        width, height, bpc, cs, kind, stream.rawBytes,
                        softMaskAlpha = alpha, softMaskWidth = smW, softMaskHeight = smH,
                        resolvedColorSpace = resolvedCs, decode = decodeArr,
                        isImageMask = isMask, maskFill = fillColor,
                    )
                }
                // JPX (`JPXDecode`): pure-Kotlin JPEG 2000 decode (T-44) into an
                // 8-bpc RAW image. A cdef opacity channel becomes the soft-mask
                // alpha when /SMaskInData asks for it. Unsupported flavours fall
                // back to the encoded kind (platform code may still handle them).
                Kind.JPEG2000 -> {
                    val raw = JpxDecoder.decode(stream.rawBytes)
                    if (raw != null) {
                        val smaskRaw = dict["SMaskInData"]
                        val smaskInData = ((if (smaskRaw is io.github.yuroyami.kitepdf.parser.PdfReference) refs?.resolve(smaskRaw) else smaskRaw) as? PdfInt)
                            ?.value?.toInt() ?: 0
                        val useAlpha = smaskInData != 0 && raw.alpha != null
                        ImageXObject(
                            raw.width, raw.height, 8, raw.colorSpace, Kind.RAW,
                            encodedBytes = ByteArray(0), pixelBytes = raw.pixelBytes,
                            softMaskAlpha = if (useAlpha) raw.alpha else alpha,
                            softMaskWidth = if (useAlpha) raw.width else smW,
                            softMaskHeight = if (useAlpha) raw.height else smH,
                            resolvedColorSpace = if (isMask) null else {
                                if (raw.colorSpace == "DeviceRGB") ColorSpace.DeviceRGB else ColorSpace.DeviceGray
                            },
                            isImageMask = isMask, maskFill = fillColor,
                        )
                    } else ImageXObject(
                        width, height, bpc, cs, kind, stream.rawBytes,
                        softMaskAlpha = alpha, softMaskWidth = smW, softMaskHeight = smH,
                        resolvedColorSpace = resolvedCs, decode = decodeArr,
                        isImageMask = isMask, maskFill = fillColor,
                    )
                }
                // For the remaining encoded kinds, hand the raw bytes through —
                // platform code (or a future native decoder) interprets them.
                else -> ImageXObject(
                    width, height, bpc, cs, kind, stream.rawBytes,
                    softMaskAlpha = alpha, softMaskWidth = smW, softMaskHeight = smH,
                    resolvedColorSpace = resolvedCs, decode = decodeArr,
                    isImageMask = isMask, maskFill = fillColor,
                )
            }
        }

        /**
         * Build an image from a self-contained encoded file, as shipped by EPUB /
         * CBZ / SVG `<image>` (rather than pulled from a PDF `/XObject` stream).
         * The format and pixel dimensions are sniffed from the bytes.
         *
         * PNG, GIF and JPEG are decoded here in pure Kotlin ([PngDecoder] /
         * [GifDecoder] / [JpegDecoder]) into a [Kind.RAW] image that renders on
         * every backend. A JPEG the native decoder can't handle (arithmetic coding,
         * 12-bit) falls back to the host platform's loader ([Kind.JPEG] with the
         * file in [encodedBytes]). Unrecognised formats return null, so callers
         * degrade gracefully by skipping the image.
         */
        public fun fromEncodedImage(bytes: ByteArray): ImageXObject? {
            if (PngDecoder.isPng(bytes)) return PngDecoder.decode(bytes)
            if (GifDecoder.isGif(bytes)) return GifDecoder.decode(bytes)
            if (JpegDecoder.isJpeg(bytes)) {
                JpegDecoder.decode(bytes)?.let { return it }
                val (w, h) = jpegSize(bytes) ?: return null
                if (w <= 0 || h <= 0) return null
                return ImageXObject(
                    width = w, height = h, bitsPerComponent = 8,
                    colorSpace = "DeviceRGB", kind = Kind.JPEG, encodedBytes = bytes,
                )
            }
            return null
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

        private fun resolveColorSpace(obj: PdfObject?, refs: IndirectResolver?): ColorSpace? {
            if (obj == null) return null
            return if (refs != null) {
                runCatching { ColorSpace.resolve(obj, refs) }.getOrNull()
            } else {
                // Without a resolver we can only recognise the device families by
                // name (Indexed/ICCBased need the document to fetch their data).
                when (colorSpaceName(obj)) {
                    "DeviceRGB", "RGB", "CalRGB" -> ColorSpace.DeviceRGB
                    "DeviceGray", "G", "CalGray" -> ColorSpace.DeviceGray
                    "DeviceCMYK", "CMYK" -> ColorSpace.DeviceCMYK
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

        /**
         * Decode an image's `/SMask` (ISO 32000-1 §11.6.5.2) — a DeviceGray image
         * whose samples ARE the base image's per-pixel alpha — into a normalised
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
                refs != null -> raw.resolve(refs) as? PdfStream ?: return none
                else -> return none
            }
            val mdict = mask.dict
            val mw = mdict.getInt("Width")?.toInt() ?: return none
            val mh = mdict.getInt("Height")?.toInt() ?: return none
            if (mw <= 0 || mh <= 0) return none
            if (pickKind(extractFilterNames(mdict["Filter"])) != Kind.RAW) return none
            val bytes = runCatching { FilterChain.decode(mask) }.getOrNull() ?: return none
            val alpha = when (mdict.getInt("BitsPerComponent")?.toInt() ?: 8) {
                8 -> if (bytes.size >= mw * mh) bytes.copyOf(mw * mh) else return none
                1 -> expand1BitToGray(bytes, mw, mh) ?: return none
                else -> return none
            }
            return Triple(alpha, mw, mh)
        }

        private fun expand1BitToGray(raw: ByteArray, w: Int, h: Int): ByteArray? {
            val rowBytes = (w + 7) / 8 // 1-bit rows are byte-aligned
            if (raw.size < rowBytes * h) return null
            val out = ByteArray(w * h)
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

        /** The shared JBIG2 globals stream (`/DecodeParms /JBIG2Globals`), decoded. */
        private fun loadJbig2Globals(dict: PdfDictionary, refs: IndirectResolver?): ByteArray? {
            val dp = dict["DecodeParms"] ?: dict["DP"] ?: return null
            fun res(o: PdfObject?) = if (refs != null && o != null) o.resolve(refs) else o
            val parms = when (val d = res(dp)) {
                is PdfDictionary -> d
                is PdfArray -> d.mapNotNull { res(it) as? PdfDictionary }.firstOrNull { it["JBIG2Globals"] != null }
                else -> null
            } ?: return null
            val gs = res(parms["JBIG2Globals"]) as? PdfStream ?: return null
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
                    else -> { /* raw-wrapper or unknown — keep scanning */ }
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
