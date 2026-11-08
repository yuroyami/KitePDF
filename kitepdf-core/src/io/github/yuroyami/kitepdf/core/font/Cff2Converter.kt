package io.github.yuroyami.kitepdf.core.font

import io.github.yuroyami.kitepdf.core.ByteArrayBuilder
import io.github.yuroyami.kitepdf.core.render.KitePath
import kotlin.math.abs
import kotlin.math.roundToLong

/**
 * A CFF2 program (OpenType 1.8) written as a CFF program of the first version, so a PDF can
 * embed it as `CIDFontType0C`, which every reader draws (#917).
 *
 * A CFF2 charstring may hold `blend` and `vsindex`, which no CFF reader knows, so each glyph is
 * written again from its outline at the font's default instance: one `rmoveto`, `rlineto` and
 * `rrcurveto` for each segment, and `endchar` (Adobe Technical Note 5177, Type 2 charstrings).
 * A quadratic segment becomes the cubic it equals. Hints and subroutines are dropped, as the
 * subsetter drops hints; widths come from the font's `hmtx`, as a PDF's `/W` gives them. The
 * result is CID-keyed with the identity ordering, each CID its glyph id, as [CffSubsetter] writes.
 */
public object Cff2Converter {

    /** [cff2] at its default instance as a CFF program, its glyphs in the same order. */
    public fun toCff(cff2: CffFont): ByteArray {
        val n = cff2.numGlyphs.coerceAtLeast(1)
        val charStrings = List(n) { gid -> charString(cff2.outline(gid)) }

        val nameIndex = index(listOf("KiteCff2".encodeToByteArray()))
        val stringIndex = index(listOf("Adobe".encodeToByteArray(), "Identity".encodeToByteArray()))
        val gsubrIndex = index(emptyList())
        val charStringsIndex = index(charStrings)
        // Charset format 2, one range: glyphs 1 to n - 1 are CIDs 1 to n - 1.
        val charset = ByteArrayBuilder(8).apply {
            append(2.toByte())
            if (n > 1) { appendU16BE(1); appendU16BE(n - 2) }
        }.toByteArray()
        // FDSelect format 3: every glyph in FontDict 0.
        val fdSelect = ByteArrayBuilder(9).apply {
            append(3.toByte()); appendU16BE(1); appendU16BE(0); append(0.toByte()); appendU16BE(n)
        }.toByteArray()
        val private = ByteArray(0)

        fun fontDict(privOffset: Int): ByteArray = ByteArrayBuilder(12).apply {
            append(int5(private.size)); append(int5(privOffset)); append(op(18))
        }.toByteArray()
        fun topDict(charsetOff: Int, charStringsOff: Int, fdArrayOff: Int, fdSelectOff: Int): ByteArray = ByteArrayBuilder(48).apply {
            append(dictInt(391)); append(dictInt(392)); append(dictInt(0)); append(op(0x0C1E)) // ROS
            append(dictInt(n)); append(op(0x0C22))                                              // CIDCount
            append(int5(charsetOff)); append(op(15))
            append(int5(charStringsOff)); append(op(17))
            append(int5(fdArrayOff)); append(op(0x0C24))
            append(int5(fdSelectOff)); append(op(0x0C25))
        }.toByteArray()

        val topLength = index(listOf(topDict(0, 0, 0, 0))).size
        var pos = 4 + nameIndex.size + topLength + stringIndex.size + gsubrIndex.size
        val charStringsOff = pos; pos += charStringsIndex.size
        val charsetOff = pos; pos += charset.size
        val fdSelectOff = pos; pos += fdSelect.size
        val fdArrayOff = pos; pos += index(listOf(fontDict(0))).size
        val privateOff = pos

        val out = ByteArrayBuilder(pos + private.size)
        out.append(byteArrayOf(1, 0, 4, 4))
        out.append(nameIndex)
        out.append(index(listOf(topDict(charsetOff, charStringsOff, fdArrayOff, fdSelectOff))))
        out.append(stringIndex)
        out.append(gsubrIndex)
        out.append(charStringsIndex)
        out.append(charset)
        out.append(fdSelect)
        out.append(index(listOf(fontDict(privateOff))))
        out.append(private)
        return out.toByteArray()
    }

    /** A Type 2 charstring that draws [path], in the program's units, with no width and no hints. */
    internal fun charString(path: KitePath?): ByteArray {
        val out = ByteArrayBuilder(64)
        var x = 0.0
        var y = 0.0
        if (path != null) for (s in path.segments) when (s) {
            is KitePath.Segment.MoveTo -> { num(out, s.x - x); num(out, s.y - y); out.append(21.toByte()); x = s.x; y = s.y }
            is KitePath.Segment.LineTo -> { num(out, s.x - x); num(out, s.y - y); out.append(5.toByte()); x = s.x; y = s.y }
            is KitePath.Segment.CurveTo -> {
                num(out, s.x1 - x); num(out, s.y1 - y); num(out, s.x2 - s.x1); num(out, s.y2 - s.y1); num(out, s.x3 - s.x2); num(out, s.y3 - s.y2)
                out.append(8.toByte()); x = s.x3; y = s.y3
            }
            is KitePath.Segment.QuadTo -> {
                // The cubic of a quadratic: its control points two thirds of the way to the quadratic's.
                val c1x = x + 2.0 / 3.0 * (s.x1 - x); val c1y = y + 2.0 / 3.0 * (s.y1 - y)
                val c2x = s.x2 + 2.0 / 3.0 * (s.x1 - s.x2); val c2y = s.y2 + 2.0 / 3.0 * (s.y1 - s.y2)
                num(out, c1x - x); num(out, c1y - y); num(out, c2x - c1x); num(out, c2y - c1y); num(out, s.x2 - c2x); num(out, s.y2 - c2y)
                out.append(8.toByte()); x = s.x2; y = s.y2
            }
            // A Type 2 contour closes at the next moveto or at endchar.
            KitePath.Segment.Close -> {}
        }
        out.append(14.toByte())
        return out.toByteArray()
    }

    /** A charstring number: an integer in the short forms, anything else as 16.16 fixed. */
    private fun num(out: ByteArrayBuilder, v: Double) {
        val r = v.roundToLong()
        if (abs(v - r) < 1e-6 && r in -32768..32767) {
            val i = r.toInt()
            when (i) {
                in -107..107 -> out.append((i + 139).toByte())
                in 108..1131 -> { val w = i - 108; out.append(((w shr 8) + 247).toByte()); out.append((w and 0xFF).toByte()) }
                in -1131..-108 -> { val w = -i - 108; out.append(((w shr 8) + 251).toByte()); out.append((w and 0xFF).toByte()) }
                else -> { out.append(28.toByte()); out.append((i shr 8).toByte()); out.append(i.toByte()) }
            }
        } else {
            val f = (v * 65536.0).roundToLong().toInt()
            out.append(255.toByte()); out.append((f ushr 24).toByte()); out.append((f ushr 16).toByte()); out.append((f ushr 8).toByte()); out.append(f.toByte())
        }
    }

    private fun index(items: List<ByteArray>): ByteArray {
        val out = ByteArrayBuilder(16 + items.sumOf { it.size })
        out.appendU16BE(items.size)
        if (items.isEmpty()) return out.toByteArray()
        out.append(4.toByte())
        var offset = 1
        out.appendU32BE(offset)
        for (it in items) { offset += it.size; out.appendU32BE(offset) }
        for (it in items) out.append(it)
        return out.toByteArray()
    }

    private fun op(o: Int): ByteArray = if (o > 0xFF) byteArrayOf((o shr 8).toByte(), o.toByte()) else byteArrayOf(o.toByte())

    private fun dictInt(v: Int): ByteArray = when (v) {
        in -107..107 -> byteArrayOf((v + 139).toByte())
        in 108..1131 -> (v - 108).let { byteArrayOf(((it shr 8) + 247).toByte(), it.toByte()) }
        in -1131..-108 -> (-v - 108).let { byteArrayOf(((it shr 8) + 251).toByte(), it.toByte()) }
        in -32768..32767 -> byteArrayOf(28, (v shr 8).toByte(), v.toByte())
        else -> int5(v)
    }

    private fun int5(v: Int): ByteArray = byteArrayOf(29, (v ushr 24).toByte(), (v ushr 16).toByte(), (v ushr 8).toByte(), v.toByte())
}
