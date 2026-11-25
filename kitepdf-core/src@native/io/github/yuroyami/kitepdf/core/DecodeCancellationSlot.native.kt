package io.github.yuroyami.kitepdf.core

/** A thread's decode cancellation: the object is one for each thread (#905). */
@kotlin.native.concurrent.ThreadLocal
internal actual object DecodeCancellationSlot {
    actual var current: KiteCancellation? = null
}
