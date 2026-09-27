package io.github.yuroyami.kitepdf.compose

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import io.github.yuroyami.kitepdf.core.render.KiteMatrix
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * The pixels of a gradient between two circles, for a platform that has no such gradient:
 * Android before API 31 (#413). The image is [width] by [height] pixels, and its pixel
 * (0, 0) starts at the device point ([left], [top]). One pixel spans [pixelSize] device
 * units. [toShading] maps a device point back to shading space, where the circles are.
 *
 * Each pixel takes the colour at the largest s whose circle passes through the pixel with a
 * radius of at least 0, as the platform gradient does (ISO 32000-1, 8.7.4.5.4). An s before
 * 0 or after 1 takes the colour of the first or the last of [stops]. A pixel that no circle
 * passes through stays transparent.
 */
internal fun twoCircleImage(
    x0: Double, y0: Double, r0: Double,
    x1: Double, y1: Double, r1: Double,
    stops: Array<Pair<Float, Color>>,
    toShading: KiteMatrix,
    left: Double, top: Double, width: Int, height: Int, pixelSize: Double = 1.0,
): ImageBitmap? {
    if (width <= 0 || height <= 0 || stops.isEmpty()) return null
    val dx = x1 - x0
    val dy = y1 - y0
    val dr = r1 - r0
    // The point p lies on the circle of s when |p - c(s)| = r(s), which is
    // a s^2 - 2 b s + c = 0 with the terms below.
    val a = dx * dx + dy * dy - dr * dr
    val rgba = ByteArray(width * height * 4)
    for (row in 0 until height) {
        val deviceY = top + (row + 0.5) * pixelSize
        for (col in 0 until width) {
            val deviceX = left + (col + 0.5) * pixelSize
            val px = toShading.transformX(deviceX, deviceY) - x0
            val py = toShading.transformY(deviceX, deviceY) - y0
            val b = px * dx + py * dy + r0 * dr
            val c = px * px + py * py - r0 * r0
            val s = largestParameter(a, b, c, r0, dr) ?: continue
            val color = colorAt(stops, s)
            val i = (row * width + col) * 4
            rgba[i] = channel(color.red)
            rgba[i + 1] = channel(color.green)
            rgba[i + 2] = channel(color.blue)
            rgba[i + 3] = channel(color.alpha)
        }
    }
    return ImageDecoder.decodeRaw(rgba, width, height)
}

/** The most pixels a [twoCircleImage] for a canvas has: 4 MB of pixels, a small share of a phone's heap. */
internal const val TWO_CIRCLE_MAX_PIXELS: Double = 1_048_576.0

/** The largest root of a s^2 - 2 b s + c = 0 whose radius r0 + s dr is at least 0, or null. */
private fun largestParameter(a: Double, b: Double, c: Double, r0: Double, dr: Double): Double? {
    fun fits(s: Double) = s.isFinite() && r0 + s * dr >= 0.0
    if (abs(a) < 1e-9) {
        // The circles grow along a line as fast as they move: one root.
        if (b == 0.0) return null
        return (c / (2 * b)).takeIf(::fits)
    }
    val discriminant = b * b - a * c
    if (discriminant < 0.0) return null
    val root = sqrt(discriminant)
    val high = maxOf((b + root) / a, (b - root) / a)
    val low = minOf((b + root) / a, (b - root) / a)
    return when {
        fits(high) -> high
        fits(low) -> low
        else -> null
    }
}

/** The colour of [stops] at [s], each end held past it. At an offset two stops share, the later one wins. */
private fun colorAt(stops: Array<Pair<Float, Color>>, s: Double): Color {
    val t = s.toFloat()
    if (t <= stops.first().first) return stops.first().second
    if (t >= stops.last().first) return stops.last().second
    for (i in 1 until stops.size) {
        val (end, to) = stops[i]
        if (t < end) {
            val (start, from) = stops[i - 1]
            val f = if (end > start) (t - start) / (end - start) else 1f
            return Color(
                from.red + (to.red - from.red) * f,
                from.green + (to.green - from.green) * f,
                from.blue + (to.blue - from.blue) * f,
                from.alpha + (to.alpha - from.alpha) * f,
            )
        }
    }
    return stops.last().second
}

private fun channel(value: Float): Byte = (value.coerceIn(0f, 1f) * 255f).roundToInt().toByte()
