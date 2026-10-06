package io.github.yuroyami.kitepdf.epub

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The document's language (spine `xml:lang`/`lang`, else `dc:language`)
 * selects the hyphenation pattern set. "Zeitschrift" is the discriminator:
 * the German patterns break it (Zeit-schrift) while the en-US set finds no
 * break point in it at all.
 */
class HyphenationLanguageTest {

    private fun open(body: String, language: String? = null, pageWidth: Double = 400.0): EpubDocument =
        EpubDocument.open(
            EpubFixtures.epub(body, language = language),
            EpubSettings(pageWidth = pageWidth, pageHeight = 640.0),
        )

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
        val german = """<body xml:lang="de"><p style="hyphens:auto">$ZEITSCHRIFT</p></body>"""
        val english = """<body><p style="hyphens:auto">$ZEITSCHRIFT</p></body>"""
        // Swept over widths, so some width leaves room for "Zeit-" at a line's end.
        assertTrue(WIDTHS.any { "-" in open(german, pageWidth = it).hyphenatedText() }, "German breaks Zeitschrift")
        for (w in WIDTHS) {
            val text = open(english, pageWidth = w).hyphenatedText()
            assertFalse("-" in text, "en-US patterns have no break inside Zeitschrift, width $w:\n$text")
        }
    }

    @Test
    fun each_spine_hyphenates_in_its_own_language() {
        val german = """<body xml:lang="de"><p style="hyphens:auto">$ZEITSCHRIFT</p></body>"""
        val english = """<body><p style="hyphens:auto">$ZEITSCHRIFT</p></body>"""
        var germanBroke = false
        for (w in WIDTHS) {
            val doc = EpubDocument.open(
                EpubFixtures.epubMultiSpine(listOf(english, german)),
                EpubSettings(pageWidth = w, pageHeight = 640.0),
            )
            val englishText = doc.page(io.github.yuroyami.kitepdf.core.KiteLocation(0, 0)).textContent().plainText
            val germanText = doc.page(io.github.yuroyami.kitepdf.core.KiteLocation(1, 0)).textContent().plainText
            assertFalse("-" in englishText, "the English chapter must not use German breaks, width $w:\n$englishText")
            germanBroke = germanBroke || "-" in germanText
        }
        assertTrue(germanBroke, "the German chapter must break Zeitschrift at some width")
    }

    @Test
    fun the_hyphenate_setting_overrides_the_book_css() {
        fun text(body: String, hyphenate: Boolean?): String = EpubDocument.open(
            EpubFixtures.epub(body),
            EpubSettings(pageWidth = 220.0, pageHeight = 640.0, hyphenate = hyphenate),
        ).pages[0].textContent().plainText
        val plain = """<body xml:lang="de"><p>Krankenhaus Krankenhaus Krankenhaus</p></body>"""
        assertFalse("-" in text(plain, null), "as authored: this book never asks for hyphens")
        assertTrue("-" in text(plain, true), "the reader turns hyphenation on, with the German patterns")
        val asks = """<body xml:lang="de"><p style="hyphens:auto">Krankenhaus Krankenhaus Krankenhaus</p></body>"""
        assertFalse("-" in text(asks, false), "the reader turns it off, even where the book asks for it")
    }

    /** A language with no bundled set gets no English breaks, which would be wrong breaks in it (#615). */
    @Test
    fun a_language_without_a_bundled_set_is_not_hyphenated() {
        val words = "hyphenation hyphenation hyphenation"
        assertTrue("-" in open("""<body><p style="hyphens:auto">$words</p></body>""", pageWidth = 120.0).hyphenatedText())
        assertTrue("-" in open("""<body xml:lang="en"><p style="hyphens:auto">$words</p></body>""", pageWidth = 120.0).hyphenatedText())
        // Czech has patterns upstream, but under the GPL only, so none are bundled.
        val czech = open("""<body xml:lang="cs"><p style="hyphens:auto">$words</p></body>""", pageWidth = 120.0).hyphenatedText()
        assertFalse("-" in czech, "no English breaks in a Czech chapter:\n$czech")
    }

    /** Punctuation that touches a word stays out of what the patterns read (#618). */
    @Test
    fun a_word_next_to_punctuation_hyphenates() {
        for (words in listOf(
            "Krankenhaus, Krankenhaus, Krankenhaus.",
            "«Krankenhaus» «Krankenhaus» «Krankenhaus»",
            "(Krankenhaus) „Krankenhaus“ Krankenhaus!",
        )) {
            val text = open("""<body xml:lang="de"><p style="hyphens:auto">$words</p></body>""", pageWidth = 220.0).hyphenatedText()
            assertTrue("-" in text, "the German patterns must break Krankenhaus in \"$words\":\n$text")
        }
    }

    /** The French patterns know the apostrophe of an elision (#618). */
    @Test
    fun a_word_with_an_apostrophe_hyphenates() {
        val words = "l’université l’université l’université"
        val text = open("""<body xml:lang="fr"><p style="hyphens:auto">$words</p></body>""", pageWidth = 150.0).hyphenatedText()
        assertTrue("-" in text, "the French patterns must break l’université:\n$text")
    }

    /** A decomposed accent is part of its word and stays on its letter (#617). */
    @Test
    fun a_word_with_a_combining_mark_hyphenates_and_keeps_the_mark() {
        val word = "De\u0301veloppement"
        val text = open(
            """<body xml:lang="fr"><p style="hyphens:auto">$word $word $word</p></body>""", pageWidth = 150.0,
        ).hyphenatedText()
        assertTrue("-" in text, "the French patterns must break Développement:\n$text")
        assertFalse(Regex("-\\s+\u0301").containsMatchIn(text), "no line starts with the accent:\n$text")
    }

    private fun EpubDocument.hyphenatedText(): String = (0 until pageCount).joinToString("\n") { pages[it].textContent().plainText }

    private companion object {
        const val ZEITSCHRIFT = "Zeitschrift Zeitschrift Zeitschrift Zeitschrift"
        /** Page widths to sweep, so that one of them leaves room for a word's first part. */
        val WIDTHS = (140..300 step 8).map { it.toDouble() }
    }
}
