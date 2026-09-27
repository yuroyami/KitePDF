package io.github.yuroyami.kitepdf.core

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import platform.posix.CLOCK_THREAD_CPUTIME_ID
import platform.posix.clock_gettime
import platform.posix.timespec
import platform.posix.usleep
import kotlin.native.concurrent.ObsoleteWorkersApi
import kotlin.native.concurrent.TransferMode
import kotlin.native.concurrent.Worker
import kotlin.test.Test
import kotlin.test.assertTrue

/** The CPU time that the calling thread used, in nanoseconds. */
@OptIn(ExperimentalForeignApi::class)
private fun threadCpuNanos(): Long = memScoped {
    val time = alloc<timespec>()
    clock_gettime(CLOCK_THREAD_CPUTIME_ID.toUInt(), time.ptr)
    time.tv_sec * 1_000_000_000L + time.tv_nsec
}

/**
 * A thread that waits for a lock held for a long time, such as during an EPUB chapter layout,
 * sleeps instead of spinning. It spun at full speed for the whole layout (#388).
 */
class KiteLockWaitTest {

    @OptIn(ObsoleteWorkersApi::class, ExperimentalForeignApi::class)
    @Test
    fun a_waiter_sleeps_while_a_long_holder_keeps_the_lock() {
        val lock = KiteLock()
        lock.lock()
        val worker = Worker.start()
        try {
            val waiterCpu = worker.execute(TransferMode.SAFE, { lock }) { held ->
                val start = threadCpuNanos()
                held.lock()
                held.unlock()
                threadCpuNanos() - start
            }
            usleep(300_000u)
            lock.unlock()
            val cpu = waiterCpu.result
            assertTrue(cpu < 60_000_000L, "the waiter used ${cpu / 1_000_000} ms of CPU during a 300 ms wait")
        } finally {
            worker.requestTermination().result
        }
    }
}
