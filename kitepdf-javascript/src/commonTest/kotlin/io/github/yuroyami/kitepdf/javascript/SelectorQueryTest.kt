package io.github.yuroyami.kitepdf.javascript

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * A chapter's scripts query the tree with the selectors of Selectors 4, valid and invalid as a
 * browser has them, with `:scope` as the DOM Standard gives it (#549). Each expected line is what
 * headless Chromium logs for the same chapter, opened as a file of the same media type.
 */
class SelectorQueryTest {

    private val runners = ArrayList<EpubScriptRunner>()

    @AfterTest
    fun closeRunners() {
        runners.forEach { it.close() }
    }

    private fun logged(body: String, script: String, html: Boolean): List<String> {
        val console = ArrayList<String>()
        val book = ScriptBooks.chapter("$body<script src=\"q.js\"></script>", extraFiles = mapOf("q.js" to script), html = html)
        val runner = EpubScriptRunner(book, onConsole = { level, message -> console += "$level: $message" }).also { runners += it }
        runner.chapterOpened(0)
        assertEquals(emptyList(), runner.failures.map { it.message })
        return console.flatMap { it.removePrefix("log: ").lines() }
    }

    private val body = "<h1>T</h1><div id=\"d\"><p class=\"a\">1</p><p class=\"b\">2</p><p class=\"a b\">3</p><p></p></div><ul><li>a</li><li>b</li><li>c</li></ul><span id=\"a:b\" lang=\"en\" data-x=\"A\">x</span><input type=\"checkbox\" checked=\"checked\" disabled=\"disabled\"/><input type=\"checkbox\" id=\"box\"/><select><option selected=\"selected\">o</option></select><a href=\"http://x\">l</a><svg xmlns=\"http://www.w3.org/2000/svg\" width=\"1\" height=\"1\"><linearGradient id=\"g\"/></svg>"

    private val script = """
            var out = [];
            function n(f) { try { return f(); } catch (e) { return e.name; } }
            function count(s, root) { return n(function () { return (root || document).querySelectorAll(s).length; }); }
            var sels = ['p:nth-of-type(2)', 'p:only-of-type', 'li:nth-last-child(1)', ':is(h1, h2)', ':where(.a)', 'div:has(> p)', '[data-x="a" i]',
              'p:not(.a, .b)', 'p:not(:first-child)', '#a\\:b', 'a[href^="http"]', 'input:checked', 'input:disabled', ':lang(en)', 'p::before',
              'p:nth-child(2 of .a)', 'p[', ':foo', 'p >', 'svg|rect', '*|*', 'P', 'div p:first-child + p', ':root > body', 'li:nth-child(-n+2)',
              ':enabled', 'option:checked', ':scope > p', 'p:empty', '[class~="a"]', '.a.b', 'p:nth-of-type(odd)', 'div > :last-child',
              'p:not(:not(.a))', ':is(p, :foo)', ':not(p, :foo)', 'p:hover', 'p::first-line', 'p::foo', '[class^=""]', 'p:nth-child(2n + 1)',
              'p:nth-child(+ 2n)', 'p:has(:has(b))', 'p:target', 'li:nth-last-of-type(odd)', 'ul > li:not(:last-child)', 'li:has(+ li + li)',
              ':is(ul, div) > :first-child', 'span:dir(ltr)', 'p:read-only', 'input:read-write', 'input:optional', ':default', ':any-link', 'a:link',
              'select:has(option:checked)', 'li:only-child', 'h1 ~ p', 'h1 + p.a', '*:not(li):first-child', 'linearGradient', 'lineargradient'];
            for (var i = 0; i < sels.length; i++) out.push(sels[i] + ' => ' + count(sels[i]));
            var d = document.getElementById('d'), p = d.querySelector('.b');
            out.push('scope ' + [count(':scope > p', d), count(':scope', d), d.matches(':scope'), d.closest(':scope').id, count('div p', d), count('body p', d),
              count(':scope'), n(function () { return document.querySelector(':scope').tagName.toLowerCase(); }), count(':scope > body')].join('|'));
            out.push('closest ' + [n(function () { return p.closest('div').id; }), n(function () { return p.closest(':scope > p') === null; }),
              n(function () { return p.closest('body > :scope'); }), n(function () { return p.closest('div:has(> :scope)').id; }), n(function () { return p.closest('p['); })].join('|'));
            var f = document.createDocumentFragment(), x = document.createElement('div'); x.innerHTML = '<p></p>'; f.appendChild(x);
            var lone = document.createElement('p');
            out.push('frag ' + [count(':root', f), count('* > p', f), count(':first-child', f), x.matches(':root'), lone.matches(':root'), lone.matches(':first-child'),
              lone.matches(':only-of-type'), lone.matches(':nth-child(1)')].join('|'));
            var box = document.getElementById('box');
            box.click();
            out.push('state ' + [count('input:checked'), box.checked, count('#box:checked'), count('#box:indeterminate')].join('|'));
            out.push('tags ' + [document.getElementsByTagName('linearGradient').length, document.getElementsByTagName('lineargradient').length,
              document.getElementsByTagName('LI').length, document.getElementsByTagName('li').length].join('|'));
            console.log(out.join('\n'));
    """.trimIndent()

    @Test
    fun an_xhtml_chapter_queries_as_a_browser_does() {
        assertEquals(
            listOf(
                "p:nth-of-type(2) => 1",
                "p:only-of-type => 0",
                "li:nth-last-child(1) => 1",
                ":is(h1, h2) => 1",
                ":where(.a) => 2",
                "div:has(> p) => 1",
                "[data-x=\"a\" i] => 1",
                "p:not(.a, .b) => 1",
                "p:not(:first-child) => 3",
                "#a\\:b => 1",
                "a[href^=\"http\"] => 1",
                "input:checked => 1",
                "input:disabled => 1",
                ":lang(en) => 1",
                "p::before => 0",
                "p:nth-child(2 of .a) => 1",
                "p[ => SyntaxError",
                ":foo => SyntaxError",
                "p > => SyntaxError",
                "svg|rect => SyntaxError",
                "*|* => 23",
                "P => 0",
                "div p:first-child + p => 1",
                ":root > body => 1",
                "li:nth-child(-n+2) => 2",
                ":enabled => 3",
                "option:checked => 1",
                ":scope > p => 0",
                "p:empty => 1",
                "[class~=\"a\"] => 2",
                ".a.b => 1",
                "p:nth-of-type(odd) => 2",
                "div > :last-child => 1",
                "p:not(:not(.a)) => 2",
                ":is(p, :foo) => 4",
                ":not(p, :foo) => SyntaxError",
                "p:hover => 0",
                "p::first-line => 0",
                "p::foo => SyntaxError",
                "[class^=\"\"] => 0",
                "p:nth-child(2n + 1) => 2",
                "p:nth-child(+ 2n) => SyntaxError",
                "p:has(:has(b)) => SyntaxError",
                "p:target => 0",
                "li:nth-last-of-type(odd) => 2",
                "ul > li:not(:last-child) => 2",
                "li:has(+ li + li) => 1",
                ":is(ul, div) > :first-child => 2",
                "span:dir(ltr) => 1",
                "p:read-only => 4",
                "input:read-write => 0",
                "input:optional => 2",
                ":default => 2",
                ":any-link => 1",
                "a:link => 1",
                "select:has(option:checked) => 1",
                "li:only-child => 0",
                "h1 ~ p => 0",
                "h1 + p.a => 0",
                "*:not(li):first-child => 7",
                "linearGradient => 1",
                "lineargradient => 0",
                "scope 4|0|true|d|4|4|1|html|1",
                "closest d|true||d|SyntaxError",
                "frag 0|1|2|false|false|true|true|true",
                "state 2|true|1|0",
                "tags 1|0|0|3",
            ),
            logged(body, script, html = false),
        )
    }

    @Test
    fun an_html_chapter_queries_as_a_browser_does() {
        assertEquals(
            listOf(
                "p:nth-of-type(2) => 1",
                "p:only-of-type => 0",
                "li:nth-last-child(1) => 1",
                ":is(h1, h2) => 1",
                ":where(.a) => 2",
                "div:has(> p) => 1",
                "[data-x=\"a\" i] => 1",
                "p:not(.a, .b) => 1",
                "p:not(:first-child) => 3",
                "#a\\:b => 1",
                "a[href^=\"http\"] => 1",
                "input:checked => 1",
                "input:disabled => 1",
                ":lang(en) => 1",
                "p::before => 0",
                "p:nth-child(2 of .a) => 1",
                "p[ => SyntaxError",
                ":foo => SyntaxError",
                "p > => SyntaxError",
                "svg|rect => SyntaxError",
                "*|* => 24",
                "P => 4",
                "div p:first-child + p => 1",
                ":root > body => 1",
                "li:nth-child(-n+2) => 2",
                ":enabled => 3",
                "option:checked => 1",
                ":scope > p => 0",
                "p:empty => 1",
                "[class~=\"a\"] => 2",
                ".a.b => 1",
                "p:nth-of-type(odd) => 2",
                "div > :last-child => 1",
                "p:not(:not(.a)) => 2",
                ":is(p, :foo) => 4",
                ":not(p, :foo) => SyntaxError",
                "p:hover => 0",
                "p::first-line => 0",
                "p::foo => SyntaxError",
                "[class^=\"\"] => 0",
                "p:nth-child(2n + 1) => 2",
                "p:nth-child(+ 2n) => SyntaxError",
                "p:has(:has(b)) => SyntaxError",
                "p:target => 0",
                "li:nth-last-of-type(odd) => 2",
                "ul > li:not(:last-child) => 2",
                "li:has(+ li + li) => 1",
                ":is(ul, div) > :first-child => 2",
                "span:dir(ltr) => 1",
                "p:read-only => 4",
                "input:read-write => 0",
                "input:optional => 2",
                ":default => 2",
                ":any-link => 1",
                "a:link => 1",
                "select:has(option:checked) => 1",
                "li:only-child => 0",
                "h1 ~ p => 0",
                "h1 + p.a => 0",
                "*:not(li):first-child => 7",
                "linearGradient => 1",
                "lineargradient => 1",
                "scope 4|0|true|d|4|4|1|html|1",
                "closest d|true||d|SyntaxError",
                "frag 0|1|2|false|false|true|true|true",
                "state 2|true|1|0",
                "tags 1|0|3|3",
            ),
            logged(body, script, html = true),
        )
    }

    private val caseBody = """<p id="p">x</p><input type="checkbox"/>""" +
        """<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 1 1" preserveAspectRatio="xMidYMid"><linearGradient id="g"/></svg>"""

    private val caseScript = """
        var s = ['linearGradient', 'lineargradient', 'LINEARGRADIENT', 'svg[viewBox]', 'svg[viewbox]', 'svg[VIEWBOX]', 'P', 'p[ID]',
          '[type=CHECKBOX]', 'svg[preserveAspectRatio=xmidymid]', 'svg[preserveAspectRatio=xMidYMid]'];
        var out = [];
        for (var i = 0; i < s.length; i++) out.push(s[i] + '=' + document.querySelectorAll(s[i]).length);
        var g = document.querySelector('linearGradient');
        out.push('m ' + [g.matches('lineargradient'), g.matches('LinearGradient'), document.querySelector('svg').matches('SVG')].join('|'));
        console.log(out.join('\n'));
    """.trimIndent()

    @Test
    fun an_html_chapter_compares_every_name_ignoring_case_and_an_xhtml_one_none() {
        assertEquals(
            listOf(
                "linearGradient=1", "lineargradient=1", "LINEARGRADIENT=1", "svg[viewBox]=1", "svg[viewbox]=1", "svg[VIEWBOX]=1", "P=1",
                "p[ID]=1", "[type=CHECKBOX]=1", "svg[preserveAspectRatio=xmidymid]=0", "svg[preserveAspectRatio=xMidYMid]=1", "m true|true|true",
            ),
            logged(caseBody, caseScript, html = true),
        )
        assertEquals(
            listOf(
                "linearGradient=1", "lineargradient=0", "LINEARGRADIENT=0", "svg[viewBox]=1", "svg[viewbox]=0", "svg[VIEWBOX]=0", "P=0",
                "p[ID]=0", "[type=CHECKBOX]=0", "svg[preserveAspectRatio=xmidymid]=0", "svg[preserveAspectRatio=xMidYMid]=1", "m false|false|false",
            ),
            logged(caseBody, caseScript, html = false),
        )
    }
}
