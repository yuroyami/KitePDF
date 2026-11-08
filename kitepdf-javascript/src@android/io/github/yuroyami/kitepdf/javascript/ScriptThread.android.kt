package io.github.yuroyami.kitepdf.javascript

import java.util.concurrent.Callable
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

internal actual fun startScriptThread(): ScriptThread = ExecutorScriptThread()

/** One daemon thread behind a queue, so a runner that is never closed does not keep the process alive. */
private class ExecutorScriptThread : ScriptThread {

    @Volatile
    private var thread: Thread? = null

    private val executor: ExecutorService = Executors.newSingleThreadExecutor { task ->
        Thread(null, task, "kitepdf-scripts", SCRIPT_STACK_BYTES).apply { isDaemon = true }.also { thread = it }
    }

    override fun <T> call(block: () -> T): T {
        if (Thread.currentThread() === thread) return block()
        return executor.submit(Callable { runCatching(block) }).get().getOrThrow()
    }

    override fun callWithin(millis: Long, block: () -> Unit): Boolean {
        if (Thread.currentThread() === thread) {
            block()
            return true
        }
        val task = executor.submit { runCatching(block) }
        return try {
            task.get(millis, java.util.concurrent.TimeUnit.MILLISECONDS)
            true
        } catch (_: java.util.concurrent.TimeoutException) {
            false
        }
    }

    override fun close() {
        executor.shutdown()
    }
}
