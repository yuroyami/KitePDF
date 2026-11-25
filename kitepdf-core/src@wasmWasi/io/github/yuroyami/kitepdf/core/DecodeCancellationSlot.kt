package io.github.yuroyami.kitepdf.core

/** The decode cancellation: this target runs one thread (#905). */
internal actual object DecodeCancellationSlot {
    actual var current: KiteCancellation? = null
}
