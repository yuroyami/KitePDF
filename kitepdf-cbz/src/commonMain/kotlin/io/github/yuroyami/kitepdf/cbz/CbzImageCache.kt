package io.github.yuroyami.kitepdf.cbz

import io.github.yuroyami.kitepdf.core.KiteLock
import io.github.yuroyami.kitepdf.core.render.KiteImageData
import io.github.yuroyami.kitepdf.core.withLock

/**
 * The decoded images of a comic by entry name, the least recently used dropped first once they
 * pass [maxBytes]. A page drawn again, at another size or as a thumbnail, does not decode its
 * scan again (#386). Safe from any thread.
 */
internal class CbzImageCache(private val maxBytes: Long) {
    private val lock = KiteLock()

    /** Oldest use first. */
    private val entries = LinkedHashMap<String, KiteImageData>()
    private var bytes = 0L

    fun get(name: String): KiteImageData? = lock.withLock {
        // Taken out and put back, so the entry moves to the newest end.
        entries.remove(name)?.also { entries[name] = it }
    }

    fun put(name: String, image: KiteImageData) {
        val size = sizeOf(image)
        // An image larger than the whole budget is not kept, so it cannot push every other one out.
        if (size > maxBytes) return
        lock.withLock {
            entries.remove(name)?.let { bytes -= sizeOf(it) }
            entries[name] = image
            bytes += size
            val oldest = entries.entries.iterator()
            while (bytes > maxBytes && oldest.hasNext()) {
                val entry = oldest.next()
                if (entry.key == name) continue
                bytes -= sizeOf(entry.value)
                oldest.remove()
            }
        }
    }

    private fun sizeOf(image: KiteImageData): Long =
        (image.pixelBytes?.size ?: image.encodedBytes.size).toLong() + (image.softMaskAlpha?.size ?: 0)
}
