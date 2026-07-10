package io.github.yuroyami.kitepdf.core

import java.util.concurrent.locks.ReentrantLock

actual class KiteLock actual constructor() {
    private val lock = ReentrantLock()
    actual fun lock() = lock.lock()
    actual fun unlock() = lock.unlock()
}

actual fun currentThreadId(): Long = Thread.currentThread().id
