package io.github.yuroyami.kitepdf.javascript

internal actual fun startScriptThread(): ScriptThread = InPlaceScriptThread

/** The page's only thread. */
private object InPlaceScriptThread : ScriptThread {

    override fun <T> call(block: () -> T): T = block()

    override fun close() {}
}
