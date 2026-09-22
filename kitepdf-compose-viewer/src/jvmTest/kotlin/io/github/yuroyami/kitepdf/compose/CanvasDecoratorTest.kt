package io.github.yuroyami.kitepdf.compose

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.font.createFontFamilyResolver
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.use
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

    private fun rasterizer(): KitePageRasterizer {
        val density = Density(1f)
        return KitePageRasterizer(density, LayoutDirection.Ltr,
            TextMeasurer(createFontFamilyResolver(), density, LayoutDirection.Ltr))
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
        assertPaint(renderer.rasterize(page, 200, 200, canvasDecorator = decorator), Color.Green)
        assertPaint(renderer.rasterizeOffMain(page, 200, 200, canvasDecorator = decorator), Color.Green)
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
        val px = rasterizer().rasterizeOffMain(page, 200, 200, canvasDecorator = decorator).toPixelMap()
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
        assertPaint(rasterizer().rasterize(document().pages[0], 200, 200,
            theme = theme, canvasDecorator = Ink(green)), Color.Yellow)
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
