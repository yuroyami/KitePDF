package io.github.yuroyami.kitepdf.core

import kotlin.concurrent.AtomicLong
import kotlinx.cinterop.ExperimentalForeignApi
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

    /** Fails for a thread that does not hold the lock, as the JVM's ReentrantLock does (#773). */
    public actual fun unlock() {
        check(owner.value == currentThreadId()) { "KiteLock.unlock from a thread that does not hold it" }
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

/**
 * A number that each thread takes from [nextThreadId] on its first call and keeps, so two live
 * threads never share one. The hash of pthread_self folded a 64-bit value to 32 bits, and two
 * threads with one hash both passed [KiteLock]'s reentrancy check and ran inside the lock together,
 * over the caches of documents, fonts and bitmaps it guards, and PdfDocument's cycle claims (#773).
 * 0 is reserved for "unlocked".
 */
public actual fun currentThreadId(): Long {
    val known = threadId
    if (known != 0L) return known
    return nextThreadId.addAndGet(1).also { threadId = it }
}

@kotlin.native.concurrent.ThreadLocal
private var threadId: Long = 0L

private val nextThreadId = AtomicLong(0)
