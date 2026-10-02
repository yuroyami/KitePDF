package io.github.yuroyami.kitepdf.compose

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.rememberCoroutineScope
import io.github.yuroyami.kitepdf.compose.EdtImageComposeScene as ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.unit.Density
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

    /**
     * Three tall pages. Page 0 links out, to the height [targetY] of page 1, and to the height 300
     * of itself. Page 2 follows, so a strip can scroll that far.
     */
    private fun document(targetY: Double = 150.0): Pages = Pages(
        listOf(
            LinkPage(
                200.0, 600.0,
                listOf(
                    KiteLink(KiteRectangle(20.0, 20.0, 90.0, 90.0), uri = "https://example.com/x"),
                    KiteLink(KiteRectangle(110.0, 110.0, 180.0, 180.0), target = KiteBookmark.Page(1), targetY = { targetY }),
                    KiteLink(KiteRectangle(20.0, 400.0, 90.0, 470.0), target = KiteBookmark.Page(0), targetY = { 300.0 }),
                ),
            ),
            LinkPage(200.0, 600.0),
            LinkPage(200.0, 600.0),
        ),
    )

    private fun withViewer(
        layout: KiteDocLayout,
        document: Pages = document(),
        zoomSpec: KiteZoomSpec = KiteZoomSpec(),
        block: (KiteDocViewState, CoroutineScope, SceneTestDriver, ImageComposeScene) -> Unit,
    ) {
        lateinit var state: KiteDocViewState
        lateinit var scope: CoroutineScope
        ImageComposeScene(width = 200, height = 320, density = Density(1f)) {
            state = rememberKiteDocViewState(document)
            scope = rememberCoroutineScope()
            KiteDocView(state = state, modifier = Modifier.fillMaxSize(), layout = layout, zoomSpec = zoomSpec)
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
            val outward = offered.single() as KiteLinkAction.Plain
            assertEquals("https://example.com/x", outward.uri)
            assertEquals(null, outward.target)
            assertEquals(0, outward.pageIndex)
            assertEquals(KiteRectangle(20.0, 20.0, 90.0, 90.0), outward.rect)

            // A host that takes a link inside the document keeps the view where it is.
            val inside = assertNotNull(state.displayToViewport(0, 145.0, 145.0))
            assertTrue(handleLinkTap(state, scope, { true }, inside))
            driver.pumpFrames(10)
            assertEquals(0, state.currentScrollPosition.location.page, "the view moved for a link that the host took")

            // Declined, the viewer follows it to its place.
            assertTrue(handleLinkTap(state, scope, { offered += it; false }, inside))
            driver.pumpUntilState { state.currentScrollPosition.location.page == 1 && state.currentScrollPosition.offsetPx > 0 }
            driver.pumpFrames(30)
            val position = state.currentScrollPosition
            assertEquals(1, position.location.page)
            // The page is 600 tall in a 200 wide strip, so height 150 is 150 px into its slot.
            assertEquals(150f, position.offsetPx.toFloat(), 2f, "the strip did not scroll to the place: $position")
            assertEquals(KiteBookmark.Page(1), offered.last().target, "the link inside the document did not go to the host first")

            // Paper that no link covers falls through to onTap.
            val blank = assertNotNull(state.displayToViewport(1, 100.0, 400.0))
            assertFalse(handleLinkTap(state, scope, { offered += it; true }, blank))
        }
    }

    /** Follows the link to page 1, and waits until the view rests on that page. */
    private fun followToPageOne(state: KiteDocViewState, scope: CoroutineScope, driver: SceneTestDriver) {
        val inside = assertNotNull(state.displayToViewport(0, 145.0, 145.0))
        assertTrue(handleLinkTap(state, scope, null, inside))
        driver.pumpUntilState { state.currentPage == 1 && state.adapter?.isScrollInProgress != true }
        driver.pumpFrames(30)
    }

    /** Where the height [y] of page [page] shows on the screen, in viewport px from the top. */
    private fun screenY(state: KiteDocViewState, page: Int, y: Double): Float = assertNotNull(state.displayToViewport(page, 100.0, y)).y

    @Test
    fun a_zoomed_strip_brings_the_place_to_the_top_of_the_screen() {
        // At 150 the place is below the top the zoom shows; at 40 it is above it, on the page before.
        for (target in listOf(150.0, 40.0)) {
            withViewer(KiteDocLayout.Default, document(target)) { state, scope, driver, _ ->
                onTestUiThread { state.setZoom(2f) }
                followToPageOne(state, scope, driver)
                assertEquals(0f, screenY(state, 1, target), 2f, "the height $target is not at the top of the screen")
            }
        }
    }

    @Test
    fun a_zoomed_horizontal_strip_pans_the_place_to_the_top_of_the_screen() {
        withViewer(KiteDocLayout.Continuous(androidx.compose.foundation.gestures.Orientation.Horizontal), document(300.0)) { state, scope, driver, _ ->
            onTestUiThread { state.setZoom(2f) }
            followToPageOne(state, scope, driver)
            assertEquals(0f, screenY(state, 1, 300.0), 2f, "the pan did not bring the place to the top")
        }
    }

    @Test
    fun a_zoomed_pager_that_keeps_its_zoom_pans_the_place_to_the_top_of_the_screen() {
        withViewer(KiteDocLayout.Paged(), document(300.0), KiteZoomSpec(resetZoomOnPageChange = false)) { state, scope, driver, _ ->
            onTestUiThread { state.setZoom(2f) }
            driver.pumpFrames(2)
            // A place on the page in view pans there at once.
            val here = assertNotNull(state.displayToViewport(0, 55.0, 435.0))
            assertTrue(handleLinkTap(state, scope, null, here))
            driver.pumpFrames(2)
            assertEquals(0f, screenY(state, 0, 300.0), 2f, "the pan did not bring the place on this page to the top")
            // A place on another page pans there once the pager lands, after it recentres.
            followToPageOne(state, scope, driver)
            assertEquals(2f, state.zoom)
            assertEquals(0f, screenY(state, 1, 300.0), 2f, "the pan did not bring the place on the next page to the top")
        }
    }

    @Test
    fun a_zoomed_spread_that_keeps_its_zoom_pans_toward_the_place() {
        withViewer(KiteDocLayout.Spread(firstPageAlone = true), document(300.0), KiteZoomSpec(resetZoomOnPageChange = false)) { state, scope, driver, _ ->
            onTestUiThread { state.setZoom(2f) }
            driver.pumpFrames(2)
            followToPageOne(state, scope, driver)
            // Two tall pages side by side are short on screen, so the pan stops at its bound, well above the centre.
            val y = screenY(state, 1, 300.0)
            assertTrue(y < 60f, "the spread did not pan toward the place: it is at $y")
        }
    }

    @Test
    fun a_pager_that_resets_its_zoom_shows_the_whole_page_it_lands_on() {
        withViewer(KiteDocLayout.Paged(), document(300.0)) { state, scope, driver, _ ->
            onTestUiThread { state.setZoom(2f) }
            driver.pumpFrames(2)
            followToPageOne(state, scope, driver)
            assertEquals(1f, state.zoom)
            // The pan is centred, which clampPan can give as -0.
            assertEquals(0f, state.panOffset.x, 0.01f)
            assertEquals(0f, state.panOffset.y, 0.01f)
        }
    }

    @Test
    fun one_fixed_page_lets_a_link_in_fall_through_and_still_offers_a_link_out() {
        withViewer(KiteDocLayout.SinglePage(0)) { state, scope, _, _ ->
            val offered = mutableListOf<KiteLinkAction>()
            val inside = assertNotNull(state.displayToViewport(0, 145.0, 145.0))
            assertFalse(handleLinkTap(state, scope, { offered += it; it.uri != null }, inside))
            val out = assertNotNull(state.displayToViewport(0, 55.0, 55.0))
            assertTrue(handleLinkTap(state, scope, { offered += it; it.uri != null }, out))
            assertEquals(listOf(KiteBookmark.Page(1), null), offered.map { it.target })
            assertEquals(listOf(null, "https://example.com/x"), offered.map { it.uri })
        }
    }

    @Test
    fun a_screen_reader_finds_each_link_as_a_button() {
        withViewer(KiteDocLayout.Default) { _, _, driver, scene ->
            fun nodes(): List<SemanticsNode> = onTestUiThread {
                val out = ArrayList<SemanticsNode>()
                fun walk(node: SemanticsNode) {
                    out += node
                    node.children.forEach(::walk)
                }
                scene.semanticsOwners.forEach { walk(it.unmergedRootSemanticsNode) }
                return@onTestUiThread out
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
