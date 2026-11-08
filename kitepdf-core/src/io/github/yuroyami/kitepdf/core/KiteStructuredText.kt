package io.github.yuroyami.kitepdf.core

/**
 * Format-neutral structured text for a [KitePage]: the minimal
 * blocks → lines → text model a viewer needs for extraction, search
 * highlights and selection, without committing to a handler's internals.
 *
 * All geometry is in the page's DISPLAY space: the top-left-origin, y-down
 * `[0, displayWidth] x [0, displayHeight]` box that [KitePage.displayToDeviceBase]
 * maps onto. Because [KiteRectangle] names its fields PDF-style (y-up), display
 * rectangles here store the y-MIN (the edge nearest the page top) in
 * [KiteRectangle.bottom] and the y-MAX in [KiteRectangle.top], keeping
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
    public fun search(needle: String, ignoreCase: Boolean = true, pageIndex: Int = -1): List<KiteSearchHit> =
        search(needle, KiteSearchOptions(ignoreCase = ignoreCase), pageIndex, location = null)

    /**
     * [search] with an explicit page [location] in the current layout. The caller owns
     * the page coordinate: structured text alone cannot recover its chapter or page.
     * [pageIndex] keeps its caller-supplied meaning and may be -1 when unknown.
     * Neither the location nor the quads are a content anchor that survives reflow.
     */
    public fun search(
        needle: String,
        ignoreCase: Boolean = true,
        pageIndex: Int = -1,
        location: KiteLocation?,
    ): List<KiteSearchHit> = search(needle, KiteSearchOptions(ignoreCase = ignoreCase), pageIndex, location)

    /**
     * [search] by [options]: case, whole words and diacritics (#943). Hits keep the quads of the
     * page's own characters, marks a fold dropped included.
     */
    public fun search(
        needle: String,
        options: KiteSearchOptions,
        pageIndex: Int = -1,
        location: KiteLocation? = null,
    ): List<KiteSearchHit> {
        if (needle.isEmpty()) return emptyList()
        val needle = expandLigatures(needle).let { if (options.ignoreDiacritics) foldDiacritics(it, null) else it }
        if (needle.isEmpty()) return emptyList()
        val hits = ArrayList<KiteSearchHit>()
        for (block in blocks) {
            // Concatenate the block's lines; chars map back to (line, char) for quads.
            val sb = StringBuilder()
            val refLine = ArrayList<Int>()  // -1 for a joiner space
            val refChar = ArrayList<Int>()
            // A line that says how it ended joins the next as copyText joins them: a space, nothing
            // after a hyphen the layout added, which goes, or nothing at all. A HARD line, as every
            // line of a fixed page is, keeps the old guess from a trailing hyphen (#919).
            fun dropsHyphen(line: KiteTextLine): Boolean = when (line.end) {
                KiteLineEnd.HARD -> line.text.endsWith("-")
                KiteLineEnd.HYPHEN -> line.text.endsWith("-")
                KiteLineEnd.SPACE, KiteLineEnd.NONE -> false
            }
            fun joinsDirectly(line: KiteTextLine): Boolean = when (line.end) {
                KiteLineEnd.HARD -> line.text.endsWith("-")
                KiteLineEnd.HYPHEN, KiteLineEnd.NONE -> true
                KiteLineEnd.SPACE -> false
            }
            for ((li, line) in block.lines.withIndex()) {
                val last = li + 1 == block.lines.size
                if (li > 0 && sb.isNotEmpty() && !joinsDirectly(block.lines[li - 1])) {
                    sb.append(' '); refLine.add(-1); refChar.add(-1)
                }
                val keep = if (!last && dropsHyphen(line)) line.text.length - 1 else line.text.length
                for (k in 0 until keep) {
                    // A ligature presentation form reads as its letters, each mapped back to it,
                    // so "find" matches a page that draws U+FB01 (#807).
                    val letters = LIGATURES[line.text[k]]
                    if (letters == null) { sb.append(line.text[k]); refLine.add(li); refChar.add(k) }
                    else for (c in letters) { sb.append(c); refLine.add(li); refChar.add(k) }
                }
            }
            val text = sb.toString()
            // With diacritics ignored the match runs on the folded text, and [origin] takes each of its
            // characters back to the text's own.
            val origin: IntArray?
            val haystack = if (options.ignoreDiacritics) {
                val map = ArrayList<Int>(text.length)
                foldDiacritics(text, map).also { origin = map.toIntArray() }
            } else {
                origin = null
                text
            }
            var from = 0
            while (from + needle.length <= haystack.length) {
                if (!haystack.regionMatches(from, needle, 0, needle.length, options.ignoreCase)) { from++; continue }
                val start = origin?.get(from) ?: from
                var end = (origin?.get(from + needle.length - 1) ?: (from + needle.length - 1)) + 1
                // The marks the fold dropped after the last character belong to it.
                if (origin != null) while (end < text.length && DiacriticFolding.isMark(text[end])) end++
                if (options.wholeWord && !atWordBoundaries(text, start, end, needle)) { from++; continue }
                val entries = (start until end)
                    .filter { refLine[it] >= 0 } // joiner spaces carry no geometry
                    .map { refLine[it] to refChar[it] }
                hits.add(KiteSearchHit(pageIndex, lineQuads(block, entries), text.substring(start, end), location))
                from += needle.length
            }
        }
        return hits
    }

    /**
     * [text] with each precomposed letter as its base and the non-spacing marks dropped: the
     * canonical decomposition with category Mn removed, as pdf.js folds diacritics. [origin] gets the
     * index in [text] of each character of the result.
     */
    private fun foldDiacritics(text: String, origin: MutableList<Int>?): String {
        val sb = StringBuilder(text.length)
        for ((i, c) in text.withIndex()) {
            if (DiacriticFolding.isMark(c)) continue
            sb.append(DiacriticFolding.base(c))
            origin?.add(i)
        }
        return sb.toString()
    }

    /**
     * Whether the match of [needle] over [start] until [end] of [text] has no letter or digit just
     * outside it. A side where the needle ends in a script written without spaces, as Chinese,
     * Japanese and Thai are, has no boundary to find, so it always counts.
     */
    private fun atWordBoundaries(text: String, start: Int, end: Int, needle: String): Boolean {
        fun wordChar(c: Char) = c.isLetterOrDigit() || DiacriticFolding.isMark(c)
        val before = start == 0 || !wordChar(text[start - 1]) || unspaced(needle.first())
        val after = end >= text.length || !wordChar(text[end]) || unspaced(needle.last())
        return before && after
    }

    /** CJK, kana, Thai, Lao, Myanmar and Khmer, and any character outside the BMP. Korean, which spaces its words, is not. */
    private fun unspaced(c: Char): Boolean = c.code in 0x2E80..0x9FFF ||
        c.code in 0xF900..0xFAFF || c.code in 0xFF66..0xFF9F || c.code in 0x0E00..0x0EFF || c.code in 0x1000..0x109F ||
        c.code in 0x1780..0x17FF || c.isSurrogate()

    /* ─── Selection support ───────────────────────────────────────── */

    /**
     * One entry per positioned char, in reading order (blocks then lines then
     * chars): the index space [charIndexAt], [textRange] and [quadsFor] share.
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
            // Display rects keep y-min in `bottom` (y grows downward). A
            // vertical line runs down the page, so the roles swap: the band
            // test is on x and the char walk is on y.
            val along = if (line.vertical) y else x
            val across = if (line.vertical) x else y
            val bandLow = if (line.vertical) line.bounds.left else line.bounds.bottom
            val bandHigh = if (line.vertical) line.bounds.right else line.bounds.top
            if (across < bandLow || across > bandHigh) continue
            val edgeA = line.charEdges[ref.char]
            val edgeB = line.charEdges[ref.char + 1]
            val start = minOf(edgeA, edgeB)
            val end = maxOf(edgeA, edgeB)
            if (along >= start && along <= end) return i
            val dx = if (along < start) start - along else along - end
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
     * Where a selection that starts at the flattened char [index] begins, or where one that
     * ends at it ends when [after] is true: the char's logical edge, as a caret across its line.
     * Lines keep their own direction, so the start of a right-to-left run is its right edge,
     * and a caret in a vertical line runs across the column. Null for an index with no char.
     */
    public fun caretAt(index: Int, after: Boolean): KiteCaret? {
        val ref = flatChars.getOrNull(index) ?: return null
        val line = blocks[ref.block].lines[ref.line]
        val edge = line.charEdges[if (after) ref.char + 1 else ref.char]
        val inside = line.charEdges[if (after) ref.char else ref.char + 1]
        return if (line.vertical) {
            KiteCaret(edge, inside, line.bounds.left, line.bounds.right, vertical = true)
        } else {
            KiteCaret(edge, inside, line.bounds.bottom, line.bounds.top, vertical = false)
        }
    }

    /**
     * The text of the inclusive flattened range as a reader copies it. Blocks
     * are separated by `\n\n`, and the lines of one block join as their
     * [KiteTextLine.end] says: a paragraph the layout wrapped copies as one
     * line, a word it hyphenated copies whole, and a break the text holds
     * stays `\n`. Lines that end [KiteLineEnd.HARD], as every fixed-layout
     * line does, copy as [textRange] gives them.
     */
    public fun copyText(start: Int, endInclusive: Int): String {
        if (flatChars.isEmpty()) return ""
        val a = start.coerceIn(0, flatChars.size - 1)
        val b = endInclusive.coerceIn(a, flatChars.size - 1)
        return buildString {
            var prev: CharRef? = null
            for (i in a..b) {
                val ref = flatChars[i]
                val line = blocks[ref.block].lines[ref.line]
                val p = prev
                if (p != null && ref.block != p.block) {
                    append("\n\n")
                } else if (p != null && ref.line != p.line) {
                    when (blocks[p.block].lines[p.line].end) {
                        KiteLineEnd.HARD -> append('\n')
                        KiteLineEnd.SPACE -> append(' ')
                        KiteLineEnd.HYPHEN, KiteLineEnd.NONE -> {}
                    }
                }
                prev = ref
                // The hyphen the layout added is not part of the text.
                if (line.end == KiteLineEnd.HYPHEN && ref.char == line.text.lastIndex && line.text.endsWith('-')) continue
                append(line.text[ref.char])
            }
        }
    }

    /**
     * Display-space quads (one per line touched) for the inclusive flattened
     * range. Search hits use the same walker.
     */
    public fun quadsFor(start: Int, endInclusive: Int): List<KiteRectangle> {
        if (flatChars.isEmpty()) return emptyList()
        val a = start.coerceIn(0, flatChars.size - 1)
        val b = endInclusive.coerceIn(a, flatChars.size - 1)
        val out = ArrayList<KiteRectangle>()
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
    private fun lineQuads(block: KiteTextBlock, entries: List<Pair<Int, Int>>): List<KiteRectangle> {
        val quads = ArrayList<KiteRectangle>()
        var i = 0
        while (i < entries.size) {
            val li = entries[i].first
            var j = i
            while (j + 1 < entries.size && entries[j + 1].first == li) j++
            val line = block.lines[li]
            var from = Double.POSITIVE_INFINITY
            var to = Double.NEGATIVE_INFINITY
            for (k in i..j) {
                val char = entries[k].second
                from = minOf(from, line.charEdges[char], line.charEdges[char + 1])
                to = maxOf(to, line.charEdges[char], line.charEdges[char + 1])
            }
            quads.add(
                if (line.vertical) {
                    KiteRectangle(line.bounds.left, from, line.bounds.right, to)
                } else {
                    KiteRectangle(from, line.bounds.bottom, to, line.bounds.top)
                },
            )
            i = j + 1
        }
        return quads
    }
}

/** A paragraph-ish group of consecutive lines from one layout block. */
public class KiteTextBlock(public val lines: List<KiteTextLine>)

/**
 * How a [KiteTextLine] ends, which decides how [KiteStructuredText.copyText]
 * joins it to the next line of its block.
 */
public enum class KiteLineEnd {
    /** The text itself breaks the line, as a `<br>` does, or the line is fixed on its page: copied text keeps a line break. */
    HARD,

    /** The layout wrapped the line at a space: copied text joins it to the next line with one space. */
    SPACE,

    /** The layout wrapped the line inside a word and added the hyphen: copied text leaves the hyphen out and joins the halves. */
    HYPHEN,

    /** The layout wrapped the line where the text has no space, as between two CJK characters: copied text joins the lines directly. */
    NONE,
}

/**
 * One laid-out line. [charEdges] has `text.length + 1` display-space
 * boundaries: `charEdges[i]` is where char `i` starts, the final entry where
 * the line ends. That is enough to build sub-line highlight quads.
 * Entries follow the text's logical order, so right-to-left runs may have
 * descending edges and positioned runs may change direction. Hit tests use
 * each adjacent pair's extent; highlights enclose every selected pair.
 *
 * They are x boundaries on a normal line. On a [vertical] one (Japanese
 * tategaki, where a "line" is a column running down the page) they are y
 * boundaries instead, and [bounds] gives the column's width.
 */
public class KiteTextLine(
    public val text: String,
    public val bounds: KiteRectangle,
    public val charEdges: DoubleArray,
    /** True when this line is a column of vertical text: see [charEdges]. */
    public val vertical: Boolean = false,
    /** How the line ends, for [KiteStructuredText.copyText]. A reflowable page sets it; a fixed page keeps [KiteLineEnd.HARD]. */
    public val end: KiteLineEnd = KiteLineEnd.HARD,
) {
    init {
        require(charEdges.size == text.length + 1) {
            "charEdges must have text.length + 1 entries (got ${charEdges.size} for ${text.length} chars)"
        }
    }
}

/**
 * A caret at a char boundary, in display space. [position] is where it sits along its line: x on
 * a horizontal line, y in a [vertical] one. It spans the line from [from] to [to] across it. [inside]
 * is the other edge of the char the caret belongs to, so it gives the direction into that char.
 */
public class KiteCaret(
    public val position: Double,
    public val inside: Double,
    public val from: Double,
    public val to: Double,
    public val vertical: Boolean,
)

/**
 * One search match: display-space [quads] (one per line touched) at [location].
 * The location is a coordinate in the current layout, so it and the quads must
 * be recomputed when that layout changes. It is not a reflow-stable content anchor.
 *
 * Document search keeps [pageIndex] as a global page index. A standalone
 * [KiteStructuredText.search] keeps the caller's supplied index, or -1 when unknown.
 * Located consumers use [location] when present, even if the old index is stale.
 */
/**
 * How a search matches its needle (#943). The defaults are the search's own: case ignored, any
 * position, diacritics counted.
 *
 * @property wholeWord a match counts only when no letter or digit sits just before or after it, so
 *   "art" finds "art," but not "start". A side of the needle in a script written without spaces,
 *   Chinese, Japanese, Thai, Lao, Myanmar or Khmer, needs no boundary: there the option changes
 *   nothing.
 * @property ignoreDiacritics the text and the needle are both folded by canonical decomposition with
 *   the non-spacing marks removed, as pdf.js folds them, so "resume" finds "résumé" whether the page
 *   stores é precomposed or as e and U+0301. Every mark of category Mn goes, the Arabic harakat and
 *   the Hebrew points among them; letters that decompose to nothing, such as ø and ł, stay.
 */
public data class KiteSearchOptions(
    val ignoreCase: Boolean = true,
    val wholeWord: Boolean = false,
    val ignoreDiacritics: Boolean = false,
)

public class KiteSearchHit(
    public val pageIndex: Int,
    public val quads: List<KiteRectangle>,
    public val text: String,
    public val location: KiteLocation?,
) {
    /** An unlocated result; kept with its original signature for existing callers. */
    public constructor(pageIndex: Int, quads: List<KiteRectangle>, text: String) :
        this(pageIndex, quads, text, location = null)

    /** A located result whose global page index is unknown. */
    public constructor(location: KiteLocation, quads: List<KiteRectangle>, text: String) :
        this(-1, quads, text, location)
}

/** The Latin ligatures U+FB00 to U+FB06 and the letters of their compatibility decompositions (#807). */
internal val LIGATURES: Map<Char, String> = mapOf(
    '\uFB00' to "ff", '\uFB01' to "fi", '\uFB02' to "fl", '\uFB03' to "ffi", '\uFB04' to "ffl",
    '\uFB05' to "st", '\uFB06' to "st",
)

/** [text] with each ligature of [LIGATURES] written as its letters. */
internal fun expandLigatures(text: String): String {
    if (text.none { it in '\uFB00'..'\uFB06' }) return text
    val sb = StringBuilder(text.length + 4)
    for (c in text) sb.append(LIGATURES[c] ?: c.toString())
    return sb.toString()
}
