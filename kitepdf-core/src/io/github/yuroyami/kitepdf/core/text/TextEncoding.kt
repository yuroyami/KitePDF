package io.github.yuroyami.kitepdf.core.text

/**
 * Turns bytes into text when nobody can be trusted about the encoding.
 *
 * EPUB says UTF-8 or UTF-16 and nothing else. Real books ship Windows-1252
 * anyway, sometimes while declaring UTF-8, which is why the declaration is
 * treated as a hint rather than an answer.
 *
 * The order of evidence, strongest first:
 *
 * 1. A byte order mark.
 * 2. UTF-16 without one, spotted by the NUL pattern of an ASCII opener.
 * 3. A declaration in the bytes: `<?xml encoding=...?>`, `<meta charset>`,
 *    or the legacy `<meta http-equiv="Content-Type">`.
 * 4. The caller's [hint], e.g. an HTTP `Content-Type`.
 * 5. Valid UTF-8 stays UTF-8; anything else is decoded as Windows-1252,
 *    which never fails and covers the Western-European mojibake case.
 *
 * ```kotlin
 * val html = TextEncoding.decode(zip.read("chapter1.xhtml")!!)
 * ```
 */
public object TextEncoding {

    /** Canonical name of the encoding [decode] would use: see the class doc. */
    public fun sniff(bytes: ByteArray, hint: String? = null): String {
        bomOf(bytes)?.let { return it }
        utf16WithoutBom(bytes)?.let { return it }
        return when (val declared = canonical(declaredName(bytes) ?: hint)) {
            UTF_16LE, UTF_16BE, CP1252 -> declared
            else -> if (isValidUtf8(bytes)) UTF_8 else CP1252
        }
    }

    /** Decode [bytes], guessing the encoding. Never throws; never returns null. */
    public fun decode(bytes: ByteArray, hint: String? = null): String {
        if (bytes.isEmpty()) return ""
        val bom = bomOf(bytes)
        return when (val enc = sniff(bytes, hint)) {
            UTF_16LE -> decodeUtf16(bytes, if (bom != null) 2 else 0, littleEndian = true)
            UTF_16BE -> decodeUtf16(bytes, if (bom != null) 2 else 0, littleEndian = false)
            CP1252 -> decode1252(bytes)
            else -> bytes.decodeToString(if (enc == UTF_8 && bom != null) 3 else 0, bytes.size)
        }
    }

    /* ─── Evidence ───────────────────────────────────────────────────────── */

    private fun bomOf(b: ByteArray): String? = when {
        b.size >= 3 && b[0] == 0xEF.toByte() && b[1] == 0xBB.toByte() && b[2] == 0xBF.toByte() -> UTF_8
        b.size >= 2 && b[0] == 0xFF.toByte() && b[1] == 0xFE.toByte() -> UTF_16LE
        b.size >= 2 && b[0] == 0xFE.toByte() && b[1] == 0xFF.toByte() -> UTF_16BE
        else -> null
    }

    /**
     * ASCII text stored as UTF-16 is half NUL bytes, all on one side of each
     * pair. That pattern cannot occur in UTF-8 text, which carries no NULs.
     */
    private fun utf16WithoutBom(b: ByteArray): String? {
        val n = minOf(b.size, 64)
        if (n < 4) return null
        var evenZeros = 0
        var oddZeros = 0
        for (i in 0 until n) if (b[i].toInt() == 0) { if (i % 2 == 0) evenZeros++ else oddZeros++ }
        val zeros = evenZeros + oddZeros
        if (zeros * 100 < n * 30) return null
        return if (oddZeros > evenZeros) UTF_16LE else UTF_16BE
    }

    /** The encoding the document names about itself, from its first kilobyte. */
    private fun declaredName(b: ByteArray): String? {
        val head = buildString(minOf(b.size, HEAD)) {
            for (i in 0 until minOf(b.size, HEAD)) {
                val c = b[i].toInt() and 0xFF
                append(if (c == 0) ' ' else c.toChar())   // NUL-strip so UTF-16 still reads
            }
        }.lowercase()
        xmlDeclEncoding(head)?.let { return it }
        metaCharset(head)?.let { return it }
        return null
    }

    private fun xmlDeclEncoding(head: String): String? {
        val start = head.indexOf("<?xml")
        if (start < 0) return null
        val end = head.indexOf("?>", start).let { if (it < 0) head.length else it }
        return attributeValue(head.substring(start, end), "encoding")
    }

    private fun metaCharset(head: String): String? {
        var at = head.indexOf("<meta")
        while (at >= 0) {
            val end = head.indexOf('>', at).let { if (it < 0) head.length else it }
            val tag = head.substring(at, end)
            attributeValue(tag, "charset")?.let { return it }
            if ("http-equiv" in tag) {
                val content = attributeValue(tag, "content")
                val cs = content?.substringAfter("charset=", "")?.trim()
                if (!cs.isNullOrEmpty()) return cs.trim(';', '"', '\'', ' ')
            }
            at = head.indexOf("<meta", at + 5)
        }
        return null
    }

    /** `name=value`, quoted or bare, out of one already-lowercased tag. */
    private fun attributeValue(tag: String, name: String): String? {
        var i = tag.indexOf(name)
        while (i >= 0) {
            // Reject a suffix match, e.g. "content" inside "http-content".
            val before = if (i == 0) ' ' else tag[i - 1]
            if (before.isWhitespace() || before == '<') {
                var j = i + name.length
                while (j < tag.length && tag[j].isWhitespace()) j++
                if (j < tag.length && tag[j] == '=') {
                    j++
                    while (j < tag.length && tag[j].isWhitespace()) j++
                    if (j >= tag.length) return null
                    val quote = tag[j]
                    return if (quote == '"' || quote == '\'') {
                        val close = tag.indexOf(quote, j + 1)
                        if (close < 0) null else tag.substring(j + 1, close)
                    } else {
                        val close = tag.indexOfFirst(j) { it.isWhitespace() || it == '>' || it == '/' }
                        tag.substring(j, close)
                    }
                }
            }
            i = tag.indexOf(name, i + 1)
        }
        return null
    }

    private inline fun String.indexOfFirst(from: Int, pred: (Char) -> Boolean): Int {
        for (k in from until length) if (pred(this[k])) return k
        return length
    }

    private fun canonical(name: String?): String? = when (name?.trim()?.trim('"', '\'')?.lowercase()) {
        null, "" -> null
        "utf-8", "utf8", "us-ascii", "ascii", "iso-8859-15" -> UTF_8
        "utf-16", "utf-16le", "utf16", "utf16le", "unicode" -> UTF_16LE
        "utf-16be", "utf16be" -> UTF_16BE
        "windows-1252", "cp1252", "cp-1252", "iso-8859-1", "iso8859-1", "latin1", "latin-1" -> CP1252
        else -> null
    }

    /* ─── Decoders ───────────────────────────────────────────────────────── */

    private fun decodeUtf16(b: ByteArray, from: Int, littleEndian: Boolean): String {
        val n = (b.size - from) / 2
        val chars = CharArray(n)
        for (i in 0 until n) {
            val lo = b[from + i * 2].toInt() and 0xFF
            val hi = b[from + i * 2 + 1].toInt() and 0xFF
            chars[i] = (if (littleEndian) (hi shl 8) or lo else (lo shl 8) or hi).toChar()
        }
        return chars.concatToString()
    }

    private fun decode1252(b: ByteArray): String {
        val chars = CharArray(b.size)
        for (i in b.indices) {
            val v = b[i].toInt() and 0xFF
            chars[i] = if (v in 0x80..0x9F) CP1252_HIGH[v - 0x80] else v.toChar()
        }
        return chars.concatToString()
    }

    /** RFC 3629 validity, rejecting overlong forms, surrogates and out-of-range. */
    private fun isValidUtf8(b: ByteArray): Boolean {
        var i = 0
        while (i < b.size) {
            val c = b[i].toInt() and 0xFF
            val len: Int
            val min: Int
            when {
                c < 0x80 -> { i++; continue }
                c in 0xC2..0xDF -> { len = 2; min = 0x80 }
                c in 0xE0..0xEF -> { len = 3; min = 0x800 }
                c in 0xF0..0xF4 -> { len = 4; min = 0x10000 }
                else -> return false
            }
            if (i + len > b.size) return false
            var cp = c and (0x7F ushr len)
            for (k in 1 until len) {
                val cc = b[i + k].toInt() and 0xFF
                if (cc !in 0x80..0xBF) return false
                cp = (cp shl 6) or (cc and 0x3F)
            }
            if (cp < min || cp > 0x10FFFF || cp in 0xD800..0xDFFF) return false
            i += len
        }
        return true
    }

    private const val UTF_8 = "UTF-8"
    private const val UTF_16LE = "UTF-16LE"
    private const val UTF_16BE = "UTF-16BE"
    private const val CP1252 = "windows-1252"
    private const val HEAD = 1024

    /** Windows-1252's 0x80..0x9F, the only range where it differs from Latin-1. */
    private val CP1252_HIGH = charArrayOf(
        '\u20AC', '\u0081', '\u201A', '\u0192', '\u201E', '\u2026', '\u2020', '\u2021',
        '\u02C6', '\u2030', '\u0160', '\u2039', '\u0152', '\u008D', '\u017D', '\u008F',
        '\u0090', '\u2018', '\u2019', '\u201C', '\u201D', '\u2022', '\u2013', '\u2014',
        '\u02DC', '\u2122', '\u0161', '\u203A', '\u0153', '\u009D', '\u017E', '\u0178',
    )
}
