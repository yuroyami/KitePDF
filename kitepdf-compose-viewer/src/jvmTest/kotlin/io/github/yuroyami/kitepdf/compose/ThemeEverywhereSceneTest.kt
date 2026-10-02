package io.github.yuroyami.kitepdf.compose

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.PixelMap
import androidx.compose.ui.unit.dp
import io.github.yuroyami.kitepdf.PdfDocument
import io.github.yuroyami.kitepdf.PdfFormState
import io.github.yuroyami.kitepdf.PdfScriptHandler
import io.github.yuroyami.kitepdf.core.KiteBookmark
import io.github.yuroyami.kitepdf.core.render.ReaderTheme
import io.github.yuroyami.kitepdf.epub.EpubDocument
import io.github.yuroyami.kitepdf.epub.EpubSettings
import java.util.concurrent.CountDownLatch
import kotlin.test.Test

/**
 * A reader theme reaches everything the viewer paints for a page: the placeholder before its
 * raster lands, a chapter still being laid out, its thumbnail and its form widgets (#419).
 */
class ThemeEverywhereSceneTest {

    private val dark = KiteDocViewColors(theme = ReaderTheme.Dark)

    private fun isDark(pixels: PixelMap, x: Int, y: Int): Boolean = pixels[x, y].let { it.red + it.green + it.blue < 1f }

    /** One 200 x 200 page, with a white text field across [20 120 180 160] when [withField]. */
    private fun pdf(withField: Boolean = false): ByteArray {
        val sb = StringBuilder("%PDF-1.7\n")
        val offsets = ArrayList<Int>()
        fun add(body: String) {
            offsets.add(sb.length)
            sb.append("${offsets.size} 0 obj\n$body\nendobj\n")
        }
        add(if (withField) "<< /Type /Catalog /Pages 2 0 R /AcroForm << /Fields [4 0 R] >> >>" else "<< /Type /Catalog /Pages 2 0 R >>")
        add("<< /Type /Pages /Kids [3 0 R] /Count 1 /MediaBox [0 0 200 200] >>")
        add("<< /Type /Page /Parent 2 0 R /Resources << >>${if (withField) " /Annots [4 0 R]" else ""} >>")
        if (withField) add("<< /Type /Annot /Subtype /Widget /FT /Tx /T (name) /V () /Rect [20 120 180 160] /MK << /BG [1] >> >>")
        val xref = sb.length
        sb.append("xref\n0 ${offsets.size + 1}\n0000000000 65535 f \n")
        for (offset in offsets) sb.append("${offset.toString().padStart(10, '0')} 00000 n \n")
        sb.append("trailer\n<< /Size ${offsets.size + 1} /Root 1 0 R >>\nstartxref\n$xref\n%%EOF\n")
        return sb.toString().encodeToByteArray()
    }

    @Test
    fun a_page_that_has_not_landed_shows_the_theme_paper() {
        forBothEffectOrders { queued ->
            val release = CountDownLatch(1)
            try {
                val doc = PdfDocument.open(pdf())
                // Holds the raster until the test lets go, so the slot shows its placeholder.
                val slow: KiteCanvasDecorator = { canvas -> release.await(); canvas }
                val (scene, driver) = drivenScene(200, 200, queued) {
                    KiteDocView(
                        state = rememberKiteDocViewState(doc),
                        modifier = Modifier.fillMaxSize(),
                        layout = KiteDocLayout.SinglePage(0),
                        colors = dark,
                        renderSpec = KiteRenderSpec.Rasterized(canvasDecorator = slow),
                    )
                }
                scene.use {
                    driver.pumpFrames(10)
                    driver.pumpUntil { isDark(it, 100, 100) }
                }
            } finally {
                release.countDown()
            }
        }
    }

    @Test
    fun a_chapter_still_laying_out_shows_the_theme_paper() {
        for (layout in listOf(KiteDocLayout.Continuous(), KiteDocLayout.Paged())) {
            forBothEffectOrders { queued ->
                val book = EpubDocument.open(
                    multiSpineEpub(listOf("<p>One.</p>", "<p>Two.</p>")),
                    EpubSettings(pageWidth = 200.0, pageHeight = 200.0),
                )
                book.prepareChapter(0)
                val doc = LatchedDocument(book, held = 1)
                val (scene, driver) = drivenScene(200, 200, queued) {
                    KiteDocView(
                        state = rememberKiteDocViewState(doc, KiteBookmark.Flow(chapter = 1, charOffset = 0)),
                        modifier = Modifier.fillMaxSize(),
                        layout = layout,
                        colors = dark,
                    )
                }
                scene.use {
                    driver.pumpFrames(10)
                    try {
                        driver.pumpUntil { isDark(it, 100, 100) }
                    } catch (failure: AssertionError) {
                        throw AssertionError("${layout::class.simpleName}: the chapter placeholder is not themed", failure)
                    }
                }
            }
        }
    }

    @Test
    fun a_thumbnail_takes_the_viewer_theme() {
        forBothEffectOrders { queued ->
            val doc = PdfDocument.open(pdf())
            val (scene, driver) = drivenScene(200, 300, queued) {
                val state = rememberKiteDocViewState(doc)
                Column(Modifier.fillMaxSize()) {
                    KiteDocView(state = state, modifier = Modifier.size(200.dp, 200.dp), colors = dark)
                    KiteThumbnailStrip(state = state, modifier = Modifier.height(100.dp))
                }
            }
            scene.use {
                // The first thumbnail is 72 x 72 after 8 px of padding, below the 200 px viewer.
                driver.pumpFrames(10)
                driver.pumpUntil { isDark(it, 44, 244) }
            }
        }
    }

    @Test
    fun a_form_widget_takes_the_theme() {
        forBothEffectOrders { queued ->
            val doc = PdfDocument.open(pdf(withField = true))
            val scripts = object : PdfScriptHandler {
                override val formState: PdfFormState = PdfFormState(doc)
            }
            val (scene, driver) = drivenScene(200, 200, queued) {
                KiteDocView(
                    state = rememberKiteDocViewState(doc),
                    modifier = Modifier.fillMaxSize(),
                    layout = KiteDocLayout.SinglePage(0),
                    colors = dark,
                    scripts = scripts,
                )
            }
            scene.use {
                // The field's white background, [20 120 180 160], is display y 40..80.
                driver.pumpFrames(60)
                driver.pumpUntil { isDark(it, 100, 60) }
            }
        }
    }
}
