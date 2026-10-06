package io.github.yuroyami.kitepdf.compose

import androidx.compose.foundation.layout.fillMaxSize
import io.github.yuroyami.kitepdf.compose.EdtImageComposeScene as ImageComposeScene
import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import io.github.yuroyami.kitepdf.PdfChoiceSelection
import io.github.yuroyami.kitepdf.PdfDocument
import io.github.yuroyami.kitepdf.PdfFormState
import io.github.yuroyami.kitepdf.PdfScriptHandler
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Editable choices retain display text and drain pending keystrokes once across focus changes. */
class EditableChoiceInputSceneTest {
    private fun document(): PdfDocument {
        val objects = listOf(
            "<< /Type /Catalog /Pages 2 0 R /AcroForm << /Fields [4 0 R] >> >>",
            "<< /Type /Pages /Kids [3 0 R] /Count 1 >>",
            "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 200 200] /Annots [5 0 R 6 0 R] >>",
            "<< /FT /Ch /T (choice) /Ff 393216 /Opt [[(S) (Small)] [(L) (Large)]] /V (S) /DA (/Helv 12 Tf 0 g) /Kids [5 0 R 6 0 R] >>",
            "<< /Type /Annot /Subtype /Widget /Parent 4 0 R /Rect [20 140 180 180] >>",
            "<< /Type /Annot /Subtype /Widget /Parent 4 0 R /Rect [20 60 180 100] >>",
        )
        val pdf = StringBuilder("%PDF-1.7\n")
        val offsets = objects.mapIndexed { index, body -> pdf.length.also { pdf.append("${index + 1} 0 obj\n$body\nendobj\n") } }
        val xref = pdf.length
        pdf.append("xref\n0 ${objects.size + 1}\n0000000000 65535 f \n")
        offsets.forEach { pdf.append("${it.toString().padStart(10, '0')} 00000 n \n") }
        pdf.append("trailer\n<< /Size ${objects.size + 1} /Root 1 0 R >>\nstartxref\n$xref\n%%EOF\n")
        return PdfDocument.open(pdf.toString().encodeToByteArray())
    }

    private class Scripts(private val document: PdfDocument, val slow: Boolean = false) : PdfScriptHandler {
        override val formState = PdfFormState(document)
        val entered = CountDownLatch(1)
        val blurred = CountDownLatch(1)
        val release = CountDownLatch(if (slow) 1 else 0)
        val keys = ConcurrentLinkedQueue<String>()
        val commits = ConcurrentLinkedQueue<PdfChoiceSelection>()
        override suspend fun keystroke(fieldName: String, change: String, selectionStart: Int, selectionEnd: Int): String {
            keys += change
            entered.countDown()
            check(release.await(10, TimeUnit.SECONDS)) { "test did not release the pending keystroke" }
            val selection = formState.choiceSelection(fieldName)!!
            val current = selection.indices.singleOrNull()?.let { index -> document.formField(fieldName)!!.choiceOptions.first { it.index == index }.label }
                ?: selection.freeText.orEmpty()
            val rewritten = if (change == "x") "er" else change
            val start = selectionStart.coerceIn(0, current.length)
            val end = selectionEnd.coerceIn(start, current.length)
            return current.substring(0, start) + rewritten + current.substring(end)
        }
        override suspend fun commitChoice(fieldName: String, selection: PdfChoiceSelection): Boolean {
            val accepted = formState.setChoiceSelection(fieldName, selection, formState.fieldRevision(fieldName))
            if (accepted) commits += selection
            return accepted
        }
        override suspend fun blur(fieldName: String) { blurred.countDown() }
    }

    private fun input(scene: ImageComposeScene): SemanticsNode? = onTestUiThread {
        fun walk(node: SemanticsNode): SemanticsNode? {
            if (node.config.getOrNull(SemanticsProperties.Focused) == true && node.config.getOrNull(SemanticsProperties.EditableText) != null) return node
            return node.children.firstNotNullOfOrNull(::walk)
        }
        return@onTestUiThread scene.semanticsOwners.firstNotNullOfOrNull { walk(it.unmergedRootSemanticsNode) }
    }

    @OptIn(InternalComposeUiApi::class)
    private fun type(scene: ImageComposeScene, character: Char) {
        val event = java.awt.event.KeyEvent(javax.swing.JLabel(), java.awt.event.KeyEvent.KEY_TYPED, 0L, 0, java.awt.event.KeyEvent.VK_UNDEFINED, character)
        scene.sendKeyEvent(KeyEvent(Key.Unknown, KeyEventType.Unknown, codePoint = character.code, nativeEvent = event))
    }

    private fun open(state: KiteDocViewState, widget: Int) {
        onTestUiThread { state.openChoice("choice", widget, KiteDocViewState.WidgetBox(0, if (widget == 0) Rect(20f, 20f, 180f, 60f) else Rect(20f, 100f, 180f, 140f))) }
    }

    @Test
    fun typed_text_uses_the_face_label_and_escape_restores_the_selected_option() = forBothEffectOrders { queued ->
        val document = document()
        val scripts = Scripts(document)
        lateinit var state: KiteDocViewState
        val (scene, driver) = drivenScene(200, 200, queued) {
            state = rememberKiteDocViewState(document)
            KiteDocView(state, Modifier.fillMaxSize(), layout = KiteDocLayout.SinglePage(0), scripts = scripts)
        }
        scene.use {
            driver.pumpUntilState { state.pageGeometry.isNotEmpty() }
            open(state, 0)
            driver.pumpUntilState { input(scene) != null }
            assertEquals("Small", input(scene)!!.config[SemanticsProperties.EditableText].text)
            type(scene, 'e')
            driver.pumpUntilState { scripts.formState.value("choice") == "Smalle" }
            onTestUiThread { state.dismissChoice(commit = false) }
            driver.pumpUntilState { scripts.formState.choiceSelection("choice") == PdfChoiceSelection(listOf(0)) }
            assertTrue(scripts.commits.isEmpty(), "Escape must not commit typed text")
        }
    }

    @Test
    fun blur_drains_pending_rewritten_keys_exactly_once_before_committing() = forBothEffectOrders { queued ->
        val document = document()
        val scripts = Scripts(document, slow = true)
        lateinit var state: KiteDocViewState
        val (scene, driver) = drivenScene(200, 200, queued) {
            state = rememberKiteDocViewState(document)
            KiteDocView(state, Modifier.fillMaxSize(), layout = KiteDocLayout.SinglePage(0), scripts = scripts)
        }
        scene.use {
            try {
                driver.pumpUntilState { state.pageGeometry.isNotEmpty() }
                open(state, 0)
                driver.pumpUntilState { input(scene) != null }
                type(scene, 'x')
                driver.pumpUntilState { scripts.entered.count == 0L }
                type(scene, 'y')
                onTestUiThread { state.dismissChoice() }
                scripts.release.countDown()
                driver.pumpUntilState { scripts.commits.isNotEmpty() }
                assertEquals(listOf("x", "y"), scripts.keys.toList(), "the in-flight key must not run twice")
                assertEquals(PdfChoiceSelection(emptyList(), freeText = "Smallery"), scripts.commits.single())
                assertEquals("Smallery", scripts.formState.value("choice"))
            } finally { scripts.release.countDown() }
        }
    }

    @Test
    fun escape_while_a_keystroke_waits_prevents_its_late_store() = forBothEffectOrders { queued ->
        val document = document()
        val scripts = Scripts(document, slow = true)
        lateinit var state: KiteDocViewState
        val (scene, driver) = drivenScene(200, 200, queued) {
            state = rememberKiteDocViewState(document)
            KiteDocView(state, Modifier.fillMaxSize(), layout = KiteDocLayout.SinglePage(0), scripts = scripts)
        }
        scene.use {
            try {
                driver.pumpUntilState { state.pageGeometry.isNotEmpty() }
                open(state, 0)
                driver.pumpUntilState { input(scene) != null }
                type(scene, 'x')
                driver.pumpUntilState { scripts.entered.count == 0L }
                onTestUiThread { state.dismissChoice(commit = false) }
                scripts.release.countDown()
                driver.pumpUntilState { scripts.blurred.count == 0L }
                driver.pumpFrames(2)
                assertEquals(PdfChoiceSelection(listOf(0)), scripts.formState.choiceSelection("choice"))
                assertTrue(scripts.commits.isEmpty())
                assertEquals(null, state.focusedField)
                assertEquals(null, state.editingText)
            } finally { scripts.release.countDown() }
        }
    }

    @Test
    fun escape_while_final_validation_waits_invalidates_its_guarded_store() = forBothEffectOrders { queued ->
        val document = document()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val blurred = CountDownLatch(1)
        val accepted = java.util.concurrent.atomic.AtomicReference<Boolean>()
        val scripts = object : PdfScriptHandler {
            override val formState = PdfFormState(document)
            override suspend fun choiceKeystroke(fieldName: String, selection: PdfChoiceSelection): PdfChoiceSelection = selection
            override suspend fun commitChoice(fieldName: String, selection: PdfChoiceSelection): Boolean {
                // A real runner captures this before final /K and /V, then stores atomically.
                val revision = formState.fieldRevision(fieldName)
                entered.countDown()
                check(release.await(10, TimeUnit.SECONDS)) { "test did not release final validation" }
                return formState.setChoiceSelection(fieldName, selection, revision).also(accepted::set)
            }
            override suspend fun blur(fieldName: String) { blurred.countDown() }
        }
        lateinit var state: KiteDocViewState
        val (scene, driver) = drivenScene(200, 200, queued) {
            state = rememberKiteDocViewState(document)
            KiteDocView(state, Modifier.fillMaxSize(), layout = KiteDocLayout.SinglePage(0), scripts = scripts)
        }
        scene.use {
            try {
                driver.pumpUntilState { state.pageGeometry.isNotEmpty() }
                open(state, 0)
                driver.pumpUntilState { input(scene) != null }
                onTestUiThread { state.chooseChoice(PdfChoiceSelection(listOf(1))) }
                driver.pumpUntilState { entered.count == 0L }
                onTestUiThread { state.dismissChoice(commit = false) }
                release.countDown()
                driver.pumpUntilState { blurred.count == 0L }
                assertEquals(false, accepted.get(), "Escape invalidates the revision captured before final validation")
                assertEquals(PdfChoiceSelection(listOf(0)), scripts.formState.choiceSelection("choice"))
                assertEquals(null, state.choiceField)
                assertEquals(null, state.focusedField)
            } finally { release.countDown() }
        }
    }

    @Test
    fun switching_widgets_of_the_same_field_waits_for_the_old_commit_and_starts_a_new_input() = forBothEffectOrders { queued ->
        val document = document()
        val scripts = Scripts(document, slow = true)
        lateinit var state: KiteDocViewState
        val (scene, driver) = drivenScene(200, 200, queued) {
            state = rememberKiteDocViewState(document)
            KiteDocView(state, Modifier.fillMaxSize(), layout = KiteDocLayout.SinglePage(0), scripts = scripts)
        }
        scene.use {
            try {
                driver.pumpUntilState { state.pageGeometry.isNotEmpty() }
                open(state, 0)
                driver.pumpUntilState { input(scene) != null }
                type(scene, 'x')
                driver.pumpUntilState { scripts.entered.count == 0L }
                val oldGeneration = state.formInputGeneration
                open(state, 1)
                scripts.release.countDown()
                driver.pumpUntilState { state.formInputGeneration != oldGeneration && input(scene)?.config?.getOrNull(SemanticsProperties.EditableText)?.text == "Smaller" }
                type(scene, 'z')
                driver.pumpUntilState { scripts.formState.value("choice") == "Smallerz" }
                assertEquals(listOf("x", "z"), scripts.keys.toList())
                assertEquals(1, state.choiceWidgetIndex)
            } finally { scripts.release.countDown() }
        }
    }
}
