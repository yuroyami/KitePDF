package io.github.yuroyami.kitepdf.compose

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.Density
import androidx.compose.ui.use
import io.github.yuroyami.kitepdf.KitePDF
import io.github.yuroyami.kitepdf.PdfAction
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
 * T-32/T-82: taps on links navigate. PDF pages hit-test their Link
 * annotations and follow resolved destinations; EPUB pages hit-test
 * [io.github.yuroyami.kitepdf.epub.EpubPage.links] and follow internal
 * hrefs through [EpubDocument.pageOf]; URI links go to `onLinkTap`.
 * The tap path is exercised through the real composed layout (hitTest
 * geometry) by invoking the internal handler with computed offsets.
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
        lateinit var state: PdfViewState
        lateinit var scope: CoroutineScope
        val openedUris = mutableListOf<String>()
        // 200x320 viewport: the 200pt page maps 1:1 to px, page 0 at y 0..200.
        ImageComposeScene(width = 200, height = 320, density = Density(1f)) {
            state = rememberPdfViewState(doc)
            scope = rememberCoroutineScope()
            PdfView(state = state, modifier = Modifier.fillMaxSize())
        }.use { scene ->
            val driver = SceneTestDriver(scene)
            driver.pumpUntil { state.pageGeometry.isNotEmpty() }

            val onLinkTap: (PdfAction) -> Boolean = { action ->
                (action as? PdfAction.Uri)?.let { openedUris.add(it.uri) } != null
            }

            // Link rect [20..90]x[20..90] user space (y-up) = display y 110..180:
            // its centre is viewport (55, 145).
            assertTrue(handleLinkTap(state, scope, onLinkTap, Offset(55f, 145f)), "GoTo link consumes the tap")
            driver.pumpUntil { state.currentPage == 1 }
            assertEquals(1, state.currentPage, "the destination link navigated to page 2")
            assertTrue(openedUris.isEmpty(), "the GoTo link never reaches onLinkTap")

            // Back to page 0 for the URI link (rect [110..180] user = display y 20..90).
            scope.launch { state.scrollToPage(0) }
            driver.pumpUntil { state.currentPage == 0 }
            assertTrue(handleLinkTap(state, scope, onLinkTap, Offset(145f, 55f)), "URI link consumed via callback")
            assertEquals(listOf("https://example.com/kite"), openedUris)

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

        lateinit var state: PdfViewState
        lateinit var scope: CoroutineScope
        ImageComposeScene(width = 200, height = 320, density = Density(1f)) {
            state = rememberEpubViewState(doc)
            scope = rememberCoroutineScope()
            PdfView(state = state, modifier = Modifier.fillMaxSize())
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
}
