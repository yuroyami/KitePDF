package io.github.yuroyami.kitepdf.javascript

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlinx.coroutines.test.TestResult
import kotlin.test.assertEquals

/**
 * A chapter's scripts parse markup with `DOMParser` and write a node as XML with `XMLSerializer`
 * (#543). Each expected line is what headless Chromium logs for the same script in an XHTML page.
 */
class DomParserTest {

    private val runners = ArrayList<EpubScriptRunner>()

    @AfterTest
    fun closeRunners() {
        runners.forEach { it.close() }
    }

    private fun logged(body: String, script: String): List<String> {
        val console = ArrayList<String>()
        val book = ScriptBooks.chapter("$body<script src=\"a.js\"></script>", extraFiles = mapOf("a.js" to script))
        val runner = EpubScriptRunner(book, onConsole = { level, message -> console += "$level: $message" }).also { runners += it }
        runner.chapterOpened(0)
        assertEquals(emptyList(), runner.failures.map { it.message })
        return console.flatMap { it.removePrefix("log: ").lines() }
    }

    private val script = """
        var out = [];
        function n(f) { try { return f(); } catch (e) { return e.name; } }
        var p = new DOMParser();
        var x = p.parseFromString('<?xml version="1.0"?>\n<!DOCTYPE r SYSTEM "r.dtd">\n<?pi data here?>\n<r xmlns="urn:r" xmlns:q="urn:q" a="1" q:b="2"><q:c>t &amp; <![CDATA[a<b]]></q:c><!--c--><e/></r>', 'application/xml');
        out.push('xml ' + [Object.prototype.toString.call(x), x.contentType, x.childNodes.length, Array.prototype.map.call(x.childNodes, function (c) { return c.nodeType + ':' + c.nodeName; }).join(',')].join('|'));
        var r = x.documentElement;
        out.push('root ' + [r.namespaceURI, r.localName, r.getAttribute('a'), r.getAttributeNS('urn:q', 'b'), r.firstChild.namespaceURI, r.firstChild.prefix, r.firstChild.localName, r.firstChild.textContent, r.firstChild.childNodes.length, r.firstChild.lastChild.nodeType, r.firstChild.lastChild.data].join('|'));
        out.push('doctype ' + [x.doctype.name, x.doctype.publicId, x.doctype.systemId, x.doctype.nodeType, x.doctype.textContent, x.childNodes[1].target, x.childNodes[1].data, Object.prototype.toString.call(x.childNodes[1]), Object.prototype.toString.call(r.firstChild.lastChild)].join('|'));
        out.push('ser ' + new XMLSerializer().serializeToString(x));
        var h = p.parseFromString('<title>T</title><p id="a">x<b>y</b></p>', 'text/html');
        out.push('html ' + [Object.prototype.toString.call(h), h.contentType, h.documentElement.nodeName, h.head.firstChild.nodeName, h.title, h.body.firstChild.id, h.body.innerHTML, h.getElementById('a').ownerDocument === h].join('|'));
        var bad = p.parseFromString('<a><b></a>', 'text/xml');
        out.push('bad ' + bad.getElementsByTagName('parsererror').length);
        out.push('type ' + n(function () { return p.parseFromString('x', 'text/plain'); }));
        var moved = document.importNode(x.documentElement.lastChild, true);
        document.body.appendChild(moved);
        out.push('import ' + [moved.namespaceURI, moved.ownerDocument === document, document.body.lastChild === moved].join('|'));
        var adopted = document.adoptNode(h.getElementById('a'));
        out.push('adopt ' + [adopted.ownerDocument === document, h.getElementById('a'), adopted.textContent, h.body.childNodes.length].join('|'));
        out.push('ser2 ' + [new XMLSerializer().serializeToString(h.body), new XMLSerializer().serializeToString(document.createElement('br')), new XMLSerializer().serializeToString(x.createElementNS('urn:z', 'z:y')), new XMLSerializer().serializeToString(document.getElementById('q'))].join('|'));
        var ents = p.parseFromString('<!DOCTYPE html PUBLIC "-//W3C//DTD XHTML 1.1//EN" "http://www.w3.org/TR/xhtml11/DTD/xhtml11.dtd"><html xmlns="http://www.w3.org/1999/xhtml"><body>a&nbsp;b&eacute;</body></html>', 'application/xhtml+xml');
        out.push('ents ' + [ents.getElementsByTagName('parsererror').length, JSON.stringify(ents.documentElement.textContent)].join('|'));
        var noent = p.parseFromString('<r>a&nbsp;b</r>', 'application/xml');
        out.push('noent ' + noent.getElementsByTagName('parsererror').length);
        var decl = p.parseFromString('<!DOCTYPE r [<!ENTITY me "M&#233;">]><r a="&me;">&me;</r>', 'text/xml');
        out.push('decl ' + [decl.getElementsByTagName('parsererror').length, decl.documentElement.getAttribute('a'), decl.documentElement.textContent].join('|'));
        var errs = ['<r>', '<r></r><s/>', 'text<r/>', '<r a="1" a="2"/>', '<p:r/>', '<r>&#0;</r>', '<r><!-- a -- b --></r>', '<r a=1/>', '', '<r xmlns:p=""/>', '<?xml version="1.0"?><?xml x?><r/>', '<r>]]></r>'];
        out.push('errs ' + errs.map(function (s) { return p.parseFromString(s, 'application/xml').getElementsByTagName('parsererror').length; }).join(''));
        var svg = p.parseFromString('<svg xmlns="http://www.w3.org/2000/svg"><rect width="1"/></svg>', 'image/svg+xml');
        out.push('svg ' + [Object.prototype.toString.call(svg), Object.prototype.toString.call(svg.documentElement), svg.documentElement.firstChild.getAttribute('width')].join('|'));
        console.log(out.join('\n'));
    """

    @Test
    fun a_script_parses_and_serializes_markup_as_chromium_does(): TestResult = scriptTest {
        assertEquals(
            listOf(
                "xml [object XMLDocument]|application/xml|3|10:r,7:pi,1:r",
                "root urn:r|r|1|2|urn:q|q|c|t & a<b|2|4|a<b",
                "doctype r||r.dtd|10||pi|data here|[object ProcessingInstruction]|[object CDATASection]",
                "ser <?xml version=\"1.0\"?><!DOCTYPE r SYSTEM \"r.dtd\"><?pi data here?><r xmlns=\"urn:r\" xmlns:q=\"urn:q\" a=\"1\" q:b=\"2\"><q:c>t &amp; <![CDATA[a<b]]></q:c><!--c--><e/></r>",
                "html [object HTMLDocument]|text/html|HTML|TITLE|T|a|<p id=\"a\">x<b>y</b></p>|true",
                "bad 1",
                "type TypeError",
                "import urn:r|true|true",
                "adopt true||xy|0",
                "ser2 <body xmlns=\"http://www.w3.org/1999/xhtml\"></body>|<br xmlns=\"http://www.w3.org/1999/xhtml\" />|<z:y xmlns:z=\"urn:z\"/>|<p xmlns=\"http://www.w3.org/1999/xhtml\" id=\"q\">x</p>",
                "ents 0|\"a bé\"",
                "noent 1",
                "decl 0|Mé|Mé",
                "errs 111111111111",
                "svg [object XMLDocument]|[object SVGSVGElement]|1",
            ),
            logged("<p id=\"q\">x</p>", script),
        )
    }
}
