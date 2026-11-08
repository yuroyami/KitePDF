package io.github.yuroyami.kitepdf.epub

import io.github.yuroyami.kitepdf.core.font.GsubGlyph
import io.github.yuroyami.kitepdf.core.font.OpenTypeGsub
import io.github.yuroyami.kitepdf.core.text.Bidi

/**
 * Shapes text in one font the way the EPUB layout does (#945), for code that draws text of its
 * own, such as a PDF writer.
 *
 * [shape] resolves the bidi levels of the text by UAX #9 and orders it for display. It runs each
 * word through the font's GSUB with the shaper of its script, Arabic joining forms included,
 * attaches marks by their GPOS anchors and kerns adjacent glyphs. The glyphs come back from the
 * left, each at its place on the line, with the text it stands for, so the logical string can be
 * rebuilt from them.
 *
 * ```kotlin
 * val shaper = KiteShaper.load(fontBytes)
 * val line = shaper.shape("سلام")       // four glyphs, joined, drawn right to left
 * ```
 */
public class KiteShaper private constructor(private val face: EmbeddedFace) {

    /** The font's units per em. */
    public val unitsPerEm: Int get() = face.unitsPerEm

    /** The font's ascent above the baseline in thousandths of an em, from `hhea`, or 800 without one. */
    public val ascent: Double get() = face.ascentEm?.let { it * 1000.0 } ?: 800.0

    /** The font's descent below the baseline in thousandths of an em, a positive number, or 200 without one. */
    public val descent: Double get() = face.descentEm?.let { it * 1000.0 } ?: 200.0

    /** The glyph id of [codePoint] by the font's `cmap`, 0 when it has none. */
    public fun glyphId(codePoint: Int): Int = face.gidFor(codePoint)

    /**
     * [text] as one line of glyphs. [rightToLeft] sets the paragraph direction; null takes it
     * from the first strong character (UAX #9, rules P2 and P3). A line break or tab is drawn as
     * a space.
     */
    public fun shape(text: String, rightToLeft: Boolean? = null): ShapedLine {
        val cps = codePoints(text)
        val starts = IntArray(cps.size + 1)
        run { var u = 0; for (i in cps.indices) { starts[i] = u; u += if (cps[i] >= 0x10000) 2 else 1 }; starts[cps.size] = u }
        val paraLevel = when (rightToLeft) { true -> 1; false -> 0; null -> Bidi.baseLevel(cps) }
        if (cps.isEmpty()) return ShapedLine(text, emptyList(), 0.0, paraLevel == 1)
        val levels = Bidi.lineLevels(cps, Bidi.resolveLevels(cps, paraLevel), paraLevel)

        // Each word shapes alone, in logical order: a run of one level that no space breaks.
        val logical = ArrayList<Placed>(cps.size)
        var i = 0
        while (i < cps.size) {
            if (isSpace(cps[i])) {
                logical += Placed(face.gidFor(' '.code), advance(face.gidFor(' '.code)), i, i + 1, levels[i])
                i++
                continue
            }
            var end = i + 1
            while (end < cps.size && !isSpace(cps[end]) && levels[end] == levels[i]) end++
            shapeWord(cps, i, end, levels[i], logical)
            i = end
        }

        // The line in display order: glyphs of the same level keep their order, and each run at an odd level turns round.
        val glyphLevels = IntArray(logical.size) { logical[it].level }
        val order = Bidi.reorderVisually(glyphLevels)
        val out = ArrayList<ShapedGlyph>(logical.size)
        val xs = DoubleArray(logical.size)
        var pen = 0.0
        for (k in order) {
            val g = logical[k]
            val base = g.base
            xs[k] = if (base != null) Double.NaN else pen
            pen += g.advance
        }
        // A mark sits at its base, wherever the line put the base.
        for (k in order) {
            val g = logical[k]
            val base = g.base ?: continue
            xs[k] = xs[base].takeIf { !it.isNaN() } ?: 0.0
            xs[k] += g.dx
        }
        for (k in order) {
            val g = logical[k]
            if (g.invisible) continue
            val text = if (g.textEnd > g.textStart) text.substring(starts[g.textStart], starts[g.textEnd]).let { if (it.any { c -> c == '\n' || c == '\t' || c == '\r' }) " " else it } else ""
            out += ShapedGlyph(g.gid, xs[k], g.dy, g.advance, starts[g.textStart], starts[g.textEnd.coerceAtLeast(g.textStart)], text, g.level % 2 == 1)
        }
        return ShapedLine(text, out, pen, paraLevel == 1)
    }

    /** A glyph in logical order while the line is shaped. */
    private class Placed(
        val gid: Int, var advance: Double, val textStart: Int, val textEnd: Int, val level: Int,
        /** The logical index of the glyph a mark attaches to, or null. */
        var base: Int? = null, var dx: Double = 0.0, var dy: Double = 0.0, val invisible: Boolean = false,
        val glyph: GsubGlyph? = null,
    )

    private fun shapeWord(cps: IntArray, start: Int, end: Int, level: Int, out: MutableList<Placed>) {
        val word = cps.copyOfRange(start, end)
        val gids = IntArray(word.size) { face.gidFor(word[it]) }
        val gsub = face.gsub ?: OpenTypeGsub.EMPTY
        val script = TextShaper.script(word, gsub)
        val forms = if (ArabicJoining.hasArabic(word)) ArabicJoining.forms(word) else null
        val rtl = level % 2 == 1
        val glyphs = TextShaper.shape(face, gsub, script, word, gids, forms, optionalLigatures = true, rtl = BooleanArray(word.size) { rtl })
        val first = out.size
        for ((j, g) in glyphs.withIndex()) {
            val leads = j == 0 || glyphs.subList(0, j).none { it.cluster == g.cluster }
            var next = word.size
            for (h in glyphs) if (h.cluster > g.cluster && h.cluster < next) next = h.cluster
            val invisible = g.shaperData and TextShaper.INVISIBLE != 0
            out += Placed(
                g.gid, if (invisible) 0.0 else advance(g.gid),
                start + g.cluster, if (leads) start + next else start + g.cluster,
                level, invisible = invisible, glyph = g,
            )
        }
        // Kerning between neighbours in logical order. Right to left, the space between a pair is the advance of the later glyph.
        for (k in first until out.size - 1) {
            val a = out[k]; val b = out[k + 1]
            if (a.invisible || b.invisible) continue
            val kern = face.kern1000(a.gid, b.gid).toDouble()
            if (kern != 0.0) { if (rtl) b.advance += kern else a.advance += kern }
        }
        attachMarks(out, first)
    }

    /** The GPOS marks of a word: onto the mark below them, a ligature component, or the base, as the layout attaches them. */
    private fun attachMarks(glyphs: MutableList<Placed>, first: Int) {
        val scale = 1000.0 / face.unitsPerEm
        var base = -1
        var stacked = -1
        var stackedX = 0.0
        var stackedY = 0.0
        for (k in first until glyphs.size) {
            val c = glyphs[k]
            if (base >= 0 && !c.invisible) {
                val b = glyphs[base]
                val components = b.glyph?.components ?: 1
                val off = (if (stacked >= 0) face.markStackOffset(glyphs[stacked].gid, c.gid) else null)
                    ?.let { (stackedX + it.first) to (stackedY + it.second) }
                    ?: (if (components > 1) face.markLigatureOffset(b.gid, c.gid, components - 1) else null)
                    ?: face.markOffset(b.gid, c.gid)
                if (off != null) {
                    c.base = base
                    c.dx = off.first * scale
                    c.dy = off.second * scale
                    stacked = k; stackedX = off.first; stackedY = off.second
                    continue
                }
            }
            base = k
            stacked = -1
        }
    }

    private fun advance(gid: Int): Double = face.advanceRaw(gid) * 1000.0 / face.unitsPerEm

    public companion object {
        /**
         * The shaper of a TrueType or OpenType font, WOFF and WOFF2 included. A collection
         * (`.ttc`) gives face [faceIndex].
         *
         * @throws IllegalArgumentException when [fontBytes] are no font.
         */
        public fun load(fontBytes: ByteArray, faceIndex: Int = 0): KiteShaper {
            val sfnt = if (fontBytes.size >= 4 && fontBytes[0] == 't'.code.toByte() && fontBytes[1] == 't'.code.toByte() &&
                fontBytes[2] == 'c'.code.toByte() && fontBytes[3] == 'f'.code.toByte()
            ) io.github.yuroyami.kitepdf.core.font.TrueTypeFont.extractFace(fontBytes, faceIndex) else fontBytes
            val program = FontProgram.parse(sfnt) ?: throw IllegalArgumentException("not a font: ${fontBytes.size} bytes")
            return KiteShaper(EmbeddedFace("shaped", bold = false, italic = false, program = program))
        }

        private fun isSpace(cp: Int): Boolean = cp == ' '.code || cp == '\t'.code || cp == '\n'.code || cp == '\r'.code || cp == 0x3000 || cp == 0xA0

        private fun codePoints(s: String): IntArray {
            val out = IntArray(s.length)
            var n = 0
            var i = 0
            while (i < s.length) {
                val c = s[i]
                if (c.isHighSurrogate() && i + 1 < s.length && s[i + 1].isLowSurrogate()) {
                    out[n++] = 0x10000 + ((c.code - 0xD800) shl 10) + (s[i + 1].code - 0xDC00); i += 2
                } else { out[n++] = c.code; i++ }
            }
            return out.copyOf(n)
        }
    }
}

/**
 * One line of shaped text: its glyphs from the left, and its [width] in thousandths of an em.
 * [rightToLeft] is the paragraph direction it was shaped in.
 */
public class ShapedLine(
    public val text: String,
    public val glyphs: List<ShapedGlyph>,
    public val width: Double,
    public val rightToLeft: Boolean,
) {
    /** The text of the glyphs in display order. It differs from [text] where the line was reordered or a cluster split. */
    public val visualText: String get() = glyphs.joinToString("") { it.text }
}

/**
 * One glyph of a [ShapedLine]: glyph id [gid] at [x] along the line and [y] above the baseline,
 * both in thousandths of an em, with its own [advance]. It stands for the characters
 * [textStart] until [textEnd] of the line's text, which [text] holds; the later glyphs of a
 * cluster stand for none. [rightToLeft] when its bidi level is odd.
 */
public class ShapedGlyph(
    public val gid: Int,
    public val x: Double,
    public val y: Double,
    public val advance: Double,
    public val textStart: Int,
    public val textEnd: Int,
    public val text: String,
    public val rightToLeft: Boolean,
)
