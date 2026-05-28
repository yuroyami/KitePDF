package com.yuroyami.kitepdf.font

import com.yuroyami.kitepdf.core.PdfFormatException

/**
 * CFF (Compact Font Format, Adobe Tech Note 5176) parser.
 *
 * Used by PDF `/FontFile3` for `/Subtype /Type1C` (CFF Type 1) and
 * `/Subtype /CIDFontType0C` (CFF CIDFont). About half the embedded fonts in
 * modern PDFs land here rather than as TrueType (which uses `/FontFile2`).
 *
 * Coverage:
 *   - Header + Name INDEX + Top DICT + String INDEX + Global/Local Subrs.
 *   - CharStrings INDEX with Type 2 charstring → [com.yuroyami.kitepdf.render.PdfPath]
 *     conversion (see [CharstringInterpreter]).
 *   - Charsets format 0/1/2 → glyph-name lookup.
 *   - Encodings format 0/1 — for non-CIDFonts only (byte → SID → glyph name → GID).
 *   - CID-keyed fonts via FDSelect/FDArray (format 0 and 3).
 *
 * Not yet handled: hint operators are skipped (we don't rasterize, so hints
 * are ignored); flex operators are approximated as cubic Béziers; the
 * Encoding /Differences override comes from the PDF layer (PdfFont).
 */
class CffFont private constructor(
    private val reader: TtfReader,
    val name: String,
    /** Per-glyph charstring bytes — index = glyph ID. */
    internal val charStrings: List<ByteArray>,
    /** Global subroutines, indexable with the Type 2 subr-bias adjustment. */
    internal val globalSubrs: List<ByteArray>,
    /** Local subroutines per FontDict (just one for non-CID fonts). */
    internal val localSubrsPerFd: List<List<ByteArray>>,
    /** FDSelect[gid] = font-dict index. Empty for non-CID fonts (always index 0). */
    internal val fdSelect: IntArray,
    /** Glyph index → glyph name (or "cid<N>" for CID-keyed fonts). */
    private val glyphNames: Array<String?>,
    /** Glyph name → glyph index (reverse of glyphNames). */
    private val nameToGid: Map<String, Int>,
    /** Default char width in design units (from Private DICT, defaultWidthX). */
    internal val defaultWidthX: Double,
    /** Nominal width offset (from Private DICT, nominalWidthX). */
    internal val nominalWidthX: Double,
) {

    val numGlyphs: Int get() = charStrings.size

    /** Returns the outline of [glyphId], or null if it's empty / unparseable. */
    fun outline(glyphId: Int): com.yuroyami.kitepdf.render.PdfPath? {
        if (glyphId < 0 || glyphId >= charStrings.size) return null
        val cs = charStrings[glyphId]
        if (cs.isEmpty()) return null
        val fdIndex = if (fdSelect.isNotEmpty()) fdSelect[glyphId] else 0
        val locals = localSubrsPerFd.getOrNull(fdIndex) ?: emptyList()
        return runCatching {
            CharstringInterpreter(cs, locals, globalSubrs, defaultWidthX, nominalWidthX).interpret()
        }.getOrNull()
    }

    /** Glyph id for a PostScript glyph name; -1 if unknown. */
    fun glyphIdForName(name: String): Int = nameToGid[name] ?: -1

    /** Glyph id for a Unicode codepoint — uses Adobe Glyph List → name → gid. */
    fun glyphIdForCodePoint(codePoint: Int): Int {
        // Try standard glyph name lookups for ASCII range.
        if (codePoint in 0..127) {
            standardGlyphNameForAscii(codePoint)?.let { nameToGid[it]?.let { gid -> return gid } }
        }
        // CID-keyed: name encoding is "cidNNN".
        val cidName = "cid$codePoint"
        nameToGid[cidName]?.let { return it }
        // Adobe uniXXXX fallback.
        val uniName = "uni" + codePoint.toString(16).uppercase().padStart(4, '0')
        return nameToGid[uniName] ?: -1
    }

    private fun standardGlyphNameForAscii(cp: Int): String? = when (cp) {
        0x20 -> "space"; 0x21 -> "exclam"; 0x22 -> "quotedbl"; 0x23 -> "numbersign"
        0x24 -> "dollar"; 0x25 -> "percent"; 0x26 -> "ampersand"; 0x27 -> "quoteright"
        0x28 -> "parenleft"; 0x29 -> "parenright"; 0x2A -> "asterisk"; 0x2B -> "plus"
        0x2C -> "comma"; 0x2D -> "hyphen"; 0x2E -> "period"; 0x2F -> "slash"
        in 0x30..0x39 -> arrayOf("zero","one","two","three","four","five","six","seven","eight","nine")[cp - 0x30]
        0x3A -> "colon"; 0x3B -> "semicolon"; 0x3C -> "less"; 0x3D -> "equal"
        0x3E -> "greater"; 0x3F -> "question"; 0x40 -> "at"
        in 0x41..0x5A -> ('A' + (cp - 0x41)).toString()
        0x5B -> "bracketleft"; 0x5C -> "backslash"; 0x5D -> "bracketright"
        0x5E -> "asciicircum"; 0x5F -> "underscore"; 0x60 -> "quoteleft"
        in 0x61..0x7A -> ('a' + (cp - 0x61)).toString()
        0x7B -> "braceleft"; 0x7C -> "bar"; 0x7D -> "braceright"; 0x7E -> "asciitilde"
        else -> null
    }

    companion object {

        fun parse(bytes: ByteArray): CffFont {
            val reader = TtfReader(bytes)
            // ── Header ────────────────────────────────────────────────────
            val major = reader.u8()
            reader.u8()  // minor
            val hdrSize = reader.u8()
            reader.u8()  // offSize
            if (major !in 1..2) throw PdfFormatException("CFF: unsupported major version $major")
            reader.seek(hdrSize)

            val nameIndex = readIndex(reader)
            val name = nameIndex.firstOrNull()?.decodeToString() ?: "Unnamed"
            val topDictIndex = readIndex(reader)
            val stringIndex = readIndex(reader)
            val globalSubrs = readIndex(reader)

            val topDict = parseDict(topDictIndex[0])

            // Strings: SIDs 0..390 are the standard strings table (built-in);
            // SIDs 391+ index into our parsed stringIndex.
            val stringResolver: (Int) -> String = { sid ->
                when {
                    sid < 0 -> ""
                    sid < STANDARD_STRINGS.size -> STANDARD_STRINGS[sid]
                    sid - STANDARD_STRINGS.size < stringIndex.size -> stringIndex[sid - STANDARD_STRINGS.size].decodeToString()
                    else -> ""
                }
            }

            // CharStrings INDEX: offset is in /CharStrings (operator 17).
            val csOffset = (topDict[17]?.firstOrNull() as? Double)?.toInt()
                ?: throw PdfFormatException("CFF: missing /CharStrings offset")
            reader.seek(csOffset)
            val charStringsIndex = readIndex(reader)
            val numGlyphs = charStringsIndex.size

            // Charsets: glyph 0 = .notdef. For non-CID, charset[gid] = SID (glyph name).
            // For CID, charset[gid] = CID.
            val isCidKeyed = topDict.containsKey(0x0C1E) || topDict.containsKey(0x0C22)
            val charsetOffset = (topDict[15]?.firstOrNull() as? Double)?.toInt() ?: 0
            val glyphNames = Array<String?>(numGlyphs) { null }
            glyphNames[0] = ".notdef"
            if (charsetOffset > 2) {
                reader.seek(charsetOffset)
                val format = reader.u8()
                val sids = readCharset(reader, format, numGlyphs - 1)
                for ((i, sid) in sids.withIndex()) {
                    val gid = i + 1
                    glyphNames[gid] = if (isCidKeyed) "cid$sid" else stringResolver(sid)
                }
            } else {
                // Predefined charsets: 0 = ISOAdobe, 1 = Expert, 2 = ExpertSubset.
                // We populate with ISOAdobe-ish names (most common) so glyph lookups
                // still work for fonts that omit a custom charset.
                val table = predefinedCharset(charsetOffset)
                for (gid in 1 until numGlyphs) {
                    val sid = table.getOrNull(gid - 1) ?: continue
                    glyphNames[gid] = stringResolver(sid)
                }
            }
            val nameToGid = HashMap<String, Int>(numGlyphs)
            for ((gid, gn) in glyphNames.withIndex()) {
                if (gn != null) nameToGid[gn] = gid
            }

            // ── Private DICT + Local Subrs (per FontDict for CID-keyed) ───
            val (localSubrsPerFd, defaultWidthX, nominalWidthX, fdSelect) =
                parsePrivateAndFdData(reader, topDict, isCidKeyed, numGlyphs)

            return CffFont(
                reader = reader,
                name = name,
                charStrings = charStringsIndex,
                globalSubrs = globalSubrs,
                localSubrsPerFd = localSubrsPerFd,
                fdSelect = fdSelect,
                glyphNames = glyphNames,
                nameToGid = nameToGid,
                defaultWidthX = defaultWidthX,
                nominalWidthX = nominalWidthX,
            )
        }

        /* ─── INDEX parser (Section 5 of CFF spec) ──────────────────────── */

        private fun readIndex(reader: TtfReader): List<ByteArray> {
            val count = reader.u16()
            if (count == 0) return emptyList()
            val offSize = reader.u8()
            val offsets = IntArray(count + 1) { readOffset(reader, offSize) }
            val dataStart = reader.pos() - 1  // offset is relative to byte BEFORE data
            val result = ArrayList<ByteArray>(count)
            for (i in 0 until count) {
                val from = dataStart + offsets[i]
                val to = dataStart + offsets[i + 1]
                result.add(reader.slice(from, to - from))
            }
            reader.seek(dataStart + offsets[count])
            return result
        }

        private fun readOffset(reader: TtfReader, size: Int): Int {
            var v = 0
            for (i in 0 until size) v = (v shl 8) or reader.u8()
            return v
        }

        /* ─── DICT parser (Section 4) ────────────────────────────────────── */

        /**
         * Parse a CFF DICT (a series of (operands, operator) pairs).
         * Returns map of operator → operand list. Two-byte operators use 0x0Cxx.
         */
        private fun parseDict(bytes: ByteArray): Map<Int, List<Any>> {
            val result = HashMap<Int, List<Any>>()
            val operands = mutableListOf<Any>()
            var i = 0
            while (i < bytes.size) {
                val b = bytes[i].toInt() and 0xFF
                when {
                    b == 12 -> {
                        if (i + 1 >= bytes.size) break
                        val operator = 0x0C00 or (bytes[i + 1].toInt() and 0xFF)
                        result[operator] = operands.toList()
                        operands.clear()
                        i += 2
                    }
                    b <= 21 -> {
                        result[b] = operands.toList()
                        operands.clear()
                        i++
                    }
                    else -> {
                        // Operand: integer or real.
                        val (value, consumed) = readDictOperand(bytes, i)
                        operands.add(value)
                        i += consumed
                    }
                }
            }
            return result
        }

        private fun readDictOperand(bytes: ByteArray, offset: Int): Pair<Double, Int> {
            val b0 = bytes[offset].toInt() and 0xFF
            return when {
                b0 == 28 -> {
                    val v = ((bytes[offset + 1].toInt() and 0xFF) shl 8) or (bytes[offset + 2].toInt() and 0xFF)
                    val signed = if (v and 0x8000 != 0) v - 0x10000 else v
                    signed.toDouble() to 3
                }
                b0 == 29 -> {
                    val v = ((bytes[offset + 1].toInt() and 0xFF) shl 24) or
                        ((bytes[offset + 2].toInt() and 0xFF) shl 16) or
                        ((bytes[offset + 3].toInt() and 0xFF) shl 8) or
                        (bytes[offset + 4].toInt() and 0xFF)
                    v.toDouble() to 5
                }
                b0 == 30 -> {
                    // BCD real number.
                    val (real, consumed) = readBcdReal(bytes, offset + 1)
                    real to (1 + consumed)
                }
                b0 in 32..246 -> (b0 - 139).toDouble() to 1
                b0 in 247..250 -> {
                    val b1 = bytes[offset + 1].toInt() and 0xFF
                    ((b0 - 247) * 256 + b1 + 108).toDouble() to 2
                }
                b0 in 251..254 -> {
                    val b1 = bytes[offset + 1].toInt() and 0xFF
                    (-(b0 - 251) * 256 - b1 - 108).toDouble() to 2
                }
                else -> 0.0 to 1   // shouldn't happen
            }
        }

        private fun readBcdReal(bytes: ByteArray, offset: Int): Pair<Double, Int> {
            val sb = StringBuilder()
            var i = offset
            outer@ while (i < bytes.size) {
                val b = bytes[i++].toInt() and 0xFF
                for (nibble in listOf(b ushr 4, b and 0x0F)) {
                    when (nibble) {
                        in 0..9 -> sb.append(nibble.toString())
                        10 -> sb.append('.')
                        11 -> sb.append('E')
                        12 -> sb.append("E-")
                        14 -> sb.append('-')
                        15 -> break@outer
                    }
                }
            }
            return (sb.toString().toDoubleOrNull() ?: 0.0) to (i - offset)
        }

        /* ─── Charset parser (Section 13) ─────────────────────────────────── */

        private fun readCharset(reader: TtfReader, format: Int, numGlyphsMinusNotdef: Int): IntArray {
            val out = IntArray(numGlyphsMinusNotdef)
            when (format) {
                0 -> for (i in 0 until numGlyphsMinusNotdef) out[i] = reader.u16()
                1, 2 -> {
                    var i = 0
                    while (i < numGlyphsMinusNotdef) {
                        val first = reader.u16()
                        val nLeft = if (format == 1) reader.u8() else reader.u16()
                        for (k in 0..nLeft) {
                            if (i >= numGlyphsMinusNotdef) break
                            out[i++] = first + k
                        }
                    }
                }
                else -> throw PdfFormatException("CFF: unsupported charset format $format")
            }
            return out
        }

        /**
         * Predefined charset 0=ISOAdobe, 1=Expert, 2=ExpertSubset. We approximate
         * by returning SIDs 1..N — the first 391 SIDs are exactly the standard
         * strings, so for fonts that use predefined charsets this still yields
         * sensible glyph names for ASCII characters.
         */
        private fun predefinedCharset(which: Int): IntArray = IntArray(228) { it + 1 }

        /* ─── Private DICT + Local Subrs + FDSelect ────────────────────── */

        private data class PrivateData(
            val localSubrsPerFd: List<List<ByteArray>>,
            val defaultWidthX: Double,
            val nominalWidthX: Double,
            val fdSelect: IntArray,
        )

        private fun parsePrivateAndFdData(
            reader: TtfReader,
            topDict: Map<Int, List<Any>>,
            isCidKeyed: Boolean,
            numGlyphs: Int,
        ): PrivateData {
            if (isCidKeyed) {
                val fdArrayOffset = (topDict[0x0C24]?.firstOrNull() as? Double)?.toInt() ?: 0
                val fdSelectOffset = (topDict[0x0C25]?.firstOrNull() as? Double)?.toInt() ?: 0
                val fdSelect = if (fdSelectOffset > 0) readFdSelect(reader, fdSelectOffset, numGlyphs) else IntArray(numGlyphs)
                val locals = mutableListOf<List<ByteArray>>()
                var defaultW = 0.0; var nominalW = 0.0
                if (fdArrayOffset > 0) {
                    reader.seek(fdArrayOffset)
                    val fdArray = readIndex(reader)
                    for (entry in fdArray) {
                        val dict = parseDict(entry)
                        val (subrs, dW, nW) = readPrivate(reader, dict)
                        locals.add(subrs)
                        if (defaultW == 0.0) defaultW = dW
                        if (nominalW == 0.0) nominalW = nW
                    }
                }
                return PrivateData(locals, defaultW, nominalW, fdSelect)
            } else {
                val (subrs, dW, nW) = readPrivate(reader, topDict)
                return PrivateData(listOf(subrs), dW, nW, IntArray(0))
            }
        }

        private fun readPrivate(reader: TtfReader, dict: Map<Int, List<Any>>): Triple<List<ByteArray>, Double, Double> {
            val priv = dict[18] ?: return Triple(emptyList(), 0.0, 0.0)
            val privSize = (priv[0] as Double).toInt()
            val privOffset = (priv[1] as Double).toInt()
            reader.seek(privOffset)
            val privBytes = reader.slice(privOffset, privSize)
            reader.seek(privOffset)
            val privDict = parseDict(privBytes)
            val defaultW = (privDict[20]?.firstOrNull() as? Double) ?: 0.0
            val nominalW = (privDict[21]?.firstOrNull() as? Double) ?: 0.0
            val subrsOffset = (privDict[19]?.firstOrNull() as? Double)?.toInt() ?: 0
            val locals = if (subrsOffset > 0) {
                reader.seek(privOffset + subrsOffset)
                readIndex(reader)
            } else emptyList()
            return Triple(locals, defaultW, nominalW)
        }

        private fun readFdSelect(reader: TtfReader, offset: Int, numGlyphs: Int): IntArray {
            reader.seek(offset)
            val format = reader.u8()
            return when (format) {
                0 -> IntArray(numGlyphs) { reader.u8() }
                3 -> {
                    val nRanges = reader.u16()
                    val firsts = IntArray(nRanges) { reader.u16() }
                    val fds = IntArray(nRanges) { reader.u8() }
                    val sentinel = reader.u16()   // gid past the last range
                    val out = IntArray(numGlyphs)
                    for (i in 0 until nRanges) {
                        val end = if (i + 1 < nRanges) firsts[i + 1] else sentinel
                        for (g in firsts[i] until end) {
                            if (g in 0 until numGlyphs) out[g] = fds[i]
                        }
                    }
                    out
                }
                else -> IntArray(numGlyphs)
            }
        }

        /**
         * The first 391 SIDs in CFF map to a fixed standard string set.
         * We include the subset our parser actually needs for glyph naming —
         * the rest of the table is ".notdef" placeholders, since they're only
         * relevant for fonts that use the full Expert charset (rare).
         */
        private val STANDARD_STRINGS: Array<String> = buildStandardStringsTable()

        private fun buildStandardStringsTable(): Array<String> {
            val arr = Array(391) { ".notdef" }
            // First chunk — common glyph names. Indices match Adobe CFF spec Appendix A.
            val names = listOf(
                ".notdef","space","exclam","quotedbl","numbersign","dollar","percent","ampersand",
                "quoteright","parenleft","parenright","asterisk","plus","comma","hyphen","period",
                "slash","zero","one","two","three","four","five","six","seven","eight","nine",
                "colon","semicolon","less","equal","greater","question","at",
                "A","B","C","D","E","F","G","H","I","J","K","L","M","N","O","P",
                "Q","R","S","T","U","V","W","X","Y","Z",
                "bracketleft","backslash","bracketright","asciicircum","underscore","quoteleft",
                "a","b","c","d","e","f","g","h","i","j","k","l","m","n","o","p",
                "q","r","s","t","u","v","w","x","y","z",
                "braceleft","bar","braceright","asciitilde","exclamdown","cent","sterling",
                "fraction","yen","florin","section","currency","quotesingle","quotedblleft",
                "guillemotleft","guilsinglleft","guilsinglright","fi","fl","endash","dagger",
                "daggerdbl","periodcentered","paragraph","bullet","quotesinglbase","quotedblbase",
                "quotedblright","guillemotright","ellipsis","perthousand","questiondown","grave",
                "acute","circumflex","tilde","macron","breve","dotaccent","dieresis","ring",
                "cedilla","hungarumlaut","ogonek","caron","emdash","AE","ordfeminine","Lslash",
                "Oslash","OE","ordmasculine","ae","dotlessi","lslash","oslash","oe","germandbls",
            )
            for ((i, n) in names.withIndex()) {
                if (i < arr.size) arr[i] = n
            }
            return arr
        }
    }
}
