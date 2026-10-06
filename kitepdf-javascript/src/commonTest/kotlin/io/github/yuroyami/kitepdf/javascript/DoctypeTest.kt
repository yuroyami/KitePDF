package io.github.yuroyami.kitepdf.javascript

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.test.TestResult

/**
 * A script sees the document type, processing instructions and CDATA sections of its chapter, and
 * its mode (#546). Each expected line is what headless Chromium logs for the same chapter.
 */
class DoctypeTest {

    private val runners = ArrayList<EpubScriptRunner>()

    @AfterTest
    fun closeRunners() {
        runners.forEach { it.close() }
    }

    private suspend fun logged(chapter: String, script: String, html: Boolean): List<String> {
        val console = ArrayList<String>()
        val book = ScriptBooks.page(chapter, extraFiles = mapOf("a.js" to script, "b.js" to script), html = html)
        val runner = EpubScriptRunner(book, onConsole = { _, message -> console += message }).also { runners += it }
        runner.chapterOpened(0)
        assertEquals(emptyList(), runner.failures.map { it.message })
        return console.flatMap { it.lines() }
    }

    private val xhtmlScript = """
        var out = [];
        function n(f) { try { return f(); } catch (e) { return e.name; } }
        function kinds(list) { return Array.prototype.map.call(list, function (c) { return c.nodeType + ':' + c.nodeName; }).join(','); }
        var dt = document.doctype;
        out.push('doctype ' + (dt ? [dt.nodeType, dt.nodeName, dt.name, JSON.stringify(dt.publicId), JSON.stringify(dt.systemId), dt.parentNode === document, dt.nextSibling && dt.nextSibling.nodeName, dt.textContent, dt.nodeValue, Object.prototype.toString.call(dt)].join('|') : 'null'));
        out.push('children ' + document.childNodes.length + ' ' + kinds(document.childNodes));
        out.push('first ' + document.firstChild.nodeType + ' last ' + document.lastChild.nodeType);
        var pi = document.childNodes[1];
        out.push('pi ' + (pi.nodeType === 7 ? [pi.target, pi.data, pi.nodeName, pi.nodeValue, pi.textContent, pi.length].join('|') : pi.nodeType));
        var p = document.getElementById('p');
        out.push('cdata ' + p.firstChild.nodeType + '|' + p.firstChild.nodeName + '|' + p.firstChild.data + '|' + p.textContent + '|' + p.childNodes.length);
        var q = document.getElementById('q');
        out.push('q ' + kinds(q.childNodes) + '|' + q.textContent + '|' + q.innerHTML);
        out.push('mode ' + document.compatMode);
        out.push('create ' + n(function () { var x = document.createProcessingInstruction('t', 'd'); return [x.nodeType, x.target, x.data].join('|'); }) + ' ' +
          n(function () { return document.createProcessingInstruction('1t', 'd'); }) + ' ' + n(function () { return document.createProcessingInstruction('t', 'a?>b'); }) + ' ' +
          n(function () { var x = document.createCDATASection('c'); return [x.nodeType, x.data, Object.prototype.toString.call(x)].join('|'); }) + ' ' + n(function () { return document.createCDATASection('a]]>b'); }));
        out.push('impl ' + n(function () { var x = document.implementation.createDocumentType('svg:svg', '-//W3C//DTD SVG 1.1//EN', 'http://www.w3.org/Graphics/SVG/1.1/DTD/svg11.dtd'); return [x.nodeType, x.name, x.publicId, x.systemId, x.ownerDocument === document, x.parentNode].join('|'); }) + ' ' +
          n(function () { return document.implementation.createDocumentType('a b', '', ''); }) + ' ' + n(function () { return document.implementation.createDocumentType(':a', '', ''); }));
        var hd = document.implementation.createHTMLDocument('t');
        out.push('htmldoc ' + (hd.doctype ? hd.doctype.name : 'null') + ' ' + hd.compatMode + ' ' + n(function () { return hd.createCDATASection('c'); }) + ' ' + n(function () { return hd.createProcessingInstruction('t', 'd').nodeType; }));
        var xd = document.implementation.createDocument(null, 'r', document.implementation.createDocumentType('r', 'p', 's'));
        out.push('xmldoc ' + kinds(xd.childNodes) + ' ' + xd.doctype.name + ' ' + xd.compatMode + ' ' + new XMLSerializer().serializeToString(xd));
        var parsed = new DOMParser().parseFromString('<!DOCTYPE html><p>x</p>', 'text/html');
        var quirk = new DOMParser().parseFromString('<p>x</p>', 'text/html');
        out.push('parsed ' + parsed.compatMode + ' ' + (parsed.doctype && parsed.doctype.name) + ' ' + kinds(parsed.childNodes) + ' ' + quirk.compatMode + ' ' + quirk.doctype);
        dt.remove();
        out.push('removed ' + document.doctype + ' ' + document.childNodes.length + ' ' + document.compatMode);
        out.push('ser ' + new XMLSerializer().serializeToString(document).slice(0, 120));
        console.log(out.join('\n'));
    """.trimIndent()

    private val htmlScript = """
        var out = [];
        function kinds(list) { return Array.prototype.map.call(list, function (c) { return c.nodeType + ':' + c.nodeName; }).join(','); }
        var dt = document.doctype;
        out.push('doctype ' + (dt ? [dt.name, dt.publicId, dt.systemId].join('|') : 'null'));
        out.push('children ' + kinds(document.childNodes) + ' ' + document.compatMode);
        out.push('ser ' + (dt ? new XMLSerializer().serializeToString(dt) : '') + ' ' + document.documentElement.outerHTML.slice(0, 20));
        console.log(out.join('\n'));
    """.trimIndent()

    @Test
    fun an_xhtml_chapter_has_its_prologue_and_cdata_sections(): TestResult = scriptTest {
        val chapter = """
        <?xml version="1.0" encoding="UTF-8"?>
        <!DOCTYPE html>
        <?xml-stylesheet href="a.css"?>
        <html xmlns="http://www.w3.org/1999/xhtml"><head><title>Scripted</title></head><body><p id="p"><![CDATA[a<b]]></p><p id="q">x<?pi in body?>y<![CDATA[]]>z</p><script src="a.js"></script></body></html>
        """.trimIndent()
        assertEquals(
            listOf(
                "doctype 10|html|html|\"\"|\"\"|true|xml-stylesheet|||[object DocumentType]",
                "children 3 10:html,7:xml-stylesheet,1:html",
                "first 10 last 1",
                "pi xml-stylesheet|href=\"a.css\"|xml-stylesheet|href=\"a.css\"|href=\"a.css\"|12",
                "cdata 4|#cdata-section|a<b|a<b|1",
                "q 3:#text,7:pi,3:#text,4:#cdata-section,3:#text|xyz|x<?pi in body?>y<![CDATA[]]>z",
                "mode CSS1Compat",
                "create 7|t|d InvalidCharacterError InvalidCharacterError 4|c|[object CDATASection] InvalidCharacterError",
                "impl 10|svg:svg|-//W3C//DTD SVG 1.1//EN|http://www.w3.org/Graphics/SVG/1.1/DTD/svg11.dtd|true| InvalidCharacterError [object DocumentType]",
                "htmldoc html CSS1Compat NotSupportedError 7",
                "xmldoc 10:r,1:r r CSS1Compat <!DOCTYPE r PUBLIC \"p\" \"s\"><r/>",
                "parsed CSS1Compat html 10:html,1:HTML BackCompat null",
                "removed null 2 CSS1Compat",
                "ser <?xml version=\"1.0\" encoding=\"UTF-8\"?><?xml-stylesheet href=\"a.css\"?><html xmlns=\"http://www.w3.org/1999/xhtml\"><head><t",
            ),
            logged(chapter, xhtmlScript, html = false),
        )
    }

    @Test
    fun an_html_chapter_has_its_document_type_and_mode(): TestResult = scriptTest {
        val chapter = """
        <!DOCTYPE html PUBLIC "-//W3C//DTD XHTML 1.0 Strict//EN" "http://www.w3.org/TR/xhtml1/DTD/xhtml1-strict.dtd">
        <html><head><meta charset="utf-8"><title>Scripted</title></head><body><p id="p">x</p><script src="b.js"></script></body></html>
        """.trimIndent()
        assertEquals(
            listOf(
                "doctype html|-//W3C//DTD XHTML 1.0 Strict//EN|http://www.w3.org/TR/xhtml1/DTD/xhtml1-strict.dtd",
                "children 10:html,1:HTML CSS1Compat",
                "ser <!DOCTYPE html PUBLIC \"-//W3C//DTD XHTML 1.0 Strict//EN\" \"http://www.w3.org/TR/xhtml1/DTD/xhtml1-strict.dtd\"> <html><head><meta ch",
            ),
            logged(chapter, htmlScript, html = true),
        )
    }

    @Test
    fun an_html_chapter_without_a_document_type_is_in_quirks_mode(): TestResult = scriptTest {
        val chapter = """
        <html><head><meta charset="utf-8"><title>Scripted</title></head><body><p id="p">x</p><script src="b.js"></script></body></html>
        """.trimIndent()
        assertEquals(listOf("doctype null", "children 1:HTML BackCompat", "ser  <html><head><meta ch"), logged(chapter, htmlScript, html = true))
    }
}
