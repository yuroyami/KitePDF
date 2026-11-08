package io.github.yuroyami.kitepdf.epub.css

import io.github.yuroyami.kitepdf.core.render.RecordingCanvas
import io.github.yuroyami.kitepdf.epub.EpubDocument
import io.github.yuroyami.kitepdf.epub.EpubFixtures
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** An `@media` block applies only when its query matches the page (CSS Conditional Rules 3, 6; #841). 300 pt is 400 px. */
class MediaQueriesTest {

    @Test
    fun typesAndFeaturesMatchAScreenOfThePagesSize() {
        val w = 300.0
        val h = 500.0
        for (q in listOf("", "all", "screen", "only screen", "screen and (min-width: 200px)", "(max-width: 600px)",
            "(orientation: portrait)", "(prefers-color-scheme: light)", "print, screen", "not print", "(color)")) {
            assertTrue(MediaQueries.matches(q, w, h), q)
        }
        for (q in listOf("print", "speech", "amzn-kf8", "screen and (max-width: 300px)", "(min-width: 1000px)",
            "(orientation: landscape)", "(prefers-color-scheme: dark)", "not screen", "(unknown-feature: 1)", "screen and")) {
            assertFalse(MediaQueries.matches(q, w, h), q)
        }
    }

    private fun lines(css: String, extra: String = "", files: List<Pair<String, ByteArray>> = emptyList()): Int {
        val body = "<head><style>$css</style>$extra</head><body><p>x</p></body>"
        val doc = EpubDocument.open(EpubFixtures.epub(body, files))
        return doc.pages.sumOf { p -> RecordingCanvas().also { p.renderTo(it) }.calls.count { it is RecordingCanvas.Call.Glyphs } }
    }

    @Test
    fun aPrintBlockAndAPrintSheetDoNotApply() {
        assertEquals(1, lines("p { color: black } @media print { p { display: none } }"))
        assertEquals(0, lines("p { color: black } @media screen { p { display: none } }"))
        val hide = listOf("OEBPS/hide.css" to "p { display: none }".encodeToByteArray())
        assertEquals(1, lines("", "<link rel=\"stylesheet\" href=\"hide.css\" media=\"print\"/>", hide))
        assertEquals(0, lines("", "<link rel=\"stylesheet\" href=\"hide.css\" media=\"screen\"/>", hide))
        assertEquals(1, lines("@import \"hide.css\" print;", files = hide))
        assertEquals(0, lines("@import \"hide.css\";", files = hide))
    }
}
