package io.github.yuroyami.kitepdf.epub

import io.github.yuroyami.kitepdf.epub.css.ComputedStyle
import io.github.yuroyami.kitepdf.core.font.FontSpec
import io.github.yuroyami.kitepdf.core.font.TextGlyph
import io.github.yuroyami.kitepdf.core.render.ImageXObject
import io.github.yuroyami.kitepdf.core.render.RgbColor

/**
 * The CSS box tree the Phase-3 layout engine positions and paints. Unlike the
 * flat Phase-2 block list, this preserves nesting so a container's background,
 * border and padding wrap all its descendants.
 *
 * Coordinates are filled by [BoxLayout] in *document space*: x grows right from
 * the page content-left edge (0..contentWidth), y grows DOWN from the top of the
 * whole document flow (across all pages). (x, y) is the border-box top-left.
 */
internal sealed class LayoutBox {
    abstract val style: ComputedStyle

    var x: Double = 0.0
    var y: Double = 0.0
    var borderBoxWidth: Double = 0.0
    var borderBoxHeight: Double = 0.0

    val bottom: Double get() = y + borderBoxHeight
}

/** A block container: stacks [children] vertically (block formatting context). */
internal class BlockBox(
    override val style: ComputedStyle,
    val children: List<LayoutBox>,
) : LayoutBox() {
    // Table-cell grid placement (only meaningful when this box is a cell).
    var colspan: Int = 1
    var rowspan: Int = 1
    var gridRow: Int = 0
    var gridCol: Int = 0

    /**
     * Anchor ids reachable at this box: the element's own `id` (plus legacy
     * `<a name>`) and the ids of its INLINE descendants, which get no box of
     * their own. Drives href-fragment -> page navigation.
     */
    val anchors = ArrayList<String>()
}

/**
 * A block that directly holds inline content — an inline formatting context.
 * Anonymous ones (from mixed block+inline content) reuse the parent's style for
 * text but carry no box decorations. [marker] is a list-item bullet/number.
 */
internal class TextBlockBox(
    override val style: ComputedStyle,
    val runs: List<InlineRun>,
    val marker: String? = null,
    val markerColor: RgbColor = RgbColor(0.0, 0.0, 0.0),
) : LayoutBox() {
    var lines: List<PositionedLine> = emptyList()
}

/** A block-level image, scaled to fit its content width. */
internal class ImageBox(
    override val style: ComputedStyle,
    val zipPath: String,
    /** Pre-parsed inline `<svg>` content; null for raster images / `.svg` file refs. */
    var svg: SvgImage? = null,
    /** Presentational `width`/`height` HTML attributes in px (CSS width/height wins over these). */
    val attrWidth: Double? = null,
    val attrHeight: Double? = null,
) : LayoutBox() {
    var image: ImageXObject? = null
    var drawWidth: Double = 0.0
    var drawHeight: Double = 0.0
}

/** A table box: a grid of [rows] with auto column widths plus `<col>` pins. */
internal class TableBox(
    override val style: ComputedStyle,
    val rows: List<TableRowBox>,
    /** Column index -> pinned width in points, from `<col>`/`<colgroup>` widths. */
    val colWidths: Map<Int, Double> = emptyMap(),
) : LayoutBox()

/** One table row; its [cells] are block containers laid out at their column widths. */
internal class TableRowBox(
    override val style: ComputedStyle,
    val cells: List<BlockBox>,
) : LayoutBox()

/** One inline run positioned on a line: glyphs + paint + document-space left [x]. */
internal class PlacedRun(
    val glyphs: List<TextGlyph>,
    /** Mutable only for the post-layout `position:relative` shift pass. */
    var x: Double,
    val fontSize: Double,
    val fontSpec: FontSpec,
    val color: RgbColor,
    val baselineShift: Double = 0.0,
    val underline: Boolean = false,
    /** True when [glyphs] carry embedded outlines; then [unitsPerEm] is the face's. */
    val hasOutlines: Boolean = false,
    val unitsPerEm: Int = 1000,
    /**
     * True for decoration runs that are not part of the reading text — a ruby
     * reading overlay. Text extraction and search skip them.
     */
    val isAnnotation: Boolean = false,
    /** Link target when the run is inside `<a href>` (see [InlineRun.href]). */
    val href: String? = null,
)

/**
 * An inline image placed on a line, parallel to [PlacedRun]: document-space
 * left [x], its draw size, and the decoded payload (raster [image] or [svg]).
 * Its bottom sits on the line's baseline (`vertical-align` baseline only).
 */
internal class PlacedImage(
    /** Mutable only for the post-layout `position:relative` shift pass. */
    var x: Double,
    val width: Double,
    val height: Double,
    val image: ImageXObject?,
    val svg: SvgImage?,
)

/** A laid-out line inside a [TextBlockBox]; [yTop] is absolute document-down. */
internal class PositionedLine(
    val runs: List<PlacedRun>,
    /** Mutable only for the post-layout `position:relative` shift pass. */
    var yTop: Double,
    val height: Double,
    val ascent: Double,
    /** Inline images on this line (bottom on the baseline). */
    val images: List<PlacedImage> = emptyList(),
) {
    /** Owning box + line index, filled after layout, for the paginator's widows/orphans. */
    var owner: TextBlockBox? = null
    var ownerIndex: Int = 0
}
