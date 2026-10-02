package io.github.yuroyami.kitepdf.compose

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.github.yuroyami.kitepdf.compose.EdtImageComposeScene as ImageComposeScene
import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerType
import io.github.yuroyami.kitepdf.PdfDocument
import io.github.yuroyami.kitepdf.PdfFormState
import io.github.yuroyami.kitepdf.PdfScriptHandler
import java.util.Collections
import java.util.concurrent.CountDownLatch
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * How the viewer calls a document's scripts: once per handler for the open scripts, never letting
 * a failure reach the host, on a lane of its own, and with the last typed value committed however
 * the reader leaves the field (#365).
 */
class ScriptCallsSceneTest {

    /** One 200 x 200 page with a text field `out` at [20 120 180 160], centred at display (100, 60). */
    private fun formPdf(): ByteArray {
        val sb = StringBuilder("%PDF-1.7\n")
        val offsets = ArrayList<Int>()
        fun add(body: String) {
            offsets.add(sb.length)
            sb.append("${offsets.size} 0 obj\n$body\nendobj\n")
        }
        add("<< /Type /Catalog /Pages 2 0 R /AcroForm << /Fields [4 0 R] >> >>")
        add("<< /Type /Pages /Kids [3 0 R] /Count 1 /MediaBox [0 0 200 200] >>")
        add("<< /Type /Page /Parent 2 0 R /Resources << >> /Annots [4 0 R] >>")
        add("<< /Type /Annot /Subtype /Widget /FT /Tx /T (out) /V () /Rect [20 120 180 160] /DA (/Helv 12 Tf 0 g) >>")
        val xref = sb.length
        sb.append("xref\n0 ${offsets.size + 1}\n0000000000 65535 f \n")
        for (offset in offsets) sb.append("${offset.toString().padStart(10, '0')} 00000 n \n")
        sb.append("trailer\n<< /Size ${offsets.size + 1} /Root 1 0 R >>\nstartxref\n$xref\n%%EOF\n")
        return sb.toString().encodeToByteArray()
    }

    /** Records every call, from whatever thread makes it. */
    private open class Recorder(document: PdfDocument) : PdfScriptHandler {
        override val formState: PdfFormState = PdfFormState(document)
        val events: MutableList<String> = Collections.synchronizedList(ArrayList())
        override fun documentOpened() { events += "open" }
        override fun pageOpened(pageIndex: Int) { events += "page $pageIndex" }
        override fun focus(fieldName: String) { events += "focus $fieldName" }
        override fun blur(fieldName: String) { events += "blur $fieldName" }
        override fun keystroke(fieldName: String, change: String, selectionStart: Int, selectionEnd: Int): String? {
            val current = formState.value(fieldName) ?: ""
            val start = selectionStart.coerceIn(0, current.length)
            return current.substring(0, start) + change + current.substring(selectionEnd.coerceIn(start, current.length))
        }
        override fun commit(fieldName: String, value: String): Boolean {
            events += "commit $fieldName=$value"
            formState.setValue(fieldName, value)
            return true
        }
    }

    private fun tapField(scene: ImageComposeScene) {
        scene.sendPointerEvent(PointerEventType.Press, Offset(100f, 60f), type = PointerType.Touch)
        scene.sendPointerEvent(PointerEventType.Release, Offset(100f, 60f), type = PointerType.Touch)
    }

    /** Types [char] into the focused node as a desktop keyboard does: a typed event from AWT. */
    @OptIn(InternalComposeUiApi::class)
    private fun type(scene: ImageComposeScene, char: Char) {
        val awt = java.awt.event.KeyEvent(
            javax.swing.JLabel(), java.awt.event.KeyEvent.KEY_TYPED, 0L, 0, java.awt.event.KeyEvent.VK_UNDEFINED, char,
        )
        scene.sendKeyEvent(KeyEvent(Key.Unknown, KeyEventType.Unknown, codePoint = char.code, nativeEvent = awt))
    }

    @Test
    fun the_document_open_scripts_run_once_when_the_view_comes_back() {
        forBothEffectOrders { queued ->
            val doc = PdfDocument.open(formPdf())
            val scripts = Recorder(doc)
            var show by mutableStateOf(true)
            val (scene, driver) = drivenScene(200, 200, queued) {
                val state = rememberKiteDocViewState(doc)
                if (show) KiteDocView(state = state, modifier = Modifier.fillMaxSize(), scripts = scripts)
            }
            scene.use {
                driver.pumpUntilState { "page 0" in scripts.events }
                show = false
                driver.pumpFrames(5)
                show = true
                driver.pumpUntilState { scripts.events.count { it == "page 0" } == 2 }
                driver.pumpFrames(10)
                assertEquals(1, scripts.events.count { it == "open" }, "${scripts.events}")
            }
        }
    }

    @Test
    fun a_failing_handler_is_logged_and_the_viewer_keeps_working() {
        val uncaught = Collections.synchronizedList(ArrayList<Throwable>())
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { _, failure -> uncaught += failure }
        try {
            forBothEffectOrders { queued ->
                val doc = PdfDocument.open(formPdf())
                val scripts = object : Recorder(doc) {
                    override fun documentOpened() = throw IllegalStateException("the open script failed")
                    override fun pageOpened(pageIndex: Int) = throw IllegalStateException("the page script failed")
                    override fun focus(fieldName: String) {
                        super.focus(fieldName)
                        throw IllegalStateException("the focus script failed")
                    }
                }
                lateinit var state: KiteDocViewState
                val (scene, driver) = drivenScene(200, 200, queued) {
                    state = rememberKiteDocViewState(doc)
                    KiteDocView(state = state, modifier = Modifier.fillMaxSize(), zoomSpec = KiteZoomSpec(doubleTapEnabled = false), scripts = scripts)
                }
                scene.use {
                    driver.pumpUntilState { state.pageGeometry.isNotEmpty() }
                    tapField(scene)
                    driver.pumpUntilState { "focus out" in scripts.events }
                    driver.pumpFrames(4)
                    // The failures above did not stop the viewer: the field still commits.
                    type(scene, '7')
                    driver.pumpUntilState { scripts.formState.value("out") == "7" }
                    onTestUiThread { state.blurFocusedField() }
                    driver.pumpUntilState { "blur out" in scripts.events }
                    assertTrue("commit out=7" in scripts.events, "${scripts.events}")
                }
            }
            // The whole stack of each failure, so a failure that comes rarely says where it came from (#443).
            assertEquals(emptyList(), uncaught.map { it.stackTraceToString() }, "a failure reached the host")
        } finally {
            Thread.setDefaultUncaughtExceptionHandler(previous)
        }
    }

    @Test
    fun a_slow_document_does_not_hold_up_another_viewer() {
        val release = CountDownLatch(1)
        val started = CountDownLatch(1)
        try {
            val slowDoc = PdfDocument.open(formPdf())
            val slow = object : Recorder(slowDoc) {
                override fun documentOpened() {
                    started.countDown()
                    release.await()
                }
            }
            val doc = PdfDocument.open(formPdf())
            val fast = Recorder(doc)
            val (slowScene, slowDriver) = drivenScene(200, 200, queued = false) {
                KiteDocView(state = rememberKiteDocViewState(slowDoc), modifier = Modifier.fillMaxSize(), scripts = slow)
            }
            slowScene.use {
                slowDriver.pumpUntilState { started.count == 0L }
                val (scene, driver) = drivenScene(200, 200, queued = false) {
                    KiteDocView(state = rememberKiteDocViewState(doc), modifier = Modifier.fillMaxSize(), scripts = fast)
                }
                scene.use {
                    driver.pumpUntilState(timeoutMs = 5_000) { "open" in fast.events }
                }
            }
        } finally {
            release.countDown()
        }
    }

    @Test
    fun a_host_can_commit_the_focused_field_and_wait_for_the_scripts() {
        forBothEffectOrders { queued ->
            val doc = PdfDocument.open(formPdf())
            val scripts = Recorder(doc)
            lateinit var state: KiteDocViewState
            val (scene, driver) = drivenScene(200, 200, queued) {
                state = rememberKiteDocViewState(doc)
                KiteDocView(state = state, modifier = Modifier.fillMaxSize(), zoomSpec = KiteZoomSpec(doubleTapEnabled = false), scripts = scripts)
            }
            scene.use {
                driver.pumpUntilState { state.pageGeometry.isNotEmpty() }
                tapField(scene)
                driver.pumpUntilState { "focus out" in scripts.events }
                driver.pumpFrames(4)
                type(scene, '4')
                driver.pumpUntilState { scripts.formState.value("out") == "4" }
                driver.runOnUi { state.commitFocusedField() }
                // No frame in between: the commit is done when the call returns.
                assertTrue("commit out=4" in scripts.events, "${scripts.events}")
                assertTrue("blur out" in scripts.events, "${scripts.events}")
                assertEquals(null, state.focusedField)
            }
        }
    }

    /**
     * The view leaves while the document's scripts are still busy with the field's focus, so the
     * commit has to wait its turn after the view is gone.
     */
    @Test
    fun the_focused_field_commits_when_the_view_leaves() {
        forBothEffectOrders { queued ->
            val focusing = CountDownLatch(1)
            val release = CountDownLatch(1)
            try {
                val doc = PdfDocument.open(formPdf())
                val scripts = object : Recorder(doc) {
                    override fun focus(fieldName: String) {
                        super.focus(fieldName)
                        focusing.countDown()
                        release.await()
                    }
                }
                var show by mutableStateOf(true)
                lateinit var state: KiteDocViewState
                val (scene, driver) = drivenScene(200, 200, queued) {
                    state = rememberKiteDocViewState(doc)
                    if (show) {
                        KiteDocView(state = state, modifier = Modifier.fillMaxSize(), zoomSpec = KiteZoomSpec(doubleTapEnabled = false), scripts = scripts)
                    }
                }
                scene.use {
                    driver.pumpUntilState { state.pageGeometry.isNotEmpty() }
                    tapField(scene)
                    driver.pumpUntilState { focusing.count == 0L && state.focusedField == "out" }
                    driver.pumpFrames(4)
                    type(scene, '9')
                    driver.pumpFrames(2)
                    show = false
                    driver.pumpFrames(10)
                    release.countDown()
                    driver.pumpUntilState { "blur out" in scripts.events }
                    assertTrue("commit out=9" in scripts.events, "${scripts.events}")
                }
            } finally {
                release.countDown()
            }
        }
    }

    /** A tap takes the caret and the view leaves before its input could take the focus. */
    @Test
    fun a_field_that_takes_the_caret_as_the_view_leaves_lets_it_go() {
        forBothEffectOrders { queued ->
            val doc = PdfDocument.open(formPdf())
            val scripts = Recorder(doc)
            var show by mutableStateOf(true)
            lateinit var state: KiteDocViewState
            val (scene, driver) = drivenScene(200, 200, queued) {
                state = rememberKiteDocViewState(doc)
                if (show) {
                    KiteDocView(state = state, modifier = Modifier.fillMaxSize(), zoomSpec = KiteZoomSpec(doubleTapEnabled = false), scripts = scripts)
                }
            }
            scene.use {
                driver.pumpUntilState { state.pageGeometry.isNotEmpty() }
                tapField(scene)
                show = false
                driver.pumpUntilState { "blur out" in scripts.events }
                assertEquals(null, state.focusedField)
            }
        }
    }
}
