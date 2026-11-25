package io.github.yuroyami.kitepdf.core

/** A thread's decode cancellation, in a ThreadLocal (#905). */
internal actual object DecodeCancellationSlot {
    private val local = ThreadLocal<KiteCancellation?>()
    actual var current: KiteCancellation?
        get() = local.get()
        set(value) { if (value == null) local.remove() else local.set(value) }
}
