package io.github.yuroyami.kitepdf.javascript

import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.startCoroutine

/**
 * Runs [block] to its end here and now. It is for a call on a script thread of its own, where no
 * engine can pause, so [block] never suspends; one that does is a bug, and fails loudly (#489).
 */
internal fun <T> runNow(block: suspend () -> T): T {
    var outcome: Result<T>? = null
    block.startCoroutine(Continuation(EmptyCoroutineContext) { outcome = it })
    return checkNotNull(outcome) { "a script call paused where nothing can wait for it" }.getOrThrow()
}
