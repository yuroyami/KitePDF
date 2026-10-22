package io.github.yuroyami.kitepdf.core

import java.util.concurrent.locks.ReentrantLock

public actual class KiteLock actual constructor() {
    private val lock = ReentrantLock()
    public actual fun lock(): Unit = lock.lock()
    public actual fun unlock(): Unit = lock.unlock()
}

public actual fun currentThreadId(): Long = Thread.currentThread().id
