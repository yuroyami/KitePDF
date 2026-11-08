package io.github.yuroyami.kitepdf.core

/** A thread's innermost warning scope: the object is one for each thread (#899). */
@kotlin.native.concurrent.ThreadLocal
internal actual object WarningScopeSlot {
    actual var current: KiteWarningScope.Frame? = null
}
