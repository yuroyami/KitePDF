package io.github.yuroyami.kitepdf.epub

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The spine's `page-progression-direction` orders the pages and nothing else, and a spine that
 * sets none progresses as the book's language reads (EPUB 3.3, 5.4.6, and Reading Systems 3.3,
 * 4.3). A chapter that declares no direction reads as its language does (#512).
 */
class PageProgressionTest {

    private fun open(body: String, language: String? = null, spineDirection: String? = null) = EpubDocument.open(
        EpubFixtures.epub(body, language = language, spineDirection = spineDirection),
        EpubSettings(pageWidth = 400.0, pageHeight = 640.0),
    )

    /** The left and right edges of the first line of the first page. */
    private fun EpubDocument.firstLine(): Pair<Double, Double> =
        pages[0].textContent().blocks[0].lines[0].bounds.let { it.left to it.right }

    @Test
    fun a_right_to_left_progression_leaves_english_text_left_to_right() {
        val doc = open("<p>This test passes.</p>", language = "en", spineDirection = "rtl")
        assertTrue(doc.epubMetadata.rightToLeft, "the pages progress right to left")
        assertEquals(48.0, doc.firstLine().first, 1.0, "the English line starts at the left edge")
    }

    @Test
    fun a_book_in_a_right_to_left_language_without_a_spine_direction_progresses_right_to_left() {
        for (lang in listOf("ar", "he", "fa-IR", "ur", "yi", "az-Arab")) {
            assertTrue(open("<p>x</p>", language = lang).epubMetadata.rightToLeft, lang)
            assertTrue(open("<p>x</p>", language = lang, spineDirection = "default").epubMetadata.rightToLeft, "$lang, default")
        }
        for (lang in listOf("en", "ja", "ar-Latn", "arn")) {
            assertFalse(open("<p>x</p>", language = lang).epubMetadata.rightToLeft, lang)
        }
        assertFalse(open("<p>x</p>", language = "ar", spineDirection = "ltr").epubMetadata.rightToLeft, "an explicit ltr wins")
    }

    @Test
    fun the_book_language_does_not_set_a_chapter_direction() {
        // EPUB Reading Systems 3.3, 3.7: the package's dc:language says nothing of a document's
        // base direction, so a chapter that declares none reads left to right (#564).
        val doc = open("<p>مرحبا بالعالم</p>", language = "ar", spineDirection = "rtl")
        assertEquals(48.0, doc.firstLine().first, 1.0, "the line starts at the left edge")
        assertEquals(352.0, open("""<body lang="ar"><p>مرحبا بالعالم</p></body>""", language = "ar").firstLine().second, 1.0, "its own lang")
    }

    @Test
    fun a_chapter_language_decides_over_the_book_language() {
        assertEquals(48.0, open("""<body lang="en"><p>English words</p></body>""", language = "ar").firstLine().first, 1.0, "lang")
        assertEquals(48.0, open("""<body xml:lang="en"><p>English words</p></body>""", language = "ar").firstLine().first, 1.0, "xml:lang")
        assertEquals(352.0, open("""<body xml:lang="he"><p>English words</p></body>""", language = "en").firstLine().second, 1.0, "he")
    }

    @Test
    fun a_dir_attribute_decides_over_any_language() {
        assertEquals(48.0, open("""<body dir="ltr"><p>words</p></body>""", language = "ar").firstLine().first, 1.0)
        assertEquals(352.0, open("""<body dir="rtl"><p>words</p></body>""", language = "en", spineDirection = "ltr").firstLine().second, 1.0)
    }
}
