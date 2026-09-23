package io.github.yuroyami.kitepdf.compose

import android.graphics.RadialGradient
import android.os.Build
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shader
import androidx.compose.ui.graphics.toArgb

internal actual fun twoCircleGradient(
    x0: Float, y0: Float, r0: Float,
    x1: Float, y1: Float, r1: Float,
    colors: List<Color>, offsets: List<Float>,
): Shader? {
    // Android draws a gradient between two circles from API 31 on.
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return null
    return RadialGradient(
        x0, y0, r0, x1, y1, r1,
        LongArray(colors.size) { android.graphics.Color.pack(colors[it].toArgb()) },
        offsets.toFloatArray(),
        android.graphics.Shader.TileMode.CLAMP,
    )
}
