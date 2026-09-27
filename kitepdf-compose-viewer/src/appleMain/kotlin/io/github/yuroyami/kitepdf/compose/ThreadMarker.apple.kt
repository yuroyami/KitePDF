package io.github.yuroyami.kitepdf.compose

import platform.Foundation.NSThread

internal actual fun currentThreadMarker(): Any = NSThread.currentThread
