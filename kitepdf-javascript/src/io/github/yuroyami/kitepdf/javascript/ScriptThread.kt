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

    /** How deep a script on this thread may recurse, in bytes of stack. */
    val stackBytes: Long get() = if (isOwnThread) SCRIPT_STACK_LIMIT else KiteJsScriptEngine.DEFAULT_STACK_BYTES

    /**
     * Called here before [user] opens an engine. Where every engine shares one thread, the user
     * whose engine is open asks it to close first, unless that user is [user] itself or one of its
     * scripts is running, and [user] becomes the one to ask next time. Where each user has a
     * thread of its own, this does nothing (#553).
     */
    fun makeRoom(user: EngineUser) {}

    /** Called here once [user] has closed its engines for good. */
    fun leave(user: EngineUser) {}
}

/** A runner whose engines may have to close so that another runner's can open on the thread they share. */
internal fun interface EngineUser {

    /** Closes the user's open engines and answers true, or answers false while one of them runs a script. */
    fun closeEngines(): Boolean
}

/** Starts a thread for one runner. JavaScript and WebAssembly have one thread, so there the work runs where it is called. */
internal expect fun startScriptThread(): ScriptThread

/**
 * The stack of a script thread, so that a script recurses as deep on every target: twice
 * [SCRIPT_STACK_LIMIT], which leaves the host's own frames room above it.
 */
internal const val SCRIPT_STACK_BYTES: Long = 16L shl 20

/** How deep, in bytes of stack, a script on a script thread may recurse before it gets a RangeError. */
internal const val SCRIPT_STACK_LIMIT: Long = 8L shl 20
