package io.github.yuroyami.kitepdf.epub.css

import io.github.yuroyami.kitepdf.core.xml.KiteXmlNode

/**
 * The language of [el]'s content: the nearest `xml:lang` or `lang` on it or its [ancestors], and
 * `xml:lang` first where an element has both (HTML, 3.2.6.2). An empty one means the language is
 * unknown, and so does none at all. The package's `dc:language` has no say: a reading system
 * must not take a content document's language from the package (EPUB Reading Systems 3.3, 3.7).
 */
internal fun contentLanguage(el: KiteXmlNode.Element, ancestors: List<KiteXmlNode.Element>): String? {
    for (e in sequenceOf(el) + ancestors.asSequence()) {
        val lang = e.attrs["xml:lang"] ?: e.attrs["lang"] ?: continue
        return lang.trim().ifEmpty { null }
    }
    return null
}

/**
 * The quotation marks of [language], as `quotes: auto` draws them: the opening and closing mark,
 * then the pair for a quotation inside one, from CLDR (CSS Generated Content 3, 2.4). The tag
 * loses subtags from its end until [QuoteData] holds it, and ends at English (#511).
 */
internal fun quoteMarks(language: String?): List<String> {
    var tag = language?.trim()?.lowercase()?.replace('_', '-') ?: ""
    // CLDR files Traditional Chinese under its script, and a tag with only the region means it too.
    val parts = tag.split('-')
    if (parts[0] == "zh" && parts.getOrNull(1) in TRADITIONAL_CHINESE_REGIONS) tag = "zh-hant-" + parts.drop(1).joinToString("-")
    while (tag.isNotEmpty()) {
        QuoteData.MARKS[tag]?.let { return it.map(Char::toString) }
        tag = tag.substringBeforeLast('-', "")
    }
    return QuoteData.DEFAULT.map(Char::toString)
}

private val TRADITIONAL_CHINESE_REGIONS = setOf("tw", "hk", "mo")
