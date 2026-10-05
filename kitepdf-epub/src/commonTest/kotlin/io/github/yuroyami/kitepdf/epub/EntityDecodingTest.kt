package io.github.yuroyami.kitepdf.epub

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * A chapter's character references reach its page text: HTML's named ones, which an XHTML 1.1
 * book may use since its DTD declares them (#570).
 */
class EntityDecodingTest {

    private fun pageText(chapter: String): String {
        val book = EpubDocument.open(EpubFixtures.epub("", chapterBytes = chapter.encodeToByteArray()), EpubSettings(pageWidth = 400.0, pageHeight = 640.0))
        return book.pages[0].textContent().plainText.replace(Regex("\\s+"), " ").trim()
    }

    @Test
    fun an_xhtml_1_1_chapter_decodes_the_names_its_dtd_declares() {
        val chapter = """<?xml version="1.0" encoding="UTF-8"?>
            <!DOCTYPE html PUBLIC "-//W3C//DTD XHTML 1.1//EN" "http://www.w3.org/TR/xhtml11/DTD/xhtml11.dtd">
            <html xmlns="http://www.w3.org/1999/xhtml"><body><p>Wait&mdash;it&rsquo;s a caf&eacute;&hellip;</p></body></html>"""
        assertEquals("Wait—it’s a café…", pageText(chapter))
    }
}
