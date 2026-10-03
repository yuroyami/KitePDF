package io.github.yuroyami.kitepdf.compose

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.PixelMap
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.graphics.toPixelMap
import io.github.yuroyami.kitepdf.epub.EpubDocument
import io.github.yuroyami.kitepdf.epub.EpubResourceFetcher
import io.github.yuroyami.kitepdf.epub.EpubSettings
import kotlinx.coroutines.CompletableDeferred
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * A picture that a book names by an https URL shows in the viewer: one whose markup gives its
 * size appears in its box once its bytes land, and the viewer waits a little for one whose bytes
 * size the layout (#38).
 */
class RemoteResourceSceneTest {

    private val url = "https://images.example.com/red.bmp"

    /** A 2x1 BMP of two red pixels. */
    private val red: ByteArray = run {
        val h = ByteArray(54)
        h[0] = 'B'.code.toByte(); h[1] = 'M'.code.toByte()
        fun le32(o: Int, v: Int) { var s = 0; var i = o; while (s < 32) { h[i++] = ((v ushr s) and 0xFF).toByte(); s += 8 } }
        fun le16(o: Int, v: Int) { h[o] = (v and 0xFF).toByte(); h[o + 1] = ((v ushr 8) and 0xFF).toByte() }
        le32(2, 62); le32(10, 54); le32(14, 40); le32(18, 2); le32(22, 1)
        le16(26, 1); le16(28, 24); le32(34, 8)
        h + byteArrayOf(0, 0, 0xFF.toByte(), 0, 0, 0xFF.toByte(), 0, 0)
    }

    private fun redPixels(pixels: PixelMap): Int = (0 until pixels.height step 2).sumOf { y ->
        (0 until pixels.width step 2).count { x ->
            val c = pixels[x, y]
            c.red > 0.8f && c.green < 0.2f && c.blue < 0.2f
        }
    }

    /** The pixels that text darkens: a thin face at this size draws gray, not black. */
    private fun inkPixels(pixels: PixelMap): Int = (0 until pixels.height).sumOf { y ->
        (0 until pixels.width).count { x ->
            val c = pixels[x, y]
            c.red < 0.6f && c.green < 0.6f && c.blue < 0.6f
        }
    }

    private fun book(body: String, fetcher: EpubResourceFetcher) = EpubDocument.open(
        multiSpineEpub(listOf(body)),
        EpubSettings(pageWidth = 200.0, pageHeight = 200.0, resourceFetcher = fetcher),
    )

    @Test
    fun a_picture_with_a_declared_size_appears_in_its_box_when_its_bytes_land() {
        for (spec in listOf(KiteRenderSpec.Rasterized(), KiteRenderSpec.Vectorized())) forBothEffectOrders { queued ->
            withoutEscapes {
                val gate = CompletableDeferred<Unit>()
                releaseAtEnd { gate.complete(Unit) }
                val doc = book(
                    """<p>Before the picture.</p><p><img src="$url" width="120" height="80" alt="Red"/></p>""",
                    { gate.await(); red },
                )
                val (scene, driver) = drivenScene(200, 200, queued) {
                    KiteDocView(state = rememberKiteDocViewState(doc), modifier = Modifier.fillMaxSize(), renderSpec = spec)
                }
                scene.use {
                    val before = driver.pumpUntil { inkPixels(it) > 20 }.toComposeImageBitmap().toPixelMap()
                    assertEquals(0, redPixels(before), "$spec: nothing to show before the bytes land")
                    gate.complete(Unit)
                    driver.pumpUntil { redPixels(it) > 400 }
                }
            }
        }
    }

    @Test
    fun the_viewer_waits_for_a_picture_that_sizes_the_layout() = forBothEffectOrders { queued ->
        withoutEscapes {
            val doc = book("""<p>Before the picture.</p><p><img src="$url" style="width:120px" alt="Red"/></p>""", { red })
            val (scene, driver) = drivenScene(200, 200, queued) {
                KiteDocView(state = rememberKiteDocViewState(doc), modifier = Modifier.fillMaxSize(), renderSpec = KiteRenderSpec.Rasterized())
            }
            scene.use { driver.pumpUntil { redPixels(it) > 400 } }
        }
    }

    @Test
    fun a_picture_that_never_lands_delays_the_chapter_only_so_long() = forBothEffectOrders { queued ->
        withoutEscapes {
            val gate = CompletableDeferred<Unit>()
            releaseAtEnd { gate.complete(Unit) }
            val asked = AtomicInteger()
            val doc = book(
                """<p>Before the picture.</p><p><img src="$url" alt="Red"/></p>""",
                { asked.incrementAndGet(); gate.await(); red },
            )
            lateinit var state: KiteDocViewState
            val (scene, driver) = drivenScene(200, 200, queued) {
                state = rememberKiteDocViewState(doc).also { it.remoteWait = 200.milliseconds }
                KiteDocView(state = state, modifier = Modifier.fillMaxSize(), renderSpec = KiteRenderSpec.Rasterized())
            }
            scene.use {
                driver.pumpUntilState { doc.isChapterReady(0) }
                driver.pumpUntil { inkPixels(it) > 20 }
                assertEquals(1, asked.get(), "one fetch")
                gate.complete(Unit)
                driver.pumpUntilState { doc.remoteArrivals.value == 1 }
                val after = driver.pumpFrames(10).toComposeImageBitmap().toPixelMap()
                assertEquals(0, redPixels(after), "the chapter keeps the layout it made without the picture")
            }
        }
    }

    @Test
    fun a_picture_that_never_lands_delays_only_the_first_chapter_that_needs_it() = forBothEffectOrders { queued ->
        withoutEscapes {
            val gate = CompletableDeferred<Unit>()
            releaseAtEnd { gate.complete(Unit) }
            val chapters = 12
            val doc = EpubDocument.open(
                multiSpineEpub(List(chapters) { """<p>Chapter $it.</p><p><img src="$url" alt="Red"/></p>""" }),
                EpubSettings(pageWidth = 200.0, pageHeight = 200.0, resourceFetcher = { gate.await(); red }),
            )
            val (scene, driver) = drivenScene(200, 200, queued) {
                val state = rememberKiteDocViewState(doc).also { it.remoteWait = 1.seconds }
                KiteDocView(state = state, modifier = Modifier.fillMaxSize(), renderSpec = KiteRenderSpec.Rasterized())
            }
            scene.use {
                driver.pumpUntilState { doc.isChapterReady(0) }
                // Each chapter after the first waited its own second for the same URL, eleven in all (#492).
                driver.pumpUntilState(timeoutMs = 8_000) { (0 until chapters).all { doc.isChapterReady(it) } }
            }
        }
    }
}
