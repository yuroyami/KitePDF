package io.github.yuroyami.kitepdf.compose

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.font.createFontFamilyResolver
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import io.github.yuroyami.kitepdf.PdfDocument
import io.github.yuroyami.kitepdf.PdfFormState
import io.github.yuroyami.kitepdf.PdfScriptHandler
import io.github.yuroyami.kitepdf.core.render.ReaderTheme
import io.github.yuroyami.kitepdf.core.render.RgbColor
import io.github.yuroyami.kitepdf.core.render.KiteCanvas
import io.github.yuroyami.kitepdf.compose.EdtImageComposeScene as ImageComposeScene
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNotSame
import kotlin.test.assertTrue

/**
 * A page drawn through Compose's text before a font for its characters lands draws again once it
 * lands. In a browser Compose downloads the fallback faces for code points that no face it has
 * covers, and marks every paragraph measured before as stale, so its own text draws again, while
 * the viewer kept the page it had drawn with a box for each of those characters (#595).
 */
class FallbackFontRedrawTest {

    private val density = Density(1f)

    /** A 300 x 300 page whose font /F1 is neither embedded nor one of the standard 14, so its text is host text. */
    private fun pdf(): PdfDocument {
        val content = "BT /F1 40 Tf 20 150 Td (Kite) Tj ET"
        val sb = StringBuilder("%PDF-1.7\n")
        val offsets = ArrayList<Int>()
        fun add(body: String) {
            offsets.add(sb.length)
            sb.append("${offsets.size} 0 obj\n$body\nendobj\n")
        }
        add("<< /Type /Catalog /Pages 2 0 R >>")
        add("<< /Type /Pages /Kids [3 0 R] /Count 1 >>")
        add("<< /Type /Page /Parent 2 0 R /MediaBox [0 0 300 300] /Resources << /Font << /F1 5 0 R >> >> /Contents 4 0 R >>")
        add("<< /Length ${content.length} >>\nstream\n$content\nendstream")
        add("<< /Type /Font /Subtype /TrueType /BaseFont /Verdana >>")
        val xref = sb.length
        sb.append("xref\n0 ${offsets.size + 1}\n0000000000 65535 f \n")
        for (offset in offsets) sb.append("${offset.toString().padStart(10, '0')} 00000 n \n")
        sb.append("trailer\n<< /Size ${offsets.size + 1} /Root 1 0 R >>\nstartxref\n$xref\n%%EOF\n")
        return PdfDocument.open(sb.toString().encodeToByteArray())
    }

    /**
     * A 200 x 200 page with one text field, whose own appearance draws its value in /F1, a font
     * that is neither embedded nor one of the standard 14.
     */
    private fun formPdf(): PdfDocument {
        val appearance = "BT /F1 20 Tf 5 12 Td (Kite) Tj ET"
        val sb = StringBuilder("%PDF-1.7\n")
        val offsets = ArrayList<Int>()
        fun add(body: String) {
            offsets.add(sb.length)
            sb.append("${offsets.size} 0 obj\n$body\nendobj\n")
        }
        add("<< /Type /Catalog /Pages 2 0 R /AcroForm << /Fields [4 0 R] /DR << /Font << /F1 5 0 R >> >> >> >>")
        add("<< /Type /Pages /Kids [3 0 R] /Count 1 /MediaBox [0 0 200 200] >>")
        add("<< /Type /Page /Parent 2 0 R /Resources << >> /Annots [4 0 R] >>")
        add("<< /Type /Annot /Subtype /Widget /FT /Tx /T (name) /V (Kite) /Rect [20 80 180 120] /DA (/F1 20 Tf 0 g) /AP << /N 6 0 R >> >>")
        add("<< /Type /Font /Subtype /TrueType /BaseFont /Verdana >>")
        add("<< /Type /XObject /Subtype /Form /BBox [0 0 160 40] /Resources << /Font << /F1 5 0 R >> >> /Length ${appearance.length} >>\nstream\n$appearance\nendstream")
        val xref = sb.length
        sb.append("xref\n0 ${offsets.size + 1}\n0000000000 65535 f \n")
        for (offset in offsets) sb.append("${offset.toString().padStart(10, '0')} 00000 n \n")
        sb.append("trailer\n<< /Size ${offsets.size + 1} /Root 1 0 R >>\nstartxref\n$xref\n%%EOF\n")
        return PdfDocument.open(sb.toString().encodeToByteArray())
    }

    @Test
    fun a_cached_page_draws_again_once_a_font_for_its_text_lands() = runBlocking {
        val measurer = TextMeasurer(createFontFamilyResolver(), density, LayoutDirection.Ltr)
        // Host text through Compose's text, as a browser draws it.
        val renderer = KitePageRasterizer(density, LayoutDirection.Ltr, measurer).apply { textOffMain = false }
        val cache = PageBitmapCache(10_000_000)
        val page = pdf().pages[0]
        suspend fun render() = renderer.rasterizeCachedOffMain(cache, page, 300, 300, Color.White, 1f, null)

        val (before, drew) = render()
        assertTrue(drew)
        assertTrue(onTestUiThread { landFallbackFont(measurer) } > 0, "the page measured no text through Compose")
        val (after, drewAgain) = render()
        assertTrue(drewAgain, "the cache kept the page drawn before the font landed")
        assertNotSame(before, after)
        val (_, drewOnceMore) = render()
        assertFalse(drewOnceMore, "a page drawn after the font landed must stay cached")
    }

    /** Finds the measurer of each canvas that draws a page, on the canvas it wraps, and counts the draws. */
    private class Measurers : KiteCanvasDecorator {
        val found: MutableSet<TextMeasurer> = ConcurrentHashMap.newKeySet()
        val draws = AtomicInteger()

        override fun invoke(inner: KiteCanvas): KiteCanvas {
            draws.incrementAndGet()
            if (inner is ComposeCanvas) {
                found += ComposeCanvas::class.java.getDeclaredField("textMeasurer").apply { isAccessible = true }.get(inner) as TextMeasurer
            }
            return inner
        }

        fun landFont(): Int = onTestUiThread { found.sumOf { landFallbackFont(it) } }
    }

    @Test
    fun a_rasterized_page_on_screen_draws_again_once_a_font_for_its_text_lands() {
        val measurers = Measurers()
        val rendered = AtomicInteger()
        val state = KiteDocViewState(pdf()).apply { hostTextOffMain = false }
        ImageComposeScene(width = 300, height = 300, density = density) {
            KiteDocView(
                state = state, modifier = Modifier.fillMaxSize(),
                renderSpec = KiteRenderSpec.Rasterized(canvasDecorator = measurers),
                onPageRendered = { index, _ -> if (index == 0) rendered.incrementAndGet() },
            )
        }.use { scene ->
            val driver = SceneTestDriver(scene)
            driver.pumpUntilState { rendered.get() > 0 }
            driver.pumpFrames(5)
            val before = rendered.get()
            assertTrue(measurers.landFont() > 0, "the page measured no text through Compose")
            driver.pumpUntilState(timeoutMs = 10_000) { rendered.get() > before }
        }
    }

    @Test
    fun a_vectorized_page_draws_again_once_a_font_for_its_text_lands() {
        val measurers = Measurers()
        val state = KiteDocViewState(pdf()).apply { hostTextOffMain = false }
        ImageComposeScene(width = 300, height = 300, density = density) {
            KiteDocView(
                state = state, modifier = Modifier.fillMaxSize(),
                renderSpec = KiteRenderSpec.Vectorized(canvasDecorator = measurers),
            )
        }.use { scene ->
            val driver = SceneTestDriver(scene)
            driver.pumpUntilState { measurers.found.isNotEmpty() }
            driver.pumpFrames(5)
            val before = measurers.draws.get()
            assertTrue(measurers.landFont() > 0, "the page measured no text through Compose")
            driver.pumpUntilState(timeoutMs = 10_000) { measurers.draws.get() > before }
        }
    }

    @Test
    fun a_thumbnail_draws_again_once_a_font_for_its_text_lands() {
        val measurers = Measurers()
        val state = KiteDocViewState(pdf()).apply {
            hostTextOffMain = false
            viewerDecorator = measurers
        }
        ImageComposeScene(width = 300, height = 100, density = density) {
            KiteThumbnailStrip(state = state, modifier = Modifier.fillMaxSize())
        }.use { scene ->
            val driver = SceneTestDriver(scene)
            // The thumbnail draws twice, first without its host text and then with it, so the font lands once the text is in.
            var before = 0
            driver.pumpUntilState {
                before = measurers.draws.get()
                measurers.landFont() > 0
            }
            driver.pumpUntilState(timeoutMs = 10_000) { measurers.draws.get() > before }
        }
    }

    @Test
    fun form_fields_draw_again_once_a_font_for_their_text_lands() {
        val doc = formPdf()
        val scripts = object : PdfScriptHandler {
            override val formState: PdfFormState = PdfFormState(doc)
        }
        val measurer = onTestUiThread { TextMeasurer(createFontFamilyResolver(), density, LayoutDirection.Ltr) }
        // The theme sees each colour the layer paints, so it counts the layer's draws.
        val paints = AtomicInteger()
        val theme = ReaderTheme(RgbColor.WHITE) { paints.incrementAndGet(); it }
        ImageComposeScene(width = 200, height = 200, density = density) {
            Box(Modifier.fillMaxSize().kiteFormLayer(doc.pages[0], scripts, measurer, 1f, 0, theme = theme, hostLines = false))
        }.use { scene ->
            val driver = SceneTestDriver(scene)
            driver.pumpUntilState { paints.get() > 0 }
            driver.pumpFrames(5)
            val before = paints.get()
            assertTrue(onTestUiThread { landFallbackFont(measurer) } > 0, "the fields measured no text through Compose")
            driver.pumpUntilState(timeoutMs = 10_000) { paints.get() > before }
        }
    }
}

/**
 * Does to every paragraph that [measurer] keeps what Compose's web text does to the paragraphs it
 * measured when a fallback font lands: each one's unresolved-symbols listener hears
 * `onNewFontInstalled`, and the paragraph then says `hasStaleResolvedFonts`. A desktop paragraph
 * has no registry to hear it from, so the test calls the listener itself (#595). Returns how many
 * paragraphs it reached. Run it on the UI thread.
 */
internal fun landFallbackFont(measurer: TextMeasurer): Int {
    fun field(owner: Any, name: String): Any? = owner.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(owner)
    val cache = field(measurer, "textLayoutCache") ?: return 0
    val lru = field(cache, "cache") ?: return 0
    val kept = (lru.javaClass.getMethod("snapshot").invoke(lru) as Map<*, *>).values.filterIsInstance<TextLayoutResult>() +
        listOfNotNull(field(cache, "singleSizeCacheResult") as TextLayoutResult?)
    val listener = Class.forName("androidx.compose.ui.text.UnresolvedSymbolsRegistry\$Listener")
    var reached = 0
    for (result in kept) {
        val intrinsics = result.multiParagraph.intrinsics
        for (info in intrinsics.javaClass.getMethod("getInfoList\$ui_text").invoke(intrinsics) as List<*>) {
            val paragraph = info!!.javaClass.getMethod("getIntrinsics").invoke(info)
            val layouter = paragraph.javaClass.getMethod("layouter").invoke(paragraph)
            listener.getMethod("onNewFontInstalled").invoke(field(layouter, "unresolvedSymbolsRegistryListener"))
            reached++
        }
    }
    return reached
}
