package io.github.yuroyami.kitepdf.compose

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shader
import androidx.compose.ui.graphics.asComposeShader
import org.jetbrains.skia.Color4f
import org.jetbrains.skia.FilterTileMode
import org.jetbrains.skia.Gradient

internal actual fun twoCircleGradient(
    x0: Float, y0: Float, r0: Float,
    x1: Float, y1: Float, r1: Float,
    colors: List<Color>, offsets: List<Float>,
): Shader? = org.jetbrains.skia.Shader.makeTwoPointConicalGradient(
    x0, y0, r0, x1, y1, r1,
    Gradient(
        Gradient.Colors(
            Array(colors.size) { Color4f(colors[it].red, colors[it].green, colors[it].blue, colors[it].alpha) },
            offsets.toFloatArray(),
            FilterTileMode.CLAMP,
        ),
    ),
).asComposeShader()
