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
