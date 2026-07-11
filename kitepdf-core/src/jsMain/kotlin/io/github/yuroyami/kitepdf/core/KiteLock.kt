package io.github.yuroyami.kitepdf.core

/** Single-threaded target: locking is a no-op. */
public actual class KiteLock actual constructor() {
    public actual fun lock() {}
    public actual fun unlock() {}
}

public actual fun currentThreadId(): Long = 0L
