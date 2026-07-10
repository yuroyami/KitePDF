package io.github.yuroyami.kitepdf.epub

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * T-70: the document's language (spine `xml:lang`/`lang`, else `dc:language`)
 * selects the hyphenation pattern set. "Krankenhaus" is the discriminator:
 * the German patterns break it (Kran-ken-haus) while the built-in en-US set
 * finds no break point in it at all.
 */
class HyphenationLanguageTest {

    private fun open(body: String, language: String? = null, pageWidth: Double = 400.0): EpubDocument =
        EpubDocument.open(
            EpubFixtures.epub(body, language = language),
            EpubSettings(pageWidth = pageWidth, pageHeight = 640.0),
        ) ?: error("fixture failed to open")

    @Test
    fun body_lang_attribute_wins() {
        val doc = open("""<body xml:lang="de-DE"><p>Hallo</p></body>""", language = "fr")
        assertEquals("de-DE", doc.documentLanguage)
    }

    @Test
    fun dc_language_is_the_fallback() {
        val doc = open("<body><p>Bonjour</p></body>", language = "fr")
        assertEquals("fr", doc.documentLanguage)
    }

    @Test
    fun no_language_anywhere_is_null() {
        val doc = open("<body><p>Hello</p></body>")
        assertNull(doc.documentLanguage)
    }

    @Test
    fun german_book_hyphenates_with_german_patterns() {
        val body = """<body xml:lang="de"><p style="hyphens:auto">Krankenhaus Krankenhaus Krankenhaus</p></body>"""
        val doc = open(body, pageWidth = 220.0)
        val text = doc.pages[0].textContent().plainText
        assertTrue("-" in text, "German patterns must break Krankenhaus somewhere:\n$text")
    }

    @Test
    fun same_book_without_language_does_not_break_the_german_word() {
        val body = """<body><p style="hyphens:auto">Krankenhaus Krankenhaus Krankenhaus</p></body>"""
        val doc = open(body, pageWidth = 220.0)
        val text = doc.pages[0].textContent().plainText
        assertFalse("-" in text, "en-US patterns have no break inside Krankenhaus:\n$text")
    }
}
