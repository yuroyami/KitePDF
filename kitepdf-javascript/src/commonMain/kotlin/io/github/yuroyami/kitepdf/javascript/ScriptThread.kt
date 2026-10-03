package io.github.yuroyami.kitepdf.javascript

/**
 * The one thread a runner's engine lives on.
 *
 * KiteJS keeps an open engine in a slot of the thread that opened it, and refuses a call from any
 * other thread. A viewer calls the runner from a pool, so the runner moves every call here instead.
 */
internal interface ScriptThread {

    /** Runs [block] on this thread and waits for its result. Runs it at once when already here. */
    fun <T> call(block: () -> T): T

    /** Stops the thread after the work already queued. */
    fun close()

    /**
     * False where there is one thread and the work runs where it is called, so two engines
     * cannot be open at once, whichever script thread opened them.
     */
    val isOwnThread: Boolean get() = true
}

/** Starts a thread for one runner. JavaScript and WebAssembly have one thread, so there the work runs where it is called. */
internal expect fun startScriptThread(): ScriptThread
