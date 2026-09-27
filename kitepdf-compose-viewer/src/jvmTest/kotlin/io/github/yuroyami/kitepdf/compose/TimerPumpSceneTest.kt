package io.github.yuroyami.kitepdf.compose

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.use
import io.github.yuroyami.kitepdf.PdfDocument
import io.github.yuroyami.kitepdf.PdfFormState
import io.github.yuroyami.kitepdf.PdfScriptHandler
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The script timer pump runs only while a timer waits: with none, the viewer asks for no frame,
 * and between two timers it sleeps until the next one is due (#368).
 */
class TimerPumpSceneTest {

    /** One empty 200 x 200 page. */
    private fun pagePdf(): ByteArray {
        val sb = StringBuilder("%PDF-1.7\n")
        val offsets = ArrayList<Int>()
        fun add(body: String) {
            offsets.add(sb.length)
            sb.append("${offsets.size} 0 obj\n$body\nendobj\n")
        }
        add("<< /Type /Catalog /Pages 2 0 R >>")
        add("<< /Type /Pages /Kids [3 0 R] /Count 1 /MediaBox [0 0 200 200] >>")
        add("<< /Type /Page /Parent 2 0 R /Resources << >> >>")
        val xref = sb.length
        sb.append("xref\n0 ${offsets.size + 1}\n0000000000 65535 f \n")
        for (offset in offsets) sb.append("${offset.toString().padStart(10, '0')} 00000 n \n")
        sb.append("trailer\n<< /Size ${offsets.size + 1} /Root 1 0 R >>\nstartxref\n$xref\n%%EOF\n")
        return sb.toString().encodeToByteArray()
    }

    /** A handler whose timer the test starts and stops, as a host's own script would. */
    private class Timers(document: PdfDocument) : PdfScriptHandler {
        override val formState: PdfFormState = PdfFormState(document)
        val pumps = AtomicInteger()
        private val listeners = CopyOnWriteArrayList<() -> Unit>()

        @Volatile private var active = false

        /** What [pumpTimers] answers: how long until the next timer is due. */
        @Volatile private var next = 0L

        override val hasTimers: Boolean get() = active

        override fun pumpTimers(nowMillis: Long): Long? {
            pumps.incrementAndGet()
            return if (active) next else null
        }

        override fun onTimersChanged(listener: () -> Unit): () -> Unit {
            listeners += listener
            return { listeners -= listener }
        }

        fun start(nextMillis: Long) {
            next = nextMillis
            active = true
            listeners.forEach { it() }
        }

        fun stop() {
            active = false
            listeners.forEach { it() }
        }
    }

    @Test
    fun with_no_timer_the_viewer_asks_for_no_frame() {
        forBothEffectOrders { queued ->
            val doc = PdfDocument.open(pagePdf())
            val scripts = Timers(doc)
            lateinit var state: KiteDocViewState
            val (scene, driver) = drivenScene(200, 200, queued) {
                state = rememberKiteDocViewState(doc)
                KiteDocView(state = state, modifier = Modifier.fillMaxSize(), scripts = scripts)
            }
            scene.use {
                driver.pumpUntilState { state.pageGeometry.isNotEmpty() }
                // Past the page's raster and its fade, which ask for frames of their own.
                driver.pumpFrames(120)
                assertFalse(scene.hasInvalidations(), "the viewer still asks for frames")
            }
        }
    }

    @Test
    fun a_timer_the_host_sets_is_pumped_until_it_stops() {
        forBothEffectOrders { queued ->
            val doc = PdfDocument.open(pagePdf())
            val scripts = Timers(doc)
            lateinit var state: KiteDocViewState
            val (scene, driver) = drivenScene(200, 200, queued) {
                state = rememberKiteDocViewState(doc)
                KiteDocView(state = state, modifier = Modifier.fillMaxSize(), scripts = scripts)
            }
            scene.use {
                driver.pumpUntilState { state.pageGeometry.isNotEmpty() }
                driver.pumpFrames(10)
                assertTrue(scripts.pumps.get() == 0, "pumped with no timer")
                // Due at once, and again on every frame, as `app.setInterval(code, 0)` asks.
                scripts.start(nextMillis = 0)
                driver.pumpUntilState { scripts.pumps.get() >= 5 }
                scripts.stop()
                driver.pumpFrames(5)
                val stopped = scripts.pumps.get()
                driver.pumpFrames(30)
                assertTrue(scripts.pumps.get() == stopped, "pumped after the timer stopped")
                driver.pumpFrames(60)
                assertFalse(scene.hasInvalidations(), "the viewer still asks for frames")
            }
        }
    }

    @Test
    fun a_timer_far_ahead_is_not_pumped_on_every_frame() {
        forBothEffectOrders { queued ->
            val doc = PdfDocument.open(pagePdf())
            val scripts = Timers(doc)
            lateinit var state: KiteDocViewState
            val (scene, driver) = drivenScene(200, 200, queued) {
                state = rememberKiteDocViewState(doc)
                KiteDocView(state = state, modifier = Modifier.fillMaxSize(), scripts = scripts)
            }
            scene.use {
                driver.pumpUntilState { state.pageGeometry.isNotEmpty() }
                scripts.start(nextMillis = 60_000)
                driver.pumpUntilState { scripts.pumps.get() >= 1 }
                driver.pumpFrames(40)
                assertTrue(scripts.pumps.get() <= 2, "pumped ${scripts.pumps.get()} times for a timer a minute away")
                scripts.stop()
            }
        }
    }
}
