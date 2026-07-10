package io.github.yuroyami.kitepdf.epub

import io.github.yuroyami.kitepdf.render.RecordingCanvas
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * T-64: `<ruby>` must not corrupt CJK text. Step 1 (this test): the reading
 * (`<rt>`) and its fallback parentheses (`<rp>`) are dropped by the UA sheet,
 * so the base text renders clean instead of "漢字かんじ" run together.
 */
class RubyTest {

    private fun drawnText(body: String): String {
        val doc = EpubDocument.open(EpubFixtures.epub(body)) ?: error("fixture failed to open")
        return doc.pages.joinToString("") { page ->
            RecordingCanvas().also { page.renderTo(it) }.calls
                .filterIsInstance<RecordingCanvas.Call.Glyphs>()
                .joinToString("") { it.text }
        }
    }

    @Test
    fun ruby_reading_is_not_rendered_inline() {
        val text = drawnText("<body><p>その<ruby>漢字<rt>かんじ</rt></ruby>です</p></body>")
        assertTrue("漢字" in text, "the ruby base must render")
        assertTrue("その" in text && "です" in text, "surrounding text must render")
        assertFalse("かんじ" in text, "the reading must not be dumped inline into the base text")
    }

    @Test
    fun ruby_fallback_parentheses_are_dropped() {
        val text = drawnText("<body><p><ruby>漢<rp>(</rp><rt>かん</rt><rp>)</rp></ruby>字</p></body>")
        assertTrue("漢" in text && "字" in text)
        assertFalse("(" in text || ")" in text, "<rp> fallback punctuation must not render")
        assertFalse("かん" in text)
    }
}
