package io.github.yuroyami.kitepdf.epub

import io.github.yuroyami.kitepdf.core.KiteReadingItem
import io.github.yuroyami.kitepdf.core.KiteRole

/** What a piece of content is, for readers that speak rather than draw. */
@Deprecated(
    "Renamed to KiteRole, which the pages of every format use (#208)",
    ReplaceWith("KiteRole", "io.github.yuroyami.kitepdf.core.KiteRole"),
)
public typealias EpubRole = KiteRole

/** One stop in a page's reading order. */
@Deprecated(
    "Renamed to KiteReadingItem, which the pages of every format give (#208)",
    ReplaceWith("KiteReadingItem", "io.github.yuroyami.kitepdf.core.KiteReadingItem"),
)
public typealias EpubReadingItem = KiteReadingItem

/** The `epub:type` that the source declared, or null. */
@Deprecated("Renamed to sourceType, which a tagged PDF fills too (#208)", ReplaceWith("sourceType"))
public val KiteReadingItem.epubType: String? get() = sourceType

/**
 * A pronunciation that an element gives its text for a speech engine: `ssml:ph`, in the
 * alphabet of the nearest `ssml:alphabet` (#39). One instance per element, so the element's
 * text stays one span however the layout splits it.
 */
internal class SpeechHint(val phoneme: String, val alphabet: String?)

/** The accessibility facts a box carries from its source element. */
internal class BoxSemantics(
    val role: KiteRole,
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
                epubType?.contains("pagebreak", ignoreCase = true) == true -> KiteRole.PAGE_BREAK
                tag.length == 2 && tag[0] == 'h' && tag[1] in '1'..'6' -> KiteRole.HEADING
                tag == "li" -> KiteRole.LIST_ITEM
                tag == "blockquote" -> KiteRole.QUOTE
                tag == "pre" || tag == "code" -> KiteRole.CODE
                tag == "td" || tag == "th" -> KiteRole.TABLE_CELL
                tag == "figcaption" -> KiteRole.CAPTION
                tag == "img" || tag == "image" -> KiteRole.IMAGE
                else -> KiteRole.TEXT
            }
            val level = if (role == KiteRole.HEADING) tag[1] - '0' else 0
            if (role == KiteRole.TEXT && !hidden && label == null && epubType == null) return null
            return BoxSemantics(role, level, label, epubType, hidden)
        }
    }
}
