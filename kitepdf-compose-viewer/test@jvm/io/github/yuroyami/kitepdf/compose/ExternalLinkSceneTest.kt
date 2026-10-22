package io.github.yuroyami.kitepdf.compose

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import io.github.yuroyami.kitepdf.compose.EdtImageComposeScene as ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.unit.Density
import io.github.yuroyami.kitepdf.KitePDF
import io.github.yuroyami.kitepdf.core.KiteDocument
import io.github.yuroyami.kitepdf.epub.EpubDocument
import io.github.yuroyami.kitepdf.epub.EpubPage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A link to an address outside the document that the host does not take: the viewer asks the
 * reader, then opens a web or mail address through the platform, and never another scheme (#519).
 */
class ExternalLinkSceneTest {

    /** One 200 pt page whose top half is a link to [uri]. */
    private fun pdfLinkingTo(uri: String): ByteArray {
        val sb = StringBuilder("%PDF-1.4\n")
        val offsets = ArrayList<Int>()
        fun add(body: String) {
            offsets.add(sb.length)
            sb.append("${offsets.size} 0 obj\n$body\nendobj\n")
        }
        add("<< /Type /Catalog /Pages 2 0 R >>")
        add("<< /Type /Pages /Kids [3 0 R] /Count 1 /MediaBox [0 0 200 200] >>")
        add("<< /Type /Page /Parent 2 0 R /Resources << >> /Annots [4 0 R] >>")
        add("<< /Type /Annot /Subtype /Link /Rect [0 100 200 200] /A << /S /URI /URI ($uri) >> >>")
        val xref = sb.length
        sb.append("xref\n0 5\n0000000000 65535 f \n")
        for (o in offsets) sb.append("${o.toString().padStart(10, '0')} 00000 n \n")
        sb.append("trailer\n<< /Size 5 /Root 1 0 R >>\nstartxref\n$xref\n%%EOF\n")
        return sb.toString().encodeToByteArray()
    }

    private class Opened : UriHandler {
        val uris = ArrayList<String>()
        override fun openUri(uri: String) {
            uris += uri
        }
    }

    private class Viewer(val scene: ImageComposeScene, val state: KiteDocViewState, val driver: SceneTestDriver, val opened: Opened) {
        fun nodes(): List<SemanticsNode> = onTestUiThread {
            val out = ArrayList<SemanticsNode>()
            fun walk(node: SemanticsNode) {
                out += node
                node.children.forEach(::walk)
            }
            scene.semanticsOwners.forEach { walk(it.unmergedRootSemanticsNode) }
            return@onTestUiThread out
        }

        fun text(label: String): SemanticsNode? =
            nodes().firstOrNull { node -> node.config.getOrNull(SemanticsProperties.Text).orEmpty().any { it.text == label } }

        fun tap(at: Offset) {
            scene.sendPointerEvent(PointerEventType.Press, at, type = PointerType.Touch)
            scene.sendPointerEvent(PointerEventType.Release, at, type = PointerType.Touch)
        }
    }

    private fun withViewer(
        document: KiteDocument,
        externalLinks: KiteExternalLinks? = KiteExternalLinks(),
        onLinkTap: ((KiteLinkAction) -> Boolean)? = null,
        block: Viewer.() -> Unit,
    ) {
        val opened = Opened()
        val state = KiteDocViewState(document)
        ImageComposeScene(width = 200, height = 300, density = Density(1f)) {
            CompositionLocalProvider(LocalUriHandler provides opened) {
                KiteDocView(
                    state = state,
                    modifier = Modifier.fillMaxSize(),
                    zoomSpec = KiteZoomSpec(doubleTapEnabled = false),
                    onLinkTap = onLinkTap,
                    externalLinks = externalLinks,
                )
            }
        }.use { scene ->
            val driver = SceneTestDriver(scene)
            driver.pumpUntilState { state.pageGeometry.isNotEmpty() }
            driver.pumpFrames(3)
            Viewer(scene, state, driver, opened).block()
        }
    }

    @Test
    fun a_web_link_asks_the_reader_and_opens_the_address_after_open() {
        withViewer(KitePDF.open(pdfLinkingTo("https://example.com/kite"))) {
            tap(Offset(100f, 50f))
            driver.pumpUntilState { state.pendingExternalLink != null }
            assertEquals("https://example.com/kite", state.pendingExternalLink)
            driver.pumpFrames(2)
            assertNotNull(text("https://example.com/kite"), "the prompt does not show the address")
            assertNotNull(text("Open this link?"), "the prompt does not ask")
            assertTrue(opened.uris.isEmpty(), "the address opened before the reader said so")

            tap(assertNotNull(text("Open")).boundsInRoot.center)
            driver.pumpUntilState { state.pendingExternalLink == null }
            assertEquals(listOf("https://example.com/kite"), opened.uris)
            driver.pumpFrames(2)
            assertNull(text("Open this link?"), "the prompt stayed up")
        }
    }

    @Test
    fun cancel_and_a_tap_beside_the_prompt_open_nothing() {
        withViewer(KitePDF.open(pdfLinkingTo("mailto:reader@example.com"))) {
            tap(Offset(100f, 50f))
            driver.pumpUntilState { state.pendingExternalLink != null }
            driver.pumpFrames(2)
            tap(assertNotNull(text("Cancel")).boundsInRoot.center)
            driver.pumpUntilState { state.pendingExternalLink == null }

            tap(Offset(100f, 50f))
            driver.pumpUntilState { state.pendingExternalLink != null }
            driver.pumpFrames(2)
            tap(Offset(4f, 296f))
            driver.pumpUntilState { state.pendingExternalLink == null }
            driver.pumpFrames(2)
            assertEquals(emptyList(), opened.uris)
        }
    }

    @Test
    fun an_address_whose_scheme_is_not_allowed_opens_nothing() {
        for (uri in listOf("javascript:alert(1)", "file:///etc/passwd", "intent://scan#Intent;end")) {
            withViewer(KitePDF.open(pdfLinkingTo(uri))) {
                tap(Offset(100f, 50f))
                driver.pumpFrames(5)
                assertNull(state.pendingExternalLink, uri)
                assertEquals(emptyList(), opened.uris, uri)
            }
        }
    }

    @Test
    fun the_host_takes_the_link_first_and_null_turns_the_prompt_off() {
        val seen = ArrayList<String?>()
        withViewer(KitePDF.open(pdfLinkingTo("https://example.com/a")), onLinkTap = { seen += it.uri; true }) {
            tap(Offset(100f, 50f))
            driver.pumpFrames(5)
            assertEquals(listOf<String?>("https://example.com/a"), seen)
            assertNull(state.pendingExternalLink)
        }
        withViewer(KitePDF.open(pdfLinkingTo("https://example.com/b")), externalLinks = null) {
            tap(Offset(100f, 50f))
            driver.pumpFrames(5)
            assertNull(state.pendingExternalLink)
            assertEquals(emptyList(), opened.uris)
        }
    }

    @Test
    fun without_asking_the_address_opens_at_once() {
        withViewer(KitePDF.open(pdfLinkingTo("https://example.com/now")), externalLinks = KiteExternalLinks(askFirst = false)) {
            tap(Offset(100f, 50f))
            driver.pumpUntilState { opened.uris.isNotEmpty() }
            assertEquals(listOf("https://example.com/now"), opened.uris)
            assertNull(state.pendingExternalLink)
        }
    }

    @Test
    fun a_mail_link_in_a_book_asks_and_opens() {
        val book = EpubDocument.open(multiSpineEpub(listOf("""<p><a href="mailto:author@example.com">Write to the author</a></p>""")))
        withViewer(book) {
            val page = assertNotNull(state.pageAt(0) as? EpubPage)
            val link = page.links.single()
            val box = assertNotNull(state.displayRectToViewport(0, link.rect))
            tap(box.center)
            driver.pumpUntilState { state.pendingExternalLink != null }
            assertEquals("mailto:author@example.com", state.pendingExternalLink)
            driver.pumpFrames(2)
            tap(assertNotNull(text("Open")).boundsInRoot.center)
            driver.pumpUntilState { opened.uris.isNotEmpty() }
            assertEquals(listOf("mailto:author@example.com"), opened.uris)
        }
    }
}
