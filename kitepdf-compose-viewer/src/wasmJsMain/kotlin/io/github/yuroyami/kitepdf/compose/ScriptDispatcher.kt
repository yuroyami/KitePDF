package io.github.yuroyami.kitepdf.compose

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

/** One thread on this target, so the scripts share it with the rest of the page. */
internal actual fun kitepdfScriptDispatcher(): CoroutineDispatcher = Dispatchers.Main
