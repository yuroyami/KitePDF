package io.github.yuroyami.kitepdf.compose

import androidx.compose.ui.graphics.ImageBitmap

/**
 * LRU cache of rasterized page bitmaps, so scrolling back through a
 * lazy list re-uses pixels instead of redrawing pages. One
 * instance lives on each [KiteDocViewState]; entries cost `w * h * 4` bytes and
 * the eldest are evicted until the total fits [maxBytes].
 *
 * NOT thread-safe by design: every access happens inside the raster
 * coroutine, which serializes on [KitePageRasterizer]'s mutex, so adding a
 * second lock here would only duplicate it.
 */
internal class PageBitmapCache(private val maxBytes: Long) {

    internal data class Key(
        /** The page object's identity (pages are per-document singletons). */
        val pageIdentity: Any,
        val w: Int,
        val h: Int,
        val bgArgb: Int,
        val themeId: Int,
        val hairlineBits: Int,
    )

    // Access-ordered behaviour done manually: Kotlin common LinkedHashMap has
    // no accessOrder constructor, so a hit re-inserts to refresh recency.
    private val entries = LinkedHashMap<Key, ImageBitmap>()
    var trackedBytes = 0L
        private set

    /**
     * Saturating byte estimate. Raster dimensions normally stay small, but a
     * cache budget must never be defeated by overflowing `w * h * 4` back to a
     * negative value.
     */
    private fun bytesOf(key: Key): Long {
        if (key.w <= 0 || key.h <= 0) return 0L
        val pixels = key.w.toLong() * key.h.toLong() // Int² still fits Long.
        return if (pixels > Long.MAX_VALUE / 4L) Long.MAX_VALUE else pixels * 4L
    }

    /**
     * The cached bitmap for [key], or [produce]'s result, inserted and
     * budget-evicted. With a zero/negative budget the cache is a pass-through.
     */
    fun getOrPut(key: Key, produce: () -> ImageBitmap): ImageBitmap {
        if (maxBytes <= 0L) return produce()
        get(key)?.let { return it }
        return produce().also { put(key, it) }
    }

    /** The cached bitmap for [key] refreshed as most recently used, or null. */
    fun get(key: Key): ImageBitmap? {
        if (maxBytes <= 0L) return null
        val hit = entries.remove(key) ?: return null
        entries[key] = hit // re-insert: most recently used
        return hit
    }

    /** Inserts [bitmap] under [key] and evicts eldest entries over budget. */
    fun put(key: Key, bitmap: ImageBitmap) {
        if (maxBytes <= 0L) return
        if (entries.remove(key) != null) trackedBytes -= bytesOf(key)
        val cost = bytesOf(key)
        // The caller still receives an oversized freshly-rendered bitmap, but
        // retaining it would make the advertised cache budget meaningless.
        if (cost == Long.MAX_VALUE || cost > maxBytes) return
        entries[key] = bitmap
        trackedBytes += cost
        val it = entries.keys.iterator()
        while (trackedBytes > maxBytes && it.hasNext()) {
            val eldest = it.next()
            it.remove()
            trackedBytes -= bytesOf(eldest)
        }
    }

    /** True when [key] is cached (test/diagnostic aid; does not touch recency). */
    fun contains(key: Key): Boolean = entries.containsKey(key)

    val size: Int get() = entries.size
}
