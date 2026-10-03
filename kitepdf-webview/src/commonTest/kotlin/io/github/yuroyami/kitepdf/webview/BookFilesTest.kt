package io.github.yuroyami.kitepdf.webview

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** What a web view gets when it asks the book for a file (#41). */
class BookFilesTest {

    private val files = BookFiles(WebBooks.scriptedPage())

    @Test
    fun a_file_of_the_book_comes_with_its_manifest_type_and_a_missing_one_is_not_found() {
        val script = files.respond("OEBPS/page.js")
        assertEquals(200, script.status)
        assertEquals("text/javascript", script.mimeType)
        assertTrue(script.body.decodeToString().startsWith("function paint()"))
        assertEquals("utf-8", script.charset)
        val missing = files.respond("OEBPS/nothing.js")
        assertEquals(404, missing.status)
        assertEquals(0, missing.body.size)
    }

    @Test
    fun every_answer_keeps_the_page_to_the_book() {
        for (response in listOf(files.respond("OEBPS/page.css"), files.respond("OEBPS/nothing.css"))) {
            val policy = response.headers.getValue("Content-Security-Policy")
            assertTrue(policy.startsWith("default-src 'self'"), policy)
            assertFalse("*" in policy || "http" in policy, "no other origin: $policy")
        }
    }

    @Test
    fun a_document_gets_the_island_script_at_the_top_of_its_head() {
        val page = files.respond("OEBPS/page.xhtml")
        assertEquals("application/xhtml+xml", page.mimeType)
        val text = page.body.decodeToString()
        val head = text.indexOf("<head>")
        val script = text.indexOf("<script type=\"text/javascript\">//<![CDATA[")
        assertTrue(head in 0 until script, "the script follows the head's tag")
        assertTrue(script < text.indexOf("<title>"), "and comes before anything else in the head")
        assertTrue("//]]></script>" in text)
        // An HTML document gets it without CDATA, and one without a head after its root.
        val html = BookFiles.withIslandScript("<!DOCTYPE html><html lang=\"en\"><body>x</body></html>".encodeToByteArray(), xml = false).decodeToString()
        assertTrue(html.startsWith("<!DOCTYPE html><html lang=\"en\"><script>(function () {"), html.take(80))
    }

    @Test
    fun a_document_not_in_utf_8_goes_as_it_is() {
        val utf16 = byteArrayOf(0xFE.toByte(), 0xFF.toByte(), 0, '<'.code.toByte())
        assertTrue(utf16.contentEquals(BookFiles.withIslandScript(utf16, xml = true)))
    }

    @Test
    fun a_zip_path_goes_into_a_url_and_comes_back() {
        val path = "OEBPS/Text/chap ter é#1.xhtml"
        val url = BookFiles.urlPathOf(path)
        assertEquals("OEBPS/Text/chap%20ter%20%C3%A9%231.xhtml", url)
        assertEquals(path, BookFiles.pathOf(url))
        assertEquals("OEBPS/a.xhtml", BookFiles.pathOf("/OEBPS/a.xhtml?x=1#top"))
    }

    @Test
    fun a_file_without_a_manifest_type_takes_one_from_its_extension() {
        assertEquals("text/css", BookFiles.typeOf("a/b.CSS"))
        assertEquals("image/svg+xml", BookFiles.typeOf("dot.svg"))
        assertEquals("font/woff2", BookFiles.typeOf("f.woff2"))
        assertEquals("application/octet-stream", BookFiles.typeOf("README"))
    }
}
