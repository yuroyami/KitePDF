package io.github.yuroyami.kitepdf.javascript

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.test.TestResult

/**
 * An HTML chapter has the `html`, `head` and `body` elements that HTML's parser makes, where the
 * markup leaves them out (#547). Each expected line is what headless Chromium logs for the same chapter
 * once it is parsed, at `DOMContentLoaded`.
 */
class HtmlStructureTest {

    private val runners = ArrayList<EpubScriptRunner>()

    @AfterTest
    fun closeRunners() {
        runners.forEach { it.close() }
    }

    private val script = """
        document.addEventListener('DOMContentLoaded', function () {
          var out = [];
          function tree(n) {
            var s = n.nodeType === 1 ? n.tagName.toLowerCase() : n.nodeType === 3 ? JSON.stringify(n.data) : n.nodeType === 8 ? '<!--' + n.data + '-->' : n.nodeType === 10 ? '!doctype' : '#';
            if (n.nodeType === 1 && n.attributes.length) s += '[' + Array.prototype.map.call(n.attributes, function (a) { return a.name + '=' + a.value; }).join(',') + ']';
            if (n.nodeType === 1 && n.tagName === 'SCRIPT') return s;
            if (n.childNodes.length) s += '(' + Array.prototype.map.call(n.childNodes, tree).join(' ') + ')';
            return s;
          }
          out.push('tree ' + Array.prototype.map.call(document.childNodes, tree).join(' '));
          out.push('body ' + (document.body && document.body.tagName) + ' head ' + (document.head && document.head.firstChild && document.head.firstChild.nodeName) + ' p ' + (document.getElementById('p') && document.getElementById('p').parentNode.tagName));
          console.log(out.join('\n'));
        });
    """.trimIndent()

    private suspend fun logged(chapter: String): List<String> {
        val console = ArrayList<String>()
        val book = ScriptBooks.page(chapter, extraFiles = mapOf("a.js" to script), html = true)
        val runner = EpubScriptRunner(book, onConsole = { _, message -> console += message }).also { runners += it }
        runner.chapterOpened(0)
        assertEquals(emptyList(), runner.failures.map { it.message })
        return console.flatMap { it.lines() }
    }

    @Test
    fun a_chapter_without_html_head_or_body_gets_all_three(): TestResult = scriptTest {
        assertEquals(listOf("tree html(head(title(\"T\")) body(p[id=p](\"x\") script[src=a.js]))", "body BODY head TITLE p BODY"), logged("<title>T</title><p id=\"p\">x</p><script src=\"a.js\"></script>"))
    }

    @Test
    fun comments_and_a_late_head_element_go_where_html_puts_them(): TestResult = scriptTest {
        assertEquals(listOf("tree <!-- a --> html[lang=en](<!-- b --> head(title(\"T\") meta[name=m]) \" \" <!-- c --> body[class=b](p[id=p](\"x\") script[src=a.js])) <!-- d -->", "body BODY head TITLE p BODY"), logged("<!-- a --><html lang=\"en\"><!-- b --><head><title>T</title></head> <!-- c --><meta name=\"m\"><body class=\"b\"><p id=\"p\">x</p><script src=\"a.js\"></script></body></html><!-- d -->"))
    }

    @Test
    fun text_before_any_element_starts_the_body(): TestResult = scriptTest {
        assertEquals(listOf("tree html(head body(\"text first\" title(\"T\") p[id=p](\"x\") script[src=a.js]))", "body BODY head null p BODY"), logged("text first<title>T</title><p id=\"p\">x</p><script src=\"a.js\"></script>"))
    }

    @Test
    fun a_second_html_or_body_adds_its_attributes_to_the_first(): TestResult = scriptTest {
        assertEquals(listOf("tree !doctype html[lang=de](head(\"\\n\" meta[charset=utf-8] \"\\n\" link[rel=stylesheet,href=s.css] \"\\n\") \"\\n\" body[id=b1,class=c](\"\\n\" p[id=p](\"x\") \"\\n\\n\\n\" script[src=a.js] \"\\n\\n\\n\"))", "body BODY head #text p BODY"), logged("<!DOCTYPE html>\n<html>\n<head>\n<meta charset=\"utf-8\">\n<link rel=\"stylesheet\" href=\"s.css\">\n</head>\n<body id=\"b1\">\n<p id=\"p\">x</p>\n<body id=\"b2\" class=\"c\">\n<html lang=\"de\">\n<script src=\"a.js\"></script>\n</body>\n</html>\n"))
    }

    @Test
    fun white_space_between_head_elements_stays_in_the_head(): TestResult = scriptTest {
        assertEquals(listOf("tree html(head(meta[charset=utf-8] \" \" style(\"p{}\") \"\\n\") body(p[id=p](\"x\") script[src=a.js]))", "body BODY head META p BODY"), logged("<meta charset=\"utf-8\"> <style>p{}</style>\n<p id=\"p\">x</p><script src=\"a.js\"></script>"))
    }

    @Test
    fun inner_html_of_an_html_element_makes_a_head_and_a_body(): TestResult = scriptTest {
        val script = """
            document.addEventListener('DOMContentLoaded', function () {
              function shape(n) {
                if (n.nodeType === 1) {
                  var s = n.tagName.toLowerCase();
                  if (n.attributes.length) s += '[' + Array.prototype.map.call(n.attributes, function (a) { return a.name + '=' + a.value; }).join(',') + ']';
                  if (n.childNodes.length) s += '(' + Array.prototype.map.call(n.childNodes, shape).join(' ') + ')';
                  return s;
                }
                return n.nodeType === 3 ? "'" + n.data + "'" : '<!--' + n.data + '-->';
              }
              console.log(['<p>x</p>', '<!--c--> <title>t</title>x<!--d-->', '<html lang=x><body class=b>y</body></html><!--e-->'].map(function (m) {
                var h = document.implementation.createHTMLDocument('').documentElement;
                h.innerHTML = m;
                return shape(h);
              }).join('\n'));
            });
        """.trimIndent()
        val console = ArrayList<String>()
        val book = ScriptBooks.chapter("<script src=\"b.js\"></script>", extraFiles = mapOf("b.js" to script))
        val runner = EpubScriptRunner(book, onConsole = { _, message -> console += message }).also { runners += it }
        runner.chapterOpened(0)
        assertEquals(emptyList(), runner.failures.map { it.message })
        assertEquals(
            listOf("html(head body(p('x')))", "html(<!--c--> head(title('t')) body('x' <!--d-->))", "html(head body[class=b]('y') <!--e-->)"),
            console.flatMap { it.lines() },
        )
    }
}
