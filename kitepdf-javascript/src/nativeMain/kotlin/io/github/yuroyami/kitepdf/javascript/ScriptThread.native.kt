@file:OptIn(ObsoleteWorkersApi::class)

package io.github.yuroyami.kitepdf.javascript

import kotlin.native.concurrent.ObsoleteWorkersApi
import kotlin.native.concurrent.ThreadLocal
import kotlin.native.concurrent.TransferMode
import kotlin.native.concurrent.Worker

internal actual fun startScriptThread(): ScriptThread = WorkerScriptThread()

/** The script thread the current thread is, if any, so a call from a script runs in place. */
@ThreadLocal
private var runningOn: ScriptThread? = null

/** A worker of its own, which Kotlin/Native runs on one thread for its whole life. */
private class WorkerScriptThread : ScriptThread {

    private val worker = Worker.start(name = "kitepdf-scripts")

    override fun <T> call(block: () -> T): T {
        if (runningOn === this) return block()
        val future = worker.execute(TransferMode.SAFE, { Task(this, block) }) { task ->
            runningOn = task.owner
            runCatching(task.block)
        }
        return future.result.getOrThrow()
    }

    override fun close() {
        worker.requestTermination().result
    }
}

private class Task<T>(val owner: ScriptThread, val block: () -> T)
