package io.github.yuroyami.kitepdf.compose

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.unit.dp
import io.github.yuroyami.kitepdf.core.KiteDocument
import io.github.yuroyami.kitepdf.core.KiteOutlineItem
import io.github.yuroyami.kitepdf.core.KitePage
import io.github.yuroyami.kitepdf.core.render.KiteCanvas
import io.github.yuroyami.kitepdf.core.render.KiteMatrix
import kotlin.test.Test
import kotlin.test.assertNotNull

/** An outline entry's navigation ends even when the host closes the panel in onNavigate, as the docs show (#663). */
class OutlineCloseSceneTest {

    private class Blank : KitePage {
        override val displayWidth: Double = 100.0
        override val displayHeight: Double = 100.0
        override fun displayToDeviceBase(): KiteMatrix = KiteMatrix(1.0, 0.0, 0.0, -1.0, 0.0, displayHeight)
        override fun renderTo(canvas: KiteCanvas, deviceCtm: KiteMatrix) = Unit
    }

    private class Pages(count: Int) : KiteDocument {
        override val pageCount: Int = count
        override val pages: List<KitePage> = List(count) { Blank() }
    }

    private fun nodes(scene: EdtImageComposeScene): List<SemanticsNode> = onTestUiThread {
        val out = ArrayList<SemanticsNode>()
        fun walk(node: SemanticsNode) {
            out += node
            node.children.forEach(::walk)
        }
        scene.semanticsOwners.forEach { walk(it.unmergedRootSemanticsNode) }
        return@onTestUiThread out
    }

    @Test
    fun closing_the_panel_in_on_navigate_keeps_the_navigation() = forBothEffectOrders { queued ->
        val state = KiteDocViewState(Pages(6))
        var showOutline by mutableStateOf(true)
        val (scene, driver) = drivenScene(200, 400, queued) {
            Column {
                KiteDocView(state = state, modifier = Modifier.fillMaxWidth().height(200.dp), layout = KiteDocLayout.Paged())
                if (showOutline) {
                    KiteOutlinePanel(state, outline = listOf(KiteOutlineItem("Last page", pageIndex = 5)), onNavigate = { showOutline = false })
                }
            }
        }
        scene.use {
            driver.pumpUntilState { state.adapter != null && nodes(scene).any { n -> n.config.getOrNull(SemanticsProperties.Text)?.any { it.text == "Last page" } == true } }
            val entry = assertNotNull(
                nodes(scene).firstOrNull { n -> n.config.getOrNull(SemanticsActions.OnClick) != null && n.config.getOrNull(SemanticsProperties.Text)?.any { it.text == "Last page" } == true }
                    ?: nodes(scene).firstOrNull { n -> n.config.getOrNull(SemanticsActions.OnClick) != null && n.children.any { c -> c.config.getOrNull(SemanticsProperties.Text)?.any { it.text == "Last page" } == true } },
                "no clickable outline entry",
            )
            onTestUiThread { entry.config[SemanticsActions.OnClick].action!!.invoke() }
            driver.pumpUntilState { !showOutline }
            driver.pumpUntilState { state.currentPage == 5 && state.adapter?.isScrollInProgress != true }
        }
    }
}
