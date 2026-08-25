package io.github.yuroyami.kitepdf.epub

/**
 * What a piece of content IS, for readers that speak rather than draw.
 * Taken from the source element, so `<h2>` is a heading whatever CSS does to
 * it and a styled `<div>` that only looks like one is not.
 */
public enum class EpubRole {
    /** Running text: `<p>`, a bare text block, anything with no better name. */
    TEXT,

    /** `<h1>`..`<h6>`; the depth is in [EpubReadingItem.headingLevel]. */
    HEADING,

    /** One `<li>`. */
    LIST_ITEM,

    /** `<blockquote>`. */
    QUOTE,

    /** `<pre>` or `<code>`. */
    CODE,

    /** `<td>` / `<th>`. */
    TABLE_CELL,

    /** `<figcaption>`. */
    CAPTION,

    /** An image; the text is its `alt` (or `aria-label`), empty when it has none. */
    IMAGE,

    /** `epub:type="pagebreak"`: the print edition's page number, not content. */
    PAGE_BREAK,
}

/**
 * One stop in a page's reading order: what a screen reader would announce,
 * in the order it would announce it.
 *
 * ```kotlin
 * for (item in book.readingOrder(page = 3)) {
 *     when (item.role) {
 *         EpubRole.HEADING -> speakHeading(item.text, item.headingLevel)
 *         else -> speak(item.text)
 *     }
 * }
 * ```
 */
public class EpubReadingItem internal constructor(
    /** What this item is. */
    public val role: EpubRole,
    /** The words to announce. Never blank except for an image with no `alt`. */
    public val text: String,
    /** 1..6 for a [EpubRole.HEADING], 0 for everything else. */
    public val headingLevel: Int = 0,
    /**
     * The `epub:type` the source declared (`footnote`, `noteref`, `pagebreak`,
     * ...), or null. EPUB's own semantic vocabulary, passed through as-is.
     */
    public val epubType: String? = null,
) {
    override fun toString(): String =
        "EpubReadingItem($role${if (headingLevel > 0) " h$headingLevel" else ""}: ${text.take(40)})"
}

/** The accessibility facts a box carries from its source element. */
internal class BoxSemantics(
    val role: EpubRole,
    val headingLevel: Int = 0,
    /** `aria-label`, or an image's `alt`: replaces the box's own text. */
    val label: String? = null,
    val epubType: String? = null,
    /** `aria-hidden="true"` or `role="presentation"`: drawn, never announced. */
    val hidden: Boolean = false,
) {
    companion object {
        /** Elements where `type` is HTML's own attribute, not `epub:type`. */
        private val HTML_TYPE_TAGS = setOf(
            "a", "area", "button", "command", "embed", "input", "li", "link",
            "menu", "object", "ol", "param", "script", "source", "style", "track",
        )

        /**
         * Read one element's semantics, or null when it says nothing worth
         * carrying (which is most elements).
         */
        fun of(tag: String, attrs: Map<String, String>, parent: BoxSemantics? = null): BoxSemantics? {
            // The XML reader drops namespace prefixes, so `epub:type` arrives
            // as plain `type`. On the handful of elements where HTML has its
            // own `type` attribute, ignore it.
            val epubType = attrs["type"]?.takeIf { tag !in HTML_TYPE_TAGS }
                ?: attrs["role"]?.takeIf { it.startsWith("doc-") }
                ?: parent?.epubType
            val hidden = parent?.hidden == true ||
                attrs["aria-hidden"]?.trim()?.lowercase() == "true" ||
                attrs["role"]?.trim()?.lowercase() in setOf("presentation", "none")
            val label = attrs["aria-label"]?.takeIf { it.isNotBlank() }
            val role = when {
                epubType?.contains("pagebreak", ignoreCase = true) == true -> EpubRole.PAGE_BREAK
                tag.length == 2 && tag[0] == 'h' && tag[1] in '1'..'6' -> EpubRole.HEADING
                tag == "li" -> EpubRole.LIST_ITEM
                tag == "blockquote" -> EpubRole.QUOTE
                tag == "pre" || tag == "code" -> EpubRole.CODE
                tag == "td" || tag == "th" -> EpubRole.TABLE_CELL
                tag == "figcaption" -> EpubRole.CAPTION
                tag == "img" || tag == "image" -> EpubRole.IMAGE
                else -> EpubRole.TEXT
            }
            val level = if (role == EpubRole.HEADING) tag[1] - '0' else 0
            if (role == EpubRole.TEXT && !hidden && label == null && epubType == null) return null
            return BoxSemantics(role, level, label, epubType, hidden)
        }
    }
}
