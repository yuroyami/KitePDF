package io.github.yuroyami.kitepdf.core.render

import kotlin.math.roundToInt

/**
 * Default allocation ceiling shared by every rasterizing surface in KitePDF:
 * 40 MP already expands to 160 MiB of RGBA, so refuse more before allocating.
 */
public const val KITE_DEFAULT_MAX_RASTER_PIXELS: Long = 40_000_000L

/** Keep the soft-mask remap acceleration from becoming a second huge bitmap. */
private const val MAX_MASK_COLUMN_MAP: Int = 1_000_000

/**
 * Assemble a [Kind.RAW][KiteImageData.Kind.RAW] image's already-decoded samples
 * into a flat RGBA8888 buffer (R,G,B,A per pixel, row-major, no padding) that a
 * platform backend can wrap in a bitmap.
 *
 * Drives every sample through the image's resolved [KiteColorSpace], so it covers:
 *   - DeviceGray / DeviceRGB / DeviceCMYK (process-CMYK polynomial)
 *   - Indexed (palette lookup) at any bit depth
 *   - ICCBased / CalGray / CalRGB (device-equivalent fallback)
 *   - 1/2/4/8/16 bits per component, with `/Decode` remapping
 *   - `/ImageMask` stencils tinted by the current fill colour
 *   - `/SMask` and `/Mask` transparency, in both of `/Mask`'s forms
 *
 * Returns null only for things genuinely undecodable here (no pixel data, a
 * colour space that couldn't be resolved, a truncated buffer) → the caller
 * paints a placeholder.
 */
public fun KiteImageData.toRgbaBytes(): ByteArray? {
    val w = width
    val h = height
    if (w <= 0 || h <= 0) return null
    val pixelCountLong = w.toLong() * h.toLong()
    if (pixelCountLong > KITE_DEFAULT_MAX_RASTER_PIXELS) return null
    val pixelCount = pixelCountLong.toInt()

    if (isImageMask) return rasterizeImageMask(w, h)

    val src = pixelBytes ?: return null
    val cs = resolvedColorSpace ?: inferDeviceSpace(src, pixelCount) ?: return null
    val bpc = bitsPerComponent
    when (bpc) {
        1, 2, 4, 8, 16 -> Unit
        else -> return null
    }
    val sourceComponents = if (cs is KiteColorSpace.Indexed) 1 else cs.componentCount
    val sourceRowBytes = packedRowBytes(w, sourceComponents, bpc) ?: return null
    if (src.size.toLong() < sourceRowBytes.toLong() * h) return null
    val out = ByteArray(pixelCount * 4)
    val opaque = 0xFF.toByte()

    when {
        // Fast paths for the overwhelmingly common 8-bit device cases (no /Decode).
        decode == null && bpc == 8 && cs === KiteColorSpace.DeviceGray -> {
            val rowBytes = w
            if (src.size.toLong() < rowBytes.toLong() * h) return null
            var i = 0; var o = 0
            repeat(pixelCount) {
                val g = src[i++]; out[o++] = g; out[o++] = g; out[o++] = g; out[o++] = opaque
            }
        }
        decode == null && bpc == 8 && cs === KiteColorSpace.DeviceRGB -> {
            val rowBytes = packedRowBytes(w, 3, 8) ?: return null
            if (src.size.toLong() < rowBytes.toLong() * h) return null
            var i = 0; var o = 0
            repeat(pixelCount) {
                out[o++] = src[i++]; out[o++] = src[i++]; out[o++] = src[i++]; out[o++] = opaque
            }
        }
        cs is KiteColorSpace.Indexed -> {
            if (!unpackIndexed(src, w, h, bpc, cs, out)) return null
        }
        // An 8-bit grey space, a curve and matrix space and DeviceRGB with /Decode run
        // from tables, so a photo in an ICC or spot space costs a few lookups per pixel
        // instead of a full conversion (#72). DeviceRGB without /Decode took a fast path above.
        bpc == 8 && cs.componentCount == 1 -> unpackGrayTable(src, pixelCount, cs, out)
        bpc == 8 && cs.componentCount == 3 && cs.curveMatrix != null -> unpackCurveMatrix(src, pixelCount, cs, out)
        bpc == 8 && cs === KiteColorSpace.DeviceRGB -> unpackRgbDecode(src, pixelCount, out)
        else -> {
            if (!unpackGeneral(src, w, h, bpc, cs, out)) return null
        }
    }
    // The two are mutually exclusive by construction: an image resolves either
    // a colour-key /Mask or an alpha plane (/SMask, or a stencil /Mask), never both.
    applyColorKeyMask(out, cs)
    applySoftMaskAlpha(out)
    unblendMatte(out)
    return out
}

/**
 * ISO 32000-1, 11.6.5.3: a sample preblended with the matte colour m holds
 * c' = m + a * (c - m), so c = m + (c' - m) / a. The blend is undone on the RGB
 * result, as pdf.js does. A fully transparent pixel keeps its samples.
 */
private fun KiteImageData.unblendMatte(rgba: ByteArray) {
    val matte = softMaskMatte ?: return
    if (softMaskAlpha == null) return
    val m = doubleArrayOf(matte.r * 255.0, matte.g * 255.0, matte.b * 255.0)
    var i = 0
    while (i + 3 < rgba.size) {
        val a = rgba[i + 3].toInt() and 0xFF
        if (a in 1..254) {
            for (c in 0..2) {
                val v = rgba[i + c].toInt() and 0xFF
                rgba[i + c] = (m[c] + (v - m[c]) * 255.0 / a).roundToInt().coerceIn(0, 255).toByte()
            }
        }
        i += 4
    }
}

/** Indexed: each sample is a palette index (no normalisation). Any bit depth. */
private fun KiteImageData.unpackIndexed(
    src: ByteArray, w: Int, h: Int, bpc: Int, cs: KiteColorSpace.Indexed, out: ByteArray,
): Boolean {
    val rowBytes = packedRowBytes(w, 1, bpc) ?: return false
    if (src.size.toLong() < rowBytes.toLong() * h) return false
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
private fun KiteImageData.unpackGeneral(
    src: ByteArray, w: Int, h: Int, bpc: Int, cs: KiteColorSpace, out: ByteArray,
): Boolean {
    val comps = cs.componentCount
    if (comps <= 0) return false
    val rowBytes = packedRowBytes(w, comps, bpc) ?: return false
    if (src.size.toLong() < rowBytes.toLong() * h) return false
    val maxval = ((1 shl bpc) - 1).toDouble()
    val compBuf = DoubleArray(comps)
    val range = sampleRanges(cs, comps)
    val opaque = 0xFF.toByte()
    var o = 0
    for (y in 0 until h) {
        var bit = y.toLong() * rowBytes * 8
        for (x in 0 until w) {
            for (c in 0 until comps) {
                val sample = readBits(src, bit, bpc); bit += bpc
                compBuf[c] = range[2 * c] + sample * (range[2 * c + 1] - range[2 * c]) / maxval
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
 * Each component's value at the lowest and the highest sample, as pairs: the
 * `/Decode` entry, else the space's own range. ISO 32000-1, Table 90: that is 0
 * to 100 for Lab lightness (#76).
 */
private fun KiteImageData.sampleRanges(cs: KiteColorSpace, comps: Int): DoubleArray {
    val dec = decode
    return DoubleArray(2 * comps) { i ->
        val c = i / 2
        when {
            dec != null && dec.size >= 2 * (c + 1) -> dec[i]
            i % 2 == 0 -> cs.componentMin(c)
            else -> cs.componentMax(c)
        }
    }
}

/** An 8-bit one-component image through a 256-entry table of the space's own colours. */
private fun KiteImageData.unpackGrayTable(src: ByteArray, pixelCount: Int, cs: KiteColorSpace, out: ByteArray) {
    val range = sampleRanges(cs, 1)
    val table = ByteArray(256 * 3)
    val comp = DoubleArray(1)
    for (s in 0..255) {
        comp[0] = range[0] + s * (range[1] - range[0]) / 255.0
        val rgb = cs.toRgb(comp)
        table[3 * s] = (rgb.r * 255.0).roundToInt().toByte()
        table[3 * s + 1] = (rgb.g * 255.0).roundToInt().toByte()
        table[3 * s + 2] = (rgb.b * 255.0).roundToInt().toByte()
    }
    var o = 0
    for (i in 0 until pixelCount) {
        val t = 3 * (src[i].toInt() and 0xFF)
        out[o++] = table[t]; out[o++] = table[t + 1]; out[o++] = table[t + 2]; out[o++] = 0xFF.toByte()
    }
}

/**
 * An 8-bit image in a curve and matrix space, such as an ICC matrix profile: a
 * table per tone curve, the matrix, then a table of the sRGB encoding. The result
 * matches the full conversion to within one level.
 */
private fun KiteImageData.unpackCurveMatrix(src: ByteArray, pixelCount: Int, cs: KiteColorSpace, out: ByteArray) {
    val model = cs.curveMatrix ?: return
    val range = sampleRanges(cs, 3)
    val lin = Array(3) { c ->
        FloatArray(256) { v -> model.curves[c](range[2 * c] + v * (range[2 * c + 1] - range[2 * c]) / 255.0).toFloat() }
    }
    val m = FloatArray(9) { model.toLinearSrgb[it].toFloat() }
    val encode = SRGB_ENCODE
    val top = (SRGB_ENCODE_STEPS - 1).toFloat()
    var p = 0
    var o = 0
    repeat(pixelCount) {
        val r = lin[0][src[p].toInt() and 0xFF]
        val g = lin[1][src[p + 1].toInt() and 0xFF]
        val b = lin[2][src[p + 2].toInt() and 0xFF]
        p += 3
        for (k in 0 until 3) {
            val v = (m[3 * k] * r + m[3 * k + 1] * g + m[3 * k + 2] * b).coerceIn(0f, 1f)
            out[o++] = encode[(v * top + 0.5f).toInt()]
        }
        out[o++] = 0xFF.toByte()
    }
}

/** An 8-bit DeviceRGB image with a `/Decode` array: one table per component. */
private fun KiteImageData.unpackRgbDecode(src: ByteArray, pixelCount: Int, out: ByteArray) {
    val range = sampleRanges(KiteColorSpace.DeviceRGB, 3)
    val table = Array(3) { c ->
        ByteArray(256) { v -> ((range[2 * c] + v * (range[2 * c + 1] - range[2 * c]) / 255.0).coerceIn(0.0, 1.0) * 255.0).roundToInt().toByte() }
    }
    var p = 0
    var o = 0
    repeat(pixelCount) {
        out[o++] = table[0][src[p++].toInt() and 0xFF]
        out[o++] = table[1][src[p++].toInt() and 0xFF]
        out[o++] = table[2][src[p++].toInt() and 0xFF]
        out[o++] = 0xFF.toByte()
    }
}

/** Linear steps of the sRGB encoding table: fine enough that the steep start stays within a level. */
private const val SRGB_ENCODE_STEPS = 65536

/** The sRGB encoding of linear value i / (steps - 1), as a byte. */
private val SRGB_ENCODE: ByteArray by lazy {
    ByteArray(SRGB_ENCODE_STEPS) { (srgbEncode(it / (SRGB_ENCODE_STEPS - 1).toDouble()) * 255.0).roundToInt().toByte() }
}

/**
 * Render an `/ImageMask` stencil: a 1-bpc bitmap where (with the default
 * `/Decode [0 1]`) a 0 sample paints the current fill colour and a 1 sample is
 * transparent. `/Decode [1 0]` inverts the sense.
 */
private fun KiteImageData.rasterizeImageMask(w: Int, h: Int): ByteArray? {
    val src = pixelBytes ?: return null
    val rowBytes = packedRowBytes(w, 1, 1) ?: return null
    if (src.size.toLong() < rowBytes.toLong() * h) return null
    val fill = maskFill ?: RgbColor.BLACK
    val fr = (fill.r * 255.0).roundToInt().toByte()
    val fg = (fill.g * 255.0).roundToInt().toByte()
    val fb = (fill.b * 255.0).roundToInt().toByte()
    val invert = decode != null && decode.size >= 2 && decode[0] == 1.0
    val out = ByteArray((w.toLong() * h * 4L).toInt())
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
private fun KiteImageData.inferDeviceSpace(src: ByteArray, pixelCount: Int): KiteColorSpace? {
    if (bitsPerComponent != 8 || pixelCount == 0) return null
    return when (src.size / pixelCount) {
        1 -> KiteColorSpace.DeviceGray
        3 -> KiteColorSpace.DeviceRGB
        4 -> KiteColorSpace.DeviceCMYK
        else -> null
    }
}

/**
 * Colour-key masking (ISO 32000-1 §8.9.6): clear the alpha of every pixel
 * whose components all fall inside their `/Mask` range. The comparison is made
 * on the SOURCE samples, before `/Decode` and colour conversion, as the spec
 * requires, so this re-reads [KiteImageData.pixelBytes] rather than judging the
 * assembled RGB.
 *
 * A range list that does not match the image's component count (a JPEG the
 * decoder handed back in another colour space, a malformed array) is ignored,
 * leaving the image opaque.
 */
private fun KiteImageData.applyColorKeyMask(rgba: ByteArray, cs: KiteColorSpace) {
    val ranges = colorKeyMask ?: return
    val src = pixelBytes ?: return
    val comps = cs.componentCount
    if (ranges.size != 2 * comps) return
    val bpc = bitsPerComponent
    val rowBytes = packedRowBytes(width, comps, bpc) ?: return
    if (src.size < rowBytes.toLong() * height) return
    var a = 3
    for (y in 0 until height) {
        var bit = y.toLong() * rowBytes * 8
        for (x in 0 until width) {
            var inside = true
            for (c in 0 until comps) {
                val sample = readBits(src, bit, bpc); bit += bpc
                if (sample < ranges[2 * c] || sample > ranges[2 * c + 1]) inside = false
            }
            if (inside) rgba[a] = 0
            a += 4
        }
    }
}

/**
 * Overwrite the alpha channel of an assembled RGBA buffer with the image's
 * soft-mask, if present. The mask is sampled with nearest-neighbour when its
 * dimensions differ from the image's (PDF permits a different-resolution mask).
 * Carries a stencil `/Mask` too, which arrives as the same plane. No-op when
 * the image has neither, leaving every pixel opaque.
 */
private fun KiteImageData.applySoftMaskAlpha(rgba: ByteArray) {
    val mask = softMaskAlpha ?: return
    val mw = softMaskWidth
    val mh = softMaskHeight
    if (mw <= 0 || mh <= 0) return
    val maskPixels = mw.toLong() * mh.toLong()
    if (maskPixels > Int.MAX_VALUE.toLong()) return
    var a = 3
    // Common case: mask matches image resolution. Walk it linearly, with no
    // per-pixel integer divides for the (mx, my) remap.
    if (mw == width && mh == height) {
        val n = (width.toLong() * height).toInt()
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
    val colMap = if (width <= MAX_MASK_COLUMN_MAP) {
        IntArray(width) { x -> (x.toLong() * mw / width).toInt() }
    } else {
        null
    }
    for (y in 0 until height) {
        val my = (y.toLong() * mh / height).toInt()
        val rowBase = my.toLong() * mw
        for (x in 0 until width) {
            val mx = colMap?.get(x) ?: (x.toLong() * mw / width).toInt()
            val idx = rowBase + mx
            rgba[a] = if (idx < mask.size.toLong()) mask[idx.toInt()] else 0xFF.toByte()
            a += 4
        }
    }
}

private fun packedRowBytes(width: Int, components: Int, bitsPerComponent: Int): Int? {
    if (width <= 0 || components <= 0 || bitsPerComponent <= 0) return null
    val samples = width.toLong() * components.toLong()
    if (samples > (Long.MAX_VALUE - 7L) / bitsPerComponent) return null
    val bits = samples * bitsPerComponent
    val bytes = (bits + 7L) / 8L
    return if (bytes <= Int.MAX_VALUE.toLong()) bytes.toInt() else null
}
