package io.github.yuroyami.kitepdf.compose

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import platform.Foundation.NSThread

internal actual fun isHostTextThread(): Boolean = NSThread.isMainThread

internal actual fun hostTextDispatcher(): CoroutineDispatcher = Dispatchers.Main.immediate
