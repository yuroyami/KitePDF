package io.github.yuroyami.kitepdf.epub.css

import io.github.yuroyami.kitepdf.core.render.RgbColor

internal enum class Display { BLOCK, INLINE, INLINE_BLOCK, LIST_ITEM, NONE, TABLE, TABLE_ROW, TABLE_CELL, TABLE_ROW_GROUP }
internal enum class TextAlign { LEFT, RIGHT, CENTER, JUSTIFY }
internal enum class WhiteSpaceMode { NORMAL, PRE, NOWRAP, PRE_WRAP, PRE_LINE }
internal enum class GenericFont { SERIF, SANS, MONO }
internal enum class CssVAlign { BASELINE, SUPER, SUB, TOP, MIDDLE, BOTTOM }
internal enum class ListType { DISC, CIRCLE, SQUARE, DECIMAL, LOWER_ROMAN, UPPER_ROMAN, LOWER_ALPHA, UPPER_ALPHA, NONE }
internal enum class Direction { LTR, RTL }
internal enum class CssPosition { STATIC, RELATIVE, ABSOLUTE, FIXED }
internal enum class ObjectFit { FILL, CONTAIN, COVER }
internal enum class WritingMode { HORIZONTAL, VERTICAL_RL, VERTICAL_LR }
internal enum class CssFloat { NONE, LEFT, RIGHT }
internal enum class CssClear { NONE, LEFT, RIGHT, BOTH }
internal enum class TextTransform { NONE, UPPERCASE, LOWERCASE, CAPITALIZE }

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
    /** Inline base direction (from `direction`/`dir`), for the bidi algorithm. */
    val direction: Direction,
    /** `hyphens: auto` — allow the line-breaker to hyphenate long words. */
    val hyphensAuto: Boolean,
    /** `position` + insets (px→pt), for out-of-flow placement (mainly fixed-layout). */
    val position: CssPosition,
    val leftPt: Double?,
    val topPt: Double?,
    val rightPt: Double?,
    val bottomPt: Double?,
    /** `object-fit` for replaced content (images/SVG) in a fixed box. */
    val objectFit: ObjectFit,
    /** `writing-mode` — vertical CJK text lays out in columns right-to-left / left-to-right. */
    val writingMode: WritingMode,
    /** `text-transform`, applied when inline runs are built. Inherited. */
    val textTransform: TextTransform = TextTransform.NONE,
    /** `letter-spacing` in points, added to every glyph advance. Inherited. */
    val letterSpacingPt: Double = 0.0,
    /** `word-spacing` in points, added to every space advance. Inherited. */
    val wordSpacingPt: Double = 0.0,
    /** `font-variant: small-caps` (smcp GSUB when the face has it, else synthesized). Inherited. */
    val smallCaps: Boolean = false,
    /** Size clamps, or null when unset. Min wins over max per CSS. */
    val minWidthPt: Double? = null,
    val minHeightPt: Double? = null,
    val maxHeightPt: Double? = null,
    /** `border-collapse: collapse` (inherited; read on the table box). */
    val borderCollapse: Boolean = false,
    /** `border-spacing` / `cellspacing` in points (inherited; 0 under collapse). */
    val borderSpacingPt: Double = 0.0,
    /** `float` — the box leaves the flow and text lines wrap beside it. Not inherited. */
    val cssFloat: CssFloat = CssFloat.NONE,
    /** `clear` — flow resumes below matching floats. Not inherited. */
    val clear: CssClear = CssClear.NONE,
) {
    val mono: Boolean get() = fontFamily == GenericFont.MONO

    companion object {
        /** The initial (root) style before any rule applies. [direction] is the publication base. */
        fun initial(rootFontSizePt: Double, color: RgbColor = RgbColor(0.0, 0.0, 0.0), direction: Direction = Direction.LTR) = ComputedStyle(
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
            direction = direction,
            hyphensAuto = false,
            position = CssPosition.STATIC,
            leftPt = null, topPt = null, rightPt = null, bottomPt = null,
            objectFit = ObjectFit.FILL,
            writingMode = WritingMode.HORIZONTAL,
        )
    }
}
