package io.github.yuroyami.kitepdf.epub.css

/**
 * The multi-column properties of one element (CSS Multi-column Layout 1, #34): the columns it
 * makes as a container, and whether it spans them as a child. None of them is inherited.
 *
 * @property count `column-count`, or null for `auto`.
 * @property width `column-width` in points, or null for `auto`.
 * @property rule `column-rule`, or null when its style draws nothing.
 * @property spanAll `column-span: all`: the element takes the whole width between two column sets.
 */
internal data class Columns(
    val count: Int? = null,
    val width: Double? = null,
    val rule: Edge? = null,
    val spanAll: Boolean = false,
) {
    /**
     * How many columns fit [room] with [gap] between them (3). A width alone fits as many as it
     * can, a count alone gives that many, and both give at most the count.
     */
    fun countIn(room: Double, gap: Double): Int {
        val byWidth = width?.let { w -> if (w <= 0.0) 1 else ((room + gap) / (w + gap)).toInt().coerceAtLeast(1) }
        return when {
            count != null && byWidth != null -> minOf(count, byWidth)
            count != null -> count
            byWidth != null -> byWidth
            else -> 1
        }.coerceIn(1, MAX_COLUMNS)
    }

    companion object {
        /** The most columns one container makes, which bounds a hostile count. */
        const val MAX_COLUMNS = 64
    }
}
