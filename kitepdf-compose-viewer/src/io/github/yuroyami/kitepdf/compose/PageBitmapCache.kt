package io.github.yuroyami.kitepdf.compose

import androidx.compose.ui.graphics.ImageBitmap
import io.github.yuroyami.kitepdf.core.render.ReaderTheme
import io.github.yuroyami.kitepdf.core.withLock

/**
 * LRU cache of rasterized page bitmaps, so scrolling back through a
 * lazy list re-uses pixels instead of redrawing pages. One
 * instance lives on each [KiteDocViewState]; entries cost `w * h * 4` bytes and
 * the eldest are evicted until the total fits [maxBytes].
 *
 * Thread-safe: rasters of several pages run at once (#370), so every read and
 * write takes the cache's own lock. [KiteDocViewState] may swap the whole
 * instance from composition when the budget changes; a raster in flight then
 * writes to the old instance, which is dropped.
 */
internal class PageBitmapCache(private val maxBytes: Long) {

    internal data class Key(
        /** The page object's identity (pages are per-document singletons). */
        val pageIdentity: Any,
        val w: Int,
        val h: Int,
        /** The paper colour the page is drawn on: the theme's when one is set (#394). */
        val bgArgb: Int,
        /** The theme itself, compared by value: two themes with equal hashes must not share pixels (#420). */
        val theme: ReaderTheme?,
        val hairlineBits: Int,
        /** True when the page was drawn without its form widgets, because a form layer draws them. */
        val withoutWidgets: Boolean = false,
        /** Keep the function itself: a hash alone could alias distinct ink filters. */
        val canvasDecorator: KiteCanvasDecorator? = null,
        /**
         * The text measurer that drew the page's system-font text. A new font environment,
         * such as another font family resolver, renders that text again (#421).
         */
        val fontEnvironment: Any? = null,
        /** The part of the page drawn [w] × [h] that the bitmap holds, for a tile; null for the whole page (#375). */
        val region: androidx.compose.ui.unit.IntRect? = null,
        /** What the page paints besides the viewer's settings, such as remote pictures that landed (#38). */
        val contentVersion: Int = 0,
    )

    /**
     * A cached page: its bitmap, and the watch on the fonts of the text it drew through Compose's
     * text, if it drew any that way (#595).
     */
    internal class Entry(val bitmap: ImageBitmap, val fonts: HostFontWatch?)

    // Access-ordered behaviour done manually: Kotlin common LinkedHashMap has
    // no accessOrder constructor, so a hit re-inserts to refresh recency.
    private val entries = LinkedHashMap<Key, Entry>()
    private val lock = io.github.yuroyami.kitepdf.core.KiteLock()

    private var bytes = 0L

    val trackedBytes: Long get() = lock.withLock { bytes }

    /**
     * Saturating byte estimate. Raster dimensions normally stay small, but a
     * cache budget must never be defeated by overflowing `w * h * 4` back to a
     * negative value.
     */
    private fun bytesOf(key: Key): Long {
        val w = key.region?.width ?: key.w
        val h = key.region?.height ?: key.h
        if (w <= 0 || h <= 0) return 0L
        val pixels = w.toLong() * h.toLong() // Int² still fits Long.
        return if (pixels > Long.MAX_VALUE / 4L) Long.MAX_VALUE else pixels * 4L
    }

    /**
     * The cached bitmap for [key], or [produce]'s result, inserted and
     * budget-evicted. With a zero/negative budget the cache is a pass-through.
     */
    fun getOrPut(key: Key, produce: () -> ImageBitmap): ImageBitmap {
        if (maxBytes <= 0L) return produce()
        get(key)?.let { return it.bitmap }
        return produce().also { put(key, it) }
    }

    /**
     * The cached page for [key] refreshed as most recently used, or null. A page whose text met a
     * font that landed after it drew leaves the cache, so it draws again with that font (#595).
     */
    fun get(key: Key): Entry? {
        if (maxBytes <= 0L) return null
        return lock.withLock {
            val hit = entries.remove(key) ?: return@withLock null
            if (hit.fonts?.stale == true) {
                bytes -= bytesOf(key)
                return@withLock null
            }
            entries[key] = hit // re-insert: most recently used
            hit
        }
    }

    /** Inserts [bitmap] under [key], with the watch on its fonts, and evicts eldest entries over budget. */
    fun put(key: Key, bitmap: ImageBitmap, fonts: HostFontWatch? = null) {
        if (maxBytes <= 0L) return
        lock.withLock {
            if (entries.remove(key) != null) bytes -= bytesOf(key)
            val cost = bytesOf(key)
            // The caller still receives an oversized freshly-rendered bitmap, but
            // retaining it would make the advertised cache budget meaningless.
            if (cost == Long.MAX_VALUE || cost > maxBytes) return
            entries[key] = Entry(bitmap, fonts)
            bytes += cost
            val it = entries.keys.iterator()
            while (bytes > maxBytes && it.hasNext()) {
                val eldest = it.next()
                it.remove()
                bytes -= bytesOf(eldest)
            }
        }
    }

    /** True when [key] is cached (test/diagnostic aid; does not touch recency). */
    fun contains(key: Key): Boolean = lock.withLock { entries.containsKey(key) }

    val size: Int get() = lock.withLock { entries.size }
}
