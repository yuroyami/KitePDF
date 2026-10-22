package io.github.yuroyami.kitepdf.epub.script

import io.github.yuroyami.kitepdf.core.KiteLock
import io.github.yuroyami.kitepdf.core.render.KiteImageData
import io.github.yuroyami.kitepdf.core.withLock

/**
 * The pixels that scripts wrote into canvases with `putImageData`, by the `href` of the `<image>`
 * that shows them in a canvas's drawing (#610). The page paints them from here, so they are never
 * encoded as a file. The scripts' thread writes and the painting thread reads, so a lock guards it.
 */
internal class CanvasImages {
    private val lock = KiteLock()
    private val images = HashMap<String, KiteImageData>()
    private var nextOwner = 0
    private var nextImage = 0

    /** A prefix of hrefs for one set of canvases, so [retainOnly] of one leaves the others alone. */
    fun owner(): String = lock.withLock { "$SCHEME${nextOwner++}-" }

    /** Holds [image] under a new href that starts with [owner], and answers the href. */
    fun add(owner: String, image: KiteImageData): String = lock.withLock {
        "$owner${nextImage++}".also { images[it] = image }
    }

    operator fun get(href: String): KiteImageData? =
        if (!href.startsWith(SCHEME)) null else lock.withLock { images[href] }

    /** How many images [owner] holds. */
    fun count(owner: String): Int = lock.withLock { images.keys.count { it.startsWith(owner) } }

    /** Forgets each image of [owner] whose href is not in [live]. */
    fun retainOnly(owner: String, live: Set<String>) = lock.withLock {
        images.keys.removeAll { it.startsWith(owner) && it !in live }
    }

    companion object {
        const val SCHEME = "kite-canvas:"
    }
}
