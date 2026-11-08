package io.github.yuroyami.kitepdf.epub

import io.github.yuroyami.kitepdf.core.KiteTextLine
import kotlin.test.Test
import kotlin.test.assertEquals

/** `nowrap` keeps its text on one line, and `pre-line` keeps only the line breaks (CSS Text 3, 3). */
class WhiteSpaceModesTest {

    /** A page whose content box is 40 pt wide. */
    private val settings = EpubSettings(pageWidth = 136.0, pageHeight = 640.0)

    private fun lines(body: String, s: EpubSettings = settings): List<String> =
        EpubDocument.open(EpubFixtures.epub(body), s).pages[0].textContent().blocks.flatMap { b -> b.lines.map(KiteTextLine::text) }
            .map { it.trim() }

    @Test
    fun nowrapOnABlockKeepsItsTextOnOneLine() {
        // Without nowrap this wraps at 40 pt (#845).
        assertEquals(listOf("aa b c d e f"), lines("""<p style="white-space:nowrap">aa b c d e f</p>"""))
    }

    @Test
    fun nowrapOnASpanKeepsTheSpanTogetherAndTheRestWraps() {
        val text = lines("""<p>aa <span style="white-space:nowrap">b c d e f</span></p>""")
        assertEquals(listOf("aa", "b c d e f"), text)
    }

    @Test
    fun preLineCollapsesSpacesAndKeepsLineBreaks() {
        // A wide page, so only the line break of the source ends a line (#846).
        val wide = EpubSettings(pageWidth = 400.0, pageHeight = 640.0)
        val text = lines("<p style=\"white-space:pre-line\">a     b\n   c\tq</p>", wide)
        assertEquals(listOf("a b", "c q"), text)
    }
}
