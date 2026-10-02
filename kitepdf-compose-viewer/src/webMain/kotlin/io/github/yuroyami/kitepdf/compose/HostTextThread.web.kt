package io.github.yuroyami.kitepdf.compose

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

// The supported browser JS and Wasm targets execute Compose and exports on one thread.
internal actual fun isHostTextThread(): Boolean = true

internal actual fun hostTextDispatcher(): CoroutineDispatcher = Dispatchers.Main.immediate
