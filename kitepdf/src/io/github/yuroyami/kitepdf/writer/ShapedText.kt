package io.github.yuroyami.kitepdf.writer

import io.github.yuroyami.kitepdf.core.text.Bidi
import io.github.yuroyami.kitepdf.core.text.Hyphenator
import io.github.yuroyami.kitepdf.epub.KiteShaper
import io.github.yuroyami.kitepdf.epub.ShapedLine

/**
 * A font that a [PdfBuilder] page both embeds and shapes text in (#945): the [EmbeddedFont] that
 * the PDF carries and the [KiteShaper] of the same program. Load once and reuse it.
 *
 * ```kotlin
 * val font = ShapedFont.load(notoNaskhBytes)
 * PdfBuilder().page {
 *     shapedText(font, 24.0, 72.0, 700.0, "سلام")
 *     paragraph(font, 11.0, x = 72.0, y = 72.0, width = 300.0, height = 600.0, text = article)
 * }
 * ```
 */
public class ShapedFont private constructor(public val font: EmbeddedFont, public val shaper: KiteShaper) {
    public companion object {
        /** As [EmbeddedFont.load], with the shaper of the same face. */
        public fun load(fontBytes: ByteArray, name: String? = null, subset: Boolean = true, faceIndex: Int = 0): ShapedFont =
            ShapedFont(EmbeddedFont.load(fontBytes, name, subset, faceIndex), KiteShaper.load(fontBytes, faceIndex))
    }
}

/** The paragraph direction of shaped text: from its first strong character, or set. */
public enum class TextDirection { Auto, LeftToRight, RightToLeft }

/** Where the lines of a [paragraph] sit in its box. [Start] and [End] follow the paragraph direction. */
public enum class TextAlign { Start, End, Left, Right, Center, Justify }

/**
 * What [paragraph] laid out: the number of [lines] it drew, the [height] they took from the top
 * of the box, the baseline of the last line, and the text that did not fit, which an app
 * continues on the next page. [overflow] is empty when everything fit.
 */
public class PdfParagraph(
    public val lines: Int,
    public val height: Double,
    public val lastBaseline: Double,
    public val overflow: String,
)

/**
 * One line of [text] in [font] at [size], shaped and ordered for display, with its baseline
 * starting at ([x], [y]) on the left (#945). Arabic letters join, marks sit on their bases, and a
 * right-to-left line runs from the right. Text extraction gives back [text]: each glyph maps to
 * its characters in the font's `/ToUnicode`, in display order, as readers expect of a line drawn
 * right to left. A line that the map cannot give back carries `/ActualText`: one where a glyph
 * id stands for two texts, as a Devanagari vowel sign does in the clusters of two consonants.
 *
 * @return the width of the line in user space.
 */
public fun ContentStreamBuilder.shapedText(
    font: ShapedFont, size: Double, x: Double, y: Double, text: String, direction: TextDirection = TextDirection.Auto,
): Double {
    val line = font.shaper.shape(text, direction.rightToLeft())
    drawLine(font, size, x, y, line, 0.0)
    return line.width * size / 1000.0
}

/**
 * [text] laid out in the box whose lower left corner is ([x], [y]), [width] wide and [height]
 * high (#945). Each line is shaped as [shapedText] shapes one.
 *
 * Lines break after a space, after a hyphen or dash, and around CJK ideographs, and at each
 * line feed, which starts a new paragraph. A word wider than the line breaks at its hyphenation
 * points for [language] (a BCP 47 tag, such as `en-US`; null for none) with a hyphen added, and
 * otherwise between its characters. Baselines are [lineHeight] times [size] apart, the first
 * the font's ascent below the top. [align] places each line; [TextAlign.Justify] widens the
 * spaces of every line but the last of a paragraph.
 *
 * Lines are drawn until the next one would go below the box. The text from that line on comes
 * back as [PdfParagraph.overflow].
 */
public fun ContentStreamBuilder.paragraph(
    font: ShapedFont,
    size: Double,
    x: Double,
    y: Double,
    width: Double,
    height: Double,
    text: String,
    align: TextAlign = TextAlign.Start,
    lineHeight: Double = 1.2,
    language: String? = null,
    direction: TextDirection = TextDirection.Auto,
): PdfParagraph {
    val shaper = font.shaper
    val ascent = shaper.ascent * size / 1000.0
    val descent = shaper.descent * size / 1000.0
    val top = y + height
    var baseline = top - ascent
    var lines = 0
    var last = Double.NaN
    val hyphenator = language?.let { Hyphenator.forLanguage(it) }
    val maxWidth = width * 1000.0 / size
    val paragraphs = text.split('\n')
    var consumed = 0
    for ((pi, para) in paragraphs.withIndex()) {
        val rtl = direction.rightToLeft() ?: (Bidi.baseLevel(codePoints(para)) == 1)
        val broken = breakLines(shaper, para, rtl, maxWidth, hyphenator)
        for ((li, b) in broken.withIndex()) {
            if (baseline - descent < y - 0.001) {
                val rest = text.substring(consumed + b.start)
                return PdfParagraph(lines, top - (last.takeIf { !it.isNaN() }?.minus(descent) ?: top), last.takeIf { !it.isNaN() } ?: top, rest)
            }
            val line = shaper.shape(b.text, rtl, language)
            val lastOfParagraph = li == broken.lastIndex
            val extra = (maxWidth - line.width).coerceAtLeast(0.0)
            val (offset, stretch) = when (align) {
                TextAlign.Left -> 0.0 to 0.0
                TextAlign.Right -> extra to 0.0
                TextAlign.Center -> extra / 2 to 0.0
                TextAlign.Start -> (if (rtl) extra else 0.0) to 0.0
                TextAlign.End -> (if (rtl) 0.0 else extra) to 0.0
                TextAlign.Justify -> if (lastOfParagraph) (if (rtl) extra else 0.0) to 0.0 else 0.0 to extra
            }
            drawLine(font, size, x + offset * size / 1000.0, baseline, line, stretch)
            last = baseline
            lines++
            baseline -= lineHeight * size
        }
        consumed += para.length + if (pi < paragraphs.lastIndex) 1 else 0
    }
    val used = if (last.isNaN()) 0.0 else top - (last - descent)
    return PdfParagraph(lines, used, if (last.isNaN()) top else last, "")
}

private fun TextDirection.rightToLeft(): Boolean? = when (this) {
    TextDirection.Auto -> null
    TextDirection.LeftToRight -> false
    TextDirection.RightToLeft -> true
}

/** [line] at ([x], [y]), its spaces widened by [stretch] thousandths of an em in all. */
private fun ContentStreamBuilder.drawLine(font: ShapedFont, size: Double, x: Double, y: Double, line: ShapedLine, stretch: Double) {
    if (line.glyphs.isEmpty()) return
    val spaces = line.glyphs.count { it.text == " " }
    val per = if (spaces > 0) stretch / spaces else 0.0
    var seen = 0
    val glyphs = line.glyphs.map { g ->
        // A right-to-left glyph of several characters, such as lam-alef, maps to them in display order,
        // so a reader that reverses the right-to-left runs of a line, as Poppler and KitePDF do, reads them right.
        val text = if (g.rightToLeft && g.text.length > 1) reversed(g.text) else g.text
        val shifted = PdfGlyph(g.gid, g.x + per * seen, g.y, text)
        if (g.text == " ") seen++
        shifted
    }
    // Readers put a line drawn right to left back in logical order themselves, and reverse an /ActualText
    // too, so a span carries one only where that reordering cannot give back the text, or where the font's
    // /ToUnicode already maps a glyph to other text, as a Devanagari vowel sign drawn for two consonants.
    val visual = glyphs.joinToString("") { it.text }
    val recovered = Bidi.logicalOrder(visual, if (line.rightToLeft) 1 else 0)?.first
        ?.let { order -> CharArray(order.size) { visual[order[it]] }.concatToString() } ?: visual
    glyphs(font.font, size, x, y, glyphs, line.text, whenAmbiguous = recovered == line.text)
}

/** [s] with its characters in reverse order, a surrogate pair kept as one. */
private fun reversed(s: String): String {
    val out = StringBuilder(s.length)
    var i = s.length
    while (i > 0) {
        val c = s[i - 1]
        if (c.isLowSurrogate() && i > 1 && s[i - 2].isHighSurrogate()) { out.append(s[i - 2]).append(c); i -= 2 } else { out.append(c); i-- }
    }
    return out.toString()
}

/** One line of a paragraph: its text, which starts at [start] of the paragraph. */
private class BrokenLine(val start: Int, val text: String)

/**
 * Greedy line breaking: the longest run of break units whose width without its trailing spaces
 * fits [maxWidth] thousandths of an em. A unit too wide for a line of its own breaks at a
 * hyphenation point, or else between characters.
 */
private fun breakLines(shaper: KiteShaper, para: String, rtl: Boolean, maxWidth: Double, hyphenator: Hyphenator?): List<BrokenLine> {
    if (para.isBlank()) return listOf(BrokenLine(0, ""))
    val units = breakUnits(para)
    fun widthOf(s: String) = shaper.shape(s, rtl).width
    val out = ArrayList<BrokenLine>()
    var lineStart = 0
    var lineWidth = 0.0
    var i = 0
    var pendingStart = -1 // the start of a unit cut by a line break, while its rest waits
    while (i < units.size) {
        val (us, ue) = units[i]
        val from = if (pendingStart >= 0) pendingStart else us
        val unit = para.substring(from, ue)
        val trimmed = unit.trimEnd()
        val unitWidth = widthOf(unit)
        val trimmedWidth = if (trimmed.length == unit.length) unitWidth else widthOf(trimmed)
        if (lineWidth + trimmedWidth <= maxWidth || (trimmed.isEmpty())) {
            lineWidth += unitWidth
            pendingStart = -1
            i++
            continue
        }
        // The unit does not fit: try to end this line with a hyphenated part of it.
        val split = hyphenSplit(shaper, rtl, trimmed, maxWidth - lineWidth, hyphenator)
        if (split != null) {
            out += BrokenLine(lineStart, para.substring(lineStart, from + split) + "-")
            lineStart = from + split; lineWidth = 0.0; pendingStart = lineStart
            continue
        }
        if (lineStart < from) {
            out += BrokenLine(lineStart, para.substring(lineStart, from).trimEnd())
            lineStart = from; lineWidth = 0.0
            continue
        }
        // Alone and still too wide: as many characters as fit, at least one.
        val cut = characterSplit(shaper, rtl, trimmed, maxWidth)
        out += BrokenLine(lineStart, para.substring(from, from + cut))
        lineStart = from + cut; lineWidth = 0.0; pendingStart = lineStart
    }
    if (lineStart < para.length) out += BrokenLine(lineStart, para.substring(lineStart).trimEnd())
    return out
}

/** The longest hyphenated prefix of [word] that fits [room] with its hyphen, as a length, or null. */
private fun hyphenSplit(shaper: KiteShaper, rtl: Boolean, word: String, room: Double, hyphenator: Hyphenator?): Int? {
    if (hyphenator == null || room <= 0) return null
    val letters = word.takeWhile { it.isLetter() || it.category == CharCategory.NON_SPACING_MARK }
    if (letters.length < 4) return null
    val points = runCatching { hyphenator.hyphenate(letters) }.getOrDefault(emptyList())
    return points.sortedDescending().firstOrNull { p -> p in 1 until letters.length && shaper.shape(word.substring(0, p) + "-", rtl).width <= room }
}

/** How many characters of [word] fit [maxWidth], never splitting a mark from its base: at least one character. */
private fun characterSplit(shaper: KiteShaper, rtl: Boolean, word: String, maxWidth: Double): Int {
    var best = 0
    var i = 0
    while (i < word.length) {
        var next = i + if (word[i].isHighSurrogate() && i + 1 < word.length) 2 else 1
        while (next < word.length && (word[next].category == CharCategory.NON_SPACING_MARK || word[next].category == CharCategory.COMBINING_SPACING_MARK || word[next] == '‍')) next++
        if (best > 0 && shaper.shape(word.substring(0, next), rtl).width > maxWidth) break
        best = next
        i = next
    }
    return best.coerceAtLeast(1)
}

/**
 * The break units of [para], each a range of it: a unit ends after its spaces, after a hyphen,
 * dash or slash that letters follow, and a CJK ideograph is a unit of its own, closing
 * punctuation staying with the ideograph before it.
 */
private fun breakUnits(para: String): List<Pair<Int, Int>> {
    val out = ArrayList<Pair<Int, Int>>()
    var start = 0
    var i = 0
    fun cut(at: Int) { if (at > start) { out += start to at; start = at } }
    while (i < para.length) {
        val c = para[i]
        when {
            c == ' ' || c == '　' -> {
                var j = i
                while (j < para.length && (para[j] == ' ' || para[j] == '　')) j++
                cut(j); i = j
                continue
            }
            (c == '-' || c == '‐' || c == '–' || c == '—' || c == '/') && i > start && i + 1 < para.length && para[i + 1].isLetterOrDigit() -> cut(i + 1)
            isIdeograph(c) -> {
                cut(i)
                var j = i + 1
                while (j < para.length && para[j] in CLOSERS) j++
                cut(j); i = j
                continue
            }
        }
        i++
    }
    cut(para.length)
    return out
}

private const val CLOSERS = "、。，．！？：；）」』】〉》〕〗〙〛ー々ぁぃぅぇぉっゃゅょゎァィゥェォッャュョヮヵヶ”’"

private fun isIdeograph(c: Char): Boolean =
    c in '぀'..'ヿ' || c in '㐀'..'䶿' || c in '一'..'鿿' || c in '豈'..'﫿' || c in '가'..'힯'

private fun codePoints(s: String): IntArray {
    val out = ArrayList<Int>(s.length)
    var i = 0
    while (i < s.length) {
        val c = s[i]
        if (c.isHighSurrogate() && i + 1 < s.length && s[i + 1].isLowSurrogate()) {
            out += 0x10000 + ((c.code - 0xD800) shl 10) + (s[i + 1].code - 0xDC00); i += 2
        } else { out += c.code; i++ }
    }
    return out.toIntArray()
}
