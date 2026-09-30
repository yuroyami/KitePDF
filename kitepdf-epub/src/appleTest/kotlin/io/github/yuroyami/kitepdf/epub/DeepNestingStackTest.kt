package io.github.yuroyami.kitepdf.epub

import io.github.yuroyami.kitepdf.core.KiteLocation
import io.github.yuroyami.kitepdf.core.render.RecordingCanvas
import kotlin.native.concurrent.ObsoleteWorkersApi
import kotlin.native.concurrent.TransferMode
import kotlin.native.concurrent.Worker
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The depth limit of [HtmlParser] against the stack that it protects. A secondary thread of an
 * Apple platform has a stack of 512 KB, and a [Worker] is such a thread. Kotlin/Native cannot
 * catch a stack overflow, so a limit that is too high ends this test run with a signal (#450).
 */
class DeepNestingStackTest {

    @OptIn(ObsoleteWorkersApi::class)
    @Test
    fun the_heaviest_layout_at_the_depth_limit_fits_the_stack_of_a_secondary_thread() {
        // Flex and grid containers take the most stack for each level of all the layouts.
        val open = """<div style="display:flex"><div style="display:grid">"""
        val body = open.repeat(500) + "deep words" + "</div></div>".repeat(500) + "<p>After.</p>"
        val book = EpubFixtures.epub(body)
        val worker = Worker.start()
        try {
            val text = worker.execute(TransferMode.SAFE, { book }) { bytes ->
                val doc = EpubDocument.open(bytes, EpubSettings(pageWidth = 400.0, pageHeight = 600.0, margin = 36.0))
                (0 until doc.pageCountIn(0)).joinToString("\n") {
                    val page = doc.page(KiteLocation(0, it))
                    page.renderTo(RecordingCanvas())
                    page.textContent().plainText
                }
            }.result
            assertTrue("deep words" in text && "After." in text, "the chapter lays out on the small stack: $text")
        } finally {
            worker.requestTermination().result
        }
    }
}
