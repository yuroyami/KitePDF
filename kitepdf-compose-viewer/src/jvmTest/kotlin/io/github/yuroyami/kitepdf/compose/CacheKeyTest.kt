package io.github.yuroyami.kitepdf.compose

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.font.createFontFamilyResolver
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import io.github.yuroyami.kitepdf.PdfDocument
import io.github.yuroyami.kitepdf.core.render.ReaderTheme
import io.github.yuroyami.kitepdf.core.render.RgbColor
import io.github.yuroyami.kitepdf.writer.PdfBuilder
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * The page bitmap cache keys what changes the pixels, and only that: a theme by its value
 * (#420), the paper by the colour the page is drawn on (#394), and a cache the host turns off
 * lets go of what it held (#395).
 */
class CacheKeyTest {

    private fun redPage() = PdfDocument.open(
        PdfBuilder().page(width = 100.0, height = 100.0) {
            setFillRgb(1.0, 0.0, 0.0); rectangle(0.0, 0.0, 100.0, 100.0); fill()
        }.build(),
    ).pages[0]

    private fun rasterizer(): KitePageRasterizer {
        val density = Density(1f)
        return KitePageRasterizer(density, LayoutDirection.Ltr, TextMeasurer(createFontFamilyResolver(), density, LayoutDirection.Ltr))
    }

    /** Paints every colour [ink], and hashes like every other [Ink], so two of them collide. */
    private class Ink(private val ink: RgbColor) : (RgbColor) -> RgbColor {
        override fun invoke(color: RgbColor): RgbColor = ink
        override fun hashCode(): Int = 13
        override fun equals(other: Any?): Boolean = other is Ink && other.ink == ink
    }

    @Test
    fun two_themes_with_equal_hashes_do_not_share_pixels() = runBlocking {
        val green = ReaderTheme(RgbColor.WHITE, Ink(RgbColor(0.0, 1.0, 0.0)))
        val blue = ReaderTheme(RgbColor.WHITE, Ink(RgbColor(0.0, 0.0, 1.0)))
        assertEquals(green.hashCode(), blue.hashCode())
        val cache = PageBitmapCache(maxBytes = 10L * 1024 * 1024)
        val page = redPage()
        val renderer = rasterizer()
        renderer.rasterizeCachedOffMain(cache, page, 100, 100, Color.White, 1f, green)
        val (second, fresh) = renderer.rasterizeCachedOffMain(cache, page, 100, 100, Color.White, 1f, blue)
        assertTrue(fresh, "the blue theme was served the green theme's pixels")
        assertEquals(Color.Blue, second.toPixelMap()[50, 50])
    }

    @Test
    fun a_background_that_a_theme_hides_is_a_cache_hit() = runBlocking {
        val cache = PageBitmapCache(maxBytes = 10L * 1024 * 1024)
        val page = redPage()
        val renderer = rasterizer()
        val (first, _) = renderer.rasterizeCachedOffMain(cache, page, 100, 100, Color.White, 1f, ReaderTheme.Sepia)
        val (second, fresh) = renderer.rasterizeCachedOffMain(cache, page, 100, 100, Color.Blue, 1f, ReaderTheme.Sepia)
        assertFalse(fresh, "a page that looks the same was rendered again")
        assertSame(first, second)
        assertEquals(1, cache.size)
    }

    @Test
    fun a_cache_turned_off_lets_go_of_its_bitmaps() {
        val state = KiteDocViewState(PdfDocument.open(PdfBuilder().page(width = 100.0, height = 100.0) {}.build()))
        val first = state.bitmapCacheFor(100_000)
        assertSame(first, state.bitmapCacheFor(100_000))
        for (off in listOf(0L, -1L)) {
            assertNull(state.bitmapCacheFor(off))
            val again = state.bitmapCacheFor(100_000)
            assertNotSame(first, again, "the cache survived a budget of $off")
        }
    }

    @Test
    fun a_switch_to_vectorized_lets_go_of_the_bitmaps() {
        forBothEffectOrders { queued ->
            val doc = PdfDocument.open(PdfBuilder().page(width = 100.0, height = 100.0) {}.build())
            lateinit var state: KiteDocViewState
            var spec by mutableStateOf<KiteRenderSpec>(KiteRenderSpec.Rasterized())
            val (scene, driver) = drivenScene(100, 100, queued) {
                state = rememberKiteDocViewState(doc)
                KiteDocView(state = state, modifier = Modifier.fillMaxSize(), renderSpec = spec)
            }
            scene.use {
                driver.pumpUntilState { state.pageGeometry.isNotEmpty() }
                driver.pumpFrames(5)
                val budget = KiteRenderSpec.Rasterized().cacheBudgetBytes
                val held = state.bitmapCacheFor(budget)
                spec = KiteRenderSpec.Vectorized()
                driver.pumpFrames(5)
                assertNotSame(held, state.bitmapCacheFor(budget), "Vectorized mode kept the page bitmaps")
            }
        }
    }
}
