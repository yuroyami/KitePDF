package io.github.yuroyami.kitepdf.compose

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import io.github.yuroyami.kitepdf.core.render.KiteMatrix
import io.github.yuroyami.kitepdf.core.render.twoCircleParameter
import kotlin.math.roundToInt

/**
 * The pixels of a gradient between two circles, for a platform that has no such gradient:
 * Android before API 31 (#413). The image is [width] by [height] pixels, and its pixel
 * (0, 0) starts at the device point ([left], [top]). One pixel spans [pixelSize] device
 * units. [toShading] maps a device point back to shading space, where the circles are.
 *
 * Each pixel takes the colour at the [twoCircleParameter] of its centre. An s before 0 or after
 * 1 takes the colour of the first or the last of [stops]. A pixel that no circle passes through
 * stays transparent.
 */
internal fun twoCircleImage(
    x0: Double, y0: Double, r0: Double,
    x1: Double, y1: Double, r1: Double,
    stops: Array<Pair<Float, Color>>,
    toShading: KiteMatrix,
    left: Double, top: Double, width: Int, height: Int, pixelSize: Double = 1.0,
): ImageBitmap? {
    if (width <= 0 || height <= 0 || stops.isEmpty()) return null
    val rgba = ByteArray(width * height * 4)
    for (row in 0 until height) {
        val deviceY = top + (row + 0.5) * pixelSize
        for (col in 0 until width) {
            val deviceX = left + (col + 0.5) * pixelSize
            val x = toShading.transformX(deviceX, deviceY)
            val y = toShading.transformY(deviceX, deviceY)
            val s = twoCircleParameter(x, y, x0, y0, r0, x1, y1, r1) ?: continue
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
