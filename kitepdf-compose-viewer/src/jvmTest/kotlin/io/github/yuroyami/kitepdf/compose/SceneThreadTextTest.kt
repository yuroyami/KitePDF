package io.github.yuroyami.kitepdf.compose

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.graphics.toPixelMap
import io.github.yuroyami.kitepdf.epub.EpubDocument
import io.github.yuroyami.kitepdf.epub.EpubSettings
import java.awt.EventQueue
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.jetbrains.skia.Image

/**
 * A Vectorized page draws its system-font text in a desktop scene that draws on a thread of its
 * own, as a test, a screenshot tool or a server does, not only on the AWT event dispatch thread
 * (#464). The page draws inside the scene's draw pass, where Compose draws the scene's own text.
 * The other scene tests draw on the AWT thread, so this one makes the plain scene on its own.
 */
class SceneThreadTextTest {

    private val book = multiSpineEpub(listOf("<p style='font-size:40px'>WWWWWWWW WWWWWWWW WWWWWWWW</p>"))

    private val content: @androidx.compose.runtime.Composable () -> Unit = {
        val doc = androidx.compose.runtime.remember {
            EpubDocument.open(book, EpubSettings(pageWidth = 200.0, pageHeight = 200.0))
        }
        KiteDocView(
            state = rememberKiteDocViewState(doc),
            modifier = Modifier.fillMaxSize(),
            renderSpec = KiteRenderSpec.Vectorized(),
        )
    }

    /** The dark pixels of the page once they stop changing, as text, the only dark paint, settles. */
    private fun settledDarkPixels(render: (Long) -> Image): Int {
        val deadline = System.currentTimeMillis() + 10_000
        var time = 0L
        var last = -1
        var steady = 0
        while (System.currentTimeMillis() < deadline) {
            val pixels = render(time).toComposeImageBitmap().toPixelMap()
            var dark = 0
            for (y in 0 until 200) for (x in 0 until 200) if (pixels[x, y].red < 0.5f) dark++
            steady = if (dark == last && dark > 0) steady + 1 else 0
            if (steady == 10) return dark
            last = dark
            time += 16_000_000L
            Thread.sleep(4)
        }
        return last
    }

    @Test
    fun a_vectorized_page_draws_its_text_in_a_scene_off_the_awt_thread() {
        assertFalse(EventQueue.isDispatchThread(), "the test thread must not be the AWT thread")
        val offThread = androidx.compose.ui.ImageComposeScene(200, 200, content = content)
        val dark = try {
            settledDarkPixels(offThread::render)
        } finally {
            offThread.close()
        }
        val onAwt = EdtImageComposeScene(200, 200, content = content)
        val awtDark = try {
            settledDarkPixels(onAwt::render)
        } finally {
            onAwt.close()
        }
        println("dark pixels of the page: $dark off the AWT thread, $awtDark on it")
        assertTrue(awtDark > 1_000, "the page draws its text on the AWT thread: $awtDark dark pixels")
        assertTrue(dark == awtDark, "the page off the AWT thread draws $dark dark pixels, on it $awtDark")
    }
}
