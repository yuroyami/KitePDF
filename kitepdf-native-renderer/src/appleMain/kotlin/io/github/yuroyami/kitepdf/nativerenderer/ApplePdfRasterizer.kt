package io.github.yuroyami.kitepdf.nativerenderer

import io.github.yuroyami.kitepdf.PdfPage
import io.github.yuroyami.kitepdf.render.Matrix as PdfMatrix
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.useContents
import kotlinx.cinterop.value
import platform.CoreFoundation.CFArrayCreateMutable
import platform.CoreFoundation.CFDictionaryCreateMutable
import platform.CoreFoundation.CFRelease
import platform.CoreGraphics.CGBitmapContextCreate
import platform.CoreGraphics.CGBitmapContextCreateImage
import platform.CoreGraphics.CGColorSpaceCreateDeviceRGB
import platform.CoreGraphics.CGColorSpaceRelease
import platform.CoreGraphics.CGContextClearRect
import platform.CoreGraphics.CGContextRef
import platform.CoreGraphics.CGContextRelease
import platform.CoreGraphics.CGContextSetRGBFillColor
import platform.CoreGraphics.CGContextFillRect
import platform.CoreGraphics.CGImageAlphaInfo
import platform.CoreGraphics.CGImageRelease
import platform.CoreGraphics.CGRectMake
import platform.Foundation.CFBridgingRelease
import platform.Foundation.NSData
import platform.Foundation.NSMutableData
import platform.ImageIO.CGImageDestinationAddImage
import platform.ImageIO.CGImageDestinationCreateWithData
import platform.ImageIO.CGImageDestinationFinalize
import platform.UniformTypeIdentifiers.UTTypePNG

/**
 * Headless rendering on Apple platforms (iOS, macOS, tvOS) — produces a
 * PNG-encoded [NSData] sized to the page in pt × [scale].
 * Pure CoreGraphics + ImageIO, no UIKit/AppKit, no Compose dependency.
 *
 * Typical uses:
 *
 *  - Pre-rendering thumbnails on disk (`writeToFile:atomically:`)
 *  - Generating sharing previews / extension thumbnails
 *  - CI snapshots of PDF rendering
 *
 * For drawing into a UIView's `drawRect:` (or an NSView) directly,
 * instantiate [CoreGraphicsCanvas] over the current `CGContext` instead —
 * the rasterizer is for off-screen output.
 */
@OptIn(ExperimentalForeignApi::class)
object ApplePdfRasterizer {

    /**
     * Render a page into a PNG-encoded NSData. Returns null if the
     * underlying CGBitmapContext or PNG encoder can't be created (very
     * rare; would indicate a system-level resource failure).
     */
    fun renderToPngData(
        page: PdfPage,
        scale: Double = 1.0,
        backgroundR: Double = 1.0,
        backgroundG: Double = 1.0,
        backgroundB: Double = 1.0,
        backgroundA: Double = 1.0,
    ): NSData? {
        val widthPx = (page.width * scale).toULong().coerceAtLeast(1uL)
        val heightPx = (page.height * scale).toULong().coerceAtLeast(1uL)
        val space = CGColorSpaceCreateDeviceRGB() ?: return null
        try {
            // ARGB32 premultiplied — matches what UIKit / AppKit hand out.
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
                // Y-flip so PDF user-space matches the rendered image orientation.
                val deviceCtm = PdfMatrix(scale, 0.0, 0.0, -scale, 0.0, page.height * scale)
                val canvas = CoreGraphicsCanvas(cgContext)
                page.renderTo(canvas, deviceCtm)

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
        val data = NSMutableData()
        @Suppress("CAST_NEVER_SUCCEEDS")
        val dest = CGImageDestinationCreateWithData(
            data as platform.CoreFoundation.CFMutableDataRef,
            UTTypePNG.identifier as platform.CoreFoundation.CFStringRef,
            1uL,
            null,
        ) ?: return null
        try {
            CGImageDestinationAddImage(dest, image, null)
            if (!CGImageDestinationFinalize(dest)) return null
        } finally {
            CFRelease(dest)
        }
        return data
    }
}

/** Old name from 0.0.2, when this only shipped for iOS. */
@Deprecated("Renamed to ApplePdfRasterizer (now also macOS/tvOS)", ReplaceWith("ApplePdfRasterizer"))
typealias IosPdfRasterizer = ApplePdfRasterizer
