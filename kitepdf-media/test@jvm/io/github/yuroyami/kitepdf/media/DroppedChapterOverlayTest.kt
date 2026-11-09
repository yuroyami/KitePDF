package io.github.yuroyami.kitepdf.media

import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import io.github.yuroyami.kitepdf.compose.KitePageOverlayScope
import io.github.yuroyami.kitepdf.core.KiteLocation
import io.github.yuroyami.kitepdf.core.KitePage
import io.github.yuroyami.kitepdf.core.KiteRectangle
import io.github.yuroyami.kitepdf.epub.EpubDocument
import io.github.yuroyami.kitepdf.epub.EpubPage
import io.github.yuroyami.kitepdf.epub.EpubSettings
import io.github.yuroyami.kitepdf.media.MediaBooks.fixture
import io.github.yuroyami.kitepdf.media.MediaBooks.storedZip
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertFalse

/**
 * The overlay of a page whose chapter the layout budget dropped reads nothing of the page in
 * composition, which would lay the chapter out again on the UI thread, and shows the media once
 * the chapter is back (#681).
 */
@OptIn(ExperimentalTestApi::class)
class DroppedChapterOverlayTest {

    /** Two chapters: a video in the first, then text. */
    private fun book(): EpubDocument {
        val container = """<?xml version="1.0"?><container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">""" +
            """<rootfiles><rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/></rootfiles></container>"""
        val opf = """<?xml version="1.0"?><package xmlns="http://www.idpf.org/2007/opf" version="3.0" unique-identifier="uid">""" +
            """<metadata xmlns:dc="http://purl.org/dc/elements/1.1/"><dc:identifier id="uid">dropped-media</dc:identifier></metadata>""" +
            """<manifest><item id="c1" href="c1.xhtml" media-type="application/xhtml+xml"/><item id="c2" href="c2.xhtml" media-type="application/xhtml+xml"/>""" +
            """<item id="v" href="clip.mp4" media-type="video/mp4"/></manifest>""" +
            """<spine><itemref idref="c1"/><itemref idref="c2"/></spine></package>"""
        fun chapter(body: String) = """<?xml version="1.0"?><html xmlns="http://www.w3.org/1999/xhtml"><body>$body</body></html>""".encodeToByteArray()
        val bytes = storedZip(
            linkedMapOf(
                "mimetype" to "application/epub+zip".encodeToByteArray(),
                "META-INF/container.xml" to container.encodeToByteArray(),
                "OEBPS/content.opf" to opf.encodeToByteArray(),
                "OEBPS/c1.xhtml" to chapter("""<video src="clip.mp4" width="160" height="120" controls="controls"></video>"""),
                "OEBPS/c2.xhtml" to chapter("<p>The second chapter.</p>"),
                "OEBPS/clip.mp4" to fixture("clip.mp4"),
            ),
        )
        return EpubDocument.open(bytes, EpubSettings(pageWidth = 200.0, pageHeight = 200.0, layoutCacheBytes = 0))
    }

    private class Scope(override val page: KitePage) : KitePageOverlayScope {
        override val pageIndex: Int = 0
        override fun Modifier.displayRect(rect: KiteRectangle): Modifier = this
        override fun Modifier.pageRect(rect: KiteRectangle): Modifier = this
    }

    @Test
    fun a_dropped_chapter_is_not_laid_out_in_composition() = runComposeUiTest {
        val doc = book()
        for (c in 0 until doc.chapterCount) doc.prepareChapter(c)
        doc.page(KiteLocation(1, 0)).loadContent()
        val page = doc.page(KiteLocation(0, 0)) as EpubPage
        assertFalse(page.isContentLoaded, "chapter 0 should be out of memory")
        val scope = Scope(page)
        setContent {
            androidx.compose.foundation.layout.Box(Modifier.size(200.dp, 200.dp)) { scope.KiteMediaOverlay(newPlayer = { null }) }
        }
        waitForIdle()
        assertFalse(page.isContentLoaded, "the overlay laid the dropped chapter out in composition")
        assertFalse(onAllNodesWithContentDescription("Play").fetchSemanticsNodes().isNotEmpty(), "the overlay showed media it had not read")
        // The page's raster brings the chapter back off the UI thread, and the overlay shows the video then.
        thread { page.loadContent() }.join()
        waitUntil(timeoutMillis = 10_000) { onAllNodesWithContentDescription("Play").fetchSemanticsNodes().isNotEmpty() }
    }
}
