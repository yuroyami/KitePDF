package io.github.yuroyami.kitepdf.core

import io.github.yuroyami.kitepdf.core.parser.PdfArray
import io.github.yuroyami.kitepdf.core.parser.PdfInt
import io.github.yuroyami.kitepdf.core.parser.PdfReal

/** PDF rectangle: [left, bottom, right, top] in user-space units. */
public data class Rectangle(val left: Double, val bottom: Double, val right: Double, val top: Double) {
    public val width: Double get() = right - left
    public val height: Double get() = top - bottom

    public companion object {
        /**
         * Parse a 4-element PDF rectangle array. Tolerant: a non-numeric entry
         * defaults to 0.0 rather than throwing (lenient salvage). For arrays
         * whose entries may be indirect references, route through the page's
         * box reader instead, which resolves each coordinate.
         */
        public fun fromPdfArray(arr: PdfArray): Rectangle {
            require(arr.size >= 4) { "Rectangle needs 4 numbers, got ${arr.size}" }
            fun n(i: Int): Double = when (val v = arr[i]) {
                is PdfReal -> v.value
                is PdfInt -> v.value.toDouble()
                else -> 0.0
            }
            return Rectangle(n(0), n(1), n(2), n(3))
        }
    }
}
