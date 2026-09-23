package io.github.yuroyami.kitepdf.nativerenderer

import io.github.yuroyami.kitepdf.core.KiteRectangle
import io.github.yuroyami.kitepdf.core.font.FontSpec
import io.github.yuroyami.kitepdf.core.font.TextGlyph
import io.github.yuroyami.kitepdf.core.render.KITE_DEFAULT_MAX_RASTER_PIXELS
import io.github.yuroyami.kitepdf.core.render.KiteBitmapCache
import io.github.yuroyami.kitepdf.core.render.KiteBlendMode
import io.github.yuroyami.kitepdf.core.render.KiteImageData
import io.github.yuroyami.kitepdf.core.render.KiteImageSampling
import io.github.yuroyami.kitepdf.core.render.KiteMaskTransfer
import io.github.yuroyami.kitepdf.core.render.KiteMatrix
import io.github.yuroyami.kitepdf.core.render.KiteCanvas
import io.github.yuroyami.kitepdf.core.render.KitePath
import io.github.yuroyami.kitepdf.core.render.paintComplexShading
import io.github.yuroyami.kitepdf.core.render.KiteShading
import io.github.yuroyami.kitepdf.core.render.RgbColor
import io.github.yuroyami.kitepdf.core.render.SoftMask
import io.github.yuroyami.kitepdf.core.render.imageSampling
import io.github.yuroyami.kitepdf.core.render.sampleStops
import io.github.yuroyami.kitepdf.core.render.shrinkRgba
import io.github.yuroyami.kitepdf.core.render.strokePen
import kotlinx.cinterop.CValue
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.cValue
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.set
import kotlinx.cinterop.useContents
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.value
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.sqrt
import platform.CoreFoundation.CFDataCreate
import platform.CoreFoundation.CFDataRef
import platform.CoreFoundation.CFRelease
import platform.CoreGraphics.CGAffineTransform
import platform.CoreGraphics.CGAffineTransformInvert
import platform.CoreGraphics.CGAffineTransformMake
import platform.CoreGraphics.CGBitmapContextCreate
import platform.CoreGraphics.CGColorSpaceCreateDeviceGray
import platform.CoreGraphics.CGContextClipToMask
import platform.CoreGraphics.CGContextFillRect
import platform.CoreGraphics.CGContextGetClipBoundingBox
import platform.CoreGraphics.CGContextRelease
import platform.CoreGraphics.CGImageRef
import platform.CoreGraphics.CGBlendMode
import platform.CoreGraphics.CGColorSpaceCreateDeviceRGB
import platform.CoreGraphics.CGColorSpaceRelease
import platform.CoreGraphics.CGContextAddLineToPoint
import platform.CoreGraphics.CGContextAddCurveToPoint
import platform.CoreGraphics.CGContextAddQuadCurveToPoint
import platform.CoreGraphics.CGContextBeginPath
import platform.CoreGraphics.CGContextBeginTransparencyLayer
import platform.CoreGraphics.CGContextClip
import platform.CoreGraphics.CGContextClosePath
import platform.CoreGraphics.CGContextConcatCTM
import platform.CoreGraphics.CGContextDrawImage
import platform.CoreGraphics.CGContextDrawLinearGradient
import platform.CoreGraphics.CGContextDrawRadialGradient
import platform.CoreGraphics.CGContextEOClip
import platform.CoreGraphics.CGContextSetLineDash
import platform.CoreGraphics.CGFloatVar
import platform.CoreGraphics.CGContextEOFillPath
import platform.CoreGraphics.CGContextEndTransparencyLayer
import platform.CoreGraphics.CGContextFillPath
import platform.CoreGraphics.CGContextGetUserSpaceToDeviceSpaceTransform
import platform.CoreGraphics.CGContextSetInterpolationQuality
import platform.CoreGraphics.kCGInterpolationHigh
import platform.CoreGraphics.kCGInterpolationLow
import platform.CoreGraphics.kCGInterpolationNone
import platform.CoreGraphics.CGContextMoveToPoint
import platform.CoreGraphics.CGContextRef
import platform.CoreGraphics.CGContextRestoreGState
import platform.CoreGraphics.CGContextSaveGState
import platform.CoreGraphics.CGContextScaleCTM
import platform.CoreGraphics.CGContextSetBlendMode
import platform.CoreGraphics.CGContextSetLineWidth
import platform.CoreGraphics.CGContextSetRGBFillColor
import platform.CoreGraphics.CGContextSetRGBStrokeColor
import platform.CoreGraphics.CGContextStrokePath
import platform.CoreGraphics.CGContextTranslateCTM
import platform.CoreGraphics.CGGradientCreateWithColorComponents
import platform.CoreGraphics.CGGradientDrawingOptions
import platform.CoreGraphics.CGGradientRelease
import platform.CoreGraphics.CGImageRelease
import platform.CoreGraphics.CGPathDrawingMode
import platform.CoreGraphics.CGPoint
import platform.CoreGraphics.CGRectMake
import platform.CoreGraphics.kCGGradientDrawsAfterEndLocation
import platform.CoreGraphics.kCGGradientDrawsBeforeStartLocation
import io.github.yuroyami.kitepdf.core.render.toRgbaBytes
import platform.CoreFoundation.CFStringCreateWithCString
import platform.CoreFoundation.kCFStringEncodingUTF8
import platform.CoreGraphics.CGContextAddPath
import platform.CoreGraphics.CGDataProviderCreateWithCFData
import platform.CoreGraphics.CGDataProviderRelease
import platform.CoreGraphics.CGGlyphVar
import platform.CoreGraphics.CGImageAlphaInfo
import platform.CoreGraphics.CGImageCreate
import platform.CoreGraphics.CGPathRelease
import kotlinx.cinterop.UShortVar
import platform.CoreGraphics.CGColorRenderingIntent
import platform.CoreText.CTFontCreatePathForGlyph
import platform.CoreText.CTFontCreateWithName
import platform.CoreText.CTFontGetGlyphsForCharacters
import platform.ImageIO.CGImageSourceCreateImageAtIndex
import platform.ImageIO.CGImageSourceCreateWithData

/**
 * [KiteCanvas] backed by an iOS / macOS [CGContextRef]. Pure CoreGraphics:
 * no Compose, no Skia. The natural choice for native iOS apps using UIKit
 * or SwiftUI; pass the context from your custom UIView's `drawRect:` or
 * the `UIGraphicsImageRenderer.image { ctx in … }` block straight in.
 *
 * Pair with [ApplePdfRasterizer] for the "give me a PNG" headless use case.
 *
 * Memory: every Core Foundation / Core Graphics ref we allocate (gradient,
 * colour space, image, CFData) is paired with a release call. Nothing
 * escapes. Kotlin/Native objects don't leak, and we never hand a CG ref
 * to caller code.
 */
@OptIn(ExperimentalForeignApi::class)
public class CoreGraphicsCanvas(private val ctx: CGContextRef) : KiteCanvas {

    private var openLayers = 0

    override fun beginPage(widthPt: Double, heightPt: Double, deviceCtm: KiteMatrix) {
        openLayers = 0
    }

    override fun endPage() {
        while (openLayers > 0) {
            CGContextRestoreGState(ctx)
            openLayers--
        }
    }

    override fun fillPath(
        path: KitePath, ctm: KiteMatrix, color: RgbColor, evenOdd: Boolean,
        alpha: Double, blendMode: KiteBlendMode,
    ) {
        CGContextSaveGState(ctx)
        try {
            CGContextSetBlendMode(ctx, blendMode.toCG())
            CGContextSetRGBFillColor(ctx, color.r, color.g, color.b, alpha)
            buildPath(path, ctm)
            if (evenOdd) CGContextEOFillPath(ctx) else CGContextFillPath(ctx)
        } finally {
            CGContextRestoreGState(ctx)
        }
    }

    override fun strokePath(
        path: KitePath, ctm: KiteMatrix, color: RgbColor, lineWidth: Double,
        alpha: Double, blendMode: KiteBlendMode,
        dashArray: List<Double>?, dashPhase: Double,
        lineCap: Int, lineJoin: Int, miterLimit: Double,
    ) {
        val pen = strokePen(ctm, lineWidth, floorPx = 0.1)
        CGContextSaveGState(ctx)
        try {
            // An elliptical pen strokes in user space: Core Graphics applies the CTM to the
            // line width and the dashes when it strokes.
            pen.strokeMatrix?.let { CGContextConcatCTM(ctx, it.toCGAffine()) }
            CGContextSetBlendMode(ctx, blendMode.toCG())
            CGContextSetRGBStrokeColor(ctx, color.r, color.g, color.b, alpha)
            CGContextSetLineWidth(ctx, pen.width)
            // PDF cap/join codes match Core Graphics' enum ordinals (butt/round/square,
            // miter/round/bevel).
            platform.CoreGraphics.CGContextSetLineCap(ctx, when (lineCap) {
                1 -> platform.CoreGraphics.CGLineCap.kCGLineCapRound
                2 -> platform.CoreGraphics.CGLineCap.kCGLineCapSquare
                else -> platform.CoreGraphics.CGLineCap.kCGLineCapButt
            })
            platform.CoreGraphics.CGContextSetLineJoin(ctx, when (lineJoin) {
                1 -> platform.CoreGraphics.CGLineJoin.kCGLineJoinRound
                2 -> platform.CoreGraphics.CGLineJoin.kCGLineJoinBevel
                else -> platform.CoreGraphics.CGLineJoin.kCGLineJoinMiter
            })
            platform.CoreGraphics.CGContextSetMiterLimit(ctx, miterLimit.coerceAtLeast(1.0))
            if (!dashArray.isNullOrEmpty()) {
                // Dash lengths are user-space units; device px = unit × scale.
                memScoped {
                    val lengths = allocArray<CGFloatVar>(dashArray.size)
                    for (i in dashArray.indices) lengths[i] = (dashArray[i] * pen.dashScale)
                    CGContextSetLineDash(ctx, dashPhase * pen.dashScale, lengths, dashArray.size.toULong())
                }
            }
            buildPath(path, pen.pathMatrix)
            CGContextStrokePath(ctx)
        } finally {
            CGContextRestoreGState(ctx)
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

        val unitScale = fontSize / unitsPerEm  // glyph outlines: font units → text space
        val advanceScale = fontSize / 1000.0   // advances are 1/1000 em, not font units
        var drewAny = false
        CGContextSaveGState(ctx)
        try {
            CGContextSetBlendMode(ctx, blendMode.toCG())
            CGContextSetRGBFillColor(ctx, color.r, color.g, color.b, alpha)
            var penX = 0.0
            for (glyph in glyphs) {
                val outline = glyph.outline
                if (outline != null && !outline.isEmpty()) {
                    val glyphMatrix = textToDevice
                        .concat(KiteMatrix.translation(penX + glyph.xOffset * unitScale, glyph.yOffset * unitScale))
                        .concat(KiteMatrix(unitScale, 0.0, 0.0, unitScale, 0.0, 0.0))
                    buildPath(outline, glyphMatrix)
                    CGContextFillPath(ctx)
                    drewAny = true
                }
                penX += glyph.advanceWidth * advanceScale + glyph.advanceAdjust
            }
        } finally {
            CGContextRestoreGState(ctx)
        }
        // Embedded font present but produced no glyphs (e.g. a subset we can't
        // decode). Fall back to a system font rather than rendering blank.
        if (!drewAny && glyphs.any { it.text.isNotBlank() }) {
            drawTextViaSystemFont(glyphs, fontSize, fontSpec, textToDevice, color, alpha, blendMode)
        }
    }

    /**
     * Standard-14 and other non-embedded fonts: fill CoreText glyph paths.
     * Pen positions use PDF's 1/1000 em advances, so layout matches what the
     * document assigned rather than the substitute font's own metrics.
     */
    private fun drawTextViaSystemFont(
        glyphs: List<TextGlyph>,
        fontSize: Double,
        fontSpec: FontSpec,
        textToDevice: KiteMatrix,
        color: RgbColor,
        alpha: Double,
        blendMode: KiteBlendMode,
    ) {
        val fontName = systemFontName(fontSpec)
        val cfName = CFStringCreateWithCString(null, fontName, kCFStringEncodingUTF8) ?: return
        val font = CTFontCreateWithName(cfName, fontSize, null)
        CFRelease(cfName)
        if (font == null) return
        try {
            val advanceScale = fontSize / 1000.0
            var penX = 0.0
            for (glyph in glyphs) {
                val ch = glyph.text.firstOrNull()
                if (ch == null) {
                    penX += glyph.advanceWidth * advanceScale + glyph.advanceAdjust
                    continue
                }
                val path = memScoped {
                    val chars = alloc<UShortVar>()
                    chars.value = ch.code.toUShort()
                    val ids = alloc<CGGlyphVar>()
                    if (!CTFontGetGlyphsForCharacters(font, chars.ptr, ids.ptr, 1)) null
                    else CTFontCreatePathForGlyph(font, ids.value, null)
                }
                if (path != null) {
                    CGContextSaveGState(ctx)
                    try {
                        CGContextSetBlendMode(ctx, blendMode.toCG())
                        CGContextSetRGBFillColor(ctx, color.r, color.g, color.b, alpha)
                        // path (text-size units, y-up) -> +pen (text space) -> textToDevice.
                        CGContextConcatCTM(ctx, textToDevice.toCGAffine())
                        CGContextTranslateCTM(ctx, penX + glyph.xOffset * advanceScale, glyph.yOffset * advanceScale)
                        CGContextBeginPath(ctx)
                        CGContextAddPath(ctx, path)
                        CGContextFillPath(ctx)
                    } finally {
                        CGContextRestoreGState(ctx)
                        CGPathRelease(path)
                    }
                }
                penX += glyph.advanceWidth * advanceScale + glyph.advanceAdjust
            }
        } finally {
            CFRelease(font)
        }
    }

    private fun systemFontName(spec: FontSpec): String = when (spec.family) {
        io.github.yuroyami.kitepdf.core.font.KiteFontFamily.Serif -> when {
            spec.bold && spec.italic -> "Times-BoldItalic"
            spec.bold -> "Times-Bold"
            spec.italic -> "Times-Italic"
            else -> "Times-Roman"
        }
        io.github.yuroyami.kitepdf.core.font.KiteFontFamily.Monospace -> when {
            spec.bold && spec.italic -> "Courier-BoldOblique"
            spec.bold -> "Courier-Bold"
            spec.italic -> "Courier-Oblique"
            else -> "Courier"
        }
        io.github.yuroyami.kitepdf.core.font.KiteFontFamily.SansSerif -> when {
            spec.bold && spec.italic -> "Helvetica-BoldOblique"
            spec.bold -> "Helvetica-Bold"
            spec.italic -> "Helvetica-Oblique"
            else -> "Helvetica"
        }
    }

    override fun fillShading(
        shading: KiteShading, ctm: KiteMatrix, clipPath: KitePath?,
        alpha: Double, blendMode: KiteBlendMode,
    ) {
        if (paintComplexShading(shading, ctm, clipPath, alpha, blendMode)) return
        val stops = shading.sampleStops() ?: return
        // The gradient is drawn in shading space under the whole CTM, so a non-uniform
        // or skewed CTM turns circles into ellipses and tilts the bands (ISO 32000-1,
        // 8.7.4.5.3 and 8.7.4.5.4). A CTM without an inverse paints nothing.
        val det = ctm.a * ctm.d - ctm.b * ctm.c
        if (det == 0.0 || !det.isFinite()) return

        CGContextSaveGState(ctx)
        try {
            CGContextSetBlendMode(ctx, blendMode.toCG())

            // Clip to the fill region if requested.
            if (clipPath != null) {
                buildPath(clipPath, ctm)
                CGContextClip(ctx)
            }
            CGContextConcatCTM(ctx, ctm.toCGAffine())

            val space = CGColorSpaceCreateDeviceRGB()
            try {
                memScoped {
                    val nStops = stops.colors.size
                    val components = allocArray<DoubleVar>(nStops * 4)
                    val locations = allocArray<DoubleVar>(nStops)
                    for (i in 0 until nStops) {
                        components[i * 4] = stops.colors[i].r
                        components[i * 4 + 1] = stops.colors[i].g
                        components[i * 4 + 2] = stops.colors[i].b
                        components[i * 4 + 3] = alpha
                        locations[i] = stops.offsets[i]
                    }
                    val gradient = CGGradientCreateWithColorComponents(
                        space, components, locations, nStops.toULong(),
                    ) ?: return@memScoped
                    try {
                        // The two draw options are the two extend flags (ISO 32000-1,
                        // 8.7.4.5.3 and 8.7.4.5.4): an end that is not extended paints nothing past it.
                        val (extendStart, extendEnd) = when (shading) {
                            is KiteShading.Axial -> shading.extendStart to shading.extendEnd
                            is KiteShading.Radial -> shading.extendStart to shading.extendEnd
                            else -> true to true
                        }
                        val drawOpts: CGGradientDrawingOptions =
                            (if (extendStart) kCGGradientDrawsBeforeStartLocation else 0u) or
                                (if (extendEnd) kCGGradientDrawsAfterEndLocation else 0u)
                        when (shading) {
                            is KiteShading.Axial -> {
                                val c = shading.coords
                                val start = cValue<CGPoint> { x = c[0]; y = c[1] }
                                val end = cValue<CGPoint> { x = c[2]; y = c[3] }
                                CGContextDrawLinearGradient(ctx, gradient, start, end, drawOpts)
                            }
                            is KiteShading.Radial -> {
                                // True PDF two-circle radial. Core Graphics takes both circles.
                                // The outer radius is at least a tenth of a device pixel.
                                val c = shading.coords
                                val r0 = c[2].coerceAtLeast(0.0)
                                val r1 = c[5].coerceAtLeast(0.1 / kotlin.math.sqrt(kotlin.math.abs(det)))
                                val startC = cValue<CGPoint> { x = c[0]; y = c[1] }
                                val endC = cValue<CGPoint> { x = c[3]; y = c[4] }
                                CGContextDrawRadialGradient(
                                    ctx, gradient, startC, r0, endC, r1, drawOpts,
                                )
                            }
                            is KiteShading.Unsupported -> Unit
                            else -> Unit // complex shading types already handled by paintComplexShading
                        }
                    } finally {
                        CGGradientRelease(gradient)
                    }
                }
            } finally {
                CGColorSpaceRelease(space)
            }
        } finally {
            CGContextRestoreGState(ctx)
        }
    }

    override fun pushClip(path: KitePath, ctm: KiteMatrix, evenOdd: Boolean) {
        CGContextSaveGState(ctx)
        openLayers++
        buildPath(path, ctm)
        if (evenOdd) CGContextEOClip(ctx) else CGContextClip(ctx)
    }

    override fun popClip() {
        if (openLayers > 0) {
            CGContextRestoreGState(ctx)
            openLayers--
        }
    }

    override fun drawImage(image: KiteImageData, ctm: KiteMatrix, alpha: Double) {
        drawImage(image, ctm, alpha, KiteBlendMode.Normal)
    }

    override fun drawImage(image: KiteImageData, ctm: KiteMatrix, alpha: Double, blendMode: KiteBlendMode) {
        // One sampling policy on every canvas (#122, #123), read from the whole transform to device pixels.
        val userToDevice = CGContextGetUserSpaceToDeviceSpaceTransform(ctx).useContents { KiteMatrix(a, b, c, d, tx, ty) }
        val sampling = imageSampling(image.width, image.height, userToDevice.concat(ctm), image.interpolate)
        val cgImage = decodeImage(image, sampling)
        if (cgImage == null) {
            drawPlaceholder(ctm)
            return
        }
        val a = alpha.coerceIn(0.0, 1.0)
        if (a <= 0.0) {
            CGImageRelease(cgImage)
            return
        }
        try {
            CGContextSaveGState(ctx)
            try {
                // PDF image space is the unit square under the CTM, with the first row
                // of the image at v = 1 (ISO 32000-1, 8.9.4). CGContextDrawImage draws
                // the first row at the top of its rectangle, so no flip is needed (#289).
                CGContextSetBlendMode(ctx, blendMode.toCG())
                CGContextConcatCTM(ctx, ctm.toCGAffine())
                val quality = when {
                    // A RAW image is averaged down in decodeImage. CoreGraphics averages an encoded one itself.
                    sampling.shrinks && image.kind != KiteImageData.Kind.RAW -> kCGInterpolationHigh
                    sampling.smooth -> kCGInterpolationLow
                    else -> kCGInterpolationNone
                }
                CGContextSetInterpolationQuality(ctx, quality)
                if (a < 1.0) platform.CoreGraphics.CGContextSetAlpha(ctx, a)
                CGContextDrawImage(ctx, CGRectMake(0.0, 0.0, 1.0, 1.0), cgImage)
            } finally {
                CGContextRestoreGState(ctx)
            }
        } finally {
            CGImageRelease(cgImage)
        }
    }

    private fun decodeImage(image: KiteImageData, sampling: KiteImageSampling): platform.CoreGraphics.CGImageRef? {
        if (image.kind == KiteImageData.Kind.RAW) return rawCgImage(image, sampling)
        val bytes = image.encodedBytes
        if (bytes.isEmpty()) return null
        if (image.kind !in IMAGE_KINDS_DECODABLE_BY_CG) return null

        val cfData = bytes.toCFData() ?: return null
        try {
            val source = CGImageSourceCreateWithData(cfData, null) ?: return null
            try {
                return CGImageSourceCreateImageAtIndex(source, 0.toULong(), null)
            } finally {
                CFRelease(source)
            }
        } finally {
            CFRelease(cfData)
        }
    }

    /**
     * Decoded samples: what every successful JPEG / JPX / JBIG2 decode
     * produces, plus plain Flate images. The CFData owns a copy of the
     * pixels, so the CGImage stays valid after the Kotlin array is gone.
     * An image drawn smaller than its pixels is averaged down first (#122).
     */
    private fun rawCgImage(image: KiteImageData, sampling: KiteImageSampling): platform.CoreGraphics.CGImageRef? {
        val pixels = rgbaImages.getOrPut(image, sampling, { it.rgba.size.toLong() }) {
            val full = image.toRgbaBytes() ?: return@getOrPut null
            val rgba = if (sampling.shrinks) shrinkRgba(full, image.width, image.height, sampling.shrinkX, sampling.shrinkY) else full
            RgbaImage(rgba, sampling.shrunkWidth(image.width), sampling.shrunkHeight(image.height))
        } ?: return null
        val width = pixels.width
        val height = pixels.height
        val cfData = pixels.rgba.toCFData() ?: return null
        val provider = CGDataProviderCreateWithCFData(cfData)
        CFRelease(cfData)   // the provider holds its own reference
        if (provider == null) return null
        val cs = CGColorSpaceCreateDeviceRGB()
        val img = CGImageCreate(
            width.toULong(), height.toULong(),
            8u, 32u, (width * 4).toULong(), cs,
            CGImageAlphaInfo.kCGImageAlphaLast.value,
            provider, null, sampling.smooth, CGColorRenderingIntent.kCGRenderingIntentDefault,
        )
        CGColorSpaceRelease(cs)
        CGDataProviderRelease(provider)
        return img
    }

    /** Straight RGBA pixels of [width] by [height]. */
    private class RgbaImage(val rgba: ByteArray, val width: Int, val height: Int)

    /**
     * RGBA built from RAW images, so that an image drawn many times converts once (#117).
     * It holds Kotlin arrays only: each draw makes its own CGImage and releases it.
     */
    private val rgbaImages = KiteBitmapCache<RgbaImage>()

    private fun drawPlaceholder(ctm: KiteMatrix) {
        CGContextSaveGState(ctx)
        try {
            CGContextConcatCTM(ctx, ctm.toCGAffine())
            CGContextSetRGBFillColor(ctx, 0.88, 0.88, 0.88, 1.0)
            CGContextSetRGBStrokeColor(ctx, 0.53, 0.53, 0.53, 1.0)
            CGContextSetLineWidth(ctx, 0.01)
            // The CTM maps the unit square (0,0)-(1,1); same frame as drawImage.
            val rect = CGRectMake(0.0, 0.0, 1.0, 1.0)
            platform.CoreGraphics.CGContextFillRect(ctx, rect)
            platform.CoreGraphics.CGContextStrokeRect(ctx, rect)
        } finally {
            CGContextRestoreGState(ctx)
        }
    }

    override fun beginTransparencyGroup(
        bbox: KiteRectangle, ctm: KiteMatrix,
        isolated: Boolean, knockout: Boolean,
        alpha: Double, blendMode: KiteBlendMode,
    ) {
        CGContextSaveGState(ctx)
        platform.CoreGraphics.CGContextSetAlpha(ctx, alpha)
        CGContextSetBlendMode(ctx, blendMode.toCG())
        CGContextBeginTransparencyLayer(ctx, null)
        openLayers++
    }

    override fun endTransparencyGroup() {
        if (openLayers > 0) {
            CGContextEndTransparencyLayer(ctx)
            CGContextRestoreGState(ctx)
            openLayers--
        }
    }

    override fun applySoftMask(
        kind: SoftMask.Kind,
        maskBBox: KiteRectangle, maskCtm: KiteMatrix,
        render: () -> Unit,
        renderMask: (KiteCanvas) -> Unit,
    ) {
        applySoftMask(kind, maskBBox, maskCtm, null, render, renderMask)
    }

    /**
     * ISO 32000-1, 11.6.5.2: the mask group draws into a bitmap of its own. Its alpha, or
     * its luminosity over a black backdrop, becomes a grey mask that the content paints
     * through with CGContextClipToMask. So the mask group's colours never reach the page,
     * and each paint keeps its own blend mode against the page (#79). The [transfer]
     * table maps each value of the grey mask.
     */
    override fun applySoftMask(
        kind: SoftMask.Kind,
        maskBBox: KiteRectangle, maskCtm: KiteMatrix,
        transfer: KiteMaskTransfer?,
        render: () -> Unit,
        renderMask: (KiteCanvas) -> Unit,
    ) {
        val toDevice = CGContextGetUserSpaceToDeviceSpaceTransform(ctx)
        val userToDevice = toDevice.useContents { KiteMatrix(a, b, c, d, tx, ty) }
        val area = maskArea(maskBBox, userToDevice.concat(maskCtm), userToDevice)
        if (area == null) {
            render() // Malformed geometry: keep the paint without its unusable mask.
            return
        }
        val (x0, y0) = area[0] to area[1]
        val width = area[2] - x0
        val height = area[3] - y0
        // The mask is zero wherever the content could show.
        if (width <= 0 || height <= 0) return
        val pixels = width.toLong() * height
        if (pixels > MASK_PIXEL_BUDGET * UNBOUNDED_MASK_FACTOR) {
            render() // An unclipped box far past any page: keep the paint without its mask.
            return
        }
        // Past the raster budget the mask applies at a lower resolution instead of being dropped.
        val scale = if (pixels > MASK_PIXEL_BUDGET) sqrt(MASK_PIXEL_BUDGET.toDouble() / pixels) else 1.0
        val w = maxOf(1, ceil(width * scale).toInt())
        val h = maxOf(1, ceil(height * scale).toInt())
        val values = maskValues(kind, w, h, x0, y0, scale, toDevice, renderMask)
        if (values != null && transfer != null) {
            for (i in values.indices) values[i] = transfer[values[i].toInt() and 255].toByte()
        }
        val mask = values?.let { greyImage(it, w, h) }
        if (mask == null) {
            render()
            return
        }
        CGContextSaveGState(ctx)
        try {
            // Clip in device pixels, then return to the user space the content paints in.
            CGContextConcatCTM(ctx, CGAffineTransformInvert(toDevice))
            CGContextClipToMask(ctx, CGRectMake(x0.toDouble(), y0.toDouble(), width.toDouble(), height.toDouble()), mask)
            CGContextConcatCTM(ctx, toDevice)
            render()
        } finally {
            CGContextRestoreGState(ctx)
            CGImageRelease(mask)
        }
    }

    /**
     * The device pixels that [box] covers under [boxToDevice], inside the clip, as
     * left, bottom, right and top. Null when the geometry is not finite.
     */
    private fun maskArea(box: KiteRectangle, boxToDevice: KiteMatrix, userToDevice: KiteMatrix): IntArray? {
        val b = box.normalized()
        val bounds = deviceBounds(b.left, b.bottom, b.right, b.top, boxToDevice) ?: return null
        val clip = CGContextGetClipBoundingBox(ctx).useContents {
            deviceBounds(origin.x, origin.y, origin.x + size.width, origin.y + size.height, userToDevice)
        }
        // A context without a finite clip box leaves the mask box as it is.
        if (clip != null) {
            bounds[0] = maxOf(bounds[0], clip[0])
            bounds[1] = maxOf(bounds[1], clip[1])
            bounds[2] = minOf(bounds[2], clip[2])
            bounds[3] = minOf(bounds[3], clip[3])
        }
        return intArrayOf(floor(bounds[0]).toInt(), floor(bounds[1]).toInt(), ceil(bounds[2]).toInt(), ceil(bounds[3]).toInt())
    }

    /** The bounds of a rectangle under [m] as left, bottom, right and top, or null when not finite. */
    private fun deviceBounds(left: Double, bottom: Double, right: Double, top: Double, m: KiteMatrix): DoubleArray? {
        val xs = doubleArrayOf(m.transformX(left, bottom), m.transformX(right, bottom), m.transformX(left, top), m.transformX(right, top))
        val ys = doubleArrayOf(m.transformY(left, bottom), m.transformY(right, bottom), m.transformY(left, top), m.transformY(right, top))
        if (!xs.all { it.isFinite() } || !ys.all { it.isFinite() }) return null
        // Keep the bounds within what an Int can hold: the clip cuts them down anyway.
        fun c(v: Double) = v.coerceIn(-1e9, 1e9)
        return doubleArrayOf(c(xs.min()), c(ys.min()), c(xs.max()), c(ys.max()))
    }

    /**
     * One mask value per pixel, from the top row down, for [w] by [h] pixels whose bottom
     * left corner is device pixel ([x0], [y0]) at [scale]. The value is the mask group's
     * alpha, or for a luminosity mask its luminosity over black (ISO 32000-1, 11.5.3).
     */
    private fun maskValues(
        kind: SoftMask.Kind, w: Int, h: Int, x0: Int, y0: Int, scale: Double,
        toDevice: CValue<CGAffineTransform>, renderMask: (KiteCanvas) -> Unit,
    ): ByteArray? {
        val rgba = ByteArray(w * h * 4)
        rgba.usePinned { pinned ->
            val space = CGColorSpaceCreateDeviceRGB()
            val maskCtx = CGBitmapContextCreate(
                pinned.addressOf(0), w.toULong(), h.toULong(), 8u, (w * 4).toULong(), space,
                CGImageAlphaInfo.kCGImageAlphaPremultipliedLast.value,
            )
            CGColorSpaceRelease(space)
            if (maskCtx == null) return null
            try {
                if (kind == SoftMask.Kind.Luminosity) {
                    // Unpainted pixels show the black backdrop, whose luminosity is zero.
                    CGContextSetRGBFillColor(maskCtx, 0.0, 0.0, 0.0, 1.0)
                    CGContextFillRect(maskCtx, CGRectMake(0.0, 0.0, w.toDouble(), h.toDouble()))
                }
                CGContextScaleCTM(maskCtx, scale, scale)
                CGContextTranslateCTM(maskCtx, -x0.toDouble(), -y0.toDouble())
                CGContextConcatCTM(maskCtx, toDevice)
                renderMask(CoreGraphicsCanvas(maskCtx))
            } finally {
                CGContextRelease(maskCtx)
            }
        }
        if (kind == SoftMask.Kind.Alpha) return ByteArray(w * h) { rgba[it * 4 + 3] }
        // Opaque over black, so the premultiplied colour is the colour itself.
        return ByteArray(w * h) { i ->
            val r = rgba[i * 4].toInt() and 255
            val g = rgba[i * 4 + 1].toInt() and 255
            val b = rgba[i * 4 + 2].toInt() and 255
            ((r * 77 + g * 150 + b * 29) ushr 8).toByte()
        }
    }

    /** A DeviceGray image of [values], [w] by [h] pixels, for CGContextClipToMask. */
    private fun greyImage(values: ByteArray, w: Int, h: Int): CGImageRef? {
        val cfData = values.toCFData() ?: return null
        val provider = CGDataProviderCreateWithCFData(cfData)
        CFRelease(cfData)   // the provider holds its own reference
        if (provider == null) return null
        val cs = CGColorSpaceCreateDeviceGray()
        val img = CGImageCreate(
            w.toULong(), h.toULong(), 8u, 8u, w.toULong(), cs,
            CGImageAlphaInfo.kCGImageAlphaNone.value,
            provider, null, true, CGColorRenderingIntent.kCGRenderingIntentDefault,
        )
        CGColorSpaceRelease(cs)
        CGDataProviderRelease(provider)
        return img
    }

    /* ─── Helpers ─────────────────────────────────────────────────────────── */

    private fun buildPath(src: KitePath, ctm: KiteMatrix) {
        CGContextBeginPath(ctx)
        for (seg in src.segments) {
            when (seg) {
                is KitePath.Segment.MoveTo -> {
                    val x = ctm.transformX(seg.x, seg.y)
                    val y = ctm.transformY(seg.x, seg.y)
                    CGContextMoveToPoint(ctx, x, y)
                }
                is KitePath.Segment.LineTo -> {
                    val x = ctm.transformX(seg.x, seg.y)
                    val y = ctm.transformY(seg.x, seg.y)
                    CGContextAddLineToPoint(ctx, x, y)
                }
                is KitePath.Segment.CurveTo -> {
                    val x1 = ctm.transformX(seg.x1, seg.y1)
                    val y1 = ctm.transformY(seg.x1, seg.y1)
                    val x2 = ctm.transformX(seg.x2, seg.y2)
                    val y2 = ctm.transformY(seg.x2, seg.y2)
                    val x3 = ctm.transformX(seg.x3, seg.y3)
                    val y3 = ctm.transformY(seg.x3, seg.y3)
                    CGContextAddCurveToPoint(ctx, x1, y1, x2, y2, x3, y3)
                }
                is KitePath.Segment.QuadTo -> {
                    val x1 = ctm.transformX(seg.x1, seg.y1)
                    val y1 = ctm.transformY(seg.x1, seg.y1)
                    val x2 = ctm.transformX(seg.x2, seg.y2)
                    val y2 = ctm.transformY(seg.x2, seg.y2)
                    CGContextAddQuadCurveToPoint(ctx, x1, y1, x2, y2)
                }
                KitePath.Segment.Close -> CGContextClosePath(ctx)
            }
        }
    }

    private fun KiteMatrix.toCGAffine(): CValue<platform.CoreGraphics.CGAffineTransform> =
        CGAffineTransformMake(a, b, c, d, e, f)

    private fun KiteBlendMode.toCG(): CGBlendMode = when (this) {
        KiteBlendMode.Normal -> CGBlendMode.kCGBlendModeNormal
        KiteBlendMode.Multiply -> CGBlendMode.kCGBlendModeMultiply
        KiteBlendMode.Screen -> CGBlendMode.kCGBlendModeScreen
        KiteBlendMode.Overlay -> CGBlendMode.kCGBlendModeOverlay
        KiteBlendMode.Darken -> CGBlendMode.kCGBlendModeDarken
        KiteBlendMode.Lighten -> CGBlendMode.kCGBlendModeLighten
        KiteBlendMode.ColorDodge -> CGBlendMode.kCGBlendModeColorDodge
        KiteBlendMode.ColorBurn -> CGBlendMode.kCGBlendModeColorBurn
        KiteBlendMode.HardLight -> CGBlendMode.kCGBlendModeHardLight
        KiteBlendMode.SoftLight -> CGBlendMode.kCGBlendModeSoftLight
        KiteBlendMode.Difference -> CGBlendMode.kCGBlendModeDifference
        KiteBlendMode.Exclusion -> CGBlendMode.kCGBlendModeExclusion
        KiteBlendMode.Hue -> CGBlendMode.kCGBlendModeHue
        KiteBlendMode.Saturation -> CGBlendMode.kCGBlendModeSaturation
        KiteBlendMode.Color -> CGBlendMode.kCGBlendModeColor
        KiteBlendMode.Luminosity -> CGBlendMode.kCGBlendModeLuminosity
    }

    private companion object {
        /** Pixels one soft mask may use before it drops to a lower resolution, as on AWT. */
        const val MASK_PIXEL_BUDGET = KITE_DEFAULT_MAX_RASTER_PIXELS

        /** A mask area this many budgets wide cannot be a page render; it keeps the paint unmasked. */
        const val UNBOUNDED_MASK_FACTOR = 16L

        val IMAGE_KINDS_DECODABLE_BY_CG = setOf(
            KiteImageData.Kind.JPEG,
            KiteImageData.Kind.JPEG2000,
        )
    }
}

@OptIn(ExperimentalForeignApi::class)
private fun ByteArray.toCFData(): CFDataRef? = usePinned { pinned ->
    CFDataCreate(null, pinned.addressOf(0).reinterpret(), size.toLong())
}

@OptIn(ExperimentalForeignApi::class)
private typealias DoubleVar = kotlinx.cinterop.DoubleVar
