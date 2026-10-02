package io.github.yuroyami.kitepdf.compose

import android.os.Looper
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

internal actual fun isHostTextThread(): Boolean =
    Looper.getMainLooper()?.let { it === Looper.myLooper() } ?: false

internal actual fun hostTextDispatcher(): CoroutineDispatcher = Dispatchers.Main.immediate
