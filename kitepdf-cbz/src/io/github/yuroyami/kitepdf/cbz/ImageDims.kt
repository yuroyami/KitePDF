package io.github.yuroyami.kitepdf.cbz

import kotlin.math.abs

/**
 * Width and height straight from an image file header, so a 300-page archive
 * opens without decoding 300 images. Knows PNG, GIF, BMP, JPEG, WebP and TIFF;
 * anything else answers null and the caller decodes that one image for its size.
 */
internal object ImageDims {

    fun of(bytes: ByteArray): Pair<Int, Int>? = when {
        isPng(bytes) -> png(bytes)
        isGif(bytes) -> gif(bytes)
        isBmp(bytes) -> bmp(bytes)
        isJpeg(bytes) -> jpeg(bytes)
        isWebp(bytes) -> webp(bytes)
        isTiff(bytes) -> tiff(bytes)
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

    private fun isWebp(b: ByteArray) = b.size >= 30 &&
        b.decodeToString(0, 4) == "RIFF" && b.decodeToString(8, 12) == "WEBP"

    private fun isTiff(b: ByteArray) = b.size >= 8 && (
        (b[0] == 'I'.code.toByte() && b[1] == 'I'.code.toByte() && b[2] == 42.toByte() && b[3] == 0.toByte()) ||
            (b[0] == 'M'.code.toByte() && b[1] == 'M'.code.toByte() && b[2] == 0.toByte() && b[3] == 42.toByte())
        )

    /** The first chunk after the RIFF header names which of the three WebP bitstreams follows. */
    private fun webp(b: ByteArray): Pair<Int, Int>? = when (b.decodeToString(12, 16)) {
        // Lossy: a 3-byte frame tag and the 9D 01 2A start code, then 14-bit width and height.
        "VP8 " -> dims(le16(b, 26) and 0x3FFF, le16(b, 28) and 0x3FFF)
        // Lossless: the 0x2F signature, then 14-bit width-1 and height-1.
        "VP8L" -> if (b[20] != 0x2F.toByte()) null else {
            val bits = le32(b, 21)
            dims((bits and 0x3FFF) + 1, ((bits ushr 14) and 0x3FFF) + 1)
        }
        // Extended: four flag bytes, then 24-bit canvas width-1 and height-1.
        "VP8X" -> dims(le24(b, 24) + 1, le24(b, 27) + 1)
        else -> null
    }

    /** Width and height tags (256 and 257) of the first image file directory. */
    private fun tiff(b: ByteArray): Pair<Int, Int>? {
        val le = b[0] == 'I'.code.toByte()
        fun u16(o: Int) = if (le) le16(b, o) else ((b[o].toInt() and 0xFF) shl 8) or (b[o + 1].toInt() and 0xFF)
        fun u32(o: Int) = if (le) le32(b, o) else be32(b, o)
        val ifd = u32(4)
        if (ifd < 8 || ifd + 2 > b.size) return null
        var w = 0
        var h = 0
        for (i in 0 until u16(ifd)) {
            val e = ifd + 2 + i * 12
            if (e + 12 > b.size) break
            val tag = u16(e)
            if (tag != 256 && tag != 257) continue
            val v = if (u16(e + 2) == 3) u16(e + 8) else u32(e + 8)
            if (tag == 256) w = v else h = v
        }
        return dims(w, h)
    }

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

    private fun le24(b: ByteArray, o: Int): Int =
        (b[o].toInt() and 0xFF) or ((b[o + 1].toInt() and 0xFF) shl 8) or ((b[o + 2].toInt() and 0xFF) shl 16)

    private fun le32(b: ByteArray, o: Int): Int =
        (b[o].toInt() and 0xFF) or ((b[o + 1].toInt() and 0xFF) shl 8) or
            ((b[o + 2].toInt() and 0xFF) shl 16) or ((b[o + 3].toInt() and 0xFF) shl 24)
}
