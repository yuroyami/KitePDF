package io.github.yuroyami.kitepdf.text

import io.github.yuroyami.kitepdf.core.text.Bidi

/**
 * Turns a line of text in visual order, left to right as its glyphs stand on the page, into
 * logical order, as Poppler and MuPDF give it (#913), by [Bidi.logicalOrder]. Most producers
 * write Arabic and Hebrew glyphs left to right, so each right-to-left word reads backwards in
 * content order.
 */
internal object LogicalOrder {

    /** Whether [text] holds a right-to-left letter, without which a line needs no change. */
    fun hasRtl(text: CharSequence): Boolean {
        for (c in text) {
            val t = Bidi.classify(c.code)
            if (t == Bidi.R || t == Bidi.AL) return true
        }
        return false
    }

    /** See [Bidi.logicalOrder]. */
    fun of(text: CharSequence): Pair<IntArray, IntArray>? = Bidi.logicalOrder(text)

    /** [text], a line in visual order, in logical order. */
    fun fromVisual(text: String): String {
        val (order, _) = of(text) ?: return text
        return buildString(text.length) { for (i in order) append(text[i]) }
    }

    /**
     * [text], in logical order, in the visual order the same pass turns back into it: a
     * replacement text joins the visual characters of its line, so it must stand as they do.
     */
    fun toVisual(text: String): String = fromVisual(text)
}
