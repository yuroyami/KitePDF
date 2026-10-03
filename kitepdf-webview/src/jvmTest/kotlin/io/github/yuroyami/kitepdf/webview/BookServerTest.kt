package io.github.yuroyami.kitepdf.webview

import java.io.FileNotFoundException
import java.net.HttpURLConnection
import java.net.URI
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** The desktop serves a book to its web views from the zip, on the loopback address alone (#41). */
class BookServerTest {

    private fun get(url: String): HttpURLConnection = (URI(url).toURL().openConnection() as HttpURLConnection).apply { connect() }

    @Test
    fun a_file_of_the_book_comes_from_the_zip_with_its_type_and_its_policy() {
        val doc = WebBooks.scriptedPage()
        val server = BookServer.acquire(doc)
        try {
            assertTrue(server.base.startsWith("http://127.0.0.1:"), server.base)
            val connection = get(server.urlOf("OEBPS/page.css"))
            assertEquals(200, connection.responseCode)
            assertEquals("text/css; charset=utf-8", connection.contentType)
            assertTrue(connection.getHeaderField("Content-Security-Policy").startsWith("default-src 'self'"))
            assertEquals(doc.resource("OEBPS/page.css")!!.decodeToString(), connection.inputStream.readBytes().decodeToString())
            assertEquals(listOf("OEBPS/page.css"), server.requested.toList())
        } finally {
            BookServer.release(server)
        }
    }

    @Test
    fun a_request_outside_the_token_or_for_a_missing_file_gets_nothing() {
        val server = BookServer.acquire(WebBooks.scriptedPage())
        try {
            val origin = server.base.substringBeforeLast('/').substringBeforeLast('/')
            assertFailsWith<FileNotFoundException> { get("$origin/OEBPS/page.css").inputStream }
            // The cookie of an answer opens the root of the container, which a guessed one does not.
            val cookie = get(server.urlOf("OEBPS/page.css")).getHeaderField("Set-Cookie").substringBefore(';')
            assertTrue("HttpOnly" in get(server.urlOf("OEBPS/page.css")).getHeaderField("Set-Cookie"))
            val withCookie = (URI("$origin/OEBPS/page.css").toURL().openConnection() as HttpURLConnection).apply { setRequestProperty("Cookie", cookie) }
            assertEquals(200, withCookie.responseCode)
            val guessed = (URI("$origin/OEBPS/page.css").toURL().openConnection() as HttpURLConnection).apply { setRequestProperty("Cookie", "kitepdf-book=0") }
            assertEquals(404, guessed.responseCode)
            assertEquals(404, get(server.urlOf("OEBPS/missing.css")).responseCode)
        } finally {
            BookServer.release(server)
        }
    }

    @Test
    fun a_url_of_the_book_names_its_zip_path_and_fragment() {
        val server = BookServer.acquire(WebBooks.scriptedPage())
        try {
            val url = server.urlOf("OEBPS/a b.xhtml#part")
            assertEquals(server.base + "OEBPS/a%20b.xhtml#part", url)
            assertEquals("OEBPS/a b.xhtml#part", server.hrefOf(url))
            assertNull(server.hrefOf("https://example.org/OEBPS/a.xhtml"))
        } finally {
            BookServer.release(server)
        }
    }

    @Test
    fun the_web_views_of_a_book_share_a_server_and_each_book_has_its_own() {
        val doc = WebBooks.scriptedPage()
        val first = BookServer.acquire(doc)
        val second = BookServer.acquire(doc)
        val other = BookServer.acquire(WebBooks.quizChapter())
        try {
            assertSame(first, second)
            assertNotEquals(first.base.substringBefore("/", ""), other.base, "another origin for another book")
            assertNotEquals(URI(first.base).port, URI(other.base).port)
        } finally {
            BookServer.release(other)
            BookServer.release(second)
        }
        // One user is left, so the server still answers.
        assertEquals(200, get(first.urlOf("OEBPS/page.css")).responseCode)
        BookServer.release(first)
    }
}
