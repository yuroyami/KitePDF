package io.github.yuroyami.kitepdf.epub

import io.github.yuroyami.kitepdf.epub.css.CssVAlign
import io.github.yuroyami.kitepdf.epub.css.GenericFont
import io.github.yuroyami.kitepdf.render.RgbColor

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
    val underline: Boolean = false,
    val hardBreak: Boolean = false,
    /** CSS `font-family` name to match against an embedded `@font-face`, or null. */
    val fontFamilyName: String? = null,
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
    /**
     * Inline `<img>`: the resolved zip path of the image this run stands for
     * ([text] is then a single U+FFFC object-replacement char). Sized at
     * layout from [imageCssW]/[imageCssH] (CSS width/height, then the HTML
     * attributes, in points) or the intrinsic size, capped to the line width.
     */
    val imageSrc: String? = null,
    val imageCssW: Double? = null,
    val imageCssH: Double? = null,
) {
    companion object {
        val BLACK = RgbColor(0.0, 0.0, 0.0)
    }
}
