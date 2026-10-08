package io.github.yuroyami.kitepdf.core.render

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToInt

/**
 * Finish a reduction with a separable, non-ringing cubic filter. Its support is
 * one destination pixel on either side of the centre, so every contributing source
 * pixel counts even between the power-of-two steps (ISO 32000-1, 8.9.5.3, #626).
 * Only the horizontally filtered rows needed by the current output row are kept.
 */
internal fun resampleRgba(
    rgba: ByteArray, width: Int, height: Int, outWidth: Int, outHeight: Int, alphaWeighted: Boolean,
): ByteArray {
    if (width == outWidth && height == outHeight) return rgba
    val columns = filterWeights(width, outWidth)
    val rows = filterWeights(height, outHeight)
    val out = ByteArray(outWidth * outHeight * 4)
    val filtered = HashMap<Int, FloatArray>()
    val sums = FloatArray(outWidth * 4)
    for (y in 0 until outHeight) {
        val vertical = rows[y]
        filtered.keys.removeAll { it < vertical.first }
        sums.fill(0f)
        for (j in vertical.values.indices) {
            val sourceY = vertical.first + j
            val row = filtered.getOrPut(sourceY) {
                val line = FloatArray(outWidth * 4)
                for (x in 0 until outWidth) {
                    val horizontal = columns[x]
                    val target = x * 4
                    for (i in horizontal.values.indices) {
                        val source = (sourceY * width + horizontal.first + i) * 4
                        val alpha = (rgba[source + 3].toInt() and 255).toFloat()
                        val weight = horizontal.values[i]
                        val colourWeight = if (alphaWeighted) weight * alpha / 255f else weight
                        for (c in 0..2) line[target + c] += (rgba[source + c].toInt() and 255) * colourWeight
                        line[target + 3] += alpha * weight
                    }
                }
                line
            }
            val weight = vertical.values[j]
            for (i in sums.indices) sums[i] += row[i] * weight
        }
        for (x in 0 until outWidth) {
            val p = x * 4
            val target = (y * outWidth + x) * 4
            val alpha = sums[p + 3]
            val unpremultiply = if (!alphaWeighted) 1f else if (alpha > 0f) 255f / alpha else 0f
            for (c in 0..2) out[target + c] = (sums[p + c] * unpremultiply).roundToInt().coerceIn(0, 255).toByte()
            out[target + 3] = alpha.roundToInt().coerceIn(0, 255).toByte()
        }
    }
    return out
}

private class ImageFilterWeights(val first: Int, val values: FloatArray)

/** Normalised weights preserve a constant colour, including at the image edges. */
private fun filterWeights(source: Int, destination: Int): Array<ImageFilterWeights> {
    val scale = destination.toDouble() / source
    return Array(destination) { pixel ->
        val centre = (pixel + 0.5) / scale - 0.5
        val first = ceil(centre - 1.0 / scale).toInt().coerceAtLeast(0)
        val last = floor(centre + 1.0 / scale).toInt().coerceAtMost(source - 1)
        val weights = FloatArray(last - first + 1) { i ->
            val distance = abs((first + i - centre) * scale).coerceAtMost(1.0)
            // The cubic smoothstep falls from one at the centre to zero at the edge.
            (1.0 + (2.0 * distance - 3.0) * distance * distance).toFloat()
        }
        val sum = weights.sum()
        for (i in weights.indices) weights[i] /= sum
        ImageFilterWeights(first, weights)
    }
}
