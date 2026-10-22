package io.github.yuroyami.kitepdf.core.render

import java.lang.ref.WeakReference
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** Cross-draw bitmap ownership and concurrent cache callbacks for #371. */
class KiteBitmapCacheLifetimeTest {
    private val sampling = imageSampling(64, 64, KiteMatrix(64.0, 0.0, 0.0, 64.0, 0.0, 0.0), false)

    private fun image(pixels: ByteArray = ByteArray(64 * 64)): KiteImageData = KiteImageData(
        64, 64, 8, "DeviceGray", KiteImageData.Kind.RAW, ByteArray(0),
        pixelBytes = pixels, resolvedColorSpace = KiteColorSpace.DeviceGray,
    )

    private fun seed(cache: KiteBitmapCache<Any>, identity: KiteImageIdentity): Pair<WeakReference<KiteImageData>, WeakReference<ByteArray>> {
        val pixels = ByteArray(1024 * 1024)
        val image = image(pixels).withIdentity(identity)
        cache.getOrPut(image, sampling, { 100L }) { Any() }
        return WeakReference(image) to WeakReference(pixels)
    }

    @Test
    fun retained_bitmap_does_not_retain_its_source_image_or_samples() {
        val cache = KiteBitmapCache<Any>()
        val identity = KiteImageIdentity().child("scan")
        val (source, pixels) = seed(cache, identity)
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while ((source.get() != null || pixels.get() != null) && System.nanoTime() < deadline) {
            System.gc()
            Thread.sleep(10)
        }
        assertNull(source.get(), "a retained bitmap key must not own the image")
        assertNull(pixels.get(), "a retained bitmap key must not own the decoded samples")
        assertEquals(100L, cache.heldBytes, "the bitmap must still be resident during the collection check")
        cache.getOrPut(image().withIdentity(identity), sampling, { 100L }) {
            error("a reconstructed resource must still find the retained bitmap")
        }
    }

    @Test
    fun concurrent_misses_build_without_the_lock_and_converge_on_one_retained_bitmap() {
        val cache = KiteBitmapCache<Any>()
        val image = image()
        val bothSized = CountDownLatch(2)
        val pool = Executors.newFixedThreadPool(2)
        try {
            val work = List(2) {
                pool.submit<Any?> {
                    cache.getOrPut(image, sampling, {
                        bothSized.countDown()
                        assertTrue(bothSized.await(5, TimeUnit.SECONDS), "size callbacks must run outside the lock")
                        100L
                    }) { Any() }
                }
            }
            assertSame(work[0].get(10, TimeUnit.SECONDS), work[1].get(10, TimeUnit.SECONDS))
            assertEquals(100L, cache.heldBytes)
        } finally {
            pool.shutdownNow()
            assertTrue(pool.awaitTermination(5, TimeUnit.SECONDS))
        }
    }

    @Test
    fun clear_during_a_conversion_prevents_late_repopulation() {
        val cache = KiteBitmapCache<Any>()
        val image = image()
        val building = CountDownLatch(1)
        val continueBuild = CountDownLatch(1)
        val pool = Executors.newSingleThreadExecutor()
        try {
            val work = pool.submit<Any?> {
                cache.getOrPut(image, sampling, { 100L }) {
                    building.countDown()
                    assertTrue(continueBuild.await(5, TimeUnit.SECONDS))
                    Any()
                }
            }
            assertTrue(building.await(5, TimeUnit.SECONDS))
            cache.clear()
            continueBuild.countDown()
            work.get(10, TimeUnit.SECONDS)
            assertEquals(0L, cache.heldBytes, "a cleared generation must stay empty")
            cache.getOrPut(image, sampling, { 100L }) { Any() }
            assertEquals(100L, cache.heldBytes)
        } finally {
            continueBuild.countDown()
            pool.shutdownNow()
            assertTrue(pool.awaitTermination(5, TimeUnit.SECONDS))
        }
    }
}
