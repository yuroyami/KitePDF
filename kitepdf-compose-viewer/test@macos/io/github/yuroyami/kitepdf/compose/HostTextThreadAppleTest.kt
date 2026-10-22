package io.github.yuroyami.kitepdf.compose

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import platform.CoreFoundation.CFRunLoopRunInMode
import platform.CoreFoundation.kCFRunLoopDefaultMode
import platform.Foundation.NSThread
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

/** Exercises the actual Apple main dispatcher and thread checks, without drawing host text (#428). */
@OptIn(ExperimentalForeignApi::class)
class HostTextThreadAppleTest {
    @Test
    fun a_worker_is_refused_and_the_suspend_bridge_runs_on_the_main_thread() {
        assertTrue(NSThread.isMainThread, "the native test runner must own the main run loop")
        assertTrue(isHostTextThread())
        requireHostTextThread()

        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val work = scope.async {
            assertFalse(NSThread.isMainThread, "the test must first exercise an actual worker")
            assertFalse(isHostTextThread())
            val failure = assertFailsWith<IllegalStateException> { requireHostTextThread() }
            assertTrue(failure.message.orEmpty().contains("rasterizeOffMain"))

            val ranOnMain = onHostTextThread {
                requireHostTextThread()
                NSThread.isMainThread
            }
            assertTrue(ranOnMain, "the bridge must use the real Apple main thread")
            assertFalse(NSThread.isMainThread, "the bridge must return to its worker caller")
        }
        try {
            // A blocking await on this thread would prevent Dispatchers.Main from making progress.
            assertTrue(pumpUntil(10.seconds) { work.isCompleted }, "the main-thread bridge timed out")
            runBlocking { work.await() } // Already complete; propagate assertions from the worker.
        } finally {
            scope.cancel()
            assertTrue(pumpUntil(5.seconds) { work.isCompleted }, "cancelled worker did not finish")
        }
    }

    private fun pumpUntil(timeout: Duration, complete: () -> Boolean): Boolean {
        val started = TimeSource.Monotonic.markNow()
        while (!complete() && started.elapsedNow() < timeout) {
            CFRunLoopRunInMode(kCFRunLoopDefaultMode, 0.01, true)
        }
        return complete()
    }
}
