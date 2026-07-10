package io.github.yuroyami.kitepdf.render

import io.github.yuroyami.kitepdf.compression.Zlib
import io.github.yuroyami.kitepdf.filters.FilterChain
import kotlin.math.abs

/**
 * A pure-Kotlin PNG decoder (ISO/IEC 15948) producing a [Kind.RAW][ImageXObject.Kind.RAW]
 * [ImageXObject] so it renders on every backend through [toRgbaBytes] with no
 * per-platform code -- unlike JPEG, which core defers to the host loader.
 *
 * PNG is the most common EPUB image format; this closes that gap. It reuses the
 * core [Zlib] inflater for the IDAT stream. Colour is expanded to 8-bit
 * DeviceGray or DeviceRGB; any alpha (from an alpha channel or a palette `tRNS`)
 * becomes the image's soft-mask.
 *
 * Scope: all five filter types; colour types 0/2/3/4/6 at bit depths 1/2/4/8/16
 * (16-bit is downsampled to 8; sub-8-bit applies to grayscale and palette).
 * Adam7-interlaced files and colour-key `tRNS` on non-palette images are not
 * handled yet and return null (the caller skips the image). Ancillary chunks
 * (gamma, sRGB, text) are ignored -- device-space rendering.
 */
internal object PngDecoder {

    private val SIGNATURE = byteArrayOf(
        0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A,
    )

    fun isPng(b: ByteArray): Boolean {
        if (b.size < 8) return false
        for (i in SIGNATURE.indices) if (b[i] != SIGNATURE[i]) return false
        return true
    }

    fun decode(bytes: ByteArray): ImageXObject? {
        if (!isPng(bytes)) return null

        var width = 0
        var height = 0
        var bitDepth = 0
        var colorType = -1
        var interlace = 0
        var palette: ByteArray? = null
        var trns: ByteArray? = null
        val idatParts = ArrayList<Pair<Int, Int>>() // (start, len) into bytes

        var i = 8
        while (i + 8 <= bytes.size) {
            val len = beInt(bytes, i)
            if (len < 0) return null
            val dataStart = i + 8
            if (dataStart + len + 4 > bytes.size) break // truncated chunk
            when (chunkType(bytes, i + 4)) {
                "IHDR" -> {
                    if (len < 13) return null
                    width = beInt(bytes, dataStart)
                    height = beInt(bytes, dataStart + 4)
                    bitDepth = bytes[dataStart + 8].toInt() and 0xFF
                    colorType = bytes[dataStart + 9].toInt() and 0xFF
                    interlace = bytes[dataStart + 12].toInt() and 0xFF
                }
                "PLTE" -> palette = bytes.copyOfRange(dataStart, dataStart + len)
                "tRNS" -> trns = bytes.copyOfRange(dataStart, dataStart + len)
                "IDAT" -> idatParts.add(dataStart to len)
                "IEND" -> break
            }
            i = dataStart + len + 4 // skip data + CRC
        }

        if (width <= 0 || height <= 0 || colorType < 0) return null
        if (interlace != 0) return null // Adam7 unsupported
        if (!validDepth(colorType, bitDepth)) return null
        val channels = channelsOf(colorType) ?: return null

        val compressed = concat(bytes, idatParts)
        val raw = runCatching {
            Zlib.decode(compressed, verifyChecksum = false, maxOutputBytes = FilterChain.MAX_DECODED_STREAM)
        }.getOrNull() ?: return null

        val bitsPerPixel = channels * bitDepth
        val bpp = (bitsPerPixel + 7) / 8 // filtering unit, >= 1 byte
        val rowBytes = (width.toLong() * bitsPerPixel + 7).toInt() / 8
        if (raw.size.toLong() < (rowBytes.toLong() + 1) * height) return null
        val px = unfilter(raw, height, rowBytes, bpp) ?: return null

        return assemble(width, height, bitDepth, colorType, rowBytes, px, palette, trns)
    }

    // ---- chunk / integer helpers --------------------------------------------

    private fun beInt(b: ByteArray, o: Int): Int =
        ((b[o].toInt() and 0xFF) shl 24) or ((b[o + 1].toInt() and 0xFF) shl 16) or
            ((b[o + 2].toInt() and 0xFF) shl 8) or (b[o + 3].toInt() and 0xFF)

    private fun chunkType(b: ByteArray, o: Int): String =
        buildString { for (k in 0 until 4) append((b[o + k].toInt() and 0xFF).toChar()) }

    private fun concat(bytes: ByteArray, parts: List<Pair<Int, Int>>): ByteArray {
        val total = parts.sumOf { it.second }
        val out = ByteArray(total)
        var p = 0
        for ((start, len) in parts) { bytes.copyInto(out, p, start, start + len); p += len }
        return out
    }

    private fun channelsOf(colorType: Int): Int? = when (colorType) {
        0 -> 1; 2 -> 3; 3 -> 1; 4 -> 2; 6 -> 4; else -> null
    }

    private fun validDepth(colorType: Int, bd: Int): Boolean = when (colorType) {
        0 -> bd == 1 || bd == 2 || bd == 4 || bd == 8 || bd == 16
        3 -> bd == 1 || bd == 2 || bd == 4 || bd == 8
        2, 4, 6 -> bd == 8 || bd == 16
        else -> false
    }

    // ---- de-filtering (PNG §9) ----------------------------------------------

    private fun unfilter(raw: ByteArray, h: Int, rowBytes: Int, bpp: Int): ByteArray? {
        val out = ByteArray(rowBytes * h)
        var src = 0
        for (y in 0 until h) {
            val filter = raw[src++].toInt() and 0xFF
            val row = y * rowBytes
            val prev = (y - 1) * rowBytes
            for (x in 0 until rowBytes) {
                val cur = raw[src++].toInt() and 0xFF
                val a = if (x >= bpp) out[row + x - bpp].toInt() and 0xFF else 0
                val b = if (y > 0) out[prev + x].toInt() and 0xFF else 0
                val c = if (y > 0 && x >= bpp) out[prev + x - bpp].toInt() and 0xFF else 0
                val v = when (filter) {
                    0 -> cur
                    1 -> cur + a
                    2 -> cur + b
                    3 -> cur + ((a + b) ushr 1)
                    4 -> cur + paeth(a, b, c)
                    else -> return null
                }
                out[row + x] = (v and 0xFF).toByte()
            }
        }
        return out
    }

    private fun paeth(a: Int, b: Int, c: Int): Int {
        val p = a + b - c
        val pa = abs(p - a); val pb = abs(p - b); val pc = abs(p - c)
        return if (pa <= pb && pa <= pc) a else if (pb <= pc) b else c
    }

    // ---- sample expansion -> ImageXObject -----------------------------------

    private fun assemble(
        w: Int, h: Int, bitDepth: Int, colorType: Int, rowBytes: Int,
        px: ByteArray, palette: ByteArray?, trns: ByteArray?,
    ): ImageXObject? {
        val n = w * h
        return when (colorType) {
            0 -> {
                val gray = ByteArray(n)
                readGray(px, w, h, rowBytes, bitDepth, gray)
                raw(w, h, ColorSpace.DeviceGray, "DeviceGray", gray, null)
            }
            2 -> {
                val rgb = ByteArray(n * 3)
                readTruecolor(px, w, h, rowBytes, bitDepth, 3, rgb, null)
                raw(w, h, ColorSpace.DeviceRGB, "DeviceRGB", rgb, null)
            }
            3 -> {
                if (palette == null) return null
                val rgb = ByteArray(n * 3)
                val alpha = if (trns != null) ByteArray(n) else null
                readPalette(px, w, h, rowBytes, bitDepth, palette, trns, rgb, alpha)
                raw(w, h, ColorSpace.DeviceRGB, "DeviceRGB", rgb, alpha)
            }
            4 -> {
                val gray = ByteArray(n); val alpha = ByteArray(n)
                readGrayAlpha(px, w, h, rowBytes, bitDepth, gray, alpha)
                raw(w, h, ColorSpace.DeviceGray, "DeviceGray", gray, alpha)
            }
            6 -> {
                val rgb = ByteArray(n * 3); val alpha = ByteArray(n)
                readTruecolor(px, w, h, rowBytes, bitDepth, 4, rgb, alpha)
                raw(w, h, ColorSpace.DeviceRGB, "DeviceRGB", rgb, alpha)
            }
            else -> null
        }
    }

    private fun raw(
        w: Int, h: Int, cs: ColorSpace, csName: String, pixels: ByteArray, alpha: ByteArray?,
    ): ImageXObject = ImageXObject(
        width = w, height = h, bitsPerComponent = 8, colorSpace = csName,
        kind = ImageXObject.Kind.RAW, encodedBytes = ByteArray(0), pixelBytes = pixels,
        softMaskAlpha = alpha, softMaskWidth = if (alpha != null) w else 0,
        softMaskHeight = if (alpha != null) h else 0, resolvedColorSpace = cs,
    )

    /** colour type 0 -> one 8-bit grey byte per pixel. */
    private fun readGray(px: ByteArray, w: Int, h: Int, rowBytes: Int, bitDepth: Int, out: ByteArray) {
        var o = 0
        when (bitDepth) {
            8 -> for (y in 0 until h) { val r = y * rowBytes; for (x in 0 until w) out[o++] = px[r + x] }
            16 -> for (y in 0 until h) { val r = y * rowBytes; for (x in 0 until w) out[o++] = px[r + x * 2] }
            else -> {
                val max = (1 shl bitDepth) - 1
                for (y in 0 until h) {
                    val base = y.toLong() * rowBytes * 8
                    for (x in 0 until w) {
                        val s = readBits(px, base + x.toLong() * bitDepth, bitDepth)
                        out[o++] = (s * 255 / max).toByte()
                    }
                }
            }
        }
    }

    /** colour type 2 (comps=3) or 6 (comps=4); writes RGB to [rgb], alpha to [alpha]. */
    private fun readTruecolor(
        px: ByteArray, w: Int, h: Int, rowBytes: Int, bitDepth: Int, comps: Int,
        rgb: ByteArray, alpha: ByteArray?,
    ) {
        val sampleBytes = if (bitDepth == 16) 2 else 1
        val stride = comps * sampleBytes
        var o = 0; var ao = 0
        for (y in 0 until h) {
            var i = y * rowBytes
            for (x in 0 until w) {
                rgb[o++] = px[i]; rgb[o++] = px[i + sampleBytes]; rgb[o++] = px[i + 2 * sampleBytes]
                if (alpha != null) alpha[ao++] = px[i + 3 * sampleBytes]
                i += stride
            }
        }
    }

    /** colour type 4: grey + alpha, 8 or 16 bit. */
    private fun readGrayAlpha(
        px: ByteArray, w: Int, h: Int, rowBytes: Int, bitDepth: Int, gray: ByteArray, alpha: ByteArray,
    ) {
        val sampleBytes = if (bitDepth == 16) 2 else 1
        val stride = 2 * sampleBytes
        var o = 0
        for (y in 0 until h) {
            var i = y * rowBytes
            for (x in 0 until w) {
                gray[o] = px[i]; alpha[o] = px[i + sampleBytes]; o++; i += stride
            }
        }
    }

    /** colour type 3: index -> palette RGB (+ optional per-index tRNS alpha). */
    private fun readPalette(
        px: ByteArray, w: Int, h: Int, rowBytes: Int, bitDepth: Int,
        palette: ByteArray, trns: ByteArray?, rgb: ByteArray, alpha: ByteArray?,
    ) {
        var o = 0; var ao = 0
        for (y in 0 until h) {
            val base = y.toLong() * rowBytes * 8
            for (x in 0 until w) {
                val idx = readBits(px, base + x.toLong() * bitDepth, bitDepth)
                val p = idx * 3
                rgb[o++] = palette.getOr(p); rgb[o++] = palette.getOr(p + 1); rgb[o++] = palette.getOr(p + 2)
                if (alpha != null) alpha[ao++] = if (trns != null && idx < trns.size) trns[idx] else 0xFF.toByte()
            }
        }
    }

    private fun ByteArray.getOr(i: Int): Byte = if (i < size) this[i] else 0

    /** Read [count] bits (MSB-first) at absolute bit position [bitPos]. */
    private fun readBits(data: ByteArray, bitPos: Long, count: Int): Int {
        var v = 0; var p = bitPos
        repeat(count) {
            val bi = (p ushr 3).toInt()
            val sh = 7 - (p and 7).toInt()
            val bit = if (bi < data.size) (data[bi].toInt() shr sh) and 1 else 0
            v = (v shl 1) or bit; p++
        }
        return v
    }
}
