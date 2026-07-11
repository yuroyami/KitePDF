package io.github.yuroyami.kitepdf.render

import io.github.yuroyami.kitepdf.font.FontSpec
import io.github.yuroyami.kitepdf.font.TextGlyph

/**
 * High-level drawing target. Implement this to add a new render backend;
 * use [PdfPage.renderTo] to drive an existing one.
 *
 * The [PageRenderer] walks the content stream, maintains the graphics-state
 * stack, accumulates paths, and finally calls one of these device methods
 * with a complete, ready-to-paint primitive. Backends therefore don't need
 * to track the CTM, the current path, or text positioning — they just
 * receive the final geometry plus the transform to apply.
 *
 * Concrete implementations shipped:
 *   - `ComposeCanvas` (`:kitepdf-compose`) — paints into a Compose `DrawScope`.
 *   - `SkiaCanvas` (`:kitepdf-skia`) — paints into a Skia `Canvas` for JVM.
 *   - `AwtCanvas` / `AndroidNativeCanvas` / `CoreGraphicsCanvas` / `Canvas2dCanvas`
 *     (`:kitepdf-native-renderer`) — host-platform raster backends.
 */
public interface PdfCanvas {

    /**
     * Set up for a page render — called once before any draw call, with the
     * page dimensions (in PDF user units, 1pt = 1/72 inch) and the desired
     * device CTM (e.g. flip Y and scale to fit a target rectangle).
     */
    public fun beginPage(widthPt: Double, heightPt: Double, deviceCtm: Matrix)
    public fun endPage()

    /**
     * Fill [path] under [ctm] with [color] at [alpha] (0..1) under the given
     * [blendMode]. The default `alpha = 1.0` + `blendMode = Normal` is the
     * plain over-paint that PDF assumes when no ExtGState modifies the state.
     */
    public fun fillPath(
        path: PdfPath, ctm: Matrix, color: RgbColor, evenOdd: Boolean,
        alpha: Double = 1.0, blendMode: BlendMode = BlendMode.Normal,
    )

    /** Stroke [path] under [ctm] with [color] at [lineWidth] user-units, [alpha], [blendMode].
     *  [lineCap] 0/1/2 = butt/round/square; [lineJoin] 0/1/2 = miter/round/bevel. */
    public fun strokePath(
        path: PdfPath, ctm: Matrix, color: RgbColor, lineWidth: Double,
        alpha: Double = 1.0, blendMode: BlendMode = BlendMode.Normal,
        dashArray: List<Double>? = null, dashPhase: Double = 0.0,
        lineCap: Int = 0, lineJoin: Int = 0, miterLimit: Double = 10.0,
    )

    /**
     * Fill [clipPath] (under [ctm]) with the gradient defined by [shading].
     * If [clipPath] is `null` the shading covers the current clip region
     * (or the page, when no clip is active) — the spec's `sh` operator.
     *
     * Default impl falls back to a flat-colour fill using the midpoint
     * sample; backends that can render real gradients override. For the
     * whole-clip case it fills a rectangle far larger than any page under
     * the identity matrix — the backend's active clip (or page bounds)
     * trims it, so `sh` still paints something rather than nothing.
     */
    public fun fillShading(
        shading: PdfShading, ctm: Matrix, clipPath: PdfPath?,
        alpha: Double = 1.0, blendMode: BlendMode = BlendMode.Normal,
    ) {
        // T-40 types (function-based, meshes, patches) render through shared
        // fillPath emission; axial/radial fall through to the midpoint fill.
        if (paintComplexShading(shading, ctm, clipPath, alpha, blendMode)) return
        val stops = shading.sampleStops(2) ?: return
        val mid = stops.colors[stops.colors.size / 2]
        if (clipPath == null) {
            val huge = PdfPath.Builder().apply {
                moveTo(-1e6, -1e6)
                lineTo(1e6, -1e6)
                lineTo(1e6, 1e6)
                lineTo(-1e6, 1e6)
                close()
            }.build()
            fillPath(huge, Matrix.IDENTITY, mid, evenOdd = false, alpha = alpha, blendMode = blendMode)
            return
        }
        fillPath(clipPath, ctm, mid, evenOdd = false, alpha = alpha, blendMode = blendMode)
    }

    /**
     * True when this canvas needs resolved glyph outlines ([TextGlyph.outline]),
     * i.e. it actually paints (a render backend). Extraction canvases that only
     * read text + advances override this to `false`, letting the driver skip the
     * cost of resolving glyph outlines.
     */
    public val resolvesGlyphOutlines: Boolean get() = true

    /**
     * Paint one text run, already laid out into [glyphs] by the document handler.
     * Format-neutral: no PDF (or other) font object crosses the seam, so every
     * handler — PDF, EPUB, ... — drives text the same way.
     *
     * Embedded fonts ([hasOutlines] = true) carry a resolved [TextGlyph.outline]
     * per glyph; scale each by [fontSize]/[unitsPerEm], advance the pen by
     * [TextGlyph.advanceWidth] (1/1000 em), and paint under [textToDevice].
     * Non-embedded fonts ([hasOutlines] = false) carry no outlines; render
     * [TextGlyph.text] through a host typeface chosen from [fontSpec].
     */
    public fun drawGlyphs(
        glyphs: List<TextGlyph>,
        fontSize: Double,
        unitsPerEm: Int,
        hasOutlines: Boolean,
        fontSpec: FontSpec,
        textToDevice: Matrix,
        color: RgbColor,
        alpha: Double = 1.0,
        blendMode: BlendMode = BlendMode.Normal,
    )

    /** Push a clip to [path] under [ctm]. Matched 1:1 by [popClip]. */
    public fun pushClip(path: PdfPath, ctm: Matrix, evenOdd: Boolean)
    public fun popClip()

    /**
     * Paint an XObject Image under [ctm]. PDF defines the image's bounds as
     * the unit square (0..1, 0..1); the CTM already encodes the scale +
     * position. Backends that can decode [image] paint the pixels; others
     * draw a placeholder rather than throw.
     */
    public fun drawImage(image: ImageXObject, ctm: Matrix, alpha: Double = 1.0) { /* opt-in default */ }

    /**
     * Open a transparency group (ISO 32000-1 §11.4). Subsequent paints
     * accumulate into an offscreen layer; [endTransparencyGroup] composites
     * the layer back onto the parent with [blendMode] + [alpha].
     *
     * [isolated] = the group composites onto a transparent backdrop (true)
     * vs. the parent's current contents (false). [knockout] is a per-group
     * compositing flavour that's rare outside design PDFs; backends without
     * knockout support fall back to non-knockout.
     *
     * Default implementation: a no-op pair so backends that don't model
     * groups still render their content (just without isolation). That's
     * incorrect for fancy compositing but produces something visible.
     */
    public fun beginTransparencyGroup(
        bbox: Rectangle, ctm: Matrix,
        isolated: Boolean = false, knockout: Boolean = false,
        alpha: Double = 1.0, blendMode: BlendMode = BlendMode.Normal,
    ) { /* opt-in default */ }
    public fun endTransparencyGroup() { /* opt-in default */ }

    /**
     * Apply a soft mask to the content rendered inside [render] (ISO 32000-1
     * §11.6.5). The default implementation just calls [render] — backends
     * without offscreen compositing fall back to painting through the mask.
     *
     * Concrete implementations: open a saveLayer for the content,
     * `render()` it, then over-paint via [renderMask] using `DstIn` blend
     * mode so the mask's alpha determines the visible region.
     *
     * The shipped backends implement both SMask kinds: `Alpha` uses the mask
     * group's own alpha, and `Luminosity` composites the group over an opaque
     * black backdrop and converts luminance to alpha (Skia's LUMA filter, a
     * Compose colour-matrix filter, a per-pixel pass on AWT) per §11.6.5.2
     * (T-43).
     */
    public fun applySoftMask(
        kind: SoftMask.Kind,
        maskBBox: Rectangle, maskCtm: Matrix,
        render: () -> Unit,
        renderMask: (PdfCanvas) -> Unit,
    ) {
        render()
    }
}

/** A rectangle in PDF user-space — re-exposed here for the [PdfCanvas] surface. */
public typealias Rectangle = io.github.yuroyami.kitepdf.Rectangle

/** Backend that ignores everything — handy for benchmarks and content-stream sanity tests. */
public object NoopCanvas : PdfCanvas {
    override fun beginPage(widthPt: Double, heightPt: Double, deviceCtm: Matrix) {}
    override fun endPage() {}
    override fun fillPath(path: PdfPath, ctm: Matrix, color: RgbColor, evenOdd: Boolean, alpha: Double, blendMode: BlendMode) {}
    override fun strokePath(path: PdfPath, ctm: Matrix, color: RgbColor, lineWidth: Double, alpha: Double, blendMode: BlendMode, dashArray: List<Double>?, dashPhase: Double, lineCap: Int, lineJoin: Int, miterLimit: Double) {}
    override fun drawGlyphs(glyphs: List<TextGlyph>, fontSize: Double, unitsPerEm: Int, hasOutlines: Boolean, fontSpec: FontSpec, textToDevice: Matrix, color: RgbColor, alpha: Double, blendMode: BlendMode) {}
    override fun pushClip(path: PdfPath, ctm: Matrix, evenOdd: Boolean) {}
    override fun popClip() {}
}

/** Records every device call — useful for tests + verifying operator dispatch. */
public class RecordingCanvas : PdfCanvas {
    public sealed class Call {
        public data class BeginPage(val w: Double, val h: Double, val ctm: Matrix) : Call()
        public data object EndPage : Call()
        public data class Fill(
            val path: PdfPath, val ctm: Matrix, val color: RgbColor, val evenOdd: Boolean,
            val alpha: Double = 1.0, val blendMode: BlendMode = BlendMode.Normal,
        ) : Call()
        public data class Stroke(
            val path: PdfPath, val ctm: Matrix, val color: RgbColor, val lineWidth: Double,
            val alpha: Double = 1.0, val blendMode: BlendMode = BlendMode.Normal,
            val lineCap: Int = 0, val lineJoin: Int = 0, val miterLimit: Double = 10.0,
        ) : Call()
        public data class Glyphs(
            val glyphs: List<TextGlyph>, val fontSize: Double, val unitsPerEm: Int,
            val hasOutlines: Boolean, val fontSpec: FontSpec, val textToDevice: Matrix,
            val color: RgbColor, val alpha: Double = 1.0, val blendMode: BlendMode = BlendMode.Normal,
        ) : Call() {
            /** Decoded text of the run: the concatenated per-glyph unicode. */
            val text: String get() = glyphs.joinToString("") { it.text }
            override fun equals(other: Any?): Boolean =
                other is Glyphs && text == other.text &&
                    fontSize == other.fontSize && textToDevice == other.textToDevice && color == other.color &&
                    alpha == other.alpha && blendMode == other.blendMode
            override fun hashCode(): Int {
                var h = text.hashCode()
                h = 31 * h + fontSize.hashCode()
                h = 31 * h + textToDevice.hashCode()
                h = 31 * h + color.hashCode()
                h = 31 * h + alpha.hashCode()
                h = 31 * h + blendMode.hashCode()
                return h
            }
        }
        public data class PushClip(val path: PdfPath, val ctm: Matrix, val evenOdd: Boolean) : Call()
        public data object PopClip : Call()
        public data class Image(val image: ImageXObject, val ctm: Matrix, val alpha: Double = 1.0) : Call()
        public data class PushGroup(val bbox: Rectangle, val ctm: Matrix, val isolated: Boolean, val knockout: Boolean, val alpha: Double, val blendMode: BlendMode) : Call()
        public data object PopGroup : Call()
    }

    public val calls: MutableList<Call> = mutableListOf()

    override fun beginPage(widthPt: Double, heightPt: Double, deviceCtm: Matrix): Unit =
        calls.add(Call.BeginPage(widthPt, heightPt, deviceCtm)).let { }
    override fun endPage() { calls.add(Call.EndPage) }
    override fun fillPath(path: PdfPath, ctm: Matrix, color: RgbColor, evenOdd: Boolean, alpha: Double, blendMode: BlendMode) {
        calls.add(Call.Fill(path, ctm, color, evenOdd, alpha, blendMode))
    }
    override fun strokePath(path: PdfPath, ctm: Matrix, color: RgbColor, lineWidth: Double, alpha: Double, blendMode: BlendMode, dashArray: List<Double>?, dashPhase: Double, lineCap: Int, lineJoin: Int, miterLimit: Double) {
        calls.add(Call.Stroke(path, ctm, color, lineWidth, alpha, blendMode, lineCap, lineJoin, miterLimit))
    }
    override fun drawGlyphs(glyphs: List<TextGlyph>, fontSize: Double, unitsPerEm: Int, hasOutlines: Boolean, fontSpec: FontSpec, textToDevice: Matrix, color: RgbColor, alpha: Double, blendMode: BlendMode) {
        calls.add(Call.Glyphs(glyphs, fontSize, unitsPerEm, hasOutlines, fontSpec, textToDevice, color, alpha, blendMode))
    }
    override fun pushClip(path: PdfPath, ctm: Matrix, evenOdd: Boolean) {
        calls.add(Call.PushClip(path, ctm, evenOdd))
    }
    override fun popClip() { calls.add(Call.PopClip) }
    override fun drawImage(image: ImageXObject, ctm: Matrix, alpha: Double) {
        calls.add(Call.Image(image, ctm, alpha))
    }
    override fun beginTransparencyGroup(bbox: Rectangle, ctm: Matrix, isolated: Boolean, knockout: Boolean, alpha: Double, blendMode: BlendMode) {
        calls.add(Call.PushGroup(bbox, ctm, isolated, knockout, alpha, blendMode))
    }
    override fun endTransparencyGroup() { calls.add(Call.PopGroup) }
}
