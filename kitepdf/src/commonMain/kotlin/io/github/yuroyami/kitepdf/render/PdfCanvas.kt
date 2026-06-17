package io.github.yuroyami.kitepdf.render

import io.github.yuroyami.kitepdf.font.PdfFont

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
interface PdfCanvas {

    /**
     * Set up for a page render — called once before any draw call, with the
     * page dimensions (in PDF user units, 1pt = 1/72 inch) and the desired
     * device CTM (e.g. flip Y and scale to fit a target rectangle).
     */
    fun beginPage(widthPt: Double, heightPt: Double, deviceCtm: Matrix)
    fun endPage()

    /**
     * Fill [path] under [ctm] with [color] at [alpha] (0..1) under the given
     * [blendMode]. The default `alpha = 1.0` + `blendMode = Normal` is the
     * plain over-paint that PDF assumes when no ExtGState modifies the state.
     */
    fun fillPath(
        path: PdfPath, ctm: Matrix, color: RgbColor, evenOdd: Boolean,
        alpha: Double = 1.0, blendMode: BlendMode = BlendMode.Normal,
    )

    /** Stroke [path] under [ctm] with [color] at [lineWidth] user-units, [alpha], [blendMode]. */
    fun strokePath(
        path: PdfPath, ctm: Matrix, color: RgbColor, lineWidth: Double,
        alpha: Double = 1.0, blendMode: BlendMode = BlendMode.Normal,
        dashArray: List<Double>? = null, dashPhase: Double = 0.0,
    )

    /**
     * Fill [clipPath] (under [ctm]) with the gradient defined by [shading].
     * If [clipPath] is `null` the shading covers the current clip region
     * (or the page, when no clip is active) — the spec's `sh` operator.
     *
     * Default impl falls back to a flat-colour fill using the midpoint
     * sample; backends that can render real gradients override.
     */
    fun fillShading(
        shading: PdfShading, ctm: Matrix, clipPath: PdfPath?,
        alpha: Double = 1.0, blendMode: BlendMode = BlendMode.Normal,
    ) {
        if (clipPath == null) return
        val stops = shading.sampleStops(2) ?: return
        val mid = stops.colors[stops.colors.size / 2]
        fillPath(clipPath, ctm, mid, evenOdd = false, alpha = alpha, blendMode = blendMode)
    }

    /**
     * Draw the show-text run [bytes] (verbatim from a `Tj` / `TJ` operand)
     * at the position implied by [textMatrix].
     */
    fun drawText(
        bytes: ByteArray,
        font: PdfFont,
        fontSize: Double,
        textMatrix: Matrix,
        fillColor: RgbColor,
        alpha: Double = 1.0,
        blendMode: BlendMode = BlendMode.Normal,
    )

    /** Push a clip to [path] under [ctm]. Matched 1:1 by [popClip]. */
    fun pushClip(path: PdfPath, ctm: Matrix, evenOdd: Boolean)
    fun popClip()

    /**
     * Paint an XObject Image under [ctm]. PDF defines the image's bounds as
     * the unit square (0..1, 0..1); the CTM already encodes the scale +
     * position. Backends that can decode [image] paint the pixels; others
     * draw a placeholder rather than throw.
     */
    fun drawImage(image: ImageXObject, ctm: Matrix, alpha: Double = 1.0) { /* opt-in default */ }

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
    fun beginTransparencyGroup(
        bbox: Rectangle, ctm: Matrix,
        isolated: Boolean = false, knockout: Boolean = false,
        alpha: Double = 1.0, blendMode: BlendMode = BlendMode.Normal,
    ) { /* opt-in default */ }
    fun endTransparencyGroup() { /* opt-in default */ }

    /**
     * Apply a soft mask to the content rendered inside [render] (ISO 32000-1
     * §11.6.5). The default implementation just calls [render] — backends
     * without offscreen compositing fall back to painting through the mask.
     *
     * Concrete implementations: open a saveLayer for the content,
     * `render()` it, then over-paint via [renderMask] using `DstIn` blend
     * mode so the mask's alpha determines the visible region.
     *
     * Honest scope: KitePDF v0.0.x's ComposeCanvas implements the
     * `Alpha` SMask kind correctly and approximates `Luminosity` as if it
     * were alpha. True luminosity-to-alpha conversion requires a custom
     * shader and is on the roadmap.
     */
    fun applySoftMask(
        kind: SoftMask.Kind,
        maskBBox: Rectangle, maskCtm: Matrix,
        render: () -> Unit,
        renderMask: (PdfCanvas) -> Unit,
    ) {
        render()
    }
}

/** A rectangle in PDF user-space — re-exposed here for the [PdfCanvas] surface. */
typealias Rectangle = io.github.yuroyami.kitepdf.Rectangle

/** Backend that ignores everything — handy for benchmarks and content-stream sanity tests. */
object NoopCanvas : PdfCanvas {
    override fun beginPage(widthPt: Double, heightPt: Double, deviceCtm: Matrix) {}
    override fun endPage() {}
    override fun fillPath(path: PdfPath, ctm: Matrix, color: RgbColor, evenOdd: Boolean, alpha: Double, blendMode: BlendMode) {}
    override fun strokePath(path: PdfPath, ctm: Matrix, color: RgbColor, lineWidth: Double, alpha: Double, blendMode: BlendMode, dashArray: List<Double>?, dashPhase: Double) {}
    override fun drawText(bytes: ByteArray, font: PdfFont, fontSize: Double, textMatrix: Matrix, fillColor: RgbColor, alpha: Double, blendMode: BlendMode) {}
    override fun pushClip(path: PdfPath, ctm: Matrix, evenOdd: Boolean) {}
    override fun popClip() {}
}

/** Records every device call — useful for tests + verifying operator dispatch. */
class RecordingCanvas : PdfCanvas {
    sealed class Call {
        data class BeginPage(val w: Double, val h: Double, val ctm: Matrix) : Call()
        data object EndPage : Call()
        data class Fill(
            val path: PdfPath, val ctm: Matrix, val color: RgbColor, val evenOdd: Boolean,
            val alpha: Double = 1.0, val blendMode: BlendMode = BlendMode.Normal,
        ) : Call()
        data class Stroke(
            val path: PdfPath, val ctm: Matrix, val color: RgbColor, val lineWidth: Double,
            val alpha: Double = 1.0, val blendMode: BlendMode = BlendMode.Normal,
        ) : Call()
        data class Text(
            val bytes: ByteArray, val font: PdfFont, val fontSize: Double,
            val textMatrix: Matrix, val fillColor: RgbColor,
            val alpha: Double = 1.0, val blendMode: BlendMode = BlendMode.Normal,
        ) : Call() {
            val text: String get() = font.decode(bytes)
            override fun equals(other: Any?): Boolean =
                other is Text && bytes.contentEquals(other.bytes) && font == other.font &&
                    fontSize == other.fontSize && textMatrix == other.textMatrix && fillColor == other.fillColor &&
                    alpha == other.alpha && blendMode == other.blendMode
            override fun hashCode(): Int {
                var h = bytes.contentHashCode()
                h = 31 * h + font.hashCode()
                h = 31 * h + fontSize.hashCode()
                h = 31 * h + textMatrix.hashCode()
                h = 31 * h + fillColor.hashCode()
                h = 31 * h + alpha.hashCode()
                h = 31 * h + blendMode.hashCode()
                return h
            }
        }
        data class PushClip(val path: PdfPath, val ctm: Matrix, val evenOdd: Boolean) : Call()
        data object PopClip : Call()
        data class Image(val image: ImageXObject, val ctm: Matrix, val alpha: Double = 1.0) : Call()
        data class PushGroup(val bbox: Rectangle, val ctm: Matrix, val isolated: Boolean, val knockout: Boolean, val alpha: Double, val blendMode: BlendMode) : Call()
        data object PopGroup : Call()
    }

    val calls = mutableListOf<Call>()

    override fun beginPage(widthPt: Double, heightPt: Double, deviceCtm: Matrix) =
        calls.add(Call.BeginPage(widthPt, heightPt, deviceCtm)).let { }
    override fun endPage() { calls.add(Call.EndPage) }
    override fun fillPath(path: PdfPath, ctm: Matrix, color: RgbColor, evenOdd: Boolean, alpha: Double, blendMode: BlendMode) {
        calls.add(Call.Fill(path, ctm, color, evenOdd, alpha, blendMode))
    }
    override fun strokePath(path: PdfPath, ctm: Matrix, color: RgbColor, lineWidth: Double, alpha: Double, blendMode: BlendMode, dashArray: List<Double>?, dashPhase: Double) {
        calls.add(Call.Stroke(path, ctm, color, lineWidth, alpha, blendMode))
    }
    override fun drawText(bytes: ByteArray, font: PdfFont, fontSize: Double, textMatrix: Matrix, fillColor: RgbColor, alpha: Double, blendMode: BlendMode) {
        calls.add(Call.Text(bytes, font, fontSize, textMatrix, fillColor, alpha, blendMode))
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
