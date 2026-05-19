package com.yuroyami.kitepdf.compose

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.BlendMode as ComposeBlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.TextUnitType
import com.yuroyami.kitepdf.font.PdfFont
import com.yuroyami.kitepdf.render.BlendMode as PdfBlendMode
import com.yuroyami.kitepdf.render.ImageXObject
import com.yuroyami.kitepdf.render.Matrix as PdfMatrix
import com.yuroyami.kitepdf.render.PdfCanvas
import com.yuroyami.kitepdf.render.PdfPath
import com.yuroyami.kitepdf.render.Rectangle as PdfRectangle
import com.yuroyami.kitepdf.render.RgbColor
import kotlin.math.atan2
import kotlin.math.sqrt

/**
 * [PdfCanvas] backed by a Compose Multiplatform [DrawScope].
 *
 * Three rendering paths:
 *
 *   - **Embedded outlines** — when [PdfFont.hasEmbeddedOutlines] is true,
 *     each byte/CID becomes a Compose `Path` filled at the right position.
 *   - **System-font fallback** — when no outlines available, decode to text
 *     and hand to Compose's `TextMeasurer`.
 *   - **Transparency groups** — open a `saveLayer` on the underlying Canvas
 *     when the renderer requests one; later `restore` composites the layer
 *     back with the requested blend mode + alpha.
 *
 * Clipping uses Compose's [clipPath] inside a recursive scope; transparency
 * groups use the lower-level `Canvas.saveLayer` so they can span multiple
 * `DrawScope` operations.
 */
class ComposeCanvas(
    private val drawScope: DrawScope,
    private val textMeasurer: TextMeasurer,
) : PdfCanvas {

    private val clipStack = ArrayDeque<ClipFrame>()
    /** Count of open transparency groups — for matching beginGroup/endGroup pairs. */
    private var openGroups = 0

    override fun beginPage(widthPt: Double, heightPt: Double, deviceCtm: PdfMatrix) {
        clipStack.clear()
        openGroups = 0
    }

    override fun endPage() {
        // Close any still-open transparency groups (defensive — well-formed
        // PDFs always pair them, but malformed ones leak).
        while (openGroups > 0) {
            drawScope.drawContext.canvas.restore()
            openGroups--
        }
        clipStack.clear()
    }

    override fun fillPath(
        path: PdfPath, ctm: PdfMatrix, color: RgbColor, evenOdd: Boolean,
        alpha: Double, blendMode: PdfBlendMode,
    ) {
        withActiveClips {
            val composePath = toComposePath(path, ctm).apply {
                fillType = if (evenOdd) PathFillType.EvenOdd else PathFillType.NonZero
            }
            drawScope.drawPath(
                path = composePath,
                color = color.toCompose(),
                alpha = alpha.toFloat().coerceIn(0f, 1f),
                blendMode = blendMode.toCompose(),
            )
        }
    }

    override fun strokePath(
        path: PdfPath, ctm: PdfMatrix, color: RgbColor, lineWidth: Double,
        alpha: Double, blendMode: PdfBlendMode,
    ) {
        withActiveClips {
            val composePath = toComposePath(path, ctm)
            val avgScale = (ctm.scaleX() + ctm.scaleY()) * 0.5
            drawScope.drawPath(
                path = composePath,
                color = color.toCompose(),
                alpha = alpha.toFloat().coerceIn(0f, 1f),
                style = Stroke(width = (lineWidth * avgScale).toFloat().coerceAtLeast(0.1f)),
                blendMode = blendMode.toCompose(),
            )
        }
    }

    override fun drawText(
        bytes: ByteArray,
        font: PdfFont,
        fontSize: Double,
        textMatrix: PdfMatrix,
        fillColor: RgbColor,
        alpha: Double,
        blendMode: PdfBlendMode,
    ) {
        if (bytes.isEmpty()) return
        withActiveClips {
            if (font.hasEmbeddedOutlines) {
                drawTextViaOutlines(bytes, font, fontSize, textMatrix, fillColor, alpha, blendMode)
            } else {
                drawTextViaSystemFont(bytes, font, fontSize, textMatrix, fillColor, alpha, blendMode)
            }
        }
    }

    private fun drawTextViaOutlines(
        bytes: ByteArray,
        font: PdfFont,
        fontSize: Double,
        textMatrix: PdfMatrix,
        fillColor: RgbColor,
        alpha: Double,
        blendMode: PdfBlendMode,
    ) {
        val upm = font.unitsPerEm ?: 1000
        val unitScale = fontSize / upm
        val color = fillColor.toCompose()
        val composeBlend = blendMode.toCompose()
        val a = alpha.toFloat().coerceIn(0f, 1f)
        var penX = 0.0
        for (glyph in font.layoutBytes(bytes)) {
            val outline = glyph.outline
            if (outline != null && !outline.isEmpty()) {
                val glyphMatrix = textMatrix
                    .let { tm -> PdfMatrix.translation(penX, 0.0).concat(tm) }
                    .let { tm -> PdfMatrix(unitScale, 0.0, 0.0, unitScale, 0.0, 0.0).concat(tm) }
                val cp = toComposePath(outline, glyphMatrix).apply { fillType = PathFillType.NonZero }
                drawScope.drawPath(cp, color = color, alpha = a, blendMode = composeBlend)
            }
            penX += glyph.advanceWidth * unitScale
        }
    }

    private fun drawTextViaSystemFont(
        bytes: ByteArray,
        font: PdfFont,
        fontSize: Double,
        textMatrix: PdfMatrix,
        fillColor: RgbColor,
        alpha: Double,
        blendMode: PdfBlendMode,
    ) {
        val text = font.decode(bytes)
        if (text.isEmpty()) return

        val sx = sqrt(textMatrix.a * textMatrix.a + textMatrix.b * textMatrix.b)
        val sy = sqrt(textMatrix.c * textMatrix.c + textMatrix.d * textMatrix.d)
        val rotationRadians = atan2(textMatrix.b, textMatrix.a)
        val rotationDegrees = (rotationRadians * 180.0 / PI).toFloat()
        val renderedSize = fontSize * sy

        val color = fillColor.toCompose().copy(alpha = alpha.toFloat().coerceIn(0f, 1f))
        val style = TextStyle(
            color = color,
            fontSize = TextUnit(renderedSize.toFloat(), TextUnitType.Sp),
            fontFamily = font.toComposeFamily(),
            fontWeight = font.toComposeWeight(),
            fontStyle = font.toComposeStyle(),
        )
        val layout = textMeasurer.measure(text = text, style = style)

        drawScope.withTransform({
            translate(textMatrix.e.toFloat(), textMatrix.f.toFloat())
            if (rotationDegrees != 0f) rotate(rotationDegrees, pivot = Offset.Zero)
            translate(0f, -layout.firstBaseline)
            if (sx != sy && sy != 0.0) {
                scale(scaleX = (sx / sy).toFloat(), scaleY = 1f, pivot = Offset.Zero)
            }
        }) {
            drawText(textLayoutResult = layout, blendMode = blendMode.toCompose())
        }
    }

    override fun drawImage(image: ImageXObject, ctm: PdfMatrix, alpha: Double) {
        withActiveClips {
            val bitmap = when (image.kind) {
                ImageXObject.Kind.JPEG, ImageXObject.Kind.JPEG2000 -> ImageDecoder.decode(image.encodedBytes)
                else -> null
            }
            if (bitmap != null) {
                drawBitmap(bitmap, ctm, alpha.toFloat().coerceIn(0f, 1f))
            } else {
                drawPlaceholder(ctm)
            }
        }
    }

    private fun drawBitmap(bitmap: androidx.compose.ui.graphics.ImageBitmap, ctm: PdfMatrix, alpha: Float) {
        val originX = ctm.e.toFloat()
        val originY = ctm.f.toFloat()
        val widthScale = sqrt(ctm.a * ctm.a + ctm.b * ctm.b).toFloat()
        val heightScale = sqrt(ctm.c * ctm.c + ctm.d * ctm.d).toFloat()
        val topLeftY = originY - heightScale
        drawScope.withTransform({
            translate(originX, topLeftY)
            scale(widthScale / bitmap.width, heightScale / bitmap.height, pivot = Offset.Zero)
        }) {
            drawImage(image = bitmap, topLeft = Offset.Zero, alpha = alpha)
        }
    }

    private fun drawPlaceholder(ctm: PdfMatrix) {
        val rectPath = PdfPath.Builder().apply { rectangle(0.0, 0.0, 1.0, 1.0) }.build()
        val composeRect = toComposePath(rectPath, ctm).apply { fillType = PathFillType.NonZero }
        drawScope.drawPath(composeRect, color = Color(0xFFE0E0E0.toInt()))
        drawScope.drawPath(composeRect, color = Color(0xFF888888.toInt()), style = Stroke(width = 1f))
        val diagonal = PdfPath.Builder().apply {
            moveTo(0.0, 0.0); lineTo(1.0, 1.0)
            moveTo(0.0, 1.0); lineTo(1.0, 0.0)
        }.build()
        drawScope.drawPath(
            toComposePath(diagonal, ctm),
            color = Color(0xFFAAAAAA.toInt()),
            style = Stroke(width = 0.5f),
        )
    }

    override fun pushClip(path: PdfPath, ctm: PdfMatrix, evenOdd: Boolean) {
        val composePath = toComposePath(path, ctm).apply {
            fillType = if (evenOdd) PathFillType.EvenOdd else PathFillType.NonZero
        }
        clipStack.addLast(ClipFrame(composePath))
    }

    override fun popClip() {
        if (clipStack.isNotEmpty()) clipStack.removeLast()
    }

    /**
     * Transparency groups: open a Compose `saveLayer` on the active Canvas
     * with a Paint that carries the requested alpha + blend mode. Subsequent
     * draws accumulate into the offscreen layer; matching [endTransparencyGroup]
     * calls `restore`, which composites the layer onto the parent.
     */
    override fun beginTransparencyGroup(
        bbox: PdfRectangle, ctm: PdfMatrix,
        isolated: Boolean, knockout: Boolean,
        alpha: Double, blendMode: PdfBlendMode,
    ) {
        // Compute the layer's pixel bounds in device space.
        val corners = listOf(
            ctm.transformPoint(bbox.left, bbox.bottom),
            ctm.transformPoint(bbox.right, bbox.bottom),
            ctm.transformPoint(bbox.right, bbox.top),
            ctm.transformPoint(bbox.left, bbox.top),
        )
        val xs = corners.map { it.first }
        val ys = corners.map { it.second }
        val rect = Rect(
            xs.min().toFloat(), ys.min().toFloat(),
            xs.max().toFloat(), ys.max().toFloat(),
        )

        val paint = Paint().apply {
            this.alpha = alpha.toFloat().coerceIn(0f, 1f)
            this.blendMode = blendMode.toCompose()
        }
        drawScope.drawContext.canvas.saveLayer(rect, paint)
        openGroups++
    }

    override fun endTransparencyGroup() {
        if (openGroups <= 0) return
        drawScope.drawContext.canvas.restore()
        openGroups--
    }

    /* ─── Helpers ─────────────────────────────────────────────────────────── */

    private fun withActiveClips(block: () -> Unit) {
        applyClipsThen(0, block)
    }

    private fun applyClipsThen(index: Int, block: () -> Unit) {
        if (index >= clipStack.size) {
            block(); return
        }
        val frame = clipStack[index]
        drawScope.clipPath(frame.path) {
            applyClipsThen(index + 1, block)
        }
    }

    private fun toComposePath(src: PdfPath, ctm: PdfMatrix): Path {
        val out = Path()
        for (seg in src.segments) {
            when (seg) {
                is PdfPath.Segment.MoveTo -> {
                    val (x, y) = ctm.transformPoint(seg.x, seg.y)
                    out.moveTo(x.toFloat(), y.toFloat())
                }
                is PdfPath.Segment.LineTo -> {
                    val (x, y) = ctm.transformPoint(seg.x, seg.y)
                    out.lineTo(x.toFloat(), y.toFloat())
                }
                is PdfPath.Segment.CurveTo -> {
                    val (x1, y1) = ctm.transformPoint(seg.x1, seg.y1)
                    val (x2, y2) = ctm.transformPoint(seg.x2, seg.y2)
                    val (x3, y3) = ctm.transformPoint(seg.x3, seg.y3)
                    out.cubicTo(x1.toFloat(), y1.toFloat(), x2.toFloat(), y2.toFloat(), x3.toFloat(), y3.toFloat())
                }
                is PdfPath.Segment.QuadTo -> {
                    val (x1, y1) = ctm.transformPoint(seg.x1, seg.y1)
                    val (x2, y2) = ctm.transformPoint(seg.x2, seg.y2)
                    out.quadraticTo(x1.toFloat(), y1.toFloat(), x2.toFloat(), y2.toFloat())
                }
                PdfPath.Segment.Close -> out.close()
            }
        }
        return out
    }

    private fun RgbColor.toCompose(): Color = Color(r.toFloat(), g.toFloat(), b.toFloat(), 1f)

    /**
     * Map a PDF blend mode to its Compose equivalent. All 16 PDF blend modes
     * have a 1:1 Compose counterpart, so this is a clean enum dispatch.
     */
    private fun PdfBlendMode.toCompose(): ComposeBlendMode = when (this) {
        PdfBlendMode.Normal -> ComposeBlendMode.SrcOver
        PdfBlendMode.Multiply -> ComposeBlendMode.Multiply
        PdfBlendMode.Screen -> ComposeBlendMode.Screen
        PdfBlendMode.Overlay -> ComposeBlendMode.Overlay
        PdfBlendMode.Darken -> ComposeBlendMode.Darken
        PdfBlendMode.Lighten -> ComposeBlendMode.Lighten
        PdfBlendMode.ColorDodge -> ComposeBlendMode.ColorDodge
        PdfBlendMode.ColorBurn -> ComposeBlendMode.ColorBurn
        PdfBlendMode.HardLight -> ComposeBlendMode.Hardlight
        PdfBlendMode.SoftLight -> ComposeBlendMode.Softlight
        PdfBlendMode.Difference -> ComposeBlendMode.Difference
        PdfBlendMode.Exclusion -> ComposeBlendMode.Exclusion
        PdfBlendMode.Hue -> ComposeBlendMode.Hue
        PdfBlendMode.Saturation -> ComposeBlendMode.Saturation
        PdfBlendMode.Color -> ComposeBlendMode.Color
        PdfBlendMode.Luminosity -> ComposeBlendMode.Luminosity
    }

    private fun PdfFont.toComposeFamily(): FontFamily = when {
        baseFont.startsWith("Times") -> FontFamily.Serif
        baseFont.startsWith("Courier") -> FontFamily.Monospace
        baseFont.startsWith("Symbol") -> FontFamily.SansSerif
        baseFont.startsWith("ZapfDingbats") -> FontFamily.SansSerif
        else -> FontFamily.SansSerif
    }

    private fun PdfFont.toComposeWeight(): FontWeight =
        if ("Bold" in baseFont) FontWeight.Bold else FontWeight.Normal

    private fun PdfFont.toComposeStyle(): FontStyle =
        if ("Italic" in baseFont || "Oblique" in baseFont) FontStyle.Italic else FontStyle.Normal

    private val PI = kotlin.math.PI

    private data class ClipFrame(val path: Path)
}
