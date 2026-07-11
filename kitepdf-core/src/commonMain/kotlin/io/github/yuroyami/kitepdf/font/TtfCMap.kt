package io.github.yuroyami.kitepdf.font

/**
 * TrueType / OpenType `cmap` table — character-code → glyph-index lookup.
 *
 * The `cmap` table has multiple subtables for different encodings; we pick the
 * best one (Unicode > Microsoft Unicode > everything else) and route lookups
 * through it. Format 4 (segment-mapping-to-delta) is the workhorse for BMP
 * Unicode; format 12 (segmented coverage) handles supplementary planes;
 * formats 0 and 6 are legacy but still appear in older fonts.
 *
 * Glyph index 0 is the special `.notdef` glyph — we return it for any
 * unmapped codepoint so callers always get something drawable.
 */
public class TtfCMap private constructor(private val subtable: Subtable) {

    public fun glyphIdFor(codePoint: Int): Int = subtable.glyphId(codePoint)

    public companion object {
        /** A cmap that maps every code point to `.notdef` — for CID-keyed fonts that ship no `cmap`. */
        public fun empty(): TtfCMap = TtfCMap(EmptySubtable)

        public fun parse(reader: TtfReader, table: Table): TtfCMap {
            reader.seek(table.offset)
            reader.skip(2)  // version
            val numSubtables = reader.u16()

            // Read encoding records: pick the best one we recognise.
            data class Record(val platform: Int, val encoding: Int, val offset: Int)
            val records = List(numSubtables) {
                val platform = reader.u16()
                val encoding = reader.u16()
                val offset = reader.s32()
                Record(platform, encoding, offset)
            }
            // No usable subtables → an all-.notdef cmap (avoid first() on empty).
            if (records.isEmpty()) return TtfCMap(EmptySubtable)

            // Preference order: Unicode 4 (Unicode full repertoire) → Unicode 3 (BMP) →
            // Microsoft 10 (UCS-4) → Microsoft 1 (UCS-2) → Unicode 0/1/2 → Mac Roman.
            // We try records in this order and FALL BACK to the next one whenever the
            // best-ranked subtable is in a format we can't parse (yields EmptySubtable),
            // so a font whose top choice is format 2/13/14 still resolves via a lesser
            // subtable instead of mapping everything to .notdef.
            val ranked = records.sortedBy { rank(it.platform, it.encoding) }
            for (rec in ranked) {
                val base = table.offset + rec.offset
                // A malformed offset/subtable throws while parsing; treat that like an
                // unsupported subtable and fall through to the next candidate rather
                // than failing the whole cmap.
                val sub = try {
                    reader.seek(base)
                    val format = reader.u16()
                    parseSubtable(reader, base, format)
                } catch (_: RuntimeException) {
                    EmptySubtable
                }
                if (sub !== EmptySubtable) {
                    // (3,0) symbol subtables map into the private-use F000..F0FF
                    // range; wrap so callers can still pass a bare 0x20..0xFF code.
                    return TtfCMap(
                        if (rec.platform == 3 && rec.encoding == 0) SymbolSubtable(sub) else sub
                    )
                }
            }
            return TtfCMap(EmptySubtable)
        }

        private fun rank(platform: Int, encoding: Int): Int = when (platform to encoding) {
            3 to 10 -> 0
            0 to 4 -> 1
            0 to 6 -> 2
            3 to 1 -> 3
            0 to 3 -> 4
            0 to 2 -> 5
            0 to 1 -> 6
            0 to 0 -> 7
            1 to 0 -> 8
            else -> 9
        }

        private fun parseSubtable(reader: TtfReader, base: Int, format: Int): Subtable = when (format) {
            0 -> parseFormat0(reader)
            2 -> parseFormat2(reader, base)
            4 -> parseFormat4(reader, base)
            6 -> parseFormat6(reader)
            12 -> parseFormat12(reader, base)
            13 -> parseFormat13(reader, base)
            // Format 14 (Unicode variation sequences) is deferred: it maps
            // (base, variation-selector) pairs, not a bare code point, so it needs
            // a different lookup signature. Falls back to the next subtable.
            else -> EmptySubtable
        }

        /** Format 0: byte-to-glyph 256-entry table. Trivial. */
        private fun parseFormat0(reader: TtfReader): Subtable {
            reader.skip(4)  // length + language
            val mapping = IntArray(256) { reader.u8() }
            return Format0Subtable(mapping)
        }

        /**
         * Format 2: high-byte mapping through table — the classic CJK encoding
         * (Shift-JIS, Big5, etc.). A 256-entry subHeaderKeys array routes each
         * lead byte either to single-byte handling (key 0) or to one of several
         * subHeaders covering a range of trailing bytes.
         */
        private fun parseFormat2(reader: TtfReader, base: Int): Subtable {
            reader.skip(2)  // length
            reader.skip(2)  // language
            // subHeaderKeys[256] — each is (subHeader index * 8).
            val keys = IntArray(256) { reader.u16() / 8 }
            var maxSub = 0
            for (k in keys) if (k > maxSub) maxSub = k
            val subHeaderCount = maxSub + 1
            // Each subHeader: firstCode(2) entryCount(2) idDelta(2) idRangeOffset(2).
            // idRangeOffset is relative to its own position; capture where the
            // subHeader array starts so we can compute glyphIndexArray positions.
            val subHeaderArrayLocal = reader.pos() - base
            val firstCode = IntArray(subHeaderCount)
            val entryCount = IntArray(subHeaderCount)
            val idDelta = IntArray(subHeaderCount)
            val idRangeOffset = IntArray(subHeaderCount)
            // Local offset of each subHeader's idRangeOffset field.
            val rangeOffLocal = IntArray(subHeaderCount)
            for (i in 0 until subHeaderCount) {
                firstCode[i] = reader.u16()
                entryCount[i] = reader.u16()
                idDelta[i] = reader.s16()
                rangeOffLocal[i] = reader.pos() - base
                idRangeOffset[i] = reader.u16()
            }
            // The glyphIndexArray fills the rest of the subtable; snapshot the whole
            // thing from base so lookups index an immutable copy.
            reader.seek(base + subHeaderArrayLocal + subHeaderCount * 8)
            // Determine subtable end: scan to the furthest glyphIndexArray entry any
            // subHeader can reach, then slice from base. Being generous is safe.
            var end = base + subHeaderArrayLocal + subHeaderCount * 8
            for (i in 0 until subHeaderCount) {
                if (idRangeOffset[i] != 0) {
                    val last = base + rangeOffLocal[i] + idRangeOffset[i] +
                        (entryCount[i]) * 2
                    if (last > end) end = last
                }
            }
            val subLen = (end - base).coerceIn(0, reader.bytes.size - base)
            val data = reader.slice(base, subLen)
            return Format2Subtable(keys, firstCode, entryCount, idDelta, idRangeOffset, rangeOffLocal, data)
        }

        /**
         * Format 4: segment mapping to delta values. The BMP workhorse used by
         * most Western fonts. Segments are sorted by endCount; binary search
         * within. Per-segment, glyph IDs are computed via either an idDelta
         * (simple offset) or an idRangeOffset (table lookup).
         */
        private fun parseFormat4(reader: TtfReader, base: Int): Subtable {
            // Caller already consumed the 2-byte `format`; layout from here is
            // length(2) language(2) segCountX2(2) searchRange(2) entrySelector(2)
            // rangeShift(2) endCount[segCount] reservedPad(2) startCount[] idDelta[]
            // idRangeOffset[] glyphIdArray[].
            val length = reader.u16()
            reader.skip(2)  // language
            val segCountX2 = reader.u16()
            val segCount = segCountX2 / 2
            reader.skip(6)  // searchRange + entrySelector + rangeShift

            val endCount = IntArray(segCount) { reader.u16() }
            reader.skip(2)  // reservedPad
            val startCount = IntArray(segCount) { reader.u16() }
            val idDelta = IntArray(segCount) { reader.s16() }
            // idRangeOffset is special: its value is an offset from the field's
            // own location into the glyphIdArray that follows it. We record the
            // in-subtable position of each idRangeOffset entry, then snapshot the
            // whole subtable's bytes so lookups index into an immutable copy rather
            // than seeking the shared reader (which made lookups non-reentrant).
            val rangeOffsetLocal = reader.pos() - base
            val idRangeOffset = IntArray(segCount) { reader.u16() }
            val rawLen = if (length in 1..0xFFFF) length else (reader.pos() - base)
            val subLen = rawLen.coerceIn(0, reader.bytes.size - base)
            val data = reader.slice(base, subLen)
            return Format4Subtable(endCount, startCount, idDelta, idRangeOffset, data, rangeOffsetLocal)
        }

        /** Format 6: trimmed byte-to-glyph table. Linear range of codes. */
        private fun parseFormat6(reader: TtfReader): Subtable {
            reader.skip(4)  // length + language
            val firstCode = reader.u16()
            val entryCount = reader.u16()
            val mapping = IntArray(entryCount) { reader.u16() }
            return Format6Subtable(firstCode, mapping)
        }

        /** Format 12: segmented coverage (32-bit codepoints, supplementary planes). */
        private fun parseFormat12(reader: TtfReader, base: Int): Subtable {
            reader.skip(2)  // reserved
            reader.skip(4)  // length
            reader.skip(4)  // language
            val numGroups = reader.s32()
            val starts = IntArray(numGroups)
            val ends = IntArray(numGroups)
            val startGlyphs = IntArray(numGroups)
            for (i in 0 until numGroups) {
                starts[i] = reader.s32()
                ends[i] = reader.s32()
                startGlyphs[i] = reader.s32()
            }
            // Silence "unused" warning on `base`.
            if (base < 0) error("unreachable")
            return Format12Subtable(starts, ends, startGlyphs)
        }

        /**
         * Format 13: many-to-one range mappings. Same on-disk layout as format 12,
         * but every code point in a group maps to the SINGLE glyph in `glyphId`
         * (not an incrementing range). Used for things like the "last resort" font
         * where a whole block points at one placeholder glyph.
         */
        private fun parseFormat13(reader: TtfReader, base: Int): Subtable {
            reader.skip(2)  // reserved
            reader.skip(4)  // length
            reader.skip(4)  // language
            val numGroups = reader.s32()
            val starts = IntArray(numGroups)
            val ends = IntArray(numGroups)
            val glyphs = IntArray(numGroups)
            for (i in 0 until numGroups) {
                starts[i] = reader.s32()
                ends[i] = reader.s32()
                glyphs[i] = reader.s32()
            }
            if (base < 0) error("unreachable")
            return Format13Subtable(starts, ends, glyphs)
        }
    }

    /* ─── Subtable implementations ───────────────────────────────────────── */

    private interface Subtable {
        fun glyphId(codePoint: Int): Int
    }

    private object EmptySubtable : Subtable {
        override fun glyphId(codePoint: Int): Int = 0
    }

    private class Format0Subtable(private val mapping: IntArray) : Subtable {
        override fun glyphId(codePoint: Int): Int =
            if (codePoint in 0..255) mapping[codePoint] else 0
    }

    private class Format4Subtable(
        private val endCount: IntArray,
        private val startCount: IntArray,
        private val idDelta: IntArray,
        private val idRangeOffset: IntArray,
        // Immutable snapshot of the whole format-4 subtable, so glyphId() reads the
        // glyphIdArray without mutating any shared reader (lookups are reentrant).
        private val data: ByteArray,
        // Local offset (within [data]) of idRangeOffset[0].
        private val rangeOffsetLocal: Int,
    ) : Subtable {
        override fun glyphId(codePoint: Int): Int {
            if (codePoint < 0 || codePoint > 0xFFFF) return 0
            if (endCount.isEmpty()) return 0
            // Binary search for the smallest endCount[i] >= codePoint.
            var lo = 0; var hi = endCount.size - 1
            while (lo < hi) {
                val mid = (lo + hi) ushr 1
                if (endCount[mid] < codePoint) lo = mid + 1 else hi = mid
            }
            val i = lo
            if (codePoint < startCount[i]) return 0
            return if (idRangeOffset[i] == 0) {
                ((codePoint + idDelta[i]) and 0xFFFF)
            } else {
                // The spec defines the offset arithmetic relative to the
                // idRangeOffset field's own location — translating that to a
                // local index into our snapshot:
                //   byteOffset = rangeOffsetLocal + 2*i + idRangeOffset[i]
                //                + 2*(c - startCount[i])
                val byteOffset = rangeOffsetLocal + 2 * i + idRangeOffset[i] +
                    2 * (codePoint - startCount[i])
                if (byteOffset < 0 || byteOffset + 2 > data.size) return 0
                val raw = ((data[byteOffset].toInt() and 0xFF) shl 8) or
                    (data[byteOffset + 1].toInt() and 0xFF)
                if (raw == 0) 0 else (raw + idDelta[i]) and 0xFFFF
            }
        }
    }

    private class Format6Subtable(
        private val firstCode: Int,
        private val mapping: IntArray,
    ) : Subtable {
        override fun glyphId(codePoint: Int): Int {
            val idx = codePoint - firstCode
            return if (idx in mapping.indices) mapping[idx] else 0
        }
    }

    /**
     * Format 2: high-byte mapping through table (CJK). A code point is treated as
     * up to two bytes: the high byte selects a subHeader (via [keys]); single-byte
     * codes use subHeader 0. Within a subHeader, the low byte indexes a run of
     * [entryCount] glyphIndexArray entries starting at [idRangeOffset].
     */
    private class Format2Subtable(
        private val keys: IntArray,
        private val firstCode: IntArray,
        private val entryCount: IntArray,
        private val idDelta: IntArray,
        private val idRangeOffset: IntArray,
        private val rangeOffLocal: IntArray,
        private val data: ByteArray,
    ) : Subtable {
        private fun u16(p: Int): Int =
            if (p < 0 || p + 2 > data.size) 0
            else ((data[p].toInt() and 0xFF) shl 8) or (data[p + 1].toInt() and 0xFF)

        override fun glyphId(codePoint: Int): Int {
            if (codePoint < 0 || codePoint > 0xFFFF) return 0
            val high = (codePoint ushr 8) and 0xFF
            val low = codePoint and 0xFF
            // Single-byte code (high == 0 selects subHeader 0). For a two-byte code
            // the high byte selects the subHeader; the low byte is the entry.
            val sub: Int
            val entryByte: Int
            if (high == 0 && keys[0] == 0) {
                sub = 0
                entryByte = low
            } else {
                sub = keys.getOrElse(high) { 0 }
                entryByte = low
            }
            if (sub < 0 || sub >= firstCode.size) return 0
            val idx = entryByte - firstCode[sub]
            if (idx < 0 || idx >= entryCount[sub]) return 0
            if (idRangeOffset[sub] == 0) return 0
            val p = rangeOffLocal[sub] + idRangeOffset[sub] + idx * 2
            val raw = u16(p)
            return if (raw == 0) 0 else (raw + idDelta[sub]) and 0xFFFF
        }
    }

    private class Format12Subtable(
        private val starts: IntArray,
        private val ends: IntArray,
        private val startGlyphs: IntArray,
    ) : Subtable {
        override fun glyphId(codePoint: Int): Int {
            var lo = 0; var hi = ends.size - 1
            while (lo <= hi) {
                val mid = (lo + hi) ushr 1
                when {
                    codePoint > ends[mid] -> lo = mid + 1
                    codePoint < starts[mid] -> hi = mid - 1
                    else -> return startGlyphs[mid] + (codePoint - starts[mid])
                }
            }
            return 0
        }
    }

    /**
     * Format 13: many-to-one. Same group layout as format 12 but every code point
     * in a group maps to the same [glyphs] value (no per-code increment).
     */
    private class Format13Subtable(
        private val starts: IntArray,
        private val ends: IntArray,
        private val glyphs: IntArray,
    ) : Subtable {
        override fun glyphId(codePoint: Int): Int {
            var lo = 0; var hi = ends.size - 1
            while (lo <= hi) {
                val mid = (lo + hi) ushr 1
                when {
                    codePoint > ends[mid] -> lo = mid + 1
                    codePoint < starts[mid] -> hi = mid - 1
                    else -> return glyphs[mid]
                }
            }
            return 0
        }
    }

    /**
     * Wraps a (3,0) "symbol" subtable. Symbol fonts map their glyphs into the
     * Unicode private-use range F000..F0FF, so a bare code such as 0x41 ('A') or
     * 0x20 (space) won't resolve directly. We first try the code as-is (in case
     * the caller already passed an F0xx code), then retry with 0xF000 added for
     * codes in the 0x00..0xFF range.
     */
    private class SymbolSubtable(private val inner: Subtable) : Subtable {
        override fun glyphId(codePoint: Int): Int {
            val direct = inner.glyphId(codePoint)
            if (direct != 0) return direct
            if (codePoint in 0x00..0xFF) return inner.glyphId(0xF000 or codePoint)
            return 0
        }
    }
}
