package io.github.yuroyami.kitepdf.epub

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** CSS counters in generated content (CSS Lists and Counters 3, 4; #934). */
class CssCounterTest {

    private fun book(css: String, body: String): String {
        val doc = EpubDocument.open(EpubFixtures.epub("<body><style>$css</style>$body</body>"))
        return doc.pages.joinToString(" ") { it.textContent().plainText }.replace(Regex("\\s+"), " ").trim()
    }

    @Test
    fun a_counter_numbers_each_heading() {
        val t = book("body { counter-reset: n } h2::before { counter-increment: n; content: \"Note \" counter(n) \". \" }", "<h2>First</h2><h2>Second</h2>")
        assertTrue("Note 1. First" in t && "Note 2. Second" in t, t)
    }

    @Test
    fun nested_counters_print_every_level() {
        // The pattern of CSS Lists and Counters 3, 4.5: a reset on each list, the nested list inside an item.
        val css = "div.l { counter-reset: s } p::before { counter-increment: s; content: counters(s, \".\") \" \" }"
        val t = book(css, "<div class=\"l\"><div><p>A</p><div class=\"l\"><p>B</p><p>C</p></div></div><div><p>D</p></div></div>")
        assertEquals("1 A 1.1 B 1.2 C 2 D", t)
        // A reset on a sibling also covers the siblings after it (4.4).
        val sibling = book("h2 { counter-reset: s } p::before { counter-increment: s; content: counter(s) \" \" }", "<p>a</p><h2>H</h2><p>b</p><p>c</p>")
        assertEquals("1 a H 1 b 2 c", sibling)
    }

    @Test
    fun a_counter_style_and_a_url_content() {
        val t = book("body { counter-reset: n } p::before { counter-increment: n; content: counter(n, upper-roman) \" \" }", "<p>x</p><p>y</p>")
        assertEquals("I x II y", t)
        val inert = book("p::before { content: url(a.png) \"!\" }", "<p>x</p>")
        assertEquals("x", inert, "a url() content stays inert")
    }
}
