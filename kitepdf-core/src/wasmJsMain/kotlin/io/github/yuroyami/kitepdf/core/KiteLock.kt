package io.github.yuroyami.kitepdf.core

/** Single-threaded target: locking is a no-op. */
actual class KiteLock actual constructor() {
    actual fun lock() {}
    actual fun unlock() {}
}

actual fun currentThreadId(): Long = 0L
