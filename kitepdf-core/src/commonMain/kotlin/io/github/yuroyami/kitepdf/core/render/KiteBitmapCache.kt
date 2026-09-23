package io.github.yuroyami.kitepdf.core.render

/**
 * Platform bitmaps that a canvas built from images, so that an image drawn many
 * times on a page, such as a tiled background or a row of icons, converts once (#117).
 * A canvas keeps one cache for as long as it draws.
 *
 * A bitmap is kept for one image instance and one [KiteImageSampling]: the same
 * image drawn at another size needs another bitmap. The cache holds at most
 * [budgetBytes] of bitmaps and drops the bitmap used least recently first. A bitmap
 * larger than the whole budget is not kept.
 */
public class KiteBitmapCache<T : Any>(
    private val budgetBytes: Long = DEFAULT_BUDGET_BYTES,
) {
    private data class Key(val image: KiteImageData, val shrinkX: Int, val shrinkY: Int)

    private class Entry<T>(val bitmap: T, val bytes: Long)

    /** In order from the bitmap used least recently to the one used most recently. */
    private val entries = LinkedHashMap<Key, Entry<T>>()
    private var bytes = 0L

    /** The bytes of the bitmaps that the cache holds. */
    public val heldBytes: Long get() = bytes

    /**
     * The bitmap for [image] drawn with [sampling]. On a miss, [build] makes it and
     * [sizeOf] gives its size in bytes. Returns null when [build] does.
     */
    public fun getOrPut(image: KiteImageData, sampling: KiteImageSampling, sizeOf: (T) -> Long, build: () -> T?): T? {
        val key = Key(image, sampling.shrinkX, sampling.shrinkY)
        entries.remove(key)?.let { entry ->
            // Put it back at the end, as the bitmap used most recently.
            entries[key] = entry
            return entry.bitmap
        }
        val bitmap = build() ?: return null
        val size = sizeOf(bitmap)
        if (size > budgetBytes) return bitmap
        entries[key] = Entry(bitmap, size)
        bytes += size
        val oldest = entries.values.iterator()
        while (bytes > budgetBytes && oldest.hasNext()) {
            bytes -= oldest.next().bytes
            oldest.remove()
        }
        return bitmap
    }

    public companion object {
        /** The default budget: 16 MB, enough for the repeated images of a page on a small heap. */
        public const val DEFAULT_BUDGET_BYTES: Long = 16L * 1024 * 1024
    }
}
