package io.github.yuroyami.kitepdf.parser

import io.github.yuroyami.kitepdf.core.parser.Lexer
import io.github.yuroyami.kitepdf.core.parser.PdfDictionary
import io.github.yuroyami.kitepdf.core.parser.PdfObject
import io.github.yuroyami.kitepdf.core.parser.PdfReference
import io.github.yuroyami.kitepdf.core.parser.PdfStream
import io.github.yuroyami.kitepdf.core.parser.Token

import io.github.yuroyami.kitepdf.core.ByteReader
import io.github.yuroyami.kitepdf.core.PdfFormatException
import io.github.yuroyami.kitepdf.core.filters.FilterChain

/**
 * Malformed-file recovery (ISO 32000-1 has no formal spec for this — it mirrors
 * MuPDF's `pdf_repair_xref` in `pdf-repair.c`).
 *
 * When the cross-reference table is missing, corrupt, truncated, or points at
 * the wrong offsets, the only reliable structure left is the object bodies
 * themselves. This pass linearly scans the whole file for `N G obj` headers and
 * rebuilds the xref from their real byte offsets (latest occurrence wins, so an
 * incrementally-updated object's newest revision is chosen). Object streams are
 * decoded so their compressed members are recovered too, and the trailer's
 * `/Root` (plus `/Encrypt`, `/ID`, `/Info`) is recovered from any `trailer`
 * dictionary, `/Type /XRef` stream dict, or `/Type /Catalog` object found.
 *
 * This is a best-effort, last-resort path: it never throws for a recoverable
 * file and degrades to whatever it can find.
 */
internal object PdfRepair {

    private val OBJ = "obj".encodeToByteArray()
    private val TRAILER = "trailer".encodeToByteArray()
    private const val MAX_OBJ_NUM = 50_000_000L
    private const val MAX_GEN = 65_535

    fun rebuild(reader: ByteReader): XrefAndTrailer {
        val bytes = reader.bytes
        val entries = HashMap<Long, XrefEntry>()
        val objStreamNums = LinkedHashSet<Long>()

        // Trailer fields recovered along the way. Later finds overwrite earlier
        // ones (file order ≈ revision order for incremental updates).
        var root: PdfObject? = null
        var encrypt: PdfObject? = null
        var id: PdfObject? = null
        var info: PdfObject? = null
        var lastCatalog: Long? = null

        // ── Pass 1: find every "N G obj" header. ───────────────────────────
        var search = 0
        while (true) {
            val kw = indexOfToken(bytes, OBJ, search)
            if (kw < 0) break
            search = kw + OBJ.size
            val header = parseHeaderBackwards(bytes, kw) ?: continue
            val (num, gen, headerStart) = header
            entries[num] = XrefEntry.InUse(num, gen, headerStart)

            // Peek the object body to recover trailer fields and note object streams.
            val obj = runCatching {
                val r = ByteReader(bytes)
                r.seek(headerStart)
                Parser(Lexer(r)).readIndirectObject().value
            }.getOrNull() ?: continue

            val dict = when (obj) {
                is PdfStream -> obj.dict
                is PdfDictionary -> obj
                else -> null
            } ?: continue

            when (dict.getName("Type")) {
                "Catalog" -> lastCatalog = num
                "ObjStm" -> objStreamNums.add(num)
                "XRef" -> {
                    dict["Root"]?.let { root = it }
                    dict["Encrypt"]?.let { encrypt = it }
                    dict["ID"]?.let { id = it }
                    dict["Info"]?.let { info = it }
                }
            }
        }

        // ── Pass 2: recover the trailer dict(s) for /Root etc. ──────────────
        // A classic "trailer" dictionary is the most authoritative source.
        var tsearch = 0
        while (true) {
            val t = indexOfToken(bytes, TRAILER, tsearch)
            if (t < 0) break
            tsearch = t + TRAILER.size
            val dict = runCatching {
                val r = ByteReader(bytes)
                r.seek(t + TRAILER.size)
                Parser(Lexer(r)).readObject() as? PdfDictionary
            }.getOrNull() ?: continue
            dict["Root"]?.let { root = it }
            dict["Encrypt"]?.let { encrypt = it }
            dict["ID"]?.let { id = it }
            dict["Info"]?.let { info = it }
        }

        // ── Pass 3: decode object streams to recover compressed members. ────
        for (osNum in objStreamNums) {
            val entry = entries[osNum] as? XrefEntry.InUse ?: continue
            val members = runCatching { objStreamMembers(bytes, entry.byteOffset) }.getOrNull() ?: continue
            for ((index, memberNum) in members) {
                // Direct in-file objects are more trustworthy than compressed copies.
                if (entries[memberNum] is XrefEntry.InUse) continue
                entries[memberNum] = XrefEntry.Compressed(memberNum, osNum, index)
            }
        }

        // ── Assemble the trailer. ───────────────────────────────────────────
        if (root == null) {
            val cat = lastCatalog ?: scanForCatalog(entries, bytes)
            if (cat != null) root = PdfReference(cat, 0)
        }
        if (root == null) {
            throw PdfFormatException("Repair failed: no /Root catalog found in file")
        }
        val trailerMap = LinkedHashMap<String, PdfObject>()
        trailerMap["Root"] = root
        encrypt?.let { trailerMap["Encrypt"] = it }
        id?.let { trailerMap["ID"] = it }
        info?.let { trailerMap["Info"] = it }
        return XrefAndTrailer(entries, PdfDictionary(trailerMap))
    }

    /** Decode an /ObjStm at [offset]; returns (indexInStream → objectNumber). */
    private fun objStreamMembers(bytes: ByteArray, offset: Int): List<Pair<Int, Long>> {
        val r = ByteReader(bytes)
        r.seek(offset)
        val stream = Parser(Lexer(r)).readIndirectObject().value as? PdfStream ?: return emptyList()
        val n = stream.dict.getInt("N")?.toInt() ?: return emptyList()
        val decoded = FilterChain.decode(stream)
        val header = Lexer(ByteReader(decoded))
        val out = ArrayList<Pair<Int, Long>>(n)
        for (i in 0 until n) {
            val objNum = (header.nextToken() as? Token.Integer)?.value ?: break
            (header.nextToken() as? Token.Integer) ?: break  // offset within stream (unused here)
            out.add(i to objNum)
        }
        return out
    }

    /** Last-ditch /Root: any in-use object whose body is a /Type /Catalog dict. */
    private fun scanForCatalog(entries: Map<Long, XrefEntry>, bytes: ByteArray): Long? {
        for ((num, e) in entries) {
            if (e !is XrefEntry.InUse) continue
            val dict = runCatching {
                val r = ByteReader(bytes); r.seek(e.byteOffset)
                (Parser(Lexer(r)).readIndirectObject().value as? PdfDictionary)
            }.getOrNull() ?: continue
            if (dict.getName("Type") == "Catalog") return num
        }
        return null
    }

    /**
     * Find the next occurrence of keyword [needle] that is bounded as a token:
     * preceded by whitespace (or start) and followed by whitespace/delimiter/EOF.
     * This rejects "obj" inside "endobj" and "trailer" inside arbitrary text.
     */
    private fun indexOfToken(bytes: ByteArray, needle: ByteArray, from: Int): Int {
        var i = from.coerceAtLeast(0)
        val last = bytes.size - needle.size
        outer@ while (i <= last) {
            for (k in needle.indices) {
                if (bytes[i + k] != needle[k]) { i++; continue@outer }
            }
            val before = if (i == 0) -1 else bytes[i - 1].toInt() and 0xFF
            val after = if (i + needle.size >= bytes.size) -1 else bytes[i + needle.size].toInt() and 0xFF
            val beforeOk = before == -1 || Lexer.isWhitespace(before)
            val afterOk = after == -1 || Lexer.isWhitespace(after) || Lexer.isDelimiter(after)
            if (beforeOk && afterOk) return i
            i++
        }
        return -1
    }

    /**
     * From the start of an "obj" keyword, scan backwards over "N G " to recover
     * the object and generation numbers. Returns (objNum, gen, headerStartOffset)
     * or null if the bytes before "obj" aren't a valid header.
     */
    private fun parseHeaderBackwards(bytes: ByteArray, objKwStart: Int): Triple<Long, Int, Int>? {
        var p = objKwStart - 1
        p = skipWsBack(bytes, p)
        val gen = readDigitsBack(bytes, p) ?: return null
        if (gen.value > MAX_GEN) return null
        p = gen.start - 1
        if (p < 0 || !Lexer.isWhitespace(bytes[p].toInt() and 0xFF)) return null
        p = skipWsBack(bytes, p)
        val num = readDigitsBack(bytes, p) ?: return null
        if (num.value < 0 || num.value > MAX_OBJ_NUM) return null
        // The char before the object number must be whitespace/delimiter/start.
        val before = num.start - 1
        if (before >= 0) {
            val c = bytes[before].toInt() and 0xFF
            if (!Lexer.isWhitespace(c) && !Lexer.isDelimiter(c)) return null
        }
        return Triple(num.value, gen.value.toInt(), num.start)
    }

    private data class Digits(val value: Long, val start: Int)

    private fun readDigitsBack(bytes: ByteArray, fromInclusive: Int): Digits? {
        var p = fromInclusive
        var end = p
        while (p >= 0 && (bytes[p].toInt() and 0xFF) in '0'.code..'9'.code) p--
        val start = p + 1
        if (start > end) return null
        var v = 0L
        for (i in start..end) {
            v = v * 10 + ((bytes[i].toInt() and 0xFF) - '0'.code)
            if (v < 0) return null  // overflow
        }
        return Digits(v, start)
    }

    private fun skipWsBack(bytes: ByteArray, fromInclusive: Int): Int {
        var p = fromInclusive
        while (p >= 0 && Lexer.isWhitespace(bytes[p].toInt() and 0xFF)) p--
        return p
    }
}
