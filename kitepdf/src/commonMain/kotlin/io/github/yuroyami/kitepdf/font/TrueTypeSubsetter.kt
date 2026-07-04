package io.github.yuroyami.kitepdf.font

import io.github.yuroyami.kitepdf.core.ByteArrayBuilder

/**
 * Produces a subset TrueType font containing only the glyphs a document uses
 * (plus their composite-glyph dependencies and `.notdef`).
 *
 * Glyphs are renumbered to a dense 0..N-1 range, so the caller must map the
 * original glyph ids to the new ones — exposed via [Subset.oldToNew], which the
 * embedder turns into a PDF `/CIDToGIDMap` stream (the content stream keeps the
 * *original* glyph ids as codes, and the map redirects them to the subset).
 *
 * Rebuilds `glyf` (composite child references renumbered in place), `loca`
 * (long format), and `hmtx`; copies and patches `head`/`maxp`/`hhea`; and drops
 * everything else (a CIDFontType2 with an Identity encoding + `/CIDToGIDMap`
 * needs no `cmap`/`name`/`post`). Assembly + checksums are [SfntWriter]'s job.
 */
internal object TrueTypeSubsetter {

    // Tables copied unchanged when present (no glyph-id-indexed data, so safe).
    // NOT cmap (maps Unicode→old gid — renumbering invalidates it) and NOT post
    // (format 2.0 is glyph-id-indexed, so a verbatim copy would mismatch the subset).
    //
    // cvt/fpgm/prep are the hinting-program tables: `fpgm` defines functions and
    // `prep`/`cvt` set up the control-value table that per-glyph instructions rely
    // on. We copy the `glyf` bytes verbatim (instructions included), so we MUST keep
    // these three or strict hinting rasterisers reference missing functions/CVT
    // entries and mis-render or reject the font. They carry no glyph-id-indexed data,
    // so a verbatim copy is safe.
    private val COPY_VERBATIM = listOf("name", "OS/2", "cvt ", "fpgm", "prep")

    class Subset(val fontBytes: ByteArray, val oldToNew: Map<Int, Int>)

    fun subset(font: TrueTypeFont, usedGids: Set<Int>): Subset {
        // 1. Transitive glyph closure, always including .notdef (gid 0). Gid 0 is
        //    queued like any other seed (not pre-added to the closure) so that its
        //    own composite children — if .notdef is a composite glyph — are pulled
        //    in transitively too.
        val closure = HashSet<Int>()
        val work = ArrayDeque<Int>()
        work.addLast(0)
        usedGids.forEach { work.addLast(it) }
        while (work.isNotEmpty()) {
            val g = work.removeLast()
            if (g < 0 || g >= font.numGlyphs || !closure.add(g)) continue
            for (child in font.compositeChildGids(g)) work.addLast(child)
        }

        // 2. Dense renumber in ascending original-id order (stable new ids).
        val oldGids = closure.toIntArray()
        oldGids.sort()
        val numGlyphs = oldGids.size
        val oldToNew = HashMap<Int, Int>(numGlyphs * 2)
        for (newGid in oldGids.indices) oldToNew[oldGids[newGid]] = newGid

        // 3. Rebuild glyf (renumbering composite children) + long-format loca.
        val glyf = ByteArrayBuilder(4096)
        val loca = IntArray(numGlyphs + 1)
        for (i in 0 until numGlyphs) {
            loca[i] = glyf.size()
            val bytes = font.glyfBytes(oldGids[i])
            if (bytes.isNotEmpty()) {
                glyf.append(if (isComposite(bytes)) patchComposite(bytes, oldToNew) else bytes)
                if (glyf.size() % 2 != 0) glyf.append(0)   // word-align each glyph
            }
        }
        loca[numGlyphs] = glyf.size()

        val locaBytes = ByteArrayBuilder((numGlyphs + 1) * 4)
        for (off in loca) locaBytes.appendU32BE(off)

        // 4. hmtx with a full hMetric per subset glyph (advance + lsb from original).
        val numH = font.hhea.numberOfHMetrics
        val hmtxRaw = font.rawTable("hmtx")
        val hmtx = ByteArrayBuilder(numGlyphs * 4)
        for (i in 0 until numGlyphs) {
            hmtx.appendU16BE(font.advanceWidth(oldGids[i]) and 0xFFFF)
            hmtx.appendU16BE(leftSideBearing(hmtxRaw, numH, oldGids[i]) and 0xFFFF)
        }

        // 5. Copy head/maxp/hhea and patch the fields the subset changes.
        val head = font.rawTable("head")!!.copyOf()
        u16(head, 50, 1)                       // indexToLocFormat = long
        // The other maxp v1.0 stats (maxPoints, maxContours, maxComposite*, etc.)
        // are copied verbatim. They were computed over the FULL font, so they are
        // upper bounds for any subset of it — a rasteriser sizing scratch buffers
        // from them over-allocates at worst, never under-allocates. Only numGlyphs
        // must shrink to stay consistent with loca/glyf; we patch just that.
        val maxp = font.rawTable("maxp")!!.copyOf()
        u16(maxp, 4, numGlyphs)                // maxp.numGlyphs
        val hhea = font.rawTable("hhea")!!.copyOf()
        u16(hhea, 34, numGlyphs)               // hhea.numberOfHMetrics

        val out = linkedMapOf(
            "head" to head,
            "hhea" to hhea,
            "maxp" to maxp,
            "hmtx" to hmtx.toByteArray(),
            "loca" to locaBytes.toByteArray(),
            "glyf" to glyf.toByteArray(),
        )
        // Copy name/OS/2/post verbatim when present — not required for PDF rendering
        // (the PDF drives glyphs via /CIDToGIDMap, not the font's cmap), but their
        // offsets are self-relative so copying is safe, and it keeps the embedded
        // program acceptable to stricter consumers that expect a font name.
        for (tag in COPY_VERBATIM) font.rawTable(tag)?.let { out[tag] = it }

        return Subset(SfntWriter.assemble(out), oldToNew)
    }

    private fun isComposite(b: ByteArray): Boolean =
        b.size >= 2 && (((b[0].toInt() and 0xFF) shl 8) or (b[1].toInt() and 0xFF)).toShort() < 0

    /**
     * Rewrite a composite glyph's component glyph-id references to their new ids,
     * in place. Component layout per the `glyf` spec; only the 2-byte glyphIndex
     * after each component's flags is touched, so the byte length is unchanged.
     */
    private fun patchComposite(src: ByteArray, oldToNew: Map<Int, Int>): ByteArray {
        val b = src.copyOf()
        var pos = 10                            // skip numberOfContours + bbox
        while (pos + 4 <= b.size) {
            val flags = u16r(b, pos)
            val newChild = oldToNew[u16r(b, pos + 2)] ?: 0
            u16(b, pos + 2, newChild)
            pos += 4
            pos += if (flags and 0x0001 != 0) 4 else 2
            pos += when {
                flags and 0x0008 != 0 -> 2
                flags and 0x0040 != 0 -> 4
                flags and 0x0080 != 0 -> 8
                else -> 0
            }
            if (flags and 0x0020 == 0) break
        }
        return b
    }

    /** left-side bearing for [gid] from a raw `hmtx` table ([numH] long hMetrics). */
    private fun leftSideBearing(hmtx: ByteArray?, numH: Int, gid: Int): Int {
        if (hmtx == null) return 0
        val pos = if (gid < numH) gid * 4 + 2 else numH * 4 + (gid - numH) * 2
        if (pos < 0 || pos + 2 > hmtx.size) return 0
        return u16r(hmtx, pos).toShort().toInt()
    }

    private fun u16r(b: ByteArray, p: Int): Int = ((b[p].toInt() and 0xFF) shl 8) or (b[p + 1].toInt() and 0xFF)

    private fun u16(b: ByteArray, p: Int, v: Int) {
        b[p] = (v ushr 8).toByte()
        b[p + 1] = v.toByte()
    }
}
