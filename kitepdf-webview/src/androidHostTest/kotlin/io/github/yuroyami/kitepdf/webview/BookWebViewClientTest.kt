package io.github.yuroyami.kitepdf.webview

import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** An Android web view over an island asks the book for every file, and the network for none (#41). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class BookWebViewClientTest {

    private val doc = WebBooks.quizChapter()
    private val urls = BookUrls("https://btest.book.kitepdf.invalid/")
    private val links = mutableListOf<String>()
    private val client = BookWebViewClient(BookFiles(doc), urls, urls.urlOf("OEBPS/quiz.xhtml"), links::add)

    @Test
    fun a_file_of_the_book_comes_from_the_zip_with_its_type_and_policy() {
        val response = assertNotNull(client.answer(urls.urlOf("OEBPS/dot.svg")))
        assertEquals(200, response.statusCode)
        assertEquals("image/svg+xml", response.mimeType)
        assertEquals(doc.resource("OEBPS/dot.svg")!!.decodeToString(), response.data.readBytes().decodeToString())
        assertTrue(response.responseHeaders.getValue("Content-Security-Policy").startsWith("default-src 'self'"))
        // The island's document gets the island script.
        val page = assertNotNull(client.answer(urls.urlOf("OEBPS/quiz.xhtml"))).data.readBytes().decodeToString()
        assertTrue("window.kitepdf" in page)
    }

    @Test
    fun anything_outside_the_book_gets_an_empty_answer_and_never_the_network() {
        for (url in listOf("https://example.org/tracker.png", "http://127.0.0.1:9/outside.png", urls.urlOf("OEBPS/missing.png"))) {
            val response = assertNotNull(client.answer(url), url)
            assertEquals(404, response.statusCode, url)
            assertEquals(0, response.data.readBytes().size, url)
        }
    }

    @Test
    fun a_navigation_to_another_document_goes_to_the_host_and_one_in_the_document_stays() {
        assertFalse(client.follows(urls.urlOf("OEBPS/quiz.xhtml#part")), "a place in the same document")
        assertTrue(client.follows(urls.urlOf(WebBooks.NEXT)))
        assertTrue(client.follows("https://example.org/"))
        assertEquals(listOf(WebBooks.NEXT, "https://example.org/"), links)
    }

    @Test
    fun a_remote_island_loads_on_its_own_terms() {
        val remote = BookWebViewClient(null, null, "https://example.org/quiz.html", links::add)
        assertNull(remote.answer("https://example.org/quiz.js"))
        assertTrue(remote.follows("https://example.org/next.html"))
        assertEquals(listOf("https://example.org/next.html"), links)
    }
}
