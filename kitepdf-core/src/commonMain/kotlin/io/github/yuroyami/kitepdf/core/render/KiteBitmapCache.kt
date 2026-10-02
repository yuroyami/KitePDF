package io.github.yuroyami.kitepdf.core.render

import io.github.yuroyami.kitepdf.core.KiteLock
import io.github.yuroyami.kitepdf.core.withLock

/**
 * Platform bitmaps that a canvas built from images, so that an image drawn many
 * times on a page, such as a tiled background or a row of icons, converts once (#117).
 * A viewer can keep one cache across draws without retaining decoded source images (#371).
 *
 * A bitmap is kept for one image identity and one [KiteImageSampling]: the same
 * image drawn at another size needs another bitmap. The cache holds at most
 * [budgetBytes] of bitmaps and drops the bitmap used least recently first. A bitmap
 * larger than the whole budget is not kept. A nonpositive budget disables retention.
 * Entries retain only opaque identities and bitmaps, never source image buffers.
 * Map operations are synchronized; conversions and size callbacks run outside the lock.
 */
public class KiteBitmapCache<T : Any>(
    private val budgetBytes: Long = DEFAULT_BUDGET_BYTES,
) {
    private data class Key(val identity: KiteImageIdentity, val shrinkX: Int, val shrinkY: Int)

    private class Entry<T>(val bitmap: T, val bytes: Long)

    /** In order from the bitmap used least recently to the one used most recently. */
    private val entries = LinkedHashMap<Key, Entry<T>>()
    private val lock = KiteLock()
    private var bytes = 0L
    private var generation = Any()

    /** The bytes of the bitmaps that the cache holds. */
    public val heldBytes: Long get() = lock.withLock { bytes }

    /**
     * Releases every retained bitmap. A conversion already running may return its bitmap,
     * but cannot repopulate this cleared generation. This does not recycle native resources:
     * a recorded page or another caller may still be drawing a returned bitmap.
     */
    public fun clear() {
        lock.withLock {
            entries.clear()
            bytes = 0L
            generation = Any()
        }
    }

    /**
     * The bitmap for [image] drawn with [sampling]. On a miss, [build] makes it and
     * [sizeOf] gives its size in bytes. Returns null when [build] does. A nonpositive
     * size is not retained. Concurrent misses may build twice; the first retained result
     * wins and both callers receive it. No callback runs while the cache lock is held.
     */
    public fun getOrPut(image: KiteImageData, sampling: KiteImageSampling, sizeOf: (T) -> Long, build: () -> T?): T? {
        val key = Key(image.bitmapIdentity, sampling.shrinkX, sampling.shrinkY)
        val started = lock.withLock {
            entries.remove(key)?.let { entry ->
                // Put it back at the end, as the bitmap used most recently.
                entries[key] = entry
                return entry.bitmap
            }
            generation
        }
        val bitmap = build() ?: return null
        val size = sizeOf(bitmap)
        return lock.withLock {
            if (generation !== started) return@withLock bitmap
            entries.remove(key)?.let { entry ->
                entries[key] = entry
                return@withLock entry.bitmap
            }
            if (budgetBytes <= 0L || size <= 0L || size > budgetBytes) return@withLock bitmap
            // Subtract before adding: both the budget and a valid reported size may be Long.MAX_VALUE.
            val oldest = entries.values.iterator()
            while (bytes > budgetBytes - size && oldest.hasNext()) {
                bytes -= oldest.next().bytes
                oldest.remove()
            }
            entries[key] = Entry(bitmap, size)
            bytes += size
            bitmap
        }
    }

    public companion object {
        /** The default budget: 16 MB, enough for the repeated images of a page on a small heap. */
        public const val DEFAULT_BUDGET_BYTES: Long = 16L * 1024 * 1024
    }
}
