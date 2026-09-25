package io.github.yuroyami.kitepdf.compose

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Paint

// An Android canvas has no layer that starts from the pixels under it.
internal actual fun saveLayerOverBackdrop(canvas: Canvas, bounds: Rect, paint: Paint): Boolean = false
