package com.yuroyami.kitepdf.render

import com.yuroyami.kitepdf.filters.FilterChain
import com.yuroyami.kitepdf.parser.PdfArray
import com.yuroyami.kitepdf.parser.PdfName
import com.yuroyami.kitepdf.parser.PdfObject
import com.yuroyami.kitepdf.parser.PdfStream

/**
 * An XObject Image extracted from a `/XObject` resource entry (ISO 32000-1 §8.9.5).
 *
 * The decoded byte buffer's interpretation depends on the filter chain:
 *
 *   - `DCTDecode` → JPEG file in [encodedBytes] (we expose it raw because most
 *     platform image loaders can decode it directly; we don't ship our own
 *     JPEG decoder in v0.0.3).
 *   - `FlateDecode` → raw pixel data already inflated; [pixelBytes] gives the
 *     pre-decoded buffer in DeviceRGB / DeviceGray order according to
 *     [colorSpace] and [bitsPerComponent].
 *   - `CCITTFaxDecode`, `JBIG2Decode`, `JPXDecode` — recognised but not
 *     decoded yet (Session 4+); [encodedBytes] holds the raw payload.
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
) {

    enum class Kind {
        /** Pixel data already flat in [pixelBytes] (e.g. FlateDecode-only). */
        RAW,
        /** JPEG-encoded; [encodedBytes] is a complete JFIF/EXIF file. */
        JPEG,
        /** CCITT Group 3/4 fax-encoded; not decoded by KitePDF v0.0.3. */
        CCITT,
        /** JBIG2-encoded; not decoded yet. */
        JBIG2,
        /** JPEG 2000-encoded; not decoded yet. */
        JPEG2000,
        /** Filter chain not recognised; backends should render a placeholder. */
        UNKNOWN,
    }

    companion object {

        /** Pull a stream from a /XObject /Image resource entry into an [ImageXObject]. */
        fun from(stream: PdfStream): ImageXObject {
            val dict = stream.dict
            val width = dict.getInt("Width")?.toInt() ?: 0
            val height = dict.getInt("Height")?.toInt() ?: 0
            val bpc = dict.getInt("BitsPerComponent")?.toInt() ?: 8
            val cs = colorSpaceName(dict["ColorSpace"])

            val filters = extractFilterNames(dict["Filter"])
            val kind = pickKind(filters)
            return when (kind) {
                Kind.RAW -> ImageXObject(
                    width, height, bpc, cs, kind,
                    encodedBytes = ByteArray(0),
                    pixelBytes = FilterChain.decode(stream),
                )
                // For encoded kinds, hand the raw bytes through — platform code
                // (or a future native decoder) will interpret them.
                else -> ImageXObject(width, height, bpc, cs, kind, stream.rawBytes)
            }
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
