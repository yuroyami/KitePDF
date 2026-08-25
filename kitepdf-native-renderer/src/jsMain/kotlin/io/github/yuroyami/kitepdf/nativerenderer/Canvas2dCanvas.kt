package io.github.yuroyami.kitepdf.nativerenderer

import io.github.yuroyami.kitepdf.core.render.paintComplexShading
import io.github.yuroyami.kitepdf.core.KiteRectangle
import io.github.yuroyami.kitepdf.core.font.KiteFontFamily
import io.github.yuroyami.kitepdf.core.font.FontSpec
import io.github.yuroyami.kitepdf.core.font.TextGlyph
import io.github.yuroyami.kitepdf.core.render.KiteBlendMode
import io.github.yuroyami.kitepdf.core.render.KiteImageData
import io.github.yuroyami.kitepdf.core.render.KiteMatrix
import io.github.yuroyami.kitepdf.core.render.KiteCanvas
import io.github.yuroyami.kitepdf.core.render.KitePath
import io.github.yuroyami.kitepdf.core.render.KiteShading
import io.github.yuroyami.kitepdf.core.render.RgbColor
import io.github.yuroyami.kitepdf.core.render.SoftMask
import io.github.yuroyami.kitepdf.core.render.sampleStops
import io.github.yuroyami.kitepdf.core.render.toRgbaBytes
import kotlinx.browser.document
import org.khronos.webgl.Int8Array
import org.khronos.webgl.Uint8Array
import org.khronos.webgl.Uint8ClampedArray
import org.w3c.dom.CanvasRenderingContext2D
import org.w3c.dom.HTMLCanvasElement
import org.w3c.dom.HTMLImageElement
import org.w3c.dom.ImageData
import org.w3c.dom.Path2D
import org.w3c.dom.url.URL
import org.w3c.files.Blob
import org.w3c.files.BlobPropertyBag

/**
 * [KiteCanvas] backed by a browser-side `CanvasRenderingContext2D`. Pure
 * Kotlin/JS + DOM: no Compose for Web, no Skia/WASM bundle.
 *
 * The right choice for in-browser PDF viewers that want minimal bundle
 * size and to inherit whatever rendering acceleration the browser already
 * provides for `<canvas>`.
 *
 * Honest scope:
 *
 *  - Path operations, gradients, blend modes (all 16), clipping,
 *    transparency groups, soft masks: ✅ rendered via the standard
 *    Canvas2D API.
 *  - Embedded image XObjects: ⚠️ JPEG / JP2 are decoded asynchronously by
 *    the browser (`HTMLImageElement.src = …`), which doesn't fit the
 *    renderer's synchronous draw pass. v1 paints placeholders for image
 *    XObjects; an async render path is roadmapped.
 */
public class Canvas2dCanvas(private val ctx: CanvasRenderingContext2D) : KiteCanvas {

    private var openLayers = 0

    override fun beginPage(widthPt: Double, heightPt: Double, deviceCtm: KiteMatrix) {
        openLayers = 0
    }

    override fun endPage() {
        while (openLayers > 0) {
            ctx.restore(); openLayers--
        }
    }

    override fun fillPath(
        path: KitePath, ctm: KiteMatrix, color: RgbColor, evenOdd: Boolean,
        alpha: Double, blendMode: KiteBlendMode,
    ) {
        val p = toPath2D(path, ctm)
        ctx.save()
        try {
            ctx.fillStyle = color.toCssRgba(alpha)
            ctx.globalCompositeOperation = blendMode.toCanvas()
            // Path2D + fill(path, fillRule): fillRule is "evenodd" or "nonzero"
            ctx.asDynamic().fill(p, if (evenOdd) "evenodd" else "nonzero")
        } finally {
            ctx.restore()
        }
    }

    override fun strokePath(
        path: KitePath, ctm: KiteMatrix, color: RgbColor, lineWidth: Double,
        alpha: Double, blendMode: KiteBlendMode,
        dashArray: List<Double>?, dashPhase: Double,
        lineCap: Int, lineJoin: Int, miterLimit: Double,
    ) {
        val p = toPath2D(path, ctm)
        ctx.save()
        try {
            ctx.strokeStyle = color.toCssRgba(alpha)
            val avgScale = (ctm.scaleX() + ctm.scaleY()) * 0.5
            ctx.lineWidth = (lineWidth * avgScale).coerceAtLeast(0.1)
            // lineCap/lineJoin are JS string-union types; assign the raw strings.
            ctx.asDynamic().lineCap = when (lineCap) { 1 -> "round"; 2 -> "square"; else -> "butt" }
            ctx.asDynamic().lineJoin = when (lineJoin) { 1 -> "round"; 2 -> "bevel"; else -> "miter" }
            ctx.miterLimit = miterLimit.coerceAtLeast(1.0)
            if (!dashArray.isNullOrEmpty()) {
                // Dash lengths are user-space units; device px = unit × scale.
                ctx.setLineDash(dashArray.map { it * avgScale }.toTypedArray())
                ctx.lineDashOffset = dashPhase * avgScale
            }
            ctx.globalCompositeOperation = blendMode.toCanvas()
            ctx.stroke(p)
        } finally {
            ctx.restore()
        }
    }

    override fun drawGlyphs(
        glyphs: List<TextGlyph>,
        fontSize: Double,
        unitsPerEm: Int,
        hasOutlines: Boolean,
        fontSpec: FontSpec,
        textToDevice: KiteMatrix,
        color: RgbColor,
        alpha: Double,
        blendMode: KiteBlendMode,
    ) {
        if (glyphs.isEmpty()) return
        if (!hasOutlines) {
            drawTextViaSystemFont(glyphs, fontSize, fontSpec, textToDevice, color, alpha, blendMode)
            return
        }

        val unitScale = fontSize / unitsPerEm   // glyph outlines: font units → text space
        val advanceScale = fontSize / 1000.0    // advances are 1/1000 em, not font units
        var drewAny = false
        ctx.save()
        try {
            ctx.fillStyle = color.toCssRgba(alpha)
            ctx.globalCompositeOperation = blendMode.toCanvas()
            var penX = 0.0
            for (glyph in glyphs) {
                val outline = glyph.outline
                if (outline != null && !outline.isEmpty()) {
                    val glyphMatrix = textToDevice
                        .concat(KiteMatrix.translation(penX + glyph.xOffset * unitScale, glyph.yOffset * unitScale))
                        .concat(KiteMatrix(unitScale, 0.0, 0.0, unitScale, 0.0, 0.0))
                    val p = toPath2D(outline, glyphMatrix)
                    ctx.asDynamic().fill(p, "nonzero")
                    drewAny = true
                }
                penX += glyph.advanceWidth * advanceScale
            }
        } finally {
            ctx.restore()
        }
        // Embedded font present but produced no glyphs (e.g. a subset we can't
        // decode). Fall back to a system font rather than rendering blank.
        if (!drewAny && glyphs.any { it.text.isNotBlank() }) {
            drawTextViaSystemFont(glyphs, fontSize, fontSpec, textToDevice, color, alpha, blendMode)
        }
    }

    /**
     * Fallback for non-embedded fonts (e.g. the Standard-14). Renders with a
     * platform logical font via a CSS `font` string: zero bundled bytes, since
     * the browser already ships serif / sans-serif / monospace faces that are
     * metric-compatible stand-ins for Times / Helvetica / Courier. Mirrors the
     * AWT / Compose backends.
     */
    private fun drawTextViaSystemFont(
        glyphs: List<TextGlyph>,
        fontSize: Double,
        fontSpec: FontSpec,
        textMatrix: KiteMatrix,
        color: RgbColor,
        alpha: Double,
        blendMode: KiteBlendMode,
    ) {
        val text = glyphs.joinToString("") { it.text }
        if (text.isBlank()) return

        // Decompose the (text-space → device) matrix like the Compose/AWT path:
        // translation + rotation + *positive* scale magnitudes, so the device
        // Y-flip baked into the matrix doesn't render the glyphs mirrored.
        val sx = kotlin.math.sqrt(textMatrix.a * textMatrix.a + textMatrix.b * textMatrix.b)
        val sy = kotlin.math.sqrt(textMatrix.c * textMatrix.c + textMatrix.d * textMatrix.d)
        if (sy == 0.0) return
        val rotation = kotlin.math.atan2(textMatrix.b, textMatrix.a)
        val renderedSize = (fontSize * sy).coerceAtLeast(0.01)

        ctx.save()
        try {
            ctx.translate(textMatrix.e, textMatrix.f)
            if (rotation != 0.0) ctx.rotate(rotation)
            if (sx != sy) ctx.scale(sx / sy, 1.0)
            ctx.fillStyle = color.toCssRgba(alpha)
            ctx.globalCompositeOperation = blendMode.toCanvas()
            ctx.font = systemFontFor(fontSpec, renderedSize)
            // Position each glyph by the PDF's OWN advance widths (1/1000 em),
            // not the substitute font's natural metrics, otherwise spacing
            // drifts and glyphs crowd together / overlap.
            var penX = 0.0
            val advScale = renderedSize / 1000.0
            for (glyph in glyphs) {
                val t = glyph.text
                if (t.isNotEmpty() && t != " ") ctx.fillText(t, penX, 0.0)
                penX += glyph.advanceWidth * advScale
            }
        } finally {
            ctx.restore()
        }
    }

    /** Map a non-embedded PDF font to a CSS `font` string (mirrors AwtCanvas's family/style choice). */
    private fun systemFontFor(spec: FontSpec, sizePx: Double): String {
        val family = when (spec.family) {
            KiteFontFamily.Serif -> "serif"
            KiteFontFamily.Monospace -> "monospace"
            KiteFontFamily.SansSerif -> "sans-serif"
        }
        val bold = if (spec.bold) "bold " else ""
        val italic = if (spec.italic) "italic " else ""
        return "$italic$bold${sizePx}px $family"
    }

    override fun fillShading(
        shading: KiteShading, ctm: KiteMatrix, clipPath: KitePath?,
        alpha: Double, blendMode: KiteBlendMode,
    ) {
        if (paintComplexShading(shading, ctm, clipPath, alpha, blendMode)) return
        val stops = shading.sampleStops(32) ?: return

        val gradient = when (shading) {
            is KiteShading.Axial -> {
                val (x0, y0) = ctm.transformPoint(shading.coords[0], shading.coords[1])
                val (x1, y1) = ctm.transformPoint(shading.coords[2], shading.coords[3])
                ctx.createLinearGradient(x0, y0, x1, y1)
            }
            is KiteShading.Radial -> {
                // True PDF two-circle radial. Canvas2D supports both circles.
                val sc = kotlin.math.sqrt(ctm.a * ctm.a + ctm.b * ctm.b)
                val (x0, y0) = ctm.transformPoint(shading.coords[0], shading.coords[1])
                val r0 = (shading.coords[2] * sc).coerceAtLeast(0.0)
                val (x1, y1) = ctm.transformPoint(shading.coords[3], shading.coords[4])
                val r1 = (shading.coords[5] * sc).coerceAtLeast(0.1)
                ctx.createRadialGradient(x0, y0, r0, x1, y1, r1)
            }
            is KiteShading.Unsupported -> return
            else -> return // complex shading types already handled by paintComplexShading
        }
        for (i in stops.colors.indices) {
            gradient.addColorStop(stops.offsets[i], stops.colors[i].toCssRgba(alpha))
        }

        ctx.save()
        try {
            ctx.fillStyle = gradient
            ctx.globalCompositeOperation = blendMode.toCanvas()
            if (clipPath != null) {
                val p = toPath2D(clipPath, ctm)
                ctx.asDynamic().fill(p, "nonzero")
            } else {
                ctx.fillRect(0.0, 0.0, ctx.canvas.width.toDouble(), ctx.canvas.height.toDouble())
            }
        } finally {
            ctx.restore()
        }
    }

    override fun pushClip(path: KitePath, ctm: KiteMatrix, evenOdd: Boolean) {
        ctx.save()
        openLayers++
        val p = toPath2D(path, ctm)
        ctx.asDynamic().clip(p, if (evenOdd) "evenodd" else "nonzero")
    }

    override fun popClip() {
        if (openLayers > 0) {
            ctx.restore(); openLayers--
        }
    }

    override fun drawImage(image: KiteImageData, ctm: KiteMatrix, alpha: Double) {
        // Decoded samples paint synchronously; that is what every successful
        // JPEG / JPX / JBIG2 decode produces. Encoded kinds core could not
        // decode keep the placeholder: browser decoding is async, and the
        // preload API remains the roadmap item (ledger D-3).
        val rgba = if (image.kind == KiteImageData.Kind.RAW) image.toRgbaBytes() else null
        if (rgba == null) {
            drawPlaceholder(ctm, alpha)
            return
        }
        // putImageData ignores the transform, so stage on an offscreen canvas
        // and drawImage that under the CTM.
        val off = document.createElement("canvas") as HTMLCanvasElement
        off.width = image.width
        off.height = image.height
        val offCtx = off.getContext("2d") as CanvasRenderingContext2D
        val i8 = rgba.unsafeCast<Int8Array>()
        val clamped = Uint8ClampedArray(i8.buffer, i8.byteOffset, i8.length)
        offCtx.putImageData(ImageData(clamped, image.width, image.height), 0.0, 0.0)
        ctx.save()
        try {
            ctx.setTransform(ctm.a, ctm.b, ctm.c, ctm.d, ctm.e, ctm.f)
            ctx.globalAlpha = alpha.coerceIn(0.0, 1.0)
            // Unit square, bitmap row 0 on the top edge (v = 1): the Skia
            // mapping, translate up one unit and flip Y.
            ctx.translate(0.0, 1.0)
            ctx.scale(1.0 / image.width, -1.0 / image.height)
            ctx.drawImage(off, 0.0, 0.0)
        } finally {
            ctx.restore()
        }
    }

    private fun drawPlaceholder(ctm: KiteMatrix, alpha: Double) {
        ctx.save()
        try {
            ctx.setTransform(ctm.a, ctm.b, ctm.c, ctm.d, ctm.e, ctm.f)
            ctx.globalAlpha = alpha.coerceIn(0.0, 1.0)
            ctx.fillStyle = "#E0E0E0"
            // The CTM maps the unit square (0,0)-(1,1); same frame as drawImage.
            ctx.fillRect(0.0, 0.0, 1.0, 1.0)
            ctx.strokeStyle = "#888888"
            ctx.lineWidth = 0.01
            ctx.strokeRect(0.0, 0.0, 1.0, 1.0)
        } finally {
            ctx.restore()
        }
    }

    override fun beginTransparencyGroup(
        bbox: KiteRectangle, ctm: KiteMatrix,
        isolated: Boolean, knockout: Boolean,
        alpha: Double, blendMode: KiteBlendMode,
    ) {
        // Canvas2D has no `saveLayer`. We approximate by stacking
        // globalAlpha + globalCompositeOperation. True isolated/knockout
        // semantics (paint to an off-screen and composite at end) is a
        // roadmap item.
        ctx.save()
        openLayers++
        ctx.globalAlpha = alpha.coerceIn(0.0, 1.0)
        ctx.globalCompositeOperation = blendMode.toCanvas()
    }

    override fun endTransparencyGroup() {
        if (openLayers > 0) {
            ctx.restore(); openLayers--
        }
    }

    override fun applySoftMask(
        kind: SoftMask.Kind,
        maskBBox: KiteRectangle, maskCtm: KiteMatrix,
        render: () -> Unit,
        renderMask: (KiteCanvas) -> Unit,
    ) {
        // Render content, then over-paint the mask group with
        // `destination-in` so the mask's alpha clips the content.
        // Canvas2D applies this to the whole context, which suits the
        // common case of "this whole paint is masked".
        ctx.save()
        try {
            render()
            ctx.globalCompositeOperation = "destination-in"
            renderMask(this)
        } finally {
            ctx.restore()
        }
    }

    /* ─── Helpers ─────────────────────────────────────────────────────────── */

    private fun toPath2D(src: KitePath, ctm: KiteMatrix): Path2D {
        val p = Path2D()
        for (seg in src.segments) {
            when (seg) {
                is KitePath.Segment.MoveTo -> {
                    val (x, y) = ctm.transformPoint(seg.x, seg.y)
                    p.moveTo(x, y)
                }
                is KitePath.Segment.LineTo -> {
                    val (x, y) = ctm.transformPoint(seg.x, seg.y)
                    p.lineTo(x, y)
                }
                is KitePath.Segment.CurveTo -> {
                    val (x1, y1) = ctm.transformPoint(seg.x1, seg.y1)
                    val (x2, y2) = ctm.transformPoint(seg.x2, seg.y2)
                    val (x3, y3) = ctm.transformPoint(seg.x3, seg.y3)
                    p.bezierCurveTo(x1, y1, x2, y2, x3, y3)
                }
                is KitePath.Segment.QuadTo -> {
                    val (x1, y1) = ctm.transformPoint(seg.x1, seg.y1)
                    val (x2, y2) = ctm.transformPoint(seg.x2, seg.y2)
                    p.quadraticCurveTo(x1, y1, x2, y2)
                }
                KitePath.Segment.Close -> p.closePath()
            }
        }
        return p
    }

    private fun RgbColor.toCssRgba(alpha: Double): String {
        val r8 = (r.coerceIn(0.0, 1.0) * 255).toInt()
        val g8 = (g.coerceIn(0.0, 1.0) * 255).toInt()
        val b8 = (b.coerceIn(0.0, 1.0) * 255).toInt()
        val a = alpha.coerceIn(0.0, 1.0)
        return "rgba($r8, $g8, $b8, $a)"
    }

    private fun KiteBlendMode.toCanvas(): String = when (this) {
        KiteBlendMode.Normal -> "source-over"
        KiteBlendMode.Multiply -> "multiply"
        KiteBlendMode.Screen -> "screen"
        KiteBlendMode.Overlay -> "overlay"
        KiteBlendMode.Darken -> "darken"
        KiteBlendMode.Lighten -> "lighten"
        KiteBlendMode.ColorDodge -> "color-dodge"
        KiteBlendMode.ColorBurn -> "color-burn"
        KiteBlendMode.HardLight -> "hard-light"
        KiteBlendMode.SoftLight -> "soft-light"
        KiteBlendMode.Difference -> "difference"
        KiteBlendMode.Exclusion -> "exclusion"
        KiteBlendMode.Hue -> "hue"
        KiteBlendMode.Saturation -> "saturation"
        KiteBlendMode.Color -> "color"
        KiteBlendMode.Luminosity -> "luminosity"
    }
}
