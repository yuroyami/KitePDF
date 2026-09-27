package io.github.yuroyami.kitepdf.compose

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.use
import io.github.yuroyami.kitepdf.core.KiteDocument
import io.github.yuroyami.kitepdf.core.KitePage
import io.github.yuroyami.kitepdf.core.KiteRectangle
import io.github.yuroyami.kitepdf.core.KiteStructuredText
import io.github.yuroyami.kitepdf.core.KiteTextBlock
import io.github.yuroyami.kitepdf.core.KiteTextLine
import io.github.yuroyami.kitepdf.core.render.KiteCanvas
import io.github.yuroyami.kitepdf.core.render.KiteMatrix
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotSame

/**
 * The text of the page the reader rests on is built off the main thread, so a long press on a
 * dense page does not build it on the main thread first (#380).
 */
class TextPrefetchSceneTest {

    /** A page that keeps its text once built, as a PDF page does, and records the thread that built it. */
    private class TextPage : KitePage {
        @Volatile var builtOn: Thread? = null
        override val displayWidth: Double = 100.0
        override val displayHeight: Double = 100.0
        override fun displayToDeviceBase(): KiteMatrix = KiteMatrix.IDENTITY
        override fun renderTo(canvas: KiteCanvas, deviceCtm: KiteMatrix) {}
        private val text: KiteStructuredText by lazy {
            builtOn = Thread.currentThread()
            val line = KiteTextLine("abcd", KiteRectangle(20.0, 5.0, 60.0, 15.0), doubleArrayOf(20.0, 30.0, 40.0, 50.0, 60.0))
            KiteStructuredText(listOf(KiteTextBlock(listOf(line))))
        }
        override fun textContent(): KiteStructuredText = text
    }

    private class OnePage(page: KitePage) : KiteDocument {
        override val pageCount: Int = 1
        override val pages: List<KitePage> = listOf(page)
    }

    @Test
    fun the_text_of_the_page_in_view_is_built_off_the_main_thread() {
        forBothEffectOrders { queued ->
            val page = TextPage()
            val state = KiteDocViewState(OnePage(page))
            val (scene, driver) = drivenScene(100, 100, queued) {
                KiteDocView(state = state, modifier = Modifier.fillMaxSize(), layout = KiteDocLayout.SinglePage(0))
            }
            scene.use {
                val main = Thread.currentThread()
                driver.pumpUntilState { page.builtOn != null }
                assertNotSame(main, page.builtOn, "the page's text was built on the main thread")
                // The long press then finds it ready.
                state.beginSelection(Offset(25f, 10f))
                state.extendSelection(Offset(55f, 10f))
                assertEquals("abcd", state.selection?.text)
            }
        }
    }
}
