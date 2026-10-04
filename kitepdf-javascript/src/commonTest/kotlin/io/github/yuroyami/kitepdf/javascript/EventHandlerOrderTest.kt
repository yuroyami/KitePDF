package io.github.yuroyami.kitepdf.javascript

import io.github.yuroyami.kitepdf.epub.EpubDocument
import io.github.yuroyami.kitepdf.epub.EpubSettings
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * An event handler is an entry of its target's list of event listeners (HTML, 8.1.8.1), at the
 * place it took when it was first set: setting it again keeps the place, setting it to null takes
 * it out, and a handler from markup takes its place when the parser reaches its element, so a
 * script before the element adds its listeners ahead of it (#539).
 */
class EventHandlerOrderTest {

    private val runners = ArrayList<EpubScriptRunner>()

    @AfterTest
    fun closeRunners() {
        runners.forEach { it.close() }
    }

    /** What the chapter of [book] logs, its tasks run through. */
    private fun logOf(book: EpubDocument): List<String> {
        val console = ArrayList<String>()
        val scripts = EpubScriptRunner(book, onConsole = { level, message -> console.add("$level: $message") }).also { runners += it }
        scripts.chapterOpened(0)
        var rounds = 0
        while (scripts.hasTimers && rounds++ < 20) scripts.pumpTimers(0L)
        assertEquals(emptyList(), scripts.failures.map { it.message })
        return console.map { it.removePrefix("log: ") }
    }

    /** A chapter whose head holds [head] and whose body holds [body], with [bodyAttrs] on its body. */
    private fun page(head: String = "", body: String, bodyAttrs: String = ""): EpubDocument = ScriptBooks.book(
        items = listOf(ScriptBooks.Item("chapter.xhtml", "application/xhtml+xml", properties = "scripted", spine = true)),
        files = mapOf(
            "chapter.xhtml" to ScriptBooks.xhtml(head = head, body = body).replace("<body>", "<body$bodyAttrs>"),
        ),
        settings = EpubSettings(pageWidth = 300.0, pageHeight = 200.0, margin = 20.0),
    )

    /** A chapter that runs [script] after a paragraph `p`, which holds `log(...)` to log with. */
    private fun scripted(script: String, p: String = """<p id="p">Order.</p>"""): List<String> =
        logOf(page(body = "$p<script>var out = []; function log(s) { out.push(s); } var p = document.getElementById('p');\n$script\nconsole.log(out.join(', '));</script>"))

    @Test
    fun a_handler_set_after_a_listener_runs_after_it() {
        assertEquals(
            listOf("listener 1, handler, listener 2"),
            scripted(
                """
                p.addEventListener('click', function () { log('listener 1'); });
                p.onclick = function () { log('handler'); };
                p.addEventListener('click', function () { log('listener 2'); });
                p.click();
                """,
            ),
        )
    }

    @Test
    fun setting_a_handler_again_keeps_its_place_and_null_takes_it_out() {
        assertEquals(
            listOf("b, listener | listener | listener, c"),
            scripted(
                """
                p.onclick = function () { log('a'); };
                p.addEventListener('click', function () { log('listener'); });
                p.onclick = function () { log('b'); };
                p.click(); log('|');
                p.onclick = null;
                p.click(); log('|');
                p.onclick = function () { log('c'); };
                p.click();
                """,
            ).map { it.replace(", |, ", " | ") },
        )
    }

    @Test
    fun a_listener_that_stops_the_event_now_keeps_a_later_handler_from_running() {
        assertEquals(
            listOf("listener"),
            scripted(
                """
                p.addEventListener('click', function (e) { log('listener'); e.stopImmediatePropagation(); });
                p.onclick = function () { log('handler'); };
                p.click();
                """,
            ),
        )
    }

    @Test
    fun a_handler_from_set_attribute_takes_its_place_when_set_and_leaves_when_removed() {
        assertEquals(
            listOf("listener, attribute | listener | listener, again"),
            scripted(
                """
                p.addEventListener('click', function () { log('listener'); });
                p.setAttribute('onclick', "log('attribute')");
                p.click(); log('|');
                p.removeAttribute('onclick');
                p.click(); log('|');
                p.setAttribute('onclick', "log('again')");
                p.click();
                """,
            ).map { it.replace(", |, ", " | ") },
        )
    }

    @Test
    fun a_handler_from_markup_comes_before_the_listeners_of_a_later_script() {
        assertEquals(
            listOf("markup, listener"),
            scripted(
                """
                p.addEventListener('click', function () { log('listener'); });
                p.click();
                """,
                p = """<p id="p" onclick="log('markup')">Order.</p>""",
            ),
        )
    }

    @Test
    fun a_script_before_the_body_adds_its_load_listener_ahead_of_the_body_handler() {
        assertEquals(
            listOf("head listener", "handler", "body listener"),
            logOf(
                page(
                    head = "<script>addEventListener('load', function () { console.log('head listener'); });</script>",
                    body = "<p>Order.</p><script>addEventListener('load', function () { console.log('body listener'); });</script>",
                    bodyAttrs = """ onload="console.log('handler')"""",
                ),
            ),
        )
    }

    @Test
    fun the_body_handlers_of_the_window_are_the_window_handlers() {
        assertEquals(
            listOf("true,true,false", "window"),
            logOf(
                page(
                    body = "<p>Order.</p><script>" +
                        "var f = function () { console.log('window'); }; document.body.onload = f;" +
                        " console.log([window.onload === f, document.body.onload === window.onload, document.body.hasOwnProperty('onload')].join());" +
                        "</script>",
                ),
            ),
        )
    }

    @Test
    fun a_file_reader_runs_its_handler_after_the_listener_added_before_it() {
        assertEquals(
            listOf("listener, handler"),
            logOf(
                page(
                    body = """<p>Order.</p><script>
                    var out = [], reader = new FileReader();
                    reader.addEventListener('loadend', function () { out.push('listener'); });
                    reader.onloadend = function () { out.push('handler'); console.log(out.join(', ')); };
                    reader.readAsText(new Blob(['x']));
                    </script>""",
                ),
            ),
        )
    }

    @Test
    fun the_document_has_a_ready_state_handler() {
        assertEquals(
            listOf("interactive", "complete"),
            logOf(page(body = "<p>Order.</p><script>document.onreadystatechange = function () { console.log(document.readyState); };</script>")),
        )
    }

    @Test
    fun a_handler_from_markup_sees_the_form_of_its_control_and_a_window_handler_sees_no_document() {
        // HTML compiles an element's handler in the scopes of the document, the form owner and the
        // element, and a window's handler in the global scope alone.
        assertEquals(
            listOf("function,function,true", "undefined"),
            logOf(
                page(
                    body = """<form><button id="b" type="button" onclick="console.log([typeof reset, typeof getElementById, this.form === form].join())">B</button></form>""" +
                        "<script>var form = document.querySelector('form'); document.getElementById('b').click();</script>",
                    bodyAttrs = """ onload="console.log(typeof getElementById)"""",
                ),
            ),
        )
    }

    @Test
    fun an_object_is_a_handler_value_and_anything_else_is_null() {
        assertEquals(
            listOf("true,null,null", ""),
            scripted(
                """
                var o = { handleEvent: function () { log('never'); } };
                p.onclick = o; var kept = p.onclick === o; p.click();
                p.onclick = 5; var five = p.onclick;
                p.onclick = 'log(1)';
                console.log([kept, String(five), String(p.onclick)].join());
                """,
            ),
        )
    }
}
