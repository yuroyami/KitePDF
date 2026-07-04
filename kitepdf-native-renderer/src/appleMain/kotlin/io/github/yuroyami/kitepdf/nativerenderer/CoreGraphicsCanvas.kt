package io.github.yuroyami.kitepdf.nativerenderer

import io.github.yuroyami.kitepdf.Rectangle
import io.github.yuroyami.kitepdf.font.FontSpec
import io.github.yuroyami.kitepdf.font.TextGlyph
import io.github.yuroyami.kitepdf.render.BlendMode as PdfBlendMode
import io.github.yuroyami.kitepdf.render.ImageXObject
import io.github.yuroyami.kitepdf.render.Matrix as PdfMatrix
import io.github.yuroyami.kitepdf.render.PdfCanvas
import io.github.yuroyami.kitepdf.render.PdfPath
import io.github.yuroyami.kitepdf.render.PdfShading
import io.github.yuroyami.kitepdf.render.RgbColor
import io.github.yuroyami.kitepdf.render.SoftMask
import io.github.yuroyami.kitepdf.render.sampleStops
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
import platform.CoreFoundation.CFDataCreate
import platform.CoreFoundation.CFDataRef
import platform.CoreFoundation.CFRelease
import platform.CoreGraphics.CGAffineTransformMake
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
import platform.ImageIO.CGImageSourceCreateImageAtIndex
import platform.ImageIO.CGImageSourceCreateWithData

/**
 * [PdfCanvas] backed by an iOS / macOS [CGContextRef]. Pure CoreGraphics —
 * no Compose, no Skia. The natural choice for native iOS apps using UIKit
 * or SwiftUI; pass the context from your custom UIView's `drawRect:` or
 * the `UIGraphicsImageRenderer.image { ctx in … }` block straight in.
 *
 * Pair with [ApplePdfRasterizer] for the "give me a PNG" headless use case.
 *
 * Memory: every Core Foundation / Core Graphics ref we allocate (gradient,
 * colour space, image, CFData) is paired with a release call. Nothing
 * escapes — Kotlin/Native objects don't leak, and we never hand a CG ref
 * to caller code.
 */
@OptIn(ExperimentalForeignApi::class)
class CoreGraphicsCanvas(private val ctx: CGContextRef) : PdfCanvas {

    private var openLayers = 0

    override fun beginPage(widthPt: Double, heightPt: Double, deviceCtm: PdfMatrix) {
        openLayers = 0
    }

    override fun endPage() {
        while (openLayers > 0) {
            CGContextRestoreGState(ctx)
            openLayers--
        }
    }

    override fun fillPath(
        path: PdfPath, ctm: PdfMatrix, color: RgbColor, evenOdd: Boolean,
        alpha: Double, blendMode: PdfBlendMode,
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
        path: PdfPath, ctm: PdfMatrix, color: RgbColor, lineWidth: Double,
        alpha: Double, blendMode: PdfBlendMode,
        dashArray: List<Double>?, dashPhase: Double,
        lineCap: Int, lineJoin: Int, miterLimit: Double,
    ) {
        CGContextSaveGState(ctx)
        try {
            CGContextSetBlendMode(ctx, blendMode.toCG())
            CGContextSetRGBStrokeColor(ctx, color.r, color.g, color.b, alpha)
            val avgScale = (ctm.scaleX() + ctm.scaleY()) * 0.5
            CGContextSetLineWidth(ctx, (lineWidth * avgScale).coerceAtLeast(0.1))
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
                    for (i in dashArray.indices) lengths[i] = (dashArray[i] * avgScale)
                    CGContextSetLineDash(ctx, dashPhase * avgScale, lengths, dashArray.size.toULong())
                }
            }
            buildPath(path, ctm)
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
        textToDevice: PdfMatrix,
        color: RgbColor,
        alpha: Double,
        blendMode: PdfBlendMode,
    ) {
        if (glyphs.isEmpty()) return
        if (!hasOutlines) return  // system-font fallback deferred

        val unitScale = fontSize / unitsPerEm  // glyph outlines: font units → text space
        val advanceScale = fontSize / 1000.0   // advances are 1/1000 em, not font units
        CGContextSaveGState(ctx)
        try {
            CGContextSetBlendMode(ctx, blendMode.toCG())
            CGContextSetRGBFillColor(ctx, color.r, color.g, color.b, alpha)
            var penX = 0.0
            for (glyph in glyphs) {
                val outline = glyph.outline
                if (outline != null && !outline.isEmpty()) {
                    val glyphMatrix = textToDevice
                        .let { tm -> PdfMatrix.translation(penX + glyph.xOffset * unitScale, glyph.yOffset * unitScale).concat(tm) }
                        .let { tm -> PdfMatrix(unitScale, 0.0, 0.0, unitScale, 0.0, 0.0).concat(tm) }
                    buildPath(outline, glyphMatrix)
                    CGContextFillPath(ctx)
                }
                penX += glyph.advanceWidth * advanceScale
            }
        } finally {
            CGContextRestoreGState(ctx)
        }
    }

    override fun fillShading(
        shading: PdfShading, ctm: PdfMatrix, clipPath: PdfPath?,
        alpha: Double, blendMode: PdfBlendMode,
    ) {
        val stops = shading.sampleStops(32) ?: return

        CGContextSaveGState(ctx)
        try {
            CGContextSetBlendMode(ctx, blendMode.toCG())

            // Clip to the fill region if requested.
            if (clipPath != null) {
                buildPath(clipPath, ctm)
                CGContextClip(ctx)
            }

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
                        val drawOpts: CGGradientDrawingOptions =
                            kCGGradientDrawsBeforeStartLocation or kCGGradientDrawsAfterEndLocation
                        when (shading) {
                            is PdfShading.Axial -> {
                                val (x0, y0) = ctm.transformPoint(shading.coords[0], shading.coords[1])
                                val (x1, y1) = ctm.transformPoint(shading.coords[2], shading.coords[3])
                                val start = cValue<CGPoint> { x = x0; y = y0 }
                                val end = cValue<CGPoint> { x = x1; y = y1 }
                                CGContextDrawLinearGradient(ctx, gradient, start, end, drawOpts)
                            }
                            is PdfShading.Radial -> {
                                // True PDF two-circle radial — Core Graphics takes both.
                                val sc = kotlin.math.sqrt(ctm.a * ctm.a + ctm.b * ctm.b)
                                val (x0, y0) = ctm.transformPoint(shading.coords[0], shading.coords[1])
                                val r0 = (shading.coords[2] * sc).coerceAtLeast(0.0)
                                val (x1, y1) = ctm.transformPoint(shading.coords[3], shading.coords[4])
                                val r1 = (shading.coords[5] * sc).coerceAtLeast(0.1)
                                val startC = cValue<CGPoint> { x = x0; y = y0 }
                                val endC = cValue<CGPoint> { x = x1; y = y1 }
                                CGContextDrawRadialGradient(
                                    ctx, gradient, startC, r0, endC, r1, drawOpts,
                                )
                            }
                            is PdfShading.Unsupported -> Unit
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

    override fun pushClip(path: PdfPath, ctm: PdfMatrix, evenOdd: Boolean) {
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

    override fun drawImage(image: ImageXObject, ctm: PdfMatrix, alpha: Double) {
        val cgImage = decodeImage(image)
        if (cgImage == null) {
            drawPlaceholder(ctm)
            return
        }
        try {
            CGContextSaveGState(ctx)
            try {
                // Apply CTM, then place the image in the unit square (0,-1)..(1,0)
                // — the device CTM has already flipped Y, so we flip back here.
                CGContextConcatCTM(ctx, ctm.toCGAffine())
                CGContextTranslateCTM(ctx, 0.0, -1.0)
                val rect = CGRectMake(0.0, 0.0, 1.0, 1.0)
                // CGContextSetAlpha doesn't compose with subsequent blends well;
                // we paint the image then use the global alpha through the
                // context's compositing alpha.
                CGContextDrawImage(ctx, rect, cgImage)
            } finally {
                CGContextRestoreGState(ctx)
            }
        } finally {
            CGImageRelease(cgImage)
        }
    }

    private fun decodeImage(image: ImageXObject): platform.CoreGraphics.CGImageRef? {
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

    private fun drawPlaceholder(ctm: PdfMatrix) {
        CGContextSaveGState(ctx)
        try {
            CGContextConcatCTM(ctx, ctm.toCGAffine())
            CGContextSetRGBFillColor(ctx, 0.88, 0.88, 0.88, 1.0)
            CGContextSetRGBStrokeColor(ctx, 0.53, 0.53, 0.53, 1.0)
            CGContextSetLineWidth(ctx, 0.01)
            val rect = CGRectMake(0.0, -1.0, 1.0, 1.0)
            platform.CoreGraphics.CGContextFillRect(ctx, rect)
            platform.CoreGraphics.CGContextStrokeRect(ctx, rect)
        } finally {
            CGContextRestoreGState(ctx)
        }
    }

    override fun beginTransparencyGroup(
        bbox: Rectangle, ctm: PdfMatrix,
        isolated: Boolean, knockout: Boolean,
        alpha: Double, blendMode: PdfBlendMode,
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
        maskBBox: Rectangle, maskCtm: PdfMatrix,
        render: () -> Unit,
        renderMask: (PdfCanvas) -> Unit,
    ) {
        CGContextSaveGState(ctx)
        CGContextBeginTransparencyLayer(ctx, null)
        try {
            render()
            CGContextSetBlendMode(ctx, CGBlendMode.kCGBlendModeDestinationIn)
            renderMask(this)
        } finally {
            CGContextEndTransparencyLayer(ctx)
            CGContextRestoreGState(ctx)
        }
    }

    /* ─── Helpers ─────────────────────────────────────────────────────────── */

    private fun buildPath(src: PdfPath, ctm: PdfMatrix) {
        CGContextBeginPath(ctx)
        for (seg in src.segments) {
            when (seg) {
                is PdfPath.Segment.MoveTo -> {
                    val (x, y) = ctm.transformPoint(seg.x, seg.y)
                    CGContextMoveToPoint(ctx, x, y)
                }
                is PdfPath.Segment.LineTo -> {
                    val (x, y) = ctm.transformPoint(seg.x, seg.y)
                    CGContextAddLineToPoint(ctx, x, y)
                }
                is PdfPath.Segment.CurveTo -> {
                    val (x1, y1) = ctm.transformPoint(seg.x1, seg.y1)
                    val (x2, y2) = ctm.transformPoint(seg.x2, seg.y2)
                    val (x3, y3) = ctm.transformPoint(seg.x3, seg.y3)
                    CGContextAddCurveToPoint(ctx, x1, y1, x2, y2, x3, y3)
                }
                is PdfPath.Segment.QuadTo -> {
                    val (x1, y1) = ctm.transformPoint(seg.x1, seg.y1)
                    val (x2, y2) = ctm.transformPoint(seg.x2, seg.y2)
                    CGContextAddQuadCurveToPoint(ctx, x1, y1, x2, y2)
                }
                PdfPath.Segment.Close -> CGContextClosePath(ctx)
            }
        }
    }

    private fun PdfMatrix.toCGAffine(): CValue<platform.CoreGraphics.CGAffineTransform> =
        CGAffineTransformMake(a, b, c, d, e, f)

    private fun PdfBlendMode.toCG(): CGBlendMode = when (this) {
        PdfBlendMode.Normal -> CGBlendMode.kCGBlendModeNormal
        PdfBlendMode.Multiply -> CGBlendMode.kCGBlendModeMultiply
        PdfBlendMode.Screen -> CGBlendMode.kCGBlendModeScreen
        PdfBlendMode.Overlay -> CGBlendMode.kCGBlendModeOverlay
        PdfBlendMode.Darken -> CGBlendMode.kCGBlendModeDarken
        PdfBlendMode.Lighten -> CGBlendMode.kCGBlendModeLighten
        PdfBlendMode.ColorDodge -> CGBlendMode.kCGBlendModeColorDodge
        PdfBlendMode.ColorBurn -> CGBlendMode.kCGBlendModeColorBurn
        PdfBlendMode.HardLight -> CGBlendMode.kCGBlendModeHardLight
        PdfBlendMode.SoftLight -> CGBlendMode.kCGBlendModeSoftLight
        PdfBlendMode.Difference -> CGBlendMode.kCGBlendModeDifference
        PdfBlendMode.Exclusion -> CGBlendMode.kCGBlendModeExclusion
        PdfBlendMode.Hue -> CGBlendMode.kCGBlendModeHue
        PdfBlendMode.Saturation -> CGBlendMode.kCGBlendModeSaturation
        PdfBlendMode.Color -> CGBlendMode.kCGBlendModeColor
        PdfBlendMode.Luminosity -> CGBlendMode.kCGBlendModeLuminosity
    }

    private companion object {
        val IMAGE_KINDS_DECODABLE_BY_CG = setOf(
            ImageXObject.Kind.JPEG,
            ImageXObject.Kind.JPEG2000,
        )
    }
}

@OptIn(ExperimentalForeignApi::class)
private fun ByteArray.toCFData(): CFDataRef? = usePinned { pinned ->
    CFDataCreate(null, pinned.addressOf(0).reinterpret(), size.toLong())
}

@OptIn(ExperimentalForeignApi::class)
private typealias DoubleVar = kotlinx.cinterop.DoubleVar
