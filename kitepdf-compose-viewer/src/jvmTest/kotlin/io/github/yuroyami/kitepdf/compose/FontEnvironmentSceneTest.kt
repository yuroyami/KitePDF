package io.github.yuroyami.kitepdf.compose

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFontFamilyResolver
import androidx.compose.ui.text.font.createFontFamilyResolver
import androidx.compose.ui.use
import io.github.yuroyami.kitepdf.PdfDocument
import io.github.yuroyami.kitepdf.writer.PdfBuilder
import io.github.yuroyami.kitepdf.writer.StandardFont
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals

/** A new font environment renders the page again, so its text follows the new fonts (#421). */
class FontEnvironmentSceneTest {

    @Test
    fun a_new_font_environment_renders_the_page_again() {
        forBothEffectOrders { queued ->
            val doc = PdfDocument.open(
                PdfBuilder().page(width = 200.0, height = 200.0) {
                    text(StandardFont.Helvetica, 12.0, 20.0, 150.0, "hello world")
                }.build(),
            )
            val renders = AtomicInteger()
            var resolver by mutableStateOf(createFontFamilyResolver())
            val (scene, driver) = drivenScene(200, 200, queued) {
                CompositionLocalProvider(LocalFontFamilyResolver provides resolver) {
                    KiteDocView(
                        state = rememberKiteDocViewState(doc),
                        modifier = Modifier.fillMaxSize(),
                        layout = KiteDocLayout.SinglePage(0),
                        onPageRendered = { _, _ -> renders.incrementAndGet() },
                    )
                }
            }
            scene.use {
                driver.pumpUntilState { renders.get() == 1 }
                driver.pumpFrames(20)
                assertEquals(1, renders.get(), "the page rendered again with nothing changed")
                resolver = createFontFamilyResolver()
                driver.pumpUntilState { renders.get() == 2 }
            }
        }
    }
}
