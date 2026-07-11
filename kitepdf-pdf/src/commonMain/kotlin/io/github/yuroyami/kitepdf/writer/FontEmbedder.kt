package io.github.yuroyami.kitepdf.writer

import io.github.yuroyami.kitepdf.core.font.CffSubsetter
import io.github.yuroyami.kitepdf.core.font.TrueTypeSubsetter
import io.github.yuroyami.kitepdf.core.parser.PdfArray
import io.github.yuroyami.kitepdf.core.parser.PdfDictionary
import io.github.yuroyami.kitepdf.core.parser.PdfInt
import io.github.yuroyami.kitepdf.core.parser.PdfName
import io.github.yuroyami.kitepdf.core.parser.PdfObject
import io.github.yuroyami.kitepdf.core.parser.PdfReference
import io.github.yuroyami.kitepdf.core.parser.PdfString
import kotlin.math.roundToLong

/**
 * Builds the PDF object graph for a *full-font* embedded TrueType font.
 *
 * Mirrors MuPDF's `pdf_add_cid_font`: a Type0 composite font with `Identity-H`
 * encoding and a CIDFontType2 descendant whose `/CIDToGIDMap` is the identity
 * (CID == GID == the 2-byte code in the content stream), a `/FontDescriptor`
 * carrying the whole `/FontFile2` program, and a `/ToUnicode` CMap. Widths and
 * the ToUnicode entries are emitted only for the glyphs actually used (tracked
 * in [EmbeddedFontUsage]), so the dictionaries stay small even though the font
 * program is embedded whole.
 */
internal object FontEmbedder {

    /**
     * Emit the five objects (FontFile2, FontDescriptor, CIDFont, ToUnicode,
     * Type0) through [alloc]/[emit] and return the object number of the
     * top-level Type0 font — the value to put into `/Resources /Font`.
     */
    fun embed(
        font: EmbeddedFont,
        usage: EmbeddedFontUsage,
        alloc: () -> Long,
        emit: (Long, PdfObject) -> Unit,
    ): Long {
        val ttf = font.ttf
        val scale = 1000.0 / ttf.unitsPerEm
        // font design units -> PDF glyph space (1000 units/em).
        fun em(units: Int): Long = (units * scale).roundToLong()

        // Build the font program and the descendant-font shape, branching on outline
        // type. The content stream always emits the *original* glyph id as the 2-byte
        // code, so /W and /ToUnicode stay keyed by original gid in both branches.
        //  - TrueType: CIDFontType2 + /FontFile2; subset → /CIDToGIDMap *stream* maps
        //    original gid → new gid (full → /Identity).
        //  - OpenType/CFF: CIDFontType0 + /FontFile3 (CIDFontType0C); re-emitted as a
        //    CID-keyed CFF whose charset maps new gid → original gid (so the charset
        //    does the redirect — no /CIDToGIDMap). subset=false embeds all glyphs.
        val program: ByteArray
        val baseName: String
        val descendantSubtype: String
        val fontFileEntry: String
        val fontFileExtra: Map<String, PdfObject>
        var cidToGidMap: PdfObject? = null
        val cff = font.cff
        if (cff != null) {
            val used = if (font.subset && usage.usedGids.isNotEmpty()) usage.usedGids
                else (0 until cff.numGlyphs).toSet()
            val sub = CffSubsetter.subset(cff, used)
            program = sub.cff
            baseName = if (font.subset) subsetTag(sub.oldToNew) + "+" + font.postScriptName else font.postScriptName
            descendantSubtype = "CIDFontType0"
            fontFileEntry = "FontFile3"
            fontFileExtra = linkedMapOf("Subtype" to PdfName("CIDFontType0C"))
        } else {
            descendantSubtype = "CIDFontType2"
            fontFileEntry = "FontFile2"
            if (font.subset && usage.usedGids.isNotEmpty()) {
                val sub = TrueTypeSubsetter.subset(ttf, usage.usedGids)
                program = sub.fontBytes
                baseName = subsetTag(sub.oldToNew) + "+" + font.postScriptName
                val mapNum = alloc()
                emit(mapNum, PdfStreams.flate(cidToGidMapStream(sub.oldToNew)))
                cidToGidMap = PdfReference(mapNum, 0)
            } else {
                program = font.fontBytes
                baseName = font.postScriptName
                cidToGidMap = PdfName("Identity")
            }
            fontFileExtra = linkedMapOf("Length1" to PdfInt(program.size.toLong()))
        }

        // Font program stream (/FontFile2 raw TrueType, or /FontFile3 bare CFF).
        val fontFileNum = alloc()
        emit(fontFileNum, PdfStreams.flate(program, fontFileExtra))

        // /FontDescriptor. OS/2 isn't parsed, so CapHeight/StemV are approximated;
        // Flags = Symbolic(4) is correct for an Identity-encoded CID font.
        val descNum = alloc()
        emit(
            descNum,
            PdfDictionary(
                linkedMapOf(
                    "Type" to PdfName("FontDescriptor"),
                    "FontName" to PdfName(baseName),
                    "Flags" to PdfInt(4L),
                    "FontBBox" to PdfArray(
                        listOf(
                            PdfInt(em(ttf.head.xMin)), PdfInt(em(ttf.head.yMin)),
                            PdfInt(em(ttf.head.xMax)), PdfInt(em(ttf.head.yMax)),
                        ),
                    ),
                    "ItalicAngle" to PdfInt(0L),
                    "Ascent" to PdfInt(em(ttf.hhea.ascent)),
                    "Descent" to PdfInt(em(ttf.hhea.descent)),
                    "CapHeight" to PdfInt(em(ttf.hhea.ascent)),
                    "StemV" to PdfInt(80L),
                    fontFileEntry to PdfReference(fontFileNum, 0),
                ),
            ),
        )

        // CIDFont descendant.
        val cidDict = linkedMapOf<String, PdfObject>(
            "Type" to PdfName("Font"),
            "Subtype" to PdfName(descendantSubtype),
            "BaseFont" to PdfName(baseName),
            "CIDSystemInfo" to PdfDictionary(
                linkedMapOf(
                    "Registry" to PdfString("Adobe".encodeToByteArray()),
                    "Ordering" to PdfString("Identity".encodeToByteArray()),
                    "Supplement" to PdfInt(0L),
                ),
            ),
            "FontDescriptor" to PdfReference(descNum, 0),
            "DW" to PdfInt(1000L),
        )
        cidToGidMap?.let { cidDict["CIDToGIDMap"] = it }
        widthsArray(usage.usedGids) { em(ttf.advanceWidth(it)) }?.let { cidDict["W"] = it }
        val cidNum = alloc()
        emit(cidNum, PdfDictionary(cidDict))

        // /ToUnicode — lets readers extract and copy the original text.
        val toUniNum = alloc()
        emit(toUniNum, PdfStreams.flate(toUnicodeCMap(usage.gidToUnicode)))

        // Top-level Type0 font.
        val type0Num = alloc()
        emit(
            type0Num,
            PdfDictionary(
                linkedMapOf(
                    "Type" to PdfName("Font"),
                    "Subtype" to PdfName("Type0"),
                    "BaseFont" to PdfName(baseName),
                    "Encoding" to PdfName("Identity-H"),
                    "DescendantFonts" to PdfArray(listOf(PdfReference(cidNum, 0))),
                    "ToUnicode" to PdfReference(toUniNum, 0),
                ),
            ),
        )
        return type0Num
    }

    /**
     * A `/CIDToGIDMap` stream: 2 bytes per CID (big-endian new glyph id), indexed
     * by the original glyph id the content stream emits. CIDs outside the subset
     * map to 0 (.notdef).
     */
    private fun cidToGidMapStream(oldToNew: Map<Int, Int>): ByteArray {
        val maxCid = oldToNew.keys.maxOrNull() ?: 0
        val map = ByteArray((maxCid + 1) * 2)
        for ((old, new) in oldToNew) {
            map[old * 2] = (new ushr 8).toByte()
            map[old * 2 + 1] = (new and 0xFF).toByte()
        }
        return map
    }

    /** A deterministic 6-uppercase-letter subset tag (e.g. `ABCDEF`), derived from the glyph set. */
    private fun subsetTag(oldToNew: Map<Int, Int>): String {
        var h = 2166136261L.toInt()                 // FNV-1a-ish over the sorted gids
        for (k in oldToNew.keys.sorted()) h = (h xor k) * 16777619
        var v = h.toLong() and 0xFFFFFFFFL
        return buildString {
            repeat(6) { append('A' + (v % 26).toInt()); v /= 26 }
        }
    }

    /** A `/W` array grouped into consecutive-CID runs, or null when no glyphs used. */
    private fun widthsArray(gids: Set<Int>, widthOf: (Int) -> Long): PdfArray? {
        if (gids.isEmpty()) return null
        val sorted = gids.toIntArray()
        sorted.sort()
        val items = ArrayList<PdfObject>()
        var i = 0
        while (i < sorted.size) {
            var j = i
            while (j + 1 < sorted.size && sorted[j + 1] == sorted[j] + 1) j++
            items.add(PdfInt(sorted[i].toLong()))
            val run = ArrayList<PdfObject>(j - i + 1)
            for (k in i..j) run.add(PdfInt(widthOf(sorted[k])))
            items.add(PdfArray(run))
            i = j + 1
        }
        return PdfArray(items)
    }

    /**
     * A ToUnicode CMap stream body mapping each used gid (which, under
     * Identity-H, equals its content-stream code) to its Unicode value.
     */
    private fun toUnicodeCMap(gidToUnicode: Map<Int, Int>): ByteArray {
        val sb = StringBuilder(256)
        sb.append("/CIDInit /ProcSet findresource begin\n12 dict begin\nbegincmap\n")
        sb.append("/CIDSystemInfo << /Registry (Adobe) /Ordering (UCS) /Supplement 0 >> def\n")
        sb.append("/CMapName /Adobe-Identity-UCS def\n/CMapType 2 def\n")
        sb.append("1 begincodespacerange\n<0000> <FFFF>\nendcodespacerange\n")
        // bfchar blocks are capped at 100 entries each by the CMap spec.
        val entries = gidToUnicode.entries.sortedBy { it.key }
        var idx = 0
        while (idx < entries.size) {
            val end = minOf(idx + 100, entries.size)
            sb.append(end - idx).append(" beginbfchar\n")
            for (n in idx until end) {
                val e = entries[n]
                sb.append('<'); hex4(sb, e.key); sb.append("> <"); utf16Hex(sb, e.value); sb.append(">\n")
            }
            sb.append("endbfchar\n")
            idx = end
        }
        sb.append("endcmap\nCMapName currentdict /CMap defineresource pop\nend\nend\n")
        return sb.toString().encodeToByteArray()
    }

    private const val HEX = "0123456789ABCDEF"

    private fun hex4(sb: StringBuilder, v: Int) {
        sb.append(HEX[(v ushr 12) and 0xF]).append(HEX[(v ushr 8) and 0xF])
            .append(HEX[(v ushr 4) and 0xF]).append(HEX[v and 0xF])
    }

    /** Append [cp] as UTF-16BE hex — a surrogate pair above the BMP. */
    private fun utf16Hex(sb: StringBuilder, cp: Int) {
        if (cp <= 0xFFFF) {
            hex4(sb, cp)
        } else {
            val v = cp - 0x10000
            hex4(sb, 0xD800 + (v ushr 10))
            hex4(sb, 0xDC00 + (v and 0x3FF))
        }
    }
}
