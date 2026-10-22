package io.github.yuroyami.kitepdf.compose

import java.awt.EventQueue
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CoroutineDispatcher

internal actual fun isHostTextThread(): Boolean = EventQueue.isDispatchThread()

internal actual fun hostTextDispatcher(): CoroutineDispatcher = HostTextDispatcher

/** AWT also supplies an event thread in headless mode; no Swing coroutine artifact is needed. */
private object HostTextDispatcher : CoroutineDispatcher() {
    override fun isDispatchNeeded(context: CoroutineContext): Boolean = !isHostTextThread()

    override fun dispatch(context: CoroutineContext, block: Runnable) {
        EventQueue.invokeLater(block)
    }
}
