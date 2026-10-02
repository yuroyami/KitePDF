package io.github.yuroyami.kitepdf.compose

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerType
import io.github.yuroyami.kitepdf.PdfDocument
import io.github.yuroyami.kitepdf.core.KiteDocument
import io.github.yuroyami.kitepdf.core.KitePage
import io.github.yuroyami.kitepdf.core.render.KiteCanvas
import io.github.yuroyami.kitepdf.core.render.KiteMatrix
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * A damaged page never ends the host app: not when it is drawn in the Compose draw pass, and not
 * when a reader taps or long-presses it. It shows its paper, and a gesture on it does nothing
 * (#333, #334).
 */
class DamagedPageSceneTest {

    /** A document of one page, for pages that are not PDF pages. */
    private class OnePage(page: KitePage) : KiteDocument {
        override val pageCount: Int = 1
        override val pages: List<KitePage> = listOf(page)
    }

    /** A page that throws [failure] from every draw, and counts the draws. */
    private class ThrowingPage(private val failure: Throwable) : KitePage {
        var draws = 0
        override val displayWidth: Double = 100.0
        override val displayHeight: Double = 100.0
        override fun displayToDeviceBase(): KiteMatrix = KiteMatrix(1.0, 0.0, 0.0, -1.0, 0.0, displayHeight)
        override fun renderTo(canvas: KiteCanvas, deviceCtm: KiteMatrix) {
            draws++
            canvas.beginPage(displayWidth, displayHeight, deviceCtm)
            throw failure
        }
    }

    /**
     * A one-page PDF with [pageEntries] in its page dictionary, [pagesEntries] in its page tree
     * root and [objects] after it. The page is 200 by 200 points unless [pagesEntries] says else.
     */
    private fun pdf(pageEntries: String, vararg objects: String, pagesEntries: String = "/Count 1 /MediaBox [0 0 200 200]"): ByteArray {
        val sb = StringBuilder("%PDF-1.7\n")
        val offsets = ArrayList<Int>()
        fun add(body: String) {
            offsets.add(sb.length)
            sb.append("${offsets.size} 0 obj\n$body\nendobj\n")
        }
        add("<< /Type /Catalog /Pages 2 0 R >>")
        add("<< /Type /Pages /Kids [3 0 R] $pagesEntries >>")
        add("<< /Type /Page /Parent 2 0 R /Resources << >> $pageEntries >>")
        objects.forEach(::add)
        val xref = sb.length
        sb.append("xref\n0 ${offsets.size + 1}\n0000000000 65535 f \n")
        for (offset in offsets) sb.append("${offset.toString().padStart(10, '0')} 00000 n \n")
        sb.append("trailer\n<< /Size ${offsets.size + 1} /Root 1 0 R >>\nstartxref\n$xref\n%%EOF\n")
        return sb.toString().encodeToByteArray()
    }

    private fun stream(dict: String, data: String) = "<< $dict /Length ${data.length} >>\nstream\n$data\nendstream"

    @Test
    fun a_vectorized_page_that_throws_shows_its_paper_and_is_not_drawn_again() = withoutEscapes {
        val page = ThrowingPage(IllegalStateException("torn page"))
        val doc = OnePage(page)
        val (scene, driver) = drivenScene(100, 100, queued = false) {
            KiteDocView(
                state = rememberKiteDocViewState(doc),
                modifier = Modifier.fillMaxSize(),
                layout = KiteDocLayout.SinglePage(0),
                renderSpec = KiteRenderSpec.Vectorized(),
                colors = KiteDocViewColors(pageBackground = Color.Green),
            )
        }
        scene.use {
            val frame = driver.pumpFrames(10).toComposeImageBitmap().toPixelMap()
            assertEquals(Color.Green, frame[50, 50], "the page shows its paper")
            assertEquals(1, page.draws, "a page that failed is not drawn again on every frame")
        }
    }

    /** A draw that overflows the stack is caught like an exception, on the JVM and Android. */
    @Test
    fun a_vectorized_page_that_overflows_the_stack_shows_its_paper() = withoutEscapes {
        val doc = OnePage(ThrowingPage(StackOverflowError("deep page")))
        val (scene, driver) = drivenScene(100, 100, queued = false) {
            KiteDocView(
                state = rememberKiteDocViewState(doc),
                modifier = Modifier.fillMaxSize(),
                layout = KiteDocLayout.SinglePage(0),
                renderSpec = KiteRenderSpec.Vectorized(),
                colors = KiteDocViewColors(pageBackground = Color.Green),
            )
        }
        scene.use {
            val frame = driver.pumpFrames(4).toComposeImageBitmap().toPixelMap()
            assertEquals(Color.Green, frame[50, 50], "the page shows its paper, not a half-drawn page")
        }
    }

    /**
     * Thirty thousand clips used to recurse once per clip in every paint and overflow the stack.
     * Each clip is now applied once, the renderer stops at its cap, and the page paints in both
     * render modes (#335).
     */
    @Test
    fun a_page_with_thirty_thousand_clips_paints() = withoutEscapes {
        val content = "0 0 200 200 re W n\n".repeat(30_000) + "1 0 0 rg 0 0 200 200 re f"
        val doc = PdfDocument.open(pdf("/Contents 4 0 R", stream("", content)))
        for (spec in listOf(KiteRenderSpec.Vectorized(), KiteRenderSpec.Rasterized())) {
            val (scene, driver) = drivenScene(100, 100, queued = false) {
                KiteDocView(
                    state = rememberKiteDocViewState(doc),
                    modifier = Modifier.fillMaxSize(),
                    layout = KiteDocLayout.SinglePage(0),
                    renderSpec = spec,
                    colors = KiteDocViewColors(pageBackground = Color.Green),
                )
            }
            scene.use {
                val frame = driver.pumpUntil { it[50, 50] == Color.Red }.toComposeImageBitmap().toPixelMap()
                assertEquals(Color.Red, frame[50, 50], "$spec")
            }
        }
    }

    /** A clip still clips: half the page is clipped away, and the fill shows only in the other half. */
    @Test
    fun a_clip_still_limits_the_paint() = withoutEscapes {
        val content = "q 0 0 100 200 re W n 1 0 0 rg 0 0 200 200 re f Q"
        val doc = PdfDocument.open(pdf("/Contents 4 0 R", stream("", content)))
        val (scene, driver) = drivenScene(100, 100, queued = false) {
            KiteDocView(
                state = rememberKiteDocViewState(doc),
                modifier = Modifier.fillMaxSize(),
                layout = KiteDocLayout.SinglePage(0),
                renderSpec = KiteRenderSpec.Vectorized(),
                colors = KiteDocViewColors(pageBackground = Color.Green),
            )
        }
        scene.use {
            val frame = driver.pumpUntil { it[25, 50] == Color.Red }.toComposeImageBitmap().toPixelMap()
            assertEquals(Color.Red, frame[25, 50], "inside the clip")
            assertEquals(Color.Green, frame[75, 50], "outside the clip")
        }
    }

    /** A link whose script stream has an unknown filter acts as a link that does nothing (#334). */
    @Test
    fun a_tap_on_a_link_with_an_unreadable_script_reaches_on_tap() = withoutEscapes {
        val doc = PdfDocument.open(
            pdf(
                "/Contents 4 0 R /Annots [5 0 R]",
                stream("", "0 0 1 rg 0 0 200 200 re f"),
                "<< /Type /Annot /Subtype /Link /Rect [0 0 200 200] /A << /S /JavaScript /JS 6 0 R >> >>",
                stream("/Filter /NoSuchFilter", "abc"),
            ),
        )
        var taps = 0
        val (scene, driver) = drivenScene(200, 200, queued = false) {
            KiteDocView(
                state = rememberKiteDocViewState(doc),
                modifier = Modifier.fillMaxSize(),
                layout = KiteDocLayout.SinglePage(0),
                zoomSpec = KiteZoomSpec(doubleTapEnabled = false),
                onTap = { taps++ },
            )
        }
        scene.use {
            driver.pumpFrames(4)
            scene.sendPointerEvent(PointerEventType.Press, Offset(100f, 100f), type = PointerType.Touch)
            scene.sendPointerEvent(PointerEventType.Release, Offset(100f, 100f), type = PointerType.Touch)
            driver.pumpUntilState { taps == 1 }
        }
    }

    /** A page whose content cannot be read has no text, so a long press selects nothing (#334). */
    @Test
    fun a_long_press_on_a_page_with_unreadable_content_selects_nothing() = withoutEscapes {
        val doc = PdfDocument.open(pdf("/Contents 42"))
        lateinit var state: KiteDocViewState
        val (scene, driver) = drivenScene(200, 200, queued = false) {
            state = rememberKiteDocViewState(doc)
            KiteDocView(state = state, modifier = Modifier.fillMaxSize(), layout = KiteDocLayout.SinglePage(0))
        }
        scene.use {
            driver.pumpUntilState { state.pageGeometry.isNotEmpty() }
            scene.sendPointerEvent(PointerEventType.Press, Offset(100f, 100f), type = PointerType.Touch)
            val pressed = System.currentTimeMillis()
            while (System.currentTimeMillis() - pressed < 700) driver.pumpFrames(0)
            scene.sendPointerEvent(PointerEventType.Release, Offset(100f, 100f), type = PointerType.Touch)
            driver.pumpFrames(4)
            assertNull(state.selection)
            assertEquals(false, state.isSelectionActive, "a long press that selected nothing gives the page back")
        }
    }

    /** A PDF whose `/Count` says three pages while its tree holds one shows that one page (#329). */
    @Test
    fun a_pdf_that_declares_more_pages_than_it_has_shows_the_real_ones() = withoutEscapes {
        val doc = PdfDocument.open(
            pdf("/Contents 4 0 R", stream("", "1 0 0 rg 0 0 100 100 re f"), pagesEntries = "/Count 3 /MediaBox [0 0 100 100]"),
        )
        lateinit var state: KiteDocViewState
        val (scene, driver) = drivenScene(200, 800, queued = false) {
            state = rememberKiteDocViewState(doc)
            KiteDocView(state = state, modifier = Modifier.fillMaxSize())
        }
        scene.use {
            val started = System.currentTimeMillis()
            while (System.currentTimeMillis() - started < 1_500) driver.pumpFrames(0)
            assertEquals(1, state.itemCount)
        }
    }

    /** A page with no `/MediaBox` anywhere composes, drawn as a US Letter page (#330). */
    @Test
    fun a_page_without_a_media_box_is_shown() = withoutEscapes {
        val doc = PdfDocument.open(
            pdf("/Contents 4 0 R", stream("", "1 0 0 rg 10 10 100 100 re f"), pagesEntries = "/Count 1"),
        )
        lateinit var state: KiteDocViewState
        val (scene, driver) = drivenScene(200, 200, queued = false) {
            state = rememberKiteDocViewState(doc)
            KiteDocView(state = state, modifier = Modifier.fillMaxSize())
        }
        scene.use {
            driver.pumpUntilState { state.pageGeometry.isNotEmpty() }
            assertEquals(612.0 / 792.0, doc.pages[0].displayWidth / doc.pages[0].displayHeight)
        }
    }
}
