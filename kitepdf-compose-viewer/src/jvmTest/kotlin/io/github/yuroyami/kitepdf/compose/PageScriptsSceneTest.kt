package io.github.yuroyami.kitepdf.compose

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.use
import io.github.yuroyami.kitepdf.PdfDocument
import io.github.yuroyami.kitepdf.PdfFormState
import io.github.yuroyami.kitepdf.PdfScriptHandler
import io.github.yuroyami.kitepdf.epub.EpubDocument
import io.github.yuroyami.kitepdf.epub.EpubSettings
import kotlinx.coroutines.runBlocking
import java.util.Collections
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Each page's open and close scripts run as the reader lands on pages: with page indices, once
 * the page settles, and with the open page closed when the view leaves (#366).
 */
class PageScriptsSceneTest {

    /** A PDF of [count] empty 200 x 200 pages. */
    private fun pagesPdf(count: Int): ByteArray {
        val sb = StringBuilder("%PDF-1.7\n")
        val offsets = ArrayList<Int>()
        fun add(body: String) {
            offsets.add(sb.length)
            sb.append("${offsets.size} 0 obj\n$body\nendobj\n")
        }
        val kids = (0 until count).joinToString(" ") { "${it + 3} 0 R" }
        add("<< /Type /Catalog /Pages 2 0 R >>")
        add("<< /Type /Pages /Kids [$kids] /Count $count /MediaBox [0 0 200 200] >>")
        repeat(count) { add("<< /Type /Page /Parent 2 0 R /Resources << >> >>") }
        val xref = sb.length
        sb.append("xref\n0 ${offsets.size + 1}\n0000000000 65535 f \n")
        for (offset in offsets) sb.append("${offset.toString().padStart(10, '0')} 00000 n \n")
        sb.append("trailer\n<< /Size ${offsets.size + 1} /Root 1 0 R >>\nstartxref\n$xref\n%%EOF\n")
        return sb.toString().encodeToByteArray()
    }

    private class Recorder(document: PdfDocument) : PdfScriptHandler {
        override val formState: PdfFormState = PdfFormState(document)
        val events: MutableList<String> = Collections.synchronizedList(ArrayList())
        override fun documentOpened() { events += "open" }
        override fun pageOpened(pageIndex: Int) { events += "page $pageIndex" }
        override fun pageClosed(pageIndex: Int) { events += "close $pageIndex" }
    }

    @Test
    fun each_page_opens_and_closes_as_the_reader_moves() {
        for (layout in listOf(KiteDocLayout.Paged(), KiteDocLayout.Continuous())) {
            forBothEffectOrders { queued ->
                val doc = PdfDocument.open(pagesPdf(3))
                val scripts = Recorder(doc)
                lateinit var state: KiteDocViewState
                val (scene, driver) = drivenScene(200, 200, queued) {
                    state = rememberKiteDocViewState(doc)
                    KiteDocView(state = state, modifier = Modifier.fillMaxSize(), layout = layout, scripts = scripts)
                }
                // Between frames, as an app's main thread runs it: a list scrolled inside a frame
                // would measure in the middle of composition.
                fun go(page: Int) = runBlocking { state.scrollToPage(page) }
                scene.use {
                    fun waitFor(what: String, check: () -> Boolean) = try {
                        driver.pumpUntilState(check = check)
                    } catch (failure: AssertionError) {
                        throw AssertionError("${layout::class.simpleName}: no $what, events ${scripts.events}, page ${state.currentPage}", failure)
                    }
                    waitFor("first page") { "page 0" in scripts.events }
                    go(1)
                    waitFor("second page") { "page 1" in scripts.events }
                    go(0)
                    waitFor("return") { scripts.events.count { it == "page 0" } == 2 }
                    driver.pumpFrames(10)
                    assertEquals(
                        listOf("open", "page 0", "close 0", "page 1", "close 1", "page 0"),
                        scripts.events.toList(),
                        layout::class.simpleName,
                    )
                }
            }
        }
    }

    @Test
    fun the_open_page_closes_when_the_view_leaves() {
        forBothEffectOrders { queued ->
            val doc = PdfDocument.open(pagesPdf(2))
            val scripts = Recorder(doc)
            var show by mutableStateOf(true)
            val (scene, driver) = drivenScene(200, 200, queued) {
                val state = rememberKiteDocViewState(doc, initialPage = 1)
                if (show) KiteDocView(state = state, modifier = Modifier.fillMaxSize(), layout = KiteDocLayout.Paged(), scripts = scripts)
            }
            scene.use {
                driver.pumpUntilState { "page 1" in scripts.events }
                show = false
                driver.pumpUntilState { "close 1" in scripts.events }
                assertEquals(listOf("open", "page 1", "close 1"), scripts.events.toList())
            }
        }
    }

    /** An EPUB has no page scripts, so a handler hears no page numbers from one. */
    @Test
    fun an_epub_runs_no_page_scripts() {
        forBothEffectOrders { queued ->
            val doc = EpubDocument.open(
                multiSpineEpub(listOf("<p>One.</p>", "<p>Two.</p>")),
                EpubSettings(pageWidth = 200.0, pageHeight = 200.0),
            )
            val scripts = Recorder(PdfDocument.open(pagesPdf(1)))
            lateinit var state: KiteDocViewState
            val (scene, driver) = drivenScene(200, 200, queued) {
                state = rememberKiteDocViewState(doc)
                KiteDocView(state = state, modifier = Modifier.fillMaxSize(), layout = KiteDocLayout.Paged(), scripts = scripts)
            }
            scene.use {
                driver.pumpUntilState { "open" in scripts.events && state.pageGeometry.isNotEmpty() }
                driver.pumpFrames(20)
                assertEquals(listOf("open"), scripts.events.toList())
            }
        }
    }

    @Test
    fun a_swipe_that_comes_back_opens_no_page() {
        forBothEffectOrders { queued ->
            val doc = PdfDocument.open(pagesPdf(2))
            val scripts = Recorder(doc)
            lateinit var state: KiteDocViewState
            val (scene, driver) = drivenScene(200, 200, queued) {
                state = rememberKiteDocViewState(doc)
                KiteDocView(state = state, modifier = Modifier.fillMaxSize(), layout = KiteDocLayout.Paged(), scripts = scripts)
            }
            scene.use {
                driver.pumpUntilState { "page 0" in scripts.events }
                // Past the middle and back, with the finger down the whole time.
                scene.sendPointerEvent(PointerEventType.Press, Offset(190f, 100f), type = PointerType.Touch)
                for (x in listOf(170f, 140f, 110f, 80f, 50f, 20f)) {
                    scene.sendPointerEvent(PointerEventType.Move, Offset(x, 100f), type = PointerType.Touch)
                    driver.pumpFrames(1)
                }
                for (x in listOf(50f, 80f, 110f, 140f, 170f, 190f)) {
                    scene.sendPointerEvent(PointerEventType.Move, Offset(x, 100f), type = PointerType.Touch)
                    driver.pumpFrames(1)
                }
                scene.sendPointerEvent(PointerEventType.Release, Offset(190f, 100f), type = PointerType.Touch)
                driver.pumpFrames(60)
                assertEquals(0, state.currentPage)
                assertEquals(listOf("open", "page 0"), scripts.events.toList())
            }
        }
    }
}
