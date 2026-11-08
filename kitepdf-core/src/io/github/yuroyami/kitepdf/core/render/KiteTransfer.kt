package io.github.yuroyami.kitepdf.core.render

/**
 * The transfer function of the graphics state (ISO 32000-1, 10.4, Table 58, `/TR` and `/TR2`;
 * #909): a table of 256 levels for each of red, green and blue, which maps each component of a
 * colour before it is painted. One function maps the three alike; an array of four functions
 * gives one to each colorant, and the first three map red, green and blue.
 */
public class KiteTransfer(public val red: KiteMaskTransfer, public val green: KiteMaskTransfer, public val blue: KiteMaskTransfer) {

    /** [argb], an ARGB pixel that is not premultiplied, with each colour component mapped and its alpha kept. */
    public fun map(argb: Int): Int =
        (argb and 0xFF000000.toInt()) or (red[argb ushr 16 and 0xFF] shl 16) or (green[argb ushr 8 and 0xFF] shl 8) or blue[argb and 0xFF]
}
