package io.github.yuroyami.kitepdf.compose

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.github.yuroyami.kitepdf.compose.EdtImageComposeScene as ImageComposeScene
import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.text.AnnotatedString
import io.github.yuroyami.kitepdf.PdfChoiceSelection
import io.github.yuroyami.kitepdf.PdfDocument
import io.github.yuroyami.kitepdf.PdfFormState
import io.github.yuroyami.kitepdf.PdfPage
import io.github.yuroyami.kitepdf.PdfScriptHandler
import java.io.File
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.jetbrains.skia.EncodedImageFormat

/** Real PDF choice widgets, through pointer, keyboard, semantics and the ordered script lane. */
class ChoiceFieldSceneTest {
    private fun form(
        flags: Int = COMBO,
        options: String = "[(S) (M) (L)]",
        value: String = "/V (S)",
        pageExtras: String = "",
    ): PdfDocument {
        val box = if (flags and COMBO != 0) "[20 260 300 290]" else "[20 120 300 290]"
        val bodies = listOf(
            "<< /Type /Catalog /Pages 2 0 R /AcroForm << /Fields [4 0 R 5 0 R] >> >>",
            "<< /Type /Pages /Kids [3 0 R] /Count 1 /MediaBox [0 0 320 320] >>",
            "<< /Type /Page /Parent 2 0 R /Resources << >> /Annots [4 0 R 5 0 R] $pageExtras >>",
            "<< /Type /Annot /Subtype /Widget /FT /Ch /T (size) /TU (Size) /Ff $flags /Opt $options $value /Rect $box /DA (/Helv 14 Tf 0 g) /MK << /BC [0.4] /BG [1] >> >>",
            "<< /Type /Annot /Subtype /Widget /FT /Tx /T (next) /V () /Rect [20 20 300 60] /DA (/Helv 14 Tf 0 g) >>",
        )
        val data = StringBuilder("%PDF-1.7\n")
        val offsets = ArrayList<Int>()
        for ((i, body) in bodies.withIndex()) {
            offsets += data.length
            data.append("${i + 1} 0 obj\n$body\nendobj\n")
        }
        val xref = data.length
        data.append("xref\n0 ${bodies.size + 1}\n0000000000 65535 f \n")
        for (offset in offsets) data.append("${offset.toString().padStart(10, '0')} 00000 n \n")
        data.append("trailer\n<< /Size ${bodies.size + 1} /Root 1 0 R >>\nstartxref\n$xref\n%%EOF\n")
        return PdfDocument.open(data.toString().encodeToByteArray())
    }

    private class Scripts(doc: PdfDocument) : PdfScriptHandler {
        override val formState = PdfFormState(doc)
        override val supportsMultipleChoices: Boolean = true
        val commits: MutableList<PdfChoiceSelection> = Collections.synchronizedList(ArrayList())
        val proposals: MutableList<PdfChoiceSelection> = Collections.synchronizedList(ArrayList())
        var refuse = false
        var refuseProposal = false
        var onProposal: (() -> Unit)? = null
        override fun choiceKeystroke(fieldName: String, selection: PdfChoiceSelection): PdfChoiceSelection? {
            proposals += selection
            onProposal?.invoke()
            return selection.takeUnless { refuseProposal }
        }
        override fun commitChoice(fieldName: String, selection: PdfChoiceSelection): Boolean {
            commits += selection
            return !refuse && formState.setChoiceSelection(fieldName, selection)
        }
        override fun keystroke(fieldName: String, change: String, selectionStart: Int, selectionEnd: Int): String {
            val current = formState.value(fieldName).orEmpty()
            val start = selectionStart.coerceIn(0, current.length)
            return current.take(start) + change + current.drop(selectionEnd.coerceIn(start, current.length))
        }
    }

    private class Viewer(val scene: ImageComposeScene, val driver: SceneTestDriver, val state: () -> KiteDocViewState) {
        var openings = 0
    }

    private fun viewer(doc: PdfDocument, scripts: PdfScriptHandler, queued: Boolean, layout: KiteDocLayout = KiteDocLayout.SinglePage(0), render: KiteRenderSpec = KiteRenderSpec.Rasterized()): Viewer {
        lateinit var state: KiteDocViewState
        val (scene, driver) = drivenScene(320, 320, queued) {
            state = rememberKiteDocViewState(doc)
            KiteDocView(state, Modifier.fillMaxSize(), layout = layout, renderSpec = render, scripts = scripts, zoomSpec = KiteZoomSpec(doubleTapEnabled = false))
        }
        driver.pumpUntilState { state.pageGeometry.isNotEmpty() }
        driver.pumpFrames(5)
        return Viewer(scene, driver) { state }
    }

    private fun nodes(scene: ImageComposeScene): List<SemanticsNode> = onTestUiThread {
        fun flatten(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap { flatten(it) }
        return@onTestUiThread scene.semanticsOwners.flatMap { flatten(it.unmergedRootSemanticsNode) }
    }

    private fun options(scene: ImageComposeScene): List<SemanticsNode> = nodes(scene).filter {
        it.config.getOrNull(SemanticsProperties.Selected) != null && it.config.getOrNull(SemanticsProperties.Text) != null
    }

    private fun option(scene: ImageComposeScene, label: String): SemanticsNode? = options(scene).firstOrNull {
        it.config.getOrNull(SemanticsProperties.Text)?.joinToString("") { text -> text.text } == label
    }

    private fun tap(scene: ImageComposeScene, at: Offset) {
        scene.sendPointerEvent(PointerEventType.Press, at, type = PointerType.Touch)
        scene.sendPointerEvent(PointerEventType.Release, at, type = PointerType.Touch)
    }

    private fun tap(scene: ImageComposeScene, node: SemanticsNode) = tap(scene, node.boundsInRoot.center)

    private fun Viewer.open() {
        // A script reply or an Escape effect can update state after the last rendered frame.
        // Wait for that closed control to leave the hit tree before tapping the widget again.
        driver.pumpFrames(2)
        openings++
        val page = state().pageAt(0) as PdfPage
        val rect = (state().document as PdfDocument).formFields.first().rect!!
        val x = (rect.left + rect.right) / 2
        val point = page.pageToDisplay(io.github.yuroyami.kitepdf.core.KiteRectangle(x, rect.top - 15, x, rect.top - 15))
        val area = state().displayRectToViewport(0, point)!!
        tap(scene, area.center)
        try {
            driver.pumpUntilState(timeoutMs = 5_000) { state().choiceField == "size" && !state().choiceCommitPending }
        } catch (failure: AssertionError) {
            throw AssertionError("opening=$openings tap=${area.center} choice=${state().choiceField} focused=${state().focusedField} pending=${state().choiceCommitPending} rejected=${state().choiceRejected} widget=${state().choiceWidgetArea()} geometry=${state().pageGeometry}", failure)
        }
        driver.pumpFrames(3)
    }

    @OptIn(InternalComposeUiApi::class)
    private fun press(scene: ImageComposeScene, key: Key) {
        scene.sendKeyEvent(KeyEvent(key, KeyEventType.KeyDown))
        scene.sendKeyEvent(KeyEvent(key, KeyEventType.KeyUp))
    }

    @OptIn(InternalComposeUiApi::class)
    private fun type(scene: ImageComposeScene, char: Char) {
        val awt = java.awt.event.KeyEvent(javax.swing.JLabel(), java.awt.event.KeyEvent.KEY_TYPED, 0L, 0, java.awt.event.KeyEvent.VK_UNDEFINED, char)
        scene.sendKeyEvent(KeyEvent(Key.Unknown, KeyEventType.Unknown, codePoint = char.code, nativeEvent = awt))
    }

    @Test
    fun original_combo_shows_options_without_an_ime_and_paints_the_selected_value() = forBothEffectOrders { queued ->
        val doc = form()
        val scripts = Scripts(doc)
        viewer(doc, scripts, queued).run {
            scene.use {
                val before = driver.pumpFrames(3).toComposeImageBitmap().toPixelMap()
                open()
                assertEquals(listOf("S", "M", "L"), options(scene).map { it.config[SemanticsProperties.Text].single().text })
                assertNull(state().focusedField)
                assertFalse(nodes(scene).any { it.config.getOrNull(SemanticsProperties.EditableText) != null })
                if (queued) {
                    val image = driver.pumpFrames(1)
                    File("build/choice-picker.png").also { it.parentFile.mkdirs() }.writeBytes(image.encodeToData(EncodedImageFormat.PNG)!!.bytes)
                }
                type(scene, 'X')
                driver.pumpFrames(2)
                assertEquals("S", scripts.formState.value("size"))
                tap(scene, assertNotNull(option(scene, "M")))
                driver.pumpUntilState { scripts.formState.value("size") == "M" && state().choiceField == null }
                assertEquals(listOf(1), scripts.formState.choiceSelection("size")?.indices)
                assertEquals(1, scripts.commits.size)
                val after = driver.pumpFrames(3).toComposeImageBitmap().toPixelMap()
                var differences = 0
                for (y in 32 until 59) for (x in 22 until 60) if (before[x, y] != after[x, y]) differences++
                assertTrue(differences > 5, "the widget paints M instead of the old S")
                assertFalse(scripts.formState.setChoiceSelection("size", PdfChoiceSelection(emptyList(), freeText = "XL")))
            }
        }
    }

    @Test
    fun duplicate_labels_keep_original_option_identity_and_export_value() = forBothEffectOrders { queued ->
        val doc = form(options = "[[(small) (Same)] [(large) (Same)] [(medium) (Medium)]]", value = "/V (small)")
        val scripts = Scripts(doc)
        viewer(doc, scripts, queued).run {
            scene.use {
                open()
                val duplicates = options(scene).filter { it.config[SemanticsProperties.Text].single().text == "Same" }
                assertEquals(2, duplicates.size)
                tap(scene, duplicates[1])
                driver.pumpUntilState { scripts.formState.value("size") == "large" }
                assertEquals(listOf(1), scripts.formState.choiceSelection("size")?.indices)
                val node = accessNodes(doc.pages[0], scripts.formState, KiteViewerStrings()).first { it.kind == AccessKind.CHOICE }
                assertEquals("Same", node.state)
            }
        }
    }

    @Test
    fun keyboard_navigation_selects_cancels_and_tabs_to_the_next_widget() = forBothEffectOrders { queued ->
        val doc = form()
        val scripts = Scripts(doc)
        viewer(doc, scripts, queued).run {
            scene.use {
                open()
                press(scene, Key.DirectionDown)
                press(scene, Key.Escape)
                driver.pumpUntilState { state().choiceField == null }
                assertEquals("S", scripts.formState.value("size"))
                open()
                press(scene, Key.DirectionDown)
                press(scene, Key.Enter)
                driver.pumpUntilState { scripts.formState.value("size") == "M" }
                driver.pumpUntilState { state().choiceField == null }
                open()
                press(scene, Key.Tab)
                driver.pumpUntilState { state().focusedField == "next" }
            }
        }
    }

    @Test
    fun a_refused_selection_restores_rows_and_keeps_the_picker_usable() = forBothEffectOrders { queued ->
        val doc = form()
        val scripts = Scripts(doc).apply { refuse = true }
        viewer(doc, scripts, queued).run {
            scene.use {
                open()
                tap(scene, assertNotNull(option(scene, "M")))
                driver.pumpUntilState {
                    state().choiceRejected && option(scene, "S")?.config?.getOrNull(SemanticsProperties.Selected) == true
                }
                assertEquals("S", scripts.formState.value("size"))
                assertEquals(listOf(0), state().choiceDraft?.indices)
                assertEquals("size", state().choiceField)
                assertTrue(assertNotNull(option(scene, "S")).config[SemanticsProperties.Selected])
                scripts.refuse = false
                tap(scene, assertNotNull(option(scene, "L")))
                driver.pumpUntilState { scripts.formState.value("size") == "L" && state().choiceField == null }
            }
        }
    }

    @Test
    fun multiple_list_rows_are_drafted_and_commit_as_one_transaction() = forBothEffectOrders { queued ->
        val doc = form(flags = MULTI, value = "/V []")
        val scripts = Scripts(doc)
        viewer(doc, scripts, queued).run {
            scene.use {
                open()
                assertEquals(listOf(0), state().choiceDraft?.indices, "the first list tap selects the row under the pointer")
                tap(scene, assertNotNull(option(scene, "L")))
                driver.pumpUntilState { !state().choiceCommitPending }
                assertEquals(listOf(0, 2), state().choiceDraft?.indices)
                assertEquals(emptyList(), scripts.formState.choiceSelection("size")?.indices)
                assertEquals(0, scripts.commits.size)
                press(scene, Key.Enter)
                driver.pumpUntilState { scripts.formState.choiceSelection("size")?.indices == listOf(0, 2) }
                assertEquals(1, scripts.commits.size)
            }
        }
    }

    @Test
    fun commit_on_selection_change_and_read_only_or_hidden_changes_are_respected() = forBothEffectOrders { queued ->
        val doc = form(flags = COMMIT_ON_CHANGE)
        val scripts = Scripts(doc)
        viewer(doc, scripts, queued).run {
            scene.use {
                open()
                tap(scene, assertNotNull(option(scene, "M")))
                driver.pumpUntilState { scripts.formState.value("size") == "M" }
                assertEquals(1, scripts.commits.size)
                scripts.formState.setReadOnly("size", true)
                driver.pumpUntilState { state().choiceField == null }
                assertEquals(1, scripts.commits.size)
                scripts.formState.setReadOnly("size", false)
                open()
                assertEquals("S", scripts.formState.value("size"), "reopening taps the first list row")
                assertEquals(2, scripts.commits.size)
                scripts.formState.setHidden("size", true)
                driver.pumpUntilState { state().choiceField == null }
                assertEquals(2, scripts.commits.size, "hiding the list does not commit it again")
            }
        }
    }

    @Test
    fun editable_combo_filters_labels_and_retains_a_text_input() = forBothEffectOrders { queued ->
        val doc = form(flags = COMBO or EDIT, options = "[[(small) (Small)] [(medium) (Medium)] [(large) (Large)]]", value = "/V ()")
        val scripts = Scripts(doc)
        viewer(doc, scripts, queued).run {
            scene.use {
                open()
                driver.pumpUntilState { nodes(scene).any { it.config.getOrNull(SemanticsProperties.Focused) == true && it.config.getOrNull(SemanticsProperties.EditableText) != null } }
                type(scene, 'M')
                driver.pumpUntilState { options(scene).size == 2 }
                assertNotNull(option(scene, "Medium"))
                type(scene, 'e')
                driver.pumpUntilState { options(scene).size == 1 }
                tap(scene, assertNotNull(option(scene, "Medium")))
                driver.pumpUntilState { scripts.formState.value("size") == "medium" && state().choiceField == null }
                open()
                val input = assertNotNull(nodes(scene).firstOrNull { it.config.getOrNull(SemanticsProperties.EditableText) != null })
                assertTrue(onTestUiThread { input.config[SemanticsActions.SetText].action!!.invoke(AnnotatedString("XL")) })
                driver.pumpUntilState { options(scene).isEmpty() && state().editingText == "XL" }
                press(scene, Key.Enter)
                driver.pumpUntilState { scripts.formState.value("size") == "XL" }
            }
        }
    }

    @Test
    fun empty_options_and_zoomed_pickers_keep_the_viewer_usable() = forBothEffectOrders { queued ->
        val doc = form(options = "[]", value = "/V (unknown)")
        val scripts = Scripts(doc)
        viewer(doc, scripts, queued).run {
            scene.use {
                state().setZoom(1.5f, focal = Offset(160f, 45f))
                driver.pumpFrames(3)
                open()
                assertTrue(nodes(scene).any { it.config.getOrNull(SemanticsProperties.Text)?.any { t -> t.text == "No options available" } == true })
                assertEquals(listOf("unknown"), scripts.formState.choiceSelection("size")?.unresolvedValues)
                press(scene, Key.Escape)
                driver.pumpUntilState { state().choiceField == null }
                assertEquals(0, scripts.commits.size)
            }
        }
    }

    @Test
    fun accepted_choices_remain_visible_in_all_layouts_and_render_modes() = forBothEffectOrders { queued ->
        for (layout in listOf(KiteDocLayout.Continuous(), KiteDocLayout.Paged(), KiteDocLayout.Spread(), KiteDocLayout.SinglePage(0))) {
            for (render in listOf(KiteRenderSpec.Rasterized(), KiteRenderSpec.Vectorized())) {
                val doc = form()
                val scripts = Scripts(doc)
                viewer(doc, scripts, queued, layout, render).run {
                    scene.use {
                        open()
                        val target = assertNotNull(option(scene, "L"))
                        val bounds = target.boundsInRoot
                        tap(scene, target)
                        try {
                            driver.pumpUntilState(timeoutMs = 5_000) { scripts.formState.value("size") == "L" && state().choiceField == null }
                        } catch (failure: AssertionError) {
                            File("build/choice-layout-${layout::class.simpleName}-${render::class.simpleName}.png").writeBytes(driver.pumpFrames(1).encodeToData(EncodedImageFormat.PNG)!!.bytes)
                            val pointerProposals = scripts.proposals.toList()
                            val disabled = target.config.getOrNull(SemanticsProperties.Disabled) != null
                            throw AssertionError("layout=${layout::class.simpleName} render=${render::class.simpleName} bounds=$bounds widget=${state().choiceWidgetArea()} draft=${state().choiceDraft} pending=${state().choiceCommitPending} rejected=${state().choiceRejected} proposals=$pointerProposals disabled=$disabled commits=${scripts.commits}", failure)
                        }
                        val area = assertNotNull(state().displayRectToViewport(0, io.github.yuroyami.kitepdf.core.KiteRectangle(22.0, 32.0, 65.0, 59.0)))
                        driver.pumpUntil { pixels ->
                            var ink = 0
                            for (y in area.top.toInt().coerceAtLeast(0) until area.bottom.toInt().coerceAtMost(pixels.height)) {
                                for (x in area.left.toInt().coerceAtLeast(0) until area.right.toInt().coerceAtMost(pixels.width)) {
                                    val color = pixels[x, y]
                                    if (color.red + color.green + color.blue < 1f) ink++
                                }
                            }
                            ink > 5
                        }
                        scripts.formState.setHidden("size", true)
                        driver.pumpFrames(4)
                        assertEquals("L", scripts.formState.value("size"))
                    }
                }
            }
        }
    }

    @Test
    fun list_rows_follow_crop_rotation_and_zoom() = forBothEffectOrders { queued ->
        for (rotation in listOf(0, 90, 180, 270)) {
            val doc = form(flags = COMMIT_ON_CHANGE, pageExtras = "/Rotate $rotation /CropBox [10 10 310 310]")
            val scripts = Scripts(doc)
            viewer(doc, scripts, queued).run {
                scene.use {
                    state().setZoom(1.1f, focal = Offset(160f, 160f))
                    driver.pumpFrames(3)
                    open()
                    assertEquals(listOf(0), state().choiceDraft?.indices)
                    val target = assertNotNull(option(scene, "L"))
                    val bounds = target.boundsInRoot
                    tap(scene, target)
                    try {
                        driver.pumpUntilState(timeoutMs = 5_000) { scripts.formState.value("size") == "L" }
                    } catch (failure: AssertionError) {
                        File("build/choice-rotation-$rotation.png").writeBytes(driver.pumpFrames(1).encodeToData(EncodedImageFormat.PNG)!!.bytes)
                        val pointerProposals = scripts.proposals.toList()
                        val disabled = target.config.getOrNull(SemanticsProperties.Disabled) != null
                        throw AssertionError("rotation=$rotation bounds=$bounds widget=${state().choiceWidgetArea()} draft=${state().choiceDraft} pending=${state().choiceCommitPending} rejected=${state().choiceRejected} proposals=$pointerProposals disabled=$disabled commits=${scripts.commits}", failure)
                    }
                    assertEquals(listOf(2), scripts.formState.choiceSelection("size")?.indices)
                    assertEquals(1, scripts.commits.size)
                }
            }
        }
    }

    @Test
    fun deferred_list_runs_selection_keystroke_immediately_and_restores_a_refused_row() = forBothEffectOrders { queued ->
        val doc = form(flags = 0)
        val scripts = Scripts(doc).apply { refuseProposal = true }
        viewer(doc, scripts, queued).run {
            scene.use {
                open()
                tap(scene, assertNotNull(option(scene, "M")))
                driver.pumpUntilState { state().choiceRejected }
                assertEquals(listOf(PdfChoiceSelection(listOf(1))), scripts.proposals.toList())
                assertEquals(emptyList(), scripts.commits.toList(), "a deferred proposal does not run commit validation")
                assertEquals(listOf(0), state().choiceDraft?.indices)
                assertEquals("S", scripts.formState.value("size"))
                driver.pumpFrames(2)
                assertTrue(assertNotNull(option(scene, "S")).config[SemanticsProperties.Selected])
                scripts.refuseProposal = false
                tap(scene, assertNotNull(option(scene, "L")))
                driver.pumpUntilState { scripts.proposals.size == 2 && !state().choiceCommitPending }
                assertEquals(listOf(2), state().choiceDraft?.indices)
                assertEquals("S", scripts.formState.value("size"), "the accepted draft waits for Enter")
                press(scene, Key.Enter)
                driver.pumpUntilState { scripts.formState.value("size") == "L" }
                assertEquals(listOf(PdfChoiceSelection(listOf(2))), scripts.commits.toList())
                assertEquals(2, scripts.proposals.size, "committing does not repeat the selection-change trigger")
            }
        }
    }

    @Test
    fun escape_restores_an_editable_combos_original_typed_selection() = forBothEffectOrders { queued ->
        val doc = form(flags = COMBO or EDIT, options = "[[(small) (Small)] [(medium) (Medium)]]", value = "/V (small) /I [0]")
        val scripts = Scripts(doc)
        viewer(doc, scripts, queued).run {
            scene.use {
                open()
                driver.pumpUntilState { nodes(scene).any { it.config.getOrNull(SemanticsProperties.Focused) == true && it.config.getOrNull(SemanticsProperties.EditableText) != null } }
                val input = assertNotNull(nodes(scene).firstOrNull { it.config.getOrNull(SemanticsProperties.EditableText) != null })
                assertTrue(onTestUiThread { input.config[SemanticsActions.SetText].action!!.invoke(AnnotatedString("XL")) })
                driver.pumpUntilState { state().editingText == "XL" && scripts.formState.value("size") == "XL" }
                press(scene, Key.Escape)
                driver.pumpUntilState { state().choiceField == null && scripts.formState.value("size") == "small" }
                assertEquals(PdfChoiceSelection(listOf(0)), scripts.formState.choiceSelection("size"))
                assertEquals(0, scripts.commits.size, "Escape restores the original identity without validating a new value")
            }
        }
    }

    @Test
    fun enter_during_a_slow_list_proposal_queues_one_final_commit() = forBothEffectOrders { queued ->
        val doc = form(flags = 0)
        val scripts = Scripts(doc)
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        releaseAtEnd { release.countDown() }
        viewer(doc, scripts, queued).run {
            scene.use {
                open()
                scripts.onProposal = {
                    entered.countDown()
                    check(release.await(10, TimeUnit.SECONDS)) { "the test did not release the choice script" }
                }
                tap(scene, assertNotNull(option(scene, "M")))
                driver.pumpUntilState { entered.count == 0L }
                assertTrue(state().choiceCommitPending)
                press(scene, Key.Enter)
                driver.pumpUntilState { state().choiceField == null }
                assertEquals(0, scripts.commits.size)
                release.countDown()
                driver.pumpUntilState { scripts.formState.value("size") == "M" }
                assertEquals(listOf(PdfChoiceSelection(listOf(1))), scripts.proposals.toList())
                assertEquals(listOf(PdfChoiceSelection(listOf(1))), scripts.commits.toList())
            }
        }
    }

    @Test
    fun removing_or_replacing_the_handler_drops_an_open_choice_without_committing_its_draft() = forBothEffectOrders { queued ->
        val doc = form(flags = 0)
        val first = Scripts(doc)
        val second = Scripts(doc)
        var active: PdfScriptHandler? by mutableStateOf(first)
        lateinit var state: KiteDocViewState
        val (scene, driver) = drivenScene(320, 320, queued) {
            state = rememberKiteDocViewState(doc)
            KiteDocView(state, Modifier.fillMaxSize(), layout = KiteDocLayout.SinglePage(0), scripts = active)
        }
        scene.use {
            driver.pumpUntilState { state.pageGeometry.isNotEmpty() }
            val viewer = Viewer(scene, driver) { state }
            viewer.open()
            tap(scene, assertNotNull(option(scene, "M")))
            driver.pumpUntilState { !state.choiceCommitPending && state.choiceDraft?.indices == listOf(1) }
            active = null
            driver.pumpUntilState { state.choiceField == null }
            assertEquals("S", first.formState.value("size"))
            assertEquals(0, first.commits.size)
            active = first
            driver.pumpFrames(3)
            viewer.open()
            tap(scene, assertNotNull(option(scene, "L")))
            driver.pumpUntilState { !state.choiceCommitPending && state.choiceDraft?.indices == listOf(2) }
            active = second
            driver.pumpUntilState { state.choiceField == null }
            assertEquals("S", first.formState.value("size"))
            assertEquals("S", second.formState.value("size"))
            assertEquals(0, first.commits.size)
            assertEquals(0, second.commits.size)
        }
    }

    private companion object {
        const val COMBO = 1 shl 17
        const val EDIT = 1 shl 18
        const val MULTI = 1 shl 21
        const val COMMIT_ON_CHANGE = 1 shl 26
    }
}
