package io.github.yuroyami.kitepdf.writer

/**
 * The frame of a JPEG file, as its SOF segment gives it (ITU-T T.81, B.2.2), and whether it carries
 * an Adobe APP14 marker, which a reader takes as a sign of inverted CMYK samples.
 */
internal class JpegHeader(val width: Int, val height: Int, val components: Int, val adobe: Boolean) {
    companion object {
        /** The header of [b], or null when it is not a JPEG or its frame header is missing or cut off. */
        fun read(b: ByteArray): JpegHeader? {
            fun u8(i: Int) = b[i].toInt() and 0xFF
            fun u16(i: Int) = (u8(i) shl 8) or u8(i + 1)
            if (b.size < 4 || u8(0) != 0xFF || u8(1) != 0xD8) return null
            var adobe = false
            var i = 2
            while (i + 3 < b.size) {
                if (u8(i) != 0xFF) return null
                val marker = u8(i + 1)
                if (marker == 0xFF) { i++; continue } // a fill byte
                if (marker == 0xD8 || marker == 0x01 || marker in 0xD0..0xD7) { i += 2; continue }
                if (marker == 0xD9 || marker == 0xDA) return null // the scan came before any frame
                val length = u16(i + 2)
                if (length < 2 || i + 2 + length > b.size) return null
                val body = i + 4
                if (marker == 0xEE && length >= 7 && b.decodeToString(body, body + 5) == "Adobe") adobe = true
                // SOF0 to SOF15 but DHT (C4), JPG (C8) and DAC (CC).
                if (marker in 0xC0..0xCF && marker != 0xC4 && marker != 0xC8 && marker != 0xCC) {
                    if (length < 8) return null
                    val height = u16(body + 1)
                    val width = u16(body + 3)
                    val components = u8(body + 5)
                    if (width == 0 || height == 0) return null
                    // An APP14 segment may follow the frame header, before the scan.
                    var j = i + 2 + length
                    while (!adobe && j + 3 < b.size && u8(j) == 0xFF && u8(j + 1) != 0xDA) {
                        val len = u16(j + 2)
                        if (len < 2 || j + 2 + len > b.size) break
                        if (u8(j + 1) == 0xEE && len >= 7 && b.decodeToString(j + 4, j + 9) == "Adobe") adobe = true
                        j += 2 + len
                    }
                    return JpegHeader(width, height, components, adobe)
                }
                i += 2 + length
            }
            return null
        }
    }
}
