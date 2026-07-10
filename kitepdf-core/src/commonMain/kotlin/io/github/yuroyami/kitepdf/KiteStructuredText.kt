package io.github.yuroyami.kitepdf

/**
 * Format-neutral structured text for a [KitePage] — the minimal
 * blocks → lines → text model a viewer needs for extraction, search
 * highlights and selection, without committing to a handler's internals.
 *
 * All geometry is in the page's DISPLAY space: the top-left-origin, y-down
 * `[0, displayWidth] x [0, displayHeight]` box that [KitePage.displayToDeviceBase]
 * maps onto. Because [Rectangle] names its fields PDF-style (y-up), display
 * rectangles here store the y-MIN (the edge nearest the page top) in
 * [Rectangle.bottom] and the y-MAX in [Rectangle.top], keeping
 * `width`/`height` positive.
 */
class KiteStructuredText(val blocks: List<KiteTextBlock>) {

    /** Flattened plain text: line breaks become `\n`, block breaks `\n\n`. */
    val plainText: String by lazy {
        blocks.joinToString("\n\n") { b -> b.lines.joinToString("\n") { it.text } }
    }

    /**
     * Find [needle] in this page's text. Matches may cross line boundaries
     * inside a block: a line break counts as one space, except after a line
     * ending in a hyphen, which joins the halves directly with the hyphen
     * dropped (so a hyphenated "compres-/sion" matches "compression").
     * Matches never cross block boundaries. Case-insensitive comparison uses
     * per-position `regionMatches`, so indices stay aligned for any script.
     *
     * @param pageIndex stamped onto each hit (the model itself is page-local).
     */
    fun search(needle: String, ignoreCase: Boolean = true, pageIndex: Int = -1): List<KiteSearchHit> {
        if (needle.isEmpty()) return emptyList()
        val hits = ArrayList<KiteSearchHit>()
        for (block in blocks) {
            // Concatenate the block's lines; chars map back to (line, char) for quads.
            val sb = StringBuilder()
            val refLine = ArrayList<Int>()  // -1 for a joiner space
            val refChar = ArrayList<Int>()
            for ((li, line) in block.lines.withIndex()) {
                val hyphenJoin = li + 1 < block.lines.size && line.text.endsWith("-")
                if (li > 0 && sb.isNotEmpty()) {
                    // A previous line ending in '-' already joined directly (its
                    // hyphen was dropped); otherwise the break reads as one space.
                    val prevJoined = block.lines[li - 1].text.endsWith("-")
                    if (!prevJoined) { sb.append(' '); refLine.add(-1); refChar.add(-1) }
                }
                val keep = if (hyphenJoin) line.text.length - 1 else line.text.length
                for (k in 0 until keep) { sb.append(line.text[k]); refLine.add(li); refChar.add(k) }
            }
            val text = sb.toString()
            var from = 0
            while (from + needle.length <= text.length) {
                if (!text.regionMatches(from, needle, 0, needle.length, ignoreCase)) { from++; continue }
                val quads = ArrayList<Rectangle>()
                var i = from
                while (i < from + needle.length) {
                    val li = refLine[i]
                    if (li < 0) { i++; continue } // joiner space: no geometry
                    var j = i
                    while (j + 1 < from + needle.length && refLine[j + 1] == li) j++
                    val line = block.lines[li]
                    quads.add(
                        Rectangle(
                            left = line.charEdges[refChar[i]],
                            bottom = line.bounds.bottom,
                            right = line.charEdges[refChar[j] + 1],
                            top = line.bounds.top,
                        ),
                    )
                    i = j + 1
                }
                hits.add(KiteSearchHit(pageIndex, quads, text.substring(from, from + needle.length)))
                from += needle.length
            }
        }
        return hits
    }
}

/** A paragraph-ish group of consecutive lines from one layout block. */
class KiteTextBlock(val lines: List<KiteTextLine>)

/**
 * One laid-out line. [charEdges] has `text.length + 1` display-space x
 * boundaries: `charEdges[i]` is the left edge of char `i`, the final entry
 * the line's right edge — enough to build sub-line highlight quads.
 */
class KiteTextLine(
    val text: String,
    val bounds: Rectangle,
    val charEdges: DoubleArray,
) {
    init {
        require(charEdges.size == text.length + 1) {
            "charEdges must have text.length + 1 entries (got ${charEdges.size} for ${text.length} chars)"
        }
    }
}

/** One search match: display-space [quads] (one per line touched) on page [pageIndex]. */
class KiteSearchHit(
    val pageIndex: Int,
    val quads: List<Rectangle>,
    val text: String,
)
