package io.github.yuroyami.kitepdf.compose

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shader

/**
 * A gradient between two circles that clamps past both ends: offset 0 lies on the
 * circle at ([x0], [y0]) with radius [r0], and offset 1 on the circle at ([x1], [y1])
 * with radius [r1]. Null where the platform has no such gradient.
 */
internal expect fun twoCircleGradient(
    x0: Float, y0: Float, r0: Float,
    x1: Float, y1: Float, r1: Float,
    colors: List<Color>, offsets: List<Float>,
): Shader?
