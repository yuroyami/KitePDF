package io.github.yuroyami.kitepdf.core

/**
 * The content of a `data:` URL (RFC 2397): its media type and its bytes. A document carries a
 * small resource inside itself this way, an image or a font as Base64, or an SVG percent-encoded:
 *
 * ```kotlin
 * val url = KiteDataUrl.decode("data:image/svg+xml,%3Csvg xmlns='http://www.w3.org/2000/svg'/%3E")
 * url?.mediaType   // "image/svg+xml"
 * url?.bytes       // the SVG's UTF-8 bytes
 * ```
 *
 * [decode] follows the data URL processor of the WHATWG Fetch standard: the body is
 * percent-decoded, then Base64-decoded when the header ends in `;base64`, forgiving white
 * space and missing padding.
 */
public class KiteDataUrl private constructor(
    /** The media type with its parameters, as the URL gives it, `text/plain;charset=US-ASCII` when it gives none. */
    public val mediaType: String,
    /** The decoded content. */
    public val bytes: ByteArray,
) {
    /** The media type without its parameters, lower-cased: `image/svg+xml` for `image/svg+xml;charset=utf-8`. */
    public val essence: String get() = typeEssence(mediaType)

    public companion object {
        /** Whether [url] is a `data:` URL, whatever the case of its scheme and the spaces around it. */
        public fun isDataUrl(url: String): Boolean {
            // A URL parser strips C0 controls and spaces from the ends (URL Standard, 4.4).
            val start = url.indexOfFirst { it > ' ' }
            return start >= 0 && url.regionMatches(start, "data:", 0, 5, ignoreCase = true)
        }

        /**
         * The media type essence that [url] declares, without decoding its body, or null when
         * [url] is not a `data:` URL. Cheap enough to ask of every image source.
         */
        public fun essenceOf(url: String): String? {
            if (!isDataUrl(url)) return null
            val body = url.trim { it <= ' ' }.substring(5)
            val comma = body.indexOf(',')
            if (comma < 0) return null
            return typeEssence(headerType(body.substring(0, comma)))
        }

        /** The content of [url], or null when it is not a `data:` URL or its body does not decode. */
        public fun decode(url: String): KiteDataUrl? {
            if (!isDataUrl(url)) return null
            // A fragment is not part of the body (URL Standard, 4.4).
            val body = url.trim { it <= ' ' }.substring(5).substringBefore('#')
            val comma = body.indexOf(',')
            if (comma < 0) return null
            var header = body.substring(0, comma).trim { isAsciiWhitespace(it) }
            var bytes = percentDecode(body.substring(comma + 1))
            val semicolon = header.lastIndexOf(';')
            if (semicolon >= 0 && header.substring(semicolon + 1).trim { isAsciiWhitespace(it) }.equals("base64", ignoreCase = true)) {
                bytes = forgivingBase64(bytes) ?: return null
                header = header.substring(0, semicolon)
            }
            return KiteDataUrl(headerType(header), bytes)
        }

        /** The media type a data URL's header gives, with the defaults of the Fetch standard. */
        private fun headerType(header: String): String {
            val type = header.trim { isAsciiWhitespace(it) }
            return when {
                type.isEmpty() -> "text/plain;charset=US-ASCII"
                type.startsWith(';') -> "text/plain$type"
                '/' !in type.substringBefore(';') -> "text/plain;charset=US-ASCII"
                else -> type
            }
        }

        private fun typeEssence(type: String): String = type.substringBefore(';').trim { isAsciiWhitespace(it) }.lowercase()

        private fun isAsciiWhitespace(c: Char): Boolean = c == ' ' || c == '\t' || c == '\n' || c == '\u000C' || c == '\r'

        /** The bytes of [text] with each `%XX` decoded, and every other character as UTF-8. */
        private fun percentDecode(text: String): ByteArray {
            val utf8 = text.encodeToByteArray()
            if ('%' !in text) return utf8
            val out = ByteArray(utf8.size)
            var n = 0
            var i = 0
            while (i < utf8.size) {
                val b = utf8[i]
                if (b == '%'.code.toByte() && i + 2 < utf8.size) {
                    val hi = hex(utf8[i + 1]); val lo = hex(utf8[i + 2])
                    if (hi >= 0 && lo >= 0) { out[n++] = ((hi shl 4) or lo).toByte(); i += 3; continue }
                }
                out[n++] = b
                i++
            }
            return out.copyOf(n)
        }

        private fun hex(b: Byte): Int = when (val c = b.toInt().toChar()) {
            in '0'..'9' -> c - '0'
            in 'a'..'f' -> c - 'a' + 10
            in 'A'..'F' -> c - 'A' + 10
            else -> -1
        }

        /**
         * Forgiving Base64 (Infra Standard, 4.6): white space is dropped, one or two `=` may close a
         * whole number of quads or be left off, and any other character outside the alphabet fails.
         */
        private fun forgivingBase64(data: ByteArray): ByteArray? {
            val chars = ByteArray(data.size)
            var n = 0
            for (b in data) if (!isAsciiWhitespace(b.toInt().toChar())) chars[n++] = b
            if (n % 4 == 0 && n > 0 && chars[n - 1] == '='.code.toByte()) {
                n--
                if (chars[n - 1] == '='.code.toByte()) n--
            }
            if (n % 4 == 1) return null
            val out = ByteArray(n * 3 / 4)
            var acc = 0
            var bits = 0
            var o = 0
            for (i in 0 until n) {
                val v = when (val c = chars[i].toInt().toChar()) {
                    in 'A'..'Z' -> c - 'A'
                    in 'a'..'z' -> c - 'a' + 26
                    in '0'..'9' -> c - '0' + 52
                    '+' -> 62
                    '/' -> 63
                    else -> return null
                }
                acc = (acc shl 6) or v
                bits += 6
                if (bits >= 8) {
                    bits -= 8
                    out[o++] = (acc shr bits).toByte()
                    acc = acc and ((1 shl bits) - 1)
                }
            }
            return if (o == out.size) out else out.copyOf(o)
        }
    }
}
