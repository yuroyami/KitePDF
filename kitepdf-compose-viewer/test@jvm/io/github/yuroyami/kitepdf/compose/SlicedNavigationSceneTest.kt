package io.github.yuroyami.kitepdf.compose

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import io.github.yuroyami.kitepdf.core.KiteBookmark
import io.github.yuroyami.kitepdf.core.KiteDocument
import io.github.yuroyami.kitepdf.core.KiteLocation
import io.github.yuroyami.kitepdf.epub.EpubDocument
import io.github.yuroyami.kitepdf.epub.EpubSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.util.Collections
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration

/**
 * Where layout runs on the UI thread, going to a bookmark lays its chapter out in slices, as the
 * chapter loader does, and locate finds it ready (#682).
 */
class SlicedNavigationSceneTest {

    /** Counts the locates that would lay a chapter out in one call, and the chapters laid out in slices. */
    private class Watched(private val inner: EpubDocument) : KiteDocument by inner {
        val unpreparedLocates: MutableList<Int> = Collections.synchronizedList(ArrayList())
        val sliced: MutableList<Int> = Collections.synchronizedList(ArrayList())

        override fun locate(bookmark: KiteBookmark): KiteLocation {
            if (bookmark is KiteBookmark.Flow && !inner.isChapterReady(bookmark.chapter)) unpreparedLocates += bookmark.chapter
            return inner.locate(bookmark)
        }

        override suspend fun prepareChapter(chapter: Int, checkpoint: suspend () -> Unit) {
            sliced += chapter
            inner.prepareChapter(chapter, checkpoint)
        }
    }

    @Test
    fun a_bookmark_lays_its_chapter_out_in_slices() = forBothEffectOrders { queued ->
        val bodies = List(30) { c ->
            "<h1>Chapter ${c + 1}</h1>" + (0 until 20).joinToString("") { "<p>Chapter ${c + 1} paragraph $it with words enough to wrap.</p>" }
        }
        val doc = Watched(EpubDocument.open(multiSpineEpub(bodies), EpubSettings(pageWidth = 200.0, pageHeight = 200.0)))
        val state = KiteDocViewState(doc).also {
            it.layoutPausesForFrames = true
            it.layoutSlice = Duration.ZERO
        }
        lateinit var scope: CoroutineScope
        val (scene, driver) = drivenScene(200, 260, queued) {
            scope = rememberCoroutineScope()
            KiteDocView(state = state, modifier = Modifier.fillMaxSize())
        }
        scene.use {
            driver.pumpUntilState { doc.isChapterReady(0) && state.adapter != null }
            var done = false
            onTestUiThread { scope.launch { state.scrollTo(KiteBookmark.Flow(chapter = 29)); done = true } }
            driver.pumpUntilState { done }
            assertEquals(emptyList(), doc.unpreparedLocates.toList(), "a locate laid its chapter out in one call")
            assertTrue(29 in doc.sliced, "the bookmark's chapter did not lay out in slices: ${doc.sliced}")
            assertEquals(29, state.currentLocation.chapter)
        }
    }
}
