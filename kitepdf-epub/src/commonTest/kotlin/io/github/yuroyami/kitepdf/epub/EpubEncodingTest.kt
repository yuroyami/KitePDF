package io.github.yuroyami.kitepdf.epub

import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Books that are not the UTF-8 the spec promises. The chapter bytes go into
 * the archive in another encoding and must still come out as the right text.
 */
class EpubEncodingTest {

    private fun textOf(book: ByteArray): String =
        EpubDocument.open(book).pages.joinToString("\n") { it.textContent().plainText }

    private val cp1252High = charArrayOf(
        '\u20AC', '\u0081', '\u201A', '\u0192', '\u201E', '\u2026', '\u2020', '\u2021',
        '\u02C6', '\u2030', '\u0160', '\u2039', '\u0152', '\u008D', '\u017D', '\u008F',
        '\u0090', '\u2018', '\u2019', '\u201C', '\u201D', '\u2022', '\u2013', '\u2014',
        '\u02DC', '\u2122', '\u0161', '\u203A', '\u0153', '\u009D', '\u017E', '\u0178',
    )

    /** Encode to Windows-1252: one byte per character. */
    private fun cp1252(s: String): ByteArray = ByteArray(s.length) { i ->
        val c = s[i]
        val at = cp1252High.indexOf(c)
        when {
            at >= 0 -> (0x80 + at).toByte()
            c.code <= 0xFF -> c.code.toByte()
            else -> '?'.code.toByte()
        }
    }

    private fun chapter(declaredEncoding: String, body: String) =
        """<?xml version="1.0" encoding="$declaredEncoding"?>""" +
            """<html xmlns="http://www.w3.org/1999/xhtml"><body>$body</body></html>"""

    @Test
    fun a_chapter_stored_as_windows_1252_reads_its_accents() {
        val src = chapter("windows-1252", "<p>caf\u00E9 na\u00EFve</p>")
        val text = textOf(EpubFixtures.epub("<p>ignored</p>", chapterBytes = cp1252(src)))
        assertTrue("caf\u00E9" in text, "got: $text")
        assertTrue("na\u00EFve" in text, "got: $text")
    }

    @Test
    fun a_chapter_that_lies_about_utf8_still_reads() {
        val src = chapter("utf-8", "<p>don\u2019t</p>")
        val text = textOf(EpubFixtures.epub("<p>ignored</p>", chapterBytes = cp1252(src)))
        assertTrue("don\u2019t" in text, "got: $text")
    }

    @Test
    fun a_chapter_stored_as_utf16_with_a_bom_reads() {
        val src = chapter("utf-16", "<p>caf\u00E9</p>")
        val bytes = ByteArray(2 + src.length * 2)
        bytes[0] = 0xFF.toByte(); bytes[1] = 0xFE.toByte()
        for (i in src.indices) {
            bytes[2 + i * 2] = (src[i].code and 0xFF).toByte()
            bytes[3 + i * 2] = ((src[i].code ushr 8) and 0xFF).toByte()
        }
        val text = textOf(EpubFixtures.epub("<p>ignored</p>", chapterBytes = bytes))
        assertTrue("caf\u00E9" in text, "got: $text")
    }
}
