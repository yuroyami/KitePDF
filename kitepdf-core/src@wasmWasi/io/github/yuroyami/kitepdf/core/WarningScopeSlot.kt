package io.github.yuroyami.kitepdf.core

/** The innermost warning scope: this target runs one thread (#899). */
internal actual object WarningScopeSlot {
    actual var current: KiteWarningScope.Frame? = null
}
