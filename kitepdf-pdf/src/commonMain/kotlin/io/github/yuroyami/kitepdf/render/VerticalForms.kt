package io.github.yuroyami.kitepdf.render

import io.github.yuroyami.kitepdf.core.font.TextGlyph

/**
 * The text a host face draws for a column of a font without a program. A CID such as
 * Adobe-Japan1 7911 selects the vertical form of its character (ISO 32000-1, 9.7.3, and
 * Adobe TN 5078), but its text is the horizontal code point, which extraction keeps. The host
 * face draws the Unicode vertical presentation form instead, from MuPDF's table for a
 * substitute font in writing mode 1 (#471).
 */
internal object VerticalForms {

    /** [glyphs] with the text of each one replaced by its vertical presentation form, where it has one. */
    fun of(glyphs: List<TextGlyph>): List<TextGlyph> {
        if (glyphs.none { g -> g.text.any { it in FORMS } }) return glyphs
        return glyphs.map { g ->
            if (g.text.none { it in FORMS }) g
            else g.copy(text = CharArray(g.text.length) { i -> FORMS[g.text[i]] ?: g.text[i] }.concatToString())
        }
    }

    private val FORMS: Map<Char, Char> = mapOf(
        '!' to '︕', '(' to '︵', ')' to '︶', ',' to '︐', ':' to '︓', ';' to '︔',
        '?' to '︖', '[' to '﹇', ']' to '﹈', '_' to '︳', '{' to '︷', '}' to '︸',
        '–' to '︲', '—' to '︱', '‥' to '︰', '…' to '︙',
        '、' to '︑', '。' to '︒', '〈' to '︿', '〉' to '﹀',
        '《' to '︽', '》' to '︾', '「' to '﹁', '」' to '﹂',
        '『' to '﹃', '』' to '﹄', '【' to '︻', '】' to '︼',
        '〔' to '︹', '〕' to '︺', '〖' to '︗', '〗' to '︘',
        'ー' to '︱',
        '！' to '︕', '（' to '︵', '）' to '︶', '，' to '︐',
        '－' to '︱', '：' to '︓', '；' to '︔', '？' to '︖',
        '［' to '﹇', '］' to '﹈', '＿' to '︳', '｛' to '︷', '｝' to '︸',
    )
}
