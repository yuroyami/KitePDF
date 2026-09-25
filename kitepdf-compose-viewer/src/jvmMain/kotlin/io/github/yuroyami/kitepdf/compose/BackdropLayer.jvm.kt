package io.github.yuroyami.kitepdf.compose

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.skiaCanvas
import androidx.compose.ui.graphics.skiaPaint

internal actual fun saveLayerOverBackdrop(canvas: Canvas, bounds: Rect, paint: Paint): Boolean {
    canvas.skiaCanvas.saveLayer(
        org.jetbrains.skia.Canvas.SaveLayerRec(
            org.jetbrains.skia.Rect.makeLTRB(bounds.left, bounds.top, bounds.right, bounds.bottom),
            paint.skiaPaint, null, null,
            org.jetbrains.skia.Canvas.SaveLayerFlags(org.jetbrains.skia.Canvas.SaveLayerFlagsSet.InitWithPrevious),
        ),
    )
    return true
}
