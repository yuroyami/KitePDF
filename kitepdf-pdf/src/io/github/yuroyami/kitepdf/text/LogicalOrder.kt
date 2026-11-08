package io.github.yuroyami.kitepdf.text

import io.github.yuroyami.kitepdf.core.text.Bidi

/**
 * Turns a line of text in visual order, left to right as its glyphs stand on the page, into
 * logical order, as Poppler and MuPDF give it (#913). Most producers write Arabic and Hebrew
 * glyphs left to right, so each right-to-left word reads backwards in content order.
 *
 * The bidi levels of the visual sequence are resolved as if it were logical (UAX #9), and rule L2
 * is applied to them: each run at an odd level comes out reversed into logical order, and the
 * numbers and Latin words inside it keep their own order, since L2 reverses them twice.
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

    /**
     * The order of [text]'s characters in logical order, as indices into [text], and the bidi
     * level of each character of [text], or null when [text] has no right-to-left letter. The line
     * reads right to left when it holds at least as many right-to-left letters as left-to-right
     * ones. A surrogate pair moves as one, high surrogate first.
     */
    fun of(text: CharSequence): Pair<IntArray, IntArray>? {
        if (!hasRtl(text)) return null
        val starts = ArrayList<Int>(text.length)
        val cps = ArrayList<Int>(text.length)
        var i = 0
        while (i < text.length) {
            val pair = text[i].isHighSurrogate() && i + 1 < text.length && text[i + 1].isLowSurrogate()
            starts += i
            cps += if (pair) 0x10000 + ((text[i].code - 0xD800) shl 10) + (text[i + 1].code - 0xDC00) else text[i].code
            i += if (pair) 2 else 1
        }
        val points = cps.toIntArray()
        var rtl = 0
        var ltr = 0
        for (cp in points) when (Bidi.classify(cp)) {
            Bidi.R, Bidi.AL -> rtl++
            Bidi.L -> ltr++
        }
        // In visual order a right-to-left base follows its combining marks, as a shaper reverses a
        // whole cluster. Rule W1 gives a mark the class of the character before it, which here is
        // not its base, so a run of marks before a right-to-left letter takes that letter's class.
        val classed = points.copyOf()
        var m = points.size - 1
        while (m >= 0) {
            val t = Bidi.classify(points[m])
            if (t == Bidi.R || t == Bidi.AL) {
                var k = m - 1
                while (k >= 0 && Bidi.classify(points[k]) == Bidi.NSM) classed[k--] = points[m]
                m = k
            } else {
                m--
            }
        }
        val levels = Bidi.resolveLevels(classed, if (rtl >= ltr) 1 else 0)
        val order = Bidi.reorderVisually(levels)
        val charOrder = IntArray(text.length)
        val charLevels = IntArray(text.length)
        var at = 0
        for (k in order) {
            val from = starts[k]
            val to = if (k + 1 < starts.size) starts[k + 1] else text.length
            for (c in from until to) charOrder[at++] = c
        }
        for (k in starts.indices) {
            val to = if (k + 1 < starts.size) starts[k + 1] else text.length
            for (c in starts[k] until to) charLevels[c] = levels[k]
        }
        return charOrder to charLevels
    }

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
