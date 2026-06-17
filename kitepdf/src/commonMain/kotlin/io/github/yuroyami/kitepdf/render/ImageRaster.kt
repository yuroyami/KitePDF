package io.github.yuroyami.kitepdf.render

/**
 * Assemble a [Kind.RAW][ImageXObject.Kind.RAW] image's already-inflated samples
 * into a flat RGBA8888 buffer (R,G,B,A per pixel, row-major, no padding) that a
 * platform backend can wrap in a bitmap.
 *
 * Handles the cases that cover the overwhelming majority of real-world images:
 *   - 8-bit DeviceRGB / DeviceGray / DeviceCMYK
 *   - 1-bit DeviceGray (bitmask / scanned images)
 *   - unknown colour spaces (ICCBased, CalRGB…) at 8-bit: component count is
 *     inferred from the buffer size (1/3/4 → Gray/RGB/CMYK)
 *
 * Returns null for things we can't assemble yet (Indexed needs the palette,
 * 16-bit, exotic component counts) → the caller paints a placeholder.
 */
fun ImageXObject.toRgbaBytes(): ByteArray? {
    val src = pixelBytes ?: return null
    val w = width
    val h = height
    if (w <= 0 || h <= 0) return null
    val pixelCount = w * h

    // Component count per pixel.
    val comps = when (colorSpace) {
        "DeviceRGB", "RGB", "CalRGB" -> 3
        "DeviceGray", "G", "CalGray" -> 1
        "DeviceCMYK", "CMYK" -> 4
        "Indexed", "I" -> return null // needs the colour lookup table
        else -> {
            // ICCBased and friends: infer from how many bytes-per-pixel we got.
            if (bitsPerComponent != 8 || pixelCount == 0) return null
            when (src.size / pixelCount) {
                1 -> 1
                3 -> 3
                4 -> 4
                else -> return null
            }
        }
    }

    val out = ByteArray(pixelCount * 4)
    val opaque = 0xFF.toByte()
    var o = 0

    when {
        bitsPerComponent == 8 -> {
            val rowBytes = w * comps
            if (src.size < rowBytes * h) return null
            // Hoist the (loop-invariant) component-count branch out of the
            // per-pixel loop — three specialized, branch-free passes. Samples are
            // contiguous row-major, so one running index covers all rows.
            var i = 0
            when (comps) {
                1 -> repeat(pixelCount) {
                    val g = src[i++]
                    out[o++] = g; out[o++] = g; out[o++] = g; out[o++] = opaque
                }
                3 -> repeat(pixelCount) {
                    out[o++] = src[i++]; out[o++] = src[i++]; out[o++] = src[i++]; out[o++] = opaque
                }
                else -> repeat(pixelCount) { // 4 = CMYK → naïve subtractive RGB
                    val c = src[i++].toInt() and 0xFF
                    val m = src[i++].toInt() and 0xFF
                    val yc = src[i++].toInt() and 0xFF
                    val k = src[i++].toInt() and 0xFF
                    out[o++] = ((255 - c) * (255 - k) / 255).toByte()
                    out[o++] = ((255 - m) * (255 - k) / 255).toByte()
                    out[o++] = ((255 - yc) * (255 - k) / 255).toByte()
                    out[o++] = opaque
                }
            }
        }
        bitsPerComponent == 1 && comps == 1 -> {
            val rowBytes = (w + 7) / 8 // rows are byte-aligned
            if (src.size < rowBytes * h) return null
            for (y in 0 until h) {
                val rowStart = y * rowBytes
                for (x in 0 until w) {
                    val bit = (src[rowStart + (x ushr 3)].toInt() shr (7 - (x and 7))) and 1
                    val g = if (bit == 1) opaque else 0x00.toByte()
                    out[o++] = g; out[o++] = g; out[o++] = g; out[o++] = opaque
                }
            }
        }
        else -> return null
    }
    applySoftMaskAlpha(out)
    return out
}

/**
 * Overwrite the alpha channel of an assembled RGBA buffer with the image's
 * soft-mask, if present. The mask is sampled with nearest-neighbour when its
 * dimensions differ from the image's (PDF permits a different-resolution mask).
 * No-op when the image has no `/SMask`, leaving every pixel opaque.
 */
private fun ImageXObject.applySoftMaskAlpha(rgba: ByteArray) {
    val mask = softMaskAlpha ?: return
    val mw = softMaskWidth
    val mh = softMaskHeight
    if (mw <= 0 || mh <= 0) return
    var a = 3
    // Common case: mask matches image resolution — walk it linearly, no per-pixel
    // integer divides for the (mx, my) remap.
    if (mw == width && mh == height) {
        val n = width * height
        var m = 0
        while (m < n) {
            rgba[a] = if (m < mask.size) mask[m] else 0xFF.toByte()
            a += 4; m++
        }
        return
    }
    // Mismatched resolution: precompute the per-column source index once (exact
    // same values as x*mw/width) so the inner loop does an array read instead of
    // an integer divide on every pixel.
    val colMap = IntArray(width) { x -> x * mw / width }
    for (y in 0 until height) {
        val my = y * mh / height
        val rowBase = my * mw
        for (x in 0 until width) {
            val idx = rowBase + colMap[x]
            rgba[a] = if (idx < mask.size) mask[idx] else 0xFF.toByte()
            a += 4
        }
    }
}
