package io.github.yuroyami.kitepdf.javascript

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlinx.coroutines.test.TestResult
import kotlin.test.assertEquals

/**
 * A chapter's scripts see each attribute with the namespace, prefix and local name, and the case,
 * that the document's parser gives it or a script set it with, as the DOM Standard has them (#545):
 * an XHTML chapter names them as Namespaces in XML does and an HTML chapter as HTML's parser does.
 * Each expected line is what headless Chromium logs for the same chapter, opened as a file of the
 * same media type, but for three: Chromium still checks a name a script sets against the Name
 * production of XML, where the DOM Standard now forbids only a few characters, as web-platform-tests'
 * `name-validation.html` checks, so the lines `names`, `nserr` and `toggle` are the standard's. The
 * lines that serialize or parse markup in an XHTML chapter are left out: they are #548's, which
 * writes and parses that markup as XML.
 */
class AttributeNamesTest {

    private val runners = ArrayList<EpubScriptRunner>()

    @AfterTest
    fun closeRunners() {
        runners.forEach { it.close() }
    }

    private suspend fun logged(body: String, script: String, html: Boolean): List<String> {
        val console = ArrayList<String>()
        val book = ScriptBooks.chapter("$body<script src=\"a.js\"></script>", extraFiles = mapOf("a.js" to script), html = html)
        val runner = EpubScriptRunner(book, onConsole = { level, message -> console += "$level: $message" }).also { runners += it }
        runner.chapterOpened(0)
        assertEquals(emptyList(), runner.failures.map { it.message })
        return console.flatMap { it.removePrefix("log: ").lines() }
    }

    private val namesBody = """<p id="pid" lang="en"><a id="n" xmlns:epub="http://www.idpf.org/2007/ops" epub:type="noteref" href="#f">1</a></p><svg xmlns="http://www.w3.org/2000/svg" xmlns:xlink="http://www.w3.org/1999/xlink" id="s" viewBox="0 0 10 10"><rect id="r" width="1" height="1"/><use id="u" xlink:href="#r"/></svg><math xmlns="http://www.w3.org/1998/Math/MathML" id="m" definitionURL="u" xml:lang="fr"><mi>x</mi></math>"""

    private val namesScript = """
        var OPS = 'http://www.idpf.org/2007/ops', XLINK = 'http://www.w3.org/1999/xlink', XMLNS = 'http://www.w3.org/2000/xmlns/';
        var a = document.getElementById('n'), s = document.getElementById('s'), u = document.getElementById('u'), m = document.getElementById('m');
        var out = [];
        function n(f) { try { return f(); } catch (e) { return e.name; } }
        function list(el) { return Array.prototype.map.call(el.attributes, function (x) { return x.name + '=' + x.localName + '=' + x.prefix + '=' + x.namespaceURI + '=' + x.value; }).join(' '); }
        function q(sel) { return n(function () { return document.querySelectorAll(sel).length; }); }
        out.push('get ' + [a.getAttribute('epub:type'), a.getAttribute('type'), a.getAttributeNS(OPS, 'type'), a.hasAttribute('epub:type'),
          a.hasAttributeNS(OPS, 'type'), a.getAttributeNS(null, 'type'), a.getAttribute('EPUB:TYPE'), a.getAttributeNS('', 'href')].join('|'));
        out.push('attrs a ' + list(a));
        out.push('attrs svg ' + list(s));
        out.push('attrs use ' + list(u) + ' | ' + [u.getAttribute('xlink:href'), u.getAttributeNS(XLINK, 'href'), u.getAttribute('href')].join('|'));
        out.push('attrs math ' + list(m));
        out.push('svg ' + [s.getAttribute('viewBox'), s.getAttribute('viewbox'), s.getAttributeNames().join(',')].join('|'));
        var p = document.createElement('p');
        p.setAttribute('DATA-X', '1'); p.setAttribute('Foo', '2');
        out.push('set html ' + [p.getAttributeNames().join(','), p.getAttribute('data-x'), p.getAttribute('DATA-X'), p.hasAttribute('foo')].join('|'));
        s.setAttribute('preserveAspectRatio', 'none');
        out.push('set svg ' + [s.getAttributeNames().join(','), s.getAttribute('preserveaspectratio'), s.getAttribute('preserveAspectRatio')].join('|'));
        p.setAttributeNS(OPS, 'epub:type', 'x'); p.setAttributeNS(OPS, 'other:type', 'y');
        out.push('ns ' + [p.getAttributeNames().join(','), p.getAttributeNS(OPS, 'type'), p.getAttribute('epub:type'), p.getAttribute('other:type')].join('|'));
        p.setAttribute('epub:type', 'z');
        out.push('qualified ' + [p.getAttributeNames().join(','), p.getAttributeNS(OPS, 'type')].join('|'));
        p.removeAttributeNS(OPS, 'type');
        out.push('removed ' + p.getAttributeNames().join(','));
        out.push('names ' + [n(function () { p.setAttribute('1a', 'v'); return p.getAttribute('1a'); }), n(function () { p.setAttributeNS(OPS, 'e:\u00e9t\u00e9', ''); return 'ok'; }),
          n(function () { p.setAttribute('a:b:c', ''); return p.getAttribute('a:b:c'); }), n(function () { p.setAttributeNS(OPS, 'e:a:b', ''); return 'ok'; }),
          n(function () { return document.createAttribute('1x').name; }), n(function () { return document.createAttributeNS(OPS, 'e:1x').name; })].join('|'));
        p.removeAttribute('1a'); p.removeAttribute('a:b:c');
        out.push('nserr ' + [n(function () { p.setAttributeNS(null, 'a:b', ''); }), n(function () { p.setAttributeNS(OPS, 'xmlns', ''); }),
          n(function () { p.setAttributeNS(XMLNS, 'x', ''); }), n(function () { p.setAttributeNS('', 'xml:lang', ''); }), n(function () { p.setAttribute('a b', ''); }),
          n(function () { p.setAttributeNS(OPS, 'e:1a', ''); }), n(function () { p.setAttributeNS(OPS, ':a', ''); }), n(function () { p.setAttributeNS(XMLNS, 'xmlns:q', 'urn:q'); return p.getAttributeNS(XMLNS, 'q'); })].join('|'));
        p.toggleAttribute('HIDDEN');
        out.push('toggle ' + p.getAttributeNames().join(','));
        var at = a.getAttributeNodeNS(OPS, 'type') || a.getAttributeNode('epub:type');
        out.push('node ' + [at.name, at.localName, at.prefix, at.namespaceURI, at.value, at.ownerElement.id, a.attributes.getNamedItem('epub:type') === at,
          a.attributes.getNamedItemNS(OPS, 'type') === at, a.attributes['epub:type'] === at, a.getAttributeNode('epub:type') === at].join('|'));
        var c = document.createAttributeNS(OPS, 'epub:role'); c.value = 'v';
        var d = document.createElement('div');
        d.setAttributeNodeNS(c);
        out.push('created ' + [c.name, c.prefix, c.namespaceURI, d.getAttributeNS(OPS, 'role'), c.ownerElement === d, d.getAttributeNames().join(','), document.createAttribute('ABC').name].join('|'));
        out.push('removeitem ' + [n(function () { return a.attributes.removeNamedItemNS(OPS, 'type').name; }), n(function () { return a.attributes.removeNamedItem('epub:type').name; }),
          a.hasAttributeNS(OPS, 'type'), a.hasAttribute('epub:type'), at.ownerElement, at.value].join('|'));
        a.setAttributeNS(OPS, 'epub:type', 'noteref');
        out.push('query ' + [q('[epub\\:type]'), q('a[type]'), q('svg[viewBox]'), q('svg[viewbox]'), q('[*|type]'), q('[*|href]'), q('use[href]'),
          q('[|type]'), q('p[ID]'), q('[ID=pid]'), q('math[definitionURL]'), q('math[definitionurl]'), q('[*|lang]'), q('[lang]')].join('|'));
        out.push('outer ' + a.outerHTML);
        out.push('outer use ' + u.outerHTML);
        console.log(out.join('\n'));
    """.trimIndent()

    @Test
    fun an_xhtml_chapter_names_attributes_as_xml_does(): TestResult = scriptTest {
        assertEquals(
            listOf(
                "get noteref||noteref|true|true|||#f",
                "attrs a xmlns:epub=epub=xmlns=http://www.w3.org/2000/xmlns/=http://www.idpf.org/2007/ops id=id=null=null=n epub:type=type=epub=http://www.idpf.org/2007/ops=noteref href=href=null=null=#f",
                "attrs svg xmlns=xmlns=null=http://www.w3.org/2000/xmlns/=http://www.w3.org/2000/svg xmlns:xlink=xlink=xmlns=http://www.w3.org/2000/xmlns/=http://www.w3.org/1999/xlink id=id=null=null=s viewBox=viewBox=null=null=0 0 10 10",
                "attrs use id=id=null=null=u xlink:href=href=xlink=http://www.w3.org/1999/xlink=#r | #r|#r|",
                "attrs math xmlns=xmlns=null=http://www.w3.org/2000/xmlns/=http://www.w3.org/1998/Math/MathML id=id=null=null=m definitionURL=definitionURL=null=null=u xml:lang=lang=xml=http://www.w3.org/XML/1998/namespace=fr",
                "svg 0 0 10 10||xmlns,xmlns:xlink,id,viewBox",
                "set html DATA-X,Foo||1|false",
                "set svg xmlns,xmlns:xlink,id,viewBox,preserveAspectRatio||none",
                "ns DATA-X,Foo,epub:type|y|y|",
                "qualified DATA-X,Foo,epub:type|z",
                "removed DATA-X,Foo",
                "names v|ok||ok|1x|e:1x",
                "nserr NamespaceError|NamespaceError|NamespaceError|NamespaceError|InvalidCharacterError||InvalidCharacterError|urn:q",
                "toggle DATA-X,Foo,e:été,e:a:b,e:1a,xmlns:q,HIDDEN",
                "node epub:type|type|epub|http://www.idpf.org/2007/ops|noteref|n|true|true|true|true",
                "created epub:role|epub|http://www.idpf.org/2007/ops|v|true|epub:role|ABC",
                "removeitem epub:type|NotFoundError|false|false||noteref",
                "query 0|0|1|0|1|2|0|0|0|0|1|0|2|1",
            ),
            logged(namesBody, namesScript, html = false).filterNot { it.startsWith("outer") },
        )
    }

    @Test
    fun an_html_chapter_names_attributes_as_html_does(): TestResult = scriptTest {
        assertEquals(
            listOf(
                "get noteref|||true|false||noteref|#f",
                "attrs a id=id=null=null=n xmlns:epub=xmlns:epub=null=null=http://www.idpf.org/2007/ops epub:type=epub:type=null=null=noteref href=href=null=null=#f",
                "attrs svg xmlns=xmlns=null=http://www.w3.org/2000/xmlns/=http://www.w3.org/2000/svg xmlns:xlink=xlink=xmlns=http://www.w3.org/2000/xmlns/=http://www.w3.org/1999/xlink id=id=null=null=s viewBox=viewBox=null=null=0 0 10 10",
                "attrs use id=id=null=null=u xlink:href=href=xlink=http://www.w3.org/1999/xlink=#r | #r|#r|",
                "attrs math xmlns=xmlns=null=http://www.w3.org/2000/xmlns/=http://www.w3.org/1998/Math/MathML id=id=null=null=m definitionURL=definitionURL=null=null=u xml:lang=lang=xml=http://www.w3.org/XML/1998/namespace=fr",
                "svg 0 0 10 10||xmlns,xmlns:xlink,id,viewBox",
                "set html data-x,foo|1|1|true",
                "set svg xmlns,xmlns:xlink,id,viewBox,preserveAspectRatio||none",
                "ns data-x,foo,epub:type|y|y|",
                "qualified data-x,foo,epub:type|z",
                "removed data-x,foo",
                "names v|ok||ok|1x|e:1x",
                "nserr NamespaceError|NamespaceError|NamespaceError|NamespaceError|InvalidCharacterError||InvalidCharacterError|urn:q",
                "toggle data-x,foo,e:été,e:a:b,e:1a,xmlns:q,hidden",
                "node epub:type|epub:type|||noteref|n|true|false|true|true",
                "created epub:role|epub|http://www.idpf.org/2007/ops|v|true|epub:role|abc",
                "removeitem NotFoundError|epub:type|false|false||noteref",
                "query 0|0|1|1|1|2|0|0|1|1|1|1|2|1",
                "outer <a id=\"n\" xmlns:epub=\"http://www.idpf.org/2007/ops\" href=\"#f\" epub:type=\"noteref\">1</a>",
                "outer use <use id=\"u\" xlink:href=\"#r\"></use>",
            ),
            logged(namesBody, namesScript, html = true),
        )
    }

    private val parsedBody = """<p id="d" title="a" TITLE="b" data-Ab="1" data-cD="2" data-X="3" data-ok="4">x</p><div id="host" xmlns:epub="http://www.idpf.org/2007/ops"><span id="inner">y</span></div><svg xmlns="http://www.w3.org/2000/svg" id="s2" viewBox="0 0 1 1"><a id="sa" xmlns:xlink="http://www.w3.org/1999/xlink" xlink:href="#x" xlink:title="t"/></svg><button id="b" onClick="window.hit = (window.hit || 0) + 1">b</button>"""

    private val parsedScript = """
        var out = [];
        function n(f) { try { return f(); } catch (e) { return e.name; } }
        function list(el) { return Array.prototype.map.call(el.attributes, function (x) { return x.name + '=' + x.localName + '=' + x.prefix + '=' + x.namespaceURI + '=' + x.value; }).join(' '); }
        var d = document.getElementById('d');
        out.push('dup ' + list(d));
        out.push('dataset ' + Object.keys(d.dataset).join(','));
        var sa = document.getElementById('sa');
        out.push('xlink ' + list(sa));
        var host = document.getElementById('host');
        host.innerHTML = '<b id="frag" epub:type="note" xmlns:q="urn:q" q:a="1" Mixed="m">z</b>';
        out.push('fragment ' + n(function () { return list(document.getElementById('frag')); }));
        var holder = document.createElement('div');
        document.body.appendChild(holder);
        out.push('foreign parse ' + n(function () { holder.innerHTML = '<svg viewbox="0 0 2 2" xlink:href="#h" xml:lang="en" XMLNS:XLINK="http://www.w3.org/1999/xlink"><foo Bar="1"/></svg><math definitionurl="u"></math>'; return 'ok'; }));
        out.push('foreign svg ' + n(function () { return list(holder.firstChild); }));
        out.push('foreign foo ' + n(function () { return list(holder.firstChild.firstChild); }));
        out.push('foreign math ' + n(function () { return list(holder.lastChild); }));
        out.push('serialize ' + holder.innerHTML);
        var b = document.getElementById('b');
        b.click();
        out.push('handler ' + (window.hit || 0) + '|' + b.getAttributeNames().join(','));
        var e = document.createElement('p');
        e.setAttributeNS('urn:x', 'x:id', 'nsid');
        e.setAttribute('ID', 'upper');
        out.push('id ' + [e.id, e.getAttribute('id'), e.getAttribute('ID'), e.getAttributeNames().join(',')].join('|'));
        var q = document.createElement('i');
        q.setAttributeNS('urn:x', 'x:title', 't1');
        q.setAttribute('x:title', 't2');
        out.push('qualified ' + list(q));
        console.log(out.join('\n'));
    """.trimIndent()

    @Test
    fun an_xhtml_chapter_parses_attribute_names_as_xml_does(): TestResult = scriptTest {
        assertEquals(
            listOf(
                "dup id=id=null=null=d title=title=null=null=a TITLE=TITLE=null=null=b data-Ab=data-Ab=null=null=1 data-cD=data-cD=null=null=2 data-X=data-X=null=null=3 data-ok=data-ok=null=null=4",
                "dataset ok",
                "xlink xmlns:xlink=xlink=xmlns=http://www.w3.org/2000/xmlns/=http://www.w3.org/1999/xlink id=id=null=null=sa xlink:href=href=xlink=http://www.w3.org/1999/xlink=#x xlink:title=title=xlink=http://www.w3.org/1999/xlink=t",
                "fragment xmlns:q=q=xmlns=http://www.w3.org/2000/xmlns/=urn:q id=id=null=null=frag epub:type=type=epub=http://www.idpf.org/2007/ops=note q:a=a=q=urn:q=1 Mixed=Mixed=null=null=m",
                "handler 0|id,onClick",
                "id ||upper|x:id,ID",
                "qualified x:title=title=x=urn:x=t2",
            ),
            logged(parsedBody, parsedScript, html = false).filterNot { it.startsWith("foreign") || it.startsWith("serialize") },
        )
    }

    @Test
    fun an_html_chapter_parses_attribute_names_as_html_does(): TestResult = scriptTest {
        assertEquals(
            listOf(
                "dup id=id=null=null=d title=title=null=null=a data-ab=data-ab=null=null=1 data-cd=data-cd=null=null=2 data-x=data-x=null=null=3 data-ok=data-ok=null=null=4",
                "dataset ab,cd,x,ok",
                "xlink id=id=null=null=sa xmlns:xlink=xlink=xmlns=http://www.w3.org/2000/xmlns/=http://www.w3.org/1999/xlink xlink:href=href=xlink=http://www.w3.org/1999/xlink=#x xlink:title=title=xlink=http://www.w3.org/1999/xlink=t",
                "fragment id=id=null=null=frag epub:type=epub:type=null=null=note xmlns:q=xmlns:q=null=null=urn:q q:a=q:a=null=null=1 mixed=mixed=null=null=m",
                "foreign parse ok",
                "foreign svg viewBox=viewBox=null=null=0 0 2 2 xlink:href=href=xlink=http://www.w3.org/1999/xlink=#h xml:lang=lang=xml=http://www.w3.org/XML/1998/namespace=en xmlns:xlink=xlink=xmlns=http://www.w3.org/2000/xmlns/=http://www.w3.org/1999/xlink",
                "foreign foo bar=bar=null=null=1",
                "foreign math definitionURL=definitionURL=null=null=u",
                "serialize <svg viewBox=\"0 0 2 2\" xlink:href=\"#h\" xml:lang=\"en\" xmlns:xlink=\"http://www.w3.org/1999/xlink\"><foo bar=\"1\"></foo></svg><math definitionURL=\"u\"></math>",
                "handler 1|id,onclick",
                "id upper|upper|upper|x:id,id",
                "qualified x:title=title=x=urn:x=t2",
            ),
            logged(parsedBody, parsedScript, html = true),
        )
    }
}
