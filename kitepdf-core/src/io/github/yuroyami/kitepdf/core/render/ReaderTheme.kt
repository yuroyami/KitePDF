package io.github.yuroyami.kitepdf.core.render

import io.github.yuroyami.kitepdf.core.KiteRectangle
import io.github.yuroyami.kitepdf.core.font.FontSpec
import io.github.yuroyami.kitepdf.core.font.TextGlyph

/**
 * A reading theme: a page [background] plus a [mapColor] transform applied to
 * every text / vector / border / CSS-background colour a page paints. Images and
 * gradients pass through untouched, so photos never invert. [withImages] lets the
 * theme recolour the images that are line art too.
 *
 * [Dark] and [Sepia] keep text readable: a colour with a contrast of 4.5 or more
 * on white paper keeps at least 4.5 on the theme's paper, and a weaker colour keeps
 * the contrast it had (WCAG 2.2, 1.4.3).
 *
 * Format-neutral: it themes any [KiteCanvas] (PDF or EPUB). Hand a theme to a
 * rasterizer, or wrap a canvas yourself:
 *
 * ```kotlin
 * page.renderTo(ReaderTheme.Dark.wrap(canvas), ctm)   // paint background yourself
 * ```
 *
 * Two themes are equal when they share the paper colour and the same colour
 * function, so a theme built inline with a lambda that captures nothing stays
 * one cache key across recompositions.
 */
public class ReaderTheme(
    /** Paper colour behind the page content. */
    public val background: RgbColor,
    /** Maps each content colour a page draws. Identity for [Light]. */
    public val mapColor: (RgbColor) -> RgbColor,
    /** What the theme does to images: [ReaderImages.Unchanged] unless [withImages] says otherwise (#458). */
    public val images: ReaderImages,
) {
    public constructor(background: RgbColor, mapColor: (RgbColor) -> RgbColor) : this(background, mapColor, ReaderImages.Unchanged)

    /**
     * This theme, doing [images] to the images of a page. With [ReaderImages.LineArt], a black symbol
     * on white turns as light as the text on dark paper, and a photo keeps its colours:
     *
     * ```kotlin
     * val dark = ReaderTheme.Dark.withImages(ReaderImages.LineArt)
     * ```
     */
    public fun withImages(images: ReaderImages): ReaderTheme =
        if (images == this.images) this else ReaderTheme(background, mapColor, images)

    /** Decorate [canvas] so its content colours are themed. Returns it unchanged for [Light]. */
    public fun wrap(canvas: KiteCanvas): KiteCanvas =
        if (mapColor === Light.mapColor) canvas else ThemedCanvas(canvas, mapColor, images, key)

    /** A key of this theme for the images it recolours, so two themes never share a bitmap. */
    private val key: String get() = "$background:${mapColor.hashCode()}"

    override fun equals(other: Any?): Boolean =
        this === other ||
            (other is ReaderTheme && background == other.background && mapColor == other.mapColor && images == other.images)

    override fun hashCode(): Int = 31 * (31 * background.hashCode() + mapColor.hashCode()) + images.hashCode()

    override fun toString(): String = "ReaderTheme(background=$background)"

    public companion object {
        /** No colour change; white paper. */
        public val Light: ReaderTheme = ReaderTheme(RgbColor.WHITE) { it }

        // Declared before the themes, which read them while the companion initializes.
        private val DARK_PAPER = RgbColor(0.11, 0.11, 0.12)
        private val SEPIA_PAPER = RgbColor(0.93, 0.87, 0.75)
        private val SEPIA_INK = RgbColor(0.30, 0.24, 0.18)
        private val DARK_CONTRAST = ThemeContrast(DARK_PAPER, lightest = RgbColor.WHITE, strongest = invertLightness(RgbColor.BLACK))
        private val SEPIA_CONTRAST = ThemeContrast(SEPIA_PAPER, lightest = SEPIA_PAPER, strongest = SEPIA_INK)

        /** Night mode: dark paper, content colours inverted in lightness (hue preserved). */
        public val Dark: ReaderTheme = ReaderTheme(DARK_PAPER, ::toDark)

        /** Warm reading: cream paper, ink softened toward warm brown. */
        public val Sepia: ReaderTheme = ReaderTheme(SEPIA_PAPER, ::toSepia)

        private fun toDark(c: RgbColor): RgbColor = DARK_CONTRAST.keep(c, invertLightness(c))

        /**
         * Maps each channel onto the range from the brown ink to the cream paper. White
         * stays paper and black becomes the ink, so a light fill stays light under dark
         * text instead of turning into a dark box (#253). A middle grey then darkens to
         * keep its contrast (#456).
         */
        private fun toSepia(c: RgbColor): RgbColor {
            fun mix(ink: Double, paper: Double, v: Double) = ink * (1.0 - v) + paper * v
            val tinted = RgbColor(
                mix(SEPIA_INK.r, SEPIA_PAPER.r, c.r),
                mix(SEPIA_INK.g, SEPIA_PAPER.g, c.g),
                mix(SEPIA_INK.b, SEPIA_PAPER.b, c.b),
            )
            return SEPIA_CONTRAST.keep(c, tinted)
        }

        /**
         * Invert perceived lightness while preserving hue/saturation: black text
         * becomes near-white, but a saturated link keeps its colour instead of
         * flipping to its complement (as a naive per-channel invert would). Pure
         * grays invert cleanly; coloured content shifts lightness by the same
         * delta, keeping its chroma spread. Pure blue has the middle lightness here,
         * so it stays blue, and [toDark] then lightens it to keep its contrast (#457).
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
 * Moves a theme's tinted colour to the luminance that keeps the contrast its source colour had on
 * white paper, measured as in WCAG 2.2 (#456, #457). Below [KNEE] the contrast stays the same. From
 * [KNEE] to 21, the most white paper allows, it compresses onto the range up to the contrast of
 * [strongest], the colour the theme gives black. The mapping keeps the order of lightness, so a box
 * and the text on it never swap.
 */
internal class ThemeContrast(
    paper: RgbColor,
    /** The colour a tint moves toward to get lighter: white on dark paper, the paper on light paper. */
    lightest: RgbColor,
    strongest: RgbColor,
) {
    private val paperY = luminance(paper)
    private val lightestY = luminance(lightest)
    private val lightestLinear = linear(lightest)
    private val darkPaper = paperY < luminance(strongest)
    private val best = ratio(paperY, luminance(strongest))

    /** [tinted], with the luminance that gives it the contrast on the paper that [source] had on white. */
    fun keep(source: RgbColor, tinted: RgbColor): RgbColor {
        val target = targetLuminance(ratio(1.0, luminance(source)))
        val y = luminance(tinted)
        if (kotlin.math.abs(y - target) < 1e-6) return tinted
        val (r, g, b) = linear(tinted)
        val moved = if (y > target) {
            // Darker: scale the light, which keeps the chromaticity.
            val k = target / y
            Triple(r * k, g * k, b * k)
        } else {
            // Lighter: mix toward the lightest colour of the theme, in linear light.
            val s = ((target - y) / (lightestY - y)).coerceIn(0.0, 1.0)
            Triple(r + s * (lightestLinear.first - r), g + s * (lightestLinear.second - g), b + s * (lightestLinear.third - b))
        }
        return RgbColor(encode(moved.first), encode(moved.second), encode(moved.third))
    }

    private fun targetLuminance(onWhite: Double): Double {
        val contrast = when {
            onWhite <= KNEE || best <= KNEE -> minOf(onWhite, best)
            else -> {
                val t = kotlin.math.ln(onWhite / KNEE) / kotlin.math.ln(MOST / KNEE)
                KNEE * kotlin.math.exp(t * kotlin.math.ln(best / KNEE))
            }
        }
        return if (darkPaper) contrast * (paperY + 0.05) - 0.05 else (paperY + 0.05) / contrast - 0.05
    }

    private companion object {
        /** The contrast that normal text needs (WCAG 2.2, 1.4.3). */
        const val KNEE = 4.5

        /** Black on white. */
        const val MOST = 21.0

        fun ratio(a: Double, b: Double): Double = (maxOf(a, b) + 0.05) / (minOf(a, b) + 0.05)

        fun channel(v: Double): Double =
            if (v <= 0.04045) v / 12.92 else kotlin.math.exp(2.4 * kotlin.math.ln((v + 0.055) / 1.055))

        fun encode(v: Double): Double {
            val c = v.coerceIn(0.0, 1.0)
            return if (c <= 0.0031308) c * 12.92 else 1.055 * kotlin.math.exp(kotlin.math.ln(c) / 2.4) - 0.055
        }

        fun linear(c: RgbColor): Triple<Double, Double, Double> = Triple(channel(c.r), channel(c.g), channel(c.b))

        fun luminance(c: RgbColor): Double = linear(c).let { (r, g, b) -> 0.2126 * r + 0.7152 * g + 0.0722 * b }
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
    private val images: ReaderImages = ReaderImages.Unchanged,
    private val key: String = "",
) : KiteCanvas {

    override val resolvesGlyphOutlines: Boolean get() = inner.resolvesGlyphOutlines

    override fun beginPage(widthPt: Double, heightPt: Double, deviceCtm: KiteMatrix) =
        inner.beginPage(widthPt, heightPt, deviceCtm)

    override fun endPage() = inner.endPage()

    override fun fillPath(path: KitePath, ctm: KiteMatrix, color: RgbColor, evenOdd: Boolean, alpha: Double, blendMode: KiteBlendMode) =
        inner.fillPath(path, ctm, mapColor(color), evenOdd, alpha, blendMode)

    override fun strokePath(
        path: KitePath, ctm: KiteMatrix, color: RgbColor, lineWidth: Double, alpha: Double, blendMode: KiteBlendMode,
        dashArray: List<Double>?, dashPhase: Double, lineCap: Int, lineJoin: Int, miterLimit: Double,
    ) = inner.strokePath(path, ctm, mapColor(color), lineWidth, alpha, blendMode, dashArray, dashPhase, lineCap, lineJoin, miterLimit)

    // Gradients pass through unthemed (rare in books; their colours live inside the shading).
    override fun fillShading(shading: KiteShading, ctm: KiteMatrix, clipPath: KitePath?, alpha: Double, blendMode: KiteBlendMode) =
        inner.fillShading(shading, ctm, clipPath, alpha, blendMode)

    override fun drawGlyphs(
        glyphs: List<TextGlyph>, fontSize: Double, unitsPerEm: Int, hasOutlines: Boolean, fontSpec: FontSpec,
        textToDevice: KiteMatrix, color: RgbColor, alpha: Double, blendMode: KiteBlendMode,
    ) = inner.drawGlyphs(glyphs, fontSize, unitsPerEm, hasOutlines, fontSpec, textToDevice, mapColor(color), alpha, blendMode)

    override fun hostGlyphOutline(text: String, fontSpec: FontSpec): KitePath? = inner.hostGlyphOutline(text, fontSpec)

    override fun pushClip(path: KitePath, ctm: KiteMatrix, evenOdd: Boolean) = inner.pushClip(path, ctm, evenOdd)
    override fun popClip() = inner.popClip()

    // Photos keep their real colours. Only line art takes the theme, and only when the theme asks (#458).
    override fun drawImage(image: KiteImageData, ctm: KiteMatrix, alpha: Double) = inner.drawImage(themed(image), ctm, alpha)
    override fun drawImage(image: KiteImageData, ctm: KiteMatrix, alpha: Double, blendMode: KiteBlendMode) =
        inner.drawImage(themed(image), ctm, alpha, blendMode)

    private fun themed(image: KiteImageData): KiteImageData =
        if (images == ReaderImages.LineArt) image.themedLineArt(mapColor, key) else image

    override fun beginTransparencyGroup(bbox: KiteRectangle, ctm: KiteMatrix, isolated: Boolean, knockout: Boolean, alpha: Double, blendMode: KiteBlendMode) =
        inner.beginTransparencyGroup(bbox, ctm, isolated, knockout, alpha, blendMode)

    override fun endTransparencyGroup() = inner.endTransparencyGroup()

    override fun applySoftMask(kind: SoftMask.Kind, maskBBox: KiteRectangle, maskCtm: KiteMatrix, render: () -> Unit, renderMask: (KiteCanvas) -> Unit) =
        inner.applySoftMask(kind, maskBBox, maskCtm, render, renderMask)
    override fun applySoftMask(kind: SoftMask.Kind, maskBBox: KiteRectangle, maskCtm: KiteMatrix, transfer: KiteMaskTransfer?, render: () -> Unit, renderMask: (KiteCanvas) -> Unit) =
        inner.applySoftMask(kind, maskBBox, maskCtm, transfer, render, renderMask)

    // The step paints through this canvas, so its content takes the theme too.
    override fun rasterStep(region: KiteRectangle, ctm: KiteMatrix, step: KiteRasterStep): Boolean =
        inner.rasterStep(region, ctm, step)
}
