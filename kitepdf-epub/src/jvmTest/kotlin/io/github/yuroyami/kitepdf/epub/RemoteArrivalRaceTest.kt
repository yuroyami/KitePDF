package io.github.yuroyami.kitepdf.epub

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * A resource whose bytes a caller can see has been counted in [RemoteResources.arrivals], so a
 * caller that waited for it reads the count it waited for (#567).
 */
class RemoteArrivalRaceTest {

    @Test
    fun bytes_that_have_landed_are_counted_by_the_time_a_request_sees_them() = runBlocking {
        val url = "https://example.com/pic.bmp"
        val bytes = EpubFixtures.bmp2x1()
        var stale = 0
        repeat(20_000) {
            val remote = RemoteResources(1)
            remote.request(url, { bytes })
            // Spin until a request finds the bytes landed, as fetchRemoteResources does.
            while (true) {
                val fetch = remote.request(url) { bytes } ?: break
                if (fetch.isCompleted) { fetch.await(); break }
            }
            if (remote.arrivals.value != 1) stale++
        }
        assertEquals(0, stale, "requests that found the bytes landed while arrivals still said none")
    }
}
