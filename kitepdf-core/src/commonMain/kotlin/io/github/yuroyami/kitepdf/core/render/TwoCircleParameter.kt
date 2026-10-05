package io.github.yuroyami.kitepdf.core.render

import kotlin.math.abs
import kotlin.math.sqrt

/**
 * The parameter s of a gradient between the circles ([x0], [y0], [r0]) and ([x1], [y1], [r1])
 * at the point ([x], [y]), all in shading space, for a canvas whose platform has no such
 * gradient: Android before API 31 (#413, #591). It is the largest s whose circle, centred at
 * c0 + s (c1 - c0) with a radius r0 + s (r1 - r0) of at least 0, passes through the point, as a
 * platform gradient picks it (ISO 32000-1, 8.7.4.5.4). Null when no such circle passes through
 * the point.
 */
public fun twoCircleParameter(
    x: Double, y: Double,
    x0: Double, y0: Double, r0: Double,
    x1: Double, y1: Double, r1: Double,
): Double? {
    val dx = x1 - x0
    val dy = y1 - y0
    val dr = r1 - r0
    val px = x - x0
    val py = y - y0
    // The point lies on the circle of s when |p - c(s)| = r(s), which is
    // a s^2 - 2 b s + c = 0 with the terms below.
    val a = dx * dx + dy * dy - dr * dr
    val b = px * dx + py * dy + r0 * dr
    val c = px * px + py * py - r0 * r0
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
