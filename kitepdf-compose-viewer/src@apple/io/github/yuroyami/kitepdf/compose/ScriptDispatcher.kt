package io.github.yuroyami.kitepdf.compose

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO

/** A lane on the pool for blocking work, so a long script never holds a render worker. */
internal actual fun newScriptLane(): CoroutineDispatcher = Dispatchers.IO.limitedParallelism(1)
