package io.github.yuroyami.kitepdf.epub

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * T-63: anchors, internal links and href-to-page mapping. `<a href>` runs
 * surface as display-space [EpubLink] rects on their pages; element ids (and
 * legacy `<a name>`) resolve to exact pages via `pageIndexOfHref`.
 */
class EpubLinkTest {

    private fun filler(n: Int): String = (1..n).joinToString("") { "<p>filler paragraph number $it with some words</p>" }

    @Test
    fun page_exposes_link_rects_with_resolved_targets() {
        val doc = EpubDocument.open(
            EpubFixtures.epubMultiSpine(
                listOf(
                    """<p>see <a href="chapter2.xhtml#deep">this deep anchor</a> now</p>""",
                    filler(2) + """<h2 id="deep">Deep</h2>""",
                ),
            ),
        ) ?: error("fixture failed to open")
        val links = doc.pages[0].links
        assertEquals(1, links.size)
        assertEquals("OEBPS/chapter2.xhtml#deep", links[0].href, "relative href resolves to a zip path + fragment")
        val r = links[0].rect
        assertTrue(r.left < r.right && r.bottom < r.top, "non-empty display rect: $r")
        assertTrue(r.left >= 0 && r.right <= doc.pageWidth && r.bottom >= 0 && r.top <= doc.pageHeight)
        // The rect covers the link text, not the whole line: the leading
        // "see " and trailing " now" sit outside it.
        val line = doc.pages[0].textContent().blocks[0].lines[0]
        assertTrue(r.left > line.bounds.left, "leading non-link text sits before the rect")
        assertTrue(r.right < line.bounds.right, "trailing non-link text sits after the rect")
    }

    @Test
    fun deep_anchor_resolves_to_its_exact_page_not_the_spine_start() {
        // Spine 3 opens with enough filler that #deep lands past its first page.
        val doc = EpubDocument.open(
            EpubFixtures.epubMultiSpine(
                listOf(
                    """<p>intro <a href="chapter3.xhtml#deep">link</a></p>""",
                    filler(3),
                    filler(60) + """<h2 id="deep">Deep target</h2>""",
                ),
            ),
        ) ?: error("fixture failed to open")
        val spineStart = doc.pageIndexOfHref("OEBPS/chapter3.xhtml")
        val deep = doc.pageIndexOfHref("OEBPS/chapter3.xhtml#deep")
        assertTrue(spineStart != null && deep != null)
        assertTrue(deep > spineStart, "the anchor page ($deep) must be past the spine's first page ($spineStart)")
        assertTrue(deep < doc.pageCount)
    }

    @Test
    fun fragment_only_link_targets_its_own_document() {
        val doc = EpubDocument.open(
            EpubFixtures.epub("<body><p><a href=\"#local\">jump</a></p>" + filler(60) + "<p id=\"local\">target</p></body>"),
        ) ?: error("fixture failed to open")
        assertEquals("OEBPS/chapter1.xhtml#local", doc.pages[0].links.single().href)
        val target = doc.pageIndexOfHref("OEBPS/chapter1.xhtml#local")
        assertTrue(target != null && target > 0, "the local anchor sits pages later (got $target)")
    }

    @Test
    fun external_urls_stay_verbatim_and_do_not_navigate() {
        val doc = EpubDocument.open(
            EpubFixtures.epub("""<body><p><a href="https://example.com/a#b">out</a></p></body>"""),
        ) ?: error("fixture failed to open")
        assertEquals("https://example.com/a#b", doc.pages[0].links.single().href)
        assertNull(doc.pageIndexOfHref("https://example.com/a#b"))
    }

    @Test
    fun inline_anchor_ids_and_legacy_a_name_resolve() {
        val doc = EpubDocument.open(
            EpubFixtures.epub(
                "<body>" + filler(60) +
                    "<p>x <span id=\"spanchor\">y</span> z</p>" +
                    "<p>legacy <a name=\"named\">anchor</a></p></body>",
            ),
        ) ?: error("fixture failed to open")
        val spanPage = doc.pageIndexOfHref("OEBPS/chapter1.xhtml#spanchor")
        val namedPage = doc.pageIndexOfHref("OEBPS/chapter1.xhtml#named")
        assertTrue(spanPage != null && spanPage > 0, "inline id resolves past page 0 (got $spanPage)")
        assertTrue(namedPage != null && namedPage >= spanPage!!, "legacy <a name> resolves (got $namedPage)")
    }

    @Test
    fun unknown_fragment_falls_back_to_the_document_start() {
        val doc = EpubDocument.open(
            EpubFixtures.epubMultiSpine(listOf(filler(3), filler(3))),
        ) ?: error("fixture failed to open")
        val start = doc.pageIndexOfHref("OEBPS/chapter2.xhtml")
        assertEquals(start, doc.pageIndexOfHref("OEBPS/chapter2.xhtml#no-such-id"))
        assertNull(doc.pageIndexOfHref("OEBPS/nope.xhtml"))
    }
}
