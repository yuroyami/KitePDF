package io.github.yuroyami.kitepdf.epub.css

/** One bound of a grid track's size (CSS Grid Layout 1, 7.2). */
internal sealed class GridSize {
    class Fixed(val pt: Double) : GridSize()
    class Percent(val fraction: Double) : GridSize()
    /** A share of the free space, `1fr`. Only a track's max can be one. */
    class Fraction(val fr: Double) : GridSize()
    object Auto : GridSize()
    object MinContent : GridSize()
    object MaxContent : GridSize()
}

/** A track size, `minmax(min, max)`. A single size is its own min and max, and `1fr` is `minmax(auto, 1fr)`. */
internal data class GridTrack(val min: GridSize, val max: GridSize) {
    companion object {
        val AUTO = GridTrack(GridSize.Auto, GridSize.Auto)
    }
}

/**
 * A track list: the tracks, and at [fillAt] a `repeat(auto-fill, ...)` or `repeat(auto-fit, ...)`
 * group [fill], repeated as often as the container fits it (7.2.3.2).
 */
internal data class GridTracks(val tracks: List<GridTrack> = emptyList(), val fill: List<GridTrack> = emptyList(), val fillAt: Int = 0)

/** One edge of an item's grid area: automatic, a line number, which counts from the end when negative, or a span (8.3). */
internal sealed class GridLine {
    object Auto : GridLine()
    class Line(val n: Int) : GridLine()
    class Span(val n: Int) : GridLine()
}

/**
 * The grid properties of one element (#35): the tracks it uses as a grid container, and the
 * lines it uses as a grid item. The gaps and most alignment come from [FlexStyle], which a grid
 * shares with a flex container. None of them is inherited.
 */
internal data class GridStyle(
    val columns: GridTracks = GridTracks(),
    val rows: GridTracks = GridTracks(),
    val autoColumns: GridTrack = GridTrack.AUTO,
    val autoRows: GridTrack = GridTrack.AUTO,
    /** `justify-items`: where an item sits across its area on the inline axis. */
    val justifyItems: FlexAlign = FlexAlign.STRETCH,
    val columnStart: GridLine = GridLine.Auto,
    val columnEnd: GridLine = GridLine.Auto,
    val rowStart: GridLine = GridLine.Auto,
    val rowEnd: GridLine = GridLine.Auto,
    /** `justify-self`; AUTO takes the container's [justifyItems]. */
    val justifySelf: FlexAlign = FlexAlign.AUTO,
)

/**
 * Reads the grid properties into a [GridStyle]. A value it does not understand changes nothing.
 * Named lines and areas are not read, so an item that names one is placed automatically.
 */
internal object GridValues {

    val PROPERTIES = setOf(
        "grid-template-columns", "grid-template-rows", "grid-auto-columns", "grid-auto-rows",
        "justify-items", "justify-self",
        "grid-column-start", "grid-column-end", "grid-row-start", "grid-row-end",
        "grid-column", "grid-row", "grid-area",
    )

    /** [style] with [prop] set to [value], or null when the value is not valid. [length] reads a length in points. */
    fun apply(style: GridStyle, prop: String, value: String, length: (String) -> Double?): GridStyle? {
        val v = value.trim().lowercase()
        return when (prop) {
            "grid-template-columns" -> tracks(v, length)?.let { style.copy(columns = it) }
            "grid-template-rows" -> tracks(v, length)?.let { style.copy(rows = it) }
            "grid-auto-columns" -> tracks(v, length)?.tracks?.firstOrNull()?.let { style.copy(autoColumns = it) }
            "grid-auto-rows" -> tracks(v, length)?.tracks?.firstOrNull()?.let { style.copy(autoRows = it) }
            "justify-items" -> align(v.split(WHITESPACE).last())?.takeIf { it != FlexAlign.AUTO }?.let { style.copy(justifyItems = it) }
            "justify-self" -> align(v.split(WHITESPACE).last())?.let { style.copy(justifySelf = it) }
            "grid-column-start" -> line(v)?.let { style.copy(columnStart = it) }
            "grid-column-end" -> line(v)?.let { style.copy(columnEnd = it) }
            "grid-row-start" -> line(v)?.let { style.copy(rowStart = it) }
            "grid-row-end" -> line(v)?.let { style.copy(rowEnd = it) }
            "grid-column" -> pair(v)?.let { (a, b) -> style.copy(columnStart = a, columnEnd = b) }
            "grid-row" -> pair(v)?.let { (a, b) -> style.copy(rowStart = a, rowEnd = b) }
            "grid-area" -> {
                // row-start / column-start / row-end / column-end
                val parts = v.split('/').map { it.trim() }
                val lines = parts.map { line(it) ?: return null }
                if (lines.isEmpty() || lines.size > 4) return null
                fun at(i: Int, fallback: GridLine) = lines.getOrElse(i) { fallback }
                style.copy(
                    rowStart = at(0, GridLine.Auto), columnStart = at(1, GridLine.Auto),
                    rowEnd = at(2, GridLine.Auto), columnEnd = at(3, GridLine.Auto),
                )
            }
            else -> null
        }
    }

    private fun align(v: String): FlexAlign? = when (v) {
        "auto" -> FlexAlign.AUTO
        "stretch", "normal" -> FlexAlign.STRETCH
        "start", "self-start", "flex-start", "left" -> FlexAlign.START
        "end", "self-end", "flex-end", "right" -> FlexAlign.END
        "center" -> FlexAlign.CENTER
        "baseline" -> FlexAlign.BASELINE
        else -> null
    }

    /** `start / end`, or a single `start` whose end is automatic. */
    private fun pair(v: String): Pair<GridLine, GridLine>? {
        val parts = v.split('/').map { it.trim() }
        if (parts.isEmpty() || parts.size > 2) return null
        val start = line(parts[0]) ?: return null
        val end = parts.getOrNull(1)?.let { line(it) ?: return null } ?: GridLine.Auto
        return start to end
    }

    /** A line: `auto`, an integer, or `span` with an integer. A name reads as `auto`. */
    private fun line(v: String): GridLine? {
        val words = v.trim().split(WHITESPACE).filter { it.isNotEmpty() }
        if (words.isEmpty()) return null
        if (words.size == 1 && words[0] == "auto") return GridLine.Auto
        val span = words.contains("span")
        val number = words.firstNotNullOfOrNull { it.toIntOrNull() }
        return when {
            span -> GridLine.Span((number ?: 1).coerceAtLeast(1))
            number != null && number != 0 -> GridLine.Line(number)
            number == 0 -> null
            // A named line is not read, so the item is placed automatically.
            else -> GridLine.Auto
        }
    }

    /** A track list, with `repeat()` expanded, or null when it is not one. `none` is an empty list. */
    fun tracks(v: String, length: (String) -> Double?): GridTracks? {
        if (v == "none") return GridTracks()
        val tokens = tokens(v) ?: return null
        val out = ArrayList<GridTrack>()
        var fill = emptyList<GridTrack>()
        var fillAt = 0
        for (t in tokens) {
            if (t.startsWith("repeat(") && t.endsWith(")")) {
                val inside = t.removePrefix("repeat(").dropLast(1)
                val comma = topLevelComma(inside)
                if (comma < 0) return null
                val count = inside.substring(0, comma).trim()
                val group = tokens(inside.substring(comma + 1).trim())?.map { track(it, length) ?: return null } ?: return null
                if (group.isEmpty()) return null
                if (count == "auto-fill" || count == "auto-fit") {
                    if (fill.isNotEmpty()) return null
                    fill = group
                    fillAt = out.size
                } else {
                    val n = count.toIntOrNull()?.takeIf { it in 1..MAX_REPEAT } ?: return null
                    repeat(n) { out += group }
                }
            } else {
                out += track(t, length) ?: return null
            }
        }
        return GridTracks(out, fill, fillAt)
    }

    /** One track: a size, `minmax(a, b)` or `fit-content(x)`. */
    private fun track(t: String, length: (String) -> Double?): GridTrack? {
        if (t.startsWith("minmax(") && t.endsWith(")")) {
            val inside = t.removePrefix("minmax(").dropLast(1)
            val comma = topLevelComma(inside)
            if (comma < 0) return null
            val min = size(inside.substring(0, comma).trim(), length) ?: return null
            val max = size(inside.substring(comma + 1).trim(), length) ?: return null
            // A fraction cannot be a minimum (7.2.3.3).
            if (min is GridSize.Fraction) return null
            return GridTrack(min, max)
        }
        if (t.startsWith("fit-content(") && t.endsWith(")")) {
            val limit = size(t.removePrefix("fit-content(").dropLast(1).trim(), length) ?: return null
            return GridTrack(GridSize.Auto, limit)
        }
        val s = size(t, length) ?: return null
        return if (s is GridSize.Fraction) GridTrack(GridSize.Auto, s) else GridTrack(s, s)
    }

    private fun size(t: String, length: (String) -> Double?): GridSize? = when {
        t == "auto" -> GridSize.Auto
        t == "min-content" -> GridSize.MinContent
        t == "max-content" -> GridSize.MaxContent
        t.endsWith("fr") -> t.dropLast(2).trim().toDoubleOrNull()?.takeIf { it >= 0.0 && it.isFinite() }?.let { GridSize.Fraction(it) }
        t.endsWith("%") -> t.dropLast(1).trim().toDoubleOrNull()?.takeIf { it >= 0.0 && it.isFinite() }?.let { GridSize.Percent(it / 100.0) }
        else -> (if (t == "0") 0.0 else length(t))?.takeIf { it >= 0.0 && it.isFinite() }?.let { GridSize.Fixed(it) }
    }

    /**
     * The words of a track list, a function call with its parentheses kept whole, and without
     * `[names]`, which are not read. Null when the brackets do not balance.
     */
    private fun tokens(v: String): List<String>? {
        val out = ArrayList<String>()
        val sb = StringBuilder()
        var depth = 0
        var bracket = false
        fun end() { if (sb.isNotEmpty()) { out += sb.toString(); sb.clear() } }
        for (c in v) {
            when {
                bracket -> if (c == ']') bracket = false
                c == '[' && depth == 0 -> { end(); bracket = true }
                c == '(' -> { depth++; sb.append(c) }
                c == ')' -> { depth--; if (depth < 0) return null; sb.append(c) }
                c.isWhitespace() && depth == 0 -> end()
                c.isWhitespace() -> if (sb.isNotEmpty() && sb.last() != ' ') sb.append(' ')
                else -> sb.append(c)
            }
        }
        if (depth != 0 || bracket) return null
        end()
        return out
    }

    private fun topLevelComma(s: String): Int {
        var depth = 0
        for ((i, c) in s.withIndex()) when (c) {
            '(' -> depth++
            ')' -> depth--
            ',' -> if (depth == 0) return i
        }
        return -1
    }

    /** The most tracks one `repeat()` makes, which bounds a hostile count. */
    const val MAX_REPEAT = 1000

    private val WHITESPACE = Regex("\\s+")
}
