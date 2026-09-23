package io.github.yuroyami.kitepdf.nativerenderer

import io.github.yuroyami.kitepdf.core.render.paintComplexShading
import io.github.yuroyami.kitepdf.core.KiteRectangle
import io.github.yuroyami.kitepdf.core.font.KiteFontFamily
import io.github.yuroyami.kitepdf.core.font.FontSpec
import io.github.yuroyami.kitepdf.core.font.TextGlyph
import io.github.yuroyami.kitepdf.core.render.KiteBlendMode
import io.github.yuroyami.kitepdf.core.render.KiteBitmapCache
import io.github.yuroyami.kitepdf.core.render.KiteImageData
import io.github.yuroyami.kitepdf.core.render.KiteImageSampling
import io.github.yuroyami.kitepdf.core.render.KiteMatrix
import io.github.yuroyami.kitepdf.core.render.KiteCanvas
import io.github.yuroyami.kitepdf.core.render.KitePath
import io.github.yuroyami.kitepdf.core.render.KiteShading
import io.github.yuroyami.kitepdf.core.render.RgbColor
import io.github.yuroyami.kitepdf.core.render.SoftMask
import io.github.yuroyami.kitepdf.core.render.imageSampling
import io.github.yuroyami.kitepdf.core.render.sampleStops
import io.github.yuroyami.kitepdf.core.render.shrinkRgba
import io.github.yuroyami.kitepdf.core.render.toRgbaBytes
import kotlinx.browser.document
import kotlin.math.ceil
import kotlin.math.floor
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
 *  - Path operations, gradients, blend modes (all 16) and clipping use
 *    the standard Canvas2D API. A transparency group with an alpha or a
 *    blend mode, and a soft mask, paint into an offscreen canvas that
 *    composites onto the page once.
 *  - Embedded image XObjects: ⚠️ JPEG / JP2 are decoded asynchronously by
 *    the browser (`HTMLImageElement.src = …`), which doesn't fit the
 *    renderer's synchronous draw pass. v1 paints placeholders for image
 *    XObjects; an async render path is roadmapped.
 */
public class Canvas2dCanvas(ctx: CanvasRenderingContext2D) : KiteCanvas {

    /** The context that paints go to: the host's, or the offscreen canvas of an open layer. */
    private var ctx: CanvasRenderingContext2D = ctx

    /** The device pixel where the canvas of [ctx] starts: zero for the host's canvas. */
    private var originX = 0.0
    private var originY = 0.0

    /** Clips pushed on [ctx] and not popped yet. */
    private var openLayers = 0

    /** Open transparency groups, innermost last. */
    private val groups = ArrayDeque<Group>()

    override fun beginPage(widthPt: Double, heightPt: Double, deviceCtm: KiteMatrix) {
        while (groups.isNotEmpty()) endTransparencyGroup()
        openLayers = 0
    }

    override fun endPage() {
        // Close any group left open, then roll back leftover clips (defensive).
        while (groups.isNotEmpty()) endTransparencyGroup()
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
                penX += glyph.advanceWidth * advanceScale + glyph.advanceAdjust
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
                // advScale already carries sy (renderedSize), so the text-space
                // spacing adjust needs the same factor to stay in step.
                penX += glyph.advanceWidth * advScale + glyph.advanceAdjust * sy
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
        val stops = shading.sampleStops() ?: return
        // The gradient is built and filled in shading space under the whole CTM, so a
        // non-uniform or skewed CTM turns circles into ellipses and tilts the bands
        // (ISO 32000-1, 8.7.4.5.3 and 8.7.4.5.4). A CTM without an inverse paints nothing.
        val det = ctm.a * ctm.d - ctm.b * ctm.c
        if (det == 0.0 || !det.isFinite()) return

        val gradient = when (shading) {
            is KiteShading.Axial -> {
                val c = shading.coords
                ctx.createLinearGradient(c[0], c[1], c[2], c[3])
            }
            is KiteShading.Radial -> {
                // True PDF two-circle radial. Canvas2D supports both circles.
                // The outer radius is at least a tenth of a device pixel.
                val c = shading.coords
                val minRadius = 0.1 / kotlin.math.sqrt(kotlin.math.abs(det))
                ctx.createRadialGradient(c[0], c[1], c[2].coerceAtLeast(0.0), c[3], c[4], c[5].coerceAtLeast(minRadius))
            }
            is KiteShading.Unsupported -> return
            else -> return // complex shading types already handled by paintComplexShading
        }
        // An end that is not extended paints nothing past it (ISO 32000-1, 8.7.4.5.3 and
        // 8.7.4.5.4). A canvas gradient always pads with the colour of its outermost stop,
        // so a transparent stop on that end, added first or last, makes the pad transparent.
        val (extendStart, extendEnd) = when (shading) {
            is KiteShading.Axial -> shading.extendStart to shading.extendEnd
            is KiteShading.Radial -> shading.extendStart to shading.extendEnd
            else -> true to true
        }
        if (!extendStart) gradient.addColorStop(0.0, "rgba(0,0,0,0)")
        for (i in stops.colors.indices) {
            gradient.addColorStop(stops.offsets[i], stops.colors[i].toCssRgba(alpha))
        }
        if (!extendEnd) gradient.addColorStop(1.0, "rgba(0,0,0,0)")

        ctx.save()
        try {
            setDeviceTransform(ctm)
            ctx.fillStyle = gradient
            ctx.globalCompositeOperation = blendMode.toCanvas()
            // Without a region the shading covers the whole canvas, taken back into shading space.
            val region = clipPath ?: KitePath.Builder().apply {
                val x0 = originX
                val y0 = originY
                val x1 = originX + ctx.canvas.width
                val y1 = originY + ctx.canvas.height
                fun corner(x: Double, y: Double, first: Boolean) {
                    val sx = (ctm.d * (x - ctm.e) - ctm.c * (y - ctm.f)) / det
                    val sy = (ctm.a * (y - ctm.f) - ctm.b * (x - ctm.e)) / det
                    if (first) moveTo(sx, sy) else lineTo(sx, sy)
                }
                corner(x0, y0, true); corner(x1, y0, false); corner(x1, y1, false); corner(x0, y1, false)
                close()
            }.build()
            ctx.asDynamic().fill(toPath2D(region, KiteMatrix.IDENTITY), "nonzero")
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
        // decode keep the placeholder: browser decoding is async, and a
        // preload API is still to come.
        // One sampling policy on every canvas (#122, #123). setTransform below makes the ctm the device transform.
        val sampling = imageSampling(image.width, image.height, ctm, image.interpolate)
        val off = offscreens.getOrPut(image, sampling, { it.width.toLong() * it.height * 4 }) { offscreenFor(image, sampling) }
        if (off == null) {
            drawPlaceholder(ctm, alpha)
            return
        }
        ctx.save()
        try {
            setDeviceTransform(ctm)
            ctx.globalAlpha = alpha.coerceIn(0.0, 1.0)
            ctx.imageSmoothingEnabled = sampling.smooth
            // Unit square, bitmap row 0 on the top edge (v = 1): the Skia
            // mapping, translate up one unit and flip Y.
            ctx.translate(0.0, 1.0)
            ctx.scale(1.0 / off.width, -1.0 / off.height)
            ctx.drawImage(off, 0.0, 0.0)
        } finally {
            ctx.restore()
        }
    }

    /** Offscreen canvases that hold images, so that an image drawn many times converts once (#117). */
    private val offscreens = KiteBitmapCache<HTMLCanvasElement>()

    /**
     * The image on an offscreen canvas, averaged down when [sampling] shrinks it, so fine
     * detail fades instead of dropping out. putImageData ignores the transform, so the
     * image waits here to be drawn under the CTM. Null for a kind that core did not decode.
     */
    private fun offscreenFor(image: KiteImageData, sampling: KiteImageSampling): HTMLCanvasElement? {
        if (image.kind != KiteImageData.Kind.RAW) return null
        val full = image.toRgbaBytes() ?: return null
        val rgba = if (sampling.shrinks) shrinkRgba(full, image.width, image.height, sampling.shrinkX, sampling.shrinkY) else full
        val width = sampling.shrunkWidth(image.width)
        val height = sampling.shrunkHeight(image.height)
        val off = document.createElement("canvas") as HTMLCanvasElement
        off.width = width
        off.height = height
        val offCtx = off.getContext("2d") as CanvasRenderingContext2D
        val i8 = rgba.unsafeCast<Int8Array>()
        val clamped = Uint8ClampedArray(i8.buffer, i8.byteOffset, i8.length)
        offCtx.putImageData(ImageData(clamped, width, height), 0.0, 0.0)
        return off
    }

    private fun drawPlaceholder(ctm: KiteMatrix, alpha: Double) {
        ctx.save()
        try {
            setDeviceTransform(ctm)
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

    /**
     * An open transparency group. A group with a constant alpha below 1 or a blend mode
     * other than Normal paints into its own [layer], which composites onto the page once
     * when the group ends. Otherwise [layer] is null and the group paints straight onto
     * the page, which gives the same pixels.
     */
    private class Group(val saved: Saved?, val layer: Layer?, val alpha: Double, val blendMode: KiteBlendMode)

    /** An offscreen canvas whose top left corner is device pixel ([x], [y]). */
    private class Layer(val canvas: HTMLCanvasElement, val x: Double, val y: Double)

    /** The context state from before a layer opened. */
    private class Saved(val ctx: CanvasRenderingContext2D, val originX: Double, val originY: Double, val openLayers: Int)

    /**
     * ISO 32000-1, 11.4.5: a group's constant alpha and blend mode apply once, when the
     * group composites onto its backdrop. So a group that has them paints into a layer
     * first (#77). The layer is isolated, and knockout is not honoured (#125).
     */
    override fun beginTransparencyGroup(
        bbox: KiteRectangle, ctm: KiteMatrix,
        isolated: Boolean, knockout: Boolean,
        alpha: Double, blendMode: KiteBlendMode,
    ) {
        val a = alpha.coerceIn(0.0, 1.0)
        // A plain group, or one with malformed geometry, paints straight onto the page.
        val area = if (a < 1.0 || blendMode != KiteBlendMode.Normal) deviceArea(bbox, ctm) else null
        if (area == null) {
            groups.addLast(Group(null, null, 1.0, KiteBlendMode.Normal))
            return
        }
        val saved = Saved(ctx, originX, originY, openLayers)
        groups.addLast(Group(saved, openLayer(area), a, blendMode))
    }

    override fun endTransparencyGroup() {
        val group = groups.removeLastOrNull() ?: return
        val layer = group.layer ?: return
        closeLayer(group.saved ?: return)
        composite(layer, group.alpha, group.blendMode.toCanvas())
    }

    /**
     * ISO 32000-1, 11.6.5.2: the content and the mask group each paint into a layer of
     * their own. The mask layer's alpha, or for a luminosity mask its luminosity over a
     * black backdrop, gates the content layer, which then composites onto the page. The
     * mask group's colours never reach the page (#161).
     */
    override fun applySoftMask(
        kind: SoftMask.Kind,
        maskBBox: KiteRectangle, maskCtm: KiteMatrix,
        render: () -> Unit,
        renderMask: (KiteCanvas) -> Unit,
    ) {
        val area = deviceArea(maskBBox, maskCtm)
        if (area == null) {
            render() // Malformed geometry: keep the paint without its unusable mask.
            return
        }
        // The mask is zero wherever the content could show.
        if (area[2] == 0 || area[3] == 0) return
        val content = paintInLayer(area, render)
        val mask = paintInLayer(area) {
            if (kind == SoftMask.Kind.Luminosity) {
                // Unpainted parts of the group show the black backdrop, whose luminosity is zero.
                ctx.save()
                ctx.setTransform(1.0, 0.0, 0.0, 1.0, 0.0, 0.0)
                ctx.fillStyle = "black"
                ctx.fillRect(0.0, 0.0, ctx.canvas.width.toDouble(), ctx.canvas.height.toDouble())
                ctx.restore()
            }
            renderMask(this)
        }
        if (kind == SoftMask.Kind.Luminosity) luminosityToAlpha(mask.canvas)
        val contentCtx = content.canvas.getContext("2d") as CanvasRenderingContext2D
        contentCtx.setTransform(1.0, 0.0, 0.0, 1.0, 0.0, 0.0)
        contentCtx.globalCompositeOperation = "destination-in"
        contentCtx.drawImage(mask.canvas, 0.0, 0.0)
        composite(content, 1.0, "source-over")
    }

    /** A layer over [area] that [paint] draws into, through this canvas. */
    private fun paintInLayer(area: IntArray, paint: () -> Unit): Layer {
        val saved = Saved(ctx, originX, originY, openLayers)
        val openGroups = groups.size
        val layer = openLayer(area)
        try {
            paint()
        } finally {
            // Groups the paint left open close inside the layer.
            while (groups.size > openGroups) endTransparencyGroup()
            closeLayer(saved)
        }
        return layer
    }

    /**
     * The device pixels that [box] covers under [toDevice], inside the canvas of [ctx], as
     * left, top, width and height. Null when the geometry is not finite.
     */
    private fun deviceArea(box: KiteRectangle, toDevice: KiteMatrix): IntArray? {
        val b = box.normalized()
        val xs = doubleArrayOf(
            toDevice.transformX(b.left, b.bottom), toDevice.transformX(b.right, b.bottom),
            toDevice.transformX(b.left, b.top), toDevice.transformX(b.right, b.top),
        )
        val ys = doubleArrayOf(
            toDevice.transformY(b.left, b.bottom), toDevice.transformY(b.right, b.bottom),
            toDevice.transformY(b.left, b.top), toDevice.transformY(b.right, b.top),
        )
        if (!xs.all { it.isFinite() } || !ys.all { it.isFinite() }) return null
        val left = floor(maxOf(xs.min(), originX)).toInt()
        val top = floor(maxOf(ys.min(), originY)).toInt()
        val right = ceil(minOf(xs.max(), originX + ctx.canvas.width)).toInt()
        val bottom = ceil(minOf(ys.max(), originY + ctx.canvas.height)).toInt()
        return intArrayOf(left, top, maxOf(0, right - left), maxOf(0, bottom - top))
    }

    /** Opens an offscreen canvas over [area] and sends the paints that follow to it. */
    private fun openLayer(area: IntArray): Layer {
        val canvas = document.createElement("canvas") as HTMLCanvasElement
        canvas.width = area[2]
        canvas.height = area[3]
        val layer = Layer(canvas, area[0].toDouble(), area[1].toDouble())
        ctx = canvas.getContext("2d") as CanvasRenderingContext2D
        originX = layer.x
        originY = layer.y
        openLayers = 0
        ctx.setTransform(1.0, 0.0, 0.0, 1.0, -originX, -originY)
        return layer
    }

    /** Drops the clips left on the current layer and sends paints back to the context in [saved]. */
    private fun closeLayer(saved: Saved) {
        while (openLayers > 0) {
            ctx.restore(); openLayers--
        }
        ctx = saved.ctx
        originX = saved.originX
        originY = saved.originY
        openLayers = saved.openLayers
    }

    /** Draws [layer] onto [ctx] where it belongs, with [alpha] and the composite operation [mode]. */
    private fun composite(layer: Layer, alpha: Double, mode: String) {
        // A canvas without pixels cannot be drawn, and shows nothing anyway.
        if (layer.canvas.width == 0 || layer.canvas.height == 0) return
        ctx.save()
        try {
            ctx.setTransform(1.0, 0.0, 0.0, 1.0, layer.x - originX, layer.y - originY)
            ctx.globalAlpha = alpha
            ctx.globalCompositeOperation = mode
            ctx.drawImage(layer.canvas, 0.0, 0.0)
        } finally {
            ctx.restore()
        }
    }

    /** Replaces the alpha of every pixel of [canvas] with its luminosity 0.30 R + 0.59 G + 0.11 B. */
    private fun luminosityToAlpha(canvas: HTMLCanvasElement) {
        if (canvas.width == 0 || canvas.height == 0) return
        val maskCtx = canvas.getContext("2d") as CanvasRenderingContext2D
        val image = maskCtx.getImageData(0.0, 0.0, canvas.width.toDouble(), canvas.height.toDouble())
        val data = image.data.asDynamic()
        val length = canvas.width * canvas.height * 4
        var i = 0
        while (i < length) {
            val r = data[i] as Int
            val g = data[i + 1] as Int
            val b = data[i + 2] as Int
            data[i + 3] = (r * 77 + g * 150 + b * 29) ushr 8
            i += 4
        }
        maskCtx.putImageData(image, 0.0, 0.0)
    }

    /** Sets [m], a transform to device pixels, on [ctx], whose canvas starts at the layer origin. */
    private fun setDeviceTransform(m: KiteMatrix) {
        ctx.setTransform(m.a, m.b, m.c, m.d, m.e - originX, m.f - originY)
    }

    /* ─── Helpers ─────────────────────────────────────────────────────────── */

    private fun toPath2D(src: KitePath, ctm: KiteMatrix): Path2D {
        val p = Path2D()
        for (seg in src.segments) {
            when (seg) {
                is KitePath.Segment.MoveTo -> {
                    val x = ctm.transformX(seg.x, seg.y)
                    val y = ctm.transformY(seg.x, seg.y)
                    p.moveTo(x, y)
                }
                is KitePath.Segment.LineTo -> {
                    val x = ctm.transformX(seg.x, seg.y)
                    val y = ctm.transformY(seg.x, seg.y)
                    p.lineTo(x, y)
                }
                is KitePath.Segment.CurveTo -> {
                    val x1 = ctm.transformX(seg.x1, seg.y1)
                    val y1 = ctm.transformY(seg.x1, seg.y1)
                    val x2 = ctm.transformX(seg.x2, seg.y2)
                    val y2 = ctm.transformY(seg.x2, seg.y2)
                    val x3 = ctm.transformX(seg.x3, seg.y3)
                    val y3 = ctm.transformY(seg.x3, seg.y3)
                    p.bezierCurveTo(x1, y1, x2, y2, x3, y3)
                }
                is KitePath.Segment.QuadTo -> {
                    val x1 = ctm.transformX(seg.x1, seg.y1)
                    val y1 = ctm.transformY(seg.x1, seg.y1)
                    val x2 = ctm.transformX(seg.x2, seg.y2)
                    val y2 = ctm.transformY(seg.x2, seg.y2)
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
