package io.github.yuroyami.kitepdf.compose

import io.github.yuroyami.kitepdf.core.KiteLock
import io.github.yuroyami.kitepdf.core.withLock
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.suspendCancellableCoroutine

/** How urgent a raster is. A free slot of the [RasterGate] goes to the most urgent raster that waits. */
internal object RasterPriority {
    /** A thumbnail. */
    const val THUMBNAIL = 0

    /** A page drawn ahead, next to the pages on screen. */
    const val NEAR = 1

    /** A page on screen, or a raster that an application asks for. */
    const val VISIBLE = 2
}

/** The raster priority of page slot [pageIndex]: a page on screen before a page composed ahead of it (#370). */
internal fun rasterPriorityOf(state: KiteDocViewState?, pageIndex: Int): Int =
    if (state?.adapter?.shows(pageIndex) == false) RasterPriority.NEAR else RasterPriority.VISIBLE

/**
 * Lets [permits] rasters run at once. A raster that finds no free slot waits. A slot that frees
 * goes to the waiting raster whose priority is highest at that moment, and among equals to the
 * one that came first (#370). Each choice reads the priorities again, so a page that scrolls
 * into view while it waits moves ahead of the pages drawn in advance.
 */
internal class RasterGate(private val permits: Int) {

    private class Waiter(val priority: () -> Int, val arrival: Long, val continuation: CancellableContinuation<Unit>)

    private val lock = KiteLock()
    private var free = permits
    private var arrivals = 0L
    private val waiting = ArrayList<Waiter>()

    /** The rasters that wait for a slot now. */
    val waitingCount: Int get() = lock.withLock { waiting.size }

    /** Runs [block] once a slot is free, and frees the slot when [block] ends or is cancelled. */
    suspend fun <T> withPermit(priority: () -> Int, block: suspend () -> T): T {
        acquire(priority)
        try {
            return block()
        } finally {
            release()
        }
    }

    private suspend fun acquire(priority: () -> Int) {
        val granted = lock.withLock {
            if (free > 0) {
                free--
                true
            } else {
                false
            }
        }
        if (granted) return
        suspendCancellableCoroutine { continuation ->
            val waiter = lock.withLock {
                if (free > 0) {
                    free--
                    null
                } else {
                    Waiter(priority, arrivals++, continuation).also { waiting += it }
                }
            }
            if (waiter == null) {
                continuation.resume(Unit) { _, _, _ -> release() }
            } else {
                continuation.invokeOnCancellation { lock.withLock { waiting.remove(waiter) } }
            }
        }
    }

    /** Hands the slot to the most urgent waiter, or frees it. A waiter cancelled after the hand-off passes it on. */
    private fun release() {
        val next = lock.withLock {
            val best = waiting.maxWithOrNull(compareBy<Waiter> { urgency(it) }.thenByDescending { it.arrival })
            if (best == null) free++ else waiting.remove(best)
            best
        } ?: return
        next.continuation.resume(Unit) { _, _, _ -> release() }
    }

    private fun urgency(waiter: Waiter): Int = runCatching { waiter.priority() }.getOrDefault(RasterPriority.NEAR)
}
