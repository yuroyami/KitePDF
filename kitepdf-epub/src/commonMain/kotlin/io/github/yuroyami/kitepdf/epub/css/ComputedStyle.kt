package io.github.yuroyami.kitepdf.epub.css

import io.github.yuroyami.kitepdf.core.render.RgbColor

internal enum class Display { BLOCK, INLINE, INLINE_BLOCK, LIST_ITEM, NONE, TABLE, TABLE_ROW, TABLE_CELL, TABLE_ROW_GROUP, FLEX, GRID }
/** START and END follow the text direction. LEFT and RIGHT never flip (#169). */
internal enum class TextAlign { START, END, LEFT, RIGHT, CENTER, JUSTIFY }
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

/** `word-break` (CSS Text 3, 5.2). BREAK_WORD is the legacy keyword that acts as `overflow-wrap: anywhere`. */
internal enum class WordBreak { NORMAL, BREAK_ALL, KEEP_ALL, BREAK_WORD }

/**
 * `line-break` (CSS Text 3, 5.3): how strictly CJK text keeps its small kana, marks and punctuation
 * off the start of a line. AUTO takes the STRICT rule, as Unicode's line breaking does by default.
 */
internal enum class LineBreak { AUTO, LOOSE, NORMAL, STRICT, ANYWHERE }

/**
 * `text-orientation` (CSS Writing Modes 3, 5.1): in vertical text, MIXED stands CJK characters
 * upright and turns the rest sideways, UPRIGHT stands every character up, SIDEWAYS turns them all.
 */
internal enum class TextOrientation { MIXED, UPRIGHT, SIDEWAYS }

/**
 * The `border-style` of one edge. Where two collapsed table borders of the same width
 * meet, the visible style listed first wins (CSS 2.1, 17.6.2.1).
 */
internal enum class BorderStyle { NONE, HIDDEN, DOUBLE, SOLID, DASHED, DOTTED, RIDGE, OUTSET, GROOVE, INSET }

/** One border edge. Painted only when [visible] (`border-style` not none/hidden). */
internal class Edge(val width: Double, val color: RgbColor, val style: BorderStyle) {
    /** Whether the edge occupies space and paints. */
    val visible: Boolean get() = style != BorderStyle.NONE && style != BorderStyle.HIDDEN

    /** Width that actually occupies space and paints. */
    val effective: Double get() = if (visible) width else 0.0

    companion object {
        val NONE = Edge(0.0, RgbColor(0.0, 0.0, 0.0), BorderStyle.NONE)
    }
}

/** A background colour and its alpha (CSS Color 4, 4.2). The cascade keeps no fully transparent one (#253). */
internal data class CssBackground(val color: RgbColor, val alpha: Double = 1.0)

/**
 * One decoration line and the element that draws it. CSS Text Decoration 3 keeps that
 * element's colour across its descendants (2.3), and one thickness and position on each
 * line (2.5), so the line takes its size from that element too (#271). [raised] is true
 * when that element has its own `vertical-align`: its lines then follow its shifted text.
 */
internal data class DecorationLine(
    val color: RgbColor,
    val sizePt: Double,
    val raised: Boolean = false,
    /** The decorating element's `text-underline-position`; a descendant's own value does not move the line. */
    val position: UnderlinePosition = UnderlinePosition.AUTO,
)

/**
 * `text-underline-position` or `-epub-text-underline-position` (CSS Text Decoration 3, 3.4, #508).
 * [under] sets the line below the descenders. In vertical text [side] puts it on that side of the
 * column, as for `under`; horizontal text ignores [side].
 */
internal data class UnderlinePosition(val under: Boolean = false, val side: UnderlineSide? = null) {
    companion object {
        val AUTO = UnderlinePosition()
    }
}

internal enum class UnderlineSide { LEFT, RIGHT }

/**
 * The fully-resolved style of one element: the cascade's output. Lengths are in
 * absolute points; inherited properties already carry the parent's value.
 * Box-model fields (margins/padding/background) are computed here; the layout
 * engine applies the subset it supports.
 */
internal data class ComputedStyle(
    val display: Display,
    val fontSizePt: Double,
    val bold: Boolean,
    val italic: Boolean,
    val fontFamily: GenericFont,
    val color: RgbColor,
    val backgroundColor: CssBackground?,
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
    /** Propagated underline (CSS Text Decoration 3, 2.1). */
    val underline: DecorationLine?,
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
    /** `margin-left`/`margin-right: auto` centers a width-constrained box. */
    val marginLeftAuto: Boolean,
    val marginRightAuto: Boolean,
    /** Ordered `font-family` names before the first generic, for matching embedded faces. */
    val fontFamilyNames: List<String>,
    /** Inline base direction (from `direction`/`dir`), for the bidi algorithm. */
    val direction: Direction,
    /** `hyphens: auto` allows the line-breaker to hyphenate long words. */
    val hyphensAuto: Boolean,
    /** `position` + insets (px→pt), for out-of-flow placement (mainly fixed-layout). */
    val position: CssPosition,
    val leftPt: Double?,
    val topPt: Double?,
    val rightPt: Double?,
    val bottomPt: Double?,
    /** `object-fit` for replaced content (images/SVG) in a fixed box. */
    val objectFit: ObjectFit,
    /** `writing-mode`: vertical CJK text lays out in columns right-to-left / left-to-right. */
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
    /** `float`: the box leaves the flow and text lines wrap beside it. Not inherited. */
    val cssFloat: CssFloat = CssFloat.NONE,
    /** `clear`: flow resumes below matching floats. Not inherited. */
    val clear: CssClear = CssClear.NONE,
    /**
     * `table-layout: fixed`: columns are sized by `<col>` and the first row's
     * declared widths alone, never by cell content. Not inherited.
     */
    val tableLayoutFixed: Boolean = false,
    /** Propagated line-through (CSS Text Decoration 3, 2.1). */
    val lineThrough: DecorationLine? = null,
    /** `z-index` of a positioned box, null for `auto` (CSS 2.1, 9.9.1, #172). Not inherited. */
    val zIndex: Int? = null,
    /** `opacity`, 0 to 1; below 1 the box and its content paint as one group (CSS Color 4, 14, #28). Not inherited. */
    val opacity: Double = 1.0,
    /** False for `visibility: hidden` or `collapse`: the box keeps its room and paints nothing of its own. Inherited. */
    val visible: Boolean = true,
    /** True for an `overflow` other than `visible`: the content is clipped to the padding box (#28). Not inherited. */
    val clipsOverflow: Boolean = false,
    /** `border-radius` and its longhands, or null for square corners (#28). Not inherited. */
    val radii: CornerRadii? = null,
    /** `box-shadow`, in the order the rule lists them, the first on top (#28). Not inherited. */
    val shadows: List<BoxShadow> = emptyList(),
    /**
     * The layers of `background-image` that paint, each with its size, position and repeat, the
     * first on top (#28, #503). Not inherited.
     */
    val backgroundLayers: List<CssBackgroundLayer> = emptyList(),
    /** `transform`, applied right to left, or null for none. It moves paint, not layout (#28). Not inherited. */
    val transform: List<CssTransform>? = null,
    /** `transform-origin`, x then y, in the border box. Not inherited. */
    val transformOrigin: Pair<CssOffset, CssOffset> = CssOffset.HALF to CssOffset.HALF,
    /** The flex properties, as a container and as an item (#33). Not inherited. */
    val flex: FlexStyle = FlexStyle(),
    /** The grid properties, as a container and as an item (#35). Not inherited. */
    val grid: GridStyle = GridStyle(),
    /** The multi-column properties (#34). Not inherited. */
    val columns: Columns = Columns(),
    /**
     * `quotes`: the opening and closing mark of each level of quotation, an empty list for
     * `none`, or null for `auto`, which takes the marks of the content language (#511). Inherited.
     */
    val quotes: List<String>? = null,
    /**
     * `text-align-last`: how the last line of a block, and a line that a forced break ends, align,
     * or null for `auto`, which follows [textAlign] and sets a justified block's last line at the
     * start (CSS Text 3, 7.2, #508). Inherited.
     */
    val textAlignLast: TextAlign? = null,
    /**
     * True when a word that cannot fit a line of its own may break between any two characters:
     * `overflow-wrap` (or `word-wrap`) `break-word` or `anywhere` (CSS Text 3, 5.5, #574).
     * Inherited. `word-break: break-word` has the same effect, through [wordBreak].
     */
    val overflowWrap: Boolean = false,
    /** `word-break` or `-epub-word-break` (CSS Text 3, 5.2, #508). Inherited. */
    val wordBreak: WordBreak = WordBreak.NORMAL,
    /** `full-width` (or EPUB's `-epub-fullwidth`) in `text-transform`, beside its case (CSS Text 3, 2.1, #508). Inherited. */
    val fullWidth: Boolean = false,
    /** `line-break` or `-epub-line-break` (CSS Text 3, 5.3, #508). Inherited. */
    val lineBreak: LineBreak = LineBreak.AUTO,
    /** `text-orientation` or `-epub-text-orientation` (CSS Writing Modes 3, 5.1, #508). Inherited. */
    val textOrientation: TextOrientation = TextOrientation.MIXED,
    /** `text-underline-position` or `-epub-text-underline-position` (CSS Text Decoration 3, 3.4, #508). Inherited. */
    val underlinePosition: UnderlinePosition = UnderlinePosition.AUTO,
) {
    val mono: Boolean get() = fontFamily == GenericFont.MONO

    companion object {
        /** The initial (root) style before any rule applies. [direction] is the publication base. */
        fun initial(rootFontSizePt: Double, color: RgbColor = RgbColor(0.0, 0.0, 0.0), direction: Direction = Direction.LTR) = ComputedStyle(
            display = Display.BLOCK,
            fontSizePt = rootFontSizePt,
            bold = false, italic = false, fontFamily = GenericFont.SERIF,
            color = color, backgroundColor = null,
            textAlign = TextAlign.START, textIndentPt = 0.0, lineHeightPt = null,
            marginTopPt = 0.0, marginRightPt = 0.0, marginBottomPt = 0.0, marginLeftPt = 0.0,
            paddingTopPt = 0.0, paddingRightPt = 0.0, paddingBottomPt = 0.0, paddingLeftPt = 0.0,
            whiteSpace = WhiteSpaceMode.NORMAL, listType = ListType.DISC,
            verticalAlign = CssVAlign.BASELINE, underline = null,
            borderTop = Edge.NONE, borderRight = Edge.NONE, borderBottom = Edge.NONE, borderLeft = Edge.NONE,
            widthPt = null, heightPt = null, maxWidthPt = null,
            breakBefore = false, breakAfter = false, breakInsideAvoid = false,
            marginLeftAuto = false, marginRightAuto = false,
            fontFamilyNames = emptyList(),
            direction = direction,
            hyphensAuto = false,
            position = CssPosition.STATIC,
            leftPt = null, topPt = null, rightPt = null, bottomPt = null,
            objectFit = ObjectFit.FILL,
            writingMode = WritingMode.HORIZONTAL,
        )
    }
}
