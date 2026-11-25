package io.github.yuroyami.kitepdf.core.render

import io.github.yuroyami.kitepdf.core.DecodeCancellationSlot
import kotlin.coroutines.cancellation.CancellationException

/**
 * Thrown by [decodeCheck] to stop an image decode whose render was cancelled (#905). It is a
 * [CancellationException], so a module that catches failures around a decode can let it through.
 */
internal class DecodeCancelled : CancellationException("the image decode was cancelled")

/**
 * The check that an ImageKodec decode on this thread runs, or null outside
 * [io.github.yuroyami.kitepdf.core.runCancellableDecodes]. It throws [DecodeCancelled] once the
 * render's cancellation reads true.
 */
internal fun decodeCheck(): (() -> Unit)? {
    val cancellation = DecodeCancellationSlot.current ?: return null
    return { if (cancellation.isCancelled()) throw DecodeCancelled() }
}

/**
 * [block], or null when it throws, as `runCatching { }.getOrNull()` gives. A [DecodeCancelled]
 * passes through: a cancelled decode is not a broken image, so nothing may cache it as one.
 */
internal inline fun <T> decodeOrNull(block: () -> T): T? = try {
    block()
} catch (cancelled: DecodeCancelled) {
    throw cancelled
} catch (failed: Throwable) {
    null
}
