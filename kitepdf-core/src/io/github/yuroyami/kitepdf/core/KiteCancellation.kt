package io.github.yuroyami.kitepdf.core

/**
 * A signal that a render in progress may stop early, as MuPDF's abort cookie does. A
 * renderer checks it between operators. Once it reads true, the render paints nothing
 * more and returns promptly, with the canvas closed (#188).
 *
 * ```kotlin
 * val job = coroutineContext.job
 * page.renderTo(canvas, ctm, KiteCancellation { !job.isActive })
 * ```
 */
public fun interface KiteCancellation {
    public fun isCancelled(): Boolean
}

/**
 * Runs [block] so that each image decode it starts on this thread stops once [cancellation]
 * reads true. A renderer that reads its cancellation only between operators wraps its render in
 * this, so one large image cannot hold a render that the caller gave up on (#905).
 *
 * A stopped decode ends [block] at that point, and this returns false. The decode caches no
 * image and logs no warning, so a later render decodes the image again. Null runs [block] as
 * it is and returns true.
 */
public fun runCancellableDecodes(cancellation: KiteCancellation?, block: () -> Unit): Boolean {
    if (cancellation == null) {
        block()
        return true
    }
    val outer = DecodeCancellationSlot.current
    DecodeCancellationSlot.current = cancellation
    return try {
        block()
        true
    } catch (cancelled: io.github.yuroyami.kitepdf.core.render.DecodeCancelled) {
        false
    } finally {
        DecodeCancellationSlot.current = outer
    }
}

/** The cancellation of the innermost [runCancellableDecodes] of this thread. */
internal expect object DecodeCancellationSlot {
    var current: KiteCancellation?
}
