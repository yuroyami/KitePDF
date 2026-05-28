package com.yuroyami.kitepdf.font

import com.yuroyami.kitepdf.render.PdfPath

/**
 * Type 1 (PostScript) font parser — handles `/FontFile` embedded fonts
 * (Adobe Type 1 Font Format, "the Black Book").
 *
 * A Type 1 font on disk is three sections:
 *
 *   1. **Cleartext PostScript header** (`/Length1` bytes): font dict
 *      `/FontInfo`, `/Encoding` glyph-name array, then `dup /Private begin`.
 *   2. **Binary eexec-encrypted block** (`/Length2` bytes): Private dict
 *      with `/Subrs` and `/CharStrings`. The `eexec` operator decrypts
 *      this with a fixed-stream cipher; the first 4 bytes are random
 *      seed material we skip.
 *   3. **Cleartext trailer** (`/Length3` bytes, ≥ 512): 512 zeros + the
 *      string `cleartomark` to signal the end.
 *
 * Each Subr and CharString inside the eexec block is *separately*
 * charstring-encrypted (different seed) with `/lenIV` random bytes
 * prepended. After we strip those, the bytes are Type 1 charstring
 * bytecodes for [Type1CharstringInterpreter].
 *
 * Scope: rendering only. We extract enough to give every charstring its
 * outline; PostScript expressions and hints that don't affect vector
 * painting are not interpreted.
 */
internal class Type1Font private constructor(
    val name: String,
    private val subrs: List<ByteArray>,
    private val charStrings: Map<String, ByteArray>,
    /** /Encoding[i] = glyph name (or .notdef) for the standard 256 slots. */
    private val encoding: Array<String?>,
    private val lenIV: Int,
) {

    fun outlineForGlyphName(glyphName: String): PdfPath? {
        val cs = charStrings[glyphName] ?: return null
        val decrypted = decryptCharstring(cs, lenIV)
        return runCatching {
            Type1CharstringInterpreter(decrypted, subrs).interpret()
        }.getOrNull()
    }

    /** Look up a glyph by byte code via the font's built-in /Encoding. */
    fun outlineForByte(code: Int): PdfPath? {
        val name = encoding.getOrNull(code and 0xFF) ?: return null
        return outlineForGlyphName(name)
    }

    fun hasGlyphName(name: String): Boolean = charStrings.containsKey(name)
    val glyphNames: Set<String> get() = charStrings.keys

    companion object {

        fun parse(fontFile: ByteArray, length1: Int, length2: Int): Type1Font {
            // Section 1: cleartext PostScript.
            val header = fontFile.copyOfRange(0, minOf(length1, fontFile.size))
            val encoding = parseEncoding(header)
            val fontName = parseFontName(header)

            // Section 2: eexec-encrypted block — decrypt the WHOLE thing then
            // parse the Private dict and its CharStrings + Subrs entries from
            // the decrypted PostScript.
            val eexecBegin = minOf(length1, fontFile.size)
            val eexecEnd = minOf(length1 + length2, fontFile.size)
            val eexec = fontFile.copyOfRange(eexecBegin, eexecEnd)
            val decryptedEexec = decryptEexec(eexec)
            val plaintext = decryptedEexec.copyOfRange(4, decryptedEexec.size)  // strip 4 random bytes

            val lenIV = parseLenIV(plaintext)
            val subrs = parseSubrs(plaintext)
            val charStrings = parseCharStrings(plaintext)

            return Type1Font(fontName, subrs, charStrings, encoding, lenIV)
        }

        /* ─── PostScript header scanning ─────────────────────────────────── */

        private fun parseFontName(header: ByteArray): String {
            val text = header.decodeToString()
            val idx = text.indexOf("/FontName")
            if (idx < 0) return "Unknown"
            val after = text.substring(idx + "/FontName".length).trimStart()
            if (!after.startsWith("/")) return "Unknown"
            val end = after.indexOfAny(charArrayOf(' ', '\n', '\r', '\t', '/', '<'), 1)
            return if (end > 1) after.substring(1, end) else "Unknown"
        }

        /**
         * Parse the `/Encoding` array — 256 entries of `dup <code> /<name> put`
         * lines inside `StandardEncoding` definitions or `Encoding` rewrites.
         * If we find `/Encoding StandardEncoding def`, we use that table.
         */
        private fun parseEncoding(header: ByteArray): Array<String?> {
            val text = header.decodeToString()
            val encodingMark = text.indexOf("/Encoding")
            if (encodingMark < 0) return Encodings.standardEncoding.copyOf()
            // If the line says "StandardEncoding def", use it wholesale.
            val tail = text.substring(encodingMark, minOf(encodingMark + 200, text.length))
            if (tail.contains("StandardEncoding") && tail.contains("def")) {
                return Encodings.standardEncoding.copyOf()
            }
            // Otherwise scan for "dup <int> /<name> put" lines.
            val out: Array<String?> = Encodings.standardEncoding.copyOf()
            val regex = Regex("""dup\s+(\d+)\s+/(\S+)\s+put""")
            for (match in regex.findAll(text.substring(encodingMark))) {
                val code = match.groupValues[1].toIntOrNull() ?: continue
                val name = match.groupValues[2]
                if (code in 0..255) out[code] = name
            }
            return out
        }

        private fun parseLenIV(plaintext: ByteArray): Int {
            val text = plaintext.decodeToString()
            val idx = text.indexOf("/lenIV")
            if (idx < 0) return 4
            val tail = text.substring(idx + "/lenIV".length, minOf(idx + 30, text.length)).trimStart()
            val end = tail.indexOfAny(charArrayOf(' ', '\n', '\r', '\t', '/'))
            val numStr = if (end > 0) tail.substring(0, end) else tail
            return numStr.trim().toIntOrNull() ?: 4
        }

        /**
         * Scan for `/Subrs <count> array … dup <i> <len> RD <bytes> NP` lines.
         * The bytes after RD are the raw encrypted charstring; we capture
         * `<len>` bytes verbatim and key by `<i>`.
         *
         * `RD` is the canonical operator name but fonts sometimes use `-|`.
         * We accept both.
         */
        private fun parseSubrs(plaintext: ByteArray): List<ByteArray> {
            val subrsMark = indexOfBytes(plaintext, "/Subrs".encodeToByteArray()) ?: return emptyList()
            // Determine subr count from "<num> array" pattern.
            val countStr = readNumberAfter(plaintext, subrsMark + "/Subrs".length)
            val count = countStr?.toIntOrNull() ?: return emptyList()
            val out = arrayOfNulls<ByteArray>(count)
            var cursor = subrsMark
            while (cursor < plaintext.size) {
                val dup = indexOfBytes(plaintext, "dup ".encodeToByteArray(), cursor) ?: break
                if (dup > subrsMark + 2000 + count * 50) break   // safety bound
                val nums = readTwoNumbersAfter(plaintext, dup + 4) ?: break
                val (idx, len) = nums.first to nums.second
                // Find the RD or -| operator + single space + bytes.
                val rdEnd = findRdMarker(plaintext, dup + 4) ?: break
                if (rdEnd + len > plaintext.size) break
                if (idx in 0 until count) out[idx] = plaintext.copyOfRange(rdEnd, rdEnd + len)
                cursor = rdEnd + len
            }
            return out.map { it ?: ByteArray(0) }
        }

        /**
         * Scan for `/CharStrings <count> dict dup begin … /<name> <len> RD <bytes> ND`.
         * Same structure as subrs but keyed by glyph name.
         */
        private fun parseCharStrings(plaintext: ByteArray): Map<String, ByteArray> {
            val mark = indexOfBytes(plaintext, "/CharStrings".encodeToByteArray()) ?: return emptyMap()
            val out = HashMap<String, ByteArray>(64)
            var cursor = mark
            val end = plaintext.size
            while (cursor < end) {
                // Find next "/name " pattern.
                val nameStart = findNextGlyphNameAfter(plaintext, cursor) ?: break
                val nameEnd = plaintext.indexOfFirst(nameStart) { b -> b == ' '.code.toByte() || b == '\n'.code.toByte() || b == '\r'.code.toByte() || b == '\t'.code.toByte() }
                if (nameEnd <= nameStart) break
                val name = plaintext.copyOfRange(nameStart, nameEnd).decodeToString()
                // Stop if we've left CharStrings (we hit "end" or "/Private").
                if (name == "end" || name == "Private" || name == "FontDirectory") break
                val nums = readNumberAfter(plaintext, nameEnd)
                val len = nums?.toIntOrNull()
                if (len == null) { cursor = nameEnd + 1; continue }
                val rdEnd = findRdMarker(plaintext, nameEnd) ?: break
                if (rdEnd + len > plaintext.size) break
                out[name] = plaintext.copyOfRange(rdEnd, rdEnd + len)
                cursor = rdEnd + len
            }
            return out
        }

        /* ─── Decryption (eexec + charstring) ────────────────────────────── */

        /**
         * eexec uses a fixed-seed stream cipher (initial seed 55665).
         * The encrypted bytes may be hex-encoded ASCII (older fonts) — we
         * detect that by looking at the first few bytes.
         */
        private fun decryptEexec(input: ByteArray): ByteArray {
            val raw = if (looksLikeHex(input)) hexDecode(input) else input
            return decryptStream(raw, seed = 55665)
        }

        private fun decryptCharstring(input: ByteArray, lenIV: Int): ByteArray {
            val decrypted = decryptStream(input, seed = 4330)
            return if (decrypted.size > lenIV) decrypted.copyOfRange(lenIV, decrypted.size) else ByteArray(0)
        }

        private fun decryptStream(input: ByteArray, seed: Int): ByteArray {
            val out = ByteArray(input.size)
            var r = seed
            val c1 = 52845
            val c2 = 22719
            for (i in input.indices) {
                val cipher = input[i].toInt() and 0xFF
                val plain = cipher xor (r ushr 8)
                out[i] = (plain and 0xFF).toByte()
                r = ((cipher + r) * c1 + c2) and 0xFFFF
            }
            return out
        }

        private fun looksLikeHex(input: ByteArray): Boolean {
            if (input.size < 16) return false
            for (i in 0 until 16) {
                val c = input[i].toInt() and 0xFF
                val isHex = (c in '0'.code..'9'.code) || (c in 'A'.code..'F'.code) || (c in 'a'.code..'f'.code)
                val isWs = c == ' '.code || c == '\t'.code || c == '\n'.code || c == '\r'.code
                if (!isHex && !isWs) return false
            }
            return true
        }

        private fun hexDecode(input: ByteArray): ByteArray {
            val tmp = StringBuilder(input.size)
            for (b in input) {
                val c = b.toInt() and 0xFF
                if ((c in '0'.code..'9'.code) || (c in 'A'.code..'F'.code) || (c in 'a'.code..'f'.code)) {
                    tmp.append(c.toChar())
                }
            }
            val len = tmp.length / 2
            return ByteArray(len) { i ->
                val hi = tmp[i * 2].digitToInt(16)
                val lo = tmp[i * 2 + 1].digitToInt(16)
                ((hi shl 4) or lo).toByte()
            }
        }

        /* ─── Tiny byte-buffer scanning helpers ─────────────────────────── */

        private fun indexOfBytes(haystack: ByteArray, needle: ByteArray, fromIndex: Int = 0): Int? {
            if (needle.isEmpty()) return fromIndex
            outer@ for (i in fromIndex..(haystack.size - needle.size)) {
                for (j in needle.indices) {
                    if (haystack[i + j] != needle[j]) continue@outer
                }
                return i
            }
            return null
        }

        private fun readNumberAfter(buf: ByteArray, start: Int): String? {
            var i = start
            while (i < buf.size && (buf[i] == ' '.code.toByte() || buf[i] == '\t'.code.toByte())) i++
            val begin = i
            while (i < buf.size && buf[i] in '0'.code.toByte()..'9'.code.toByte()) i++
            if (i == begin) return null
            return buf.copyOfRange(begin, i).decodeToString()
        }

        private fun readTwoNumbersAfter(buf: ByteArray, start: Int): Pair<Int, Int>? {
            val a = readNumberAfter(buf, start)?.toIntOrNull() ?: return null
            // Find position after first number.
            var i = start
            while (i < buf.size && (buf[i] == ' '.code.toByte() || buf[i] == '\t'.code.toByte())) i++
            while (i < buf.size && buf[i] in '0'.code.toByte()..'9'.code.toByte()) i++
            val b = readNumberAfter(buf, i)?.toIntOrNull() ?: return null
            return a to b
        }

        private fun findRdMarker(buf: ByteArray, from: Int): Int? {
            // Find " RD " or " -| " — the operator that signals "next <len> bytes are encrypted charstring".
            val patterns = listOf(" RD ".encodeToByteArray(), " -| ".encodeToByteArray())
            for (p in patterns) {
                val idx = indexOfBytes(buf, p, from)
                if (idx != null) return idx + p.size
            }
            return null
        }

        private fun findNextGlyphNameAfter(buf: ByteArray, from: Int): Int? {
            for (i in from..(buf.size - 2)) {
                if (buf[i] == '/'.code.toByte()) {
                    val next = buf[i + 1].toInt() and 0xFF
                    if ((next in 'A'.code..'Z'.code) || (next in 'a'.code..'z'.code) || next == '.'.code) {
                        return i + 1
                    }
                }
            }
            return null
        }

        private fun ByteArray.indexOfFirst(from: Int, predicate: (Byte) -> Boolean): Int {
            for (i in from until size) if (predicate(this[i])) return i
            return -1
        }
    }
}
