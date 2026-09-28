package io.github.yuroyami.kitepdf.compose

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.use
import io.github.yuroyami.kitepdf.core.KiteCancellation
import io.github.yuroyami.kitepdf.core.KiteDocument
import io.github.yuroyami.kitepdf.core.KiteLink
import io.github.yuroyami.kitepdf.core.KiteLocation
import io.github.yuroyami.kitepdf.core.KitePage
import io.github.yuroyami.kitepdf.core.KiteReadingItem
import io.github.yuroyami.kitepdf.core.KiteStructuredText
import io.github.yuroyami.kitepdf.core.render.KiteCanvas
import io.github.yuroyami.kitepdf.core.render.KiteMatrix
import io.github.yuroyami.kitepdf.epub.EpubDocument
import io.github.yuroyami.kitepdf.epub.EpubSettings
import java.util.Collections
import java.util.IdentityHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A page whose chapter the layout budget dropped lays nothing out on the UI thread. A Vectorized
 * draw shows the paper until the content is back, and a long press or a link tap finds no text
 * and no links until then (#377).
 */
class DroppedChapterSceneTest {

    /** Four chapters under a budget that keeps one of them in memory. */
    private fun book(): EpubDocument = EpubDocument.open(
        multiSpineEpub(
            List(4) { c -> "<h1>Chapter ${c + 1}</h1>" + (0 until 8).joinToString("") { "<p>Chapter ${c + 1} paragraph $it with words enough to wrap.</p>" } },
        ),
        EpubSettings(pageWidth = 200.0, pageHeight = 200.0, layoutCacheBytes = 0),
    )

    /** [book] with chapter 0 laid out once and then dropped for chapter 3. */
    private fun bookWithChapter0Dropped(): EpubDocument = book().also { epub ->
        for (c in 0 until epub.chapterCount) epub.prepareChapter(c)
        epub.page(KiteLocation(3, 0)).loadContent()
        assertFalse(epub.page(KiteLocation(0, 0)).isContentLoaded, "chapter 0 should be out of memory")
    }

    /**
     * Wraps every page. A content read on the [ui] thread while the content is out of memory is
     * a violation. A content read on another thread waits until [release], so the content stays
     * out of memory for as long as the test needs.
     */
    private class WatchedDocument(private val inner: EpubDocument, private val ui: Thread) : KiteDocument by inner {
        val violations: MutableList<String> = Collections.synchronizedList(ArrayList())
        private val hold = CountDownLatch(1)
        private val wrapped = IdentityHashMap<KitePage, KitePage>()

        init {
            releaseAtEnd(::release)
        }

        fun release() = hold.countDown()

        fun wrap(page: KitePage): KitePage = synchronized(wrapped) { wrapped.getOrPut(page) { WatchedPage(page) } }

        override fun page(location: KiteLocation): KitePage = wrap(inner.page(location))

        override val pages: List<KitePage>
            get() = inner.pages.let { all ->
                object : AbstractList<KitePage>() {
                    override val size: Int get() = all.size
                    override fun get(index: Int): KitePage = wrap(all[index])
                }
            }

        private fun watch(what: String, page: KitePage) {
            if (Thread.currentThread() === ui) {
                if (!page.isContentLoaded) violations += what
            } else {
                hold.await(30, TimeUnit.SECONDS)
            }
        }

        inner class WatchedPage(private val page: KitePage) : KitePage by page {
            override fun renderTo(canvas: KiteCanvas, deviceCtm: KiteMatrix) {
                watch("draw", page)
                page.renderTo(canvas, deviceCtm)
            }

            override fun renderTo(canvas: KiteCanvas, deviceCtm: KiteMatrix, cancellation: KiteCancellation) {
                watch("draw", page)
                page.renderTo(canvas, deviceCtm, cancellation)
            }

            override val drawsHostFontText: Boolean?
                get() {
                    watch("host-font check", page)
                    return page.drawsHostFontText
                }

            override fun loadContent() {
                watch("load", page)
                page.loadContent()
            }

            override fun textContent(): KiteStructuredText? {
                watch("text", page)
                return page.textContent()
            }

            override fun readingOrder(): List<KiteReadingItem> {
                watch("reading order", page)
                return page.readingOrder()
            }

            override val hyperlinks: List<KiteLink>
                get() {
                    watch("links", page)
                    return page.hyperlinks
                }
        }
    }

    @Test
    fun a_vectorized_page_of_a_dropped_chapter_shows_its_paper_until_the_content_is_back() = forBothEffectOrders { queued ->
        val epub = bookWithChapter0Dropped()
        val doc = WatchedDocument(epub, Thread.currentThread())
        val state = KiteDocViewState(doc)
        val (scene, driver) = drivenScene(200, 260, queued) {
            KiteDocView(state = state, modifier = Modifier.fillMaxSize(), renderSpec = KiteRenderSpec.Vectorized())
        }
        scene.use {
            driver.pumpUntilState { state.adapter != null && state.pageGeometry.containsKey(0) }
            driver.pumpFrames(3)
            assertEquals(emptyList(), doc.violations.toList(), "the UI thread read a dropped chapter")
            assertFalse(epub.page(KiteLocation(0, 0)).isContentLoaded, "only the test lets the content come back")
            doc.release()
            // Once the content is back, the page draws its text.
            driver.pumpUntil { pixels -> (0 until 200 step 2).sumOf { y -> (0 until 200 step 2).count { x -> pixels[x, y].red < 0.5f } } > 20 }
            assertEquals(emptyList(), doc.violations.toList())
        }
    }

    @Test
    fun a_long_press_and_a_tap_on_a_dropped_chapter_read_nothing_on_the_ui_thread() = forBothEffectOrders { queued ->
        val epub = bookWithChapter0Dropped()
        val doc = WatchedDocument(epub, Thread.currentThread())
        val state = KiteDocViewState(doc)
        val (scene, driver) = drivenScene(200, 260, queued) {
            KiteDocView(state = state, modifier = Modifier.fillMaxSize())
        }
        scene.use {
            driver.pumpUntilState { state.adapter != null && state.pageGeometry.containsKey(0) }
            state.beginSelection(Offset(100f, 60f))
            assertNull(state.selection, "a page out of memory has no text to select yet")
            state.endSelectionGesture()
            scene.sendPointerEvent(PointerEventType.Press, Offset(100f, 60f), type = PointerType.Touch)
            scene.sendPointerEvent(PointerEventType.Release, Offset(100f, 60f), type = PointerType.Touch)
            // A single tap lands once the double-tap time is over.
            repeat(40) {
                driver.pumpFrames(1)
                Thread.sleep(20)
            }
            assertEquals(emptyList(), doc.violations.toList(), "the UI thread read a dropped chapter")
            doc.release()
            driver.pumpUntilState { epub.page(KiteLocation(0, 0)).isContentLoaded }
            assertTrue(state.pageAt(0)?.isContentLoaded == true)
        }
    }
}
