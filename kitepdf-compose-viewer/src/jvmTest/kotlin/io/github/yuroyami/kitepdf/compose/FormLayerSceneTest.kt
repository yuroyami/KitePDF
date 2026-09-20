package io.github.yuroyami.kitepdf.compose

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.Density
import androidx.compose.ui.use
import io.github.yuroyami.kitepdf.PdfDocument
import io.github.yuroyami.kitepdf.PdfFormState
import io.github.yuroyami.kitepdf.PdfScriptHandler
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * A form in the viewer: a tap on a widget reaches the document's scripts, a value they write is
 * drawn without rasterizing the page again, and the timers they set are pumped a frame at a time.
 *
 * The handler here is a stand-in rather than the real engine, because the viewer must work with
 * any implementation of [PdfScriptHandler] and must not carry a JavaScript engine of its own.
 */
class FormLayerSceneTest {

    /** One page, 200 by 200, with a push button and a text field, neither carrying an `/AP`. */
    private fun formPdf(): ByteArray {
        val sb = StringBuilder()
        val offsets = ArrayList<Int>()
        fun add(s: String) {
            offsets.add(sb.length)
            sb.append(s)
        }
        sb.append("%PDF-1.7\n")
        add("1 0 obj\n<< /Type /Catalog /Pages 2 0 R /AcroForm << /Fields [4 0 R 5 0 R] >> >>\nendobj\n")
        add("2 0 obj\n<< /Type /Pages /Kids [3 0 R] /Count 1 /MediaBox [0 0 200 200] >>\nendobj\n")
        add("3 0 obj\n<< /Type /Page /Parent 2 0 R /Resources << >> /Annots [4 0 R 5 0 R] >>\nendobj\n")
        add(
            "4 0 obj\n<< /Type /Annot /Subtype /Widget /FT /Btn /Ff 65536 /T (press) " +
                "/Rect [20 20 90 60] /MK << /BG [0.8] /CA (Go) >> >>\nendobj\n",
        )
        add(
            "5 0 obj\n<< /Type /Annot /Subtype /Widget /FT /Tx /T (out) /V () " +
                "/Rect [20 120 180 160] /DA (/Helv 12 Tf 0 g) >>\nendobj\n",
        )
        val xref = sb.length
        sb.append("xref\n0 ${offsets.size + 1}\n0000000000 65535 f \n")
        for (off in offsets) sb.append("${off.toString().padStart(10, '0')} 00000 n \n")
        sb.append("trailer\n<< /Size ${offsets.size + 1} /Root 1 0 R >>\nstartxref\n$xref\n%%EOF\n")
        return sb.toString().encodeToByteArray()
    }

    /** A handler that behaves like a document whose button writes a field and starts a timer. */
    private class FakeScripts(document: PdfDocument) : PdfScriptHandler {
        override val formState: PdfFormState = PdfFormState(document)
        val events = mutableListOf<String>()
        var ticks = 0
        private var timerDue: Long? = null

        override fun documentOpened() { events.add("open") }
        override fun pageOpened(pageIndex: Int) { events.add("page $pageIndex") }
        override fun mouseDown(fieldName: String) { events.add("down $fieldName") }
        override fun mouseUp(fieldName: String) {
            events.add("up $fieldName")
            // Only the button writes and starts the timer, as its own script would.
            if (fieldName == "press") {
                formState.setValue("out", "pressed")
                timerDue = 0
            }
        }

        /** Stands in for a keystroke script that only takes digits. */
        override fun keystroke(fieldName: String, change: String, selectionStart: Int, selectionEnd: Int): String? {
            if (change.any { !it.isDigit() }) return null
            val current = formState.value(fieldName) ?: ""
            val start = selectionStart.coerceIn(0, current.length)
            val end = selectionEnd.coerceIn(start, current.length)
            return current.substring(0, start) + change + current.substring(end)
        }

        override fun commit(fieldName: String, value: String): Boolean {
            events.add("commit $fieldName=$value")
            formState.setValue(fieldName, value)
            return true
        }

        override fun focus(fieldName: String) { events.add("focus $fieldName") }
        override fun blur(fieldName: String) { events.add("blur $fieldName") }

        override val hasTimers: Boolean get() = timerDue != null
        override fun pumpTimers(nowMillis: Long): Long? {
            val due = timerDue ?: return null
            if (nowMillis < due) return due - nowMillis
            ticks++
            formState.setValue("out", "tick $ticks")
            timerDue = if (ticks < 3) nowMillis + 10 else null
            return timerDue?.minus(nowMillis)
        }
    }

    @Test
    fun a_tap_on_a_widget_reaches_the_scripts_and_the_form_redraws() {
        val doc = PdfDocument.open(formPdf())
        val scripts = FakeScripts(doc)
        lateinit var state: KiteDocViewState
        ImageComposeScene(width = 200, height = 200, density = Density(1f)) {
            state = rememberKiteDocViewState(doc)
            KiteDocView(state = state, modifier = Modifier.fillMaxSize(), scripts = scripts)
        }.use { scene ->
            val driver = SceneTestDriver(scene)
            driver.pumpUntil { state.pageGeometry.isNotEmpty() }
            driver.pumpUntil { scripts.events.contains("open") }
            assertTrue(scripts.events.contains("page 0"), "the page's own trigger fired: ${scripts.events}")

            // The button is [20..90] x [20..60] in user space, so display y 140..180: centre (55, 160).
            assertTrue(handleWidgetTap(state, scripts, Offset(55f, 160f)), "the button consumed the tap")
            assertEquals(listOf("down press", "up press"), scripts.events.drop(2))
            assertEquals("pressed", scripts.formState.value("out"))
            // handleWidgetTap with no scope runs the press where it stands, which is what keeps
            // this assertion in order; the viewer passes its own scope and posts instead.

            // The timer the press started is pumped by the viewer, a frame at a time.
            driver.pumpUntil { scripts.ticks >= 3 }
            assertEquals("tick 3", scripts.formState.value("out"))
            assertFalse(scripts.hasTimers, "the timer stopped itself")

            // A tap on empty page space is not a widget.
            assertFalse(handleWidgetTap(state, scripts, Offset(150f, 190f)))
        }
    }

    @Test
    fun a_tap_on_a_text_field_takes_the_caret_and_typing_goes_through_the_scripts() {
        val doc = PdfDocument.open(formPdf())
        val scripts = FakeScripts(doc)
        lateinit var state: KiteDocViewState
        ImageComposeScene(width = 200, height = 200, density = Density(1f)) {
            state = rememberKiteDocViewState(doc)
            KiteDocView(state = state, modifier = Modifier.fillMaxSize(), scripts = scripts)
        }.use { scene ->
            val driver = SceneTestDriver(scene)
            driver.pumpUntil { state.pageGeometry.isNotEmpty() }

            // The text field is [20..180] x [120..160] user space, so display y 40..80: centre (100, 60).
            assertTrue(handleWidgetTap(state, scripts, Offset(100f, 60f)), "the field consumed the tap")
            assertEquals("out", state.focusedField)
            // The focus script runs on the script thread, so the viewer does not wait for it.
            driver.pumpUntil { scripts.events.contains("focus out") }

            // A digit is taken and a letter is refused, as the field's keystroke script says.
            assertEquals("4", scripts.keystroke("out", "4", 0, 0))
            scripts.formState.setValue("out", "4")
            assertEquals(null, scripts.keystroke("out", "x", 1, 1), "a letter is refused")

            // Leaving the field commits it, which is what runs validate, calculate and format.
            state.blurFocusedField()
            assertEquals(null, state.focusedField)
            driver.pumpUntil { scripts.events.contains("commit out=4") }
            driver.pumpUntil { scripts.events.contains("blur out") }
        }
    }

    /** An edit is reduced to the text put in and the range it replaces, which is what a script sees. */
    @Test
    fun an_edit_reduces_to_one_insertion_or_deletion() {
        editOf("", "w").let { assertEquals("w", it.change); assertEquals(0, it.start); assertEquals(0, it.end) }
        editOf("12", "123").let { assertEquals("3", it.change); assertEquals(2, it.start); assertEquals(2, it.end) }
        editOf("123", "13").let { assertEquals("", it.change); assertEquals(1, it.start); assertEquals(2, it.end) }
        editOf("abc", "aXc").let { assertEquals("X", it.change); assertEquals(1, it.start); assertEquals(2, it.end) }
        editOf("abc", "abc").let { assertEquals("", it.change); assertEquals(3, it.start); assertEquals(3, it.end) }
    }

    /** The scripts run on a thread of their own, so a slow document does not stop the drawing. */
    @Test
    fun a_slow_script_does_not_block_the_viewer() {
        val doc = PdfDocument.open(formPdf())
        val scripts = object : PdfScriptHandler {
            override val formState: PdfFormState = PdfFormState(doc)

            /** Held until the test lets go, which is what a long running script looks like. */
            val release = java.util.concurrent.CountDownLatch(1)
            val started = java.util.concurrent.CountDownLatch(1)
            val finished = java.util.concurrent.CountDownLatch(1)

            @Volatile var thread: String? = null

            override fun documentOpened() {
                thread = Thread.currentThread().name
                started.countDown()
                release.await()
                formState.setValue("out", "late")
                finished.countDown()
            }
        }
        lateinit var state: KiteDocViewState
        ImageComposeScene(width = 200, height = 200, density = Density(1f)) {
            state = rememberKiteDocViewState(doc)
            KiteDocView(state = state, modifier = Modifier.fillMaxSize(), scripts = scripts)
        }.use { scene ->
            val driver = SceneTestDriver(scene)
            driver.pumpUntilState { scripts.started.count == 0L }

            // The script is stuck now. If it held the thread that draws, none of these frames
            // would render and the loop below would never finish.
            var framesWhileBusy = 0
            driver.pumpUntilState(maxFrames = 12, timeoutMs = 4_000) {
                framesWhileBusy++
                framesWhileBusy >= 10
            }
            assertTrue(framesWhileBusy >= 10, "only $framesWhileBusy frames rendered while the script was stuck")
            assertEquals("", scripts.formState.value("out"), "the script has not written yet")

            scripts.release.countDown()
            driver.pumpUntilState { scripts.finished.count == 0L }
            assertEquals("late", scripts.formState.value("out"))
            assertTrue(
                scripts.thread != Thread.currentThread().name,
                "the script ran on its own thread, not the test's: ${scripts.thread}",
            )
        }
    }
}
