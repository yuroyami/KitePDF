package io.github.yuroyami.kitepdf.nativerenderer

/** RGBA byte quads (straight alpha) to ARGB ints, the android.graphics packing. */
internal object RgbaPixels {
    fun toArgbInts(rgba: ByteArray): IntArray {
        val out = IntArray(rgba.size / 4)
        for (i in out.indices) {
            val p = i * 4
            out[i] = ((rgba[p + 3].toInt() and 0xFF) shl 24) or
                ((rgba[p].toInt() and 0xFF) shl 16) or
                ((rgba[p + 1].toInt() and 0xFF) shl 8) or
                (rgba[p + 2].toInt() and 0xFF)
        }
        return out
    }
}
