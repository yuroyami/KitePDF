package io.github.yuroyami.kitepdf.compose

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Paint

/**
 * Opens a layer over [bounds] that starts as a copy of what lies under it and composites
 * with [paint] when it is restored. Returns false, and opens nothing, where the platform
 * cannot copy the backdrop into a layer.
 */
internal expect fun saveLayerOverBackdrop(canvas: Canvas, bounds: Rect, paint: Paint): Boolean
