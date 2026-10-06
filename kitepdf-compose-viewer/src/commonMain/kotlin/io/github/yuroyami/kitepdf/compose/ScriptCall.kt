package io.github.yuroyami.kitepdf.compose

import io.github.yuroyami.kitepdf.core.kiteWarn
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Runs one call to a document's script handler. A handler that throws is logged and the call
 * answers [fallback], so a broken document never ends the host app or stops the viewer (#365).
 */
internal inline fun <T> scriptCall(what: String, fallback: T, call: () -> T): T = try {
    call()
} catch (failure: Exception) {
    if (failure is CancellationException) throw failure
    kiteWarn { "scripts: $what failed: ${failure.message}" }
    fallback
}

/**
 * Runs [work] here, before this returns, unless a script in it pauses, which only happens on the
 * web. A viewer state with no script lane yet calls the handler this way.
 */
internal fun runInPlace(work: suspend () -> Unit) {
    CoroutineScope(Dispatchers.Unconfined).launch { work() }
}
