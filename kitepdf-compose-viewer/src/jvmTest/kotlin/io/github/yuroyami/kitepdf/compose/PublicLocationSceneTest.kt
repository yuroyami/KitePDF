package io.github.yuroyami.kitepdf.compose

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.IntSize
import io.github.yuroyami.kitepdf.core.KiteBookmark
import io.github.yuroyami.kitepdf.core.KiteDocument
import io.github.yuroyami.kitepdf.core.KiteLocation
import io.github.yuroyami.kitepdf.core.KitePage
import io.github.yuroyami.kitepdf.core.KiteRectangle
import io.github.yuroyami.kitepdf.core.KiteSearchHit
import io.github.yuroyami.kitepdf.epub.EpubDocument
import io.github.yuroyami.kitepdf.epub.EpubSettings
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Public page coordinates survive publication of earlier chapters in the same layout (#340). */
class PublicLocationSceneTest {
    private val settings = EpubSettings(pageWidth = 200.0, pageHeight = 200.0)
    private val target = KiteLocation(8, 0)

    private fun bytes(): ByteArray = multiSpineEpub(
        List(12) { chapter ->
            "<h1>Chapter ${chapter + 1}</h1>" + (0 until 24).joinToString("") {
                "<p>Chapter ${chapter + 1} paragraph $it with words enough to wrap.</p>"
            }
        },
    )

    @Test
    fun exact_lookup_reads_only_published_pages_and_does_not_prepare_chapters() {
        val inner = EpubDocument.open(bytes(), settings)
        inner.prepareChapter(target.chapter)
        val doc = object : KiteDocument by inner {
            override val pages: List<KitePage> get() = error("lookup read every page")
            override val pageCount: Int get() = error("lookup read the whole-book page count")
            override fun prepareChapter(chapter: Int) = error("lookup prepared chapter $chapter")
        }
        val state = KiteDocViewState(doc)
        assertEquals(8, state.slotOf(target))
        assertEquals(target, state.locationOf(8))
        assertNull(state.locationOf(0), "a pending chapter is not its first real page")
        assertNull(state.slotOf(KiteLocation(0, 0)))
        assertNull(state.slotOf(KiteLocation(8, inner.pageCountIn(8))))
        assertNull(state.slotOf(KiteLocation(12, 0)))
        assertNull(state.locationOf(-1))
        assertNull(state.locationOf(state.itemCount))
        assertFalse(inner.isChapterReady(0), "pure lookup laid out an earlier chapter")

        inner.prepareChapter(0)
        assertNull(state.locationOf(0), "ready document data is not yet a published page")
        assertNull(state.slotOf(KiteLocation(0, 0)))
        assertEquals(8, state.slotOf(target), "lookup mixed two strip versions")
        state.publishNow()
        assertEquals(KiteLocation(0, 0), state.locationOf(0))
        assertEquals(0, state.slotOf(KiteLocation(0, 0)))
        assertTrue(assertNotNull(state.slotOf(target)) > 8)
        assertEquals(target, state.locationOf(assertNotNull(state.slotOf(target))))
    }

    @Test
    fun legacy_global_hits_wait_for_the_published_prefix_and_located_hits_never_fall_back() {
        val doc = EpubDocument.open(bytes(), settings)
        doc.prepareChapter(target.chapter)
        val state = KiteDocViewState(doc)
        val before = assertNotNull(state.slotOf(target))
        val quad = KiteRectangle(20.0, 20.0, 100.0, 50.0)
        val legacy = KiteSearchHit(before, listOf(quad), "legacy")
        val unresolved = KiteSearchHit(before, listOf(quad), "unavailable", location = KiteLocation(11, 0))
        state.highlights = listOf(KiteHighlight(legacy, id = "legacy"), KiteHighlight(unresolved, id = "unavailable"))
        state.searchHighlights = listOf(legacy, unresolved)
        state.viewportSize = IntSize(200, 200)
        state.pageGeometry[before] = Rect(0f, 0f, 200f, 200f)

        assertTrue(state.highlightsByPage.isEmpty(), "an unknown global page must not mean a current slot")
        assertTrue(state.searchHitsByPage.isEmpty())
        assertNull(state.highlightAt(Offset(40f, 30f)))

        doc.prepareChapter(0)
        assertTrue(doc.pageCountIn(0) > before, "chapter 0 must contain the legacy global page")
        assertTrue(state.highlightsByPage.isEmpty(), "the ready prefix has not been published")
        assertTrue(state.searchHitsByPage.isEmpty())
        state.publishNow()

        assertEquals(KiteLocation(0, before), state.locationOf(before))
        assertEquals(listOf("legacy"), state.highlightsByPage[before]?.map { it.id })
        assertEquals(listOf(legacy), state.searchHitsByPage[before])
        assertEquals("legacy", state.highlightAt(Offset(40f, 30f))?.id)
        assertFalse(state.isComplete, "known-prefix lookup should not require the entire book")
        assertNull(state.slotOf(assertNotNull(unresolved.location)))

        val located = KiteSearchHit(before, listOf(quad), "located", location = target)
        state.highlights = listOf(KiteHighlight(located, id = "located"))
        state.searchHighlights = listOf(located)
        val after = assertNotNull(state.slotOf(target))
        assertTrue(after > before)
        assertEquals(listOf(located), state.searchHitsByPage[after])
        assertNull(state.searchHitsByPage[before], "a stale global integer must lose to its location")
        assertNull(state.highlightAt(Offset(40f, 30f)), "the located mark must leave the old integer's page")
        state.pageGeometry.clear()
        state.pageGeometry[after] = Rect(0f, 0f, 200f, 200f)
        assertEquals("located", state.highlightAt(Offset(40f, 30f))?.id)
    }

    @Test
    fun a_saved_chapter_eight_highlight_keeps_its_words_pixels_and_hits_through_publication_and_reopen() =
        forBothEffectOrders { queued ->
            val source = bytes()
            val first = EpubDocument.open(source, settings)
            first.prepareChapter(target.chapter)
            val held = LatchedDocument(first, held = 7)
            lateinit var state: KiteDocViewState
            lateinit var saved: ByteArray
            var start = -1
            var end = -1
            val callbacks = ArrayList<KiteTextSelection?>()
            val (scene, driver) = drivenScene(200, 260, queued) {
                state = rememberKiteDocViewState(held, KiteBookmark.Flow(target.chapter))
                KiteDocView(
                    state, Modifier.fillMaxSize(), layout = KiteDocLayout.Paged(),
                    colors = KiteDocViewColors(searchHighlight = Color.Cyan),
                )
            }
            scene.use {
                driver.pumpUntilState {
                    state.currentLocation == target && state.openAt == null &&
                        state.slotOf(target)?.let { state.pageGeometry.containsKey(it) } == true
                }
                assertFalse(first.isChapterReady(7), "the earlier chapter must remain pending")
                val slotBefore = assertNotNull(state.slotOf(target))
                val text = assertNotNull(first.page(target).textContent())
                fun pointAt(char: Int): Offset = viewportPoint(state, target, text.quadsFor(char, char).first())
                state.onSelectionChange = { callbacks += it }
                onTestUiThread { state.beginSelection(pointAt(0)) }
                onTestUiThread { state.extendSelection(pointAt(6)) }
                onTestUiThread { state.endSelectionGesture() }
                val selection = assertNotNull(state.selection)
                assertEquals(target, selection.location)
                assertEquals(target, callbacks.last()?.location)
                start = selection.start
                end = selection.end
                val hit = KiteSearchHit(selection.pageIndex, selection.quads, selection.text, location = selection.location)
                saved = encode(hit)
                onTestUiThread { state.clearSelection() }
                val mark = KiteHighlight(hit, color = Color.Magenta, id = "saved")
                assertEquals(target, mark.location)
                state.highlights = listOf(mark)
                assertPaintedAndHittable(driver, state, hit)

                held.release()
                driver.pumpUntilState { state.isComplete }
                driver.pumpFrames(4)
                val slotAfter = assertNotNull(state.slotOf(target))
                assertTrue(slotAfter > slotBefore, "earlier chapters must move the old integer")
                assertEquals(target, state.currentLocation)
                assertEquals(selection.text, assertNotNull(first.page(target).textContent()).copyText(start, end))
                assertEquals(slotBefore, hit.pageIndex, "the stored legacy field must really be stale")
                assertPaintedAndHittable(driver, state, hit)

                state.highlights = emptyList()
                state.searchHighlights = listOf(hit)
                assertPainted(driver, state, hit, Color.Cyan)
                assertNull(state.highlightAt(viewportPoint(state, target, hit.quads.first())), "search hits are not saved marks")
                state.searchHighlights = emptyList()

                // A provided but unavailable location cannot paint at an otherwise valid integer.
                state.highlights = listOf(mark, KiteHighlight(
                    KiteSearchHit(slotAfter, hit.quads, hit.text, location = KiteLocation(99, 0)),
                    color = Color.Green,
                    id = "unavailable",
                ))
                assertPaintedAndHittable(driver, state, hit)
            }

            // Host persistence stores primitives; reopening uses the same content and layout.
            val restored = decode(saved)
            val reopened = EpubDocument.open(source, settings)
            val (newScene, newDriver) = drivenScene(200, 260, queued) {
                state = rememberKiteDocViewState(reopened, KiteBookmark.Flow(target.chapter))
                KiteDocView(state, Modifier.fillMaxSize(), layout = KiteDocLayout.Paged())
            }
            newScene.use {
                state.highlights = listOf(KiteHighlight(restored, color = Color.Magenta, id = "saved"))
                newDriver.pumpUntilState {
                    state.isComplete && state.currentLocation == target &&
                        state.slotOf(target)?.let { state.pageGeometry.containsKey(it) } == true
                }
                val text = assertNotNull(reopened.page(assertNotNull(restored.location)).textContent())
                assertEquals(restored.text, text.copyText(start, end), "reopening changed the marked words")
                assertEquals(restored.quads, text.quadsFor(start, end), "the fixture must use an identical layout")
                assertPaintedAndHittable(newDriver, state, restored)
            }
        }

    private fun assertPaintedAndHittable(driver: SceneTestDriver, state: KiteDocViewState, hit: KiteSearchHit) {
        val location = assertNotNull(hit.location)
        assertPainted(driver, state, hit, Color.Magenta)
        val point = viewportPoint(state, location, hit.quads.first())
        val mark = assertNotNull(state.highlightAt(point))
        assertEquals("saved", mark.id)
        assertEquals(location, mark.location)
        assertEquals(hit.text, mark.hit.text)
        assertEquals(location, assertNotNull(state.hitTestDisplay(point)).location)
        assertEquals(location, assertNotNull(state.hitTest(point)).location)
    }

    private fun assertPainted(driver: SceneTestDriver, state: KiteDocViewState, hit: KiteSearchHit, expected: Color) {
        val location = assertNotNull(hit.location)
        driver.pumpUntil { pixels ->
            val slot = state.slotOf(location) ?: return@pumpUntil false
            val rect = state.displayRectToViewport(slot, hit.quads.first()) ?: return@pumpUntil false
            val x = rect.center.x.toInt()
            val y = rect.center.y.toInt()
            if (x !in 0 until pixels.width || y !in 0 until pixels.height) return@pumpUntil false
            val color = pixels[x, y]
            kotlin.math.abs(color.red - expected.red) < 0.05f &&
                kotlin.math.abs(color.green - expected.green) < 0.05f &&
                kotlin.math.abs(color.blue - expected.blue) < 0.05f
        }
    }

    private fun viewportPoint(state: KiteDocViewState, location: KiteLocation, quad: KiteRectangle): Offset =
        assertNotNull(state.displayRectToViewport(assertNotNull(state.slotOf(location)), quad)).center

    private fun encode(hit: KiteSearchHit): ByteArray = ByteArrayOutputStream().also { bytes ->
        DataOutputStream(bytes).use { output ->
            val location = assertNotNull(hit.location)
            output.writeInt(location.chapter)
            output.writeInt(location.page)
            output.writeInt(hit.pageIndex)
            output.writeUTF(hit.text)
            output.writeInt(hit.quads.size)
            hit.quads.forEach { quad ->
                output.writeDouble(quad.left)
                output.writeDouble(quad.bottom)
                output.writeDouble(quad.right)
                output.writeDouble(quad.top)
            }
        }
    }.toByteArray()

    private fun decode(bytes: ByteArray): KiteSearchHit = DataInputStream(ByteArrayInputStream(bytes)).use { input ->
        val location = KiteLocation(input.readInt(), input.readInt())
        val pageIndex = input.readInt()
        val text = input.readUTF()
        val quads = List(input.readInt()) {
            KiteRectangle(input.readDouble(), input.readDouble(), input.readDouble(), input.readDouble())
        }
        KiteSearchHit(pageIndex, quads, text, location = location)
    }
}
