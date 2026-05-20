package com.yuroyami.kitepdf.font

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
class TtfCMap private constructor(private val subtable: Subtable) {

    fun glyphIdFor(codePoint: Int): Int = subtable.glyphId(codePoint)

    companion object {
        fun parse(reader: TtfReader, table: Table): TtfCMap {
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

            // Preference order: Unicode 4 (Unicode full repertoire) → Unicode 3 (BMP) →
            // Microsoft 10 (UCS-4) → Microsoft 1 (UCS-2) → Unicode 0/1/2 → Mac Roman.
            val ranked = records.sortedBy { rank(it.platform, it.encoding) }
            val chosen = ranked.first()
            reader.seek(table.offset + chosen.offset)
            val format = reader.u16()
            return TtfCMap(parseSubtable(reader, table.offset + chosen.offset, format))
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
            4 -> parseFormat4(reader)
            6 -> parseFormat6(reader)
            12 -> parseFormat12(reader, base)
            else -> EmptySubtable
        }

        /** Format 0: byte-to-glyph 256-entry table. Trivial. */
        private fun parseFormat0(reader: TtfReader): Subtable {
            reader.skip(4)  // length + language
            val mapping = IntArray(256) { reader.u8() }
            return Format0Subtable(mapping)
        }

        /**
         * Format 4: segment mapping to delta values. The BMP workhorse used by
         * most Western fonts. Segments are sorted by endCount; binary search
         * within. Per-segment, glyph IDs are computed via either an idDelta
         * (simple offset) or an idRangeOffset (table lookup).
         */
        private fun parseFormat4(reader: TtfReader): Subtable {
            reader.skip(4)  // length + language
            val segCountX2 = reader.u16()
            val segCount = segCountX2 / 2
            reader.skip(6)  // searchRange + entrySelector + rangeShift

            val endCount = IntArray(segCount) { reader.u16() }
            reader.skip(2)  // reservedPad
            val startCount = IntArray(segCount) { reader.u16() }
            val idDelta = IntArray(segCount) { reader.s16() }
            // idRangeOffset is special: its value is an offset from the field's
            // own location into the glyphIdArray that follows it.
            val rangeOffsetPos = reader.pos()
            val idRangeOffset = IntArray(segCount) { reader.u16() }
            // The remainder of the subtable is the glyphIdArray (we keep a ref
            // to its start position so format-4 lookups can index into it).
            return Format4Subtable(endCount, startCount, idDelta, idRangeOffset, reader, rangeOffsetPos)
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
        private val reader: TtfReader,
        private val rangeOffsetPos: Int,
    ) : Subtable {
        override fun glyphId(codePoint: Int): Int {
            if (codePoint < 0 || codePoint > 0xFFFF) return 0
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
                // idRangeOffset field's own memory address — translating that:
                //   glyphIdArrayIndex = (idRangeOffset[i]/2 + (c - startCount[i]) + i)
                // and the resulting byte offset = rangeOffsetPos + 2*i + idRangeOffset[i]
                //                                + 2*(c - startCount[i])
                val byteOffset = rangeOffsetPos + 2 * i + idRangeOffset[i] +
                    2 * (codePoint - startCount[i])
                reader.seek(byteOffset)
                val raw = reader.u16()
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
}
