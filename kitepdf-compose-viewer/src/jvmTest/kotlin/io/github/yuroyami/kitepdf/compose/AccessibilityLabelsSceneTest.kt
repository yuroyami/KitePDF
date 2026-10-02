package io.github.yuroyami.kitepdf.compose

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.CompositionLocalProvider
import io.github.yuroyami.kitepdf.compose.EdtImageComposeScene as ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import io.github.yuroyami.kitepdf.KitePDF
import io.github.yuroyami.kitepdf.writer.PdfBuilder
import io.github.yuroyami.kitepdf.writer.StandardFont
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * A screen reader finds each page, the page buttons, the thumbnails and the colours of the
 * selection menu by name, in words a host can translate. None of them had a name (#427).
 */
class AccessibilityLabelsSceneTest {

    private fun doc() = KitePDF.open(
        PdfBuilder().apply {
            repeat(3) { i -> page(width = 200.0, height = 300.0) { text(StandardFont.Helvetica, 14.0, 20.0, 250.0, "Page text $i") } }
        }.build(),
    )

    private fun nodes(scene: ImageComposeScene): List<SemanticsNode> = onTestUiThread {
        val out = ArrayList<SemanticsNode>()
        fun walk(node: SemanticsNode) {
            out += node
            node.children.forEach(::walk)
        }
        scene.semanticsOwners.forEach { walk(it.unmergedRootSemanticsNode) }
        return@onTestUiThread out
    }

    private fun named(scene: ImageComposeScene, name: String): SemanticsNode? =
        nodes(scene).firstOrNull { name in it.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty() }

    private fun withViewer(strings: KiteViewerStrings = KiteViewerStrings(), block: (ImageComposeScene, KiteDocViewState, SceneTestDriver) -> Unit) {
        val state = KiteDocViewState(doc())
        ImageComposeScene(width = 200, height = 500, density = Density(1f)) {
            CompositionLocalProvider(LocalKiteViewerStrings provides strings) {
                Column {
                    KiteDocView(state = state, modifier = Modifier.fillMaxWidth().height(300.dp), layout = KiteDocLayout.Paged())
                    KiteNavigationControls(state)
                    KiteThumbnailStrip(state, modifier = Modifier.fillMaxWidth().height(100.dp))
                }
            }
        }.use { scene ->
            val driver = SceneTestDriver(scene)
            driver.pumpUntilState { state.pageGeometry.isNotEmpty() }
            driver.pumpFrames(5)
            block(scene, state, driver)
        }
    }

    @Test
    fun pages_buttons_and_thumbnails_have_names_and_roles() {
        withViewer { scene, _, _ ->
            val page = assertNotNull(named(scene, "Page 1 of 3"), "no node names the page")
            assertEquals(Role.Image, page.config.getOrNull(SemanticsProperties.Role))
            for (button in listOf("Previous page", "Next page")) {
                val node = assertNotNull(named(scene, button), "no node names the $button button")
                assertEquals(Role.Button, node.config.getOrNull(SemanticsProperties.Role))
            }
            val thumbnails = nodes(scene).filter {
                it.config.getOrNull(SemanticsProperties.Role) == Role.Button &&
                    it.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty().any { name -> name.startsWith("Page ") }
            }
            assertTrue(thumbnails.size >= 3, "thumbnails: ${thumbnails.map { it.config.getOrNull(SemanticsProperties.ContentDescription) }}")
            assertEquals(1, thumbnails.count { it.config.getOrNull(SemanticsProperties.Selected) == true }, "one thumbnail is the current page")
        }
    }

    @Test
    fun a_host_can_translate_the_names() {
        val german = KiteViewerStrings(
            page = { number, count -> if (count == null) "Seite $number" else "Seite $number von $count" },
            previousPage = "Vorherige Seite",
            nextPage = "Nächste Seite",
        )
        withViewer(german) { scene, _, _ ->
            assertNotNull(named(scene, "Seite 1 von 3"))
            assertNotNull(named(scene, "Nächste Seite"))
        }
    }

    @Test
    fun the_colours_of_the_selection_menu_have_names() {
        val state = KiteDocViewState(doc())
        ImageComposeScene(width = 200, height = 300, density = Density(1f)) {
            KiteDocView(
                state = state, modifier = Modifier.fillMaxWidth().height(300.dp), layout = KiteDocLayout.Paged(),
                overlay = {
                    KiteSelectionMenu(
                        state = state, items = emptyList(),
                        highlightColors = listOf(Color.Yellow, Color.Green),
                        onHighlightColorPicked = { _, _ -> },
                    )
                },
            )
        }.use { scene ->
            val driver = SceneTestDriver(scene)
            driver.pumpUntilState { state.pageGeometry.isNotEmpty() }
            onTestUiThread { state.beginSelection(assertNotNull(state.displayToViewport(0, 22.0, 45.0))) }
            onTestUiThread { state.extendSelection(assertNotNull(state.displayToViewport(0, 90.0, 45.0))) }
            onTestUiThread { state.endSelectionGesture() }
            driver.pumpFrames(5)
            assertNotNull(named(scene, "Highlight colour 1 of 2"))
            assertNotNull(named(scene, "Highlight colour 2 of 2"))
        }
    }
}
