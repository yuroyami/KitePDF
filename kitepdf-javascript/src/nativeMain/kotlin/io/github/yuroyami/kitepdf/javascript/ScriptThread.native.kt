package io.github.yuroyami.kitepdf.javascript

import kotlin.native.concurrent.ThreadLocal
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.completeWith
import kotlinx.coroutines.runBlocking

internal actual fun startScriptThread(): ScriptThread = PosixScriptThread()

/** Starts a detached POSIX thread with [stackBytes] of stack that runs [body]. */
internal expect fun startNativeThread(stackBytes: Long, body: () -> Unit)

/** The script thread the current thread is, if any, so a call from a script runs in place. */
@ThreadLocal
private var runningOn: ScriptThread? = null

/** A thread with [SCRIPT_STACK_BYTES] of stack, since a Worker takes the platform's default. */
private class PosixScriptThread : ScriptThread {

    private val tasks = Channel<() -> Unit>(Channel.UNLIMITED)

    init {
        startNativeThread(SCRIPT_STACK_BYTES) {
            runningOn = this
            runBlocking { for (task in tasks) task() }
        }
    }

    override fun <T> call(block: () -> T): T {
        if (runningOn === this) return block()
        val result = CompletableDeferred<T>()
        check(tasks.trySend { result.completeWith(runCatching(block)) }.isSuccess) { "the script thread is closed" }
        return runBlocking { result.await() }
    }

    override fun close() {
        tasks.close()
    }
}
