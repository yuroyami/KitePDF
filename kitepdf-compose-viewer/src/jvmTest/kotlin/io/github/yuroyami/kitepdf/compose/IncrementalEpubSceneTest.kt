package io.github.yuroyami.kitepdf.compose

import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Density
import androidx.compose.ui.use
import io.github.yuroyami.kitepdf.core.KiteBookmark
import io.github.yuroyami.kitepdf.core.KiteDocument
import io.github.yuroyami.kitepdf.core.KiteLocation
import io.github.yuroyami.kitepdf.core.KitePage
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

/**
 * The viewer half of incremental layout: open at a saved position without
 * paginating the book first, and do not move the page under the reader when a
 * chapter lands above them.
 */
class IncrementalEpubSceneTest {

    private val settings = EpubSettings(pageWidth = 200.0, pageHeight = 200.0)

    private fun book(chapters: Int = 12): EpubDocument = EpubDocument.open(
        multiSpine(
            List(chapters) { c ->
                "<h1 id=\"head$c\">Chapter ${c + 1}</h1>" +
                    (0 until 18).joinToString("") {
                        "<p>Chapter ${c + 1} paragraph $it with words enough to wrap.</p>"
                    }
            },
        ),
        settings,
    )

    /** The headline: a late chapter is on screen while the earlier ones are not laid out. */
    @Test
    fun opening_at_a_bookmark_shows_that_page_before_the_book_is_paginated() {
        val doc = book()
        val mark = KiteBookmark.Flow(chapter = 9, charOffset = 0)
        lateinit var state: KiteDocViewState
        ImageComposeScene(width = 200, height = 260, density = Density(1f)) {
            state = rememberKiteDocViewState(doc, mark)
            KiteDocView(state = state, modifier = Modifier.fillMaxSize())
        }.use { scene ->
            val driver = SceneTestDriver(scene)
            // One frame: the target chapter is laid out, the reader is on it.
            driver.pumpUntilState { state.currentLocation.chapter == 9 }
            assertEquals(9, state.currentLocation.chapter)
            assertTrue(doc.isChapterReady(9))
            assertFalse(doc.isComplete, "the whole book should not be laid out yet")
        }
    }

    /** The loader fills the book in behind the reader, and finishes. */
    @Test
    fun the_rest_of_the_book_lays_out_in_the_background() {
        val doc = book(8)
        lateinit var state: KiteDocViewState
        ImageComposeScene(width = 200, height = 260, density = Density(1f)) {
            state = rememberKiteDocViewState(doc, KiteBookmark.Flow(chapter = 5))
            KiteDocView(state = state, modifier = Modifier.fillMaxSize())
        }.use { scene ->
            val driver = SceneTestDriver(scene)
            driver.pumpUntilState { doc.isComplete }
            assertTrue(doc.isComplete)
            assertTrue(state.isComplete)
            assertEquals(doc.pageCount, state.knownPageCount)
        }
    }

    /** A chapter landing ABOVE the reader must not shove their page away. */
    @Test
    fun a_chapter_landing_above_does_not_move_the_visible_page() {
        val doc = book(10)
        lateinit var state: KiteDocViewState
        ImageComposeScene(width = 200, height = 260, density = Density(1f)) {
            state = rememberKiteDocViewState(doc, KiteBookmark.Flow(chapter = 7))
            KiteDocView(
                state = state,
                modifier = Modifier.fillMaxSize(),
                layout = KiteDocLayout.Continuous(Orientation.Vertical),
            )
        }.use { scene ->
            val driver = SceneTestDriver(scene)
            driver.pumpUntilState { state.currentLocation.chapter == 7 }
            val before = state.currentLocation
            val slotBefore = state.currentPage

            driver.pumpUntilState { doc.isComplete }
            assertEquals(before, state.currentLocation, "the reader moved while chapters landed")
            assertTrue(
                state.currentPage > slotBefore,
                "the slot index must grow as earlier chapters expand: was $slotBefore, now ${state.currentPage}",
            )
        }
    }

    /** Same, in a pager, where the index shift has to be corrected by hand. */
    @Test
    fun a_pager_stays_on_its_page_while_chapters_land() {
        val doc = book(10)
        lateinit var state: KiteDocViewState
        ImageComposeScene(width = 200, height = 260, density = Density(1f)) {
            state = rememberKiteDocViewState(doc, KiteBookmark.Flow(chapter = 6))
            KiteDocView(
                state = state,
                modifier = Modifier.fillMaxSize(),
                layout = KiteDocLayout.Paged(Orientation.Horizontal),
            )
        }.use { scene ->
            val driver = SceneTestDriver(scene)
            driver.pumpUntilState { state.currentLocation.chapter == 6 }
            val before = state.currentLocation
            driver.pumpUntilState { doc.isComplete }
            assertEquals(before, state.currentLocation, "the pager slid off its page")
        }
    }

    /**
     * A reader can scroll ahead onto a chapter that is not laid out yet and sit
     * on its placeholder. When that chapter lands, the placeholder becomes its
     * pages and the reader must end up at the start of the chapter they were
     * waiting for, not back where they came from.
     */
    @Test
    fun landing_on_a_placeholder_puts_the_reader_at_that_chapter() {
        val doc = book(8)
        val gate = GatedDocument(doc, held = 5)
        lateinit var state: KiteDocViewState
        var goTo by mutableStateOf(-1)
        ImageComposeScene(width = 200, height = 260, density = Density(1f)) {
            state = rememberKiteDocViewState(gate, KiteBookmark.Flow(chapter = 4))
            KiteDocView(
                state = state,
                modifier = Modifier.fillMaxSize(),
                layout = KiteDocLayout.Paged(Orientation.Horizontal),
            )
            if (goTo >= 0) LaunchedEffect(goTo) { state.scrollToPage(goTo) }
        }.use { scene ->
            val driver = SceneTestDriver(scene)
            // Every chapter but 5 lays out AND publishes; 5 stays a placeholder.
            // Waiting on the PUBLISHED strip, not raw document readiness: the
            // strip is memoized per publication, so slot math on state.items
            // is only stable once the strip itself shows the layout.
            driver.pumpUntilState {
                state.items.filterIsInstance<DocItem.ChapterGap>().map { it.chapter } == listOf(5)
            }
            assertFalse(gate.isChapterReady(5), "chapter 6 should still be held back")

            val gap = state.items.indexOfFirst { it is DocItem.ChapterGap && it.chapter == 5 }
            assertTrue(gap >= 0, "chapter 6 should be holding a placeholder slot")
            goTo = gap
            driver.pumpUntilState { state.currentPage == gap }
            assertEquals(KiteLocation(5, 0), state.currentLocation, "the reader is on chapter 6's placeholder")

            gate.release()
            state.onChapterReady()
            driver.pumpUntilState { gate.isChapterReady(5) && state.currentLocation.chapter == 5 }
            assertEquals(
                KiteLocation(5, 0),
                state.currentLocation,
                "the chapter landed and the reader was pulled off it",
            )
        }
    }

    /** Delegates everything, but refuses to lay out one chapter until released. */
    private class GatedDocument(private val inner: KiteDocument, private val held: Int) : KiteDocument by inner {
        private var open = false
        fun release() {
            open = true
            inner.prepareChapter(held)
        }

        override fun prepareChapter(chapter: Int) {
            if (chapter == held && !open) return
            inner.prepareChapter(chapter)
        }
    }

    /** A bookmark taken now must reopen at the same place later. */
    @Test
    fun a_bookmark_round_trips_through_the_state() {
        val doc = book()
        lateinit var state: KiteDocViewState
        ImageComposeScene(width = 200, height = 260, density = Density(1f)) {
            state = rememberKiteDocViewState(doc, KiteBookmark.Flow(chapter = 4))
            KiteDocView(state = state, modifier = Modifier.fillMaxSize())
        }.use { scene ->
            val driver = SceneTestDriver(scene)
            driver.pumpUntilState { state.currentLocation.chapter == 4 }
            assertEquals(4, state.currentBookmark().chapter, "the bookmark names the chapter being read")
            // Settle first: while chapters are still landing the strip is moving,
            // and reading the location twice can straddle a change.
            driver.pumpUntilState { state.isComplete }
            val at = state.currentLocation
            assertEquals(at, doc.locate(state.currentBookmark()), "a bookmark must reopen where it was taken")
        }
    }

    /**
     * The contract that makes all of this work: composing and navigating must
     * never touch the whole-document views, which lay out every chapter.
     */
    @Test
    fun the_viewer_never_asks_for_the_whole_document() {
        val doc = book()
        val spy = WholeDocumentSpy(doc)
        lateinit var state: KiteDocViewState
        ImageComposeScene(width = 200, height = 260, density = Density(1f)) {
            state = rememberKiteDocViewState(spy, KiteBookmark.Flow(chapter = 8))
            KiteDocView(state = state, modifier = Modifier.fillMaxSize())
        }.use { scene ->
            val driver = SceneTestDriver(scene)
            driver.pumpUntilState { state.currentLocation.chapter == 8 }
            assertEquals(null, spy.touched, "composition read ${spy.touched}")
            driver.pumpUntilState { spy.isComplete }
            assertEquals(null, spy.touched, "the background loader read ${spy.touched}")
        }
    }

    /** Delegates everything, and records any read of the two eager members. */
    private class WholeDocumentSpy(private val inner: KiteDocument) : KiteDocument by inner {
        var touched: String? = null
        override val pages: List<KitePage>
            get() {
                if (touched == null) touched = "pages"
                return inner.pages
            }
        override val pageCount: Int
            get() {
                if (touched == null) touched = "pageCount"
                return inner.pageCount
            }
    }

    /**
     * The page total is approximate until the book is laid out, which is what
     * the indicator's "~" reflects. Both ends of that are asserted here.
     */
    @Test
    fun the_total_is_approximate_until_the_book_is_laid_out() {
        val doc = book(6)
        // Before anything is composed: nothing laid out, nothing to count.
        val fresh = KiteDocViewState(doc, KiteBookmark.Flow(chapter = 3))
        assertFalse(fresh.isComplete, "a fresh book has laid nothing out")
        assertEquals(0, fresh.knownPageCount)

        lateinit var state: KiteDocViewState
        ImageComposeScene(width = 200, height = 260, density = Density(1f)) {
            state = rememberKiteDocViewState(doc, KiteBookmark.Flow(chapter = 3))
            KiteDocView(state = state, modifier = Modifier.fillMaxSize())
        }.use { scene ->
            SceneTestDriver(scene).pumpUntilState { state.isComplete }
            assertTrue(state.isComplete, "the loader never finished the book")
            assertEquals(doc.pageCount, state.knownPageCount, "the total is exact once complete")
        }
    }

    /** Navigation still works across a chapter boundary, laying the next one out. */
    @Test
    fun next_page_crosses_into_the_following_chapter() {
        val doc = book(5)
        doc.prepareChapter(2)
        val last = doc.pageCountIn(2) - 1
        lateinit var state: KiteDocViewState
        var go by mutableStateOf(false)
        ImageComposeScene(width = 200, height = 260, density = Density(1f)) {
            state = rememberKiteDocViewState(doc, KiteBookmark.Flow(chapter = 2, charOffset = Int.MAX_VALUE - 1))
            KiteDocView(state = state, modifier = Modifier.fillMaxSize())
            if (go) LaunchedEffect(Unit) { state.nextPage() }
        }.use { scene ->
            val driver = SceneTestDriver(scene)
            driver.pumpUntilState { state.currentLocation == KiteLocation(2, last) }
            assertEquals(KiteLocation(2, last), state.currentLocation, "expected to open on the chapter's last page")
            go = true
            driver.pumpUntilState { state.currentLocation.chapter == 3 }
            assertEquals(KiteLocation(3, 0), state.currentLocation)
        }
    }

    /** A TOC entry opens its chapter and nothing else. */
    @Test
    fun an_outline_target_prepares_only_its_own_chapter() {
        val doc = book(9)
        val target = assertNotNull(doc.bookmarkOf("OEBPS/chapter7.xhtml#head6"))
        assertEquals(6, target.chapter)
        doc.locate(target)
        assertTrue(doc.isChapterReady(6))
        assertFalse(doc.isChapterReady(0))
        assertFalse(doc.isChapterReady(8))
    }

    /* ── fixture ──────────────────────────────────────────────────────────── */

    private fun multiSpine(bodies: List<String>): ByteArray {
        val container = """<?xml version="1.0"?><container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container"><rootfiles><rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/></rootfiles></container>"""
        val items = bodies.indices.joinToString("") {
            """<item id="c${it + 1}" href="chapter${it + 1}.xhtml" media-type="application/xhtml+xml"/>"""
        }
        val refs = bodies.indices.joinToString("") { """<itemref idref="c${it + 1}"/>""" }
        val opf = """<?xml version="1.0"?>
            <package xmlns="http://www.idpf.org/2007/opf" version="3.0" unique-identifier="id">
              <metadata xmlns:dc="http://purl.org/dc/elements/1.1/"><dc:identifier id="id">scene</dc:identifier></metadata>
              <manifest>$items</manifest>
              <spine>$refs</spine>
            </package>"""
        val files = bodies.mapIndexed { i, body ->
            "OEBPS/chapter${i + 1}.xhtml" to
                """<?xml version="1.0"?><html xmlns="http://www.w3.org/1999/xhtml"><body>$body</body></html>""".encodeToByteArray()
        }
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            zip.setMethod(ZipOutputStream.STORED)
            val entries = listOf(
                "mimetype" to "application/epub+zip".encodeToByteArray(),
                "META-INF/container.xml" to container.encodeToByteArray(),
                "OEBPS/content.opf" to opf.encodeToByteArray(),
            ) + files
            for ((name, data) in entries) {
                zip.putNextEntry(
                    ZipEntry(name).apply {
                        method = ZipEntry.STORED
                        size = data.size.toLong()
                        compressedSize = data.size.toLong()
                        crc = CRC32().apply { update(data) }.value
                    },
                )
                zip.write(data)
                zip.closeEntry()
            }
        }
        return out.toByteArray()
    }
}
