package io.github.yuroyami.kitepdf.compose

import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import io.github.yuroyami.kitepdf.core.render.KiteMaskTransfer
import io.github.yuroyami.kitepdf.core.render.SoftMask

/**
 * The colour filter that turns a soft mask group into its mask values (ISO 32000-1, 11.6.5.2):
 * the group's luminosity or its alpha, mapped by [transfer]. Null when the group's own alpha is
 * the mask as it is.
 */
internal expect fun softMaskFilter(kind: SoftMask.Kind, transfer: KiteMaskTransfer?): ColorFilter?

/**
 * True where [softMaskFilter] applies a transfer function by its whole table. Where it is false,
 * the canvas gates a mask whose transfer is not nearly linear by its pixels (#445).
 */
internal expect val maskTableFilters: Boolean

/** The most pixels the image of a mask group has when a canvas gates by pixels: 4 MB of them, a small share of a phone's heap. */
internal const val MASK_MAX_PIXELS: Double = 1_048_576.0

/** A colour matrix that moves the luminosity of a colour, 0.299 R + 0.587 G + 0.114 B, into its alpha, and clears its colour. */
internal val LUMINOSITY_TO_ALPHA: FloatArray = floatArrayOf(
    0f, 0f, 0f, 0f, 0f,
    0f, 0f, 0f, 0f, 0f,
    0f, 0f, 0f, 0f, 0f,
    0.299f, 0.587f, 0.114f, 0f, 0f,
)

/**
 * [softMaskFilter] as one colour matrix, for a backend with no table filter. A matrix can only
 * scale and offset, so [transfer] applies as the straight line closest to its table: exact for a
 * linear function such as an inverter.
 */
internal fun softMaskMatrix(kind: SoftMask.Kind, transfer: KiteMaskTransfer?): ColorFilter? {
    if (kind == SoftMask.Kind.Alpha && transfer == null) return null
    // The mask value then becomes slope * value + offset. The offset of a Compose colour matrix
    // is in levels from 0 to 255.
    val slope = (transfer?.slope ?: 1.0).toFloat()
    val offset = ((transfer?.offset ?: 0.0) * 255).toFloat()
    val matrix = if (kind == SoftMask.Kind.Luminosity) {
        LUMINOSITY_TO_ALPHA.copyOf().also { m ->
            for (i in 15..17) m[i] *= slope
            m[19] = offset
        }
    } else {
        floatArrayOf(
            1f, 0f, 0f, 0f, 0f,
            0f, 1f, 0f, 0f, 0f,
            0f, 0f, 1f, 0f, 0f,
            0f, 0f, 0f, slope, offset,
        )
    }
    return ColorFilter.colorMatrix(ColorMatrix(matrix))
}
