package io.github.yuroyami.kitepdf.javascript

import io.github.yuroyami.kitepdf.core.KiteLocation
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlinx.coroutines.test.TestResult
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The objects of a chapter's DOM have the shape a browser gives them: collections that are live
 * (#542), tag names whose case follows the kind of document (#541), and an interface with its
 * class string for each node and element (#538). Each expected line is what headless Chromium
 * logs for the same chapter, opened as a file of the same media type.
 */
class DomInterfaceTest {

    private val runners = ArrayList<EpubScriptRunner>()

    @AfterTest
    fun closeRunners() {
        runners.forEach { it.close() }
    }

    /** The lines that [script] logs in a chapter whose body is [body], an XHTML chapter, or an HTML one for [html]. */
    private suspend fun logged(body: String, script: String, html: Boolean = false): List<String> {
        val console = ArrayList<String>()
        val book = ScriptBooks.chapter("$body<script src=\"dom.js\"></script>", extraFiles = mapOf("dom.js" to script), html = html)
        val runner = EpubScriptRunner(book, onConsole = { level, message -> console += "$level: $message" }).also { runners += it }
        runner.chapterOpened(0)
        assertEquals(emptyList(), runner.failures.map { it.message })
        return console.flatMap { it.removePrefix("log: ").lines() }
    }

    @Test
    fun a_collection_is_the_same_object_on_each_read_and_follows_the_tree(): TestResult = scriptTest {
        val body = """<p id="p">a<em>b</em><span class="hot">c</span></p><form id="f"><input name="q"/>""" +
            """<select id="s"><option>1</option></select></form><table id="t"><tbody><tr><td>1</td></tr></tbody></table>""" +
            """<map name="m" id="m"><area href="#a"/></map>"""
        val script = """
            var out = [];
            function log(k, v) { out.push(k + ' ' + v); }
            var p = document.getElementById('p'), form = document.getElementById('f'), sel = document.getElementById('s');
            var table = document.getElementById('t'), map = document.getElementById('m');
            function collections() {
              return [p.childNodes, p.children, document.getElementsByTagName('em'), document.getElementsByClassName('hot'),
                document.getElementsByName('q'), form.elements, sel.options, table.rows, table.rows[0].cells, table.tBodies, map.areas,
                document.forms, document.images, document.links, document.scripts];
            }
            var held = collections(), again = collections();
            log('same', held.filter(function (c, i) { return c === again[i]; }).length);
            function lengths() { return held.map(function (c) { return c.length; }).join(','); }
            log('before', lengths());
            var em = document.createElement('em'); em.className = 'hot'; p.appendChild(em);
            var input = document.createElement('input'); input.name = 'q'; form.appendChild(input);
            sel.appendChild(document.createElement('option'));
            table.insertRow(-1); table.rows[0].insertCell(-1); table.createTBody();
            var area = document.createElement('area'); area.href = '#x'; map.appendChild(area);
            document.body.appendChild(document.createElement('form'));
            document.body.appendChild(document.createElement('img'));
            var a = document.createElement('a'); a.href = '#y'; document.body.appendChild(a);
            var s = document.createElement('script'); s.type = 'text/plain'; document.body.appendChild(s);
            log('after', lengths());
            var found = document.querySelectorAll('em'), count = found.length;
            p.appendChild(document.createElement('em'));
            log('static', found.length === count && held[2].length === count + 1);
            log('kinds', [Array.isArray(held[0]), typeof held[0].map, typeof held[0].forEach, typeof held[1].forEach].join());
            log('classes', [held[0], held[1], held[5], held[6], found, form.elements.namedItem('q')].map(function (c) {
              return Object.prototype.toString.call(c).slice(8, -1);
            }).join());
            log('named', [form.elements.q.length, document.getElementsByTagName('select').s === sel, held[1][0] === p.firstElementChild,
              held[1].item(9) === null, held[1].namedItem('nothing') === null].join());
            log('missing', [typeof held[0].nope, typeof held[5].nope, typeof form.nope, typeof p.style.nope, typeof localStorage.nope,
              typeof p.dataset.nope].join());
            var kids = p.childNodes, steps = 0;
            while (kids.length && steps < 100) { p.removeChild(kids[0]); steps++; }
            log('emptied', kids.length + ' ' + steps);
            console.log(out.join('\n'));
        """.trimIndent()
        val chromium = listOf(
            "same 15",
            "before 3,2,1,1,1,2,1,1,1,1,1,1,0,1,1",
            "after 4,3,2,2,2,3,2,2,2,2,2,2,1,3,2",
            "static true",
            "kinds false,undefined,function,undefined",
            "classes NodeList,HTMLCollection,HTMLFormControlsCollection,HTMLOptionsCollection,NodeList,RadioNodeList",
            "named 2,true,true,true,true",
            "missing undefined,undefined,undefined,undefined,undefined,undefined",
            "emptied 0 5",
        )
        assertEquals(chromium, logged(body, script))
        assertEquals(chromium, logged(body, script, html = true))
    }

    @Test
    fun a_tag_name_is_uppercased_only_for_an_html_element_of_an_html_chapter(): TestResult = scriptTest {
        val body = """<p id="p">x</p><svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 1 1"><linearGradient id="g"/></svg>"""
        val script = """
            function kind(o) { return Object.prototype.toString.call(o).slice(8, -1); }
            var p = document.getElementById('p'), g = document.getElementById('g');
            var made = document.createElement('DIV'), madeNs = document.createElementNS('http://www.w3.org/1999/xhtml', 'Span');
            console.log([p.tagName, p.nodeName, g.parentNode.tagName, g.tagName, g.localName, document.body.tagName,
              made.tagName, made.localName, kind(made), madeNs.tagName, madeNs.localName, kind(madeNs), kind(document),
              document.contentType, document.compatMode, document.querySelector('linearGradient') === g].join(' '));
            var pre = document.createElementNS('http://www.w3.org/2000/svg', 's:linearGradient');
            var preHtml = document.createElementNS('http://www.w3.org/1999/xhtml', 'h:Div');
            console.log([pre.tagName, pre.prefix, pre.localName, kind(pre), preHtml.tagName, preHtml.prefix, preHtml.localName,
              preHtml.nodeName].join(' '));
        """.trimIndent()
        assertEquals(
            listOf(
                "p p svg linearGradient linearGradient body DIV DIV HTMLUnknownElement Span Span HTMLUnknownElement " +
                    "XMLDocument application/xhtml+xml CSS1Compat true",
                "s:linearGradient s linearGradient SVGLinearGradientElement h:Div h Div h:Div",
            ),
            logged(body, script),
        )
        assertEquals(
            listOf(
                "P P svg linearGradient linearGradient BODY DIV div HTMLDivElement SPAN Span HTMLUnknownElement " +
                    "HTMLDocument text/html CSS1Compat true",
                "s:linearGradient s linearGradient SVGLinearGradientElement H:DIV h Div H:DIV",
            ),
            logged(body, script, html = true),
        )
    }

    @Test
    fun each_node_and_element_has_the_class_string_of_its_interface(): TestResult = scriptTest {
        val body = """<p id="p" title="t">x</p><svg id="svg" xmlns="http://www.w3.org/2000/svg" viewBox="0 0 1 1">""" +
            """<linearGradient id="grad"/></svg><math id="math" xmlns="http://www.w3.org/1998/Math/MathML"><mi>x</mi></math>"""
        val script = """
            function kind(o) { return Object.prototype.toString.call(o).slice(8, -1); }
            var p = document.getElementById('p');
            var nodes = [document, p.firstChild, document.createDocumentFragment(), document.implementation, p.childNodes, p.children,
              p.style, p.classList, p.attributes, p.attributes[0], p.dataset, window, location, navigator, localStorage, new Event('x'),
              p.getBoundingClientRect(), document.getElementById('svg'), document.getElementById('grad'), document.getElementById('math'),
              document.createElementNS(null, 'x'), getComputedStyle(p)];
            console.log('nodes ' + nodes.map(kind).join(','));
            var tags = ['body', 'div', 'p', 'h2', 'ins', 'del', 'q', 'blockquote', 'td', 'th', 'thead', 'col', 'pre', 'span', 'em', 'section',
              'img', 'a', 'table', 'tr', 'form', 'input', 'select', 'option', 'textarea', 'ul', 'li', 'video', 'canvas', 'foo', 'x-foo', 'applet', 'font'];
            console.log('elements ' + tags.map(function (t) { return t + ':' + kind(document.createElement(t)); }).join(','));
            var illegal;
            try { new HTMLBodyElement(); illegal = 'made'; } catch (e) { illegal = e.name; }
            var d = Object.getOwnPropertyDescriptor(HTMLBodyElement, 'prototype');
            console.log('interfaces ' + [document.body instanceof HTMLBodyElement, HTMLHeadingElement.prototype instanceof HTMLElement,
              document.getElementById('svg') instanceof HTMLElement, document.getElementById('svg') instanceof SVGElement,
              d.writable, d.enumerable, d.configurable, typeof HTMLBodyElement, HTMLBodyElement.name, illegal, String(document.body),
              Object.getPrototypeOf(HTMLUnknownElement.prototype) === HTMLElement.prototype].join());
        """.trimIndent()
        val nodes = "Text,DocumentFragment,DOMImplementation,NodeList,HTMLCollection,CSSStyleDeclaration,DOMTokenList,NamedNodeMap," +
            "Attr,DOMStringMap,Window,Location,Navigator,Storage,Event,DOMRect,SVGSVGElement,SVGLinearGradientElement,MathMLElement," +
            "Element,CSSStyleDeclaration"
        val rest = listOf(
            "elements body:HTMLBodyElement,div:HTMLDivElement,p:HTMLParagraphElement,h2:HTMLHeadingElement,ins:HTMLModElement," +
                "del:HTMLModElement,q:HTMLQuoteElement,blockquote:HTMLQuoteElement,td:HTMLTableCellElement,th:HTMLTableCellElement," +
                "thead:HTMLTableSectionElement,col:HTMLTableColElement,pre:HTMLPreElement,span:HTMLSpanElement,em:HTMLElement," +
                "section:HTMLElement,img:HTMLImageElement,a:HTMLAnchorElement,table:HTMLTableElement,tr:HTMLTableRowElement," +
                "form:HTMLFormElement,input:HTMLInputElement,select:HTMLSelectElement,option:HTMLOptionElement," +
                "textarea:HTMLTextAreaElement,ul:HTMLUListElement,li:HTMLLIElement,video:HTMLVideoElement,canvas:HTMLCanvasElement," +
                "foo:HTMLUnknownElement,x-foo:HTMLElement,applet:HTMLUnknownElement,font:HTMLFontElement",
            "interfaces true,true,false,true,false,false,false,function,HTMLBodyElement,TypeError,[object HTMLBodyElement],true",
        )
        assertEquals(listOf("nodes XMLDocument,$nodes") + rest, logged(body, script))
        assertEquals(listOf("nodes HTMLDocument,$nodes") + rest, logged(body, script, html = true))
    }

    @Test
    fun a_comment_is_a_node_of_its_own_that_the_page_does_not_show(): TestResult = scriptTest {
        // The tree kept no comment, and createComment made a text node (#544).
        val body = """<div id="q">x<!-- c -->y</div><p id="w"><!--first--><span>s</span></p>"""
        val script = """
            var out = [];
            function log(k, v) { out.push(k + ' ' + v); }
            var q = document.getElementById('q'), w = document.getElementById('w');
            log('parsed', [q.childNodes.length, q.childNodes[1].nodeType, q.childNodes[1].nodeName, q.childNodes[1].data, q.innerHTML, q.textContent,
              q.children.length].join('|'));
            var c = document.createComment('made');
            log('created', [c.nodeType, c.nodeName, c.data, c.nodeValue, c.textContent, c.length, Object.prototype.toString.call(c),
              c instanceof CharacterData, c instanceof Text, c.ownerDocument === document, c.parentNode === null].join('|'));
            q.appendChild(c);
            c.appendData('!');
            log('serialized', q.innerHTML + '|' + q.outerHTML);
            c.nodeValue = 'set';
            log('nodeValue', c.data + '|' + c.cloneNode().data + '|' + String(c.previousSibling.nodeType));
            q.innerHTML = 'a<!--b-->c<p id="e"><!--d--></p>';
            log('innerHTML', [q.childNodes.length, q.childNodes[1].nodeType, q.childNodes[1].data, document.getElementById('e').firstChild.nodeType,
              q.innerHTML].join('|'));
            log('selectors', [document.querySelector('#e:empty') !== null, document.querySelector('#w > span:first-child') !== null,
              w.firstChild.nodeType, w.firstElementChild.tagName, w.childElementCount, w.childNodes.length].join('|'));
            var slot = document.createComment('slot');
            q.appendChild(slot);
            var b = document.createElement('b');
            b.textContent = 'filled';
            q.replaceChild(b, slot);
            log('replaced', q.innerHTML);
            console.log(out.join('\n'));
        """.trimIndent()
        assertEquals(
            listOf(
                "parsed 3|8|#comment| c |x<!-- c -->y|xy|0",
                "created 8|#comment|made|made|made|4|[object Comment]|true|false|true|true",
                "serialized x<!-- c -->y<!--made!-->|<div id=\"q\">x<!-- c -->y<!--made!--></div>",
                "nodeValue set|set|3",
                "innerHTML 4|8|b|8|a<!--b-->c<p id=\"e\"><!--d--></p>",
                "selectors true|true|8|SPAN|1|2",
                "replaced a<!--b-->c<p id=\"e\"><!--d--></p><b>filled</b>",
            ),
            logged(body, script, html = true),
        )
        // An XHTML chapter's markup is serialized as HTML is, where a browser gives each element
        // its xmlns, so the lines that serialize are left to the HTML chapter above.
        val xhtml = logged(body, script).filter { it.split(' ').first() in setOf("parsed", "created", "nodeValue", "selectors") }
        assertEquals(
            listOf(
                "parsed 3|8|#comment| c |x<!-- c -->y|xy|0",
                "created 8|#comment|made|made|made|4|[object Comment]|true|false|true|true",
                "nodeValue set|set|3",
                "selectors true|true|8|span|1|2",
            ),
            xhtml,
        )
        // The page shows none of them, those of the markup and those a script adds alike.
        val book = ScriptBooks.chapter(
            """<p id="q">x<!-- hidden -->y</p><p id="r">z</p><script src="dom.js"></script>""",
            extraFiles = mapOf(
                "dom.js" to """
                    var r = document.getElementById('r');
                    r.appendChild(document.createComment('late'));
                    r.insertBefore(document.createComment('early'), r.firstChild);
                    r.appendChild(document.createTextNode('!'));
                """.trimIndent(),
            ),
        )
        val runner = EpubScriptRunner(book).also { runners += it }
        runner.chapterOpened(0)
        assertEquals(emptyList(), runner.failures.map { it.message })
        val text = book.page(KiteLocation(0, 0)).textContent().plainText
        assertTrue("xy" in text && "z!" in text && "hidden" !in text && "late" !in text && "early" !in text, text)
    }
}
