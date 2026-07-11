package io.github.yuroyami.kitepdf.core

/**
 * A minimal reentrant mutual-exclusion lock (T-16). JVM/Android wrap
 * `ReentrantLock`, native targets a reentrant atomic spinlock, JS/Wasm are
 * single-threaded no-ops. Use through [withLock].
 *
 * Engine-internal plumbing that must cross module boundaries (the handlers
 * synchronize their document caches with it); not a public concurrency API.
 */
public expect class KiteLock() {
    public fun lock()
    public fun unlock()
}

public inline fun <T> KiteLock.withLock(block: () -> T): T {
    lock()
    try {
        return block()
    } finally {
        unlock()
    }
}

/**
 * A stable identifier for the calling thread (0 on single-threaded targets).
 * Lets cycle guards distinguish same-thread reentrancy (a real cycle) from
 * another thread legitimately resolving the same object concurrently.
 */
public expect fun currentThreadId(): Long
