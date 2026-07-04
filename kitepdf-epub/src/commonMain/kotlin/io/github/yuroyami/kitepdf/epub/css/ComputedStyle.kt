package io.github.yuroyami.kitepdf.epub.css

import io.github.yuroyami.kitepdf.render.RgbColor

internal enum class Display { BLOCK, INLINE, INLINE_BLOCK, LIST_ITEM, NONE, TABLE, TABLE_ROW, TABLE_CELL, TABLE_ROW_GROUP }
internal enum class TextAlign { LEFT, RIGHT, CENTER, JUSTIFY }
internal enum class WhiteSpaceMode { NORMAL, PRE, NOWRAP, PRE_WRAP, PRE_LINE }
internal enum class GenericFont { SERIF, SANS, MONO }
internal enum class CssVAlign { BASELINE, SUPER, SUB }
internal enum class ListType { DISC, CIRCLE, SQUARE, DECIMAL, LOWER_ROMAN, UPPER_ROMAN, LOWER_ALPHA, UPPER_ALPHA, NONE }

/** One border edge. Painted only when [visible] (`border-style` not none/hidden). */
internal class Edge(val width: Double, val color: RgbColor, val visible: Boolean) {
    /** Width that actually occupies space and paints. */
    val effective: Double get() = if (visible) width else 0.0

    companion object {
        val NONE = Edge(0.0, RgbColor(0.0, 0.0, 0.0), false)
    }
}

/**
 * The fully-resolved style of one element: the cascade's output. Lengths are in
 * absolute points; inherited properties already carry the parent's value.
 * Box-model fields (margins/padding/background) are computed here but only
 * *applied* by the layout engine to the extent Phase 2 supports (left inset +
 * vertical spacing); full borders/backgrounds are Phase 3.
 */
internal data class ComputedStyle(
    val display: Display,
    val fontSizePt: Double,
    val bold: Boolean,
    val italic: Boolean,
    val fontFamily: GenericFont,
    val color: RgbColor,
    val backgroundColor: RgbColor?,
    val textAlign: TextAlign,
    val textIndentPt: Double,
    /** Resolved line height in points, or null for `normal`. */
    val lineHeightPt: Double?,
    val marginTopPt: Double,
    val marginRightPt: Double,
    val marginBottomPt: Double,
    val marginLeftPt: Double,
    val paddingTopPt: Double,
    val paddingRightPt: Double,
    val paddingBottomPt: Double,
    val paddingLeftPt: Double,
    val whiteSpace: WhiteSpaceMode,
    val listType: ListType,
    val verticalAlign: CssVAlign,
    val underline: Boolean,
    val borderTop: Edge,
    val borderRight: Edge,
    val borderBottom: Edge,
    val borderLeft: Edge,
    /** Explicit sizes, or null for `auto`/`none`. */
    val widthPt: Double?,
    val heightPt: Double?,
    val maxWidthPt: Double?,
    /** Forced page break before/after this block; avoid splitting it across pages. */
    val breakBefore: Boolean,
    val breakAfter: Boolean,
    val breakInsideAvoid: Boolean,
    /** `margin-left`/`margin-right: auto` — centers a width-constrained box. */
    val marginLeftAuto: Boolean,
    val marginRightAuto: Boolean,
    /** First non-generic `font-family` name, for matching an `@font-face`; null if all generic. */
    val fontFamilyName: String?,
) {
    val mono: Boolean get() = fontFamily == GenericFont.MONO

    companion object {
        /** The initial (root) style before any rule applies. */
        fun initial(rootFontSizePt: Double, color: RgbColor = RgbColor(0.0, 0.0, 0.0)) = ComputedStyle(
            display = Display.BLOCK,
            fontSizePt = rootFontSizePt,
            bold = false, italic = false, fontFamily = GenericFont.SERIF,
            color = color, backgroundColor = null,
            textAlign = TextAlign.LEFT, textIndentPt = 0.0, lineHeightPt = null,
            marginTopPt = 0.0, marginRightPt = 0.0, marginBottomPt = 0.0, marginLeftPt = 0.0,
            paddingTopPt = 0.0, paddingRightPt = 0.0, paddingBottomPt = 0.0, paddingLeftPt = 0.0,
            whiteSpace = WhiteSpaceMode.NORMAL, listType = ListType.DISC,
            verticalAlign = CssVAlign.BASELINE, underline = false,
            borderTop = Edge.NONE, borderRight = Edge.NONE, borderBottom = Edge.NONE, borderLeft = Edge.NONE,
            widthPt = null, heightPt = null, maxWidthPt = null,
            breakBefore = false, breakAfter = false, breakInsideAvoid = false,
            marginLeftAuto = false, marginRightAuto = false,
            fontFamilyName = null,
        )
    }
}
