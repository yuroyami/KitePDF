package io.github.yuroyami.kitepdf.core

/** A thread's innermost warning scope, in a ThreadLocal (#899). */
internal actual object WarningScopeSlot {
    private val local = ThreadLocal<KiteWarningScope.Frame?>()
    actual var current: KiteWarningScope.Frame?
        get() = local.get()
        set(value) { if (value == null) local.remove() else local.set(value) }
}
