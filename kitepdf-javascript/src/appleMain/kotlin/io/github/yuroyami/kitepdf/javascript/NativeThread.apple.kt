@file:OptIn(ExperimentalForeignApi::class)

package io.github.yuroyami.kitepdf.javascript

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.StableRef
import kotlinx.cinterop.alloc
import kotlinx.cinterop.asStableRef
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.staticCFunction
import platform.posix.PTHREAD_CREATE_DETACHED
import platform.posix.pthread_attr_destroy
import platform.posix.pthread_attr_init
import platform.posix.pthread_attr_setdetachstate
import platform.posix.pthread_attr_setstacksize
import platform.posix.pthread_attr_t
import platform.posix.pthread_create
import platform.posix.pthread_tVar

// The same text in each native family, because pthread_t has a different type in each.
internal actual fun startNativeThread(stackBytes: Long, body: () -> Unit): Unit = memScoped {
    val attr = alloc<pthread_attr_t>()
    pthread_attr_init(attr.ptr)
    pthread_attr_setstacksize(attr.ptr, stackBytes.convert())
    pthread_attr_setdetachstate(attr.ptr, PTHREAD_CREATE_DETACHED)
    val ref = StableRef.create(body)
    val started = pthread_create(alloc<pthread_tVar>().ptr, attr.ptr, staticCFunction { arg ->
        val task = arg!!.asStableRef<() -> Unit>()
        val run = task.get()
        task.dispose()
        run()
        null
    }, ref.asCPointer())
    pthread_attr_destroy(attr.ptr)
    if (started != 0) {
        ref.dispose()
        error("could not start the script thread: error $started")
    }
}
