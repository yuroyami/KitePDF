package io.github.yuroyami.kitepdf.compose

import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.font.createFontFamilyResolver
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import io.github.yuroyami.kitepdf.core.KitePage
import io.github.yuroyami.kitepdf.core.font.FontSpec
import io.github.yuroyami.kitepdf.core.font.KiteFontFamily
import io.github.yuroyami.kitepdf.core.font.TextGlyph
import io.github.yuroyami.kitepdf.core.render.KiteCanvas
import io.github.yuroyami.kitepdf.core.render.KiteMatrix
import io.github.yuroyami.kitepdf.core.render.RgbColor
import java.util.concurrent.atomic.AtomicInteger
import android.os.Looper
import io.github.yuroyami.kitepdf.core.render.KiteBlendMode
import java.time.Duration
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Android draws host-font text through its own text stack on any thread, with the paint Compose's
 * text gives it, so a page with such text rasters once off the main thread and looks the same
 * (#487). Robolectric's native graphics draw both paths with Android's real Skia and fonts, on API
 * 26, where Compose picks a face by its style, and on API 35, where it picks one by its weight.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [26, 35])
class AndroidHostTextTest {

    private val density = Density(1f)

    private fun measurer() = TextMeasurer(createFontFamilyResolver(RuntimeEnvironment.getApplication()), density, LayoutDirection.Ltr)

    private fun pixels(bitmap: ImageBitmap): IntArray {
        val map = bitmap.toPixelMap()
        return IntArray(map.width * map.height) { map[it % map.width, it / map.width].hashCode() }
    }

    @Test
    fun android_text_draws_the_pixels_compose_text_drew() {
        // Latin and accented text, kana, Han, Greek and Cyrillic, at sizes whose baselines round either way.
        val text = "Hello, world. Quick fox été い 中文 Αβ Ж"
        val glyphs = text.map { TextGlyph(0, 1, -1, it.toString(), 560.0, null, false) }
        val measurer = measurer()
        fun ink(spec: FontSpec, size: Double, throughCompose: Boolean): IntArray {
            val bitmap = ImageBitmap(600, 100)
            CanvasDrawScope().draw(density, LayoutDirection.Ltr, Canvas(bitmap), Size(600f, 100f)) {
                drawRect(Color.White)
                ComposeCanvas(this, measurer, 1f, false, magnification = 1f, hostLines = !throughCompose)
                    .drawGlyphs(glyphs, size, 1000, false, spec, KiteMatrix(1.0, 0.0, 0.0, -1.0, 10.3, 70.6), RgbColor.BLACK)
            }
            return pixels(bitmap)
        }
        // On API 26 the first Han text of a Japanese spec in a process placed its second glyph a fraction of a
        // pixel apart on the two paths, and every draw after it matched, so both paths draw the text once first.
        for (throughCompose in listOf(true, false)) ink(FontSpec(KiteFontFamily.SansSerif, false, false, language = "ja"), 9.5, throughCompose)
        val blank = pixels(ImageBitmap(600, 100).also { CanvasDrawScope().draw(density, LayoutDirection.Ltr, Canvas(it), Size(600f, 100f)) { drawRect(Color.White) } })
        for (size in listOf(7.0, 9.5, 15.2, 23.3)) for (family in KiteFontFamily.entries) for (bold in listOf(false, true)) for (italic in listOf(false, true)) {
            for (language in listOf(null, "ja")) {
                val spec = FontSpec(family, bold, italic, language = language)
                val android = ink(spec, size, throughCompose = false)
                assertTrue(!android.contentEquals(blank), "$spec at $size drew nothing")
                assertContentEquals(ink(spec, size, throughCompose = true), android, "$spec at $size")
            }
        }
    }

    @Test
    fun translucent_text_in_another_blend_mode_draws_as_compose_text_did() {
        val glyphs = "Multiply".map { TextGlyph(0, 1, -1, it.toString(), 600.0, null, false) }
        val measurer = measurer()
        fun ink(throughCompose: Boolean): IntArray {
            val bitmap = ImageBitmap(300, 80)
            CanvasDrawScope().draw(density, LayoutDirection.Ltr, Canvas(bitmap), Size(300f, 80f)) {
                drawRect(Color(0xFF80C0FF))
                ComposeCanvas(this, measurer, 1f, false, magnification = 1f, hostLines = !throughCompose).drawGlyphs(
                    glyphs, 30.0, 1000, false, FontSpec(KiteFontFamily.SansSerif, true, false),
                    KiteMatrix(1.0, 0.0, 0.0, -1.0, 10.0, 55.0), RgbColor(0.9, 0.2, 0.1), 0.6, KiteBlendMode.Multiply,
                )
            }
            return pixels(bitmap)
        }
        val android = ink(throughCompose = false)
        assertTrue(android.toSet().size > 2, "the text drew nothing")
        assertContentEquals(ink(throughCompose = true), android)
    }

    @Test
    fun a_page_with_host_text_rasters_once_off_the_main_thread() {
        val draws = AtomicInteger()
        val page = object : KitePage {
            override val displayWidth = 200.0
            override val displayHeight = 100.0
            override fun displayToDeviceBase() = KiteMatrix(1.0, 0.0, 0.0, -1.0, 0.0, 100.0)
            override fun renderTo(canvas: KiteCanvas, deviceCtm: KiteMatrix) {
                draws.incrementAndGet()
                canvas.beginPage(200.0, 100.0, deviceCtm)
                val glyphs = "Hello".map { TextGlyph(0, 1, -1, it.toString(), 600.0, null, false) }
                canvas.drawGlyphs(glyphs, 30.0, 1000, false, FontSpec(KiteFontFamily.Serif, false, false), deviceCtm.concat(KiteMatrix.translation(10.0, 30.0)), RgbColor.BLACK)
                canvas.endPage()
            }
        }
        val renderer = KitePageRasterizer(density, LayoutDirection.Ltr, measurer())
        val mainThread = Thread.currentThread()
        val drawThreads = java.util.Collections.synchronizedSet(HashSet<Thread>())
        val raster = CoroutineScope(Dispatchers.Default).async {
            renderer.rasterizeOffMain(page, 200, 100, canvasDecorator = { inner ->
                object : KiteCanvas by inner {
                    override fun drawGlyphs(
                        glyphs: List<TextGlyph>, fontSize: Double, unitsPerEm: Int, hasOutlines: Boolean,
                        fontSpec: FontSpec, textToDevice: KiteMatrix, color: RgbColor, alpha: Double, blendMode: KiteBlendMode,
                    ) {
                        drawThreads += Thread.currentThread()
                        inner.drawGlyphs(glyphs, fontSize, unitsPerEm, hasOutlines, fontSpec, textToDevice, color, alpha, blendMode)
                    }
                }
            })
        }
        // The main looper runs only when this test runs it, so a raster that went to the main thread finishes too, and fails below.
        val deadline = System.nanoTime() + 20_000_000_000L
        while (!raster.isCompleted && System.nanoTime() < deadline) shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(5))
        assertTrue(raster.isCompleted, "the raster did not finish")
        val bitmap = runBlocking { raster.await() }
        assertEquals(1, draws.get(), "the page drew more than once")
        assertTrue(mainThread !in drawThreads && drawThreads.isNotEmpty(), "the text drew on the main thread")
        val map = bitmap.toPixelMap()
        var dark = 0
        for (y in 0 until 100) for (x in 0 until 200) if (map[x, y].red < 0.5f) dark++
        assertTrue(dark > 50, "the raster has no text: $dark dark pixels")
    }
}
