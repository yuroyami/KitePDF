package io.github.yuroyami.kitepdf.core.render

import io.github.yuroyami.kitepdf.core.font.TextGlyph
import io.github.yuroyami.kitepdf.core.text.Bidi

/*
 * How a canvas draws a run of text in a host font, which comes as one glyph per character, in
 * the order it draws, left to right, each with the advance the document gives it.
 */

/**
 * [glyphs] cut into pieces that each shape as one unit. A glyph that the document
 * spaces after, by character or word spacing, ends its piece, and a spaced blank
 * glyph is a piece of its own. A run without spacing stays one piece, which keeps
 * its ligatures and combining marks.
 */
public fun spacedPieces(glyphs: List<TextGlyph>): List<List<TextGlyph>> {
    val pieces = ArrayList<List<TextGlyph>>()
    var start = 0
    for (i in glyphs.indices) {
        val glyph = glyphs[i]
        if (glyph.advanceAdjust == 0.0) continue
        if (glyph.text.isBlank() && start < i) {
            pieces += glyphs.subList(start, i)
            start = i
        }
        pieces += glyphs.subList(start, i + 1)
        start = i + 1
    }
    if (start < glyphs.size) pieces += glyphs.subList(start, glyphs.size)
    return pieces
}

/**
 * Glyphs of a piece that a text engine draws as one string, [offset] advance units from the
 * piece's start. [rightToLeft] when its letters run right to left, so that [text] holds them in
 * logical order inside a right-to-left override.
 */
public class DrawOrderPart(
    public val glyphs: List<TextGlyph>,
    public val text: String,
    public val offset: Double,
    public val rightToLeft: Boolean = false,
)

/**
 * [piece], which is in the order it draws, left to right, as the strings a text engine draws in
 * that order (#486). A text engine runs the bidi algorithm, so it would reverse right-to-left
 * letters the layout already reversed. A piece without them stays one part, as it was. Otherwise
 * it is cut where the strong direction changes, and a right-to-left part goes back to logical
 * order inside a right-to-left override, so the engine joins Arabic letters with their logical
 * neighbours and lays them out right to left, which is the order they came in. A left-to-right
 * part beside it takes a left-to-right override, so that its digits and punctuation stay put.
 */
public fun drawOrderParts(piece: List<TextGlyph>): List<DrawOrderPart> {
    val directions = IntArray(piece.size) { strongDirection(piece[it].text) }
    if (directions.none { it == RIGHT_TO_LEFT }) {
        return listOf(DrawOrderPart(piece, piece.joinToString("") { it.text }, 0.0))
    }
    val parts = ArrayList<DrawOrderPart>()
    var start = 0
    var offset = 0.0
    var direction = directions.first { it != NEUTRAL }
    fun close(end: Int) {
        val glyphs = piece.subList(start, end)
        val text = if (direction == RIGHT_TO_LEFT) {
            glyphs.asReversed().joinToString("", prefix = "‮", postfix = "‬") { it.text }
        } else {
            glyphs.joinToString("", prefix = "‭", postfix = "‬") { it.text }
        }
        parts += DrawOrderPart(glyphs, text, offset, direction == RIGHT_TO_LEFT)
        offset += glyphs.sumOf { it.advanceWidth }
        start = end
    }
    for (i in piece.indices) {
        val d = directions[i]
        if (d == NEUTRAL || d == direction) continue
        close(i)
        direction = d
    }
    close(piece.size)
    return parts
}

/**
 * A part of a run of host text as a canvas draws it. [x] is where its first glyph starts and
 * [width] is the width the document gives its glyphs, both in the units of the scales
 * [hostTextParts] took. A [shaped] part draws [text] as one string that the text engine shapes,
 * fitted to [width]. Any other part draws each of its glyphs alone, at its own advance, as
 * MuPDF places a base-14 font. [rightToLeft] as in [DrawOrderPart].
 */
public class HostTextPart(
    public val glyphs: List<TextGlyph>,
    public val text: String,
    public val x: Double,
    public val width: Double,
    public val shaped: Boolean,
    public val rightToLeft: Boolean = false,
)

/**
 * [glyphs] as the parts a canvas without a layout of its own draws them in (#588): the pieces of
 * [spacedPieces], each cut into the [drawOrderParts]. A part of more than one glyph is [shaped]
 * when a character of it changes shape or place with its neighbours, as Arabic letters join and
 * an Indic vowel sign moves, so that the text engine sees them together. Advances move the pen by
 * [advanceScale] each, and the spacing a glyph adds after it by [adjustScale].
 */
public fun hostTextParts(glyphs: List<TextGlyph>, advanceScale: Double, adjustScale: Double): List<HostTextPart> {
    val parts = ArrayList<HostTextPart>()
    var penX = 0.0
    for (piece in spacedPieces(glyphs)) {
        for (part in drawOrderParts(piece)) {
            parts += HostTextPart(
                part.glyphs, part.text,
                x = penX + part.offset * advanceScale,
                width = part.glyphs.sumOf { it.advanceWidth } * advanceScale,
                shaped = part.glyphs.size > 1 && part.glyphs.any { needsShaping(it.text) },
                rightToLeft = part.rightToLeft,
            )
        }
        penX += piece.sumOf { it.advanceWidth } * advanceScale + piece.last().advanceAdjust * adjustScale
    }
    return parts
}

/**
 * True when a character of [text] may draw differently beside its neighbours than alone: a mark,
 * a joiner or another format character, a right-to-left letter, or a letter of a script that
 * joins, reorders or builds clusters, such as Arabic, Devanagari or Thai. False for Latin, Greek,
 * Cyrillic and Armenian letters, punctuation, symbols, CJK ideographs, kana and Hangul syllables.
 */
public fun needsShaping(text: String): Boolean {
    var i = 0
    while (i < text.length) {
        val high = text[i]
        val pair = high.isHighSurrogate() && i + 1 < text.length && text[i + 1].isLowSurrogate()
        val cp = if (pair) 0x10000 + ((high.code - 0xD800) shl 10) + (text[i + 1].code - 0xDC00) else high.code
        if (!drawsAlone(cp)) return true
        i += if (pair) 2 else 1
    }
    return false
}

/** True for a character that draws the same alone as beside its neighbours. See [needsShaping]. */
private fun drawsAlone(cp: Int): Boolean {
    if (cp < 0x0300) return true
    when (Bidi.classify(cp)) {
        Bidi.NSM, Bidi.BN, Bidi.R, Bidi.AL,
        Bidi.LRE, Bidi.LRO, Bidi.RLE, Bidi.RLO, Bidi.PDF, Bidi.LRI, Bidi.RLI, Bidi.FSI, Bidi.PDI -> return false
    }
    return cp < 0x0590 || // Greek, Cyrillic, Armenian
        cp in 0x1E00..0x1FFF || // Latin Extended Additional, Greek Extended
        cp in 0x2000..0x2E7F || // punctuation, symbols, arrows, shapes, dingbats
        cp in 0x2E80..0xA4CF || // CJK radicals and symbols, kana, Bopomofo, ideographs, Yi
        cp in 0xAC00..0xD7A3 || // Hangul syllables
        cp in 0xF900..0xFAFF || // CJK compatibility ideographs
        cp in 0xFE10..0xFE1F || cp in 0xFE30..0xFE4F || // vertical and CJK compatibility forms
        cp in 0xFF00..0xFFEF || // halfwidth and fullwidth forms
        cp in 0x20000..0x3FFFF // ideographs of the supplementary planes
}

private const val NEUTRAL = -1
private const val LEFT_TO_RIGHT = 0
private const val RIGHT_TO_LEFT = 1

/** The direction of the first strong character of [text], or [NEUTRAL] when it has none. */
private fun strongDirection(text: String): Int {
    var i = 0
    while (i < text.length) {
        val high = text[i]
        val pair = high.isHighSurrogate() && i + 1 < text.length && text[i + 1].isLowSurrogate()
        val cp = if (pair) 0x10000 + ((high.code - 0xD800) shl 10) + (text[i + 1].code - 0xDC00) else high.code
        when (Bidi.classify(cp)) {
            Bidi.L -> return LEFT_TO_RIGHT
            Bidi.R, Bidi.AL -> return RIGHT_TO_LEFT
        }
        i += if (pair) 2 else 1
    }
    return NEUTRAL
}
