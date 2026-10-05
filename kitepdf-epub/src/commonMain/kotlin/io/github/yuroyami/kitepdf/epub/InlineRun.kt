package io.github.yuroyami.kitepdf.epub

import io.github.yuroyami.kitepdf.core.xml.KiteXmlNode

import io.github.yuroyami.kitepdf.epub.css.CssBackground
import io.github.yuroyami.kitepdf.epub.css.CssVAlign
import io.github.yuroyami.kitepdf.epub.css.DecorationLine
import io.github.yuroyami.kitepdf.epub.css.GenericFont
import io.github.yuroyami.kitepdf.epub.css.ObjectFit
import io.github.yuroyami.kitepdf.core.render.RgbColor
import io.github.yuroyami.kitepdf.svg.SvgImage

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
    val lineThrough: DecorationLine? = null,
    /** Inline box background, including the nearest painted inline ancestor. */
    val backgroundColor: CssBackground? = null,
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
) {
    companion object {
        val BLACK = RgbColor(0.0, 0.0, 0.0)
    }
}
