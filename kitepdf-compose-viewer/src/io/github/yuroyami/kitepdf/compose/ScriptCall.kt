package io.github.yuroyami.kitepdf.compose

import io.github.yuroyami.kitepdf.core.kiteWarn
import kotlin.coroutines.cancellation.CancellationException
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Runs one call to a document's script handler. A handler that throws is logged and the call
 * answers [fallback], so a broken document never ends the host app or stops the viewer (#365).
 * An Error counts too: a chapter that builds a tree 30,000 levels deep made the handler throw
 * StackOverflowError, which passed a catch of Exception and ended an Android app (#956), as an
 * OutOfMemoryError once did to the raster guard (#219).
 */
internal inline fun <T> scriptCall(what: String, fallback: T, call: () -> T): T = try {
    call()
} catch (failure: Throwable) {
    if (failure is CancellationException) throw failure
    kiteWarn { "scripts: $what failed: $failure" }
    fallback
}

/**
 * Where the viewer launches script work that no composition owns. A failure in one launch is logged
 * and goes no further, where a scope with no parent and no handler handed it to the thread's
 * uncaught exception handler, which ends an Android app (#956).
 */
internal val ScriptFailureHandler: CoroutineExceptionHandler = CoroutineExceptionHandler { _, failure ->
    kiteWarn { "scripts: a script call failed: $failure" }
}

/** A scope on [context] whose launches fail alone, each logged by [ScriptFailureHandler] (#956). */
internal fun isolatedScriptScope(context: CoroutineContext): CoroutineScope =
    CoroutineScope(context + SupervisorJob() + ScriptFailureHandler)

/**
 * Runs [work] here, before this returns, unless a script in it pauses, which only happens on the
 * web. A viewer state with no script lane yet calls the handler this way.
 */
internal fun runInPlace(work: suspend () -> Unit) {
    isolatedScriptScope(Dispatchers.Unconfined).launch { work() }
}
