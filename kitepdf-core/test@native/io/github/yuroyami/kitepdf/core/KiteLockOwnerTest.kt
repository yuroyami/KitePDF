package io.github.yuroyami.kitepdf.core

import kotlin.native.concurrent.ObsoleteWorkersApi
import kotlin.native.concurrent.TransferMode
import kotlin.native.concurrent.Worker
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals

/** Each thread has an id of its own, and only the holder unlocks the lock (#773). */
class KiteLockOwnerTest {

    @OptIn(ObsoleteWorkersApi::class)
    @Test
    fun threadIdsAreUniqueAndStable() {
        val mine = currentThreadId()
        assertEquals(mine, currentThreadId())
        val workers = List(4) { Worker.start() }
        try {
            val ids = workers.map { w -> w.execute(TransferMode.SAFE, { }) { currentThreadId() }.result }
            assertEquals(ids.size, ids.toSet().size)
            for (id in ids) assertNotEquals(mine, id)
        } finally {
            workers.forEach { it.requestTermination().result }
        }
    }

    @OptIn(ObsoleteWorkersApi::class)
    @Test
    fun anotherThreadCannotUnlock() {
        val lock = KiteLock()
        lock.lock()
        val worker = Worker.start()
        try {
            val failed = worker.execute(TransferMode.SAFE, { lock }, ::unlockFails).result
            assertEquals(true, failed)
        } finally {
            worker.requestTermination().result
            lock.unlock()
        }
        assertFailsWith<IllegalStateException> { lock.unlock() }
    }
}

private fun unlockFails(lock: KiteLock): Boolean = try {
    lock.unlock()
    false
} catch (_: IllegalStateException) {
    true
}
