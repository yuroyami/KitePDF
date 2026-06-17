package io.github.yuroyami.kitepdf.render

import io.github.yuroyami.kitepdf.filters.FilterChain
import io.github.yuroyami.kitepdf.parser.IndirectResolver
import io.github.yuroyami.kitepdf.parser.PdfArray
import io.github.yuroyami.kitepdf.parser.PdfDictionary
import io.github.yuroyami.kitepdf.parser.PdfName
import io.github.yuroyami.kitepdf.parser.PdfObject
import io.github.yuroyami.kitepdf.parser.PdfStream

/**
 * An XObject Image extracted from a `/XObject` resource entry (ISO 32000-1 §8.9.5).
 *
 * The decoded byte buffer's interpretation depends on the filter chain:
 *
 *   - `DCTDecode` → JPEG file in [encodedBytes]; decoded by the host platform's
 *     image loader (see `ImageDecoder` in `:kitepdf-compose`).
 *   - `FlateDecode` → raw pixel data already inflated; [pixelBytes] gives the
 *     pre-decoded buffer in DeviceRGB / DeviceGray order according to
 *     [colorSpace] and [bitsPerComponent].
 *   - `CCITTFaxDecode`, `JBIG2Decode`, `JPXDecode` — recognised but not
 *     decoded yet; [encodedBytes] holds the raw payload.
 *
 * Callers should switch on [kind] to pick the right rendering path.
 */
class ImageXObject internal constructor(
    val width: Int,
    val height: Int,
    val bitsPerComponent: Int,
    val colorSpace: String,
    val kind: Kind,
    /** Encoded bytes — for kinds that defer decoding to a platform image loader. */
    val encodedBytes: ByteArray,
    /** Pixel bytes — only populated for [Kind.RAW]. */
    val pixelBytes: ByteArray? = null,
    /**
     * Soft-mask alpha (ISO 32000-1 §11.6.5.2), normalised to 8-bit grayscale —
     * one byte per pixel, 0 = transparent, 255 = opaque, row-major over
     * [softMaskWidth]×[softMaskHeight]. Null when the image carries no `/SMask`.
     */
    val softMaskAlpha: ByteArray? = null,
    val softMaskWidth: Int = 0,
    val softMaskHeight: Int = 0,
) {

    enum class Kind {
        /** Pixel data already flat in [pixelBytes] (e.g. FlateDecode-only). */
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
         * [refs] (when provided) resolves an indirect `/SMask` reference so the
         * image's alpha is carried through; without it, only a directly-embedded
         * mask is read.
         */
        fun from(stream: PdfStream, refs: IndirectResolver? = null): ImageXObject {
            val dict = stream.dict
            val width = dict.getInt("Width")?.toInt() ?: 0
            val height = dict.getInt("Height")?.toInt() ?: 0
            val bpc = dict.getInt("BitsPerComponent")?.toInt() ?: 8
            val cs = colorSpaceName(dict["ColorSpace"])

            val (alpha, smW, smH) = loadSoftMask(dict, refs)

            val filters = extractFilterNames(dict["Filter"])
            val kind = pickKind(filters)
            return when (kind) {
                Kind.RAW -> ImageXObject(
                    width, height, bpc, cs, kind,
                    encodedBytes = ByteArray(0),
                    pixelBytes = FilterChain.decode(stream),
                    softMaskAlpha = alpha, softMaskWidth = smW, softMaskHeight = smH,
                )
                // For encoded kinds, hand the raw bytes through — platform code
                // (or a future native decoder) will interpret them.
                else -> ImageXObject(
                    width, height, bpc, cs, kind, stream.rawBytes,
                    softMaskAlpha = alpha, softMaskWidth = smW, softMaskHeight = smH,
                )
            }
        }

        /**
         * Decode an image's `/SMask` (ISO 32000-1 §11.6.5.2) — a DeviceGray image
         * whose samples ARE the base image's per-pixel alpha — into a normalised
         * 8-bit grayscale buffer (0 = transparent, 255 = opaque).
         *
         * Scope: RAW (Flate/LZW/…) DeviceGray masks at 1 or 8 bpc. A DCT/JPX-encoded
         * mask (needs a platform decoder) is skipped, leaving the image opaque.
         * The `/Decode` invert array is not yet applied.
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
                    "CCITTFaxDecode", "CCF" -> return Kind.CCITT
                    "JBIG2Decode" -> return Kind.JBIG2
                    "FlateDecode", "Fl", "ASCIIHexDecode", "AHx",
                    "ASCII85Decode", "A85", "RunLengthDecode", "RL", "LZWDecode", "LZW" -> {
                        // Continue scanning — these are wrappers around raw pixel data.
                    }
                    else -> { /* unknown filter — keep scanning */ }
                }
            }
            // No image-specific filter: bytes are raw pixels (possibly Flate-wrapped).
            return if (filters.isEmpty()) Kind.RAW
            else if (filters.all { it in OK_RAW_WRAPPERS }) Kind.RAW
            else Kind.UNKNOWN
        }

        private val OK_RAW_WRAPPERS = setOf(
            "FlateDecode", "Fl", "ASCIIHexDecode", "AHx", "ASCII85Decode", "A85",
            "RunLengthDecode", "RL", "LZWDecode", "LZW",
        )
    }
}
