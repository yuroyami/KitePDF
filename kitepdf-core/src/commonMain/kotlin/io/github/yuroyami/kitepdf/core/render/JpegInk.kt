package io.github.yuroyami.kitepdf.core.render

import io.github.yuroyami.kiteimagecodec.KiteImageCodec

/**
 * The CMYK samples of a four-component JPEG in a PDF (#470). DCTDecode returns CMYK, either as
 * stored or converted back from YCCK (ISO 32000-1, 7.4.8, Table 13), and a PDF stores normal ink.
 * KiteImageCodec returns only RGB, and it reads the ink as a standalone Photoshop file stores it:
 * inverted, with K multiplied in. That RGB cannot give the samples back.
 *
 * So each component decodes on its own. A copy of the file puts the component first in the frame
 * header and points the other three at a quantization table of zeros. Those three then decode flat
 * at 128, and a JPEG decoder turns a luma under flat chroma into equal R, G and B, which is the
 * component unchanged. That costs four decodes for one image.
 */
internal object JpegInk {

    /** Where [decode] rewrites the headers of a four-component JPEG. */
    class Layout(
        /** The offset of the first component in the frame header. */
        val frame: Int,
        /** The quantization table of each component, from the frame header. */
        val tables: IntArray,
        /** The offset of the table byte of each quantization table the file defines. */
        val quantization: IntArray,
        /** The offset of the transform byte of each Adobe marker. */
        val adobe: IntArray,
        /** True when the components hold YCbCr and K, which [decode] turns into CMYK. */
        val ycck: Boolean,
    )

    /**
     * The layout of [jpeg], or null when it is not a four-component JPEG with headers that read.
     * [colorTransform] is the `/ColorTransform` of the filter. An Adobe marker overrides it, as in
     * MuPDF, and without either a four-component JPEG holds CMYK (ISO 32000-1, Table 13).
     */
    fun layout(jpeg: ByteArray, colorTransform: Int?): Layout? {
        if (jpeg.size < 4 || u8(jpeg, 0) != 0xFF || u8(jpeg, 1) != 0xD8) return null
        var frame = -1
        val quantization = ArrayList<Int>()
        val adobe = ArrayList<Int>()
        var transform = -1
        var i = 2
        while (i + 1 < jpeg.size) {
            if (u8(jpeg, i) != 0xFF) return null
            var marker = u8(jpeg, i + 1)
            i += 2
            while (marker == 0xFF && i < jpeg.size) marker = u8(jpeg, i++) // fill bytes
            if (marker == 0xD9) break
            if (marker == 0x01 || marker == 0xD8 || marker in 0xD0..0xD7) continue // no length
            if (i + 1 >= jpeg.size) return null
            val length = (u8(jpeg, i) shl 8) or u8(jpeg, i + 1)
            if (length < 2 || i + length > jpeg.size) return null
            when (marker) {
                0xDB -> {
                    var p = i + 2
                    while (p < i + length) {
                        quantization += p
                        p += if ((u8(jpeg, p) shr 4) != 0) 129 else 65
                    }
                }
                0xC0, 0xC1, 0xC2 -> {
                    if (frame >= 0) return null
                    if (length < 20 || u8(jpeg, i + 7) != 4) return null
                    frame = i + 8
                }
                0xEE -> if (length >= 14 && isAdobe(jpeg, i + 2)) {
                    // libjpeg reads the transform from byte 11 of the payload, and the last marker wins.
                    adobe += i + 13
                    transform = u8(jpeg, i + 13)
                }
                0xDA -> {
                    if (frame < 0) return null
                    i = scanEnd(jpeg, i + length)
                    continue
                }
                else -> if (marker in 0xC3..0xCF && marker != 0xC4 && marker != 0xC8 && marker != 0xCC) return null
            }
            i += length
        }
        if (frame < 0) return null
        val tables = IntArray(4) { u8(jpeg, frame + 3 * it + 2) }
        if (tables.any { it > 3 }) return null
        val ycck = (if (adobe.isEmpty()) colorTransform ?: 0 else transform) != 0
        return Layout(frame, tables, quantization.toIntArray(), adobe.toIntArray(), ycck)
    }

    /**
     * The CMYK samples of [jpeg], four bytes a pixel, with each side divided by [reduction]: 1, 2,
     * 4 or 8. Null when a component does not decode.
     */
    fun decode(jpeg: ByteArray, layout: Layout, reduction: Int): KiteImageSamples.Decoded? {
        var out: ByteArray? = null
        var width = 0
        var height = 0
        for (index in 0 until 4) {
            val bitmap = runCatching { KiteImageCodec.decodeReduced(component(jpeg, layout, index), reduction) }.getOrNull() ?: return null
            val samples = out ?: ByteArray(bitmap.width * bitmap.height * 4).also {
                out = it
                width = bitmap.width
                height = bitmap.height
            }
            if (bitmap.width != width || bitmap.height != height) return null
            val argb = bitmap.argb
            var o = index
            for (p in argb) {
                samples[o] = p.toByte() // blue, equal to red and green
                o += 4
            }
        }
        val samples = out ?: return null
        if (layout.ycck) ycckToCmyk(samples)
        return KiteImageSamples.Decoded(width, height, samples)
    }

    /** True when the first component of [jpeg] decodes, at an eighth of its size. */
    fun decodes(jpeg: ByteArray, layout: Layout): Boolean =
        runCatching { KiteImageCodec.decodeReduced(component(jpeg, layout, 0), 8) }.isSuccess

    /** A copy of [jpeg] that decodes to [index], one of its four components, as R, G and B. */
    private fun component(jpeg: ByteArray, layout: Layout, index: Int): ByteArray {
        val own = layout.tables[index]
        // A table no header of the file defines, when there is one, else any other than the component's own.
        val defined = BooleanArray(4)
        for (q in layout.quantization) (u8(jpeg, q) and 15).takeIf { it < 4 }?.let { defined[it] = true }
        val flat = (0 until 4).firstOrNull { it != own && !defined[it] } ?: (0 until 4).first { it != own }
        // The table of zeros goes first, so every component that uses it decodes flat at 128.
        val out = ByteArray(jpeg.size + ZERO_TABLE)
        out[0] = 0xFF.toByte()
        out[1] = 0xD8.toByte()
        out[2] = 0xFF.toByte()
        out[3] = 0xDB.toByte()
        out[5] = (ZERO_TABLE - 2).toByte()
        out[6] = flat.toByte()
        jpeg.copyInto(out, 2 + ZERO_TABLE, 2)
        val shift = ZERO_TABLE
        // A later definition of that table would undo the zeros, so it is zeroed too.
        for (q in layout.quantization) {
            if ((u8(jpeg, q) and 15) != flat) continue
            val size = if ((u8(jpeg, q) shr 4) != 0) 128 else 64
            out.fill(0, q + shift + 1, q + shift + 1 + size)
        }
        // The component moves to the front of the frame. The scans find each component by its id.
        var slot = 1
        for (k in 0 until 4) {
            val from = layout.frame + 3 * k
            val to = layout.frame + shift + 3 * (if (k == index) 0 else slot++)
            out[to] = jpeg[from]
            out[to + 1] = jpeg[from + 1]
            out[to + 2] = if (k == index) jpeg[from + 2] else flat.toByte()
        }
        // Transform 1 makes KiteImageCodec read the first three components as YCbCr, and leave K out.
        for (a in layout.adobe) out[a + shift] = 1
        return out
    }

    /**
     * YCCK to CMYK in place: YCbCr to RGB, then each of R, G and B inverted, with K as is. These are
     * the fixed-point tables of libjpeg (jdcolor.c), which MuPDF decodes with.
     */
    private fun ycckToCmyk(samples: ByteArray) {
        var i = 0
        while (i + 3 < samples.size) {
            val y = samples[i].toInt() and 0xFF
            val cb = (samples[i + 1].toInt() and 0xFF) - 128
            val cr = (samples[i + 2].toInt() and 0xFF) - 128
            val r = y + ((91881 * cr + HALF) shr 16)
            val g = y + ((-22554 * cb - 46802 * cr + HALF) shr 16)
            val b = y + ((116130 * cb + HALF) shr 16)
            samples[i] = (255 - r).coerceIn(0, 255).toByte()
            samples[i + 1] = (255 - g).coerceIn(0, 255).toByte()
            samples[i + 2] = (255 - b).coerceIn(0, 255).toByte()
            i += 4
        }
    }

    /** The offset after the entropy-coded data that starts at [start]: its ending marker, or the end. */
    private fun scanEnd(jpeg: ByteArray, start: Int): Int {
        var i = start
        while (i + 1 < jpeg.size) {
            if (u8(jpeg, i) == 0xFF) {
                val next = u8(jpeg, i + 1)
                if (next != 0 && next != 0xFF && next !in 0xD0..0xD7) return i
            }
            i++
        }
        return jpeg.size
    }

    private fun isAdobe(jpeg: ByteArray, at: Int): Boolean =
        u8(jpeg, at) == 'A'.code && u8(jpeg, at + 1) == 'd'.code && u8(jpeg, at + 2) == 'o'.code &&
            u8(jpeg, at + 3) == 'b'.code && u8(jpeg, at + 4) == 'e'.code

    private fun u8(b: ByteArray, i: Int): Int = b[i].toInt() and 0xFF

    /** A DQT segment with one 8-bit table: marker, length, table byte and 64 values. */
    private const val ZERO_TABLE = 69

    private const val HALF = 1 shl 15
}
