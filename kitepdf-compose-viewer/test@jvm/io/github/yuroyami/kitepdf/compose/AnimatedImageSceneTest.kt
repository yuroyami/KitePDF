package io.github.yuroyami.kitepdf.compose

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PixelMap
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.graphics.toPixelMap
import io.github.yuroyami.kitepdf.cbz.CbzDocument
import io.github.yuroyami.kitepdf.core.KiteDocument
import io.github.yuroyami.kitepdf.epub.EpubDocument
import io.github.yuroyami.kitepdf.epub.EpubSettings
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * An animated GIF in a chapter and an animated WebP comic page play in the viewer, frame after
 * frame and loop after loop, and show their first frame with animation off (#939).
 */
class AnimatedImageSceneTest {

    /** Two 8 by 8 frames of 100 ms, red then green, looping for ever, written by Pillow. */
    private val gif = Base64.getDecoder().decode("R0lGODlhCAAIAIEAAP8AAAAAAAAAAAAAACH/C05FVFNDQVBFMi4wAwEAAAAh+QQECgAAACwAAAAACAAIAAAIDwABCBxIsKDBgwgTKkwYEAAh+QQFCgABACwAAAAACAAIAIEAyAAAAAAAAAAAAAAIDwABCBxIsKDBgwgTKkwYEAA7")
    private val webp = Base64.getDecoder().decode("UklGRoQAAABXRUJQVlA4WAoAAAACAAAABwAABwAAQU5JTQYAAAAAAAAAAABBTk1GKAAAAAAAAAAAAAcAAAcAAGQAAAJWUDhMDwAAAC8HwAEABxD9j/4HIqL/AQBBTk1GKAAAAAAAAAAAAAcAAAcAAGQAAABWUDhMDwAAAC8HwAEAB1DkiP4HIqL/AQA=")

    private fun book(): EpubDocument {
        val container = """<?xml version="1.0"?><container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container"><rootfiles><rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/></rootfiles></container>"""
        val opf = """<?xml version="1.0"?><package xmlns="http://www.idpf.org/2007/opf" version="3.0" unique-identifier="id">
            <metadata xmlns:dc="http://purl.org/dc/elements/1.1/"><dc:identifier id="id">anim</dc:identifier></metadata>
            <manifest><item id="c1" href="c1.xhtml" media-type="application/xhtml+xml"/><item id="g" href="anim.gif" media-type="image/gif"/></manifest>
            <spine><itemref idref="c1"/></spine></package>"""
        val chapter = """<?xml version="1.0"?><html xmlns="http://www.w3.org/1999/xhtml"><body style="margin:0">""" +
            """<img src="anim.gif" alt="" style="display:block;width:200px;height:200px;image-rendering:pixelated"/></body></html>"""
        val bytes = storedZipOf(listOf(
            "mimetype" to "application/epub+zip".encodeToByteArray(),
            "META-INF/container.xml" to container.encodeToByteArray(),
            "OEBPS/content.opf" to opf.encodeToByteArray(),
            "OEBPS/c1.xhtml" to chapter.encodeToByteArray(),
            "OEBPS/anim.gif" to gif,
        ))
        return EpubDocument.open(bytes, EpubSettings(pageWidth = 200.0, pageHeight = 200.0, margin = 0.0))
    }

    private fun comic(): KiteDocument = CbzDocument.open(storedZipOf(listOf("001.webp" to webp)))

    private fun PixelMap.centre(state: KiteDocViewState): Color? {
        val rect = state.pageGeometry[0] ?: return null
        val pixels = this
        val x = rect.center.x.toInt()
        val y = rect.center.y.toInt()
        if (x !in 0 until pixels.width || y !in 0 until pixels.height) return null
        return pixels[x, y]
    }

    private fun Color.isRed() = red > 0.8f && green < 0.3f && blue < 0.3f
    private fun Color.isGreen() = green > 0.6f && red < 0.3f && blue < 0.3f

    private fun plays(document: KiteDocument, queued: Boolean) {
        lateinit var state: KiteDocViewState
        val (scene, driver) = drivenScene(200, 200, queued) {
            state = rememberKiteDocViewState(document)
            KiteDocView(state, Modifier.fillMaxSize(), layout = KiteDocLayout.Paged())
        }
        scene.use {
            state.animateImages = true
            // Frame 1, then frame 2 after its 100 ms, then frame 1 again as the loop starts over.
            driver.pumpUntil { pixels -> pixels.centre(state)?.isRed() == true }
            driver.pumpUntil { pixels -> pixels.centre(state)?.isGreen() == true }
            driver.pumpUntil { pixels -> pixels.centre(state)?.isRed() == true }
            driver.pumpUntil { pixels -> pixels.centre(state)?.isGreen() == true }
        }
    }

    private fun staysStill(document: KiteDocument, queued: Boolean) {
        lateinit var state: KiteDocViewState
        val (scene, driver) = drivenScene(200, 200, queued) {
            state = rememberKiteDocViewState(document)
            KiteDocView(state, Modifier.fillMaxSize(), layout = KiteDocLayout.Paged())
        }
        scene.use {
            state.animateImages = false
            driver.pumpUntil { pixels -> pixels.centre(state)?.isRed() == true }
            // Half a second of frames: the first frame stays.
            repeat(30) {
                val shown = driver.pumpFrames(1).toComposeImageBitmap().toPixelMap().centre(state)
                assertTrue(shown?.isRed() == true, "frame $it of a still viewer shows $shown")
            }
        }
    }

    @Test
    fun a_gif_in_a_chapter_plays_and_loops() = forBothEffectOrders { plays(book(), it) }

    @Test
    fun a_webp_comic_page_plays_and_loops() = forBothEffectOrders { plays(comic(), it) }

    @Test
    fun with_animation_off_both_show_their_first_frame() = forBothEffectOrders { queued ->
        staysStill(book(), queued)
        staysStill(comic(), queued)
    }
}
