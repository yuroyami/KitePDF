package io.github.yuroyami.kitepdf.compose

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

/** The pool for blocking work, one call at a time, so a long script never holds a render worker. */
private val scriptCalls: CoroutineDispatcher = Dispatchers.IO.limitedParallelism(1)

internal actual fun kitepdfScriptDispatcher(): CoroutineDispatcher = scriptCalls
