package io.github.yuroyami.kitepdf.javascript

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.test.TestResult

/**
 * In an XHTML chapter, `innerHTML`, `outerHTML` and `insertAdjacentHTML` read and write XML, as a
 * browser does in an XML document (#548). The expected text is what headless Chromium logs for the
 * same chapter and the same script.
 */
class InnerXmlTest {

    private val runners = ArrayList<EpubScriptRunner>()

    @AfterTest
    fun closeRunners() {
        runners.forEach { it.close() }
    }

    private val page = """
        <?xml version="1.0" encoding="UTF-8"?>
        <html xmlns="http://www.w3.org/1999/xhtml" xmlns:ep="http://www.idpf.org/2007/ops"><head><title>Scripted</title></head><body><div id="q"><p class="a">x &amp; y<br/></p><svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 1 1"><rect width="1"/></svg></div><div id="w"><p id="r">r</p><p id="s">s</p></div><script src="a.js"></script></body></html>
    """.trimIndent()

    private val script = """
        var out = [];
        function n(f) { try { return f(); } catch (e) { return e.name; } }
        function d(x) { return x ? [x.namespaceURI, x.prefix, x.localName].join(',') : 'none'; }
        function at(x) { return Array.prototype.map.call(x.attributes, function (a) { return [a.namespaceURI, a.prefix, a.localName, a.value].join(','); }).join(';'); }
        var q = document.getElementById('q');
        out.push('inner ' + q.innerHTML);
        out.push('outer ' + q.outerHTML);
        out.push('bad ' + n(function () { q.innerHTML = '<b>x</i>'; return 'ok'; }) + ' ' + q.childNodes.length);
        q.innerHTML = '<br/>a&amp;b<svg xmlns="http://www.w3.org/2000/svg"><rect/></svg>';
        out.push('set ' + q.innerHTML + ' ' + d(q.firstChild));
        out.push('prefix ' + n(function () { q.innerHTML = '<ep:x/>'; return d(q.firstChild) + '|' + q.innerHTML; }));
        out.push('entities ' + n(function () { q.innerHTML = 'a&nbsp;b'; return 'ok'; }) + ' ' + n(function () { q.innerHTML = '&lt;&#65;&#x42;&quot;&apos;'; return q.textContent; }));
        out.push('undeclared ' + n(function () { q.innerHTML = '<zz:x>t<y/></zz:x>'; return d(q.firstChild) + '|' + d(q.firstChild.lastChild) + '|' + q.innerHTML; }));
        out.push('undeclaredattr ' + n(function () { q.innerHTML = '<x zz:a="1"/>'; return 'ok'; }) + ' ' + n(function () { q.innerHTML = '<a b:c:d="1"/>'; return 'ok'; }));
        out.push('names ' + ['<a:b:c/>', '<a:/>', '<:a/>', '<xmlns:a/>'].map(function (m) { return n(function () { q.innerHTML = m; return d(q.firstChild); }); }).join('|'));
        out.push('decls ' + ['<a xmlns:p="u"><b xmlns:p=""><p:c/></b></a>', '<a xmlns="http://www.w3.org/XML/1998/namespace"><b/></a>', '<xml:a xmlns:xml="urn:x"/>', '<a xmlns:xmlns="urn:x"/>', '<p:a xmlns:p="http://www.w3.org/XML/1998/namespace"/>'].map(function (m) {
          return n(function () { q.innerHTML = m; var e = q.firstChild; while (e.firstChild) e = e.firstChild; return d(e) + '/' + at(q.firstChild); });
        }).join('|'));
        out.push('wf ' + ['<a x="1" x="2"/>', 'a < b', 'a\u0001b', '<?xml x?>', '<?xml version="1.0"?><a/>', '<!DOCTYPE a><a/>', '<a>', '</a>', '&eacute;'].map(function (m) { return n(function () { q.innerHTML = m; return 'ok'; }); }).join(','));
        out.push('kept ' + n(function () { q.innerHTML = ' <?pi x?><![CDATA[c]]><!--k-->'; return q.childNodes.length + '|' + Array.prototype.map.call(q.childNodes, function (c) { return c.nodeType; }).join('') + '|' + q.innerHTML; }));
        out.push('empty ' + n(function () { q.innerHTML = ''; return q.childNodes.length; }) + ' ' + n(function () { q.innerHTML = '\n'; return q.childNodes.length; }));
        var cases = [['urn:z', 'e'], ['urn:z', 'z:e'], ['http://www.w3.org/1999/xhtml', 'h:div'], [null, 'e'], ['http://www.w3.org/1999/xhtml', 'div']];
        out.push('detached ' + cases.map(function (c) { var e = document.createElementNS(c[0], c[1]); return n(function () { e.innerHTML = '<a/><z:b/>'; return d(e.firstChild) + '/' + d(e.lastChild) + '/' + e.innerHTML; }); }).join('|'));
        var f = document.createElementNS('urn:z', 'e'); f.setAttribute('xmlns', 'urn:w'); f.innerHTML = '<a/>';
        var g = document.createElementNS('urn:z', 'e'); document.body.appendChild(g); g.innerHTML = '<a/>';
        var g2 = document.createElementNS('urn:z', 'z:e'), holder = document.createElementNS('urn:y', 'y');
        holder.setAttributeNS('http://www.w3.org/2000/xmlns/', 'xmlns:z', 'urn:zz'); holder.appendChild(g2); g2.innerHTML = '<a/><z:b/>';
        var frag = document.createDocumentFragment(), k = document.createElementNS('urn:k', 'k'); frag.appendChild(k); k.innerHTML = '<a/>';
        out.push('scopes ' + [d(f.firstChild), d(g.firstChild), d(g2.firstChild), d(g2.lastChild), d(k.firstChild)].join('|'));
        document.body.removeChild(g);
        var r = document.getElementById('r'), w = document.getElementById('w');
        out.push('adjacent ' + n(function () { r.insertAdjacentHTML('beforeend', '<i>i</i>'); r.insertAdjacentHTML('afterbegin', '<i/>'); r.insertAdjacentHTML('beforebegin', '<!--b-->'); r.insertAdjacentHTML('afterend', 'e'); return w.innerHTML; }));
        out.push('adjacentbad ' + n(function () { r.insertAdjacentHTML('afterend', '<i>'); return 'ok'; }) + ' ' + n(function () { r.insertAdjacentHTML('nowhere', '<i/>'); return 'ok'; }) + ' ' + n(function () { document.documentElement.insertAdjacentHTML('afterend', '<i/>'); return 'ok'; }) + ' ' + n(function () { document.createElement('i').insertAdjacentHTML('afterend', '<i/>'); return 'ok'; }) + ' ' + w.childNodes.length);
        out.push('outerset ' + n(function () { document.getElementById('s').outerHTML = '<p id="t">t</p><!--c-->'; return w.innerHTML; }));
        out.push('outerbad ' + n(function () { document.getElementById('t').outerHTML = '<p>'; return 'ok'; }) + ' ' + n(function () { document.documentElement.outerHTML = '<p/>'; return 'ok'; }) + ' ' + w.childNodes.length);
        var ff = document.createDocumentFragment(), fe = document.createElementNS('urn:f', 'f'); ff.appendChild(fe);
        out.push('outerfrag ' + n(function () { fe.outerHTML = '<a/>'; return d(ff.firstChild); }));
        q.innerHTML = '';
        var x = document.createElement('i'); x.setAttribute('t', 'a<>&"\n\t\r'); x.appendChild(document.createTextNode('a<>&"\u00a0\r')); q.appendChild(x); q.appendChild(document.createComment('a--b'));
        function esc(s) { return JSON.stringify(s).replace(/\u00a0/g, '\\u00a0'); }
        out.push('escapes ' + esc(q.innerHTML) + ' ' + esc(new XMLSerializer().serializeToString(x)));
        var h = document.implementation.createHTMLDocument(''), hd = h.createElement('div'); h.body.appendChild(hd);
        hd.innerHTML = '<br/>a<svg><rect/></svg><b>x</i>';
        out.push('htmldoc ' + hd.innerHTML + ' ' + n(function () { var e = document.createElement('div'); e.innerHTML = '<b>x</i>'; return 'ok'; }));
        var xd = new DOMParser().parseFromString('<r xmlns="urn:r"><s/></r>', 'application/xml');
        out.push('xmldoc ' + n(function () { xd.documentElement.innerHTML = '<t/>&amp;'; return d(xd.documentElement.firstChild) + '|' + xd.documentElement.innerHTML + '|' + xd.documentElement.outerHTML; }));
        console.log(out.join('\n'));
    """.trimIndent()

    private val expected = """
        inner <p xmlns="http://www.w3.org/1999/xhtml" class="a">x &amp; y<br /></p><svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 1 1"><rect width="1"/></svg>
        outer <div xmlns="http://www.w3.org/1999/xhtml" id="q"><p class="a">x &amp; y<br /></p><svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 1 1"><rect width="1"/></svg></div>
        bad SyntaxError 2
        set <br xmlns="http://www.w3.org/1999/xhtml" />a&amp;b<svg xmlns="http://www.w3.org/2000/svg"><rect/></svg> http://www.w3.org/1999/xhtml,,br
        prefix http://www.idpf.org/2007/ops,ep,x|<ep:x xmlns:ep="http://www.idpf.org/2007/ops"/>
        entities SyntaxError <AB"'
        undeclared ,,zz:x|http://www.w3.org/1999/xhtml,,y|<zz:x>t<y xmlns="http://www.w3.org/1999/xhtml"></y></zz:x>
        undeclaredattr NamespaceError NamespaceError
        names http://www.w3.org/1999/xhtml,,a:b:c|http://www.w3.org/1999/xhtml,,a:|http://www.w3.org/1999/xhtml,,:a|,,xmlns:a
        decls u,p,c/http://www.w3.org/2000/xmlns/,xmlns,p,u|http://www.w3.org/1999/xhtml,,b/|http://www.w3.org/XML/1998/namespace,xml,a/|http://www.w3.org/1999/xhtml,,a/|,,p:a/
        wf SyntaxError,SyntaxError,SyntaxError,SyntaxError,SyntaxError,SyntaxError,SyntaxError,SyntaxError,SyntaxError
        kept 4|3748| <?pi x?><![CDATA[c]]><!--k-->
        empty 0 1
        detached urn:z,,a/,,z:b/<a xmlns="urn:z"/><z:b/>|,,a/urn:z,z,b/<a/><z:b xmlns:z="urn:z"/>|,,a/,,z:b/<a/><z:b/>|,,a/,,z:b/<a/><z:b/>|http://www.w3.org/1999/xhtml,,a/,,z:b/<a xmlns="http://www.w3.org/1999/xhtml"></a><z:b/>
        scopes urn:z,,a|urn:z,,a|urn:y,,a|urn:z,z,b|urn:k,,a
        adjacent <!--b--><p xmlns="http://www.w3.org/1999/xhtml" id="r"><i></i>r<i>i</i></p>e<p xmlns="http://www.w3.org/1999/xhtml" id="s">s</p>
        adjacentbad SyntaxError SyntaxError NoModificationAllowedError NoModificationAllowedError 4
        outerset <!--b--><p xmlns="http://www.w3.org/1999/xhtml" id="r"><i></i>r<i>i</i></p>e<p xmlns="http://www.w3.org/1999/xhtml" id="t">t</p><!--c-->
        outerbad SyntaxError NoModificationAllowedError 5
        outerfrag http://www.w3.org/1999/xhtml,,a
        escapes "<i xmlns=\"http://www.w3.org/1999/xhtml\" t=\"a&lt;&gt;&amp;&quot;&#10;&#9;&#13;\">a&lt;&gt;&amp;\"\u00a0\r</i><!--a--b-->" "<i xmlns=\"http://www.w3.org/1999/xhtml\" t=\"a&lt;&gt;&amp;&quot;&#10;&#9;&#13;\">a&lt;&gt;&amp;\"\u00a0\r</i>"
        htmldoc <br>a<svg><rect></rect></svg><b>x</b> SyntaxError
        xmldoc urn:r,,t|<t xmlns="urn:r"/>&amp;|<r xmlns="urn:r"><t/>&amp;</r>
    """.trimIndent()

    @Test
    fun an_xhtml_chapter_reads_and_writes_markup_as_xml(): TestResult = scriptTest {
        val console = ArrayList<String>()
        val book = ScriptBooks.page(page, extraFiles = mapOf("a.js" to script))
        val runner = EpubScriptRunner(book, onConsole = { _, message -> console += message }).also { runners += it }
        runner.chapterOpened(0)
        assertEquals(emptyList(), runner.failures.map { it.message })
        assertEquals(expected.lines(), console.flatMap { it.lines() })
    }
}
