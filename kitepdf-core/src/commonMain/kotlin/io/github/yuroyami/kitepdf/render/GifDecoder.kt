package io.github.yuroyami.kitepdf.render

/**
 * A pure-Kotlin GIF decoder (first frame only) producing a [Kind.RAW][ImageXObject.Kind.RAW]
 * [ImageXObject], so GIF images in EPUBs render on every backend through
 * [toRgbaBytes] with no platform loader — closing the last common raster gap
 * alongside [PngDecoder] and [JpegDecoder].
 *
 * Scope: GIF87a/89a, the first image frame, global or local colour table,
 * interlaced or not, single-index transparency (from a Graphic Control Extension).
 * Animation beyond frame 1 is intentionally ignored (EPUB shows a still image).
 * The frame is composited onto a logical-screen-sized RGBA buffer; pixels outside
 * the frame (and the transparent index) are transparent.
 */
internal object GifDecoder {

    fun isGif(b: ByteArray): Boolean =
        b.size >= 6 && b[0].toInt() == 'G'.code && b[1].toInt() == 'I'.code && b[2].toInt() == 'F'.code &&
            b[3].toInt() == '8'.code && (b[4].toInt() == '7'.code || b[4].toInt() == '9'.code) && b[5].toInt() == 'a'.code

    fun decode(bytes: ByteArray): ImageXObject? = runCatching { Decoder(bytes).run() }.getOrNull()

    private class Decoder(val b: ByteArray) {
        var p = 0
        fun u8(): Int = b[p++].toInt() and 0xFF
        fun u16(): Int { val v = (b[p].toInt() and 0xFF) or ((b[p + 1].toInt() and 0xFF) shl 8); p += 2; return v }

        fun run(): ImageXObject? {
            if (!isGif(b)) return null
            p = 6
            val screenW = u16(); val screenH = u16()
            val packed = u8()
            u8(); u8() // bg colour index, pixel aspect ratio
            val gctFlag = packed and 0x80 != 0
            val gctSize = 2 shl (packed and 0x07)
            val gct = if (gctFlag) readColorTable(gctSize) else null
            if (screenW <= 0 || screenH <= 0) return null

            var transparentIndex = -1
            while (p < b.size) {
                when (u8()) {
                    0x21 -> { // extension
                        val label = u8()
                        if (label == 0xF9) { // Graphic Control Extension
                            val len = u8() // 4
                            val flags = u8()
                            u16() // delay
                            val tIdx = u8()
                            u8() // block terminator
                            if (flags and 0x01 != 0) transparentIndex = tIdx
                            if (len != 4) { /* tolerate */ }
                        } else skipSubBlocks()
                    }
                    0x2C -> return decodeImage(screenW, screenH, gct, transparentIndex)
                    0x3B -> return null // trailer before any image
                    else -> return null
                }
            }
            return null
        }

        private fun readColorTable(n: Int): IntArray {
            val t = IntArray(n)
            for (i in 0 until n) {
                val r = u8(); val g = u8(); val bl = u8()
                t[i] = (r shl 16) or (g shl 8) or bl
            }
            return t
        }

        private fun skipSubBlocks() {
            while (true) { val len = u8(); if (len == 0) break; p += len }
        }

        private fun decodeImage(screenW: Int, screenH: Int, gct: IntArray?, transparentIndex: Int): ImageXObject? {
            val left = u16(); val top = u16(); val w = u16(); val h = u16()
            val packed = u8()
            val lctFlag = packed and 0x80 != 0
            val interlace = packed and 0x40 != 0
            val lctSize = 2 shl (packed and 0x07)
            val palette = if (lctFlag) readColorTable(lctSize) else gct ?: return null
            if (w <= 0 || h <= 0) return null

            val minCodeSize = u8()
            val data = readSubBlocks()
            val indices = lzwDecode(data, minCodeSize, w * h) ?: return null
            val ordered = if (interlace) deinterlace(indices, w, h) else indices

            // Composite the frame onto a logical-screen RGBA buffer (transparent bg).
            val rgba = ByteArray(screenW * screenH * 4) // all zero = transparent
            var hasAlpha = false
            for (y in 0 until h) {
                val sy = top + y
                if (sy < 0 || sy >= screenH) continue
                for (x in 0 until w) {
                    val sx = left + x
                    if (sx < 0 || sx >= screenW) continue
                    val idx = ordered[y * w + x].toInt() and 0xFF
                    val o = (sy * screenW + sx) * 4
                    if (idx == transparentIndex) { hasAlpha = true; continue } // leave transparent
                    val c = if (idx < palette.size) palette[idx] else 0
                    rgba[o] = ((c shr 16) and 0xFF).toByte()
                    rgba[o + 1] = ((c shr 8) and 0xFF).toByte()
                    rgba[o + 2] = (c and 0xFF).toByte()
                    rgba[o + 3] = 0xFF.toByte()
                }
            }
            if (top > 0 || left > 0 || w < screenW || h < screenH) hasAlpha = true

            // Emit as DeviceRGB RAW with a soft-mask carrying the alpha (if any).
            val rgb = ByteArray(screenW * screenH * 3)
            val alpha = if (hasAlpha) ByteArray(screenW * screenH) else null
            var s = 0; var d = 0
            for (i in 0 until screenW * screenH) {
                rgb[d] = rgba[s]; rgb[d + 1] = rgba[s + 1]; rgb[d + 2] = rgba[s + 2]
                alpha?.set(i, rgba[s + 3])
                s += 4; d += 3
            }
            return ImageXObject(
                width = screenW, height = screenH, bitsPerComponent = 8, colorSpace = "DeviceRGB",
                kind = ImageXObject.Kind.RAW, encodedBytes = ByteArray(0), pixelBytes = rgb,
                resolvedColorSpace = ColorSpace.DeviceRGB,
                softMaskAlpha = alpha, softMaskWidth = if (alpha != null) screenW else 0,
                softMaskHeight = if (alpha != null) screenH else 0,
            )
        }

        private fun readSubBlocks(): ByteArray {
            val out = ByteArrayCollector()
            while (true) {
                val len = u8(); if (len == 0) break
                out.append(b, p, len); p += len
            }
            return out.toByteArray()
        }

        /** GIF variable-width LSB-first LZW → index stream of length [expected]. */
        private fun lzwDecode(data: ByteArray, minCodeSize: Int, expected: Int): ByteArray? {
            val clear = 1 shl minCodeSize
            val end = clear + 1
            val out = ByteArray(expected)
            var outPos = 0

            val prefix = IntArray(4096)
            val suffix = IntArray(4096)
            val stack = IntArray(4096)
            for (i in 0 until clear) { prefix[i] = -1; suffix[i] = i }
            var next = clear + 2
            var codeSize = minCodeSize + 1
            var maxCode = 1 shl codeSize

            var bitBuf = 0; var bitCnt = 0; var pos = 0
            var prev = -1; var firstByte = 0

            fun readCode(): Int {
                while (bitCnt < codeSize) {
                    if (pos >= data.size) return -1
                    bitBuf = bitBuf or ((data[pos++].toInt() and 0xFF) shl bitCnt)
                    bitCnt += 8
                }
                val c = bitBuf and (maxCode - 1)
                bitBuf = bitBuf ushr codeSize; bitCnt -= codeSize
                return c
            }

            while (true) {
                var code = readCode()
                if (code < 0 || code == end) break
                if (code == clear) {
                    next = clear + 2; codeSize = minCodeSize + 1; maxCode = 1 shl codeSize; prev = -1
                    continue
                }
                if (prev == -1) {
                    // first code after clear: a literal
                    firstByte = suffix[code]
                    if (outPos < expected) out[outPos++] = code.toByte()
                    prev = code
                    continue
                }
                var cur = code
                var sp = 0
                if (cur >= next) { stack[sp++] = firstByte; cur = prev } // KwKwK
                while (cur >= clear) { stack[sp++] = suffix[cur]; cur = prefix[cur] }
                firstByte = suffix[cur]
                stack[sp++] = firstByte
                while (sp > 0 && outPos < expected) out[outPos++] = stack[--sp].toByte()
                if (next < 4096) {
                    prefix[next] = prev; suffix[next] = firstByte; next++
                    if (next == maxCode && codeSize < 12) { codeSize++; maxCode = 1 shl codeSize }
                }
                prev = code
                if (outPos >= expected) break
            }
            return out
        }

        private fun deinterlace(src: ByteArray, w: Int, h: Int): ByteArray {
            val out = ByteArray(src.size)
            var row = 0
            val passes = arrayOf(intArrayOf(0, 8), intArrayOf(4, 8), intArrayOf(2, 4), intArrayOf(1, 2))
            for (pass in passes) {
                var y = pass[0]
                while (y < h) {
                    src.copyInto(out, y * w, row * w, row * w + w)
                    row++; y += pass[1]
                }
            }
            return out
        }
    }

    /** Small growable byte buffer (avoids depending on a platform stream). */
    private class ByteArrayCollector {
        private var buf = ByteArray(1024)
        private var size = 0
        fun append(src: ByteArray, off: Int, len: Int) {
            if (size + len > buf.size) { var n = buf.size * 2; while (n < size + len) n *= 2; buf = buf.copyOf(n) }
            src.copyInto(buf, size, off, off + len); size += len
        }
        fun toByteArray(): ByteArray = buf.copyOf(size)
    }
}
