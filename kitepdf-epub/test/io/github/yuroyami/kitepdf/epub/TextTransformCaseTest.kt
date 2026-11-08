package io.github.yuroyami.kitepdf.epub

import kotlin.test.Test
import kotlin.test.assertEquals

/** text-transform uses the full case mappings, final sigma and the language's own (CSS Text 3, 2.1.1; #873). */
class TextTransformCaseTest {

    private fun text(body: String): String =
        EpubDocument.open(EpubFixtures.epub(body)).pages[0].textContent().plainText.trim()

    @Test
    fun fullMappingsChangeTheLength() {
        assertEquals("STRASSE", text("""<p style="text-transform: uppercase">Straße</p>"""))
        assertEquals("FIX", text("<p style=\"text-transform: uppercase\">${Char(0xFB01)}x</p>"))
    }

    @Test
    fun aCapitalSigmaThatEndsAWordLowersToTheFinalForm() {
        assertEquals("οδος οσα", text("""<p style="text-transform: lowercase">ΟΔΟΣ ΟΣΑ</p>"""))
    }

    @Test
    fun turkishKeepsTheDotOfI() {
        assertEquals("İSTANBUL", text("""<p lang="tr" style="text-transform: uppercase">istanbul</p>"""))
        assertEquals("ısparta", text("""<p lang="tr" style="text-transform: lowercase">ISPARTA</p>"""))
        assertEquals("ISTANBUL", text("""<p lang="en" style="text-transform: uppercase">istanbul</p>"""))
    }
}
