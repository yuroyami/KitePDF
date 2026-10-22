package io.github.yuroyami.kitepdf.core.font

/**
 * Where one glyph of vertical text sits on the pen, and how far it moves the pen, in
 * thousandths of an em (ISO 32000-1, 9.7.4.3). [PdfFont.verticalMetrics] gives it.
 *
 * A glyph in vertical writing has two origins. Its horizontal origin is the one its
 * outline is drawn from. Its vertical origin sits on the pen, and the position vector
 * (`vx`, `vy`) leads from the first to the second. A renderer therefore draws the
 * outline at the pen minus that vector, then moves the pen by [displacement].
 */
public class PdfVerticalMetrics internal constructor(
    /** `w1y`: how far the pen moves after the glyph. It is usually -1000, one em down. */
    public val displacement: Double,
    /** `vx`: the x of the position vector. The default is half the glyph's width. */
    public val originX: Double,
    /** `vy`: the y of the position vector. The default is 880. */
    public val originY: Double,
)
