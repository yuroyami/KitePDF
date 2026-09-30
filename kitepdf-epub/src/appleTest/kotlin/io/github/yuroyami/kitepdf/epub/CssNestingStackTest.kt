package io.github.yuroyami.kitepdf.epub

import io.github.yuroyami.kitepdf.core.KiteLocation
import io.github.yuroyami.kitepdf.core.render.RecordingCanvas
import io.github.yuroyami.kitepdf.core.render.RgbColor
import io.github.yuroyami.kitepdf.epub.css.CssParser
import kotlin.native.concurrent.ObsoleteWorkersApi
import kotlin.native.concurrent.TransferMode
import kotlin.native.concurrent.Worker
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * CSS parsing, layout and drawing on the 512 KB stack of an Apple secondary thread (#451).
 * Kotlin/Native cannot catch a stack overflow, so each fixture runs on a [Worker].
 */
class CssNestingStackTest {

    @Test
    fun deeply_nested_group_rules_preserve_text_and_following_styles_on_a_secondary_thread() {
        for (kind in listOf("media", "supports", "mixed")) {
            assertStylesOnWorker(nestedRules(2_000, kind), RgbColor(0.0, 128 / 255.0, 0.0), kind)
        }
    }

    @Test
    fun group_rules_at_the_depth_limit_apply_on_a_secondary_thread() {
        for (kind in listOf("media", "supports", "mixed")) {
            assertStylesOnWorker(nestedRules(CssParser.MAX_NESTING, kind), RgbColor(0.0, 0.0, 1.0), kind)
        }
    }

    @Test
    fun deeply_nested_not_preserves_text_and_following_styles_on_a_secondary_thread() {
        val selector = ".nested" + ":not(".repeat(2_000) + ".absent" + ")".repeat(2_000)
        assertStylesOnWorker("$selector { color:#0000ff }", RgbColor(0.0, 128 / 255.0, 0.0), ":not()")
    }

    private fun nestedRules(depth: Int, kind: String): String = buildString {
        repeat(depth) { level ->
            append(if (kind == "media" || kind == "mixed" && level % 2 == 0) "@media all {" else "@supports (display:block) {")
        }
        append(".nested { color:#0000ff }")
        repeat(depth) { append('}') }
    }

    @OptIn(ObsoleteWorkersApi::class)
    private fun assertStylesOnWorker(css: String, nestedColor: RgbColor, label: String) {
        val book = EpubFixtures.epubFoldered(
            bodies = listOf("""<p class="nested">Deep.</p><p class="after">After.</p>"""),
            sheets = listOf("book.css" to "p { color:#008000 } $css .after { color:#ff0000 }"),
        )
        val worker = Worker.start()
        try {
            val (text, painted) = worker.execute(TransferMode.SAFE, { book }) { bytes ->
                val doc = EpubDocument.open(bytes, EpubSettings(pageWidth = 400.0, pageHeight = 600.0, margin = 36.0))
                val runs = ArrayList<Pair<String, RgbColor>>()
                val text = (0 until doc.pageCountIn(0)).joinToString("\n") {
                    val page = doc.page(KiteLocation(0, it))
                    val canvas = RecordingCanvas()
                    page.renderTo(canvas)
                    for (call in canvas.calls) {
                        if (call is RecordingCanvas.Call.Glyphs) runs.add(call.text to call.color)
                    }
                    page.textContent().plainText
                }
                text to runs
            }.result
            assertTrue("Deep." in text && "After." in text, "$label: chapter text survives: $text")
            assertTrue(painted.any { "Deep." in it.first && it.second == nestedColor }, "$label: nested rule respects the limit: $painted")
            assertTrue(painted.any { "After." in it.first && it.second == RgbColor(1.0, 0.0, 0.0) }, "$label: following rule applies: $painted")
        } finally {
            worker.requestTermination().result
        }
    }
}
