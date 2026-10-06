package io.github.yuroyami.kitepdf.javascript

import io.github.yuroyami.kitepdf.core.KiteLocation
import io.github.yuroyami.kitepdf.core.render.RecordingCanvas
import io.github.yuroyami.kitepdf.epub.EpubDocument
import io.github.yuroyami.kitepdf.epub.EpubEmbedKind
import io.github.yuroyami.kitepdf.epub.EpubPage
import io.github.yuroyami.kitepdf.epub.EpubSettings
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.test.TestResult

/**
 * An iframe in a chapter (#528), or an object of a document (#612): the box shows its document,
 * and the document's scripts run in a window of their own, which cannot reach the chapter around it.
 * The two windows see each other as windows of another origin and talk through messages (#613). The books follow the W3C
 * EPUB tests `scr-support_iframe`, `scr-readingsystem-support_iframe`,
 * `scr-readingsystem-support_iframe_svg`, `scr-not-support_ccscript-modify-host` and
 * `scr-not-support_ccscript-modify-size`.
 */
class FrameTest {

    private val runners = ArrayList<EpubScriptRunner>()

    @AfterTest
    fun closeRunners() {
        runners.forEach { it.close() }
    }

    /** A book of one chapter, `content.xhtml`, whose body is [body], beside the frame documents [frames]. */
    private fun book(body: String, frames: Map<String, String>, head: String = ""): EpubDocument = ScriptBooks.book(
        items = listOf(ScriptBooks.Item("content.xhtml", "application/xhtml+xml", spine = true)) +
            frames.keys.map { ScriptBooks.Item(it, if (it.endsWith(".svg")) "image/svg+xml" else "application/xhtml+xml") },
        files = mapOf("content.xhtml" to ScriptBooks.xhtml(head = head, body = body)) + frames,
        settings = EpubSettings(pageWidth = 600.0, pageHeight = 800.0, margin = 20.0),
    )

    private fun open(book: EpubDocument, console: MutableList<String> = ArrayList()): EpubScriptRunner {
        val scripts = EpubScriptRunner(book, onConsole = { _, m -> console += m }).also { runners += it }
        scripts.chapterOpened(0)
        return scripts
    }

    /** The text the first page paints, its frames' included, with its white space folded. */
    private fun painted(page: EpubPage): String =
        RecordingCanvas().also { page.renderTo(it) }.calls.filterIsInstance<RecordingCanvas.Call.Glyphs>()
            .joinToString(" ") { call -> call.glyphs.joinToString("") { it.text } }
            .split(' ', '\n', '\t').filter { it.isNotEmpty() }.joinToString(" ")

    private fun EpubDocument.first(): EpubPage = page(KiteLocation(0, 0))

    private fun frameDoc(script: String, body: String): String =
        ScriptBooks.xhtml(head = "<script>$script</script>", body = body)

    @Test
    fun a_frame_shows_its_document_and_its_scripts_run(): TestResult = scriptTest {
        val book = book(
            """<iframe src="iframe_content.xhtml" width="600" style="border: solid;" title="frame"></iframe><p>After the frame.</p>""",
            mapOf(
                "iframe_content.xhtml" to frameDoc(
                    "function test_support() { document.getElementById('scripting_support').textContent = 'Scripting in an iframe works.'; }" +
                        "window.addEventListener('load', test_support);",
                    """<p id="scripting_support">No scripting in an iframe.</p>""",
                ),
            ),
        )
        // Before scripts run the frame shows its document as it is.
        assertTrue("No scripting in an iframe." in painted(book.first()), painted(book.first()))
        val scripts = open(book)
        assertEquals(emptyList(), scripts.failures.map { it.message })
        val text = painted(book.first())
        assertTrue("Scripting in an iframe works." in text, text)
        assertTrue("After the frame." in text, text)
        assertFalse("No scripting" in text, text)
    }

    @Test
    fun a_frame_has_the_reading_system_object(): TestResult = scriptTest {
        val book = book(
            """<iframe src="iframe_content.xhtml" width="600" height="600"></iframe>""",
            mapOf(
                "iframe_content.xhtml" to frameDoc(
                    "window.addEventListener('load', function () { if (navigator.epubReadingSystem !== undefined) {" +
                        " document.getElementById('fail').style.display = 'none'; document.getElementById('pass').style.display = 'block'; } });",
                    """<div id="fail" style="display: block"><p>Object missing.</p></div><div id="pass" style="display: none"><p>Object there.</p></div>""",
                ),
            ),
        )
        val scripts = open(book)
        assertEquals(emptyList(), scripts.failures.map { it.message })
        val text = painted(book.first())
        assertTrue("Object there." in text, text)
        assertFalse("Object missing." in text, text)
    }

    @Test
    fun an_svg_document_in_a_frame_runs_its_scripts(): TestResult = scriptTest {
        val svg = """<svg version="1.1" viewBox="0 0 300 200" xmlns="http://www.w3.org/2000/svg"><script>//<![CDATA[
            window.addEventListener('load', function () {
              document.getElementById('middle').replaceChildren(document.createTextNode('Scripts run in SVG.'));
              if (navigator.epubReadingSystem !== undefined) document.getElementById('ers').style.display = 'block';
            });
            //]]></script>
            <rect width="100%" height="100%" fill="white"/>
            <text x="150" y="50" id="middle" font-size="10" text-anchor="middle">No scripts in SVG.</text>
            <text x="150" y="100" id="ers" font-size="10" text-anchor="middle" style="display:none">Reading system there.</text>
            </svg>"""
        val book = book("""<iframe src="iframe_content.svg" width="800" height="800"></iframe>""", mapOf("iframe_content.svg" to svg))
        assertTrue("No scripts in SVG." in painted(book.first()), painted(book.first()))
        val scripts = open(book)
        assertEquals(emptyList(), scripts.failures.map { it.message })
        val text = painted(book.first())
        assertTrue("Scripts run in SVG." in text, text)
        assertTrue("Reading system there." in text, text)
    }

    @Test
    fun a_frame_cannot_change_the_chapter_around_it(): TestResult = scriptTest {
        val book = book(
            """<iframe src="iframe_content.xhtml" width="500" height="70"></iframe><p id="modified_content">The chapter keeps this text.</p>""",
            mapOf(
                "iframe_content.xhtml" to frameDoc(
                    "window.addEventListener('load', function () {" +
                        " document.getElementById('scripting_support').textContent = 'The frame ran.';" +
                        " window.parent.document.getElementById('modified_content').textContent = 'The frame changed the chapter.'; });",
                    """<p id="scripting_support">The frame did not run.</p>""",
                ),
            ),
        )
        val scripts = open(book)
        val text = painted(book.first())
        assertTrue("The frame ran." in text, text)
        assertTrue("The chapter keeps this text." in text, text)
        // The frame's parent is a window of another origin, whose document it cannot read.
        assertEquals(1, scripts.failures.size, scripts.failures.map { it.message }.toString())
        assertTrue("frame OEBPS/iframe_content.xhtml" in scripts.failures.single().message.orEmpty(), scripts.failures.single().message)
    }

    @Test
    fun a_frame_cannot_change_its_size(): TestResult = scriptTest {
        val book = book(
            """<iframe id="the_frame" src="iframe_content.xhtml" width="500" height="70"></iframe>""",
            mapOf(
                "iframe_content.xhtml" to frameDoc(
                    "window.addEventListener('load', function () { var f = window.parent.document.getElementById('the_frame'); f.width = 200; f.height = 200; });",
                    """<p>Frame.</p>""",
                ),
            ),
        )
        open(book)
        val frame = book.first().embeds.single { it.kind == EpubEmbedKind.FRAME }
        assertEquals(375.0, frame.rect.width, 0.01)
        assertEquals(52.5, frame.rect.height, 0.01)
    }

    @Test
    fun a_frame_is_a_window_of_its_own_with_the_books_origin(): TestResult = scriptTest {
        val console = ArrayList<String>()
        val book = book(
            """<iframe src="sub/frame.xhtml"></iframe><script>window.addEventListener('load', function () { console.log('chapter load ' + location.origin); });</script>""",
            mapOf(
                "sub/frame.xhtml" to frameDoc(
                    "console.log('frame ' + (parent === window) + ' ' + (top === window) + ' ' + window.frameElement + ' ' + location.pathname);" +
                        "window.addEventListener('load', function () { console.log('frame load ' + location.origin + ' ' + innerWidth + 'x' + innerHeight); });",
                    "<p>Frame.</p>",
                ),
            ),
        )
        open(book, console)
        assertEquals(3, console.size, console.toString())
        assertEquals("frame false false null /OEBPS/sub/frame.xhtml", console[0])
        // A frame's document loads before the load event of the chapter around it, at the frame's own size.
        val frameOrigin = console[1].removePrefix("frame load ").substringBefore(' ')
        assertEquals("300x150", console[1].substringAfterLast(' '))
        assertEquals("chapter load $frameOrigin", console[2])
    }

    @Test
    fun a_tap_on_a_frame_goes_to_its_document(): TestResult = scriptTest {
        val console = ArrayList<String>()
        val book = book(
            """<p>Before.</p><iframe src="frame.xhtml" width="200" height="100"></iframe>""" +
                """<script>document.addEventListener('click', function () { console.log('chapter click'); });</script>""",
            mapOf(
                "frame.xhtml" to frameDoc(
                    "document.addEventListener('click', function (e) { console.log('frame click ' + e.target.id + ' ' + Math.round(e.clientX) + ',' + Math.round(e.clientY)); });",
                    """<div id="in" style="height: 100px">Inside.</div>""",
                ),
            ),
        )
        val scripts = open(book, console)
        val rect = book.first().embeds.single().rect
        scripts.tap(book.first(), rect.left + 15.0, rect.bottom + 30.0)
        assertEquals(listOf("frame click in 20,40"), console)
        scripts.tap(book.first(), rect.left - 5.0, rect.bottom - 5.0)
        assertEquals(listOf("frame click in 20,40", "chapter click"), console)
    }

    @Test
    fun a_frames_timers_run(): TestResult = scriptTest {
        var now = 0L
        val book = book(
            """<iframe src="frame.xhtml"></iframe>""",
            mapOf("frame.xhtml" to frameDoc("setTimeout(function () { document.getElementById('t').textContent = 'Later.'; }, 100);", """<p id="t">Now.</p>""")),
        )
        val scripts = EpubScriptRunner(book, clock = { now }).also { runners += it }
        scripts.chapterOpened(0)
        assertTrue(scripts.hasTimers)
        assertTrue("Now." in painted(book.first()))
        now = 200
        scripts.pumpTimers(now)
        assertTrue("Later." in painted(book.first()), painted(book.first()))
        assertFalse(scripts.hasTimers)
    }

    @Test
    fun an_object_shows_its_document_of_the_book_in_place_of_its_fallback(): TestResult = scriptTest {
        val book = book(
            """<object data="widget.xhtml" type="application/xhtml+xml" width="300" height="100"><p>Fallback words.</p></object>""" +
                """<object data="gone.xhtml" type="application/xhtml+xml"><p>Missing fallback.</p></object>""",
            mapOf(
                "widget.xhtml" to frameDoc(
                    "window.addEventListener('load', function () { document.getElementById('w').textContent = 'Widget ran.'; });",
                    """<p id="w">Widget.</p>""",
                ),
            ),
        )
        val before = painted(book.first())
        assertTrue("Widget." in before && "Fallback words." !in before, before)
        assertTrue("Missing fallback." in before, "an object whose document is not in the book shows its fallback: $before")
        assertTrue(book.isScripted(0), "the object's document has a script")
        val scripts = open(book)
        assertEquals(emptyList(), scripts.failures.map { it.message })
        val text = painted(book.first())
        assertTrue("Widget ran." in text, text)
        val widget = book.first().embeds.first { it.kind == EpubEmbedKind.OBJECT }
        assertEquals(225.0, widget.rect.width, 0.01)
        assertEquals(75.0, widget.rect.height, 0.01)
    }

    @Test
    fun a_frame_outside_the_book_shows_nothing(): TestResult = scriptTest {
        val book = book(
            """<iframe src="https://example.com/page.html"></iframe><iframe src="file:///etc/hosts"></iframe><iframe src="missing.xhtml"></iframe><p>Text.</p>""",
            emptyMap(),
        )
        val scripts = open(book)
        assertEquals(emptyList(), scripts.failures.map { it.message })
        assertEquals("Text.", painted(book.first()))
    }

    @Test
    fun a_chapter_and_its_frame_post_messages_to_each_other(): TestResult = scriptTest {
        val console = ArrayList<String>()
        val book = book(
            """<iframe id="f" src="frame.xhtml"></iframe><script>var f = document.getElementById('f');""" +
                """window.addEventListener('message', function (e) { console.log('chapter got ' + e.data.n + ' ' + (e.source === f.contentWindow) + ' ' + (e.origin === location.origin)); });""" +
                """f.addEventListener('load', function () { f.contentWindow.postMessage({ n: 1, when: new Date(5), bytes: new Uint8Array([1, 2, 255]) }, '*'); });</script>""",
            mapOf(
                "frame.xhtml" to frameDoc(
                    "window.addEventListener('message', function (e) {" +
                        " console.log('frame got ' + e.data.n + ' ' + e.data.when.getTime() + ' ' + Array.prototype.join.call(e.data.bytes, ',') + ' ' + (e.source === parent));" +
                        " e.source.postMessage({ n: e.data.n + 1 }, '*'); });",
                    "<p>Frame.</p>",
                ),
            ),
        )
        val scripts = open(book, console)
        assertEquals(emptyList(), scripts.failures.map { it.message })
        assertEquals(listOf("frame got 1 5 1,2,255 true", "chapter got 2 true true"), console)
    }

    @Test
    fun a_port_sent_to_a_frame_stays_entangled_with_its_partner(): TestResult = scriptTest {
        val console = ArrayList<String>()
        val book = book(
            """<iframe id="f" src="frame.xhtml"></iframe><script>var f = document.getElementById('f'), c = new MessageChannel();""" +
                """c.port1.onmessage = function (e) { console.log('chapter port ' + e.data); };""" +
                """window.addEventListener('message', function (e) { var back = e.ports[0]; back.onmessage = function (m) { console.log('back ' + m.data); }; c.port1.postMessage('local'); });""" +
                """f.addEventListener('load', function () { c.port1.postMessage('early'); f.contentWindow.postMessage('take', '*', [c.port2]); c.port1.postMessage('ping'); });</script>""",
            mapOf(
                "frame.xhtml" to frameDoc(
                    "var port, seen = 0; window.addEventListener('message', function (e) { port = e.ports[0];" +
                        " port.onmessage = function (m) { console.log('frame port ' + m.data); port.postMessage('pong ' + m.data);" +
                        " if (++seen === 2) parent.postMessage('give', '*', [port]); }; });",
                    "<p>Frame.</p>",
                ),
            ),
        )
        val scripts = open(book, console)
        // A task that a task queued runs at the next pump, as the viewer calls it.
        repeat(10) { if (scripts.hasTimers) scripts.pumpTimers(0) }
        assertFalse(scripts.hasTimers)
        assertEquals(emptyList(), scripts.failures.map { it.message })
        // The port went back to the chapter, so it and its partner are in one window again.
        assertEquals(
            listOf("frame port early", "frame port ping", "chapter port pong early", "chapter port pong ping", "back local"),
            console,
        )
    }

    @Test
    fun a_frame_is_a_window_of_another_origin(): TestResult = scriptTest {
        val console = ArrayList<String>()
        val book = book(
            """<iframe id="f" src="frame.xhtml"></iframe><script>var f = document.getElementById('f'), w = f.contentWindow;""" +
                """function name(fn) { try { fn(); return 'none'; } catch (e) { return e.name; } }""" +
                """console.log('chapter ' + name(function () { return w.document; }) + ' ' + name(function () { w.foo = 1; }) + ' ' + f.contentDocument + ' ' +""" +
                """ (w.parent === window) + ' ' + (w.top === window) + ' ' + (w.window === w) + ' ' + window.length + ' ' + w.closed + ' ' + typeof w.postMessage + ' ' +""" +
                """ name(function () { var s = new ReadableStream(); w.postMessage(s, '*', [s]); }) + ' ' + (f.contentWindow === w));</script>""",
            mapOf(
                "frame.xhtml" to frameDoc(
                    "function name(fn) { try { fn(); return 'none'; } catch (e) { return e.name; } }" +
                        "console.log('frame ' + name(function () { return parent.document; }) + ' ' + (top === parent) + ' ' + parent.length + ' ' + window.length + ' ' + (parent.parent === parent));",
                    "<p>Frame.</p>",
                ),
            ),
        )
        val scripts = open(book, console)
        assertEquals(emptyList(), scripts.failures.map { it.message })
        assertEquals(
            listOf("chapter SecurityError SecurityError null true true true 1 false function DataCloneError true", "frame SecurityError true 1 0 true"),
            console,
        )
    }

    @Test
    fun a_frame_that_a_script_adds_loads_and_runs(): TestResult = scriptTest {
        val console = ArrayList<String>()
        val book = book(
            """<p>Chapter.</p><script>window.addEventListener('load', function () { var f = document.createElement('iframe');""" +
                """ f.onload = function () { console.log('added loaded ' + (f.contentWindow !== null)); f.contentWindow.postMessage('hello', '*'); };""" +
                """ f.src = 'frame.xhtml'; document.body.appendChild(f); console.log('appended ' + (f.contentWindow !== null)); });</script>""",
            mapOf(
                "frame.xhtml" to frameDoc(
                    "console.log('frame ran'); window.addEventListener('message', function (e) { document.getElementById('t').textContent = 'Frame got ' + e.data + '.'; });",
                    """<p id="t">Frame waits.</p>""",
                ),
            ),
        )
        val scripts = open(book, console)
        assertEquals(emptyList(), scripts.failures.map { it.message })
        assertEquals(listOf("appended true", "frame ran", "added loaded true"), console)
        val text = painted(book.first())
        assertTrue("Frame got hello." in text, text)
    }
}
