package io.github.yuroyami.kitepdf.compose

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.unit.Density
import androidx.compose.ui.use
import io.github.yuroyami.kitepdf.core.KiteBookmark
import io.github.yuroyami.kitepdf.core.KiteDocument
import io.github.yuroyami.kitepdf.core.KiteLink
import io.github.yuroyami.kitepdf.core.KitePage
import io.github.yuroyami.kitepdf.core.KiteRectangle
import io.github.yuroyami.kitepdf.core.render.KiteCanvas
import io.github.yuroyami.kitepdf.core.render.KiteMatrix
import kotlinx.coroutines.CoroutineScope
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * A page that gives plain links, as XPS and SVG pages do, has them followed on a tap and named
 * to a screen reader (#433).
 */
class PlainLinkSceneTest {

    /** A blank page with [hyperlinks], drawn y down as XPS and SVG pages are. */
    private class LinkPage(
        override val displayWidth: Double,
        override val displayHeight: Double,
        override val hyperlinks: List<KiteLink> = emptyList(),
    ) : KitePage {
        override fun displayToDeviceBase(): KiteMatrix = KiteMatrix.IDENTITY
        override fun renderTo(canvas: KiteCanvas, deviceCtm: KiteMatrix) {
            canvas.beginPage(displayWidth, displayHeight, deviceCtm)
            canvas.endPage()
        }
    }

    private class Pages(override val pages: List<KitePage>) : KiteDocument {
        override val pageCount: Int get() = pages.size
    }

    /** Page 0 links out, and to the height 150 of page 1, a tall page; page 2 follows, so the strip can scroll that far. */
    private fun document(): Pages = Pages(
        listOf(
            LinkPage(
                200.0, 200.0,
                listOf(
                    KiteLink(KiteRectangle(20.0, 20.0, 90.0, 90.0), uri = "https://example.com/x"),
                    KiteLink(KiteRectangle(110.0, 110.0, 180.0, 180.0), target = KiteBookmark.Page(1), targetY = { 150.0 }),
                ),
            ),
            LinkPage(200.0, 600.0),
            LinkPage(200.0, 600.0),
        ),
    )

    private fun withViewer(layout: KiteDocLayout, block: (KiteDocViewState, CoroutineScope, SceneTestDriver, ImageComposeScene) -> Unit) {
        lateinit var state: KiteDocViewState
        lateinit var scope: CoroutineScope
        ImageComposeScene(width = 200, height = 320, density = Density(1f)) {
            state = rememberKiteDocViewState(document())
            scope = rememberCoroutineScope()
            KiteDocView(state = state, modifier = Modifier.fillMaxSize(), layout = layout)
        }.use { scene ->
            val driver = SceneTestDriver(scene)
            driver.pumpUntilState { state.pageGeometry.containsKey(0) }
            block(state, scope, driver, scene)
        }
    }

    @Test
    fun a_link_out_goes_to_the_host_and_a_link_in_scrolls_to_its_place() {
        withViewer(KiteDocLayout.Default) { state, scope, driver, _ ->
            val offered = mutableListOf<KiteLinkAction>()
            val out = assertNotNull(state.displayToViewport(0, 55.0, 55.0))
            assertTrue(handleLinkTap(state, scope, { offered += it; true }, out))
            assertEquals(listOf<KiteLinkAction>(KiteLinkAction.Uri("https://example.com/x")), offered)

            val inside = assertNotNull(state.displayToViewport(0, 145.0, 145.0))
            assertTrue(handleLinkTap(state, scope, { offered += it; true }, inside))
            driver.pumpUntilState { state.currentScrollPosition.location.page == 1 && state.currentScrollPosition.offsetPx > 0 }
            driver.pumpFrames(30)
            val position = state.currentScrollPosition
            assertEquals(1, position.location.page)
            // The page is 600 tall in a 200 wide strip, so height 150 is 150 px into its slot.
            assertEquals(150f, position.offsetPx.toFloat(), 2f, "the strip did not scroll to the place: $position")
            assertEquals(1, offered.size, "a link inside the document does not go to the host")

            // Paper that no link covers falls through to onTap.
            val blank = assertNotNull(state.displayToViewport(1, 100.0, 400.0))
            assertFalse(handleLinkTap(state, scope, { offered += it; true }, blank))
        }
    }

    @Test
    fun one_fixed_page_lets_a_link_in_fall_through_and_still_offers_a_link_out() {
        withViewer(KiteDocLayout.SinglePage(0)) { state, scope, _, _ ->
            val offered = mutableListOf<KiteLinkAction>()
            val inside = assertNotNull(state.displayToViewport(0, 145.0, 145.0))
            assertFalse(handleLinkTap(state, scope, { offered += it; true }, inside))
            val out = assertNotNull(state.displayToViewport(0, 55.0, 55.0))
            assertTrue(handleLinkTap(state, scope, { offered += it; true }, out))
            assertEquals(listOf<KiteLinkAction>(KiteLinkAction.Uri("https://example.com/x")), offered)
        }
    }

    @Test
    fun a_screen_reader_finds_each_link_as_a_button() {
        withViewer(KiteDocLayout.Default) { _, _, driver, scene ->
            fun nodes(): List<SemanticsNode> {
                val out = ArrayList<SemanticsNode>()
                fun walk(node: SemanticsNode) {
                    out += node
                    node.children.forEach(::walk)
                }
                scene.semanticsOwners.forEach { walk(it.unmergedRootSemanticsNode) }
                return out
            }
            fun named(name: String) = nodes().firstOrNull { it.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty().contains(name) }
            driver.pumpUntilState { named("https://example.com/x") != null }
            val out = assertNotNull(named("https://example.com/x"))
            assertEquals(Role.Button, out.config.getOrNull(SemanticsProperties.Role))
            // A link with no words and no address takes the viewer's name for a link.
            val inside = assertNotNull(named(KiteViewerStrings().link))
            assertEquals(Role.Button, inside.config.getOrNull(SemanticsProperties.Role))
        }
    }
}
