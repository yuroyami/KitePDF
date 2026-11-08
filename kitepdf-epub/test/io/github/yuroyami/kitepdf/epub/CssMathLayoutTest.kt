package io.github.yuroyami.kitepdf.epub

import kotlin.test.Test
import kotlin.test.assertEquals

/** A margin written with calc(), min(), max() or clamp() moves the text as its value does (#930). */
class CssMathLayoutTest {

    private fun lineStart(margin: String): Double {
        val doc = EpubDocument.open(
            EpubFixtures.epub("<p style=\"margin-left: $margin\">Hello world</p>"),
            EpubSettings(pageWidth = 400.0, pageHeight = 600.0),
        )
        return doc.pages[0].textContent().blocks.first().lines.first().bounds.left
    }

    @Test
    fun each_math_function_gives_the_margin_its_value_makes() {
        val plain = lineStart("100px")
        for (v in listOf("calc(100px)", "calc(50px + 50px)", "max(100px, 10px)", "clamp(100px, 5px, 200px)")) {
            assertEquals(plain, lineStart(v), 1e-6, v)
        }
        assertEquals(plain - 75.0, lineStart("0"), 1e-6, "the fixture tells the two apart")
    }
}
