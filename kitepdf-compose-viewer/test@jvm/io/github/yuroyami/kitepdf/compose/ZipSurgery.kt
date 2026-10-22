package io.github.yuroyami.kitepdf.compose

/**
 * Edits to a ZIP file's headers that the W3C container tests describe but a folder of files
 * cannot hold (#497): a container in another compression method, and one split into segments.
 */
internal object ZipSurgery {

    /** [zip] with every entry but `mimetype` marked as compressed by [method], such as 12 for bzip2. */
    fun withMethod(zip: ByteArray, method: Int): ByteArray {
        val out = zip.copyOf()
        var at = 0
        while (at + 30 <= out.size) {
            when (u32(out, at)) {
                LOCAL -> {
                    val nameLength = u16(out, at + 26)
                    val name = String(out, at + 30, nameLength, Charsets.UTF_8)
                    if (name != "mimetype") put16(out, at + 8, method)
                    at += 30 + nameLength + u16(out, at + 28) + u32(out, at + 18).toInt()
                }
                CENTRAL -> {
                    val nameLength = u16(out, at + 28)
                    val name = String(out, at + 46, nameLength, Charsets.UTF_8)
                    if (name != "mimetype") put16(out, at + 10, method)
                    at += 46 + nameLength + u16(out, at + 30) + u16(out, at + 32)
                }
                else -> at++
            }
        }
        return out
    }

    /** [zip] whose end record says it is the second segment of a split archive. */
    fun asSecondDisk(zip: ByteArray): ByteArray {
        val out = zip.copyOf()
        val end = (out.size - 22 downTo 0).first { u32(out, it) == END }
        put16(out, end + 4, 1)
        put16(out, end + 6, 1)
        return out
    }

    private const val LOCAL = 0x04034b50L
    private const val CENTRAL = 0x02014b50L
    private const val END = 0x06054b50L

    private fun u16(b: ByteArray, i: Int) = (b[i].toInt() and 0xFF) or ((b[i + 1].toInt() and 0xFF) shl 8)
    private fun u32(b: ByteArray, i: Int) = u16(b, i).toLong() or (u16(b, i + 2).toLong() shl 16)
    private fun put16(b: ByteArray, i: Int, v: Int) {
        b[i] = v.toByte()
        b[i + 1] = (v shr 8).toByte()
    }
}
