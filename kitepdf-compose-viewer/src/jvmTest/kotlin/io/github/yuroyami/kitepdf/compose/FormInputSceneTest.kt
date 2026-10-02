package io.github.yuroyami.kitepdf.compose

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import io.github.yuroyami.kitepdf.compose.EdtImageComposeScene as ImageComposeScene
import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import io.github.yuroyami.kitepdf.PdfDocument
import io.github.yuroyami.kitepdf.PdfFormState
import io.github.yuroyami.kitepdf.PdfScriptHandler
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The input that takes a form field's keys sits inside the viewer, over the field's widget, and
 * takes keys the way the field asks: one line or several, a password or a number keyboard, and
 * no more characters than `/MaxLen` (ISO 32000-1, 12.7.4.3). It sat next to the viewer, so the
 * host's layout got a second child while a field had the caret, and every field took one line
 * of any text (#362).
 */
class FormInputSceneTest {

    /**
     * One 200 x 200 page with four text fields, at display rectangles:
     * `name` (20, 20)-(180, 50), one line; `notes` (20, 60)-(180, 140), several lines;
     * `code` (20, 150)-(90, 180), a password of at most 4 characters; and
     * `amount` (110, 150)-(180, 180), with a number format.
     */
    private fun formPdf(): PdfDocument {
        val sb = StringBuilder("%PDF-1.7\n")
        val offsets = ArrayList<Int>()
        fun add(body: String) {
            offsets.add(sb.length)
            sb.append("${offsets.size} 0 obj\n$body\nendobj\n")
        }
        add("<< /Type /Catalog /Pages 2 0 R /AcroForm << /Fields [4 0 R 5 0 R 6 0 R 7 0 R] >> >>")
        add("<< /Type /Pages /Kids [3 0 R] /Count 1 /MediaBox [0 0 200 200] >>")
        add("<< /Type /Page /Parent 2 0 R /Resources << >> /Annots [4 0 R 5 0 R 6 0 R 7 0 R] >>")
        add("<< /Type /Annot /Subtype /Widget /FT /Tx /T (name) /V () /Rect [20 150 180 180] /DA (/Helv 12 Tf 0 g) >>")
        add("<< /Type /Annot /Subtype /Widget /FT /Tx /T (notes) /Ff 4096 /V () /Rect [20 60 180 140] /DA (/Helv 12 Tf 0 g) >>")
        add("<< /Type /Annot /Subtype /Widget /FT /Tx /T (code) /Ff 8192 /MaxLen 4 /V () /Rect [20 20 90 50] /DA (/Helv 12 Tf 0 g) >>")
        add(
            "<< /Type /Annot /Subtype /Widget /FT /Tx /T (amount) /TU (Amount in euros) /V () /Rect [110 20 180 50] /DA (/Helv 12 Tf 0 g) " +
                "/AA << /F << /S /JavaScript /JS (AFNumber_Format\\(2, 0, 0, 0, \"\", true\\);) >> >> >>",
        )
        val xref = sb.length
        sb.append("xref\n0 ${offsets.size + 1}\n0000000000 65535 f \n")
        for (offset in offsets) sb.append("${offset.toString().padStart(10, '0')} 00000 n \n")
        sb.append("trailer\n<< /Size ${offsets.size + 1} /Root 1 0 R >>\nstartxref\n$xref\n%%EOF\n")
        return PdfDocument.open(sb.toString().encodeToByteArray())
    }

    /** Keeps every key, as a field with no keystroke script does. */
    private class Scripts(document: PdfDocument) : PdfScriptHandler {
        override val formState: PdfFormState = PdfFormState(document)
        override fun keystroke(fieldName: String, change: String, selectionStart: Int, selectionEnd: Int): String {
            val current = formState.value(fieldName) ?: ""
            val start = selectionStart.coerceIn(0, current.length)
            return current.substring(0, start) + change + current.substring(selectionEnd.coerceIn(start, current.length))
        }
    }

    private fun tap(scene: ImageComposeScene, x: Float, y: Float) {
        scene.sendPointerEvent(PointerEventType.Press, Offset(x, y), type = PointerType.Touch)
        scene.sendPointerEvent(PointerEventType.Release, Offset(x, y), type = PointerType.Touch)
    }

    @OptIn(InternalComposeUiApi::class)
    private fun type(scene: ImageComposeScene, char: Char) {
        val awt = java.awt.event.KeyEvent(
            javax.swing.JLabel(), java.awt.event.KeyEvent.KEY_TYPED, 0L, 0, java.awt.event.KeyEvent.VK_UNDEFINED, char,
        )
        scene.sendKeyEvent(KeyEvent(Key.Unknown, KeyEventType.Unknown, codePoint = char.code, nativeEvent = awt))
    }

    @OptIn(InternalComposeUiApi::class)
    private fun press(scene: ImageComposeScene, key: Key) {
        scene.sendKeyEvent(KeyEvent(key, KeyEventType.KeyDown))
        scene.sendKeyEvent(KeyEvent(key, KeyEventType.KeyUp))
    }

    /** The text input that has the focus, or null. */
    private fun focusedInput(scene: ImageComposeScene): SemanticsNode? = onTestUiThread {
        fun walk(node: SemanticsNode): SemanticsNode? {
            if (node.config.getOrNull(SemanticsProperties.Focused) == true && node.config.getOrNull(SemanticsProperties.EditableText) != null) return node
            return node.children.firstNotNullOfOrNull { walk(it) }
        }
        return@onTestUiThread scene.semanticsOwners.firstNotNullOfOrNull { walk(it.unmergedRootSemanticsNode) }
    }

    /** A 200 x 200 viewer of [doc] on its first page, with a stand-in for the scripts. */
    private fun viewer(doc: PdfDocument, queued: Boolean, scripts: Scripts = Scripts(doc)): Triple<ImageComposeScene, SceneTestDriver, () -> KiteDocViewState> {
        lateinit var state: KiteDocViewState
        val (scene, driver) = drivenScene(200, 200, queued) {
            state = rememberKiteDocViewState(doc)
            KiteDocView(
                state = state,
                modifier = Modifier.fillMaxSize(),
                layout = KiteDocLayout.SinglePage(0),
                zoomSpec = KiteZoomSpec(doubleTapEnabled = false),
                scripts = scripts,
            )
        }
        driver.pumpUntilState { state.pageGeometry.isNotEmpty() }
        return Triple(scene, driver) { state }
    }

    /** A screen reader names the input by the field's tooltip, else by the field's own name (#427). */
    @Test
    fun the_input_carries_the_tooltip_or_the_name_of_its_field() {
        val doc = formPdf()
        val (scene, driver, _) = viewer(doc, queued = true)
        scene.use {
            val amount = focus(scene, driver, 145f, 165f)
            assertEquals(listOf("Amount in euros"), amount.config.getOrNull(SemanticsProperties.ContentDescription))
            val name = focus(scene, driver, 100f, 35f)
            assertEquals(listOf("name"), name.config.getOrNull(SemanticsProperties.ContentDescription))
        }
    }

    /** Taps the field at ([x], [y]) and waits until its input has the focus. */
    private fun focus(scene: ImageComposeScene, driver: SceneTestDriver, x: Float, y: Float): SemanticsNode {
        tap(scene, x, y)
        driver.pumpUntilState { focusedInput(scene) != null }
        driver.pumpFrames(2)
        return focusedInput(scene)!!
    }

    private fun assertArea(expected: Rect, node: SemanticsNode, what: String) {
        val actual = Rect(node.positionInRoot, node.size.let { androidx.compose.ui.geometry.Size(it.width.toFloat(), it.height.toFloat()) })
        for ((name, pair) in listOf("left" to (expected.left to actual.left), "top" to (expected.top to actual.top), "right" to (expected.right to actual.right), "bottom" to (expected.bottom to actual.bottom))) {
            assertTrue(abs(pair.first - pair.second) <= 1f, "$what: $name expected ${pair.first}, got ${pair.second} in $actual")
        }
    }

    @Test
    fun a_field_with_the_caret_leaves_the_host_layout_alone() {
        forBothEffectOrders { queued ->
            val doc = formPdf()
            lateinit var state: KiteDocViewState
            // A column with spacing: a node the viewer emits next to its box, even one with no
            // size, would take a gap of its own and shrink the viewer.
            val (scene, driver) = drivenScene(200, 300, queued) {
                state = rememberKiteDocViewState(doc)
                Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    KiteDocView(
                        state = state,
                        modifier = Modifier.fillMaxWidth().weight(1f),
                        layout = KiteDocLayout.SinglePage(0),
                        zoomSpec = KiteZoomSpec(doubleTapEnabled = false),
                        scripts = Scripts(doc),
                    )
                    Spacer(Modifier.fillMaxWidth().height(90.dp))
                }
            }
            scene.use {
                driver.pumpUntilState { state.pageGeometry.isNotEmpty() }
                assertEquals(IntSize(200, 200), state.viewportSize)
                focus(scene, driver, 100f, 35f)
                assertEquals("name", state.focusedField)
                assertEquals(IntSize(200, 200), state.viewportSize, "the viewer keeps its size while a field has the caret")
            }
        }
    }

    @Test
    fun the_input_lies_over_its_widget_and_follows_the_zoom() {
        forBothEffectOrders { queued ->
            val (scene, driver, state) = viewer(formPdf(), queued)
            scene.use {
                assertArea(Rect(20f, 150f, 90f, 180f), focus(scene, driver, 55f, 165f), "at zoom 1")
                // Zoom 1.5 around the widget's centre: each edge moves away from it by half again.
                state().setZoom(1.5f, focal = Offset(55f, 165f))
                driver.pumpFrames(3)
                assertArea(Rect(2.5f, 142.5f, 107.5f, 187.5f), focusedInput(scene)!!, "at zoom 1.5")
            }
        }
    }

    @Test
    fun each_field_asks_for_its_own_keyboard() {
        val doc = formPdf()
        fun options(name: String) = fieldInputOptions(doc.formFields.first { it.fullyQualifiedName == name })
        with(options("name")) {
            assertTrue(singleLine)
            assertEquals(ImeAction.Done, keyboard.imeAction)
            assertEquals(KeyboardType.Text, keyboard.keyboardType)
            assertEquals(null, maxLength)
        }
        with(options("notes")) {
            assertFalse(singleLine, "a multi-line field")
            assertEquals(ImeAction.Default, keyboard.imeAction)
        }
        with(options("code")) {
            assertEquals(KeyboardType.Password, keyboard.keyboardType)
            assertEquals(false, keyboard.autoCorrectEnabled, "a password gets no suggestions")
            assertEquals(4, maxLength)
        }
        assertEquals(KeyboardType.Decimal, options("amount").keyboard.keyboardType, "a field with a number format")
    }

    @Test
    fun a_multi_line_field_takes_a_line_break_and_a_short_field_stops_at_its_length() {
        forBothEffectOrders { queued ->
            val doc = formPdf()
            val scripts = Scripts(doc)
            val (scene, driver, state) = viewer(doc, queued, scripts)
            scene.use {
                val notes = focus(scene, driver, 100f, 100f)
                assertEquals(ImeAction.Default, notes.config.getOrNull(SemanticsProperties.ImeAction))
                type(scene, 'a')
                driver.pumpUntilState { scripts.formState.value("notes") == "a" }
                press(scene, Key.Enter)
                driver.pumpFrames(2)
                type(scene, 'b')
                driver.pumpUntilState { scripts.formState.value("notes") == "a\nb" }

                val code = focus(scene, driver, 55f, 165f)
                assertEquals("code", state().focusedField)
                assertNotNull(code.config.getOrNull(SemanticsProperties.Password), "a password field is one to assistive technology")
                for (char in "12345") {
                    type(scene, char)
                    driver.pumpFrames(2)
                }
                driver.pumpUntilState { scripts.formState.value("code") == "1234" }
                driver.pumpFrames(5)
                assertEquals("1234", scripts.formState.value("code"), "the field takes 4 characters")
            }
        }
    }
}
