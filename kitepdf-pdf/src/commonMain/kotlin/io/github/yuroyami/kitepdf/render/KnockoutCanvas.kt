package io.github.yuroyami.kitepdf.render

import io.github.yuroyami.kitepdf.core.KiteRectangle
import io.github.yuroyami.kitepdf.core.font.FontSpec
import io.github.yuroyami.kitepdf.core.font.TextGlyph
import io.github.yuroyami.kitepdf.core.render.KiteBlendMode
import io.github.yuroyami.kitepdf.core.render.KiteCanvas
import io.github.yuroyami.kitepdf.core.render.KiteImageData
import io.github.yuroyami.kitepdf.core.render.KiteMaskTransfer
import io.github.yuroyami.kitepdf.core.render.KiteMatrix
import io.github.yuroyami.kitepdf.core.render.KitePath
import io.github.yuroyami.kitepdf.core.render.KiteRaster
import io.github.yuroyami.kitepdf.core.render.KiteRasterScope
import io.github.yuroyami.kitepdf.core.render.KiteShading
import io.github.yuroyami.kitepdf.core.render.RgbColor
import io.github.yuroyami.kitepdf.core.render.SoftMask

/**
 * The canvas that the content of a non-isolated knockout group whose paints blend draws
 * through (ISO 32000-1, 11.4.6, #308). Each elementary object composites with the group's
 * initial backdrop, the page, and replaces what the objects before it left within its shape.
 * No canvas can paint that, so the renderer marks each object with [element], and this canvas
 * paints it three times into rasters of the group's raster step [scope]: over a copy of
 * [backdrop], at full alpha in Normal for its shape, and alone for its alpha.
 * [GroupRasters.Knockout] adds them up.
 *
 * A render starts without the clips of the page, so a clip pushed between two objects is kept
 * here and pushed again inside each object's render.
 */
internal class KnockoutCanvas(
    private val inner: KiteCanvas,
    private val scope: KiteRasterScope,
    private val backdrop: KiteRaster,
) : KiteCanvas by inner {

    /** The group so far. */
    val group = GroupRasters.Knockout(backdrop)

    /** True while an object renders, when every paint goes straight to [inner]. */
    val inElement: Boolean get() = depth > 0

    private var depth = 0

    /** True while an object renders for its shape: every paint at full alpha in Normal. */
    private var shapeOnly = false

    private class Clip(val path: KitePath, val ctm: KiteMatrix, val evenOdd: Boolean)

    private val clips = ArrayList<Clip>()

    /**
     * Renders one elementary object, [paint], three times and adds it to the group. [reset]
     * puts the renderer's state back before each time, so an object that moves the text
     * position moves it once.
     */
    fun element(reset: () -> Unit, paint: () -> Unit) {
        if (depth > 0) return paint()
        depth++
        try {
            reset()
            val over = scope.render(backdrop) { clipped(paint) }
            reset()
            shapeOnly = true
            val shape = try {
                scope.render { clipped(paint) }
            } finally {
                shapeOnly = false
            }
            reset()
            val alone = scope.render { clipped(paint) }
            group.add(over, shape, alone)
        } finally {
            depth--
        }
    }

    private fun clipped(paint: () -> Unit) {
        for (clip in clips) inner.pushClip(clip.path, clip.ctm, clip.evenOdd)
        try {
            paint()
        } finally {
            repeat(clips.size) { inner.popClip() }
        }
    }

    private fun alpha(alpha: Double) = if (shapeOnly) 1.0 else alpha
    private fun mode(mode: KiteBlendMode) = if (shapeOnly) KiteBlendMode.Normal else mode

    override fun pushClip(path: KitePath, ctm: KiteMatrix, evenOdd: Boolean) {
        if (depth > 0) inner.pushClip(path, ctm, evenOdd) else clips += Clip(path, ctm, evenOdd)
    }

    override fun popClip() {
        if (depth > 0) inner.popClip() else clips.removeLastOrNull()
    }

    // A paint that reaches this canvas outside an object is an object of its own.
    override fun fillPath(path: KitePath, ctm: KiteMatrix, color: RgbColor, evenOdd: Boolean, alpha: Double, blendMode: KiteBlendMode) {
        if (depth == 0) element({}) { fillPath(path, ctm, color, evenOdd, alpha, blendMode) }
        else inner.fillPath(path, ctm, color, evenOdd, alpha(alpha), mode(blendMode))
    }

    override fun strokePath(
        path: KitePath, ctm: KiteMatrix, color: RgbColor, lineWidth: Double, alpha: Double, blendMode: KiteBlendMode,
        dashArray: List<Double>?, dashPhase: Double, lineCap: Int, lineJoin: Int, miterLimit: Double,
    ) {
        if (depth == 0) element({}) { strokePath(path, ctm, color, lineWidth, alpha, blendMode, dashArray, dashPhase, lineCap, lineJoin, miterLimit) }
        else inner.strokePath(path, ctm, color, lineWidth, alpha(alpha), mode(blendMode), dashArray, dashPhase, lineCap, lineJoin, miterLimit)
    }

    override fun drawGlyphs(
        glyphs: List<TextGlyph>, fontSize: Double, unitsPerEm: Int, hasOutlines: Boolean, fontSpec: FontSpec,
        textToDevice: KiteMatrix, color: RgbColor, alpha: Double, blendMode: KiteBlendMode,
    ) {
        if (depth == 0) element({}) { drawGlyphs(glyphs, fontSize, unitsPerEm, hasOutlines, fontSpec, textToDevice, color, alpha, blendMode) }
        else inner.drawGlyphs(glyphs, fontSize, unitsPerEm, hasOutlines, fontSpec, textToDevice, color, alpha(alpha), mode(blendMode))
    }

    override fun fillShading(shading: KiteShading, ctm: KiteMatrix, clipPath: KitePath?, alpha: Double, blendMode: KiteBlendMode) {
        if (depth == 0) element({}) { fillShading(shading, ctm, clipPath, alpha, blendMode) }
        else inner.fillShading(shading, ctm, clipPath, alpha(alpha), mode(blendMode))
    }

    override fun drawImage(image: KiteImageData, ctm: KiteMatrix, alpha: Double) {
        if (depth == 0) element({}) { drawImage(image, ctm, alpha) }
        else inner.drawImage(image, ctm, alpha(alpha))
    }

    override fun drawImage(image: KiteImageData, ctm: KiteMatrix, alpha: Double, blendMode: KiteBlendMode) {
        when {
            depth == 0 -> element({}) { drawImage(image, ctm, alpha, blendMode) }
            shapeOnly -> inner.drawImage(image, ctm, 1.0)
            else -> inner.drawImage(image, ctm, alpha, blendMode)
        }
    }

    override fun beginTransparencyGroup(
        bbox: KiteRectangle, ctm: KiteMatrix, isolated: Boolean, knockout: Boolean, alpha: Double, blendMode: KiteBlendMode,
    ) = inner.beginTransparencyGroup(bbox, ctm, isolated, knockout, alpha(alpha), mode(blendMode))

    // A soft mask gates an object's opacity, not its shape (ISO 32000-1, 11.6.5).
    override fun applySoftMask(
        kind: SoftMask.Kind, maskBBox: KiteRectangle, maskCtm: KiteMatrix, render: () -> Unit, renderMask: (KiteCanvas) -> Unit,
    ) {
        if (shapeOnly) render() else inner.applySoftMask(kind, maskBBox, maskCtm, render, renderMask)
    }

    override fun applySoftMask(
        kind: SoftMask.Kind, maskBBox: KiteRectangle, maskCtm: KiteMatrix, transfer: KiteMaskTransfer?,
        render: () -> Unit, renderMask: (KiteCanvas) -> Unit,
    ) {
        if (shapeOnly) render() else inner.applySoftMask(kind, maskBBox, maskCtm, transfer, render, renderMask)
    }
}
