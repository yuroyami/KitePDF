package io.github.yuroyami.kitepdf.javascript

internal actual fun startScriptThread(): ScriptThread = InPlaceScriptThread

/** The page's only thread. */
private object InPlaceScriptThread : ScriptThread {

    /** The user whose engine is open here, where the engine opens one per thread. */
    private var holder: EngineUser? = null

    override fun <T> call(block: () -> T): T = block()

    override fun close() {}

    override val isOwnThread: Boolean get() = false

    override fun makeRoom(user: EngineUser) {
        // An engine that holds several engines on a thread leaves the others open.
        if (!KiteJsScriptEngine.oneEnginePerThread) return
        val previous = holder
        // A user whose script is running keeps its engine, and the new one will not open.
        if (previous != null && previous !== user && !previous.closeEngines()) return
        holder = user
    }

    override fun leave(user: EngineUser) {
        if (holder === user) holder = null
    }
}
