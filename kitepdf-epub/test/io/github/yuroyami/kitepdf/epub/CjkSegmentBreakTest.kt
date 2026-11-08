package io.github.yuroyami.kitepdf.epub

import kotlin.test.Test
import kotlin.test.assertEquals

/** A source line break between two wide characters is no space (CSS Text 3, 4.1.3; #834). */
class CjkSegmentBreakTest {

    private fun text(body: String): String =
        EpubDocument.open(EpubFixtures.epub(body)).pages[0].textContent().plainText.trim()

    @Test
    fun aLineBreakBetweenIdeographsGoesAway() {
        assertEquals("春夏秋冬山川草木", text("<p>春夏秋冬\n  山川草木</p>"))
    }

    @Test
    fun aLineBreakBetweenHangulOrLatinStaysASpace() {
        assertEquals("한국어 문장", text("<p>한국어\n문장</p>"))
        assertEquals("春 a", text("<p>春\na</p>"))
        assertEquals("春 夏", text("<p>春 夏</p>"))
    }
}
