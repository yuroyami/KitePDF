package io.github.yuroyami.kitepdf.cbz

import kotlin.math.abs

/**
 * Width and height straight from an image file header, so a 300-page archive
 * opens without decoding 300 images. Knows PNG, GIF, BMP and JPEG; anything
 * else answers null and the caller decodes that one image for its size.
 */
internal object ImageDims {

    fun of(bytes: ByteArray): Pair<Int, Int>? = when {
        isPng(bytes) -> png(bytes)
        isGif(bytes) -> gif(bytes)
        isBmp(bytes) -> bmp(bytes)
        isJpeg(bytes) -> jpeg(bytes)
        else -> null
    }

    private fun isPng(b: ByteArray) = b.size >= 24 &&
        b[0] == 0x89.toByte() && b[1] == 0x50.toByte() && b[2] == 0x4E.toByte() && b[3] == 0x47.toByte()

    private fun isGif(b: ByteArray) = b.size >= 10 &&
        b[0] == 'G'.code.toByte() && b[1] == 'I'.code.toByte() && b[2] == 'F'.code.toByte()

    private fun isBmp(b: ByteArray) = b.size >= 26 &&
        b[0] == 'B'.code.toByte() && b[1] == 'M'.code.toByte()

    private fun isJpeg(b: ByteArray) = b.size >= 4 &&
        b[0] == 0xFF.toByte() && b[1] == 0xD8.toByte()

    private fun png(b: ByteArray): Pair<Int, Int>? =
        dims(be32(b, 16), be32(b, 20))

    private fun gif(b: ByteArray): Pair<Int, Int>? =
        dims(le16(b, 6), le16(b, 8))

    private fun bmp(b: ByteArray): Pair<Int, Int>? =
        dims(le32(b, 18), abs(le32(b, 22))) // negative height = top-down row order

    /** Scan markers for the first SOFn frame header (height then width, big-endian). */
    private fun jpeg(b: ByteArray): Pair<Int, Int>? {
        var p = 2
        while (p + 4 <= b.size) {
            if (b[p] != 0xFF.toByte()) return null
            val marker = b[p + 1].toInt() and 0xFF
            if (marker == 0xD8 || marker == 0x01 || marker in 0xD0..0xD7) {
                p += 2
                continue
            }
            val len = ((b[p + 2].toInt() and 0xFF) shl 8) or (b[p + 3].toInt() and 0xFF)
            if (marker in 0xC0..0xCF && marker != 0xC4 && marker != 0xC8 && marker != 0xCC) {
                if (p + 9 >= b.size) return null
                val h = ((b[p + 5].toInt() and 0xFF) shl 8) or (b[p + 6].toInt() and 0xFF)
                val w = ((b[p + 7].toInt() and 0xFF) shl 8) or (b[p + 8].toInt() and 0xFF)
                return dims(w, h)
            }
            p += 2 + len
        }
        return null
    }

    private fun dims(w: Int, h: Int): Pair<Int, Int>? =
        if (w in 1..0xFFFFFF && h in 1..0xFFFFFF) w to h else null

    private fun be32(b: ByteArray, o: Int): Int =
        ((b[o].toInt() and 0xFF) shl 24) or ((b[o + 1].toInt() and 0xFF) shl 16) or
            ((b[o + 2].toInt() and 0xFF) shl 8) or (b[o + 3].toInt() and 0xFF)

    private fun le16(b: ByteArray, o: Int): Int =
        (b[o].toInt() and 0xFF) or ((b[o + 1].toInt() and 0xFF) shl 8)

    private fun le32(b: ByteArray, o: Int): Int =
        (b[o].toInt() and 0xFF) or ((b[o + 1].toInt() and 0xFF) shl 8) or
            ((b[o + 2].toInt() and 0xFF) shl 16) or ((b[o + 3].toInt() and 0xFF) shl 24)
}
