package io.github.yuroyami.kitepdf.core.render

/**
 * Whether the JPEG 2000 decoder returns the samples of [data] as the file stores them. A raw
 * codestream has no colour boxes, so it does. A JP2 does unless a palette box maps its indices
 * to colours or its colour box names a space that the decoder converts to RGB, such as sYCC or
 * CMYK (ISO/IEC 15444-1, I.5.3.3 and I.5.3.4). Greyscale and sRGB, and an ICC profile, which
 * the decoder does not apply, leave the samples as they are.
 *
 * ISO 32000-1, 8.9.5.2, Table 89: an image dictionary that names a `/ColorSpace` makes the PDF
 * read the samples in that space and ignore the space in the data, which needs them as stored (#808).
 */
internal fun jpxStoresSamples(data: ByteArray): Boolean {
    if (data.size >= 2 && data[0] == 0xFF.toByte() && data[1] == 0x4F.toByte()) return true
    if (data.size < 12 || u32(data, 0) != 12L || u32(data, 4) != 0x6A502020L) return false
    var colorSeen = false
    var stored = true
    fun walk(from: Int, to: Int, depth: Int) {
        var p = from
        while (p + 8 <= to) {
            var length = u32(data, p)
            val type = u32(data, p + 4)
            var body = p + 8
            if (length == 1L) {
                if (p + 16 > to) return
                length = (u32(data, p + 8) shl 32) or u32(data, p + 12)
                body = p + 16
            } else if (length == 0L) {
                length = (to - p).toLong()
            }
            if (length < body - p || p + length > to) return
            val end = (p + length).toInt()
            when (type) {
                0x6A703268L -> if (depth == 0) walk(body, end, 1) // jp2h
                0x70636C72L -> stored = false // pclr
                0x636F6C72L -> if (!colorSeen && body + 3 <= end) { // colr: the first one applies
                    colorSeen = true
                    if (data[body].toInt() == 1) {
                        if (body + 7 > end) { stored = false; return }
                        val space = u32(data, body + 3)
                        if (space != 16L && space != 17L) stored = false // sRGB, greyscale
                    }
                }
                0x6A703263L -> return // jp2c: the codestream follows, and the header boxes come before it
            }
            p = end
        }
    }
    walk(12, data.size, 0)
    return stored
}

private fun u32(d: ByteArray, p: Int): Long =
    ((d[p].toLong() and 0xFF) shl 24) or ((d[p + 1].toLong() and 0xFF) shl 16) or
        ((d[p + 2].toLong() and 0xFF) shl 8) or (d[p + 3].toLong() and 0xFF)

/**
 * The space the JPEG 2000 decoder gives [data] in, `DeviceGray` or `DeviceRGB`, read from its headers
 * alone, or null when the headers leave it open: an opacity channel, a channel definition box, two
 * or four components without a colour box that converts them, or a header cut short. A caller then
 * learns it from a decode. ISO/IEC 15444-1, A.5.1 (SIZ) and I.5.3 (the JP2 header boxes; #744).
 */
internal fun jpxDecodedSpace(data: ByteArray): String? {
    fun fromComponents(n: Int) = when (n) { 1 -> "DeviceGray"; 3 -> "DeviceRGB"; else -> null }
    if (data.size >= 2 && data[0] == 0xFF.toByte() && data[1] == 0x4F.toByte()) return fromComponents(sizComponents(data, 0) ?: return null)
    if (data.size < 12 || u32(data, 0) != 12L || u32(data, 4) != 0x6A502020L) return null
    var colorSpace: Long? = null
    var colorMethod = 0
    var paletteColumns = 0
    var definitions = false
    var components: Int? = null
    fun walk(from: Int, to: Int, depth: Int) {
        var p = from
        while (p + 8 <= to) {
            var length = u32(data, p)
            val type = u32(data, p + 4)
            var body = p + 8
            if (length == 1L) {
                if (p + 16 > to) return
                length = (u32(data, p + 8) shl 32) or u32(data, p + 12)
                body = p + 16
            } else if (length == 0L) {
                length = (to - p).toLong()
            }
            if (length < body - p || p + length > to) return
            val end = (p + length).toInt()
            when (type) {
                0x6A703268L -> if (depth == 0) walk(body, end, 1) // jp2h
                0x70636C72L -> if (body + 3 <= end) paletteColumns = data[body + 2].toInt() and 0xFF // pclr
                0x63646566L -> definitions = true // cdef
                0x636F6C72L -> if (colorMethod == 0 && body + 3 <= end) { // colr: the first one applies
                    colorMethod = data[body].toInt()
                    if (colorMethod == 1 && body + 7 <= end) colorSpace = u32(data, body + 3)
                }
                0x6A703263L -> { components = sizComponents(data, body); return } // jp2c
            }
            p = end
        }
    }
    walk(12, data.size, 0)
    if (definitions) return null
    if (paletteColumns > 0) return fromComponents(paletteColumns)
    return when (colorSpace) {
        17L -> "DeviceGray"
        16L, 18L, 24L -> if (components == 3) "DeviceRGB" else null // sRGB, sYCC, e-sYCC
        12L -> if (components == 4) "DeviceRGB" else null // CMYK, which the decoder converts
        else -> components?.let(::fromComponents)
    }
}

/** Csiz, the component count of the SIZ segment of the codestream at [start], or null. */
private fun sizComponents(d: ByteArray, start: Int): Int? {
    if (start + 42 > d.size || d[start] != 0xFF.toByte() || d[start + 1] != 0x4F.toByte()) return null
    if (d[start + 2] != 0xFF.toByte() || d[start + 3] != 0x51.toByte()) return null
    return ((d[start + 40].toInt() and 0xFF) shl 8) or (d[start + 41].toInt() and 0xFF)
}
