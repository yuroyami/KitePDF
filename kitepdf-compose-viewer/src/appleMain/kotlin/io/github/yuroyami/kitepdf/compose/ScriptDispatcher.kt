package io.github.yuroyami.kitepdf.compose

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

/**
 * One thread, and the same one every time: an engine belongs to the thread that opened it, and
 * a script that runs for a minute must not be the thread the reader is waiting on.
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
private val scriptThread: CoroutineDispatcher = Dispatchers.Default.limitedParallelism(1)

internal actual fun kitepdfScriptDispatcher(): CoroutineDispatcher = scriptThread
