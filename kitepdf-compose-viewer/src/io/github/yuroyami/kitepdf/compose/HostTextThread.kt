package io.github.yuroyami.kitepdf.compose

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** The thread that also owns Compose UI text, including its shared style cache (#428). */
internal expect fun isHostTextThread(): Boolean

/** Dispatches to the verified platform text thread even without a coroutine Main provider. */
internal expect fun hostTextDispatcher(): CoroutineDispatcher

internal fun requireHostTextThread() {
    check(isHostTextThread()) {
        "Compose text rendering requires the platform UI thread (AWT event dispatch thread on desktop JVM). " +
            "Use KitePageRasterizer.rasterizeOffMain for a background export."
    }
}

/** Never blocks a worker waiting for UI; cancelled queued work cannot start a text pass. */
internal suspend fun <T> onHostTextThread(block: () -> T): T = withContext(hostTextDispatcher()) {
    ensureActive()
    requireHostTextThread()
    block().also { ensureActive() }
}
