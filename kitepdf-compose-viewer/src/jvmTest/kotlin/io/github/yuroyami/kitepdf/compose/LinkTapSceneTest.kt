package io.github.yuroyami.kitepdf.compose

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.unit.Density
import androidx.compose.ui.use
import io.github.yuroyami.kitepdf.KitePDF
import io.github.yuroyami.kitepdf.PdfAction
import io.github.yuroyami.kitepdf.core.KiteBookmark
import io.github.yuroyami.kitepdf.core.KiteLinkKind
import io.github.yuroyami.kitepdf.core.KiteRectangle
import io.github.yuroyami.kitepdf.epub.EpubDocument
import io.github.yuroyami.kitepdf.epub.EpubSettings
import java.io.ByteArrayOutputStream
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Taps on links. Every link goes to `onLinkTap` first, in every format. When the
 * host does not take it, a PDF link follows its resolved destination, and an
 * EPUB link follows its internal href. The tap path is exercised through the
 * real composed layout (hitTest geometry) by invoking the internal handler with
 * computed offsets.
 */
class LinkTapSceneTest {

    /* ─── PDF fixture: 2 pages, page 0 carries links ─────────────────────── */

    private fun pdfWithLinks(): ByteArray {
        val sb = StringBuilder()
        val offsets = ArrayList<Int>()
        fun add(s: String) {
            offsets.add(sb.length)
            sb.append(s)
        }
        sb.append("%PDF-1.4\n")
        add("1 0 obj\n<< /Type /Catalog /Pages 2 0 R >>\nendobj\n")
        add("2 0 obj\n<< /Type /Pages /Kids [3 0 R 4 0 R] /Count 2 /MediaBox [0 0 200 200] >>\nendobj\n")
        add("3 0 obj\n<< /Type /Page /Parent 2 0 R /Resources << >> /Annots [5 0 R 6 0 R] >>\nendobj\n")
        add("4 0 obj\n<< /Type /Page /Parent 2 0 R /Resources << >> >>\nendobj\n")
        // Bottom-left quadrant: an internal GoTo destination to page 2.
        add("5 0 obj\n<< /Type /Annot /Subtype /Link /Rect [20 20 90 90] /Dest [4 0 R /Fit] >>\nendobj\n")
        // Top-right quadrant: a URI action.
        add(
            "6 0 obj\n<< /Type /Annot /Subtype /Link /Rect [110 110 180 180] " +
                "/A << /S /URI /URI (https://example.com/kite) >> >>\nendobj\n",
        )
        val xref = sb.length
        sb.append("xref\n0 7\n0000000000 65535 f \n")
        for (o in offsets) sb.append("${o.toString().padStart(10, '0')} 00000 n \n")
        sb.append("trailer\n<< /Size 7 /Root 1 0 R >>\nstartxref\n$xref\n%%EOF\n")
        return sb.toString().encodeToByteArray()
    }

    @Test
    fun pdf_link_tap_navigates_and_uri_link_reaches_the_callback() {
        val doc = KitePDF.open(pdfWithLinks())
        lateinit var state: KiteDocViewState
        lateinit var scope: CoroutineScope
        val openedUris = mutableListOf<String>()
        // 200x320 viewport: the 200pt page maps 1:1 to px, page 0 at y 0..200.
        ImageComposeScene(width = 200, height = 320, density = Density(1f)) {
            state = rememberKiteDocViewState(doc)
            scope = rememberCoroutineScope()
            KiteDocView(state = state, modifier = Modifier.fillMaxSize())
        }.use { scene ->
            val driver = SceneTestDriver(scene)
            driver.pumpUntil { state.pageGeometry.isNotEmpty() }

            // PDF links arrive as KiteLinkAction.Pdf with the parsed action intact.
            val offered = mutableListOf<KiteLinkAction>()
            val onLinkTap: (KiteLinkAction) -> Boolean = { link ->
                offered += link
                val action = (link as? KiteLinkAction.Pdf)?.action
                (action as? PdfAction.Uri)?.let { openedUris.add(it.uri) } != null
            }

            // Link rect [20..90]x[20..90] user space (y-up) = display y 110..180:
            // its centre is viewport (55, 145).
            assertTrue(handleLinkTap(state, scope, onLinkTap, Offset(55f, 145f)), "GoTo link consumes the tap")
            driver.pumpUntil { state.currentPage == 1 }
            assertEquals(1, state.currentPage, "the destination link navigated to page 2")
            assertTrue(openedUris.isEmpty(), "the GoTo link opened an address")
            // The host saw the go-to link first, with the facts that every format gives.
            val goTo = offered.single() as KiteLinkAction.Pdf
            assertTrue(goTo.action is PdfAction.GoTo, "${goTo.action}")
            assertEquals(KiteBookmark.Page(1), goTo.target)
            assertEquals(null, goTo.uri)
            assertEquals(KiteLinkKind.LINK, goTo.kind)
            assertEquals(0, goTo.pageIndex)
            assertEquals(KiteRectangle(20.0, 110.0, 90.0, 180.0), goTo.rect, "the rect is in display space, y down")

            // Back to page 0 for the URI link (rect [110..180] user = display y 20..90).
            scope.launch { state.scrollToPage(0) }
            driver.pumpUntil { state.currentPage == 0 }
            assertTrue(handleLinkTap(state, scope, onLinkTap, Offset(145f, 55f)), "URI link consumed via callback")
            assertEquals(listOf("https://example.com/kite"), openedUris)
            assertEquals("https://example.com/kite", offered.last().uri)
            assertEquals(null, offered.last().target)

            // Empty page area: not consumed, falls through to onTap.
            assertFalse(handleLinkTap(state, scope, onLinkTap, Offset(100f, 100f)))
        }
    }

    /* ─── EPUB fixture: chapter 1 links to chapter 2 ─────────────────────── */

    private fun storedZip(entries: List<Pair<String, ByteArray>>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zos ->
            zos.setMethod(ZipOutputStream.STORED)
            for ((name, data) in entries) {
                val e = ZipEntry(name)
                e.method = ZipEntry.STORED
                e.size = data.size.toLong()
                e.crc = CRC32().apply { update(data) }.value
                zos.putNextEntry(e)
                zos.write(data)
                zos.closeEntry()
            }
        }
        return out.toByteArray()
    }

    private fun epubWithLink(): EpubDocument {
        val container = """<?xml version="1.0"?><container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container"><rootfiles><rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/></rootfiles></container>"""
        val opf = """<?xml version="1.0"?>
            <package xmlns="http://www.idpf.org/2007/opf" version="3.0" unique-identifier="id">
              <metadata xmlns:dc="http://purl.org/dc/elements/1.1/"><dc:identifier id="id">x</dc:identifier></metadata>
              <manifest>
                <item id="c1" href="ch1.xhtml" media-type="application/xhtml+xml"/>
                <item id="c2" href="ch2.xhtml" media-type="application/xhtml+xml"/>
              </manifest>
              <spine><itemref idref="c1"/><itemref idref="c2"/></spine>
            </package>"""
        val filler = (1..30).joinToString("") { "<p>filler paragraph $it keeps chapter one long</p>" }
        val ch1 = """<?xml version="1.0"?><html xmlns="http://www.w3.org/1999/xhtml"><body><p><a href="ch2.xhtml">go to chapter two</a></p>$filler</body></html>"""
        val ch2 = """<?xml version="1.0"?><html xmlns="http://www.w3.org/1999/xhtml"><body><p>chapter two content</p></body></html>"""
        val zip = storedZip(
            listOf(
                "mimetype" to "application/epub+zip".encodeToByteArray(),
                "META-INF/container.xml" to container.encodeToByteArray(),
                "OEBPS/content.opf" to opf.encodeToByteArray(),
                "OEBPS/ch1.xhtml" to ch1.encodeToByteArray(),
                "OEBPS/ch2.xhtml" to ch2.encodeToByteArray(),
            ),
        )
        return EpubDocument.open(zip, EpubSettings(pageWidth = 200.0, pageHeight = 200.0))
            ?: error("EPUB fixture failed to open")
    }

    @Test
    fun epub_internal_link_tap_navigates_to_the_target_chapter() {
        val doc = epubWithLink()
        val targetPage = doc.pageOf("OEBPS/ch2.xhtml")
        assertNotNull(targetPage, "ch2 resolves to a page")
        assertTrue(targetPage > 0, "ch2 starts after ch1")

        val epubPage = doc.pages[0] as io.github.yuroyami.kitepdf.epub.EpubPage
        val link = epubPage.links.single()
        assertEquals("OEBPS/ch2.xhtml", link.href)

        lateinit var state: KiteDocViewState
        lateinit var scope: CoroutineScope
        ImageComposeScene(width = 200, height = 320, density = Density(1f)) {
            state = rememberKiteDocViewState(doc)
            scope = rememberCoroutineScope()
            KiteDocView(state = state, modifier = Modifier.fillMaxSize())
        }.use { scene ->
            val driver = SceneTestDriver(scene)
            driver.pumpUntil { state.pageGeometry.isNotEmpty() }

            // Page 0's slot maps 1:1 (200pt page in a 200px-wide slot at y 0);
            // link rects are already display-space y-down.
            val tap = Offset(
                ((link.rect.left + link.rect.right) / 2).toFloat(),
                ((link.rect.bottom + link.rect.top) / 2).toFloat(),
            )
            assertTrue(handleLinkTap(state, scope, null, tap), "internal href consumes the tap")
            driver.pumpUntil { state.currentPage == targetPage }
            assertEquals(targetPage, state.currentPage)

            // A miss (page margin) is not consumed.
            assertFalse(handleLinkTap(state, scope, null, Offset(5f, 5f)))
        }
    }

    private fun epubWithExternalLink(): EpubDocument {
        val container = """<?xml version="1.0"?><container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container"><rootfiles><rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/></rootfiles></container>"""
        val opf = """<?xml version="1.0"?>
            <package xmlns="http://www.idpf.org/2007/opf" version="3.0" unique-identifier="id">
              <metadata xmlns:dc="http://purl.org/dc/elements/1.1/"><dc:identifier id="id">x</dc:identifier></metadata>
              <manifest><item id="c1" href="ch1.xhtml" media-type="application/xhtml+xml"/></manifest>
              <spine><itemref idref="c1"/></spine>
            </package>"""
        val ch1 = """<?xml version="1.0"?><html xmlns="http://www.w3.org/1999/xhtml"><body><p><a href="https://example.org/out">outside</a></p></body></html>"""
        val zip = storedZip(
            listOf(
                "mimetype" to "application/epub+zip".encodeToByteArray(),
                "META-INF/container.xml" to container.encodeToByteArray(),
                "OEBPS/content.opf" to opf.encodeToByteArray(),
                "OEBPS/ch1.xhtml" to ch1.encodeToByteArray(),
            ),
        )
        return EpubDocument.open(zip, EpubSettings(pageWidth = 200.0, pageHeight = 200.0))
            ?: error("EPUB fixture failed to open")
    }

    /** An EPUB href with a scheme reaches the callback with its address, and a declined one falls through. */
    @Test
    fun epub_external_link_tap_reports_its_uri() {
        val doc = epubWithExternalLink()
        val epubPage = doc.pages[0] as io.github.yuroyami.kitepdf.epub.EpubPage
        val link = epubPage.links.single()
        assertEquals("https://example.org/out", link.href)
        withViewer(doc) { state, scope, _ ->
            val seen = mutableListOf<KiteLinkAction>()
            assertTrue(handleLinkTap(state, scope, { seen.add(it); true }, centreOf(link)))
            val tapped = seen.single() as KiteLinkAction.Epub
            assertEquals("https://example.org/out", tapped.uri, "uri reads back without a when")
            assertEquals(null, tapped.target)
            assertEquals(KiteLinkKind.LINK, tapped.kind)
            assertEquals(link.rect, tapped.rect)
            // Declined, a link out of the book does nothing, so the tap goes on to onTap.
            assertFalse(handleLinkTap(state, scope, { false }, centreOf(link)))
        }
    }

    /** One chapter: a note reference at the top, and the note it points at after enough text to fill a page. */
    private fun epubWithNote(): EpubDocument {
        val container = """<?xml version="1.0"?><container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container"><rootfiles><rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/></rootfiles></container>"""
        val opf = """<?xml version="1.0"?>
            <package xmlns="http://www.idpf.org/2007/opf" version="3.0" unique-identifier="id">
              <metadata xmlns:dc="http://purl.org/dc/elements/1.1/"><dc:identifier id="id">x</dc:identifier></metadata>
              <manifest><item id="c1" href="ch1.xhtml" media-type="application/xhtml+xml"/></manifest>
              <spine><itemref idref="c1"/></spine>
            </package>"""
        val filler = (1..30).joinToString("") { "<p>filler paragraph $it keeps the note on a later page</p>" }
        val ch1 = """<?xml version="1.0"?><html xmlns="http://www.w3.org/1999/xhtml" xmlns:epub="http://www.idpf.org/2007/ops"><body>""" +
            """<p>A claim<a epub:type="noteref" href="#fn1">1</a></p>$filler""" +
            """<aside epub:type="footnote" id="fn1"><p>The note.</p></aside></body></html>"""
        val zip = storedZip(
            listOf(
                "mimetype" to "application/epub+zip".encodeToByteArray(),
                "META-INF/container.xml" to container.encodeToByteArray(),
                "OEBPS/content.opf" to opf.encodeToByteArray(),
                "OEBPS/ch1.xhtml" to ch1.encodeToByteArray(),
            ),
        )
        return EpubDocument.open(zip, EpubSettings(pageWidth = 200.0, pageHeight = 200.0))
            ?: error("EPUB fixture failed to open")
    }

    private fun centreOf(link: io.github.yuroyami.kitepdf.epub.EpubLink) = Offset(
        ((link.rect.left + link.rect.right) / 2).toFloat(),
        ((link.rect.bottom + link.rect.top) / 2).toFloat(),
    )

    /** A host can show a note in place: the viewer offers the reference first and does not scroll (#277). */
    @Test
    fun epub_note_reference_goes_to_the_host_before_the_viewer_scrolls() {
        val doc = epubWithNote()
        val link = (doc.pages[0] as io.github.yuroyami.kitepdf.epub.EpubPage).links.single()
        assertEquals(io.github.yuroyami.kitepdf.epub.EpubLinkKind.NOTE_REFERENCE, link.kind)
        withViewer(doc) { state, scope, driver ->
            val shown = mutableListOf<String>()
            val consumed = handleLinkTap(state, scope, { tapped ->
                tapped.kind == KiteLinkKind.NOTE_REFERENCE &&
                    doc.linkTarget((tapped as KiteLinkAction.Epub).link.href)?.let { shown += it.text } != null
            }, centreOf(link))
            assertTrue(consumed, "the host consumed the tap")
            assertEquals(listOf("The note."), shown)
            driver.pumpFrames(10)
            assertEquals(0, state.currentPage, "the viewer stays on the page the reader tapped")

            // Declined by the host: the viewer follows the link as before.
            val offered = mutableListOf<KiteLinkAction>()
            assertTrue(handleLinkTap(state, scope, { offered += it; false }, centreOf(link)))
            assertEquals(KiteLinkKind.NOTE_REFERENCE, offered.single().kind, "the reference went to the host more than once")
            assertNotNull(offered.single().target)
            driver.pumpUntil { state.currentPage > 0 }
            assertTrue(state.currentPage > 0, "the viewer scrolled to the note")
        }
    }

    /** Lays out [doc] in a viewer with [layout] and runs [block] once the pages have a place. */
    private fun withViewer(
        doc: EpubDocument,
        layout: KiteDocLayout = KiteDocLayout.Default,
        block: (KiteDocViewState, CoroutineScope, SceneTestDriver) -> Unit,
    ) {
        lateinit var state: KiteDocViewState
        lateinit var scope: CoroutineScope
        ImageComposeScene(width = 200, height = 320, density = Density(1f)) {
            state = rememberKiteDocViewState(doc)
            scope = rememberCoroutineScope()
            KiteDocView(state = state, modifier = Modifier.fillMaxSize(), layout = layout)
        }.use { scene ->
            val driver = SceneTestDriver(scene)
            driver.pumpUntilState { state.pageGeometry.containsKey(0) }
            block(state, scope, driver)
        }
    }

    /** A host can show a note that the book does not mark as one: every internal link goes to it first (#444). */
    @Test
    fun an_ordinary_internal_link_goes_to_the_host_before_the_viewer_scrolls() {
        val doc = epubWithLink()
        val link = (doc.pages[0] as io.github.yuroyami.kitepdf.epub.EpubPage).links.single()
        withViewer(doc) { state, scope, driver ->
            val offered = mutableListOf<KiteLinkAction>()
            assertTrue(handleLinkTap(state, scope, { offered += it; true }, centreOf(link)))
            val tapped = offered.single() as KiteLinkAction.Epub
            assertEquals("OEBPS/ch2.xhtml", tapped.link.href)
            assertEquals(KiteLinkKind.LINK, tapped.kind)
            assertEquals(null, tapped.uri)
            assertEquals(doc.bookmarkOf("OEBPS/ch2.xhtml"), tapped.target)
            assertEquals(0, tapped.pageIndex)
            assertEquals(link.rect, tapped.rect)
            driver.pumpFrames(10)
            assertEquals(0, state.currentPage, "the viewer stays on the page the reader tapped")

            // Declined by the host: the viewer follows the link as before.
            assertTrue(handleLinkTap(state, scope, { false }, centreOf(link)))
            driver.pumpUntil { state.currentPage > 0 }
            assertTrue(state.currentPage > 0, "the viewer scrolled to chapter two")
        }
    }

    @Test
    fun a_real_tap_on_an_internal_link_reaches_the_link_callback_of_the_view() {
        val doc = epubWithLink()
        val link = (doc.pages[0] as io.github.yuroyami.kitepdf.epub.EpubPage).links.single()
        lateinit var state: KiteDocViewState
        val offered = mutableListOf<String>()
        ImageComposeScene(width = 200, height = 320, density = Density(1f)) {
            state = rememberKiteDocViewState(doc)
            KiteDocView(
                state = state, modifier = Modifier.fillMaxSize(), zoomSpec = KiteZoomSpec(doubleTapEnabled = false),
                onLinkTap = { offered += (it as KiteLinkAction.Epub).link.href; true },
            )
        }.use { scene ->
            val driver = SceneTestDriver(scene)
            driver.pumpUntilState { state.pageGeometry.isNotEmpty() }
            scene.sendPointerEvent(PointerEventType.Press, centreOf(link), type = PointerType.Touch)
            scene.sendPointerEvent(PointerEventType.Release, centreOf(link), type = PointerType.Touch)
            driver.pumpUntilState { offered.isNotEmpty() }
            assertEquals(listOf("OEBPS/ch2.xhtml"), offered)
            driver.pumpFrames(10)
            assertEquals(0, state.currentPage, "the viewer scrolled although the host consumed the tap")
        }
    }

    /** One fixed page cannot move: a declined link inside the book falls through, as a PDF link does. */
    @Test
    fun an_internal_book_link_on_one_fixed_page_still_reaches_the_host_and_then_falls_through() {
        val doc = epubWithLink()
        val link = (doc.pages[0] as io.github.yuroyami.kitepdf.epub.EpubPage).links.single()
        withViewer(doc, layout = KiteDocLayout.SinglePage(0)) { state, scope, _ ->
            // One page is centred in the view, so its link is found through the view's own mapping.
            val tap = assertNotNull(state.displayRectToViewport(0, link.rect)).center
            val offered = mutableListOf<KiteLinkAction>()
            assertFalse(handleLinkTap(state, scope, { offered += it; false }, tap), "a fixed page followed a link")
            assertEquals(doc.bookmarkOf("OEBPS/ch2.xhtml"), offered.single().target)
            assertTrue(handleLinkTap(state, scope, { true }, tap), "the host could not take the link")
        }
    }

    /** The deprecated composables' callback only sees what the viewer cannot follow, as before. */
    @Test
    fun the_old_link_callback_only_gets_the_links_the_viewer_cannot_follow() {
        withPdfViewer(KitePDF.open(pdfWithLinks())) { state, scope, driver ->
            val seen = mutableListOf<PdfAction>()
            val legacy = legacyLinkTap { seen += it; true }
            assertTrue(handleLinkTap(state, scope, legacy, assertNotNull(state.displayToViewport(0, 55.0, 145.0))))
            driver.pumpUntilState { state.currentPage == 1 }
            assertTrue(seen.isEmpty(), "the old callback got a go-to link that the viewer follows: $seen")
            scope.launch { state.scrollToPage(0) }
            driver.pumpUntilState { state.currentPage == 0 }
            assertTrue(handleLinkTap(state, scope, legacy, assertNotNull(state.displayToViewport(0, 145.0, 55.0))))
            assertEquals("https://example.com/kite", (seen.single() as PdfAction.Uri).uri)
        }
        val book = epubWithExternalLink()
        val out = (book.pages[0] as io.github.yuroyami.kitepdf.epub.EpubPage).links.single()
        withViewer(book) { state, scope, _ ->
            val seen = mutableListOf<PdfAction>()
            assertTrue(handleLinkTap(state, scope, legacyLinkTap { seen += it; true }, centreOf(out)))
            assertEquals("https://example.org/out", (seen.single() as PdfAction.Uri).uri, "a link out of a book is a URI action")
        }
    }

    /* ─── PDF actions: a page turn and a script, both on page 0 of two ───── */

    private fun pdfWithActions(): ByteArray {
        val sb = StringBuilder("%PDF-1.4\n")
        val offsets = ArrayList<Int>()
        fun add(s: String) {
            offsets.add(sb.length)
            sb.append(s)
        }
        add("1 0 obj\n<< /Type /Catalog /Pages 2 0 R >>\nendobj\n")
        add("2 0 obj\n<< /Type /Pages /Kids [3 0 R 4 0 R] /Count 2 /MediaBox [0 0 200 200] >>\nendobj\n")
        add("3 0 obj\n<< /Type /Page /Parent 2 0 R /Resources << >> /Annots [5 0 R 6 0 R] >>\nendobj\n")
        add("4 0 obj\n<< /Type /Page /Parent 2 0 R /Resources << >> >>\nendobj\n")
        add("5 0 obj\n<< /Type /Annot /Subtype /Link /Rect [20 20 90 90] /A << /S /Named /N /NextPage >> >>\nendobj\n")
        add("6 0 obj\n<< /Type /Annot /Subtype /Link /Rect [110 110 180 180] /A << /S /JavaScript /JS (app.alert\\(1\\)) >> >>\nendobj\n")
        val xref = sb.length
        sb.append("xref\n0 7\n0000000000 65535 f \n")
        for (o in offsets) sb.append("${o.toString().padStart(10, '0')} 00000 n \n")
        sb.append("trailer\n<< /Size 7 /Root 1 0 R >>\nstartxref\n$xref\n%%EOF\n")
        return sb.toString().encodeToByteArray()
    }

    /** Records the script actions that the viewer runs. */
    private class ScriptRecorder(document: io.github.yuroyami.kitepdf.PdfDocument) : io.github.yuroyami.kitepdf.PdfScriptHandler {
        override val formState = io.github.yuroyami.kitepdf.PdfFormState(document)
        val ran: MutableList<String> = java.util.Collections.synchronizedList(ArrayList())
        override fun runAction(action: PdfAction.JavaScript) { ran += action.script }
    }

    /** Lays out [doc] in [layout] with [scripts], and runs [block] once page 0 has a place. */
    private fun withPdfViewer(
        doc: io.github.yuroyami.kitepdf.PdfDocument,
        layout: KiteDocLayout = KiteDocLayout.Default,
        scripts: io.github.yuroyami.kitepdf.PdfScriptHandler? = null,
        block: (KiteDocViewState, CoroutineScope, SceneTestDriver) -> Unit,
    ) {
        lateinit var state: KiteDocViewState
        lateinit var scope: CoroutineScope
        ImageComposeScene(width = 200, height = 320, density = Density(1f)) {
            state = rememberKiteDocViewState(doc)
            scope = rememberCoroutineScope()
            KiteDocView(state = state, modifier = Modifier.fillMaxSize(), layout = layout, scripts = scripts)
        }.use { scene ->
            val driver = SceneTestDriver(scene)
            driver.pumpUntilState { state.pageGeometry.containsKey(0) }
            block(state, scope, driver)
        }
    }

    /** A link that names a page turn turns the page (#433), once the host has seen it and let it go. */
    @Test
    fun a_next_page_link_turns_the_page() {
        withPdfViewer(KitePDF.open(pdfWithActions())) { state, scope, driver ->
            val tap = assertNotNull(state.displayToViewport(0, 55.0, 145.0))
            assertTrue(handleLinkTap(state, scope, { true }, tap), "the host could not take the page turn")
            driver.pumpFrames(10)
            assertEquals(0, state.currentPage, "the viewer turned the page although the host took the link")

            val offered = mutableListOf<KiteLinkAction>()
            assertTrue(handleLinkTap(state, scope, { offered += it; false }, tap))
            driver.pumpUntilState { state.currentPage == 1 }
            assertEquals(1, state.currentPage)
            val named = (offered.single() as KiteLinkAction.Pdf).action as PdfAction.Named
            assertEquals(PdfAction.NamedActionType.NextPage, named.name)
        }
    }

    @Test
    fun a_script_link_runs_in_the_scripts_of_the_view() {
        val doc = KitePDF.open(pdfWithActions())
        val scripts = ScriptRecorder(doc)
        withPdfViewer(doc, scripts = scripts) { state, scope, driver ->
            val tap = assertNotNull(state.displayToViewport(0, 145.0, 55.0))
            // Taken by the host, the script does not run. Both runs share one script thread, so
            // a run of the first call would come before the second and show as two entries.
            assertTrue(handleLinkTap(state, scope, { true }, tap))
            val offered = mutableListOf<KiteLinkAction>()
            assertTrue(handleLinkTap(state, scope, { offered += it; false }, tap))
            driver.pumpUntilState { scripts.ran.isNotEmpty() }
            driver.pumpFrames(5)
            assertEquals(listOf("app.alert(1)"), scripts.ran.toList(), "the view ran a script link that the host took")
            assertTrue((offered.single() as KiteLinkAction.Pdf).action is PdfAction.JavaScript)
        }
    }

    @Test
    fun a_host_that_takes_a_go_to_link_keeps_the_view_on_its_page() {
        withPdfViewer(KitePDF.open(pdfWithLinks())) { state, scope, driver ->
            assertTrue(handleLinkTap(state, scope, { true }, assertNotNull(state.displayToViewport(0, 55.0, 145.0))))
            driver.pumpFrames(10)
            assertEquals(0, state.currentPage, "the viewer followed a link that the host took")
        }
    }

    @Test
    fun a_go_to_link_in_a_single_page_view_goes_to_the_host() {
        withPdfViewer(KitePDF.open(pdfWithLinks()), layout = KiteDocLayout.SinglePage(0)) { state, scope, _ ->
            val tap = assertNotNull(state.displayToViewport(0, 55.0, 145.0))
            val offered = mutableListOf<KiteLinkAction>()
            assertTrue(handleLinkTap(state, scope, { offered += it; true }, tap), "the host took the link")
            assertTrue((offered.single() as KiteLinkAction.Pdf).action is PdfAction.GoTo)
            assertFalse(handleLinkTap(state, scope, null, tap), "a link nobody takes must fall through to onTap")
        }
    }

    /** Page 0 links to the height 150 of page 1, a tall page; page 2 follows, so the strip can scroll that far. */
    private fun pdfWithPlaceLink(): ByteArray {
        val sb = StringBuilder("%PDF-1.4\n")
        val offsets = ArrayList<Int>()
        fun add(s: String) {
            offsets.add(sb.length)
            sb.append(s)
        }
        add("1 0 obj\n<< /Type /Catalog /Pages 2 0 R >>\nendobj\n")
        add("2 0 obj\n<< /Type /Pages /Kids [3 0 R 4 0 R 5 0 R] /Count 3 >>\nendobj\n")
        add("3 0 obj\n<< /Type /Page /Parent 2 0 R /MediaBox [0 0 200 200] /Resources << >> /Annots [6 0 R] >>\nendobj\n")
        add("4 0 obj\n<< /Type /Page /Parent 2 0 R /MediaBox [0 0 200 600] /Resources << >> >>\nendobj\n")
        add("5 0 obj\n<< /Type /Page /Parent 2 0 R /MediaBox [0 0 200 600] /Resources << >> >>\nendobj\n")
        add("6 0 obj\n<< /Type /Annot /Subtype /Link /Rect [20 20 90 90] /Dest [4 0 R /XYZ 0 150 null] >>\nendobj\n")
        val xref = sb.length
        sb.append("xref\n0 7\n0000000000 65535 f \n")
        for (o in offsets) sb.append("${o.toString().padStart(10, '0')} 00000 n \n")
        sb.append("trailer\n<< /Size 7 /Root 1 0 R >>\nstartxref\n$xref\n%%EOF\n")
        return sb.toString().encodeToByteArray()
    }

    /** A link to a place on a page scrolls to that place, where it landed at the top of the page (#433). */
    @Test
    fun a_link_to_a_place_on_a_page_scrolls_to_that_place() {
        withPdfViewer(KitePDF.open(pdfWithPlaceLink())) { state, scope, driver ->
            val tap = assertNotNull(state.displayToViewport(0, 55.0, 145.0))
            assertTrue(handleLinkTap(state, scope, null, tap))
            driver.pumpUntilState { state.currentScrollPosition.location.page == 1 && state.currentScrollPosition.offsetPx > 0 }
            driver.pumpFrames(30)
            val position = state.currentScrollPosition
            assertEquals(1, position.location.page)
            // The page is 600 tall in a 200 wide strip, so its slot is 600 px, and height 150 from the bottom is 450 px down.
            assertEquals(450f, position.offsetPx.toFloat(), 2f, "the strip did not scroll to the place: $position")
        }
    }
}
