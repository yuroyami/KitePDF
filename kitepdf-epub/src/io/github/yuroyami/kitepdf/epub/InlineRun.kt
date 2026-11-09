package io.github.yuroyami.kitepdf.epub

import io.github.yuroyami.kitepdf.core.xml.KiteXmlNode

import io.github.yuroyami.kitepdf.epub.css.CssBackground
import io.github.yuroyami.kitepdf.epub.css.CssVAlign
import io.github.yuroyami.kitepdf.epub.css.DecorationLine
import io.github.yuroyami.kitepdf.epub.css.Emphasis
import io.github.yuroyami.kitepdf.epub.css.GenericFont
import io.github.yuroyami.kitepdf.epub.css.LineBreak
import io.github.yuroyami.kitepdf.epub.css.TextOrientation
import io.github.yuroyami.kitepdf.epub.css.ObjectFit
import io.github.yuroyami.kitepdf.core.render.RgbColor
import io.github.yuroyami.kitepdf.svg.SvgImage
import io.github.yuroyami.kitepdf.epub.css.Edge

/**
 * An element of `display: inline-block` whose content is inline (CSS 2.1, 9.2.4; #840): its text
 * stays runs of the line, so it reads, searches and selects as before, but the runs form one unit
 * that no line breaks inside. The unit takes the element's [widthPt] or [minWidthPt] when its text
 * is narrower, its horizontal padding, border and margins as room before and after it, and paints
 * its [background] and border as one box behind its text.
 *
 * One object for each element, compared by identity. An inline-block with a block inside it keeps
 * the old layout, as inline text.
 */
internal class InlineBox(
    val widthPt: Double?,
    val minWidthPt: Double?,
    val paddingTop: Double,
    val paddingRight: Double,
    val paddingBottom: Double,
    val paddingLeft: Double,
    val borderTop: Edge,
    val borderRight: Edge,
    val borderBottom: Edge,
    val borderLeft: Edge,
    val marginLeft: Double,
    val marginRight: Double,
    val background: CssBackground?,
) {
    /** The room before the text: the left margin, border and padding. */
    val before: Double get() = marginLeft + borderLeft.effective + paddingLeft

    /** The room after the text, without the shortfall from [widthPt]: the right padding, border and margin. */
    val after: Double get() = paddingRight + borderRight.effective + marginRight
}

/**
 * A maximal span of text sharing one computed inline style. Everything the layout
 * needs to size and paint the run is resolved here: its own [fontSizePt] (CSS can
 * size inline text independently of its block), emphasis, colour, and baseline
 * shift. A [hardBreak] run is a `<br>` -- its [text] is empty and it forces a new
 * line within the block.
 */
internal data class InlineRun(
    val text: String,
    val fontSizePt: Double,
    val bold: Boolean = false,
    /** The numeric `font-weight`, which picks among `@font-face` weights (#929). */
    val fontWeight: Int = if (bold) 700 else 400,
    val italic: Boolean = false,
    val family: GenericFont = GenericFont.SERIF,
    val color: RgbColor = BLACK,
    val valign: CssVAlign = CssVAlign.BASELINE,
    val underline: DecorationLine? = null,
    val hardBreak: Boolean = false,
    /** Ordered CSS families before the first generic, matched against embedded faces. */
    val fontFamilyNames: List<String> = emptyList(),
    /**
     * Ruby membership: runs of one `<ruby>` base share a group id (>= 0) and
     * carry the collected `<rt>` reading. The layout keeps a group unbreakable,
     * pads a base narrower than its reading, and paints the reading centered
     * above it at [io.github.yuroyami.kitepdf.epub.BoxLayout] RUBY_SIZE em.
     */
    val rubyGroup: Int = -1,
    val rubyText: String? = null,
    /**
     * Link target when this run sits inside `<a href>`: an external URL kept
     * verbatim, or an internal target resolved to `zip/path.xhtml#fragment`.
     */
    val href: String? = null,
    /** `letter-spacing` in points, added to every glyph advance. */
    val letterSpacingPt: Double = 0.0,
    /** `word-spacing` in points, added to every space advance. */
    val wordSpacingPt: Double = 0.0,
    /** `font-variant: small-caps`. */
    val smallCaps: Boolean = false,
    /** A word of this run that cannot fit a line of its own may break between two characters (#574). */
    val overflowWrap: Boolean = false,
    /** `word-break: break-all`: a line may break between any two letters of a word (#508). */
    val breakAll: Boolean = false,
    /** `word-break: keep-all`: CJK letters join into words, as Latin ones do (#508). */
    val keepAll: Boolean = false,
    /**
     * `white-space: nowrap`: the run's spaces and the letters around them are no place to break,
     * so the run's text stays on one line (CSS Text 3, 3; #845).
     */
    val noWrap: Boolean = false,
    /** `tab-size` of the run's text: a count of spaces, or a length when [tabLengthPt] is set (#847). */
    val tabSpaces: Double = 8.0,
    val tabLengthPt: Double? = null,
    /** The language of the run's element, or null for the chapter's, which hyphenation reads (#864). */
    val language: String? = null,
    /** `line-break` (CSS Text 3, 5.3, #508). */
    val lineBreak: LineBreak = LineBreak.AUTO,
    /** `text-orientation`, which only vertical text reads (CSS Writing Modes 3, 5.1, #508). */
    val textOrientation: TextOrientation = TextOrientation.MIXED,
    /**
     * Inline `<img>`: the resolved zip path of the image this run stands for
     * ([text] is then a single U+FFFC object-replacement char). Sized at
     * layout from [imageCssW]/[imageCssH] (CSS width/height, then the HTML
     * attributes, in points) or the intrinsic size, capped to the line width.
     */
    val imageSrc: String? = null,
    /** Inline `<svg>` element: its parsed picture, drawn in place of [imageSrc], which is then empty. */
    val imageSvg: SvgImage? = null,
    val imageCssW: Double? = null,
    val imageCssH: Double? = null,
    /** The image's `alt`, for the reading order. Empty string means decorative. */
    val imageAlt: String? = null,
    val imageObjectFit: ObjectFit = ObjectFit.FILL,
    /** False when the image's `image-rendering` asks for hard pixel edges (#936). */
    val imageSmooth: Boolean = true,
    val lineThrough: DecorationLine? = null,
    val overline: DecorationLine? = null,
    /** The emphasis marks over the run's letters, or null for none (CSS Text Decoration 3, 3, #508). */
    val emphasis: Emphasis? = null,
    /**
     * Tate-chu-yoko: runs of one element with `text-combine-upright: all` share a group id (>= 0),
     * and vertical text sets their text side by side in one em (CSS Writing Modes 3, 9.1, #508).
     */
    val combineGroup: Int = -1,
    /** Inline box background, including the nearest painted inline ancestor. */
    val backgroundColor: CssBackground? = null,
    /** The inline-block this run's text is inside, which lays out as one unit on its line, or null (#840). */
    val box: InlineBox? = null,
    /** The pronunciation of the element this run's text belongs to, or null (#39). */
    val speech: SpeechHint? = null,
    /**
     * The ids of the elements around this run's text, outermost first, so a fragment finds its
     * lines (#36). One list per element, so runs of one element share it.
     */
    val ids: List<String> = emptyList(),
    /** A `<math>` element: one U+FFFC run that the layout sets as a formula (#32). */
    val math: MathRoot? = null,
    /**
     * The innermost element this run's text belongs to, in a chapter that scripts run in, so a
     * tap finds the element under it (#41). Null in every other chapter, so runs there split
     * exactly where they did.
     */
    val element: KiteXmlNode.Element? = null,
    /**
     * The inline elements around the text that open an embedding, override or isolate
     * (`unicode-bidi`), outermost first (#932). Each scope is one element, by identity.
     */
    val bidi: List<BidiScope> = emptyList(),
) {
    companion object {
        val BLACK = RgbColor(0.0, 0.0, 0.0)
    }
}

/** One inline element's `unicode-bidi` scope and its direction, compared by identity (#932). */
internal class BidiScope(val kind: io.github.yuroyami.kitepdf.epub.css.UnicodeBidi, val rtl: Boolean)
