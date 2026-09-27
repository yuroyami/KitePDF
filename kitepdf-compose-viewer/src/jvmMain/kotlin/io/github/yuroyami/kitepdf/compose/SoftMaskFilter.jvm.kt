package io.github.yuroyami.kitepdf.compose

import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.asComposeColorFilter
import androidx.compose.ui.graphics.asSkiaColorFilter
import io.github.yuroyami.kitepdf.core.render.KiteMaskTransfer
import io.github.yuroyami.kitepdf.core.render.SoftMask

/** Skia takes the whole transfer table, as the Skia renderer gives it, where a line fit bent every curve (#414). */
internal actual fun softMaskFilter(kind: SoftMask.Kind, transfer: KiteMaskTransfer?): ColorFilter? {
    val table = transfer?.let { org.jetbrains.skia.ColorFilter.makeTableARGB(it.toByteArray(), null, null, null) }
    if (kind == SoftMask.Kind.Alpha) return table?.asComposeColorFilter()
    val luminosity = ColorFilter.colorMatrix(ColorMatrix(LUMINOSITY_TO_ALPHA))
    if (table == null) return luminosity
    return org.jetbrains.skia.ColorFilter.makeComposed(table, luminosity.asSkiaColorFilter()).asComposeColorFilter()
}

internal actual val maskTableFilters: Boolean = true
