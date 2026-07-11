package io.github.yuroyami.kitepdf.epub

import io.github.yuroyami.kitepdf.epub.brotli.Brotli
import io.github.yuroyami.kitepdf.core.filters.FilterChain

/**
 * WOFF2 (W3C Web Open Font Format 2.0) → bare SFNT decoder. A WOFF2 file is
 * an SFNT whose tables are concatenated into a single Brotli stream, with the
 * `glyf`/`loca` pair optionally re-coded into the spec's compact transformed
 * form (per-glyph substreams + triplet-encoded points) and `hmtx` optionally
 * stripped of redundant left side bearings. This reverses all of it:
 * Brotli-decompresses the data block, reconstructs transformed `glyf`
 * (rebuilding `loca` from the actual glyph offsets) and transformed `hmtx`
 * (lsb = glyph xMin), and reassembles a plain SFNT that the core
 * [io.github.yuroyami.kitepdf.core.font.TrueTypeFont] parser reads unchanged.
 *
 * Scope: single fonts only; WOFF2 collections (`ttcf` flavor) return null.
 */
internal object Woff2 {

    /** WOFF2 signature `wOF2`. */
    fun isWoff2(b: ByteArray): Boolean =
        b.size >= 4 && b[0].toInt() == 'w'.code && b[1].toInt() == 'O'.code &&
            b[2].toInt() == 'F'.code && b[3].toInt() == '2'.code

    /** Known-table tags, indexed by the 6-bit table-directory flag (63 = arbitrary tag follows). */
    private val KNOWN_TAGS = listOf(
        "cmap", "head", "hhea", "hmtx", "maxp", "name", "OS/2", "post", "cvt ",
        "fpgm", "glyf", "loca", "prep", "CFF ", "VORG", "EBDT", "EBLC", "gasp",
        "hdmx", "kern", "LTSH", "PCLT", "VDMX", "vhea", "vmtx", "BASE", "GDEF",
        "GPOS", "GSUB", "EBSC", "JSTF", "MATH", "CBDT", "CBLC", "COLR", "CPAL",
        "SVG ", "sbix", "acnt", "avar", "bdat", "bloc", "bsln", "cvar", "fdsc",
        "feat", "fmtx", "fvar", "gvar", "hsty", "just", "lcar", "mort", "morx",
        "opbd", "prop", "trak", "Zapf", "Silf", "Glat", "Gloc", "Feat", "Sill",
    )

    private class Entry(
        val tag: String,
        val origLength: Int,
        val transformVersion: Int,
        val transformed: Boolean,
        val srcOffset: Int,
        val srcLength: Int,
    ) {
        var data: ByteArray? = null
    }

    /** Decompress a WOFF2 file to an SFNT blob, or null if malformed / a collection. */
    fun toSfnt(b: ByteArray): ByteArray? = runCatching { convert(b) }.getOrNull()

    private fun convert(b: ByteArray): ByteArray? {
        if (!isWoff2(b) || b.size < 48) return null
        val flavor = u32(b, 4)
        if (flavor == 0x74746366L) return null // 'ttcf' collection: unsupported
        val numTables = u16(b, 12)
        if (numTables == 0) return null
        val totalCompressedSize = u32(b, 20).toInt()

        // Table directory.
        val rd = Reader(b, 48)
        val entries = ArrayList<Entry>(numTables)
        var srcOffset = 0
        repeat(numTables) {
            val flags = rd.u8()
            val tagIdx = flags and 0x3F
            val version = (flags ushr 6) and 3
            val tag = if (tagIdx == 0x3F) rd.tag() else KNOWN_TAGS[tagIdx]
            val origLength = rd.base128()
            // For glyf/loca, transform version 0 means TRANSFORMED (3 = null
            // transform); for every other table 0 is the null transform.
            val transformed = if (tag == "glyf" || tag == "loca") version != 3 else version != 0
            val srcLength = when {
                !transformed -> origLength
                else -> rd.base128() // transformLength (0 for transformed loca)
            }
            entries.add(Entry(tag, origLength, version, transformed, srcOffset, srcLength))
            srcOffset += srcLength
        }

        // Single Brotli stream holding every table's (possibly transformed) data.
        val compressed = b.copyOfRange(rd.pos, minOf(b.size, rd.pos + totalCompressedSize))
        val raw = Brotli.decode(compressed, maxOutputBytes = FilterChain.MAX_DECODED_STREAM)
        if (raw.size < srcOffset) return null

        // Reconstruct glyf/loca first (hmtx may need its xMins).
        val glyf = entries.firstOrNull { it.tag == "glyf" }
        val loca = entries.firstOrNull { it.tag == "loca" }
        var xMins: IntArray? = null
        var numGlyphs = 0
        if (glyf != null && loca != null && glyf.transformed) {
            if (!loca.transformed || loca.srcLength != 0) return null
            val g = reconstructGlyf(raw, glyf.srcOffset, glyf.srcLength) ?: return null
            glyf.data = g.glyf
            loca.data = g.loca
            xMins = g.xMins
            numGlyphs = g.numGlyphs
        } else if ((glyf?.transformed == true) != (loca?.transformed == true)) {
            return null // must transform (or not) as a pair
        }
        for (e in entries) {
            if (e.data != null) continue
            if (e.tag == "hmtx" && e.transformed) continue // below, needs hhea/maxp
            if (e.transformed) return null // no other transforms defined
            if (e.srcOffset + e.srcLength > raw.size) return null
            e.data = raw.copyOfRange(e.srcOffset, e.srcOffset + e.srcLength)
        }
        val hmtx = entries.firstOrNull { it.tag == "hmtx" }
        if (hmtx != null && hmtx.transformed) {
            val hhea = entries.firstOrNull { it.tag == "hhea" }?.data ?: return null
            if (numGlyphs == 0) {
                val maxp = entries.firstOrNull { it.tag == "maxp" }?.data ?: return null
                numGlyphs = u16(maxp, 4)
            }
            if (hhea.size < 36) return null
            val numHMetrics = u16(hhea, 34)
            hmtx.data = reconstructHmtx(
                raw, hmtx.srcOffset, hmtx.srcLength, numGlyphs, numHMetrics,
                xMins ?: IntArray(numGlyphs),
            ) ?: return null
        }

        return assembleSfnt(flavor, entries)
    }

    /* ─── Transformed glyf reconstruction (WOFF2 spec section 5.1) ───────── */

    private class GlyfResult(val glyf: ByteArray, val loca: ByteArray, val xMins: IntArray, val numGlyphs: Int)

    private fun reconstructGlyf(raw: ByteArray, off: Int, len: Int): GlyfResult? {
        if (off + len > raw.size || len < 36) return null
        val hdr = Reader(raw, off)
        hdr.u16() // reserved
        val optionFlags = hdr.u16()
        val hasOverlapBitmap = (optionFlags and 1) != 0
        val numGlyphs = hdr.u16()
        val indexFormat = hdr.u16()
        val sizes = IntArray(7) { hdr.u32().toInt() }
        var p = hdr.pos
        val streams = Array(7) { i ->
            if (p + sizes[i] > off + len) return null
            val r = Reader(raw, p)
            p += sizes[i]
            r
        }
        val nContourS = streams[0]
        val nPointsS = streams[1]
        val flagS = streams[2]
        val glyphS = streams[3]
        val compositeS = streams[4]
        val bboxS = streams[5]
        val instrS = streams[6]
        val streamEnd = IntArray(7).also { var q = hdr.pos; for (i in 0..6) { q += sizes[i]; it[i] = q } }

        val overlapBitmapAt = p
        if (hasOverlapBitmap && p + ((numGlyphs + 7) shr 3) > off + len) return null

        val bboxBitmapAt = bboxS.pos
        val bboxBitmapLen = ((numGlyphs + 31) shr 5) shl 2
        bboxS.pos += bboxBitmapLen
        if (bboxS.pos > streamEnd[5]) return null

        fun hasBbox(i: Int) = (raw[bboxBitmapAt + (i shr 3)].toInt() and (0x80 ushr (i and 7))) != 0
        fun hasOverlap(i: Int) = hasOverlapBitmap &&
            (raw[overlapBitmapAt + (i shr 3)].toInt() and (0x80 ushr (i and 7))) != 0

        val glyfOut = ByteBuilder()
        val locaValues = IntArray(numGlyphs + 1)
        val xMins = IntArray(numGlyphs)

        for (i in 0 until numGlyphs) {
            locaValues[i] = glyfOut.size
            val nContours = nContourS.u16()
            if (nContours == 0xFFFF) {
                // Composite glyph: explicit bbox + verbatim component records.
                if (!hasBbox(i)) return null
                val start = compositeS.pos
                var more = true
                var haveInstr = false
                while (more) {
                    val flags = compositeS.u16()
                    compositeS.pos += 2 // glyph index
                    haveInstr = haveInstr || (flags and 0x0100) != 0 // WE_HAVE_INSTRUCTIONS
                    compositeS.pos += if ((flags and 0x0001) != 0) 4 else 2 // args
                    compositeS.pos += when {
                        (flags and 0x0008) != 0 -> 2 // WE_HAVE_A_SCALE
                        (flags and 0x0040) != 0 -> 4 // X_AND_Y_SCALE
                        (flags and 0x0080) != 0 -> 8 // TWO_BY_TWO
                        else -> 0
                    }
                    more = (flags and 0x0020) != 0 // MORE_COMPONENTS
                    if (compositeS.pos > streamEnd[4]) return null
                }
                glyfOut.u16(0xFFFF)
                glyfOut.bytes(raw, bboxS.pos, 8)
                bboxS.pos += 8
                xMins[i] = s16(raw, bboxS.pos - 8 + 0)
                glyfOut.bytes(raw, start, compositeS.pos - start)
                if (haveInstr) {
                    val instrLen = glyphS.u255()
                    glyfOut.u16(instrLen)
                    glyfOut.bytes(raw, instrS.pos, instrLen)
                    instrS.pos += instrLen
                    if (instrS.pos > streamEnd[6]) return null
                }
            } else if (nContours > 0) {
                // Simple glyph: 255UShort contour sizes + flag/triplet point streams.
                val endPts = IntArray(nContours)
                var total = 0
                for (c in 0 until nContours) {
                    total += nPointsS.u255()
                    endPts[c] = total - 1
                }
                if (total > 65535) return null
                val xs = IntArray(total)
                val ys = IntArray(total)
                val onCurve = BooleanArray(total)
                var x = 0
                var y = 0
                for (pt in 0 until total) {
                    var flag = raw[flagS.pos++].toInt() and 0xFF
                    onCurve[pt] = (flag ushr 7) == 0
                    flag = flag and 0x7F
                    val t = glyphS.pos
                    var dx: Int
                    var dy: Int
                    when {
                        flag < 10 -> {
                            dx = 0
                            dy = withSign(flag, ((flag and 14) shl 7) + (raw[t].toInt() and 0xFF))
                            glyphS.pos += 1
                        }
                        flag < 20 -> {
                            dx = withSign(flag, (((flag - 10) and 14) shl 7) + (raw[t].toInt() and 0xFF))
                            dy = 0
                            glyphS.pos += 1
                        }
                        flag < 84 -> {
                            val b0 = flag - 20
                            val b1 = raw[t].toInt() and 0xFF
                            dx = withSign(flag, 1 + (b0 and 0x30) + (b1 ushr 4))
                            dy = withSign(flag shr 1, 1 + ((b0 and 0x0C) shl 2) + (b1 and 0x0F))
                            glyphS.pos += 1
                        }
                        flag < 120 -> {
                            val b0 = flag - 84
                            dx = withSign(flag, 1 + ((b0 / 12) shl 8) + (raw[t].toInt() and 0xFF))
                            dy = withSign(flag shr 1, 1 + (((b0 % 12) shr 2) shl 8) + (raw[t + 1].toInt() and 0xFF))
                            glyphS.pos += 2
                        }
                        flag < 124 -> {
                            val b2 = raw[t + 1].toInt() and 0xFF
                            dx = withSign(flag, ((raw[t].toInt() and 0xFF) shl 4) + (b2 ushr 4))
                            dy = withSign(flag shr 1, ((b2 and 0x0F) shl 8) + (raw[t + 2].toInt() and 0xFF))
                            glyphS.pos += 3
                        }
                        else -> {
                            dx = withSign(flag, ((raw[t].toInt() and 0xFF) shl 8) + (raw[t + 1].toInt() and 0xFF))
                            dy = withSign(flag shr 1, ((raw[t + 2].toInt() and 0xFF) shl 8) + (raw[t + 3].toInt() and 0xFF))
                            glyphS.pos += 4
                        }
                    }
                    if (glyphS.pos > streamEnd[3] || flagS.pos > streamEnd[2]) return null
                    x += dx
                    y += dy
                    xs[pt] = x
                    ys[pt] = y
                }
                val instrLen = glyphS.u255()

                glyfOut.u16(nContours)
                if (hasBbox(i)) {
                    glyfOut.bytes(raw, bboxS.pos, 8)
                    xMins[i] = s16(raw, bboxS.pos)
                    bboxS.pos += 8
                    if (bboxS.pos > streamEnd[5]) return null
                } else {
                    var minX = xs[0]; var minY = ys[0]; var maxX = xs[0]; var maxY = ys[0]
                    for (pt in 1 until total) {
                        if (xs[pt] < minX) minX = xs[pt]
                        if (xs[pt] > maxX) maxX = xs[pt]
                        if (ys[pt] < minY) minY = ys[pt]
                        if (ys[pt] > maxY) maxY = ys[pt]
                    }
                    glyfOut.u16(minX); glyfOut.u16(minY); glyfOut.u16(maxX); glyfOut.u16(maxY)
                    xMins[i] = minX
                }
                for (e in endPts) glyfOut.u16(e)
                glyfOut.u16(instrLen)
                glyfOut.bytes(raw, instrS.pos, instrLen)
                instrS.pos += instrLen
                if (instrS.pos > streamEnd[6]) return null
                // Point data, one flag byte per point (no repeat compression).
                var lx = 0
                var ly = 0
                for (pt in 0 until total) {
                    val dx = xs[pt] - lx
                    val dy = ys[pt] - ly
                    var f = if (onCurve[pt]) 1 else 0
                    if (pt == 0 && hasOverlap(i)) f = f or 0x40
                    if (dx == 0) f = f or 16 else if (dx in -255..255) f = f or 2 or (if (dx > 0) 16 else 0)
                    if (dy == 0) f = f or 32 else if (dy in -255..255) f = f or 4 or (if (dy > 0) 32 else 0)
                    glyfOut.u8(f)
                    lx = xs[pt]
                    ly = ys[pt]
                }
                lx = 0; ly = 0
                for (pt in 0 until total) {
                    val dx = xs[pt] - lx
                    if (dx != 0) {
                        if (dx in -255..255) glyfOut.u8(if (dx < 0) -dx else dx) else glyfOut.u16(dx)
                    }
                    lx = xs[pt]
                }
                for (pt in 0 until total) {
                    val dy = ys[pt] - ly
                    if (dy != 0) {
                        if (dy in -255..255) glyfOut.u8(if (dy < 0) -dy else dy) else glyfOut.u16(dy)
                    }
                    ly = ys[pt]
                }
            } else {
                // Empty glyph: no data, and the spec forbids an explicit bbox.
                if (hasBbox(i)) return null
            }
            while (glyfOut.size and 3 != 0) glyfOut.u8(0)
        }
        locaValues[numGlyphs] = glyfOut.size

        val locaOut = ByteBuilder()
        for (v in locaValues) {
            if (indexFormat != 0) locaOut.u32(v.toLong()) else locaOut.u16(v ushr 1)
        }
        return GlyfResult(glyfOut.toByteArray(), locaOut.toByteArray(), xMins, numGlyphs)
    }

    /* ─── Transformed hmtx reconstruction (WOFF2 spec section 5.4) ───────── */

    private fun reconstructHmtx(
        raw: ByteArray, off: Int, len: Int, numGlyphs: Int, numHMetrics: Int, xMins: IntArray,
    ): ByteArray? {
        if (off + len > raw.size || len < 1) return null
        if (numHMetrics < 1 || numHMetrics > numGlyphs || xMins.size < numGlyphs) return null
        val rd = Reader(raw, off)
        val flags = rd.u8()
        if (flags and 0xFC != 0) return null
        val hasProportionalLsbs = flags and 1 == 0
        val hasMonospaceLsbs = flags and 2 == 0
        if (hasProportionalLsbs && hasMonospaceLsbs) return null // nothing was transformed

        val advances = IntArray(numHMetrics) { rd.u16() }
        val lsbs = IntArray(numGlyphs)
        for (i in 0 until numHMetrics) {
            lsbs[i] = if (hasProportionalLsbs) rd.u16() else xMins[i] and 0xFFFF
        }
        for (i in numHMetrics until numGlyphs) {
            lsbs[i] = if (hasMonospaceLsbs) rd.u16() else xMins[i] and 0xFFFF
        }
        if (rd.pos > off + len) return null
        val out = ByteBuilder()
        for (i in 0 until numGlyphs) {
            if (i < numHMetrics) out.u16(advances[i])
            out.u16(lsbs[i])
        }
        return out.toByteArray()
    }

    /* ─── SFNT assembly ──────────────────────────────────────────────────── */

    private fun assembleSfnt(flavor: Long, entries: List<Entry>): ByteArray? {
        val tables = entries.mapNotNull { e -> e.data?.let { e.tag to it } }
            .sortedBy { it.first }
        if (tables.size != entries.size) return null
        val n = tables.size
        var maxPow2 = 1
        var entrySelector = 0
        while (maxPow2 * 2 <= n) {
            maxPow2 *= 2
            entrySelector++
        }
        val dataStart = 12 + n * 16
        var pos = dataStart
        val offsets = IntArray(n)
        for (i in tables.indices) {
            offsets[i] = pos
            pos += (tables[i].second.size + 3) and 3.inv()
        }
        val out = ByteArray(pos)
        putU32(out, 0, flavor)
        putU16(out, 4, n)
        putU16(out, 6, maxPow2 * 16)
        putU16(out, 8, entrySelector)
        putU16(out, 10, n * 16 - maxPow2 * 16)
        var headOffset = -1
        for (i in tables.indices) {
            val (tag, data) = tables[i]
            if (tag == "head" && data.size >= 12) {
                // Zero checkSumAdjustment before summing; restored below.
                putU32(data, 8, 0)
                headOffset = offsets[i]
            }
            data.copyInto(out, offsets[i])
            val d = 12 + i * 16
            putU32(out, d, tagValue(tag))
            putU32(out, d + 4, checksum(out, offsets[i], (data.size + 3) and 3.inv()))
            putU32(out, d + 8, offsets[i].toLong())
            putU32(out, d + 12, data.size.toLong())
        }
        if (headOffset >= 0) {
            val total = checksum(out, 0, out.size)
            putU32(out, headOffset + 8, (0xB1B0AFBAL - total) and 0xFFFFFFFFL)
        }
        return out
    }

    private fun checksum(b: ByteArray, off: Int, len: Int): Long {
        var sum = 0L
        var i = off
        val end = off + len
        while (i + 3 < end) {
            sum = (sum + u32(b, i)) and 0xFFFFFFFFL
            i += 4
        }
        return sum
    }

    private fun tagValue(tag: String): Long {
        var v = 0L
        for (c in tag) v = (v shl 8) or c.code.toLong()
        return v
    }

    private fun withSign(flag: Int, base: Int): Int = if (flag and 1 != 0) base else -base

    /* ─── Readers / builders ─────────────────────────────────────────────── */

    private class Reader(val b: ByteArray, var pos: Int) {
        fun u8(): Int = b[pos++].toInt() and 0xFF
        fun u16(): Int = (u8() shl 8) or u8()
        fun u32(): Long = ((u16().toLong()) shl 16) or u16().toLong()
        fun tag(): String = buildString { repeat(4) { append(u8().toChar()) } }

        /** UIntBase128: 1..5 bytes, 7 bits each, no leading zero byte. */
        fun base128(): Int {
            var acc = 0L
            for (i in 0 until 5) {
                val byte = u8()
                if (i == 0 && byte == 0x80) error("woff2: leading zero in base128")
                acc = (acc shl 7) or (byte and 0x7F).toLong()
                if (acc > 0xFFFFFFFFL) error("woff2: base128 overflow")
                if (byte and 0x80 == 0) return acc.toInt()
            }
            error("woff2: base128 too long")
        }

        /** 255UInt16 (MicroType Express): 253 = word, 254/255 = one more byte. */
        fun u255(): Int = when (val code = u8()) {
            253 -> u16()
            255 -> u8() + 253
            254 -> u8() + 506
            else -> code
        }
    }

    private class ByteBuilder {
        var buf = ByteArray(16 shl 10)
        var size = 0

        private fun ensure(extra: Int) {
            if (size + extra <= buf.size) return
            var cap = buf.size
            while (cap < size + extra) cap *= 2
            buf = buf.copyOf(cap)
        }

        fun u8(v: Int) {
            ensure(1)
            buf[size++] = v.toByte()
        }

        fun u16(v: Int) {
            ensure(2)
            buf[size++] = (v ushr 8).toByte()
            buf[size++] = v.toByte()
        }

        fun u32(v: Long) {
            ensure(4)
            buf[size++] = (v ushr 24).toByte()
            buf[size++] = (v ushr 16).toByte()
            buf[size++] = (v ushr 8).toByte()
            buf[size++] = v.toByte()
        }

        fun bytes(src: ByteArray, off: Int, len: Int) {
            ensure(len)
            src.copyInto(buf, size, off, off + len)
            size += len
        }

        fun toByteArray(): ByteArray = buf.copyOf(size)
    }

    private fun u16(b: ByteArray, o: Int) = ((b[o].toInt() and 0xFF) shl 8) or (b[o + 1].toInt() and 0xFF)
    private fun s16(b: ByteArray, o: Int): Int = u16(b, o).let { if (it >= 0x8000) it - 0x10000 else it }
    private fun u32(b: ByteArray, o: Int): Long =
        ((b[o].toLong() and 0xFF) shl 24) or ((b[o + 1].toLong() and 0xFF) shl 16) or
            ((b[o + 2].toLong() and 0xFF) shl 8) or (b[o + 3].toLong() and 0xFF)

    private fun putU16(b: ByteArray, o: Int, v: Int) {
        b[o] = ((v ushr 8) and 0xFF).toByte()
        b[o + 1] = (v and 0xFF).toByte()
    }

    private fun putU32(b: ByteArray, o: Int, v: Long) {
        b[o] = ((v ushr 24) and 0xFF).toByte()
        b[o + 1] = ((v ushr 16) and 0xFF).toByte()
        b[o + 2] = ((v ushr 8) and 0xFF).toByte()
        b[o + 3] = (v and 0xFF).toByte()
    }
}
