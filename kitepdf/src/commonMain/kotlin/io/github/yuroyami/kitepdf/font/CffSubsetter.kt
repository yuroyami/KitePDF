package io.github.yuroyami.kitepdf.font

import io.github.yuroyami.kitepdf.core.ByteArrayBuilder

/**
 * Subsets a CFF font program (the outlines inside an OpenType `.otf`) down to the
 * glyphs a document uses, and re-emits it as a **CID-keyed** CFF.
 *
 * The output is always CID-keyed with a charset that maps each new glyph id to
 * its *original* glyph id (used as the CID). Under a PDF Type0 / CIDFontType0 /
 * Identity-H font that means the content stream can keep emitting the original
 * glyph id as the 2-byte code — identical to the TrueType path — and the reader
 * resolves code → CID(=original gid) → new gid via this charset. So `/W` and
 * `/ToUnicode` stay keyed by the original gid too.
 *
 * Only the CharStrings INDEX is subset; all global and local subroutines are
 * kept verbatim (their biased references stay valid), as do all FontDicts. For a
 * CJK CFF the charstrings dominate, so this is the bulk of the size win without
 * the complexity (and risk) of renumbering subrs. The deprecated `seac`-via-
 * `endchar` accent composition is not followed, so a glyph built that way would
 * lose its components — CID-keyed fonts don't use it.
 */
internal object CffSubsetter {

    class Subset(val cff: ByteArray, val oldToNew: Map<Int, Int>)

    fun subset(cff: CffFont, usedGids: Set<Int>): Subset {
        val numGlyphsOrig = cff.numGlyphs

        // Closure = used glyphs + .notdef, renumbered densely in ascending order.
        val keep = HashSet<Int>()
        keep.add(0)
        for (g in usedGids) if (g in 0 until numGlyphsOrig) keep.add(g)
        val oldGids = keep.toIntArray()
        oldGids.sort()
        val n = oldGids.size
        val oldToNew = HashMap<Int, Int>(n * 2)
        for (i in oldGids.indices) oldToNew[oldGids[i]] = i

        // FontDicts: keep all (so original FD indices and their local subrs stay valid).
        val numFds = maxOf(1, cff.localSubrsPerFd.size)
        fun fdOf(oldGid: Int): Int = if (cff.fdSelect.isNotEmpty()) cff.fdSelect[oldGid] else 0

        // ── Fixed sections (independent of absolute offsets) ──────────────────
        val nameIndex = writeIndex(listOf("KiteSubset".encodeToByteArray()))
        val stringIndex = writeIndex(listOf("Adobe".encodeToByteArray(), "Identity".encodeToByteArray()))
        val gsubrIndex = writeIndex(cff.globalSubrs)
        val charStringsIndex = writeIndex(List(n) { cff.charStrings.getOrElse(oldGids[it]) { ByteArray(0) } })

        // Charset format 0: CID for new gid 1..n-1 is the original gid.
        val charset = ByteArrayBuilder(1 + 2 * n).apply {
            append(0)
            for (i in 1 until n) appendU16BE(oldGids[i])
        }.toByteArray()

        val fdselect = buildFdSelect(n) { fdOf(oldGids[it]) }

        // Private DICT + Local Subr INDEX per FontDict.
        val privBytes = ArrayList<ByteArray>(numFds)
        val localSubrBytes = ArrayList<ByteArray>(numFds)
        for (fd in 0 until numFds) {
            val locals = cff.localSubrsPerFd.getOrElse(fd) { emptyList() }
            val p = cff.fdPrivates.getOrElse(fd) { CffFont.FdPrivate(0.0, 0.0) }
            val widths = ByteArrayBuilder(16).apply {
                if (p.defaultWidthX != 0.0) { append(dictInt(p.defaultWidthX.toInt())); append(op(20)) }
                if (p.nominalWidthX != 0.0) { append(dictInt(p.nominalWidthX.toInt())); append(op(21)) }
            }.toByteArray()
            if (locals.isEmpty()) {
                privBytes.add(widths)
                localSubrBytes.add(ByteArray(0))
            } else {
                // Subrs offset is relative to the Private DICT start; local subrs follow it.
                val privSize = widths.size + 6   // + dictInt5(offset)=5 + op(19)=1
                privBytes.add(ByteArrayBuilder(privSize).apply {
                    append(widths); append(dictInt5(privSize)); append(op(19))
                }.toByteArray())
                localSubrBytes.add(writeIndex(locals))
            }
        }

        // FontDict INDEX: each FontDict carries only its Private (size, offset). Fixed-size.
        fun fontDict(privSize: Int, privOffset: Int): ByteArray =
            ByteArrayBuilder(12).apply { append(dictInt5(privSize)); append(dictInt5(privOffset)); append(op(18)) }.toByteArray()

        val maxCid = oldGids[n - 1]
        // Measure the Top DICT (fixed size: all offsets are 5-byte) to lay everything out.
        fun topDict(charsetOff: Int, charStringsOff: Int, fdArrayOff: Int, fdSelectOff: Int): ByteArray =
            ByteArrayBuilder(48).apply {
                append(dictInt(391)); append(dictInt(392)); append(dictInt(0)); append(op(0x0C1E)) // ROS
                append(dictInt(maxCid + 1)); append(op(0x0C22))                                     // CIDCount
                append(dictInt5(charsetOff)); append(op(15))                                        // charset
                append(dictInt5(charStringsOff)); append(op(17))                                    // CharStrings
                append(dictInt5(fdArrayOff)); append(op(0x0C24))                                    // FDArray
                append(dictInt5(fdSelectOff)); append(op(0x0C25))                                   // FDSelect
            }.toByteArray()

        val topDictLen = topDict(0, 0, 0, 0).size
        val topDictIndexLen = writeIndex(listOf(ByteArray(topDictLen))).size

        // ── Layout: compute absolute offsets ─────────────────────────────────
        var pos = 4 // header
        pos += nameIndex.size
        pos += topDictIndexLen
        pos += stringIndex.size
        pos += gsubrIndex.size
        val charStringsOffset = pos; pos += charStringsIndex.size
        val charsetOffset = pos; pos += charset.size
        val fdSelectOffset = pos; pos += fdselect.size
        val fdArrayOffset = pos
        val fontDictsSized = (0 until numFds).map { fontDict(privBytes[it].size, 0) }
        pos += writeIndex(fontDictsSized).size
        val privateOffsets = IntArray(numFds)
        for (fd in 0 until numFds) {
            privateOffsets[fd] = pos
            pos += privBytes[fd].size
            pos += localSubrBytes[fd].size
        }

        // ── Assemble with the real offsets ───────────────────────────────────
        val topDictIndex = writeIndex(listOf(topDict(charsetOffset, charStringsOffset, fdArrayOffset, fdSelectOffset)))
        val fdArrayIndex = writeIndex((0 until numFds).map { fontDict(privBytes[it].size, privateOffsets[it]) })

        val out = ByteArrayBuilder(pos)
        out.append(byteArrayOf(1, 0, 4, 4))            // header: major=1 minor=0 hdrSize=4 offSize=4
        out.append(nameIndex)
        out.append(topDictIndex)
        out.append(stringIndex)
        out.append(gsubrIndex)
        out.append(charStringsIndex)
        out.append(charset)
        out.append(fdselect)
        out.append(fdArrayIndex)
        for (fd in 0 until numFds) {
            out.append(privBytes[fd])
            out.append(localSubrBytes[fd])
        }
        return Subset(out.toByteArray(), oldToNew)
    }

    /** FDSelect format 3: ranges of new-gid → FontDict index. */
    private fun buildFdSelect(n: Int, fdOf: (Int) -> Int): ByteArray {
        val ranges = ArrayList<Pair<Int, Int>>()  // (firstGid, fd)
        var prev = -1
        for (g in 0 until n) {
            val fd = fdOf(g)
            if (fd != prev) { ranges.add(g to fd); prev = fd }
        }
        return ByteArrayBuilder(3 + 3 * ranges.size + 2).apply {
            append(3)                       // format
            appendU16BE(ranges.size)
            for ((first, fd) in ranges) { appendU16BE(first); append(fd.toByte()) }
            appendU16BE(n)                  // sentinel
        }.toByteArray()
    }

    /* ─── CFF primitives (Adobe Tech Note 5176) ──────────────────────────── */

    /** A CFF INDEX: count(u16), offSize(u8), offsets, data. Empty → just a 0 count. */
    private fun writeIndex(items: List<ByteArray>): ByteArray {
        if (items.isEmpty()) return byteArrayOf(0, 0)
        val dataSize = items.sumOf { it.size }
        val offSize = when {
            dataSize + 1 < 0x100 -> 1
            dataSize + 1 < 0x10000 -> 2
            dataSize + 1 < 0x1000000 -> 3
            else -> 4
        }
        val out = ByteArrayBuilder(3 + (items.size + 1) * offSize + dataSize)
        out.appendU16BE(items.size)
        out.append(offSize.toByte())
        var off = 1
        writeOffset(out, off, offSize)
        for (it in items) { off += it.size; writeOffset(out, off, offSize) }
        for (it in items) out.append(it)
        return out.toByteArray()
    }

    private fun writeOffset(out: ByteArrayBuilder, value: Int, size: Int) {
        for (i in size - 1 downTo 0) out.append((value ushr (8 * i)).toByte())
    }

    /** A two-byte DICT operator (12 xx) or one-byte operator. */
    private fun op(o: Int): ByteArray =
        if (o >= 0x0C00) byteArrayOf(12, (o and 0xFF).toByte()) else byteArrayOf(o.toByte())

    /** Variable-length DICT integer operand encoding. */
    private fun dictInt(v: Int): ByteArray = when {
        v in -107..107 -> byteArrayOf((v + 139).toByte())
        v in 108..1131 -> { val w = v - 108; byteArrayOf((247 + (w ushr 8)).toByte(), (w and 0xFF).toByte()) }
        v in -1131..-108 -> { val w = -v - 108; byteArrayOf((251 + (w ushr 8)).toByte(), (w and 0xFF).toByte()) }
        v in -32768..32767 -> byteArrayOf(28, (v ushr 8).toByte(), v.toByte())
        else -> dictInt5(v)
    }

    /** Fixed 5-byte DICT integer (29 + i32) — keeps DICT sizes stable for offset backpatching. */
    private fun dictInt5(v: Int): ByteArray =
        byteArrayOf(29, (v ushr 24).toByte(), (v ushr 16).toByte(), (v ushr 8).toByte(), v.toByte())
}
