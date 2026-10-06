package io.github.yuroyami.kitepdf.javascript

import io.github.yuroyami.kitepdf.epub.EpubDocument
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.test.TestResult

/**
 * A chapter that the reader reaches at a fragment has it in `location`, the element it names
 * matches `:target` once the chapter is parsed, and a move to another fragment fires `popstate`
 * and then `hashchange` (#550). Each expected line is what headless Chromium logs for the same
 * chapter opened at `#n`, where the viewer's move to `#m` is a script's `location.hash = 'm'`.
 */
class FragmentTest {

    private val runners = ArrayList<EpubScriptRunner>()

    @AfterTest
    fun closeRunners() {
        runners.forEach { it.close() }
    }

    private val script = """
        function t() { var x = document.querySelector(':target'); return x ? (x.id || x.getAttribute('name')) : 'none'; }
        function c(id) { return getComputedStyle(document.getElementById(id)).color; }
        function tail(u) { var i = u.indexOf('#'); return i < 0 ? '' : u.slice(i); }
        function log(s) { console.log(s); }
        log('parse ' + location.hash + ' ' + t() + ' ' + c('n'));
        document.addEventListener('DOMContentLoaded', function () { log('dcl ' + location.hash + ' ' + t() + ' ' + c('n')); });
        document.addEventListener('readystatechange', function () { log('state ' + document.readyState + ' ' + t()); });
        window.addEventListener('popstate', function (e) { log('popstate ' + location.hash + ' ' + e.state + ' ' + e.bubbles + ' ' + e.cancelable + ' ' + e.isTrusted + ' ' + (e instanceof PopStateEvent)); });
        window.addEventListener('hashchange', function (e) {
          log('hashchange ' + tail(e.oldURL) + ' > ' + tail(e.newURL) + ' ' + e.bubbles + ' ' + e.cancelable + ' ' + e.isTrusted + ' ' + (e instanceof HashChangeEvent) + ' ' + t());
        });
        window.addEventListener('load', function () {
          log('load ' + location.hash + ' ' + t() + ' ' + c('n') + ' ' + c('a'));
          document.getElementById('toa').click();
          log('click ' + location.hash + ' ' + t() + ' ' + c('n') + ' ' + c('a'));
          document.getElementById('toa').click();
          log('again ' + location.hash + ' ' + t());
          location.hash = 'a';
          log('same ' + location.hash + ' ' + t());
          location.hash = 'a b';
          log('space ' + location.hash + ' ' + t());
          location.hash = '';
          log('empty |' + location.hash + '| ' + tail(location.href) + ' ' + t());
          location.hash = 'nm';
          log('named ' + location.hash + ' ' + t());
          location.hash = '%6E';
          log('decoded ' + location.hash + ' ' + t());
          var n = document.getElementById('n');
          n.remove();
          log('removed ' + t() + ' ' + n.matches(':target') + ' ' + n.cloneNode(true).matches(':target'));
          document.body.appendChild(n);
          location.href = location.pathname.split('/').pop() + '#dup';
          log('href ' + location.hash + ' ' + t() + ' ' + document.querySelector(':target').textContent);
          var e = new HashChangeEvent('hashchange', { oldURL: 'x', newURL: 'y' });
          var p = new PopStateEvent('popstate', { state: 7 });
          log('made ' + e.oldURL + e.newURL + ' ' + p.state + ' ' + p.hasUAVisualTransition + ' ' + document.createEvent('HashChangeEvent').newURL.length);
        });
        
    """.trimIndent()

    private val body = "<p id=\"a\"><a id=\"toa\" href=\"#a\">a</a></p><p id=\"n\">n</p><p id=\"m\">m</p><p id=\"dup\">d1</p><p id=\"dup\">d2</p>" +
        "<p><a name=\"nm\">named</a></p><script src=\"frag.js\"></script>"

    private fun book(html: Boolean): EpubDocument {
        val head = "<style>p{color:blue} :target{color:red}</style>"
        val name = if (html) "chapter.html" else "chapter.xhtml"
        return ScriptBooks.book(
            items = listOf(
                ScriptBooks.Item(name, if (html) "text/html" else "application/xhtml+xml", properties = "scripted", spine = true),
                ScriptBooks.Item("frag.js", "text/javascript"),
            ),
            files = mapOf(name to if (html) ScriptBooks.html(head, body) else ScriptBooks.xhtml(head, body), "frag.js" to script),
        )
    }

    private fun logged(html: Boolean): List<String> {
        val console = ArrayList<String>()
        val book = book(html)
        val runner = EpubScriptRunner(book, onConsole = { _, message -> console += message }).also { runners += it }
        fun pump() {
            var rounds = 0
            while (runner.hasTimers && rounds++ < 20) runner.pumpTimers(0L)
        }
        // The viewer reaches the chapter through a link to its #n.
        book.setFragment(0, "n")
        runner.chapterOpened(0)
        pump()
        // Then through a link to its #m.
        book.setFragment(0, "m")
        runner.chapterOpened(0)
        pump()
        assertEquals(emptyList(), runner.failures.map { it.message })
        assertEquals("m", book.fragmentOf(0))
        return console
    }

    private val chromium = listOf(
        "parse #n none rgb(0, 0, 255)",
        "state interactive none",
        "dcl #n none rgb(0, 0, 255)",
        "state complete n",
        "load #n n rgb(255, 0, 0) rgb(0, 0, 255)",
        "popstate #a null false false true true",
        "click #a a rgb(0, 0, 255) rgb(255, 0, 0)",
        "popstate #a null false false true true",
        "again #a a",
        "same #a a",
        "popstate #a%20b null false false true true",
        "space #a%20b none",
        "popstate  null false false true true",
        "empty || # none",
        "popstate #nm null false false true true",
        "named #nm nm",
        "popstate #%6E null false false true true",
        "decoded #%6E n",
        "removed none true false",
        "popstate #dup null false false true true",
        "href #dup dup d1",
        "made xy 7 false 0",
        "hashchange #n > #a false false true true dup",
        "hashchange #a > #a%20b false false true true dup",
        "hashchange #a%20b > # false false true true dup",
        "hashchange # > #nm false false true true dup",
        "hashchange #nm > #%6E false false true true dup",
        "hashchange #%6E > #dup false false true true dup",
        "popstate #m null false false true true",
        "hashchange #dup > #m false false true true m",
    )

    @Test
    fun an_xhtml_chapter_follows_its_fragment_as_chromium_does(): TestResult = scriptTest {
        assertEquals(chromium, logged(html = false))
    }

    @Test
    fun an_html_chapter_follows_its_fragment_as_chromium_does(): TestResult = scriptTest {
        assertEquals(chromium, logged(html = true))
    }
}
