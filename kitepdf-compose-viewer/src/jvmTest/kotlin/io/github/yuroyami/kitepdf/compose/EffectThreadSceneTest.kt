package io.github.yuroyami.kitepdf.compose

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MonotonicFrameClock
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.unit.dp
import io.github.yuroyami.kitepdf.compose.EdtImageComposeScene as ImageComposeScene
import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.layout.layout
import io.github.yuroyami.kitepdf.PdfDocument
import io.github.yuroyami.kitepdf.PdfFormState
import io.github.yuroyami.kitepdf.PdfScriptHandler
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotSame
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * The viewer's effects go on on the composition's thread after a wait that another thread
 * ended, whatever dispatcher runs them. Under the default `ImageComposeScene` dispatcher they
 * went on on the script thread, took snapshots there, and ran Compose's layout observers on it
 * (#443).
 */
class EffectThreadSceneTest {

    /** A PDF whose objects are [bodies], numbered from 1. Object 1 is the catalog. */
    private fun pdf(bodies: List<String>): ByteArray {
        val sb = StringBuilder("%PDF-1.7\n")
        val offsets = ArrayList<Int>()
        for ((index, body) in bodies.withIndex()) {
            offsets.add(sb.length)
            sb.append("${index + 1} 0 obj\n$body\nendobj\n")
        }
        val xref = sb.length
        sb.append("xref\n0 ${offsets.size + 1}\n0000000000 65535 f \n")
        for (offset in offsets) sb.append("${offset.toString().padStart(10, '0')} 00000 n \n")
        sb.append("trailer\n<< /Size ${offsets.size + 1} /Root 1 0 R >>\nstartxref\n$xref\n%%EOF\n")
        return sb.toString().encodeToByteArray()
    }

    /**
     * [count] empty 200 x 200 pages. With [goToLast], page 1 has a push button at display
     * (20..90, 140..180) that goes to the last page.
     */
    private fun pagesPdf(count: Int, goToLast: Boolean = false): PdfDocument {
        val widget = count + 3
        val kids = (0 until count).joinToString(" ") { "${it + 3} 0 R" }
        val bodies = ArrayList<String>()
        bodies += if (goToLast) "<< /Type /Catalog /Pages 2 0 R /AcroForm << /Fields [$widget 0 R] >> >>" else "<< /Type /Catalog /Pages 2 0 R >>"
        bodies += "<< /Type /Pages /Kids [$kids] /Count $count /MediaBox [0 0 200 200] >>"
        repeat(count) { page ->
            val annots = if (goToLast && page == 0) " /Annots [$widget 0 R]" else ""
            bodies += "<< /Type /Page /Parent 2 0 R /Resources << >>$annots >>"
        }
        if (goToLast) {
            bodies += "<< /Type /Annot /Subtype /Widget /FT /Btn /Ff 65536 /T (go) /Rect [20 20 90 60] " +
                "/A << /S /GoTo /D [${count + 2} 0 R /Fit] >> >>"
        }
        return PdfDocument.open(pdf(bodies))
    }

    /** A handler with no scripts of its own, so the viewer gets a script thread. */
    private open class Scripts(document: PdfDocument) : PdfScriptHandler {
        override val formState: PdfFormState = PdfFormState(document)
    }

    /**
     * One 200 x 200 page with a push button `press` at display (20..90, 140..180) and a text field
     * `out` at display (20..180, 40..80).
     */
    private fun formPdf(): PdfDocument = PdfDocument.open(
        pdf(
            listOf(
                "<< /Type /Catalog /Pages 2 0 R /AcroForm << /Fields [4 0 R 5 0 R] >> >>",
                "<< /Type /Pages /Kids [3 0 R] /Count 1 /MediaBox [0 0 200 200] >>",
                "<< /Type /Page /Parent 2 0 R /Resources << >> /Annots [4 0 R 5 0 R] >>",
                "<< /Type /Annot /Subtype /Widget /FT /Btn /Ff 65536 /T (press) /Rect [20 20 90 60] >>",
                "<< /Type /Annot /Subtype /Widget /FT /Tx /T (out) /V () /Rect [20 120 180 160] /DA (/Helv 12 Tf 0 g) >>",
            ),
        ),
    )

    /** Stands in for a field script that writes capitals and a button script that writes the field. */
    private class FormScripts(document: PdfDocument) : Scripts(document) {
        override fun keystroke(fieldName: String, change: String, selectionStart: Int, selectionEnd: Int): String {
            val current = formState.value(fieldName) ?: ""
            val start = selectionStart.coerceIn(0, current.length)
            return current.substring(0, start) + change.uppercase() + current.substring(selectionEnd.coerceIn(start, current.length))
        }

        override fun mouseUp(fieldName: String, widgetIndex: Int) {
            if (fieldName == "press") formState.setValue("out", "PRESSED")
        }
    }

    private fun tap(scene: ImageComposeScene, x: Float, y: Float) {
        scene.sendPointerEvent(PointerEventType.Press, Offset(x, y), type = PointerType.Touch)
        scene.sendPointerEvent(PointerEventType.Release, Offset(x, y), type = PointerType.Touch)
    }

    /** Types [char] into the focused node as a desktop keyboard does: a typed event from AWT. */
    @OptIn(InternalComposeUiApi::class)
    private fun type(scene: ImageComposeScene, char: Char) {
        val awt = java.awt.event.KeyEvent(
            javax.swing.JLabel(), java.awt.event.KeyEvent.KEY_TYPED, 0L, 0, java.awt.event.KeyEvent.VK_UNDEFINED, char,
        )
        scene.sendKeyEvent(KeyEvent(Key.Unknown, KeyEventType.Unknown, codePoint = char.code, nativeEvent = awt))
    }

    /**
     * Records the thread of each apply notification that carries [probe]: the thread that took the
     * first snapshot after a write to it.
     */
    private fun <T> recordApplies(probe: Any, body: (threads: List<Thread>) -> T): T {
        val threads = Collections.synchronizedList(ArrayList<Thread>())
        val observer = Snapshot.registerApplyObserver { changed, _ -> if (probe in changed) threads += Thread.currentThread() }
        return try {
            body(threads)
        } finally {
            observer.dispose()
        }
    }

    @Test
    fun back_on_compose_thread_waits_for_a_frame_only_under_a_dispatcher_that_never_dispatches() {
        class CountingClock : MonotonicFrameClock {
            var frames = 0
            override suspend fun <R> withFrameNanos(onFrame: (Long) -> R): R {
                frames++
                return onFrame(0L)
            }
        }
        val inPlace = CountingClock()
        runBlocking(Dispatchers.Unconfined + inPlace) { backOnComposeThread() }
        assertEquals(1, inPlace.frames, "a dispatcher that never dispatches waits for a frame")
        val dispatching = CountingClock()
        runBlocking(dispatching) { backOnComposeThread() }
        assertEquals(0, dispatching.frames, "a dispatcher that dispatches has brought the caller back already")
        // With no frame clock there is no composition to go back to.
        runBlocking(Dispatchers.Unconfined) { backOnComposeThread() }
    }

    @Test
    fun an_effect_is_back_on_the_composition_thread_after_a_pool_hop() {
        forBothEffectOrders { queued ->
            val stranded = AtomicReference<Thread?>()
            val back = AtomicReference<Thread?>()
            val (scene, driver) = drivenScene(10, 10, queued) {
                LaunchedEffect(Unit) {
                    withContext(Dispatchers.Default) {}
                    stranded.set(Thread.currentThread())
                    backOnComposeThread()
                    back.set(Thread.currentThread())
                }
            }
            scene.use {
                driver.pumpUntilState { back.get() != null }
                assertSame(onTestUiThread { Thread.currentThread() }, back.get())
                // The premise: under the default dispatcher the effect goes on on the pool thread.
                if (!queued) assertNotSame(onTestUiThread { Thread.currentThread() }, stranded.get())
            }
        }
    }

    @Test
    fun the_page_scripts_first_look_at_the_reader_from_the_composition_thread() {
        val doc = pagesPdf(2)
        val probe = mutableStateOf(0)
        val firstFrame = CountDownLatch(1)
        val opened = CountDownLatch(1)
        val scripts = object : Scripts(doc) {
            override fun documentOpened() {
                firstFrame.await(10, TimeUnit.SECONDS)
                // A change that the next snapshot applies, on the thread that takes it.
                probe.value = 1
                opened.countDown()
            }
        }
        recordApplies(probe) { threads ->
            // The default order only: an app's order dispatches, so it cannot leave an effect on the script thread.
            val (scene, driver) = drivenScene(200, 200, queued = false) {
                KiteDocView(
                    state = rememberKiteDocViewState(doc),
                    modifier = Modifier.fillMaxSize(),
                    layout = KiteDocLayout.Paged(),
                    scripts = scripts,
                )
            }
            scene.use {
                driver.pumpFrames(0)
                firstFrame.countDown()
                assertTrue(opened.await(10, TimeUnit.SECONDS), "the open script ran")
                // No frame for a while, so a snapshot taken meanwhile comes from another thread.
                Thread.sleep(300)
                driver.pumpUntilState { threads.isNotEmpty() }
                assertEquals(listOf(onTestUiThread { Thread.currentThread() }), threads.toList())
            }
        }
    }

    @Test
    fun the_page_scripts_look_again_from_the_composition_thread_after_a_script() {
        val doc = pagesPdf(3)
        val probe = mutableStateOf(0)
        val inFirstPage = CountDownLatch(1)
        val leaveFirstPage = CountDownLatch(1)
        val scripts = object : Scripts(doc) {
            override fun pageOpened(pageIndex: Int) {
                if (pageIndex != 0) return
                inFirstPage.countDown()
                leaveFirstPage.await(10, TimeUnit.SECONDS)
                probe.value = 1
            }
        }
        recordApplies(probe) { threads ->
            lateinit var state: KiteDocViewState
            val (scene, driver) = drivenScene(200, 200, queued = false) {
                state = rememberKiteDocViewState(doc)
                KiteDocView(state = state, modifier = Modifier.fillMaxSize(), layout = KiteDocLayout.Paged(), scripts = scripts)
            }
            scene.use {
                driver.pumpUntilState { inFirstPage.count == 0L }
                // The reader moves while the script runs, so the page scripts have a change to look at after it.
                driver.runOnUi { state.scrollToPage(1) }
                driver.pumpFrames(3)
                leaveFirstPage.countDown()
                Thread.sleep(300)
                driver.pumpUntilState { threads.isNotEmpty() }
                assertEquals(listOf(onTestUiThread { Thread.currentThread() }), threads.toList())
            }
        }
    }

    @Test
    fun a_widget_action_moves_the_viewer_from_the_composition_thread() {
        forBothEffectOrders { queued ->
            val doc = pagesPdf(8, goToLast = true)
            val measuredOn = Collections.synchronizedSet(HashSet<Thread>())
            lateinit var state: KiteDocViewState
            val (scene, driver) = drivenScene(200, 200, queued) {
                state = rememberKiteDocViewState(doc)
                KiteDocView(
                    state = state,
                    modifier = Modifier.fillMaxSize(),
                    layout = KiteDocLayout.Paged(),
                    zoomSpec = KiteZoomSpec(doubleTapEnabled = false),
                    scripts = Scripts(doc),
                    // A page far off is composed and measured by the jump, before its raster lands.
                    pagePlaceholder = {
                        Box(
                            Modifier.fillMaxSize().layout { measurable, constraints ->
                                measuredOn += Thread.currentThread()
                                val placeable = measurable.measure(constraints)
                                layout(placeable.width, placeable.height) { placeable.place(0, 0) }
                            },
                        )
                    },
                )
            }
            scene.use {
                driver.pumpUntilState { state.pageGeometry.isNotEmpty() }
                scene.sendPointerEvent(PointerEventType.Press, Offset(55f, 160f), type = PointerType.Touch)
                scene.sendPointerEvent(PointerEventType.Release, Offset(55f, 160f), type = PointerType.Touch)
                // No frame for a while, so a jump made meanwhile comes from another thread.
                Thread.sleep(300)
                driver.pumpUntilState { state.currentPage == 7 }
                assertEquals(setOf(onTestUiThread { Thread.currentThread() }), measuredOn.toSet())
            }
        }
    }

    @Test
    fun a_script_answer_is_published_on_the_composition_thread() {
        // The default order only: an app's order dispatches, so it cannot leave an effect on the script thread.
        val doc = formPdf()
        val scripts = FormScripts(doc)
        lateinit var state: KiteDocViewState
        val (scene, driver) = drivenScene(200, 200, queued = false) {
            state = rememberKiteDocViewState(doc)
            KiteDocView(
                state = state,
                modifier = Modifier.fillMaxSize(),
                zoomSpec = KiteZoomSpec(doubleTapEnabled = false),
                scripts = scripts,
            )
        }
        scene.use {
            // The page raster lands first, from the raster thread.
            driver.pumpUntilState { state.pageRenderState(0) == KitePageRenderState.Ready }
            driver.pumpFrames(5)
            val offThread = viewerWritesOffThread {
                // A key that the field's script answers with a capital: the field shows the answer.
                tap(scene, 100f, 60f)
                driver.pumpUntilState { state.focusedField == "out" }
                driver.pumpFrames(4)
                type(scene, 'a')
                driver.pumpUntilState { scripts.formState.value("out") == "A" }
                driver.pumpFrames(10)
                // A button whose script writes the field: the form layer repaints.
                tap(scene, 55f, 160f)
                driver.pumpUntilState { scripts.formState.value("out") == "PRESSED" }
                driver.pumpFrames(10)
            }
            assertEquals(emptyList(), offThread)
        }
    }

    @Test
    fun rasters_and_a_resize_land_on_the_composition_thread() {
        // The default order only: an app's order dispatches, so it cannot leave an effect on the pool.
        val doc = pagesPdf(3)
        var shown by mutableStateOf(false)
        var width by mutableStateOf(200)
        lateinit var state: KiteDocViewState
        val (scene, driver) = drivenScene(300, 300, queued = false) {
            state = rememberKiteDocViewState(doc)
            if (shown) {
                Column {
                    Box(Modifier.size(width.dp, 200.dp)) {
                        KiteDocView(state = state, modifier = Modifier.fillMaxSize(), layout = KiteDocLayout.SinglePage(0))
                    }
                    KiteThumbnailStrip(state)
                }
            }
        }
        scene.use {
            val offThread = viewerWritesOffThread {
                shown = true
                driver.pumpUntilState { state.pageRenderState(0) == KitePageRenderState.Ready }
                // The new size settles after a pause, and the page renders again at it.
                width = 150
                driver.pumpFrames(2)
                Thread.sleep(400)
                driver.pumpFrames(20)
            }
            assertEquals(emptyList(), offThread)
        }
    }

    @Test
    fun commit_focused_field_returns_on_the_composition_thread() {
        forBothEffectOrders { queued ->
            val doc = pagesPdf(1)
            val returnedOn = AtomicReference<Thread?>()
            lateinit var state: KiteDocViewState
            lateinit var scope: CoroutineScope
            val (scene, driver) = drivenScene(200, 200, queued) {
                state = rememberKiteDocViewState(doc)
                scope = rememberCoroutineScope()
                KiteDocView(state = state, modifier = Modifier.fillMaxSize(), scripts = Scripts(doc))
            }
            scene.use {
                driver.pumpUntilState { state.pageGeometry.isNotEmpty() }
                onTestUiThread {
                    scope.launch {
                        state.commitFocusedField()
                        returnedOn.set(Thread.currentThread())
                    }
                }
                driver.pumpUntilState { returnedOn.get() != null }
                assertSame(onTestUiThread { Thread.currentThread() }, returnedOn.get())
            }
        }
    }
}
