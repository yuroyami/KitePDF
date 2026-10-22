package io.github.yuroyami.kitepdf.compose

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.PixelMap
import io.github.yuroyami.kitepdf.core.KiteDocument
import io.github.yuroyami.kitepdf.core.KitePage
import io.github.yuroyami.kitepdf.core.render.KiteBitmapCache
import io.github.yuroyami.kitepdf.core.render.KiteCanvas
import io.github.yuroyami.kitepdf.core.render.KiteImageData
import io.github.yuroyami.kitepdf.core.render.KiteImageIdentity
import io.github.yuroyami.kitepdf.core.render.KiteMatrix
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.util.concurrent.atomic.AtomicInteger
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Real vector redraws reuse converted images without keeping a retired viewer's cache (#371). */
class VectorImageCacheSceneTest {

    private class ImagePage(rgb: Int) : KitePage {
        val renders = AtomicInteger()
        private val identity = KiteImageIdentity()
        private val png = ByteArrayOutputStream().use { output ->
            val source = BufferedImage(256, 256, BufferedImage.TYPE_INT_RGB)
            for (y in 0 until 256) for (x in 0 until 256) source.setRGB(x, y, rgb)
            check(ImageIO.write(source, "png", output))
            output.toByteArray()
        }

        override val displayWidth: Double = 128.0
        override val displayHeight: Double = 128.0
        override val drawsHostFontText: Boolean = false
        override fun displayToDeviceBase(): KiteMatrix = KiteMatrix.IDENTITY

        override fun renderTo(canvas: KiteCanvas, deviceCtm: KiteMatrix) {
            renders.incrementAndGet()
            canvas.beginPage(displayWidth, displayHeight, deviceCtm)
            // A fresh decoded wrapper on every draw exercises identity reuse after source eviction.
            val image = checkNotNull(KiteImageData.fromEncodedImage(png)).withIdentity(identity)
            canvas.drawImage(image, deviceCtm.concat(KiteMatrix(128.0, 0.0, 0.0, -128.0, 0.0, 128.0)))
            canvas.endPage()
        }
    }

    private class Pages(override val pages: List<KitePage>) : KiteDocument {
        override val pageCount: Int get() = pages.size
    }

    private fun countConversions(conversions: AtomicInteger): KiteCanvasDecorator = { inner ->
        val base = inner as ComposeCanvas
        object : KiteCanvas by inner {
            override fun endPage() {
                inner.endPage()
                conversions.addAndGet(base.convertedImages)
            }
        }
    }

    private fun red(pixels: PixelMap): Boolean = pixels[64, 64].let {
        it.red > 0.8f && it.green < 0.2f && it.blue < 0.2f
    }

    private fun blue(pixels: PixelMap): Boolean = pixels[64, 64].let {
        it.blue > 0.8f && it.red < 0.2f && it.green < 0.2f
    }

    @Test
    fun real_redraws_and_recycled_page_slots_reuse_their_converted_images() = forBothEffectOrders { queued ->
        val first = ImagePage(0xFF0000)
        val second = ImagePage(0x0000FF)
        val state = KiteDocViewState(Pages(listOf(first, second)))
        val conversions = AtomicInteger()
        val decorator = countConversions(conversions)
        var hairline by mutableStateOf(1f)
        var pageIndex by mutableStateOf(0)
        val (scene, driver) = drivenScene(128, 128, queued) {
            KiteDocView(
                state, Modifier.fillMaxSize(), layout = KiteDocLayout.SinglePage(pageIndex), colors = KiteDocViewColors(theme = null),
                renderSpec = KiteRenderSpec.Vectorized(hairline, decorator, CACHE_BYTES),
            )
        }
        scene.use {
            driver.pumpUntil { red(it) && conversions.get() == 1 }
            val drawn = first.renders.get()
            onTestUiThread { hairline = 2f }
            driver.pumpUntil { red(it) && first.renders.get() > drawn }
            assertEquals(1, conversions.get(), "a real spec redraw converted the source again")

            onTestUiThread { pageIndex = 1 }
            driver.pumpUntil { blue(it) && second.renders.get() > 0 }
            assertEquals(2, conversions.get(), "the second image needs its own bitmap")
            val beforeReturn = first.renders.get()
            onTestUiThread { pageIndex = 0 }
            driver.pumpUntil { red(it) && first.renders.get() > beforeReturn }
            assertEquals(2, conversions.get(), "recreating the first slot lost its converted bitmap")
        }
    }

    @Test
    fun settled_zoom_uses_a_new_sampling_size_then_reuses_the_previous_one() = forBothEffectOrders { queued ->
        val page = ImagePage(0xFF0000)
        val state = KiteDocViewState(Pages(listOf(page)))
        val conversions = AtomicInteger()
        val spec = KiteRenderSpec.Vectorized(canvasDecorator = countConversions(conversions), imageCacheBudgetBytes = CACHE_BYTES)
        val (scene, driver) = drivenScene(128, 128, queued) {
            KiteDocView(state, Modifier.fillMaxSize(), layout = KiteDocLayout.SinglePage(0), colors = KiteDocViewColors(theme = null), renderSpec = spec)
        }
        scene.use {
            driver.pumpUntil { red(it) && conversions.get() == 1 }
            val beforeZoom = page.renders.get()
            onTestUiThread { state.setZoom(4f) }
            driver.pumpUntil { red(it) && page.renders.get() > beforeZoom && conversions.get() >= 2 }
            assertEquals(2, conversions.get(), "the larger sampling size should be converted once")
            val zoomed = page.renders.get()
            onTestUiThread { state.setZoom(1f) }
            driver.pumpUntil { red(it) && page.renders.get() > zoomed }
            // A live zoom transform can invalidate before the 220 ms settled redraw. Keep
            // pumping past that delay so this assertion also covers the final sampling size.
            driver.pumpFrames(80)
            assertEquals(2, conversions.get(), "the earlier sampling size should remain resident")
        }
    }

    @Test
    fun zero_and_too_small_budgets_reconvert_each_actual_draw() = forBothEffectOrders { queued ->
        for (budget in listOf(0L, 1L)) {
            val page = ImagePage(0xFF0000)
            val state = KiteDocViewState(Pages(listOf(page)))
            val conversions = AtomicInteger()
            val decorator = countConversions(conversions)
            var hairline by mutableStateOf(1f)
            val (scene, driver) = drivenScene(128, 128, queued) {
                KiteDocView(
                    state, Modifier.fillMaxSize(), layout = KiteDocLayout.SinglePage(0), colors = KiteDocViewColors(theme = null),
                    renderSpec = KiteRenderSpec.Vectorized(hairline, decorator, budget),
                )
            }
            scene.use {
                driver.pumpUntil { red(it) && conversions.get() > 0 }
                val draws = page.renders.get()
                val converted = conversions.get()
                onTestUiThread { hairline = 2f }
                driver.pumpUntil { red(it) && page.renders.get() > draws }
                assertTrue(conversions.get() > converted, "budget $budget unexpectedly retained the bitmap")
                assertEquals(page.renders.get() - draws, conversions.get() - converted)
                if (budget > 0L) {
                    val cache = onTestUiThread { checkNotNull(state.vectorImageCacheFor(budget)) }
                    assertEquals(0L, cache.heldBytes, "an oversized image must not enter the cache")
                }
            }
        }
    }

    @Test
    fun mode_document_and_viewer_lifetime_changes_release_cached_bitmaps() = forBothEffectOrders { queued ->
        val firstState = KiteDocViewState(Pages(listOf(ImagePage(0xFF0000))))
        val secondState = KiteDocViewState(Pages(listOf(ImagePage(0x0000FF))))
        var state by mutableStateOf(firstState)
        val conversions = AtomicInteger()
        val vector = KiteRenderSpec.Vectorized(canvasDecorator = countConversions(conversions), imageCacheBudgetBytes = CACHE_BYTES)
        var spec by mutableStateOf<KiteRenderSpec>(vector)
        var visible by mutableStateOf(true)
        val (scene, driver) = drivenScene(128, 128, queued) {
            if (visible) {
                KiteDocView(state, Modifier.fillMaxSize(), layout = KiteDocLayout.SinglePage(0), colors = KiteDocViewColors(theme = null), renderSpec = spec)
            }
        }
        lateinit var finalCache: KiteBitmapCache<ImageBitmap>
        scene.use {
            driver.pumpUntil { red(it) && conversions.get() > 0 }
            val initialCache = onTestUiThread { checkNotNull(firstState.vectorImageCacheFor(CACHE_BYTES)) }
            assertTrue(initialCache.heldBytes > 0L)
            onTestUiThread { spec = KiteRenderSpec.Rasterized() }
            driver.pumpUntil { red(it) && initialCache.heldBytes == 0L }

            val beforeVector = conversions.get()
            onTestUiThread { spec = vector }
            driver.pumpUntil { red(it) && conversions.get() > beforeVector }
            val oldDocumentCache = onTestUiThread { checkNotNull(firstState.vectorImageCacheFor(CACHE_BYTES)) }
            assertTrue(oldDocumentCache.heldBytes > 0L)
            onTestUiThread { state = secondState }
            driver.pumpUntil { blue(it) && oldDocumentCache.heldBytes == 0L }

            val detachedCache = onTestUiThread { checkNotNull(secondState.vectorImageCacheFor(CACHE_BYTES)) }
            assertTrue(detachedCache.heldBytes > 0L)
            onTestUiThread { visible = false }
            driver.pumpUntilState { detachedCache.heldBytes == 0L }
            val beforeReattach = conversions.get()
            onTestUiThread { visible = true }
            driver.pumpUntil { blue(it) && conversions.get() > beforeReattach }
            finalCache = onTestUiThread { checkNotNull(secondState.vectorImageCacheFor(CACHE_BYTES)) }
            assertTrue(finalCache.heldBytes > 0L)
        }
        assertEquals(0L, finalCache.heldBytes, "closing the scene must release its converted images")
    }

    private companion object {
        const val CACHE_BYTES = 2L * 1024 * 1024
    }
}
