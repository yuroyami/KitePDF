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
 *   - `JBIG2Decode`, `JPXDecode` (JPEG 2000) — recognised but not decoded yet;
 *     [encodedBytes] holds the raw payload.
 *
 * Callers should switch on [kind] to pick the right rendering path. Stencil masks
 * (`/ImageMask true`) carry [isImageMask] and are tinted by [maskFill].
 */
class ImageXObject internal constructor(
    val width: Int,
    val height: Int,
    val bitsPerComponent: Int,
    val colorSpace: String,
    val kind: Kind,
    /** Encoded bytes — for kinds that defer decoding to a platform image loader. */
    val encodedBytes: ByteArray,
    /** Pixel bytes — populated for [Kind.RAW] (already run through the filter chain). */
    val pixelBytes: ByteArray? = null,
    /**
     * Soft-mask alpha (ISO 32000-1 §11.6.5.2), normalised to 8-bit grayscale —
     * one byte per pixel, 0 = transparent, 255 = opaque, row-major over
     * [softMaskWidth]×[softMaskHeight]. Null when the image carries no `/SMask`.
     */
    val softMaskAlpha: ByteArray? = null,
    val softMaskWidth: Int = 0,
    val softMaskHeight: Int = 0,
    /**
     * The image's colour space resolved against the document (Indexed palettes,
     * ICCBased component counts, etc.). Null for stencil masks and when it could
     * not be resolved (then [toRgbaBytes] infers a device space from the data).
     */
    val resolvedColorSpace: ColorSpace? = null,
    /** `/Decode` array (per-component min/max remap), or null for the identity map. */
    val decode: DoubleArray? = null,
    /** True for `/ImageMask` stencils — 1-bpc, painted with [maskFill]. */
    val isImageMask: Boolean = false,
    /** Fill colour to tint an [isImageMask] stencil (the graphics-state fill colour). */
    val maskFill: RgbColor? = null,
) {

    enum class Kind {
        /** Pixel data already flat in [pixelBytes] (Flate/LZW/CCITT/ASCII/RLE). */
        RAW,
        /** JPEG-encoded; [encodedBytes] is a complete JFIF/EXIF file. */
        JPEG,
        /** CCITT Group 3/4 fax-encoded; not decoded yet. */
        CCITT,
        /** JBIG2-encoded; not decoded yet. */
        JBIG2,
        /** JPEG 2000-encoded; not decoded yet. */
        JPEG2000,
        /** Filter chain not recognised; backends should render a placeholder. */
        UNKNOWN,
    }

    companion object {

        /**
         * Pull a stream from a /XObject /Image resource entry into an [ImageXObject].
         * [refs] (when provided) resolves indirect `/ColorSpace` and `/SMask`
         * references; [fillColor] tints `/ImageMask` stencils (pass the current
         * graphics-state fill colour).
         */
        fun from(
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
                // For encoded kinds, hand the raw bytes through — platform code
                // (or a future native decoder) will interpret them.
                else -> ImageXObject(
                    width, height, bpc, cs, kind, stream.rawBytes,
                    softMaskAlpha = alpha, softMaskWidth = smW, softMaskHeight = smH,
                    resolvedColorSpace = resolvedCs, decode = decodeArr,
                    isImageMask = isMask, maskFill = fillColor,
                )
            }
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
