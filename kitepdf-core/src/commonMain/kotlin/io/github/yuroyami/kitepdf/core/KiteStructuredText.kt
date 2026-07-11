package io.github.yuroyami.kitepdf.core

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
public class KiteStructuredText(public val blocks: List<KiteTextBlock>) {

    /** Flattened plain text: line breaks become `\n`, block breaks `\n\n`. */
    public val plainText: String by lazy {
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
    public fun search(needle: String, ignoreCase: Boolean = true, pageIndex: Int = -1): List<KiteSearchHit> {
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
                val entries = (from until from + needle.length)
                    .filter { refLine[it] >= 0 } // joiner spaces carry no geometry
                    .map { refLine[it] to refChar[it] }
                hits.add(KiteSearchHit(pageIndex, lineQuads(block, entries), text.substring(from, from + needle.length)))
                from += needle.length
            }
        }
        return hits
    }

    /* ─── Selection support (T-80) ───────────────────────────────────────── */

    /**
     * One entry per positioned char, in reading order (blocks then lines then
     * chars) — the index space [charIndexAt], [textRange] and [quadsFor] share.
     */
    private class CharRef(val block: Int, val line: Int, val char: Int)

    private val flatChars: List<CharRef> by lazy {
        buildList {
            for ((bi, block) in blocks.withIndex()) {
                for ((li, line) in block.lines.withIndex()) {
                    for (ci in line.text.indices) add(CharRef(bi, li, ci))
                }
            }
        }
    }

    /** Number of positioned chars ([charIndexAt]'s index space). */
    public val charCount: Int get() = flatChars.size

    /**
     * The flattened index of the char at a display-space point, or null when
     * the point is on no text line. Within a line, x clamps to the nearest
     * char, which is what a selection drag wants at the line's ends.
     */
    public fun charIndexAt(x: Double, y: Double): Int? {
        var best = -1
        var bestDx = Double.MAX_VALUE
        for ((i, ref) in flatChars.withIndex()) {
            val line = blocks[ref.block].lines[ref.line]
            // Display rects keep y-min in `bottom` (y grows downward).
            if (y < line.bounds.bottom || y > line.bounds.top) continue
            val left = line.charEdges[ref.char]
            val right = line.charEdges[ref.char + 1]
            if (x >= left && x <= right) return i
            val dx = if (x < left) left - x else x - right
            if (dx < bestDx) {
                bestDx = dx
                best = i
            }
        }
        return if (best >= 0) best else null
    }

    /**
     * The text of the inclusive flattened range [start]..[endInclusive], with
     * line breaks as `\n` and block breaks as `\n\n` (matching [plainText]).
     */
    public fun textRange(start: Int, endInclusive: Int): String {
        if (flatChars.isEmpty()) return ""
        val a = start.coerceIn(0, flatChars.size - 1)
        val b = endInclusive.coerceIn(a, flatChars.size - 1)
        return buildString {
            var prev: CharRef? = null
            for (i in a..b) {
                val ref = flatChars[i]
                val p = prev
                if (p != null) {
                    if (ref.block != p.block) append("\n\n")
                    else if (ref.line != p.line) append('\n')
                }
                append(blocks[ref.block].lines[ref.line].text[ref.char])
                prev = ref
            }
        }
    }

    /**
     * Display-space quads (one per line touched) for the inclusive flattened
     * range — the same walker search hits use.
     */
    public fun quadsFor(start: Int, endInclusive: Int): List<Rectangle> {
        if (flatChars.isEmpty()) return emptyList()
        val a = start.coerceIn(0, flatChars.size - 1)
        val b = endInclusive.coerceIn(a, flatChars.size - 1)
        val out = ArrayList<Rectangle>()
        var i = a
        while (i <= b) {
            val block = flatChars[i].block
            var j = i
            while (j + 1 <= b && flatChars[j + 1].block == block) j++
            out += lineQuads(blocks[block], (i..j).map { flatChars[it].line to flatChars[it].char })
            i = j + 1
        }
        return out
    }

    /**
     * Merges consecutive same-line `(lineIndex, charIndex)` entries of one
     * block into per-line quads spanning their char edges.
     */
    private fun lineQuads(block: KiteTextBlock, entries: List<Pair<Int, Int>>): List<Rectangle> {
        val quads = ArrayList<Rectangle>()
        var i = 0
        while (i < entries.size) {
            val li = entries[i].first
            var j = i
            while (j + 1 < entries.size && entries[j + 1].first == li) j++
            val line = block.lines[li]
            quads.add(
                Rectangle(
                    left = line.charEdges[entries[i].second],
                    bottom = line.bounds.bottom,
                    right = line.charEdges[entries[j].second + 1],
                    top = line.bounds.top,
                ),
            )
            i = j + 1
        }
        return quads
    }
}

/** A paragraph-ish group of consecutive lines from one layout block. */
public class KiteTextBlock(public val lines: List<KiteTextLine>)

/**
 * One laid-out line. [charEdges] has `text.length + 1` display-space x
 * boundaries: `charEdges[i]` is the left edge of char `i`, the final entry
 * the line's right edge — enough to build sub-line highlight quads.
 */
public class KiteTextLine(
    public val text: String,
    public val bounds: Rectangle,
    public val charEdges: DoubleArray,
) {
    init {
        require(charEdges.size == text.length + 1) {
            "charEdges must have text.length + 1 entries (got ${charEdges.size} for ${text.length} chars)"
        }
    }
}

/** One search match: display-space [quads] (one per line touched) on page [pageIndex]. */
public class KiteSearchHit(
    public val pageIndex: Int,
    public val quads: List<Rectangle>,
    public val text: String,
)
