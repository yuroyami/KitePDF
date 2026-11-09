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
 * Malformed-file recovery. ISO 32000-1 has no formal spec for this, so the
 * approach mirrors MuPDF's `pdf_repair_xref` in `pdf-repair.c`.
 *
 * When the cross-reference table is missing, corrupt, truncated, or points at
 * the wrong offsets, the only reliable structure left is the object bodies
 * themselves. This pass linearly scans the whole file for `N G obj` headers and
 * rebuilds the xref from their real byte offsets (latest occurrence wins, so an
 * incrementally-updated object's newest revision is chosen). Object streams are
 * decoded so their compressed members are recovered too, and the trailer's
 * `/Root` (plus `/Encrypt`, `/ID`, `/Info`) is recovered from any `trailer`
 * dictionary, `/Type /XRef` stream dict, or `/Type /Catalog` object found.
 * Every catalog it finds is kept, the likeliest first, because the one a trailer
 * names can be the broken one (#584).
 *
 * This is a best-effort, last-resort path: it never throws for a recoverable
 * file and degrades to whatever it can find.
 */
internal object PdfRepair {

    private val OBJ = "obj".encodeToByteArray()
    private val ENDSTREAM = "endstream".encodeToByteArray()

    /** Every offset where [needle] starts in the file of [r], in order, as a Long, since a source can pass 2 GiB (#906). */
    private fun keywordOffsets(r: ByteReader, needle: ByteArray): LongArray {
        val out = ArrayList<Long>()
        var from = 0L
        while (true) {
            val at = r.indexOf(needle, from)
            if (at < 0) break
            out.add(at)
            from = at + needle.size
        }
        return out.toLongArray()
    }
    private val TRAILER = "trailer".encodeToByteArray()
    private const val MAX_OBJ_NUM = 50_000_000L
    private const val MAX_GEN = 65_535

    /**
     * [decryptStream], when given, decrypts an object stream before its members are read (#793).
     * The scan reads [reader]'s file through forks of it, so a file opened from a source is read
     * a window at a time, and with [streamsOnDemand] its streams are not copied (#906).
     */
    fun rebuild(
        reader: ByteReader,
        streamsOnDemand: Boolean = false,
        decryptStream: ((Long, Int, PdfStream) -> PdfStream)? = null,
    ): RepairedFile {
        val bytes = reader.fork()
        val entries = HashMap<Long, XrefEntry>()
        val objStreamNums = LinkedHashSet<Long>()

        // Trailer fields recovered along the way. Later finds overwrite earlier
        // ones (file order ≈ revision order for incremental updates); every
        // /Root and every catalog is kept, in file order.
        val trailerRoots = ArrayList<PdfObject>()
        val xrefStreamRoots = ArrayList<PdfObject>()
        val catalogs = ArrayList<PdfObject>()
        var encrypt: PdfObject? = null
        var id: PdfObject? = null
        var info: PdfObject? = null

        // Every "endstream" of the file, found in one pass, so a stream whose /Length is missing or
        // wrong finds its end by a binary search instead of a scan of the rest of the file (#966).
        val endstreams = keywordOffsets(bytes, ENDSTREAM)
        val endstreamAfter = { from: Long ->
            var lo = 0
            var hi = endstreams.size
            while (lo < hi) { val mid = (lo + hi) ushr 1; if (endstreams[mid] < from) lo = mid + 1 else hi = mid }
            if (lo < endstreams.size) endstreams[lo] else -1L
        }

        // ── Pass 1: find every "N G obj" header. ───────────────────────────
        var search = 0L
        while (true) {
            val kw = indexOfToken(bytes, OBJ, search)
            if (kw < 0) break
            search = kw + OBJ.size
            val header = parseHeaderBackwards(bytes, kw) ?: continue
            val (num, gen, headerStart) = header

            // Parse the object body to recover trailer fields and note object streams. Only a copy
            // that parses is kept, as MuPDF's pdf_repair_xref keeps it: a later copy cut off by a
            // partial download replaced the complete one before it, and the page went blank (#811).
            val r = reader.fork()
            val obj = runCatching {
                r.seek(headerStart)
                Parser(Lexer(r), endstreamAfter = endstreamAfter, streamsOnDemand = streamsOnDemand).readIndirectObject().value
            }.getOrNull() ?: continue
            entries[num] = XrefEntry.InUse(num, gen, headerStart)
            // The scan resumes after a stream it parsed, as MuPDF's pdf_repair_xref does, so the
            // bytes of one stream are read once, however many objects follow it.
            if (obj is PdfStream) search = maxOf(search, r.posLong())

            val dict = when (obj) {
                is PdfStream -> obj.dict
                is PdfDictionary -> obj
                else -> null
            } ?: continue

            when (dict.getName("Type")) {
                "Catalog" -> catalogs.add(PdfReference(num, gen))
                "ObjStm" -> objStreamNums.add(num)
                "XRef" -> {
                    dict["Root"]?.let { xrefStreamRoots.add(it) }
                    dict["Encrypt"]?.let { encrypt = it }
                    dict["ID"]?.let { id = it }
                    dict["Info"]?.let { info = it }
                }
            }
        }

        // ── Pass 2: recover the trailer dict(s) for /Root etc. ──────────────
        // A classic "trailer" dictionary is the most authoritative source.
        var tsearch = 0L
        while (true) {
            val t = indexOfToken(bytes, TRAILER, tsearch)
            if (t < 0) break
            tsearch = t + TRAILER.size
            val dict = runCatching {
                val r = reader.fork()
                r.seek(t + TRAILER.size)
                Parser(Lexer(r)).readObject() as? PdfDictionary
            }.getOrNull() ?: continue
            dict["Root"]?.let { trailerRoots.add(it) }
            dict["Encrypt"]?.let { encrypt = it }
            dict["ID"]?.let { id = it }
            dict["Info"]?.let { info = it }
        }

        // ── Pass 3: decode object streams to recover compressed members. ────
        for (osNum in objStreamNums) {
            val entry = entries[osNum] as? XrefEntry.InUse ?: continue
            val members = runCatching { objStreamMembers(bytes, entry.offset, osNum, entry.generation, decryptStream) }.getOrNull() ?: continue
            for ((index, memberNum) in members) {
                // The copy later in the file is the newer revision, direct or compressed (ISO 32000-1,
                // 7.5.6 and 7.5.7), as MuPDF's pdf_repair_obj_stm decides. The direct copy always won,
                // so an update that moved a changed object into an object stream repaired to the old
                // version (#812).
                val direct = entries[memberNum] as? XrefEntry.InUse
                if (direct != null && direct.offset > entry.offset) continue
                entries[memberNum] = XrefEntry.Compressed(memberNum, osNum, index)
            }
        }

        // ── Assemble the trailer. ───────────────────────────────────────────
        // A classic trailer is the most authoritative, then an xref stream, then a
        // catalog that nothing names; the newest of each comes first.
        val roots = LinkedHashSet<PdfObject>().apply {
            addAll(trailerRoots.asReversed())
            addAll(xrefStreamRoots.asReversed())
            addAll(catalogs.asReversed())
        }.toList()
        if (roots.isEmpty()) {
            throw PdfFormatException("Repair failed: no /Root catalog found in file")
        }
        val trailerMap = LinkedHashMap<String, PdfObject>()
        trailerMap["Root"] = roots.first()
        encrypt?.let { trailerMap["Encrypt"] = it }
        id?.let { trailerMap["ID"] = it }
        info?.let { trailerMap["Info"] = it }
        return RepairedFile(entries, PdfDictionary(trailerMap), roots)
    }

    /** Decode an /ObjStm at [offset]; returns (indexInStream → objectNumber). */
    private fun objStreamMembers(
        bytes: ByteReader,
        offset: Long,
        num: Long,
        gen: Int,
        decryptStream: ((Long, Int, PdfStream) -> PdfStream)?,
    ): List<Pair<Int, Long>> {
        val r = bytes.fork()
        r.seek(offset)
        val raw = Parser(Lexer(r)).readIndirectObject().value as? PdfStream ?: return emptyList()
        val stream = decryptStream?.invoke(num, gen, raw) ?: raw
        val n = stream.dict.getInt("N")?.toInt() ?: return emptyList()
        val decoded = FilterChain.decode(stream)
        // A header pair takes at least four bytes, so /N past that only sized the list (#968).
        if (n < 0) return emptyList()
        val header = Lexer(ByteReader(decoded))
        val out = ArrayList<Pair<Int, Long>>(minOf(n, decoded.size / 4))
        for (i in 0 until n) {
            val objNum = (header.nextToken() as? Token.Integer)?.value ?: break
            (header.nextToken() as? Token.Integer) ?: break  // offset within stream (unused here)
            out.add(i to objNum)
        }
        return out
    }

    /**
     * Find the next occurrence of keyword [needle] that is bounded as a token:
     * preceded by whitespace (or start) and followed by whitespace/delimiter/EOF.
     * This rejects "obj" inside "endobj" and "trailer" inside arbitrary text.
     */
    private fun indexOfToken(bytes: ByteReader, needle: ByteArray, from: Long): Long {
        var i = from.coerceAtLeast(0)
        while (true) {
            i = bytes.indexOf(needle, i)
            if (i < 0) return -1
            val before = bytes.byteAt(i - 1)
            val after = bytes.byteAt(i + needle.size)
            val beforeOk = before == -1 || Lexer.isWhitespace(before)
            val afterOk = after == -1 || Lexer.isWhitespace(after) || Lexer.isDelimiter(after)
            if (beforeOk && afterOk) return i
            i++
        }
    }

    /**
     * From the start of an "obj" keyword, scan backwards over "N G " to recover
     * the object and generation numbers. Returns (objNum, gen, headerStartOffset)
     * or null if the bytes before "obj" aren't a valid header.
     */
    private fun parseHeaderBackwards(bytes: ByteReader, objKwStart: Long): Triple<Long, Int, Long>? {
        var p = objKwStart - 1
        p = skipWsBack(bytes, p)
        val gen = readDigitsBack(bytes, p) ?: return null
        if (gen.value > MAX_GEN) return null
        p = gen.start - 1
        if (p < 0 || !Lexer.isWhitespace(bytes.byteAt(p))) return null
        p = skipWsBack(bytes, p)
        val num = readDigitsBack(bytes, p) ?: return null
        if (num.value < 0 || num.value > MAX_OBJ_NUM) return null
        // The char before the object number must be whitespace/delimiter/start.
        val before = num.start - 1
        if (before >= 0) {
            val c = bytes.byteAt(before)
            if (!Lexer.isWhitespace(c) && !Lexer.isDelimiter(c)) return null
        }
        return Triple(num.value, gen.value.toInt(), num.start)
    }

    private data class Digits(val value: Long, val start: Long)

    private fun readDigitsBack(bytes: ByteReader, fromInclusive: Long): Digits? {
        var p = fromInclusive
        var end = p
        while (p >= 0 && bytes.byteAt(p) in '0'.code..'9'.code) p--
        val start = p + 1
        if (start > end) return null
        var v = 0L
        for (i in start..end) {
            v = v * 10 + (bytes.byteAt(i) - '0'.code)
            if (v < 0) return null  // overflow
        }
        return Digits(v, start)
    }

    private fun skipWsBack(bytes: ByteReader, fromInclusive: Long): Long {
        var p = fromInclusive
        while (p >= 0 && Lexer.isWhitespace(bytes.byteAt(p))) p--
        return p
    }
}

/**
 * What [PdfRepair.rebuild] recovered: the entries, a trailer that names the first of [roots],
 * and every catalog the file names or holds, the likeliest first.
 */
internal class RepairedFile(
    val entries: Map<Long, XrefEntry>,
    val trailer: PdfDictionary,
    val roots: List<PdfObject>,
) {
    /** [trailer] with [root] as its catalog. */
    fun trailerWith(root: PdfObject): PdfDictionary = PdfDictionary(trailer.map + ("Root" to root))
}
