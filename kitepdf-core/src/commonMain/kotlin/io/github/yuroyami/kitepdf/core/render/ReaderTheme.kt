package io.github.yuroyami.kitepdf.core.render

import io.github.yuroyami.kitepdf.core.font.FontSpec
import io.github.yuroyami.kitepdf.core.font.TextGlyph

/**
 * A reading theme: a page [background] plus a [mapColor] transform applied to
 * every text / vector / border / CSS-background colour a page paints. Images and
 * gradients pass through untouched, so photos never invert.
 *
 * Format-neutral — it themes any [KiteCanvas] (PDF or EPUB). Hand a theme to a
 * rasterizer, or wrap a canvas yourself:
 *
 * ```kotlin
 * page.renderTo(ReaderTheme.Dark.wrap(canvas), ctm)   // paint background yourself
 * ```
 */
public class ReaderTheme(
    /** Paper colour behind the page content. */
    public val background: RgbColor,
    /** Maps each content colour a page draws. Identity for [Light]. */
    public val mapColor: (RgbColor) -> RgbColor,
) {
    /** Decorate [canvas] so its content colours are themed. Returns it unchanged for [Light]. */
    public fun wrap(canvas: KiteCanvas): KiteCanvas =
        if (this === Light) canvas else ThemedCanvas(canvas, mapColor)

    public companion object {
        /** No colour change; white paper. */
        public val Light: ReaderTheme = ReaderTheme(RgbColor.WHITE) { it }

        /** Night mode: dark paper, content colours inverted in lightness (hue preserved). */
        public val Dark: ReaderTheme = ReaderTheme(RgbColor(0.11, 0.11, 0.12), ::invertLightness)

        /** Warm reading: cream paper, ink softened toward warm brown. */
        public val Sepia: ReaderTheme = ReaderTheme(RgbColor(0.93, 0.87, 0.75)) { c ->
            RgbColor(minOf(c.r, 0.30), minOf(c.g, 0.24), minOf(c.b, 0.18))
        }

        /**
         * Invert perceived lightness while preserving hue/saturation: black text
         * becomes near-white, but a saturated link keeps its colour instead of
         * flipping to its complement (as a naive per-channel invert would). Pure
         * grays invert cleanly; coloured content shifts lightness by the same
         * delta, keeping its chroma spread.
         */
        private fun invertLightness(c: RgbColor): RgbColor {
            val mx = maxOf(c.r, c.g, c.b)
            val mn = minOf(c.r, c.g, c.b)
            val l = (mx + mn) / 2.0
            if (mx == mn) return RgbColor.gray(1.0 - l)
            val d = (1.0 - l) - l
            fun sh(v: Double) = (v + d).coerceIn(0.0, 1.0)
            return RgbColor(sh(c.r), sh(c.g), sh(c.b))
        }
    }
}

/**
 * A [KiteCanvas] decorator that remaps every content colour through [mapColor]
 * before forwarding to [inner]. Fill / stroke / glyph colours (text, vector art,
 * borders, CSS backgrounds) are themed; images, gradients, clips, groups and
 * soft masks pass through so photos keep their real colours. Drives [ReaderTheme].
 */
internal class ThemedCanvas(
    private val inner: KiteCanvas,
    private val mapColor: (RgbColor) -> RgbColor,
) : KiteCanvas {

    override val resolvesGlyphOutlines: Boolean get() = inner.resolvesGlyphOutlines

    override fun beginPage(widthPt: Double, heightPt: Double, deviceCtm: Matrix) =
        inner.beginPage(widthPt, heightPt, deviceCtm)

    override fun endPage() = inner.endPage()

    override fun fillPath(path: KitePath, ctm: Matrix, color: RgbColor, evenOdd: Boolean, alpha: Double, blendMode: BlendMode) =
        inner.fillPath(path, ctm, mapColor(color), evenOdd, alpha, blendMode)

    override fun strokePath(
        path: KitePath, ctm: Matrix, color: RgbColor, lineWidth: Double, alpha: Double, blendMode: BlendMode,
        dashArray: List<Double>?, dashPhase: Double, lineCap: Int, lineJoin: Int, miterLimit: Double,
    ) = inner.strokePath(path, ctm, mapColor(color), lineWidth, alpha, blendMode, dashArray, dashPhase, lineCap, lineJoin, miterLimit)

    // Gradients pass through unthemed (rare in books; their colours live inside the shading).
    override fun fillShading(shading: KiteShading, ctm: Matrix, clipPath: KitePath?, alpha: Double, blendMode: BlendMode) =
        inner.fillShading(shading, ctm, clipPath, alpha, blendMode)

    override fun drawGlyphs(
        glyphs: List<TextGlyph>, fontSize: Double, unitsPerEm: Int, hasOutlines: Boolean, fontSpec: FontSpec,
        textToDevice: Matrix, color: RgbColor, alpha: Double, blendMode: BlendMode,
    ) = inner.drawGlyphs(glyphs, fontSize, unitsPerEm, hasOutlines, fontSpec, textToDevice, mapColor(color), alpha, blendMode)

    override fun pushClip(path: KitePath, ctm: Matrix, evenOdd: Boolean) = inner.pushClip(path, ctm, evenOdd)
    override fun popClip() = inner.popClip()

    // Images are NOT themed — photos should keep their real colours.
    override fun drawImage(image: ImageXObject, ctm: Matrix, alpha: Double) = inner.drawImage(image, ctm, alpha)

    override fun beginTransparencyGroup(bbox: Rectangle, ctm: Matrix, isolated: Boolean, knockout: Boolean, alpha: Double, blendMode: BlendMode) =
        inner.beginTransparencyGroup(bbox, ctm, isolated, knockout, alpha, blendMode)

    override fun endTransparencyGroup() = inner.endTransparencyGroup()

    override fun applySoftMask(kind: SoftMask.Kind, maskBBox: Rectangle, maskCtm: Matrix, render: () -> Unit, renderMask: (KiteCanvas) -> Unit) =
        inner.applySoftMask(kind, maskBBox, maskCtm, render, renderMask)
}
