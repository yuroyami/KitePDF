package io.github.yuroyami.kitepdf.compose

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.font.createFontFamilyResolver
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import io.github.yuroyami.kitepdf.core.KitePage
import io.github.yuroyami.kitepdf.core.render.KiteCanvas
import io.github.yuroyami.kitepdf.core.render.KiteMatrix
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The render gate that replaced the one process-wide raster lock (#370). */
class RasterGateTest {

    @Test
    fun a_free_slot_goes_to_the_most_urgent_raster_that_waits() = runBlocking {
        val gate = RasterGate(2)
        val order = mutableListOf<String>()
        val holds = List(2) { CompletableDeferred<Unit>() }
        val jobs = holds.mapIndexed { i, hold -> launch { gate.withPermit({ RasterPriority.NEAR }) { order += "hold$i"; hold.await() } } }.toMutableList()
        repeat(3) { i -> jobs += launch { gate.withPermit({ RasterPriority.NEAR }) { order += "near$i" } } }
        jobs += launch { gate.withPermit({ RasterPriority.THUMBNAIL }) { order += "thumbnail" } }
        jobs += launch { gate.withPermit({ RasterPriority.VISIBLE }) { order += "visible" } }
        while (gate.waitingCount < 5) yield()
        holds.forEach { it.complete(Unit) }
        jobs.joinAll()
        assertEquals(listOf("hold0", "hold1", "visible", "near0", "near1", "near2", "thumbnail"), order)
    }

    @Test
    fun a_raster_that_comes_into_view_while_it_waits_moves_ahead() = runBlocking {
        val gate = RasterGate(1)
        val order = mutableListOf<String>()
        val hold = CompletableDeferred<Unit>()
        var shown = false
        val jobs = listOf(
            launch { gate.withPermit({ RasterPriority.NEAR }) { hold.await() } },
            launch { gate.withPermit({ RasterPriority.NEAR }) { order += "drawn ahead" } },
            launch { gate.withPermit({ if (shown) RasterPriority.VISIBLE else RasterPriority.NEAR }) { order += "scrolled into view" } },
        )
        while (gate.waitingCount < 2) yield()
        shown = true
        hold.complete(Unit)
        jobs.joinAll()
        assertEquals(listOf("scrolled into view", "drawn ahead"), order)
    }

    @Test
    fun two_rasters_run_at_once_and_no_more() = runBlocking {
        val gate = RasterGate(2)
        val inside = AtomicInteger()
        val most = AtomicInteger()
        val bothIn = CountDownLatch(2)
        val jobs = List(4) {
            launch(Dispatchers.Default) {
                gate.withPermit({ RasterPriority.VISIBLE }) {
                    most.accumulateAndGet(inside.incrementAndGet(), ::maxOf)
                    bothIn.countDown()
                    // Each of the first two waits for the other, so this passes only when two run at once.
                    assertTrue(bothIn.await(10, TimeUnit.SECONDS), "a second raster did not start beside the first")
                    Thread.sleep(20)
                    inside.decrementAndGet()
                }
            }
        }
        jobs.joinAll()
        assertEquals(2, most.get())
    }

    @Test
    fun a_cancelled_raster_leaves_the_queue() = runBlocking {
        val gate = RasterGate(1)
        val hold = CompletableDeferred<Unit>()
        val holder = launch { gate.withPermit({ RasterPriority.VISIBLE }) { hold.await() } }
        var ran = false
        val waiter = launch { gate.withPermit({ RasterPriority.VISIBLE }) { ran = true } }
        while (gate.waitingCount < 1) yield()
        waiter.cancel()
        assertEquals(0, gate.waitingCount)
        hold.complete(Unit)
        holder.join()
        assertTrue(withTimeout(5_000) { gate.withPermit({ RasterPriority.VISIBLE }) { true } }, "the slot is free again")
        assertFalse(ran)
    }

    @Test
    fun a_raster_cancelled_after_it_got_the_slot_passes_the_slot_on() = runBlocking {
        val gate = RasterGate(1)
        val hold = CompletableDeferred<Unit>()
        val holder = launch { gate.withPermit({ RasterPriority.VISIBLE }) { hold.await() } }
        var ran = false
        val waiter = launch { gate.withPermit({ RasterPriority.VISIBLE }) { ran = true } }
        while (gate.waitingCount < 1) yield()
        hold.complete(Unit)
        // The holder runs to its end and hands the slot to the waiter, which has not run yet.
        yield()
        assertTrue(holder.isCompleted)
        waiter.cancel()
        waiter.join()
        assertFalse(ran)
        assertTrue(withTimeout(5_000) { gate.withPermit({ RasterPriority.VISIBLE }) { true } }, "the slot did not leak")
    }

    @Test
    fun a_page_on_screen_renders_before_the_pages_drawn_ahead_of_it() = runBlocking<Unit> {
        val density = Density(1f)
        val rasterizer = KitePageRasterizer(density, LayoutDirection.Ltr, TextMeasurer(createFontFamilyResolver(), density, LayoutDirection.Ltr))
        val started = Collections.synchronizedList(ArrayList<String>())
        val latches = HashMap<String, CountDownLatch>()
        fun page(name: String, slow: Boolean) = object : KitePage {
            override val displayWidth = 50.0
            override val displayHeight = 50.0
            override fun displayToDeviceBase() = KiteMatrix(1.0, 0.0, 0.0, -1.0, 0.0, 50.0)
            override fun renderTo(canvas: KiteCanvas, deviceCtm: KiteMatrix) {
                started += name
                if (slow) latches.getValue(name).await(20, TimeUnit.SECONDS)
            }
        }
        val gate = KitePageRasterizer.rasterGate
        withTimeout(30_000) {
            val ahead = (0 until 10).map { i ->
                latches["ahead$i"] = CountDownLatch(1)
                async(Dispatchers.Default) {
                    rasterizer.rasterizeCachedOrNull(null, page("ahead$i", slow = true), 50, 50, Color.White, 1f, null, i, priority = { RasterPriority.NEAR })
                }
            }
            while (started.size < 2 || gate.waitingCount < 8) delay(5)
            val visible = async(Dispatchers.Default) {
                rasterizer.rasterizeCachedOrNull(null, page("visible", slow = false), 50, 50, Color.White, 1f, null, 10, priority = { RasterPriority.VISIBLE })
            }
            while (gate.waitingCount < 9) delay(5)
            // One of the two running pages ends, and its slot goes to the page on screen.
            latches.getValue(started[0]).countDown()
            while ("visible" !in started) delay(5)
            assertEquals(2, started.indexOf("visible"), "the page on screen waited behind pages drawn ahead: $started")
            latches.values.forEach { it.countDown() }
            (ahead + visible).awaitAll()
        }
    }
}
