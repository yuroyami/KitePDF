package io.github.yuroyami.kitepdf.compose

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.graphics.toPixelMap
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

    /**
     * A real tap and real keys, the way a reader fills a field. The input's first focus event
     * arrives before its focus request lands and says "not focused", which used to commit the
     * field empty and take the caret away at once (#356).
     */
    @Test
    fun a_tap_on_a_text_field_takes_the_caret_and_typing_goes_through_the_scripts() {
        val doc = PdfDocument.open(formPdf())
        val scripts = FakeScripts(doc)
        lateinit var state: KiteDocViewState
        ImageComposeScene(width = 200, height = 200, density = Density(1f)) {
            state = rememberKiteDocViewState(doc)
            KiteDocView(
                state = state,
                modifier = Modifier.fillMaxSize(),
                zoomSpec = KiteZoomSpec(doubleTapEnabled = false),
                scripts = scripts,
            )
        }.use { scene ->
            val driver = SceneTestDriver(scene)
            driver.pumpUntil { state.pageGeometry.isNotEmpty() }

            // The text field is [20..180] x [120..160] user space, so display y 40..80: centre (100, 60).
            scene.sendPointerEvent(PointerEventType.Press, Offset(100f, 60f), type = PointerType.Touch)
            scene.sendPointerEvent(PointerEventType.Release, Offset(100f, 60f), type = PointerType.Touch)
            driver.pumpUntilState { scripts.events.contains("focus out") }
            driver.pumpFrames(10)
            assertEquals("out", state.focusedField, "the field kept the caret: ${scripts.events}")
            assertFalse(scripts.events.any { it.startsWith("commit") || it.startsWith("blur") }, "${scripts.events}")

            // A digit is taken and a letter is refused, as the field's keystroke script says.
            type(scene, '4')
            driver.pumpUntilState { scripts.formState.value("out") == "4" }
            type(scene, 'x')
            driver.pumpFrames(10)
            assertEquals("4", scripts.formState.value("out"), "a letter is refused")

            // Leaving the field commits it, which is what runs validate, calculate and format.
            state.blurFocusedField()
            driver.pumpUntilState { scripts.events.contains("blur out") }
            assertEquals(null, state.focusedField)
            assertEquals(
                listOf("down out", "up out", "focus out", "commit out=4", "blur out"),
                scripts.events.dropWhile { !it.startsWith("down") },
            )
        }
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
     * A key typed in the middle of a value while the script still weighs the one before it.
     * The script refuses the letter and takes the digit, and the digit stays where it was
     * typed (#363).
     */
    @Test
    fun typing_in_the_middle_goes_through_a_slow_script_in_order() {
        val doc = PdfDocument.open(formPdf())
        val scripts = object : PdfScriptHandler {
            override val formState: PdfFormState = PdfFormState(doc)
            override fun keystroke(fieldName: String, change: String, selectionStart: Int, selectionEnd: Int): String? {
                Thread.sleep(80)
                if (change.any { !it.isDigit() }) return null
                val current = formState.value(fieldName) ?: ""
                val start = selectionStart.coerceIn(0, current.length)
                return current.substring(0, start) + change + current.substring(selectionEnd.coerceIn(start, current.length))
            }
        }
        scripts.formState.setValue("out", "123")
        lateinit var state: KiteDocViewState
        ImageComposeScene(width = 200, height = 200, density = Density(1f)) {
            state = rememberKiteDocViewState(doc)
            KiteDocView(state = state, modifier = Modifier.fillMaxSize(), zoomSpec = KiteZoomSpec(doubleTapEnabled = false), scripts = scripts)
        }.use { scene ->
            val driver = SceneTestDriver(scene)
            driver.pumpUntil { state.pageGeometry.isNotEmpty() }
            scene.sendPointerEvent(PointerEventType.Press, Offset(100f, 60f), type = PointerType.Touch)
            scene.sendPointerEvent(PointerEventType.Release, Offset(100f, 60f), type = PointerType.Touch)
            driver.pumpUntilState { state.focusedField == "out" }
            driver.pumpFrames(4)
            // The caret starts at the end, so two steps left put it after the 1. The text field reads
            // its value at composition, so each key gets a frame, as keys from a keyboard do.
            repeat(2) {
                press(scene, Key.DirectionLeft)
                driver.pumpFrames(1)
            }
            // Both keys go in before the script has answered the first.
            type(scene, 'x')
            driver.pumpFrames(1)
            type(scene, '9')
            driver.pumpUntilState { scripts.formState.value("out") == "1923" }
            driver.pumpFrames(10)
            assertEquals("1923", scripts.formState.value("out"))
        }
    }

    /** A tap whose script writes a field repaints the form, with no timer running (#357). */
    @Test
    fun a_button_that_writes_a_field_repaints_the_form() {
        val doc = PdfDocument.open(formPdf())
        val scripts = object : PdfScriptHandler {
            override val formState: PdfFormState = PdfFormState(doc)
            override fun mouseUp(fieldName: String) {
                if (fieldName == "press") formState.setValue("out", "88888888")
            }
        }
        lateinit var state: KiteDocViewState
        ImageComposeScene(width = 200, height = 200, density = Density(1f)) {
            state = rememberKiteDocViewState(doc)
            KiteDocView(
                state = state,
                modifier = Modifier.fillMaxSize(),
                layout = KiteDocLayout.SinglePage(0),
                zoomSpec = KiteZoomSpec(doubleTapEnabled = false),
                scripts = scripts,
            )
        }.use { scene ->
            val driver = SceneTestDriver(scene)
            driver.pumpUntil { state.pageGeometry.isNotEmpty() }
            // Past the page's fade-in, so no animation redraws the form by chance.
            val before = darkPixelsInField(driver.pumpFrames(60).toComposeImageBitmap().toPixelMap())
            // The button is [20..90] x [20..60] in user space, so display y 140..180: centre (55, 160).
            scene.sendPointerEvent(PointerEventType.Press, Offset(55f, 160f), type = PointerType.Touch)
            scene.sendPointerEvent(PointerEventType.Release, Offset(55f, 160f), type = PointerType.Touch)
            driver.pumpUntilState { scripts.formState.value("out") == "88888888" }
            driver.pumpUntil { darkPixelsInField(it) > before + 20 }
        }
    }

    /** A value the host writes while the viewer is idle shows without anything else moving (#357). */
    @Test
    fun a_value_the_host_writes_repaints_the_form() {
        val doc = PdfDocument.open(formPdf())
        val scripts = object : PdfScriptHandler {
            override val formState: PdfFormState = PdfFormState(doc)
        }
        lateinit var state: KiteDocViewState
        ImageComposeScene(width = 200, height = 200, density = Density(1f)) {
            state = rememberKiteDocViewState(doc)
            KiteDocView(state = state, modifier = Modifier.fillMaxSize(), layout = KiteDocLayout.SinglePage(0), scripts = scripts)
        }.use { scene ->
            val driver = SceneTestDriver(scene)
            driver.pumpUntil { state.pageGeometry.isNotEmpty() }
            // Past the page's fade-in, so no animation redraws the form by chance.
            val before = darkPixelsInField(driver.pumpFrames(60).toComposeImageBitmap().toPixelMap())
            scripts.formState.setValue("out", "88888888")
            driver.pumpUntil { darkPixelsInField(it) > before + 20 }
        }
    }

    /** Leaving one field for another commits the first before the second one's scripts run (#363). */
    @Test
    fun the_old_field_commits_before_the_new_widget_is_pressed() {
        val doc = PdfDocument.open(formPdf())
        val scripts = FakeScripts(doc)
        lateinit var state: KiteDocViewState
        ImageComposeScene(width = 200, height = 200, density = Density(1f)) {
            state = rememberKiteDocViewState(doc)
            KiteDocView(state = state, modifier = Modifier.fillMaxSize(), zoomSpec = KiteZoomSpec(doubleTapEnabled = false), scripts = scripts)
        }.use { scene ->
            val driver = SceneTestDriver(scene)
            driver.pumpUntil { state.pageGeometry.isNotEmpty() }
            scene.sendPointerEvent(PointerEventType.Press, Offset(100f, 60f), type = PointerType.Touch)
            scene.sendPointerEvent(PointerEventType.Release, Offset(100f, 60f), type = PointerType.Touch)
            driver.pumpUntilState { scripts.events.contains("focus out") }
            scene.sendPointerEvent(PointerEventType.Press, Offset(55f, 160f), type = PointerType.Touch)
            scene.sendPointerEvent(PointerEventType.Release, Offset(55f, 160f), type = PointerType.Touch)
            driver.pumpUntilState { scripts.events.contains("up press") }
            val events = scripts.events
            assertTrue(events.indexOf("blur out") in 0 until events.indexOf("down press"), "the order was $events")
        }
    }

    /** Dark pixels in the text field's area, display y 40..80 and x 20..180. */
    private fun darkPixelsInField(pixels: androidx.compose.ui.graphics.PixelMap): Int {
        var dark = 0
        for (y in 42 until 78) for (x in 22 until 178) {
            val c = pixels[x, y]
            if (c.red + c.green + c.blue < 1.2f) dark++
        }
        return dark
    }

    /** Sends one key press and release as a desktop keyboard does, for keys that type nothing. */
    @OptIn(InternalComposeUiApi::class)
    private fun press(scene: ImageComposeScene, key: Key) {
        scene.sendKeyEvent(KeyEvent(key, KeyEventType.KeyDown))
        scene.sendKeyEvent(KeyEvent(key, KeyEventType.KeyUp))
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
