package io.github.yuroyami.kitepdf.core

import kotlin.concurrent.AtomicLong
import kotlinx.cinterop.ExperimentalForeignApi
import platform.posix.pthread_self
import platform.posix.sched_yield
import platform.posix.usleep

/**
 * Reentrant spinlock over a single atomic owner word. POSIX mutex structs
 * differ per native family (structs on Apple/Linux, integer typedefs on
 * MinGW), so a portable pure-atomics lock beats four platform actuals here.
 * Most critical sections under this lock are a few map operations. After a
 * short spin budget the waiter yields: an empty spin on Darwin's QoS
 * scheduler can starve a lower-priority holder scheduled on the same core
 * (the failure mode that got OSSpinLock deprecated). A holder that keeps the
 * lock longer, such as an EPUB chapter layout, makes the waiter sleep in
 * short steps, so it does not burn a core for the whole layout (#388).
 */
@OptIn(ExperimentalForeignApi::class)
public actual class KiteLock actual constructor() {
    /** 0 = unlocked, else the owning thread's id. */
    private val owner = AtomicLong(0)
    private var depth = 0

    public actual fun lock() {
        val me = currentThreadId()
        if (owner.value == me) {
            depth++
            return
        }
        var spins = 0
        var yields = 0
        while (!owner.compareAndSet(0, me)) {
            if (++spins < SPIN_BUDGET) continue
            spins = 0
            if (++yields < YIELD_BUDGET) sched_yield() else usleep(SLEEP_MICROS)
        }
        depth = 1
    }

    public actual fun unlock() {
        if (--depth == 0) owner.value = 0
    }

    private companion object {
        /**
         * CAS attempts before yielding the core. Uncontended and short-hold
         * acquisitions (the only kind this lock is used for) succeed well
         * inside this budget, so the yield syscall stays off the fast path.
         */
        private const val SPIN_BUDGET = 64

        /** Yields before the waiter sleeps: about the time of a few map operations under load. */
        private const val YIELD_BUDGET = 32

        /** One step of sleep while a long holder keeps the lock. */
        private const val SLEEP_MICROS = 500u
    }
}

// pthread_t is a pointer on Apple and an integer elsewhere; hashCode is the
// one portable projection. A collision merely risks one spurious "cyclic
// reference" null-cache if two colliding threads race the same object:
// lenient degradation, astronomically unlikely. 0 is reserved for "unlocked".
@OptIn(ExperimentalForeignApi::class)
public actual fun currentThreadId(): Long {
    val h = pthread_self().hashCode().toLong()
    return if (h == 0L) 1L else h
}
