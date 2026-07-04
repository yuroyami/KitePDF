package io.github.yuroyami.kitepdf.render

import kotlin.math.roundToInt

/**
 * Assemble a [Kind.RAW][ImageXObject.Kind.RAW] image's already-decoded samples
 * into a flat RGBA8888 buffer (R,G,B,A per pixel, row-major, no padding) that a
 * platform backend can wrap in a bitmap.
 *
 * Drives every sample through the image's resolved [ColorSpace], so it covers:
 *   - DeviceGray / DeviceRGB / DeviceCMYK (process-CMYK polynomial)
 *   - Indexed (palette lookup) at any bit depth
 *   - ICCBased / CalGray / CalRGB (device-equivalent fallback)
 *   - 1/2/4/8/16 bits per component, with `/Decode` remapping
 *   - `/ImageMask` stencils tinted by the current fill colour
 *
 * Returns null only for things genuinely undecodable here (no pixel data, a
 * colour space that couldn't be resolved, a truncated buffer) → the caller
 * paints a placeholder.
 */
fun ImageXObject.toRgbaBytes(): ByteArray? {
    val w = width
    val h = height
    if (w <= 0 || h <= 0) return null

    if (isImageMask) return rasterizeImageMask(w, h)

    val src = pixelBytes ?: return null
    val pixelCount = w * h
    val cs = resolvedColorSpace ?: inferDeviceSpace(src, pixelCount) ?: return null
    val bpc = bitsPerComponent
    val out = ByteArray(pixelCount * 4)
    val opaque = 0xFF.toByte()

    when {
        // Fast paths for the overwhelmingly common 8-bit device cases (no /Decode).
        decode == null && bpc == 8 && cs === ColorSpace.DeviceGray -> {
            val rowBytes = w
            if (src.size < rowBytes * h) return null
            var i = 0; var o = 0
            repeat(pixelCount) {
                val g = src[i++]; out[o++] = g; out[o++] = g; out[o++] = g; out[o++] = opaque
            }
        }
        decode == null && bpc == 8 && cs === ColorSpace.DeviceRGB -> {
            val rowBytes = w * 3
            if (src.size < rowBytes * h) return null
            var i = 0; var o = 0
            repeat(pixelCount) {
                out[o++] = src[i++]; out[o++] = src[i++]; out[o++] = src[i++]; out[o++] = opaque
            }
        }
        cs is ColorSpace.Indexed -> {
            if (!unpackIndexed(src, w, h, bpc, cs, out)) return null
        }
        else -> {
            if (!unpackGeneral(src, w, h, bpc, cs, out)) return null
        }
    }
    applySoftMaskAlpha(out)
    return out
}

/** Indexed: each sample is a palette index (no normalisation). Any bit depth. */
private fun ImageXObject.unpackIndexed(
    src: ByteArray, w: Int, h: Int, bpc: Int, cs: ColorSpace.Indexed, out: ByteArray,
): Boolean {
    val rowBytes = (w * bpc + 7) / 8
    if (src.size < rowBytes * h) return false
    val opaque = 0xFF.toByte()
    // /Decode for Indexed remaps the sample range to an index range; default is
    // [0, 2^bpc - 1] which is the identity (sample IS the index).
    val maxval = (1 shl bpc) - 1
    val dmin = decode?.getOrNull(0)
    val dmax = decode?.getOrNull(1)
    var o = 0
    for (y in 0 until h) {
        var bit = y.toLong() * rowBytes * 8
        for (x in 0 until w) {
            val sample = readBits(src, bit, bpc); bit += bpc
            val index = if (dmin != null && dmax != null)
                (dmin + sample.toDouble() * (dmax - dmin) / maxval).roundToInt()
            else sample
            val rgb = cs.colorAt(index)
            out[o++] = (rgb.r * 255.0).roundToInt().toByte()
            out[o++] = (rgb.g * 255.0).roundToInt().toByte()
            out[o++] = (rgb.b * 255.0).roundToInt().toByte()
            out[o++] = opaque
        }
    }
    return true
}

/** General path: normalise each component to [0,1], apply /Decode, then toRgb. */
private fun ImageXObject.unpackGeneral(
    src: ByteArray, w: Int, h: Int, bpc: Int, cs: ColorSpace, out: ByteArray,
): Boolean {
    val comps = cs.componentCount
    val rowBytes = (w * comps * bpc + 7) / 8
    if (src.size < rowBytes * h) return false
    val maxval = ((1 shl bpc) - 1).toDouble()
    val dec = decode
    val compBuf = DoubleArray(comps)
    val opaque = 0xFF.toByte()
    var o = 0
    for (y in 0 until h) {
        var bit = y.toLong() * rowBytes * 8
        for (x in 0 until w) {
            for (c in 0 until comps) {
                val sample = readBits(src, bit, bpc); bit += bpc
                compBuf[c] = if (dec != null && dec.size >= 2 * (c + 1)) {
                    val dmin = dec[2 * c]; val dmax = dec[2 * c + 1]
                    dmin + sample * (dmax - dmin) / maxval
                } else {
                    sample / maxval
                }
            }
            val rgb = cs.toRgb(compBuf)
            out[o++] = (rgb.r * 255.0).roundToInt().toByte()
            out[o++] = (rgb.g * 255.0).roundToInt().toByte()
            out[o++] = (rgb.b * 255.0).roundToInt().toByte()
            out[o++] = opaque
        }
    }
    return true
}

/**
 * Render an `/ImageMask` stencil: a 1-bpc bitmap where (with the default
 * `/Decode [0 1]`) a 0 sample paints the current fill colour and a 1 sample is
 * transparent. `/Decode [1 0]` inverts the sense.
 */
private fun ImageXObject.rasterizeImageMask(w: Int, h: Int): ByteArray? {
    val src = pixelBytes ?: return null
    val rowBytes = (w + 7) / 8
    if (src.size < rowBytes * h) return null
    val fill = maskFill ?: RgbColor.BLACK
    val fr = (fill.r * 255.0).roundToInt().toByte()
    val fg = (fill.g * 255.0).roundToInt().toByte()
    val fb = (fill.b * 255.0).roundToInt().toByte()
    val invert = decode != null && decode.size >= 2 && decode[0] == 1.0
    val out = ByteArray(w * h * 4)
    var o = 0
    for (y in 0 until h) {
        val rowStart = y * rowBytes
        for (x in 0 until w) {
            val bit = (src[rowStart + (x ushr 3)].toInt() shr (7 - (x and 7))) and 1
            val paint = if (invert) bit == 1 else bit == 0
            if (paint) {
                out[o++] = fr; out[o++] = fg; out[o++] = fb; out[o++] = 0xFF.toByte()
            } else {
                out[o++] = 0; out[o++] = 0; out[o++] = 0; out[o++] = 0
            }
        }
    }
    return out
}

/** Read [count] bits (1..16) MSB-first starting at absolute bit position [bitPos]. */
private fun readBits(data: ByteArray, bitPos: Long, count: Int): Int {
    var v = 0
    var p = bitPos
    repeat(count) {
        val byteIdx = (p ushr 3).toInt()
        val shift = 7 - (p and 7).toInt()
        val b = if (byteIdx < data.size) (data[byteIdx].toInt() shr shift) and 1 else 0
        v = (v shl 1) or b
        p++
    }
    return v
}

/** Infer a device colour space from the bytes-per-pixel when none was resolved. */
private fun ImageXObject.inferDeviceSpace(src: ByteArray, pixelCount: Int): ColorSpace? {
    if (bitsPerComponent != 8 || pixelCount == 0) return null
    return when (src.size / pixelCount) {
        1 -> ColorSpace.DeviceGray
        3 -> ColorSpace.DeviceRGB
        4 -> ColorSpace.DeviceCMYK
        else -> null
    }
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
