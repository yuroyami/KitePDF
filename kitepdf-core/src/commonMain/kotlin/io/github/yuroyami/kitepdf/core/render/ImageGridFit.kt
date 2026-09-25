package io.github.yuroyami.kitepdf.core.render

import kotlin.math.abs
import kotlin.math.truncate

/**
 * [ctm], which maps an image's unit square to device pixels, with the image edges moved
 * outwards onto whole pixels. The image then covers every pixel it touches, and two
 * images that meet leave no seam between them. A canvas that draws into pixels applies
 * it just before it draws an image, image mask or image clip.
 *
 * ISO 32000-1 does not say how an image edge meets the pixel grid, so MuPDF decides:
 * this is `fz_gridfit_matrix` for an image that is not tiled. It applies to an image
 * turned by a multiple of 90 degrees, flipped or not. Any other matrix comes back
 * unchanged (#300).
 */
public fun gridFitImage(ctm: KiteMatrix): KiteMatrix {
    var (a, b, c, d) = listOf(ctm.a, ctm.b, ctm.c, ctm.d)
    var e = ctm.e
    var f = ctm.f
    when {
        abs(b) < FLOAT_EPSILON && abs(c) < FLOAT_EPSILON -> {
            fitAxis(a, e).let { (scale, origin) -> a = scale; e = origin }
            fitAxis(d, f).let { (scale, origin) -> d = scale; f = origin }
        }
        abs(a) < FLOAT_EPSILON && abs(d) < FLOAT_EPSILON -> {
            fitAxis(b, f).let { (scale, origin) -> b = scale; f = origin }
            fitAxis(c, e).let { (scale, origin) -> c = scale; e = origin }
        }
        else -> return ctm
    }
    return KiteMatrix(a, b, c, d, e, f)
}

/** The FLT_EPSILON that MuPDF tests a matrix entry against. */
private const val FLOAT_EPSILON = 1.1920929e-7

/** The slack that MuPDF allows before it moves an edge by a whole pixel. */
private const val EDGE_EPSILON = 0.001

/**
 * One axis of [gridFitImage]: the edge at [origin] and the edge at [origin] plus [scale],
 * each moved outwards onto a whole pixel. Returns the new scale and origin.
 */
private fun fitAxis(scale: Double, origin: Double): Pair<Double, Double> = when {
    scale > 0 -> {
        val low = moveDown(origin)
        val size = moveUp(scale + origin - low)
        size to low
    }
    scale < 0 -> {
        val high = moveUp(origin)
        val size = moveDown(scale + origin - high)
        size to high
    }
    else -> scale to origin
}

/** [v] onto the whole number at or below it, as MuPDF truncates and then corrects. */
private fun moveDown(v: Double): Double {
    val t = truncate(v)
    return if (t - v > EDGE_EPSILON) t - 1.0 else t
}

/** [v] onto the whole number at or above it, as MuPDF truncates and then corrects. */
private fun moveUp(v: Double): Double {
    val t = truncate(v)
    return if (v - t > EDGE_EPSILON) t + 1.0 else t
}
