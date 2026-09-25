package io.github.yuroyami.kitepdf.epub

/**
 * A Unicode property packed into a string, for the tables that shaping reads (#319): runs of
 * code points that share one value. Each run is its first and last code point in six hex
 * digits, then its value in two.
 */
internal class PackedRuns(packed: String) {
    private val starts = IntArray(packed.length / RUN)
    private val ends = IntArray(starts.size)
    private val values = ByteArray(starts.size)

    init {
        for (k in starts.indices) {
            val at = k * RUN
            starts[k] = packed.substring(at, at + 6).toInt(16)
            ends[k] = packed.substring(at + 6, at + 12).toInt(16)
            values[k] = packed.substring(at + 12, at + 14).toInt(16).toByte()
        }
    }

    /** The value of the run that holds [cp], or 0 when no run holds it. */
    operator fun get(cp: Int): Int {
        var lo = 0
        var hi = starts.size - 1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            when {
                cp < starts[mid] -> hi = mid - 1
                cp > ends[mid] -> lo = mid + 1
                else -> return values[mid].toInt() and 0xFF
            }
        }
        return 0
    }

    private companion object {
        const val RUN = 14
    }
}
