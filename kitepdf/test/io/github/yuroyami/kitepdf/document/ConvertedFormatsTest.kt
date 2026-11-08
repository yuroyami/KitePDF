package io.github.yuroyami.kitepdf.document

import io.github.yuroyami.kitepdf.epub.EpubDocument
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** KiteDoc knows FictionBook 2 and HTML by their bytes, and opens plain text when told (#946, #947). */
class ConvertedFormatsTest {

    private val fb2 = """<?xml version="1.0" encoding="UTF-8"?><FictionBook xmlns="http://www.gribuser.ru/xml/fictionbook/2.0">""" +
        """<description><title-info><book-title>Tale</book-title><lang>en</lang></title-info></description>""" +
        """<body><section><title><p>One</p></title><p>Once upon a time.</p></section></body></FictionBook>"""

    @Test
    fun fb2_and_html_are_known_by_their_bytes() {
        assertEquals(KiteDocFormat.Fb2, KiteDoc.formatOf(fb2.encodeToByteArray()))
        assertEquals(KiteDocFormat.Html, KiteDoc.formatOf("﻿  <!DOCTYPE html><html><body><p>Hi</p></body></html>".encodeToByteArray()))
        assertNull(KiteDoc.formatOf("just some words".encodeToByteArray()), "plain text has no signature")
    }

    @Test
    fun each_opens_as_a_book() {
        val book = KiteDoc.open(fb2.encodeToByteArray())
        assertTrue(book is EpubDocument)
        assertEquals("Tale", book.metadata.title)
        val page = KiteDoc.open("<!DOCTYPE html><html><body><p>Hello page</p></body></html>".encodeToByteArray())
        assertTrue("Hello page" in page.page(io.github.yuroyami.kitepdf.core.KiteLocation(0, 0)).textContent()!!.plainText)
        val text = KiteDoc.open("Para one.\n\nPara two.".encodeToByteArray(), KiteDocFormat.Text)
        assertTrue("Para two." in text.page(io.github.yuroyami.kitepdf.core.KiteLocation(0, 0)).textContent()!!.plainText)
    }
}
