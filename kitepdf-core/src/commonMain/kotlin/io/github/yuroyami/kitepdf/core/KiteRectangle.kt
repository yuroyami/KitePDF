package io.github.yuroyami.kitepdf.core

import io.github.yuroyami.kitepdf.core.parser.PdfArray
import io.github.yuroyami.kitepdf.core.parser.PdfInt
import io.github.yuroyami.kitepdf.core.parser.PdfReal

/** PDF rectangle: [left, bottom, right, top] in user-space units. */
public data class KiteRectangle(val left: Double, val bottom: Double, val right: Double, val top: Double) {
    public val width: Double get() = right - left
    public val height: Double get() = top - bottom

    /**
     * The same rectangle with its corners sorted, so `left <= right` and `bottom <= top`.
     *
     * ISO 32000-1 §7.9.5 defines a rectangle array as **two diagonally opposite corners in any
     * order**, and requires the consumer to normalise. Plenty of producers write `[x2 y2 x1 y1]`.
     * Read positionally, that yields an inside-out box: `width` and `height` go negative, so a
     * synthesized border paints nothing, and a containment test of the form
     * `y < bottom || y > top` can never be satisfied by any point at all.
     *
     * That was not theoretical. Link annotations built straight from `/Rect` meant every link on
     * such a page was both invisible and untappable, from this one omission.
     */
    public fun normalized(): KiteRectangle = KiteRectangle(
        left = minOf(left, right),
        bottom = minOf(bottom, top),
        right = maxOf(left, right),
        top = maxOf(bottom, top),
    )

    public companion object {
        /**
         * Parse a 4-element PDF rectangle array. Tolerant: a non-numeric entry
         * defaults to 0.0 rather than throwing (lenient salvage). For arrays
         * whose entries may be indirect references, route through the page's
         * box reader instead, which resolves each coordinate.
         *
         * Corners are normalised on the way out, per §7.9.5.
         */
        public fun fromPdfArray(arr: PdfArray): KiteRectangle {
            require(arr.size >= 4) { "Rectangle needs 4 numbers, got ${arr.size}" }
            fun n(i: Int): Double = when (val v = arr[i]) {
                is PdfReal -> v.value
                is PdfInt -> v.value.toDouble()
                else -> 0.0
            }
            return KiteRectangle(n(0), n(1), n(2), n(3)).normalized()
        }
    }
}
