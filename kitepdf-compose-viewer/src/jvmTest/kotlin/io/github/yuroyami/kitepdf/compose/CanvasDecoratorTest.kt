package io.github.yuroyami.kitepdf.compose

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.mutableStateOf
import io.github.yuroyami.kitepdf.compose.EdtImageComposeScene as ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.font.createFontFamilyResolver
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import io.github.yuroyami.kitepdf.PdfDocument
import io.github.yuroyami.kitepdf.core.KitePage
import io.github.yuroyami.kitepdf.core.font.FontSpec
import io.github.yuroyami.kitepdf.core.font.KiteFontFamily
import io.github.yuroyami.kitepdf.core.font.TextGlyph
import java.util.concurrent.atomic.AtomicInteger
import io.github.yuroyami.kitepdf.core.render.KiteBlendMode
import io.github.yuroyami.kitepdf.core.render.KiteCanvas
import io.github.yuroyami.kitepdf.core.render.KiteMatrix
import io.github.yuroyami.kitepdf.core.render.KitePath
import io.github.yuroyami.kitepdf.core.render.ReaderTheme
import io.github.yuroyami.kitepdf.core.render.RgbColor
import io.github.yuroyami.kitepdf.writer.PdfBuilder
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** Public hook, cache invalidation and actual viewer pixels for #132. */
class CanvasDecoratorTest {
    private val green = RgbColor(0.0, 1.0, 0.0)
    private val yellow = RgbColor(1.0, 1.0, 0.0)

    private fun document() = PdfDocument.open(PdfBuilder().page(width = 200.0, height = 200.0) {
        setFillRgb(1.0, 0.0, 0.0); rectangle(0.0, 0.0, 100.0, 200.0); fill()
        setFillRgb(0.0, 0.0, 1.0); rectangle(100.0, 0.0, 100.0, 200.0); fill()
    }.build())

    /** Identical hashes exercise actual decorator keys, not hash-only cache keys. */
    private class Ink(private val replacement: RgbColor) : (KiteCanvas) -> KiteCanvas {
        override fun hashCode(): Int = 7
        override fun invoke(inner: KiteCanvas): KiteCanvas = object : KiteCanvas by inner {
            override fun fillPath(path: KitePath, ctm: KiteMatrix, color: RgbColor, evenOdd: Boolean, alpha: Double, blendMode: KiteBlendMode) {
                inner.fillPath(path, ctm, if (color.r > 0.9) replacement else color, evenOdd, alpha, blendMode)
                // Insert an additional mark during this very draw call, under the page CTM.
                if (color.r > 0.9) {
                    val mark = KitePath.Builder().apply { rectangle(10.0, 10.0, 20.0, 20.0) }.build()
                    inner.fillPath(mark, ctm, RgbColor.BLACK, false)
                }
            }
        }
    }

    /** A rasterizer; [throughCompose] makes it draw host text through Compose's text on the UI thread, as Android does. */
    private fun rasterizer(throughCompose: Boolean = false): KitePageRasterizer {
        val density = Density(1f)
        return KitePageRasterizer(density, LayoutDirection.Ltr,
            TextMeasurer(createFontFamilyResolver(), density, LayoutDirection.Ltr)).apply { textOffMain = !throughCompose }
    }

    private fun assertPaint(bitmap: ImageBitmap, left: Color) {
        val px = bitmap.toPixelMap()
        assertEquals(left, px[50, 100])
        assertEquals(Color.Blue, px[150, 100])
        assertEquals(Color.Black, px[20, 180], "extra mark must be interleaved with page paint")
    }

    @Test
    fun both_public_rasterizers_wrap_page_ink() = runBlocking {
        val page = document().pages[0]
        val renderer = rasterizer()
        val decorator = Ink(green)
        assertPaint(onTestUiThread { renderer.rasterize(page, 200, 200, canvasDecorator = decorator) }, Color.Green)
        assertPaint(renderer.rasterizeOffMain(page, 200, 200, canvasDecorator = decorator), Color.Green)
    }

    /** A page of one "H" without outlines, which draws through a host font; [says] is its hint. */
    private fun hostFontPage(says: Boolean?) = object : KitePage {
        override val displayWidth = 200.0
        override val displayHeight = 200.0
        override val drawsHostFontText: Boolean? = says
        override fun displayToDeviceBase() = KiteMatrix(1.0, 0.0, 0.0, -1.0, 0.0, 200.0)
        override fun renderTo(canvas: KiteCanvas, deviceCtm: KiteMatrix) {
            canvas.beginPage(200.0, 200.0, deviceCtm)
            canvas.drawGlyphs(
                listOf(TextGlyph(0, 1, -1, "H", 700.0, null, false)),
                40.0, 1000, false, FontSpec(KiteFontFamily.Serif, false, false),
                deviceCtm.concat(KiteMatrix.translation(20.0, 100.0)), RgbColor.BLACK,
            )
            canvas.endPage()
        }
    }

    @Test
    fun a_page_that_draws_host_font_text_renders_once() = runBlocking {
        val passes = AtomicInteger()
        val decorator: KiteCanvasDecorator = { inner -> passes.incrementAndGet(); inner }
        // On Android, a page that says so goes to the host-font pass at once (#131).
        val px = rasterizer(throughCompose = true).rasterizeOffMain(hostFontPage(says = true), 200, 200, canvasDecorator = decorator).toPixelMap()
        assertEquals(1, passes.get(), "the page was drawn more than once")
        var inked = 0
        for (y in 0 until 200) for (x in 0 until 200) if (px[x, y].red < 0.5f) inked++
        assertTrue(inked > 20, "the host-font text was not drawn")

        // A page that does not say so is probed once, and its next raster goes there at once.
        passes.set(0)
        val renderer = rasterizer(throughCompose = true)
        val page = hostFontPage(says = null)
        renderer.rasterizeOffMain(page, 200, 200, canvasDecorator = decorator)
        assertEquals(2, passes.get(), "the first raster probes and draws again")
        renderer.rasterizeOffMain(page, 300, 300, canvasDecorator = decorator)
        assertEquals(3, passes.get(), "the next raster of the page probed again")

        // On the desktop JVM, iOS and macOS, Skia shapes host text off the UI thread, so any page draws once (#131).
        passes.set(0)
        val skia = rasterizer()
        val drawn = skia.rasterizeOffMain(hostFontPage(says = null), 200, 200, canvasDecorator = decorator).toPixelMap()
        assertEquals(1, passes.get(), "a page with host-font text was drawn more than once")
        var skiaInked = 0
        for (y in 0 until 200) for (x in 0 until 200) if (drawn[x, y].red < 0.5f) skiaInked++
        assertTrue(skiaInked > 20, "the host-font text was not drawn")
    }

    @Test
    fun without_the_off_main_probe_a_page_draws_once() = runBlocking {
        // Where rasters run on the UI thread, as in a browser, the probe only doubles the work (#389).
        val passes = AtomicInteger()
        val decorator: KiteCanvasDecorator = { inner -> passes.incrementAndGet(); inner }
        val renderer = rasterizer().apply { probesOffMain = false }
        val px = renderer.rasterizeOffMain(hostFontPage(says = null), 200, 200, canvasDecorator = decorator).toPixelMap()
        assertEquals(1, passes.get(), "the page was drawn more than once")
        var inked = 0
        for (y in 0 until 200) for (x in 0 until 200) if (px[x, y].red < 0.5f) inked++
        assertTrue(inked > 20, "the host-font text was not drawn")
    }

    @Test
    fun off_main_system_font_retry_creates_a_fresh_wrapper() = runBlocking {
        val passes = AtomicInteger()
        val textCalls = AtomicInteger()
        val page = object : KitePage {
            override val displayWidth = 200.0
            override val displayHeight = 200.0
            override fun displayToDeviceBase() = KiteMatrix(1.0, 0.0, 0.0, -1.0, 0.0, 200.0)
            override fun renderTo(canvas: KiteCanvas, deviceCtm: KiteMatrix) {
                canvas.beginPage(200.0, 200.0, deviceCtm)
                canvas.drawGlyphs(
                    listOf(TextGlyph(0, 1, -1, "H", 700.0, null, false)),
                    40.0, 1000, false, FontSpec(KiteFontFamily.Serif, false, false),
                    deviceCtm.concat(KiteMatrix.translation(20.0, 100.0)), RgbColor.BLACK,
                )
                canvas.endPage()
            }
        }
        val decorator: KiteCanvasDecorator = { inner ->
            passes.incrementAndGet()
            object : KiteCanvas by inner {
                override fun drawGlyphs(glyphs: List<TextGlyph>, fontSize: Double, unitsPerEm: Int,
                    hasOutlines: Boolean, fontSpec: FontSpec, textToDevice: KiteMatrix,
                    color: RgbColor, alpha: Double, blendMode: KiteBlendMode) {
                    textCalls.incrementAndGet()
                    inner.drawGlyphs(glyphs, fontSize, unitsPerEm, hasOutlines, fontSpec,
                        textToDevice, green, alpha, blendMode)
                }
            }
        }
        val px = rasterizer(throughCompose = true).rasterizeOffMain(page, 200, 200, canvasDecorator = decorator).toPixelMap()
        assertEquals(2, passes.get(), "probe and system-font retry need independent wrappers")
        assertEquals(2, textCalls.get(), "both passes must traverse the decorator")
        var greenPixels = 0
        for (y in 0 until 200) for (x in 0 until 200) {
            if (px[x, y].green > 0.5f && px[x, y].red < 0.2f) greenPixels++
        }
        assertTrue(greenPixels > 20, "system-font retry must paint decorated text")
    }

    @Test
    fun wrapper_ink_flows_through_the_reader_theme() {
        val theme = ReaderTheme(RgbColor.WHITE) { color ->
            if (color == green) yellow else color
        }
        assertPaint(onTestUiThread { rasterizer().rasterize(document().pages[0], 200, 200,
            theme = theme, canvasDecorator = Ink(green)) }, Color.Yellow)
    }

    @Test
    fun cache_distinguishes_decorators_even_with_equal_hashes() = runBlocking {
        val page = document().pages[0]
        val renderer = rasterizer()
        val cache = PageBitmapCache(1_000_000)
        val firstInk = Ink(green)
        suspend fun render(ink: KiteCanvasDecorator?) = renderer.rasterizeCachedOffMain(
            cache, page, 200, 200, Color.White, 1f, null, canvasDecorator = ink)
        val first = render(firstInk)
        val second = render(firstInk)
        assertTrue(first.second)
        assertFalse(second.second)
        assertSame(first.first, second.first)
        val changed = render(Ink(yellow))
        assertTrue(changed.second)
        assertPaint(changed.first, Color.Yellow)
        val plain = render(null)
        assertTrue(plain.second)
        assertEquals(Color.Red, plain.first.toPixelMap()[50, 100])
    }

    @Test
    fun raster_viewer_repaints_when_decorator_changes_or_is_removed() = viewer(vector = false)

    @Test
    fun vector_viewer_repaints_when_decorator_changes_or_is_removed() = viewer(vector = true)

    private fun viewer(vector: Boolean) {
        val doc = document()
        val decorator = mutableStateOf<KiteCanvasDecorator?>(Ink(green))
        ImageComposeScene(width = 200, height = 200, density = Density(1f)) {
            KiteDocView(
                state = rememberKiteDocViewState(doc),
                modifier = Modifier.fillMaxSize(),
                renderSpec = if (vector) KiteRenderSpec.Vectorized(canvasDecorator = decorator.value)
                    else KiteRenderSpec.Rasterized(canvasDecorator = decorator.value),
            )
        }.use { scene ->
            val driver = SceneTestDriver(scene)
            fun waitFor(color: Color, decorated: Boolean) {
                val image = driver.pumpUntil { px -> px[50, 100] == color && px[150, 100] == Color.Blue }
                    .toComposeImageBitmap()
                if (decorated) assertPaint(image, color)
                else assertEquals(color, image.toPixelMap()[50, 100])
            }
            waitFor(Color.Green, true)
            decorator.value = Ink(yellow)
            waitFor(Color.Yellow, true)
            decorator.value = null
            waitFor(Color.Red, false)
        }
    }
}
