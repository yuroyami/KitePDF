package io.github.yuroyami.kitepdf.nativerenderer

import android.graphics.BitmapFactory
import android.graphics.BlendMode as AndroidBlendMode
import android.graphics.Canvas as AndroidCanvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.Shader
import android.graphics.Typeface
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
import io.github.yuroyami.kitepdf.core.render.paintComplexShading
import io.github.yuroyami.kitepdf.core.render.sampleStops

/**
 * [KiteCanvas] backed by [android.graphics.Canvas].
 *
 * This is the right choice on Android when you don't want Compose: pass a
 * `Canvas` from your custom View's `onDraw(Canvas)` override straight into
 * the constructor and the renderer paints into it.
 *
 * Pair with [AndroidPdfBitmapRenderer] for the "render a PDF page into a
 * Bitmap" use case.
 *
 * Blend modes require API 29+ (`Paint.setBlendMode`). The module's minSdk
 * is 29 to match. See :kitepdf-native build.gradle.kts.
 */
public class AndroidNativeCanvas(private val canvas: AndroidCanvas) : KiteCanvas {

    /** Open layers from clip pushes + transparency groups. */
    private var openLayers = 0

    override fun beginPage(widthPt: Double, heightPt: Double, deviceCtm: KiteMatrix) {
        openLayers = 0
    }

    override fun endPage() {
        // Defensive: well-formed PDFs always pair pushes with pops.
        while (openLayers > 0) {
            canvas.restore()
            openLayers--
        }
    }

    override fun fillPath(
        path: KitePath, ctm: KiteMatrix, color: RgbColor, evenOdd: Boolean,
        alpha: Double, blendMode: KiteBlendMode,
    ) {
        val p = toAndroidPath(path, ctm).apply {
            fillType = if (evenOdd) Path.FillType.EVEN_ODD else Path.FillType.WINDING
        }
        val paint = Paint().apply {
            isAntiAlias = true
            style = Paint.Style.FILL
            this.color = color.toArgb(alpha)
            applyBlendMode(blendMode)
        }
        canvas.drawPath(p, paint)
    }

    override fun strokePath(
        path: KitePath, ctm: KiteMatrix, color: RgbColor, lineWidth: Double,
        alpha: Double, blendMode: KiteBlendMode,
        dashArray: List<Double>?, dashPhase: Double,
        lineCap: Int, lineJoin: Int, miterLimit: Double,
    ) {
        val p = toAndroidPath(path, ctm)
        val avgScale = (ctm.scaleX() + ctm.scaleY()) * 0.5
        val paint = Paint().apply {
            isAntiAlias = true
            style = Paint.Style.STROKE
            this.color = color.toArgb(alpha)
            strokeWidth = (lineWidth * avgScale).toFloat().coerceAtLeast(0.1f)
            strokeCap = when (lineCap) { 1 -> Paint.Cap.ROUND; 2 -> Paint.Cap.SQUARE; else -> Paint.Cap.BUTT }
            strokeJoin = when (lineJoin) { 1 -> Paint.Join.ROUND; 2 -> Paint.Join.BEVEL; else -> Paint.Join.MITER }
            strokeMiter = miterLimit.toFloat().coerceAtLeast(1f)
            if (!dashArray.isNullOrEmpty()) {
                // Dash lengths are user-space units; device px = unit × scale.
                val intervals = FloatArray(dashArray.size) { (dashArray[it] * avgScale).toFloat() }
                pathEffect = DashPathEffect(intervals, (dashPhase * avgScale).toFloat())
            }
            applyBlendMode(blendMode)
        }
        canvas.drawPath(p, paint)
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

        val unitScale = fontSize / unitsPerEm  // glyph outlines: font units → text space
        val advanceScale = fontSize / 1000.0   // advances are 1/1000 em, not font units
        val paint = Paint().apply {
            isAntiAlias = true
            style = Paint.Style.FILL
            this.color = color.toArgb(alpha)
            applyBlendMode(blendMode)
        }
        var drewAny = false
        var penX = 0.0
        for (glyph in glyphs) {
            val outline = glyph.outline
            if (outline != null && !outline.isEmpty()) {
                val glyphMatrix = textToDevice
                    .concat(KiteMatrix.translation(penX + glyph.xOffset * unitScale, glyph.yOffset * unitScale))
                    .concat(KiteMatrix(unitScale, 0.0, 0.0, unitScale, 0.0, 0.0))
                val p = toAndroidPath(outline, glyphMatrix).apply { fillType = Path.FillType.WINDING }
                canvas.drawPath(p, paint)
                drewAny = true
            }
            penX += glyph.advanceWidth * advanceScale
        }
        // Embedded font present but produced no glyphs (e.g. a subset we can't
        // decode). Fall back to a system font rather than rendering blank.
        if (!drewAny && glyphs.any { it.text.isNotBlank() }) {
            drawTextViaSystemFont(glyphs, fontSize, fontSpec, textToDevice, color, alpha, blendMode)
        }
    }

    /**
     * Fallback for non-embedded fonts (e.g. the Standard-14). Renders with a
     * platform logical font: zero bundled bytes, since Android already ships
     * Serif / SansSerif / Monospace faces that are metric-compatible stand-ins
     * for Times / Helvetica / Courier. Mirrors AwtCanvas / ComposeCanvas.
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

        // Decompose the (text-space → device) matrix like the Compose path:
        // translation + rotation + *positive* scale magnitudes, so the device
        // Y-flip baked into the matrix doesn't render the glyphs mirrored.
        val sx = kotlin.math.sqrt(textMatrix.a * textMatrix.a + textMatrix.b * textMatrix.b)
        val sy = kotlin.math.sqrt(textMatrix.c * textMatrix.c + textMatrix.d * textMatrix.d)
        if (sy == 0.0) return
        val rotation = kotlin.math.atan2(textMatrix.b, textMatrix.a)
        val renderedSize = (fontSize * sy).coerceAtLeast(0.01)

        canvas.save()
        openLayers++
        try {
            canvas.translate(textMatrix.e.toFloat(), textMatrix.f.toFloat())
            if (rotation != 0.0) canvas.rotate(Math.toDegrees(rotation).toFloat())
            if (sx != sy) canvas.scale((sx / sy).toFloat(), 1f)
            val paint = Paint().apply {
                isAntiAlias = true
                style = Paint.Style.FILL
                this.color = color.toArgb(alpha)
                typeface = systemFontFor(fontSpec)
                textSize = renderedSize.toFloat()
                applyBlendMode(blendMode)
            }
            // Position each glyph by the PDF's OWN advance widths (1/1000 em),
            // not the substitute font's natural metrics, otherwise spacing
            // drifts and glyphs crowd together / overlap.
            var penX = 0.0
            val advScale = renderedSize / 1000.0
            for (glyph in glyphs) {
                val t = glyph.text
                if (t.isNotEmpty() && t != " ") canvas.drawText(t, penX.toFloat(), 0f, paint)
                penX += glyph.advanceWidth * advScale
            }
        } finally {
            canvas.restore()
            openLayers--
        }
    }

    /** Map a non-embedded PDF font to an Android logical font (mirrors AwtCanvas's family/style choice). */
    private fun systemFontFor(spec: FontSpec): Typeface {
        val base = when (spec.family) {
            KiteFontFamily.Serif -> Typeface.SERIF
            KiteFontFamily.Monospace -> Typeface.MONOSPACE
            KiteFontFamily.SansSerif -> Typeface.SANS_SERIF
        }
        val style = when {
            spec.bold && spec.italic -> Typeface.BOLD_ITALIC
            spec.bold -> Typeface.BOLD
            spec.italic -> Typeface.ITALIC
            else -> Typeface.NORMAL
        }
        return Typeface.create(base, style)
    }

    override fun fillShading(
        shading: KiteShading, ctm: KiteMatrix, clipPath: KitePath?,
        alpha: Double, blendMode: KiteBlendMode,
    ) {
        if (paintComplexShading(shading, ctm, clipPath, alpha, blendMode)) return
        val stops = shading.sampleStops(32) ?: return
        val colors = IntArray(stops.colors.size) { stops.colors[it].toArgb(alpha) }
        val positions = FloatArray(stops.offsets.size) { stops.offsets[it].toFloat() }

        val shader: Shader = when (shading) {
            is KiteShading.Axial -> {
                val (x0, y0) = ctm.transformPoint(shading.coords[0], shading.coords[1])
                val (x1, y1) = ctm.transformPoint(shading.coords[2], shading.coords[3])
                LinearGradient(
                    x0.toFloat(), y0.toFloat(), x1.toFloat(), y1.toFloat(),
                    colors, positions, Shader.TileMode.CLAMP,
                )
            }
            is KiteShading.Radial -> {
                val (cx, cy) = ctm.transformPoint(shading.coords[3], shading.coords[4])
                val r = (shading.coords[5] * kotlin.math.sqrt(ctm.a * ctm.a + ctm.b * ctm.b))
                    .toFloat().coerceAtLeast(0.1f)
                RadialGradient(cx.toFloat(), cy.toFloat(), r, colors, positions, Shader.TileMode.CLAMP)
            }
            is KiteShading.Unsupported -> return
            else -> return // complex shading types already handled by paintComplexShading
        }
        val paint = Paint().apply {
            isAntiAlias = true
            style = Paint.Style.FILL
            this.shader = shader
            applyBlendMode(blendMode)
        }
        if (clipPath != null) {
            val cp = toAndroidPath(clipPath, ctm).apply { fillType = Path.FillType.WINDING }
            canvas.drawPath(cp, paint)
        } else {
            canvas.drawPaint(paint)
        }
    }

    override fun pushClip(path: KitePath, ctm: KiteMatrix, evenOdd: Boolean) {
        canvas.save()
        openLayers++
        val p = toAndroidPath(path, ctm).apply {
            fillType = if (evenOdd) Path.FillType.EVEN_ODD else Path.FillType.WINDING
        }
        canvas.clipPath(p)
    }

    override fun popClip() {
        if (openLayers > 0) {
            canvas.restore()
            openLayers--
        }
    }

    override fun drawImage(image: KiteImageData, ctm: KiteMatrix, alpha: Double) {
        val bm = decodeImage(image)
        if (bm == null) {
            drawPlaceholder(ctm)
            return
        }
        canvas.save()
        openLayers++
        canvas.concat(pdfMatrixToAndroid(ctm))
        canvas.save()
        canvas.translate(0f, -1f)
        canvas.scale(1f / bm.width, 1f / bm.height)
        val paint = Paint().apply {
            this.alpha = (alpha.coerceIn(0.0, 1.0) * 255).toInt()
        }
        canvas.drawBitmap(bm, 0f, 0f, paint)
        canvas.restore()
        canvas.restore()
        openLayers--
    }

    private fun decodeImage(image: KiteImageData): android.graphics.Bitmap? = try {
        when (image.kind) {
            KiteImageData.Kind.JPEG, KiteImageData.Kind.JPEG2000, KiteImageData.Kind.JBIG2 ->
                BitmapFactory.decodeByteArray(image.encodedBytes, 0, image.encodedBytes.size)
            else -> null
        }
    } catch (t: Throwable) {
        null
    }

    private fun drawPlaceholder(ctm: KiteMatrix) {
        canvas.save()
        canvas.concat(pdfMatrixToAndroid(ctm))
        val fill = Paint().apply { color = 0xFFE0E0E0.toInt(); style = Paint.Style.FILL }
        val stroke = Paint().apply {
            color = 0xFF888888.toInt(); style = Paint.Style.STROKE; strokeWidth = 0.01f
        }
        val rect = Path().apply {
            moveTo(0f, 0f); lineTo(1f, 0f); lineTo(1f, 1f); lineTo(0f, 1f); close()
        }
        canvas.drawPath(rect, fill)
        canvas.drawPath(rect, stroke)
        canvas.restore()
    }

    override fun beginTransparencyGroup(
        bbox: KiteRectangle, ctm: KiteMatrix,
        isolated: Boolean, knockout: Boolean,
        alpha: Double, blendMode: KiteBlendMode,
    ) {
        val paint = Paint().apply {
            this.alpha = (alpha.coerceIn(0.0, 1.0) * 255).toInt()
            applyBlendMode(blendMode)
        }
        canvas.saveLayer(null, paint)
        openLayers++
    }

    override fun endTransparencyGroup() {
        if (openLayers > 0) {
            canvas.restore()
            openLayers--
        }
    }

    override fun applySoftMask(
        kind: SoftMask.Kind,
        maskBBox: KiteRectangle, maskCtm: KiteMatrix,
        render: () -> Unit,
        renderMask: (KiteCanvas) -> Unit,
    ) {
        canvas.saveLayer(null, Paint())
        openLayers++
        try {
            render()
            val maskPaint = Paint().apply { blendMode = AndroidBlendMode.DST_IN }
            canvas.saveLayer(null, maskPaint)
            openLayers++
            try {
                renderMask(this)
            } finally {
                canvas.restore()
                openLayers--
            }
        } finally {
            canvas.restore()
            openLayers--
        }
    }

    /* ─── Helpers ─────────────────────────────────────────────────────────── */

    private fun toAndroidPath(src: KitePath, ctm: KiteMatrix): Path {
        val out = Path()
        for (seg in src.segments) {
            when (seg) {
                is KitePath.Segment.MoveTo -> {
                    val (x, y) = ctm.transformPoint(seg.x, seg.y)
                    out.moveTo(x.toFloat(), y.toFloat())
                }
                is KitePath.Segment.LineTo -> {
                    val (x, y) = ctm.transformPoint(seg.x, seg.y)
                    out.lineTo(x.toFloat(), y.toFloat())
                }
                is KitePath.Segment.CurveTo -> {
                    val (x1, y1) = ctm.transformPoint(seg.x1, seg.y1)
                    val (x2, y2) = ctm.transformPoint(seg.x2, seg.y2)
                    val (x3, y3) = ctm.transformPoint(seg.x3, seg.y3)
                    out.cubicTo(x1.toFloat(), y1.toFloat(), x2.toFloat(), y2.toFloat(), x3.toFloat(), y3.toFloat())
                }
                is KitePath.Segment.QuadTo -> {
                    val (x1, y1) = ctm.transformPoint(seg.x1, seg.y1)
                    val (x2, y2) = ctm.transformPoint(seg.x2, seg.y2)
                    out.quadTo(x1.toFloat(), y1.toFloat(), x2.toFloat(), y2.toFloat())
                }
                KitePath.Segment.Close -> out.close()
            }
        }
        return out
    }

    private fun pdfMatrixToAndroid(m: KiteMatrix): Matrix = Matrix().apply {
        setValues(
            floatArrayOf(
                m.a.toFloat(), m.c.toFloat(), m.e.toFloat(),
                m.b.toFloat(), m.d.toFloat(), m.f.toFloat(),
                0f, 0f, 1f,
            ),
        )
    }

    private fun RgbColor.toArgb(alpha: Double): Int = Color.argb(
        (alpha.coerceIn(0.0, 1.0) * 255).toInt(),
        (r.coerceIn(0.0, 1.0) * 255).toInt(),
        (g.coerceIn(0.0, 1.0) * 255).toInt(),
        (b.coerceIn(0.0, 1.0) * 255).toInt(),
    )

    private fun Paint.applyBlendMode(mode: KiteBlendMode) {
        // API 29+: minSdk for :kitepdf-native is 29, so this is unconditional.
        blendMode = when (mode) {
            KiteBlendMode.Normal -> AndroidBlendMode.SRC_OVER
            KiteBlendMode.Multiply -> AndroidBlendMode.MULTIPLY
            KiteBlendMode.Screen -> AndroidBlendMode.SCREEN
            KiteBlendMode.Overlay -> AndroidBlendMode.OVERLAY
            KiteBlendMode.Darken -> AndroidBlendMode.DARKEN
            KiteBlendMode.Lighten -> AndroidBlendMode.LIGHTEN
            KiteBlendMode.ColorDodge -> AndroidBlendMode.COLOR_DODGE
            KiteBlendMode.ColorBurn -> AndroidBlendMode.COLOR_BURN
            KiteBlendMode.HardLight -> AndroidBlendMode.HARD_LIGHT
            KiteBlendMode.SoftLight -> AndroidBlendMode.SOFT_LIGHT
            KiteBlendMode.Difference -> AndroidBlendMode.DIFFERENCE
            KiteBlendMode.Exclusion -> AndroidBlendMode.EXCLUSION
            KiteBlendMode.Hue -> AndroidBlendMode.HUE
            KiteBlendMode.Saturation -> AndroidBlendMode.SATURATION
            KiteBlendMode.Color -> AndroidBlendMode.COLOR
            KiteBlendMode.Luminosity -> AndroidBlendMode.LUMINOSITY
        }
    }
}
