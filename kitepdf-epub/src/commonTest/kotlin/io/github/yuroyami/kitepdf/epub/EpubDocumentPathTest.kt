package io.github.yuroyami.kitepdf.epub

import kotlin.test.Test
import kotlin.test.assertEquals

/** Href resolution against a base directory: `..`/`.` normalization + percent-decode. */
class EpubDocumentPathTest {

    @Test
    fun resolves_sibling() {
        assertEquals("OEBPS/chapter1.xhtml", EpubDocument.resolvePath("OEBPS", "chapter1.xhtml"))
    }

    @Test
    fun resolves_parent_traversal() {
        assertEquals("OEBPS/img/pic.png", EpubDocument.resolvePath("OEBPS/text", "../img/pic.png"))
    }

    @Test
    fun collapses_dot_segments() {
        assertEquals("OEBPS/a.png", EpubDocument.resolvePath("OEBPS", "./a.png"))
    }

    @Test
    fun strips_fragment_and_query() {
        assertEquals("OEBPS/a.xhtml", EpubDocument.resolvePath("OEBPS", "a.xhtml#section2"))
    }

    @Test
    fun percent_decodes() {
        assertEquals("OEBPS/my image.png", EpubDocument.resolvePath("OEBPS", "my%20image.png"))
    }

    @Test
    fun absolute_href_ignores_base() {
        assertEquals("img/a.png", EpubDocument.resolvePath("OEBPS/text", "/img/a.png"))
    }
}
