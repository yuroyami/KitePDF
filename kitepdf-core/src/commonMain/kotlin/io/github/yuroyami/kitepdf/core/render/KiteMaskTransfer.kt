package io.github.yuroyami.kitepdf.core.render

import kotlin.math.roundToInt

/**
 * The transfer function of a soft mask as a table of 256 levels (ISO 32000-1, 11.6.5.2,
 * Table 144, /TR). The table maps each value of the mask group, its alpha or its
 * luminosity, to the mask value that gates the content. Both run from 0 to 255.
 */
public class KiteMaskTransfer private constructor(private val table: IntArray) {

    /** The mask value for the group value [level]. A level outside 0 to 255 is clamped. */
    public operator fun get(level: Int): Int = table[level.coerceIn(0, 255)]

    /** The table as 256 bytes, for a backend that takes a lookup table. */
    public fun toByteArray(): ByteArray = ByteArray(256) { table[it].toByte() }

    /**
     * The slope of the straight line closest to the table, with both axes from 0 to 1.
     * A backend that can only scale and offset a colour, such as a colour matrix, uses
     * [slope] and [offset]. The line is exact for a linear function such as an inverter.
     */
    public val slope: Double

    /** The mask value of that line for group value 0, from 0 to 1. */
    public val offset: Double

    init {
        // A least-squares fit over the 256 levels.
        var sx = 0.0; var sy = 0.0; var sxx = 0.0; var sxy = 0.0
        for (i in 0..255) {
            val x = i / 255.0
            val y = table[i] / 255.0
            sx += x; sy += y; sxx += x * x; sxy += x * y
        }
        val n = 256.0
        slope = (n * sxy - sx * sy) / (n * sxx - sx * sx)
        offset = (sy - slope * sx) / n
    }

    public companion object {
        /** The table of [function], which maps a group value from 0 to 1 to a mask value from 0 to 1. */
        public fun of(function: (Double) -> Double): KiteMaskTransfer = KiteMaskTransfer(
            IntArray(256) { level ->
                val value = function(level / 255.0)
                if (value.isFinite()) (value.coerceIn(0.0, 1.0) * 255).roundToInt() else 0
            },
        )
    }
}
