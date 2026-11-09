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

/** The most pixels a raster step works on before it drops to a lower resolution: 64 MB of ARGB. */
internal const val STEP_MAX_PIXELS: Double = 16_777_216.0

/**
 * About the bytes a raster step holds at once for each of its pixels: the backdrop, two renders,
 * each a bitmap and its copy read back, an RGBA copy of the backdrop, the group without its
 * backdrop and the RGBA copy drawn back. 36, as measured with an A4 page (#789).
 */
internal const val STEP_BYTES_PER_PIXEL: Long = 36L

/**
 * The most bytes the buffers of one raster step take: a quarter of the heap where the platform
 * tells its size, so a 192 MB heap gives 48 MB, about 1.3 million pixels. A larger step declines,
 * and the group paints as a layer, as where the canvas cannot read the backdrop (#789).
 */
internal fun stepMaxBytes(): Long = stepBudgetForTest ?: platformStepMaxBytes()

/** A budget a test sets in place of the platform's. */
internal var stepBudgetForTest: Long? = null

internal expect fun platformStepMaxBytes(): Long

/**
 * A colour matrix that moves the luminosity of a colour into its alpha, with the weights of [kind]:
 * 0.30 R + 0.59 G + 0.11 B for a PDF mask, as every other canvas computes it, and the CSS weights
 * for an SVG mask (#799, #821). It clears the colour.
 */
internal fun luminosityToAlpha(kind: SoftMask.Kind): FloatArray = floatArrayOf(
    0f, 0f, 0f, 0f, 0f,
    0f, 0f, 0f, 0f, 0f,
    0f, 0f, 0f, 0f, 0f,
    kind.red.toFloat(), kind.green.toFloat(), kind.blue.toFloat(), 0f, 0f,
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
    val matrix = if (kind.readsColor) {
        luminosityToAlpha(kind).also { m ->
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
