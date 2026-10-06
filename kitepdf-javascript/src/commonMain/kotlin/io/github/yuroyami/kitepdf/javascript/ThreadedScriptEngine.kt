package io.github.yuroyami.kitepdf.javascript

import io.github.yuroyami.kitepdf.core.script.KiteScriptEngine

/**
 * An engine opened on [thread] and called there, whichever thread calls it (#498).
 *
 * KiteJS keeps one open engine per thread, so each chapter of a book gets an engine on a thread
 * of its own this way. A call waits for its answer, so the caller and the engine never run at
 * once, and a host function the engine calls may read what the caller left.
 */
internal class ThreadedScriptEngine(
    private val thread: ScriptThread,
    open: () -> KiteScriptEngine,
) : KiteScriptEngine {

    private val engine: KiteScriptEngine = try {
        thread.call(open)
    } catch (failure: Throwable) {
        thread.close()
        throw failure
    }

    override fun evaluate(source: String, name: String): String? = thread.call { engine.evaluate(source, name) }

    /** Pauses only where the work runs where it is called, as on the web: a thread of its own cannot hand a pause back. */
    override suspend fun evaluatePausing(source: String, name: String): String? =
        if (thread.isOwnThread) evaluate(source, name) else engine.evaluatePausing(source, name)

    override fun defineFunction(name: String, function: (List<Any?>) -> Any?): Unit =
        thread.call { engine.defineFunction(name, function) }

    override fun defineValue(name: String, value: Any?): Unit = thread.call { engine.defineValue(name, value) }

    override fun close() {
        try {
            thread.call { engine.close() }
        } finally {
            thread.close()
        }
    }
}
