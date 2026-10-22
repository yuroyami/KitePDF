package io.github.yuroyami.kitepdf.compose

import androidx.compose.ui.graphics.ColorFilter
import io.github.yuroyami.kitepdf.core.render.KiteMaskTransfer
import io.github.yuroyami.kitepdf.core.render.SoftMask

/** Android has no table colour filter, so the transfer applies as the straight line closest to its table. */
internal actual fun softMaskFilter(kind: SoftMask.Kind, transfer: KiteMaskTransfer?): ColorFilter? = softMaskMatrix(kind, transfer)

internal actual val maskTableFilters: Boolean = false
