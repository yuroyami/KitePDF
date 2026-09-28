package io.github.yuroyami.kitepdf.core

/**
 * What a piece of content is, for a reader that speaks rather than draws. It comes from the
 * structure of the source, not from how the content looks: an EPUB `<h2>` or a tagged PDF `/H2`
 * is a heading whatever its style, and a large bold paragraph is not.
 */
public enum class KiteRole {
    /** Running text, and anything with no better name. */
    TEXT,

    /** A heading; the depth is in [KiteReadingItem.headingLevel]. */
    HEADING,

    /** One item of a list. */
    LIST_ITEM,

    /** A block quotation. */
    QUOTE,

    /** Computer code. */
    CODE,

    /** One cell of a table. */
    TABLE_CELL,

    /** The caption of a figure or a table. */
    CAPTION,

    /** An image; the text is its alternative text, empty when it has none. */
    IMAGE,

    /** The page number of a print edition, not content. */
    PAGE_BREAK,
}

/**
 * One stop in a page's reading order: what a screen reader would announce, in the order it would
 * announce it. [KitePage.readingOrder] gives the items of a page of any format.
 *
 * ```kotlin
 * for (item in page.readingOrder()) {
 *     when (item.role) {
 *         KiteRole.HEADING -> speakHeading(item.text, item.headingLevel)
 *         else -> speak(item.text)
 *     }
 * }
 * ```
 *
 * @property text the words to announce. Blank only for an image without alternative text.
 * @property headingLevel 1 to 6 for a [KiteRole.HEADING], 0 for everything else.
 * @property sourceType the source's own name for the content, or null: the `epub:type` of an EPUB
 *   element, such as `footnote`, or the structure type of a tagged PDF element, such as `Note`.
 * @property pronunciation how a speech engine says [text], from an EPUB `ssml:ph`, or null. The
 *   item then holds the words of that element only.
 * @property alphabet the phonetic alphabet of [pronunciation], such as `ipa`, or null.
 * @property language the language of [text] when the source gives one, such as `fr`, or null.
 */
public class KiteReadingItem(
    public val role: KiteRole,
    public val text: String,
    public val headingLevel: Int = 0,
    public val sourceType: String? = null,
    public val pronunciation: String? = null,
    public val alphabet: String? = null,
    public val language: String? = null,
) {
    override fun toString(): String =
        "KiteReadingItem($role${if (headingLevel > 0) " h$headingLevel" else ""}: ${text.take(40)})"
}
