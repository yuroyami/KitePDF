package io.github.yuroyami.kitepdf.media

import io.github.yuroyami.kitepdf.media.MediaBooks.book
import io.github.yuroyami.kitepdf.media.MediaBooks.firstPage
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Which items an element offers the player, and in what order (#31). */
class MediaSourcesTest {

    @Test
    fun the_sources_are_offered_in_the_element_order_and_a_missing_entry_is_skipped() {
        val doc = book(
            """<audio controls="controls"><source src="gone.ogg"/><source src="tone.mp3"/><source src="https://example.com/a.mp3"/></audio>""",
            files = mapOf("tone.mp3" to byteArrayOf(1, 2, 3)),
        )
        val media = firstPage(doc).media.single()
        assertEquals(listOf("OEBPS/tone.mp3"), mediaItems(media, doc, allowRemote = false).map { it.uri }.toList())
        assertEquals(
            listOf("OEBPS/tone.mp3", "https://example.com/a.mp3"),
            mediaItems(media, doc, allowRemote = true).map { it.uri }.toList(),
        )
    }

    @Test
    fun a_url_plays_only_over_https_and_only_when_allowed() {
        val doc = book(
            """<audio controls="controls">""" +
                """<source src="https://example.com/a.mp3"/><source src="HTTPS://example.com/b.mp3"/>""" +
                """<source src="http://example.com/c.mp3"/><source src="file:///etc/hosts"/>""" +
                """<source src="content://media/external/audio/media/1"/><source src="ftp://example.com/d.mp3"/>""" +
                """<source src="tone.mp3"/></audio>""",
            files = mapOf("tone.mp3" to byteArrayOf(1, 2, 3)),
        )
        val media = firstPage(doc).media.single()
        assertEquals(7, media.sources.size, "the book offers every source")
        assertEquals(
            listOf("https://example.com/a.mp3", "HTTPS://example.com/b.mp3", "OEBPS/tone.mp3"),
            mediaItems(media, doc, allowRemote = true).map { it.uri }.toList(),
            "EPUB Reading Systems 3.3, 3.3 and 3.5: https only, and never a file URL",
        )
        assertEquals(listOf("OEBPS/tone.mp3"), mediaItems(media, doc, allowRemote = false).map { it.uri }.toList())
    }

    @Test
    fun an_item_reads_the_bytes_of_its_entry() = runBlocking {
        val doc = book("""<audio controls="controls" src="tone.mp3"></audio>""", files = mapOf("tone.mp3" to byteArrayOf(7, 8, 9)))
        val item = mediaItems(firstPage(doc).media.single(), doc, allowRemote = false).single()
        val buffer = ByteArray(8)
        checkNotNull(item.io).open().use { io -> assertEquals(3, io.read(buffer, 0, buffer.size)) }
        assertEquals(listOf<Byte>(7, 8, 9), buffer.take(3))
    }

    @Test
    fun a_url_scheme_is_told_from_a_zip_path() {
        assertTrue(hasScheme("https://example.com/a.mp4"))
        assertTrue(hasScheme("data:audio/mpeg;base64,AAAA"))
        assertFalse(hasScheme("OEBPS/audio/a.mp3"))
        assertFalse(hasScheme("OEBPS/a:b.mp3"))
        assertFalse(hasScheme(":a.mp3"))
    }
}
