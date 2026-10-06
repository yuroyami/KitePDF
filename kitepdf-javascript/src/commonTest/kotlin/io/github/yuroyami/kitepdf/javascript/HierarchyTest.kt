package io.github.yuroyami.kitepdf.javascript

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.test.TestResult

/**
 * An insert or a replace that would make no valid document throws a `HierarchyRequestError`, and
 * a call that takes several nodes checks them as one fragment (#604). Each expected line is what
 * headless Chromium logs for the same script.
 */
class HierarchyTest {

    private val runners = ArrayList<EpubScriptRunner>()

    @AfterTest
    fun closeRunners() {
        runners.forEach { it.close() }
    }

    private val script = """
        var out = [];
        function n(f) { try { f(); return 'ok'; } catch (e) { return e.name; } }
        var d = document.implementation.createHTMLDocument('t');
        var r = [];
        r.push(n(function () { d.appendChild(d.createTextNode('x')); }));
        r.push(n(function () { d.appendChild(d.createElement('p')); }));
        r.push(n(function () { d.appendChild(d.implementation.createDocumentType('a', '', '')); }));
        r.push(n(function () { d.insertBefore(d.implementation.createDocumentType('a', '', ''), d.documentElement); }));
        r.push(n(function () { d.appendChild(d.createComment('c')); }));
        var f = d.createDocumentFragment(); f.appendChild(d.createElement('a')); f.appendChild(d.createElement('b'));
        r.push(n(function () { d.appendChild(f); }));
        r.push(n(function () { d.body.appendChild(d.implementation.createDocumentType('a', '', '')); }));
        r.push(n(function () { d.body.appendChild(d); }));
        var e = d.implementation.createDocument(null, null);
        r.push(n(function () { e.appendChild(e.createElement('r')); }));
        r.push(n(function () { e.insertBefore(e.implementation.createDocumentType('r', '', ''), e.documentElement); }));
        r.push(n(function () { e.appendChild(e.implementation.createDocumentType('s', '', '')); }));
        r.push(n(function () { d.replaceChild(d.createElement('x'), d.doctype); }));
        r.push(n(function () { d.body.append('a', d.implementation.createDocumentType('a', '', '')); }));
        out.push('doc ' + r.join(' ') + ' ' + d.body.childNodes.length + ' ' + e.childNodes.length);
        r = [];
        d = document.implementation.createHTMLDocument('t');
        r.push(n(function () { d.replaceChildren(d.createElement('html')); }) + ':' + d.childNodes.length);
        r.push(n(function () { d.appendChild(d.documentElement); }));
        r.push(n(function () { d.insertBefore(d.documentElement, d.documentElement); }));
        r.push(n(function () { d.replaceChild(d.documentElement, d.documentElement); }));
        r.push(n(function () { d.replaceChild(d.implementation.createDocumentType('h', '', ''), d.doctype); }) + ':' + d.doctype.name);
        var b = d.body; b.innerHTML = '<i>1</i><i>2</i>';
        var i1 = b.children[0], i2 = b.children[1];
        r.push(n(function () { i2.before(i1, 'x'); }) + ':' + b.innerHTML);
        r.push(n(function () { i1.after(i1); }) + ':' + b.innerHTML);
        r.push(n(function () { i1.replaceWith(i1, 'y'); }) + ':' + b.innerHTML);
        r.push(n(function () { i2.replaceWith('z'); }) + ':' + b.innerHTML);
        r.push(n(function () { b.prepend('p', i2); }) + ':' + b.innerHTML);
        var t = d.createTextNode('t');
        r.push(n(function () { t.appendChild(d.createElement('a')); }));
        r.push(n(function () { d.body.insertBefore(d.createElement('a'), d.createElement('z')); }));
        r.push(n(function () { d.body.appendChild(d.body); }));
        r.push(n(function () { d.body.insertBefore(d.documentElement, d.createElement('z')); }));
        out.push('moves ' + r.join(' '));
        console.log(out.join('\n'));
    """.trimIndent()

    @Test
    fun a_document_takes_only_what_the_dom_allows(): TestResult = scriptTest {
        val console = ArrayList<String>()
        val book = ScriptBooks.chapter("<script src=\"a.js\"></script>", extraFiles = mapOf("a.js" to script))
        val runner = EpubScriptRunner(book, onConsole = { _, message -> console += message }).also { runners += it }
        runner.chapterOpened(0)
        assertEquals(emptyList(), runner.failures.map { it.message })
        val error = "HierarchyRequestError"
        assertEquals(
            listOf(
                "doc $error $error $error $error ok $error $error $error ok ok $error $error $error 0 2",
                "moves $error:2 $error $error ok ok:h ok:<i>1</i>x<i>2</i> ok:<i>1</i>x<i>2</i> ok:<i>1</i>yx<i>2</i> ok:<i>1</i>yxz " +
                    "ok:p<i>2</i><i>1</i>yxz $error NotFoundError $error $error",
            ),
            console.flatMap { it.lines() },
        )
    }
}
