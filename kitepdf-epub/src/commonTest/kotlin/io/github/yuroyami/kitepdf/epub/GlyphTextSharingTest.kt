package io.github.yuroyami.kitepdf.epub

import io.github.yuroyami.kitepdf.core.render.RecordingCanvas
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

/**
 * Glyphs for the same character share one String. A book holds one glyph per
 * character, so a private String per glyph was a fifth of the memory a
 * laid-out chapter kept (#220).
 */
class GlyphTextSharingTest {

    @Test
    fun two_glyphs_for_the_same_character_share_their_text() {
        val doc = EpubDocument.open(EpubFixtures.epub("<p>aa</p>"))
        val glyphs = RecordingCanvas().also { doc.pages[0].renderTo(it) }.calls
            .filterIsInstance<RecordingCanvas.Call.Glyphs>()
            .flatMap { it.glyphs }
            .filter { it.text == "a" }
        assertEquals(2, glyphs.size, "expected the two a glyphs")
        assertSame(glyphs[0].text, glyphs[1].text)
    }
}
