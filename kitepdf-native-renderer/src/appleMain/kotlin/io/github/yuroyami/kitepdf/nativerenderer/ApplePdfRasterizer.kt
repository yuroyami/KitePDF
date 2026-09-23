package io.github.yuroyami.kitepdf.nativerenderer

import io.github.yuroyami.kitepdf.PdfPage
import io.github.yuroyami.kitepdf.core.render.KITE_DEFAULT_MAX_RASTER_PIXELS
import io.github.yuroyami.kitepdf.rasterGeometry
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.useContents
import kotlinx.cinterop.value
import platform.CoreFoundation.CFArrayCreateMutable
import platform.CoreFoundation.CFDataCreateMutable
import platform.CoreFoundation.CFStringCreateWithCString
import platform.CoreFoundation.kCFStringEncodingUTF8
import platform.CoreFoundation.CFDictionaryCreateMutable
import platform.CoreFoundation.CFRelease
import platform.CoreGraphics.CGBitmapContextCreate
import platform.CoreGraphics.CGBitmapContextCreateImage
import platform.CoreGraphics.CGColorSpaceCreateDeviceRGB
import platform.CoreGraphics.CGColorSpaceRelease
import platform.CoreGraphics.CGContextClearRect
import platform.CoreGraphics.CGContextRef
import platform.CoreGraphics.CGContextRelease
import platform.CoreGraphics.CGContextScaleCTM
import platform.CoreGraphics.CGContextTranslateCTM
import platform.CoreGraphics.CGContextSetRGBFillColor
import platform.CoreGraphics.CGContextFillRect
import platform.CoreGraphics.CGImageAlphaInfo
import platform.CoreGraphics.CGImageRelease
import platform.CoreGraphics.CGRectMake
import platform.Foundation.CFBridgingRelease
import platform.Foundation.NSData
import platform.ImageIO.CGImageDestinationAddImage
import platform.ImageIO.CGImageDestinationCreateWithData
import platform.ImageIO.CGImageDestinationFinalize

/**
 * Headless rendering on Apple platforms (iOS, macOS, tvOS). Produces a
 * PNG-encoded [NSData] sized by [PdfPage.rasterGeometry] (rotation, crop box
 * and `/UserUnit` all included), at [scale] device pixels per point.
 * Pure CoreGraphics + ImageIO, no UIKit/AppKit, no Compose dependency.
 *
 * Typical uses:
 *
 *  - Pre-rendering thumbnails on disk (`writeToFile:atomically:`)
 *  - Generating sharing previews / extension thumbnails
 *  - CI snapshots of PDF rendering
 *
 * For drawing into a UIView's `drawRect:` (or an NSView) directly,
 * instantiate [CoreGraphicsCanvas] over the current `CGContext` instead.
 * The rasterizer is for off-screen output.
 */
@OptIn(ExperimentalForeignApi::class)
public object ApplePdfRasterizer {

    /**
     * Render a page into a PNG-encoded NSData. Returns null if the
     * underlying CGBitmapContext or PNG encoder can't be created (very
     * rare; would indicate a system-level resource failure).
     */
    public fun renderToPngData(
        page: PdfPage,
        scale: Double = 1.0,
        backgroundR: Double = 1.0,
        backgroundG: Double = 1.0,
        backgroundB: Double = 1.0,
        backgroundA: Double = 1.0,
    ): NSData? = renderToPngData(
        page, scale, backgroundR, backgroundG, backgroundB, backgroundA,
        KITE_DEFAULT_MAX_RASTER_PIXELS,
    )

    /** [renderToPngData] with an explicit allocation ceiling. */
    public fun renderToPngData(
        page: PdfPage,
        scale: Double = 1.0,
        backgroundR: Double = 1.0,
        backgroundG: Double = 1.0,
        backgroundB: Double = 1.0,
        backgroundA: Double = 1.0,
        maxPixels: Long,
    ): NSData? {
        val geometry = page.rasterGeometry(scale, maxPixels)
        val widthPx = geometry.widthPx.toULong()
        val heightPx = geometry.heightPx.toULong()
        val space = CGColorSpaceCreateDeviceRGB() ?: return null
        try {
            // ARGB32 premultiplied, matching the format UIKit and AppKit use.
            val bytesPerRow = widthPx.toLong() * 4
            val bitmapInfo = CGImageAlphaInfo.kCGImageAlphaPremultipliedFirst.value
            val cgContext: CGContextRef = CGBitmapContextCreate(
                data = null,
                width = widthPx,
                height = heightPx,
                bitsPerComponent = 8uL,
                bytesPerRow = bytesPerRow.toULong(),
                space = space,
                bitmapInfo = bitmapInfo,
            ) ?: return null
            try {
                // Optionally fill background.
                if (backgroundA > 0) {
                    val rect = CGRectMake(0.0, 0.0, widthPx.toDouble(), heightPx.toDouble())
                    CGContextSetRGBFillColor(cgContext, backgroundR, backgroundG, backgroundB, backgroundA)
                    CGContextFillRect(cgContext, rect)
                }
                // The device matrix of the geometry is y-down and a bitmap context is y-up.
                // Flip the context so that the first row of the PNG is the top of the page (#288).
                CGContextTranslateCTM(cgContext, 0.0, heightPx.toDouble())
                CGContextScaleCTM(cgContext, 1.0, -1.0)
                val canvas = CoreGraphicsCanvas(cgContext)
                page.renderTo(canvas, geometry.deviceCtm)

                val image = CGBitmapContextCreateImage(cgContext) ?: return null
                try {
                    return encodeToPng(image)
                } finally {
                    CGImageRelease(image)
                }
            } finally {
                CGContextRelease(cgContext)
            }
        } finally {
            CGColorSpaceRelease(space)
        }
    }

    private fun encodeToPng(image: platform.CoreGraphics.CGImageRef): NSData? {
        // Core Foundation objects, not casts: Kotlin/Native checks a cast from an
        // Objective-C object to a C pointer at runtime, and it throws (#288).
        val data = CFDataCreateMutable(null, 0) ?: return null
        var encoded = false
        try {
            val type = CFStringCreateWithCString(null, "public.png", kCFStringEncodingUTF8) ?: return null
            val dest = try {
                CGImageDestinationCreateWithData(data, type, 1uL, null)
            } finally {
                CFRelease(type)
            } ?: return null
            try {
                CGImageDestinationAddImage(dest, image, null)
                encoded = CGImageDestinationFinalize(dest)
            } finally {
                CFRelease(dest)
            }
        } finally {
            if (!encoded) CFRelease(data)
        }
        if (!encoded) return null
        // The NSData takes over the reference to the bytes.
        return CFBridgingRelease(data) as NSData
    }
}
