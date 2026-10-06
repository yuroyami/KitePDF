package io.github.yuroyami.kitepdf.javascript

import io.github.yuroyami.kitepdf.core.KiteLocation
import io.github.yuroyami.kitepdf.core.render.RecordingCanvas
import io.github.yuroyami.kitepdf.core.script.KiteScriptEngine
import io.github.yuroyami.kitepdf.epub.EpubDocument
import io.github.yuroyami.kitepdf.epub.EpubPage
import io.github.yuroyami.kitepdf.epub.EpubScriptSession
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlinx.coroutines.test.TestResult
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * A book's scripts run on KiteJS over the library's own parse and layout (#41): a tap reaches the
 * element under it, and what a script changes is laid out and painted again.
 */
class EpubScriptTest {

    private val runners = ArrayList<EpubScriptRunner>()

    @AfterTest
    fun closeRunners() {
        runners.forEach { it.close() }
    }

    private fun runner(
        book: EpubDocument,
        policy: EpubScriptPolicy = EpubScriptPolicy(),
        console: MutableList<String>? = null,
        clock: (() -> Long)? = null,
    ): EpubScriptRunner =
        EpubScriptRunner(book, policy, onConsole = { level, message -> console?.add("$level: $message") }, clock = clock).also { runners += it }

    private fun fills(page: EpubPage) = RecordingCanvas().also { page.renderTo(it) }.calls.filterIsInstance<RecordingCanvas.Call.Fill>()

    private fun EpubPage.paintsRed() = fills(this).any { it.color.r > 0.9 && it.color.g < 0.1 && it.color.b < 0.1 }
    private fun EpubPage.paintsBlue() = fills(this).any { it.color.b > 0.9 && it.color.r < 0.1 && it.color.g < 0.1 }

    /** [engine], which reports to [onClose] when it closes. */
    private class ClosingEngine(private val engine: KiteScriptEngine, private val onClose: () -> Unit) : KiteScriptEngine by engine {
        override fun close() {
            engine.close()
            onClose()
        }
    }

    /** The middle of the first line of [page]'s text that holds [text], in display space. */
    private fun EpubPage.centreOf(text: String): Pair<Double, Double> {
        val line = textContent().blocks.flatMap { it.lines }.first { text in it.text }
        return (line.bounds.left + line.bounds.right) / 2 to (line.bounds.bottom + line.bounds.top) / 2
    }

    @Test
    fun a_tap_on_the_button_runs_its_script_and_the_band_turns_blue(): TestResult = scriptTest {
        val book = ScriptBooks.buttonPage()
        val page = book.page(KiteLocation(0, 0))
        val scripts = runner(book)
        scripts.chapterOpened(0)
        assertTrue(page.paintsRed(), "the band is red before the tap")

        assertFalse(scripts.tap(page, 52.5, 90.0), "the click is not prevented")

        assertTrue(page.paintsBlue(), "the script from the book turned the band blue")
        assertFalse(page.paintsRed())
        assertEquals(emptyList(), scripts.failures.map { it.message })
    }

    @Test
    fun a_listener_toggles_a_class_that_the_books_stylesheet_paints(): TestResult = scriptTest {
        val book = ScriptBooks.buttonPage(
            button = """<button id="go" type="button">Go</button>""",
            css = "h1.on { background: ${ScriptBooks.BLUE}; }",
            script = "document.getElementById('go').addEventListener('click', function () { document.getElementById('band').classList.toggle('on'); });",
        )
        val page = book.page(KiteLocation(0, 0))
        val scripts = runner(book)
        scripts.tap(page, 52.5, 90.0)
        assertTrue(page.paintsBlue(), "the class is on")
        scripts.tap(page, 52.5, 90.0)
        assertTrue(page.paintsRed(), "and off again, so the second tap found the button in the new tree")
        assertEquals(2, page.chapterVersion)
    }

    @Test
    fun a_reflowable_chapter_gains_pages_when_a_script_shows_a_hidden_section(): TestResult = scriptTest {
        val extra = (0 until 40).joinToString("") { "<p>Extra paragraph $it, which the button shows.</p>" }
        val book = ScriptBooks.chapter(
            """<p>Intro.</p><p><button id="more" type="button">More</button></p><div id="extra" hidden="hidden">$extra</div>""" +
                """<script>document.getElementById('more').onclick = function () { document.getElementById('extra').hidden = false; };</script>""",
        )
        assertEquals(1, book.pageCountIn(0), "the hidden section takes no room")
        val scripts = runner(book)
        val page = book.page(KiteLocation(0, 0))
        val (x, y) = page.centreOf("More")
        val changes = book.chapterChanges.value

        scripts.tap(page, x, y)

        val pages = book.pageCountIn(0)
        assertTrue(pages > 1, "the shown section takes pages: $pages")
        assertTrue(book.chapterChanges.value > changes, "the viewer hears of it")
        assertTrue("Extra paragraph 39" in book.page(KiteLocation(0, pages - 1)).textContent().plainText)
    }

    @Test
    fun a_script_that_prevents_a_link_keeps_the_viewer_from_following_it(): TestResult = scriptTest {
        val book = ScriptBooks.chapter(
            """<p><a id="stay" href="next.xhtml" onclick="return false;">Stay here</a></p><p><a id="go" href="next.xhtml">Go on</a></p>""",
            extraFiles = mapOf("next.xhtml" to ScriptBooks.xhtml(body = "<p>Next.</p>")),
        )
        val scripts = runner(book)
        val page = book.page(KiteLocation(0, 0))
        val (sx, sy) = page.centreOf("Stay here")
        val (gx, gy) = page.centreOf("Go on")
        assertTrue(scripts.tap(page, sx, sy), "a handler that returns false prevents the link")
        assertFalse(scripts.tap(page, gx, gy), "a plain link is the viewer's to follow")
    }

    @Test
    fun a_timer_runs_when_the_viewer_pumps_it_at_its_time(): TestResult = scriptTest {
        var now = 0L
        val book = ScriptBooks.buttonPage(script = "setTimeout(paint, 1000);")
        val page = book.page(KiteLocation(0, 0))
        val scripts = runner(book, clock = { now })
        var told = 0
        scripts.onTimersChanged { told++ }
        scripts.chapterOpened(0)
        assertTrue(scripts.hasTimers, "the script waits on a timer")
        assertTrue(told > 0, "and the viewer heard of it")

        now = 400
        assertEquals(600L, scripts.pumpTimers(now), "600 ms to go")
        assertTrue(page.paintsRed())

        now = 1000
        assertEquals(null, scripts.pumpTimers(now), "nothing waits after the timer ran")
        assertTrue(page.paintsBlue())
        assertFalse(scripts.hasTimers)
    }

    @Test
    fun the_promise_jobs_of_a_timer_run_before_the_next_timer_does(): TestResult = scriptTest {
        var now = 0L
        val console = ArrayList<String>()
        val book = ScriptBooks.chapter(
            """<p>Order.</p><script>
            setTimeout(function () { console.log('first'); Promise.resolve().then(function () { console.log('its job'); }); }, 10);
            setTimeout(function () { console.log('second'); }, 10);
            </script>""",
        )
        val scripts = runner(book, console = console, clock = { now })
        scripts.chapterOpened(0)
        now = 10
        scripts.pumpTimers(now)
        // HTML runs each timer as a task of its own, and the microtasks a task queued before the next one.
        assertEquals(listOf("log: first", "log: its job", "log: second"), console)
    }

    @Test
    fun the_promise_jobs_of_a_listener_run_before_the_next_listener_of_a_tap(): TestResult = scriptTest {
        val console = ArrayList<String>()
        val book = ScriptBooks.buttonPage(
            button = """<button id="go" type="button">Go</button>""",
            script = """
            var go = document.getElementById('go');
            go.addEventListener('click', function () { console.log('first'); Promise.resolve().then(function () { console.log('its job'); }); });
            go.addEventListener('click', function () { console.log('second'); });
            """,
        )
        val page = book.page(KiteLocation(0, 0))
        val scripts = runner(book, console = console)
        scripts.chapterOpened(0)
        scripts.tap(page, 52.5, 90.0)
        // A listener the browser calls is a callback of its own, and the microtasks it queued run after it returns.
        assertEquals(listOf("log: first", "log: its job", "log: second"), console)
    }

    @Test
    fun the_promise_jobs_of_a_scripts_own_dispatch_wait_until_the_script_is_done(): TestResult = scriptTest {
        val console = ArrayList<String>()
        val book = ScriptBooks.chapter(
            """<p id="p">Order.</p><script>
            var p = document.getElementById('p');
            p.addEventListener('ping', function () { console.log('first'); Promise.resolve().then(function () { console.log('its job'); }); });
            p.addEventListener('ping', function () { console.log('second'); });
            p.dispatchEvent(new Event('ping'));
            console.log('dispatched');
            </script>""",
        )
        runner(book, console = console).chapterOpened(0)
        assertEquals(listOf("log: first", "log: second", "log: dispatched", "log: its job"), console)
    }

    @Test
    fun the_promise_jobs_of_a_load_listener_run_before_the_next_listener(): TestResult = scriptTest {
        val console = ArrayList<String>()
        val book = ScriptBooks.chapter(
            """<p>Order.</p><script>
            document.addEventListener('DOMContentLoaded', function () { console.log('first'); Promise.resolve().then(function () { console.log('its job'); }); });
            document.addEventListener('DOMContentLoaded', function () { console.log('second'); });
            </script>""",
        )
        runner(book, console = console).chapterOpened(0)
        assertEquals(listOf("log: first", "log: its job", "log: second"), console)
    }

    @Test
    fun a_frame_that_an_earlier_callback_of_its_frame_cancels_does_not_run(): TestResult = scriptTest {
        var now = 0L
        val console = ArrayList<String>()
        val book = ScriptBooks.chapter(
            """<p>Frames.</p><script>
            var second;
            requestAnimationFrame(function () { console.log('first'); cancelAnimationFrame(second); });
            second = requestAnimationFrame(function () { console.log('second'); });
            </script>""",
        )
        val scripts = runner(book, console = console, clock = { now })
        scripts.chapterOpened(0)
        now = 16
        assertEquals(null, scripts.pumpTimers(now), "nothing waits once the frame ran")
        assertEquals(listOf("log: first"), console)
    }

    @Test
    fun a_script_that_never_returns_stops_at_the_budget_and_the_page_still_works(): TestResult = scriptTest {
        val book = ScriptBooks.buttonPage(script = "while (true) {}")
        val page = book.page(KiteLocation(0, 0))
        val scripts = runner(book, EpubScriptPolicy(budgetMillis = 300))
        scripts.chapterOpened(0)
        assertTrue(scripts.failures.isNotEmpty(), "the loop was stopped")
        assertTrue(page.paintsRed(), "the page shows its markup")

        scripts.tap(page, 52.5, 90.0)
        assertTrue(page.paintsBlue(), "a later tap gets a budget of its own, and the button works")
    }

    @Test
    fun the_budget_leaves_out_the_dom_the_library_sets_up(): TestResult = scriptTest {
        // The clock moves a second at each read, but a thousand seconds at its second read, which
        // falls between the start of the chapter's first call and the end of its DOM's setup. The
        // budget of two hundred seconds is spent before the book's script starts unless the setup
        // is left out of it, and the loop is stopped either way (#554). It holds on any engine,
        // however often that engine asks for the time.
        var now = 0L
        var reads = 0
        var started = false
        val book = ScriptBooks.buttonPage(script = "console.log('started'); while (true) {}")
        val page = book.page(KiteLocation(0, 0))
        val scripts = EpubScriptRunner(
            book,
            EpubScriptPolicy(budgetMillis = 200_000),
            onConsole = { _, message -> if (message == "started") started = true },
            clock = {
                reads++
                now += if (reads == 2) 1_000_000 else 1_000
                now
            },
        ).also { runners += it }
        scripts.chapterOpened(0)
        assertTrue(started, "the book's script ran: ${scripts.failures.map { it.message }}")
        // The loop spends the call's budget, so what the call runs after it, the load event, stops too.
        val failures = scripts.failures.map { it.message.orEmpty() }
        assertTrue(failures.first().startsWith("chapter 0, OEBPS/page.xhtml#script:"), "the loop is the first script stopped: $failures")
        assertTrue(failures.all { "interrupted" in it }, "$failures")

        scripts.tap(page, 52.5, 90.0)
        assertTrue(page.paintsBlue(), "the page's own script ran before the loop, and its button works")
    }

    @Test
    fun a_script_outside_the_book_does_not_run(): TestResult = scriptTest {
        val book = ScriptBooks.buttonPage(head = """<script src="https://example.org/tracker.js"></script>""")
        val scripts = runner(book)
        scripts.chapterOpened(0)
        assertTrue(scripts.failures.any { "outside the book" in it.message.orEmpty() }, "${scripts.failures.map { it.message }}")
    }

    @Test
    fun a_script_that_sets_the_location_goes_through_the_viewer(): TestResult = scriptTest {
        val book = ScriptBooks.buttonPage(button = """<button id="go" type="button" onclick="location.href = 'next.xhtml#n'">Go</button>""")
        val scripts = runner(book)
        val asked = ArrayList<String>()
        scripts.onNavigate { asked += it }
        scripts.tap(book.page(KiteLocation(0, 0)), 52.5, 90.0)
        assertEquals(listOf("OEBPS/next.xhtml#n"), asked)
    }

    @Test
    fun a_script_cannot_open_a_data_url_as_a_page(): TestResult = scriptTest {
        // EPUB Reading Systems 3.3, 3.4: a data URL never opens in a top-level browsing context (#514).
        val book = ScriptBooks.buttonPage(button = """<button id="go" type="button" onclick="location.href = 'data:text/html,%3Cp%3EPhish'">Go</button>""")
        val scripts = runner(book)
        val asked = ArrayList<String>()
        scripts.onNavigate { asked += it }
        scripts.tap(book.page(KiteLocation(0, 0)), 52.5, 90.0)
        assertEquals(emptyList(), asked)
        assertTrue(scripts.failures.any { "data: URL" in it.message.orEmpty() }, "${scripts.failures.map { it.message }}")
    }

    @Test
    fun noscript_content_does_not_show_once_scripts_run(): TestResult = scriptTest {
        val book = ScriptBooks.chapter("""<p>Always.</p><noscript><p>Only without scripts.</p></noscript><script>var x = 1;</script>""")
        val page = book.page(KiteLocation(0, 0))
        assertTrue("Only without scripts" in page.textContent().plainText)
        runner(book).chapterOpened(0)
        assertFalse("Only without scripts" in page.textContent().plainText)
        assertTrue("Always" in page.textContent().plainText)
    }

    @Test
    fun the_dom_answers_as_a_browser_does(): TestResult = scriptTest {
        val console = ArrayList<String>()
        val book = ScriptBooks.chapter(
            """<h1 id="title" class="big">Heading</h1><div id="box"><p class="a">One</p><p class="b">Two</p></div><span id="target">x</span>""" +
                "<script src=\"dom.js\"></script>",
            extraFiles = mapOf(
                "dom.js" to """
                    var r = {};
                    r.title = document.title;
                    r.paragraphs = document.querySelectorAll('p').length;
                    r.second = document.querySelector('#box p.b').textContent;
                    var box = document.getElementById('box');
                    box.innerHTML = '<b>bold</b> text';
                    r.html = box.innerHTML;
                    r.first = box.firstChild.tagName;
                    var li = document.createElement('em');
                    li.textContent = 'new';
                    box.insertBefore(li, box.firstChild);
                    r.order = Array.prototype.map.call(box.childNodes, function (n) { return n.nodeName; }).join(',');
                    box.removeChild(li);
                    r.after = box.childNodes.length;
                    var t = document.getElementById('target');
                    t.dataset.fooBar = '1';
                    r.dataAttr = t.getAttribute('data-foo-bar');
                    t.classList.add('x', 'y');
                    r.classes = t.className;
                    t.style.backgroundColor = 'red';
                    r.style = t.getAttribute('style');
                    r.display = getComputedStyle(document.getElementById('title')).display;
                    r.weight = getComputedStyle(document.getElementById('title')).fontWeight;
                    r.closest = t.closest('body').tagName;
                    r.matches = document.getElementById('title').matches('h1.big');
                    var order = [];
                    document.body.addEventListener('ping', function () { order.push('body capture'); }, true);
                    document.body.addEventListener('ping', function () { order.push('body bubble'); });
                    t.addEventListener('ping', function (e) { order.push('target'); });
                    t.dispatchEvent(new CustomEvent('ping', { bubbles: true }));
                    r.events = order.join(',');
                    localStorage.setItem('k', 'v');
                    r.stored = localStorage.getItem('k');
                    r.ready = document.readyState;
                    console.log(JSON.stringify(r));
                """.trimIndent(),
            ),
        )
        runner(book, console = console).chapterOpened(0)
        val logged = console.single { it.startsWith("log: ") }.removePrefix("log: ")
        val expected = listOf(
            "\"title\":\"Scripted\"",
            "\"paragraphs\":2",
            "\"second\":\"Two\"",
            // An XHTML chapter writes its markup as XML, each element with its namespace, as a browser does (#548).
            "\"html\":\"<b xmlns=\\\"http://www.w3.org/1999/xhtml\\\">bold</b> text\"",
            "\"first\":\"b\"",
            "\"order\":\"em,b,#text\"",
            "\"after\":2",
            "\"dataAttr\":\"1\"",
            "\"classes\":\"x y\"",
            "\"style\":\"background-color: red;\"",
            "\"display\":\"block\"",
            "\"weight\":\"700\"",
            "\"closest\":\"body\"",
            "\"matches\":true",
            "\"events\":\"body capture,target,body bubble\"",
            "\"stored\":\"v\"",
            "\"ready\":\"loading\"",
        )
        for (part in expected) assertTrue(part in logged, "$part in $logged")
    }

    @Test
    fun a_script_parses_urls_as_the_url_standard_does(): TestResult = scriptTest {
        // URL and URLSearchParams of the WHATWG URL Standard (#520). A path that climbs above the
        // container root stays at the root of the book's origin, as the W3C tests ocf-url_parse-* ask.
        val console = ArrayList<String>()
        val book = ScriptBooks.chapter(
            "<p>x</p><script src=\"url.js\"></script>",
            extraFiles = mapOf(
                "url.js" to """
                    var r = {};
                    try {
                      var root = new URL('../..', document.documentURI);
                      r.root = String(root) === location.origin + '/';
                      r.leak = new URL('../../../../../media/imgs/monastery.jpg', root).href === location.origin + '/media/imgs/monastery.jpg';
                      r.absolute = new URL('/media/imgs/monastery.jpg', root).pathname;
                      r.sameOrigin = new URL(location.href).origin === location.origin;
                      var u = new URL('https://user:pw@EXAMPLE.com:443/a/./b/../c?x=1&y=a+b#frag');
                      r.parts = [u.protocol, u.username, u.host, u.port, u.pathname, u.search, u.hash, u.origin].join('|');
                      r.y = u.searchParams.get('y');
                      u.searchParams.append('z', '\u00e9 &');
                      r.href = u.href;
                      u.pathname = '/new path';
                      u.port = '8080';
                      r.set = u.href;
                      r.idna = new URL('http://\uff27\uff4f.com/').host + ',' + new URL('https://fa\u00df.ExAmPlE/').host;
                      r.ipv6 = new URL('http://[0:0::1]/').host;
                      r.canParse = URL.canParse('nope') + ',' + URL.canParse('/x', 'https://a/');
                      r.params = new URLSearchParams({ b: '2', a: '1' }).toString();
                      var sp = new URLSearchParams('?q=1&q=2&s=%20t');
                      sp.sort();
                      r.sorted = sp.toString() + ';' + sp.getAll('q').join(',') + ';' + sp.size;
                      r.iter = Array.from(new URLSearchParams('a=1&b=2')).join(';');
                      try { new URL('nope'); r.invalid = 'parsed'; } catch (e) { r.invalid = e instanceof TypeError; }
                    } catch (e) { r.error = String(e); }
                    console.log(JSON.stringify(r));
                """.trimIndent(),
            ),
        )
        runner(book, console = console).chapterOpened(0)
        val logged = console.single { it.startsWith("log: ") }.removePrefix("log: ")
        val expected = listOf(
            "\"root\":true",
            "\"leak\":true",
            "\"absolute\":\"/media/imgs/monastery.jpg\"",
            "\"sameOrigin\":true",
            "\"parts\":\"https:|user|example.com||/a/c|?x=1&y=a+b|#frag|https://example.com\"",
            "\"y\":\"a b\"",
            "\"href\":\"https://user:pw@example.com/a/c?x=1&y=a+b&z=%C3%A9+%26#frag\"",
            "\"set\":\"https://user:pw@example.com:8080/new%20path?x=1&y=a+b&z=%C3%A9+%26#frag\"",
            "\"idna\":\"go.com,xn--fa-hia.example\"",
            "\"ipv6\":\"[::1]\"",
            "\"canParse\":\"false,true\"",
            "\"params\":\"b=2&a=1\"",
            "\"sorted\":\"q=1&q=2&s=+t;1,2;3\"",
            "\"iter\":\"a,1;b,2\"",
            "\"invalid\":true",
        )
        for (part in expected) assertTrue(part in logged, "$part in $logged")
    }

    /** A fixed-layout book of [count] pages like [ScriptBooks.buttonPage], each with scripts of its own. */
    private fun buttonPages(count: Int): EpubDocument = ScriptBooks.book(
        metadata = """<meta property="rendition:layout">pre-paginated</meta>""",
        items = (0 until count).map { ScriptBooks.Item("page$it.xhtml", "application/xhtml+xml", properties = "scripted", spine = true) } +
            ScriptBooks.Item("page.css", "text/css"),
        files = (0 until count).associate { i ->
            "page$i.xhtml" to ScriptBooks.xhtml(
                head = """<meta name="viewport" content="width=300, height=200"/><link rel="stylesheet" type="text/css" href="page.css"/>""",
                body = """<h1 id="band">Band $i</h1><button id="go" type="button" onclick="paint()">Go</button>""" +
                    """<script>function paint() { document.getElementById('band').style.background = '${ScriptBooks.BLUE}'; }</script>""",
            )
        } + ("page.css" to """body { margin: 0; }
            |h1 { margin: 0; height: 60px; background: ${ScriptBooks.RED}; color: ${ScriptBooks.RED}; }
            |#go { display: block; position: absolute; left: 10px; top: 100px; width: 120px; height: 40px; margin: 0; }""".trimMargin()),
    )

    @Test
    fun every_scripted_chapter_of_a_book_runs_its_own_scripts(): TestResult = scriptTest {
        // KiteJS holds one open engine per thread, and the second chapter's used to fail to open (#498).
        val book = buttonPages(3)
        val scripts = runner(book)
        for (chapter in 0 until 3) scripts.chapterOpened(chapter)
        val pages = (0 until 3).map { book.page(KiteLocation(it, 0)) }

        scripts.tap(pages[2], 52.5, 90.0)
        scripts.tap(pages[0], 52.5, 90.0)

        assertEquals(emptyList(), scripts.failures.map { it.message })
        assertTrue(pages[0].paintsBlue(), "the first chapter's button ran")
        assertTrue(pages[1].paintsRed() && !pages[1].paintsBlue(), "the second chapter's band was not touched")
        assertTrue(pages[2].paintsBlue(), "the third chapter's button ran")
    }

    @Test
    fun a_chapter_whose_engine_closed_starts_over_from_its_markup(): TestResult = scriptTest {
        val book = ScriptBooks.book(
            items = listOf(
                ScriptBooks.Item("one.xhtml", "application/xhtml+xml", properties = "scripted", spine = true),
                ScriptBooks.Item("two.xhtml", "application/xhtml+xml", properties = "scripted", spine = true),
            ),
            files = mapOf(
                "one.xhtml" to ScriptBooks.xhtml(
                    body = """<p>Markup.</p><script>var runs = (Number(localStorage.getItem('runs')) || 0) + 1; localStorage.setItem('runs', String(runs));
                        var p = document.createElement('p'); p.textContent = 'Added by run ' + runs + '.'; document.body.appendChild(p);</script>""",
                ),
                "two.xhtml" to ScriptBooks.xhtml(body = """<p>Two.</p><script>var two = true;</script>"""),
            ),
        )
        val opened = ArrayList<Int>()
        // One engine at a time, as where engines share one thread: opening the second chapter closes the first's.
        val session = EpubScriptSession(book, engineFor = { opened += it; KiteJsScriptEngine() }, liveChapters = 1)
        try {
            fun text() = book.page(KiteLocation(0, 0)).textContent().plainText
            session.chapterOpened(0)
            assertTrue("Added by run 1." in text(), text())

            session.chapterOpened(1)
            assertTrue("Added by run 1." in text(), "the chapter keeps what its scripts made of it while they are closed")

            session.chapterOpened(0)
            assertEquals(listOf(0, 1, 0), opened)
            assertTrue("Added by run 2." in text(), "the scripts ran again: ${text()}")
            assertFalse("Added by run 1." in text(), "and over the chapter's markup, not over what the first run made")
            assertEquals(emptyList(), session.failures.map { it.message })
        } finally {
            session.close()
        }
    }

    @Test
    fun unloaded_chapters_close_their_engines_and_start_over_when_used_again(): TestResult = scriptTest {
        val book = ScriptBooks.chapter(
            """<p>Markup.</p><script>var runs = (Number(localStorage.getItem('runs')) || 0) + 1; localStorage.setItem('runs', String(runs));
                var p = document.createElement('p'); p.textContent = 'Added by run ' + runs + '.'; document.body.appendChild(p);
                setTimeout(function () {}, 1000);</script>""",
        )
        val opened = ArrayList<Int>()
        val closed = ArrayList<Int>()
        val session = EpubScriptSession(
            book,
            engineFor = { chapter ->
                opened += chapter
                ClosingEngine(KiteJsScriptEngine()) { closed += chapter }
            },
        )
        try {
            var timerReports = 0
            session.onTimersChanged { timerReports++ }
            fun text() = book.page(KiteLocation(0, 0)).textContent().plainText
            session.chapterOpened(0)
            assertTrue(session.hasTimers)
            val reportsBefore = timerReports

            session.unloadChapters()
            assertEquals(listOf(0), closed, "the engine closed")
            assertFalse(session.hasTimers, "and its timers went with it")
            assertTrue(timerReports > reportsBefore, "which the listeners heard")
            assertTrue("Added by run 1." in text(), "the chapter keeps what its scripts made of it")

            session.chapterOpened(0)
            assertEquals(listOf(0, 0), opened)
            assertTrue("Added by run 2." in text() && "Added by run 1." !in text(), "the scripts ran again over the markup: ${text()}")
            assertEquals(emptyList(), session.failures.map { it.message })
        } finally {
            session.close()
        }
        session.unloadChapters()
        assertEquals(listOf(0, 0), closed, "a closed session has nothing more to unload")
    }

    @Test
    fun an_engine_that_will_not_open_is_a_failure_of_its_chapter(): TestResult = scriptTest {
        val book = ScriptBooks.chapter("""<p>Plain.</p><script>var x = 1;</script>""")
        val session = EpubScriptSession(book, engineFor = { throw IllegalStateException("no engine here") })
        session.chapterOpened(0)
        assertFalse(session.tap(book.page(KiteLocation(0, 0)), 10.0, 10.0))
        assertTrue(session.failures.any { "no engine here" in it.message.orEmpty() }, "${session.failures.map { it.message }}")
        assertTrue("Plain." in book.page(KiteLocation(0, 0)).textContent().plainText)
        session.close()
    }

    @Test
    fun a_script_finds_the_reading_system_and_what_it_supports(): TestResult = scriptTest {
        val console = ArrayList<String>()
        val book = ScriptBooks.chapter(
            """<p>Features.</p><script>
                var ers = navigator.epubReadingSystem;
                var names = ['dom-manipulation', 'layout-changes', 'spine-scripting', 'mouse-events', 'touch-events', 'keyboard-events', 'no-such-feature'];
                console.log(ers.name + ' ' + names.map(function (n) { return n + '=' + ers.hasFeature(n, '1.0'); }).join(' '));
                navigator.epubReadingSystem = null;
                console.log(typeof navigator.epubReadingSystem.hasFeature);
            </script>""",
        )
        runner(book, console = console).chapterOpened(0)
        assertEquals(
            listOf(
                "log: KitePDF dom-manipulation=true layout-changes=true spine-scripting=true mouse-events=true " +
                    "touch-events=false keyboard-events=false no-such-feature=undefined",
                "log: function",
            ),
            console,
        )
    }

    @Test
    fun a_script_measures_an_element_it_just_added_and_restyled(): TestResult = scriptTest {
        // A browser lays the page out again when a script measures after a change (#499).
        val console = ArrayList<String>()
        val book = ScriptBooks.chapter(
            """<p>Measure.</p><script>
                var div = document.createElement('div');
                div.style.width = '40px';
                div.style.paddingLeft = '8px';
                div.style.height = '20px';
                document.body.appendChild(div);
                var first = div.offsetWidth;
                div.style.width = '100px';
                console.log(first + ' ' + div.offsetWidth + ' ' + div.offsetHeight + ' ' + Math.round(div.getBoundingClientRect().width));
            </script>""",
        )
        runner(book, console = console).chapterOpened(0)
        assertEquals(listOf("log: 48 108 20 108"), console)
    }

    @Test
    fun the_chapters_of_a_book_share_its_origin_and_another_book_has_another(): TestResult = scriptTest {
        // A reading system gives each book an origin of its own, shared by its chapters (#500).
        fun book(identifier: String) = ScriptBooks.book(
            identifier = identifier,
            items = listOf(
                ScriptBooks.Item("one.xhtml", "application/xhtml+xml", properties = "scripted", spine = true),
                ScriptBooks.Item("two.xhtml", "application/xhtml+xml", properties = "scripted", spine = true),
            ),
            files = mapOf(
                "one.xhtml" to ScriptBooks.xhtml(body = "<p>One.</p><script>console.log(self.origin + ' ' + location.href);</script>"),
                "two.xhtml" to ScriptBooks.xhtml(body = "<p>Two.</p><script>console.log(location.origin + ' ' + location.pathname);</script>"),
            ),
        )
        fun originsOf(identifier: String): List<String> {
            val console = ArrayList<String>()
            val scripts = runner(book(identifier), console = console)
            scripts.chapterOpened(0)
            scripts.chapterOpened(1)
            scripts.close()
            return console.map { it.removePrefix("log: ") }
        }
        val (one, two) = originsOf("urn:uuid:one")
        val origin = one.substringBefore(' ')
        assertTrue(Regex("epub://[0-9a-f]{16}").matches(origin), origin)
        assertEquals("$origin $origin/OEBPS/one.xhtml", one)
        assertEquals("$origin /OEBPS/two.xhtml", two, "the second chapter has the same origin")
        assertEquals(origin, originsOf("urn:uuid:one")[0].substringBefore(' '), "the book has it each time it opens")
        assertTrue(origin != originsOf("urn:uuid:two")[0].substringBefore(' '), "another book has another")
    }

    @Test
    fun an_address_under_the_books_origin_is_a_place_in_the_book(): TestResult = scriptTest {
        val book = ScriptBooks.buttonPage(button = """<button id="go" type="button" onclick="location.href = location.origin + '/OEBPS/next.xhtml#n'">Go</button>""")
        val scripts = runner(book)
        val asked = ArrayList<String>()
        scripts.onNavigate { asked += it }
        scripts.tap(book.page(KiteLocation(0, 0)), 52.5, 90.0)
        assertEquals(listOf("OEBPS/next.xhtml#n"), asked)
    }

    @Test
    fun a_timer_that_throws_each_time_does_not_grow_the_failures_for_ever(): TestResult = scriptTest {
        var now = 0L
        val book = ScriptBooks.chapter("""<p>Ticks.</p><script>setInterval(function () { null.save(); }, 10);</script>""")
        val scripts = runner(book, clock = { now })
        scripts.chapterOpened(0)
        repeat(EpubScriptSession.MAX_FAILURES + 50) {
            now += 10
            scripts.pumpTimers(now)
        }
        assertEquals(EpubScriptSession.MAX_FAILURES, scripts.failures.size, "the newest are kept")
        assertTrue(scripts.hasTimers, "the timer still runs")
    }

    /** Runs the tasks that [scripts] queued, and those that they queue, until none is left. */
    private fun drain(scripts: EpubScriptRunner, now: Long = 0L) {
        var rounds = 0
        while (scripts.hasTimers && rounds++ < 20) scripts.pumpTimers(now)
        assertFalse(scripts.hasTimers, "the tasks ran out")
    }

    @Test
    fun an_image_and_a_stylesheet_load_from_blob_urls_that_a_script_made(): TestResult = scriptTest {
        // The File API (#533): a blob URL loads as a file of the book does, and what the chapter's
        // tree names stays with it after the script revokes the URL, as an image a browser loaded.
        val book = ScriptBooks.chapter(
            "<p id=\"text\">Blobs.</p><script src=\"blob.js\"></script>",
            extraFiles = mapOf(
                "blob.js" to """
                    var svg = new Blob(['<svg xmlns="http://www.w3.org/2000/svg" width="60" height="30">' +
                        '<rect width="60" height="30" fill="${ScriptBooks.RED}"/></svg>'], { type: 'image/svg+xml' });
                    var img = document.createElement('img');
                    img.src = URL.createObjectURL(svg);
                    document.body.appendChild(img);
                    URL.revokeObjectURL(img.src);
                    var css = new Blob(['#text { background: ${ScriptBooks.BLUE}; }'], { type: 'text/css' });
                    var link = document.createElement('link');
                    link.setAttribute('rel', 'stylesheet');
                    link.setAttribute('href', URL.createObjectURL(css));
                    document.head.appendChild(link);
                """.trimIndent(),
            ),
        )
        val page = book.page(KiteLocation(0, 0))
        assertFalse(page.paintsRed() || page.paintsBlue(), "nothing before the script runs")
        val scripts = runner(book)
        scripts.chapterOpened(0)
        assertEquals(emptyList(), scripts.failures.map { it.message })
        assertTrue(page.paintsRed(), "the image from the revoked blob URL")
        assertTrue(page.paintsBlue(), "the rule of the stylesheet from a blob URL")
        assertTrue(book.withFontSize(20.0).page(KiteLocation(0, 0)).paintsRed(), "and the chapter laid out again at another size")
    }

    @Test
    fun a_link_that_a_script_sets_up_through_its_properties_loads_its_style_sheet(): TestResult = scriptTest {
        // HTMLLinkElement reflects rel and href (#538). Without the interface, the two assignments
        // set plain properties of the object and no attribute, so the sheet never loaded.
        val book = ScriptBooks.chapter(
            "<p id=\"text\">Linked.</p><script src=\"link.js\"></script>",
            extraFiles = mapOf(
                "blue.css" to "#text { background: ${ScriptBooks.BLUE}; }",
                "link.js" to """
                    var link = document.createElement('link');
                    link.rel = 'stylesheet';
                    link.href = 'blue.css';
                    document.head.appendChild(link);
                """.trimIndent(),
            ),
        )
        val page = book.page(KiteLocation(0, 0))
        assertFalse(page.paintsBlue(), "nothing before the script runs")
        val scripts = runner(book)
        scripts.chapterOpened(0)
        assertEquals(emptyList(), scripts.failures.map { it.message })
        assertTrue(page.paintsBlue(), "the rule of the linked sheet")
    }

    @Test
    fun a_blob_url_names_the_books_origin_and_loads_nothing_once_its_chapter_closes(): TestResult = scriptTest {
        val console = ArrayList<String>()
        val book = ScriptBooks.chapter(
            """<p>Origin.</p><script>
                var url = URL.createObjectURL(new Blob(['<svg xmlns="http://www.w3.org/2000/svg" width="60" height="30">' +
                    '<rect width="60" height="30" fill="${ScriptBooks.RED}"/></svg>'], { type: 'image/svg+xml' }));
                console.log(url);
                console.log(new URL(url).origin === location.origin);
                window.later = url;
            </script>""",
        )
        val scripts = runner(book, console = console)
        scripts.chapterOpened(0)
        val url = console[0].removePrefix("log: ")
        assertTrue(Regex("blob:epub://[0-9a-f]{16}/[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}").matches(url), url)
        assertEquals("log: true", console[1])
        assertEquals("image/svg+xml", book.resourceType(url))
        scripts.close()
        assertEquals(null, book.resource(url), "a chapter that closes revokes the URLs its scripts made, as a page that unloads does")
    }

    @Test
    fun a_file_reader_reads_a_blob_in_tasks_of_its_own(): TestResult = scriptTest {
        val console = ArrayList<String>()
        val book = ScriptBooks.chapter(
            """<p>Reader.</p><script>
                var log = [];
                var reader = new FileReader();
                ['loadstart', 'progress', 'load', 'loadend'].forEach(function (type) {
                    reader.addEventListener(type, function (e) { log.push(type + ' ' + e.loaded + '/' + e.total); });
                });
                reader.addEventListener('loadend', function () { console.log(log.join(', ') + ': ' + reader.result); });
                reader.readAsText(new Blob(['\u00FEsoup ', new Uint8Array([0xC3, 0xA9])], { type: 'text/plain;charset=iso-8859-1' }));
                log.push('state ' + reader.readyState);
                new Blob(['text']).text().then(function (t) { console.log('then ' + t); });
                Blob.prototype.text.call({}).catch(function (e) { console.log('catch ' + e.name); });
            </script>""",
        )
        val scripts = runner(book, console = console)
        scripts.chapterOpened(0)
        assertEquals(emptyList(), scripts.failures.map { it.message })
        assertEquals(listOf("log: catch TypeError"), console, "nothing is read while the script runs, and a read of no blob rejects at once")
        console.clear()
        drain(scripts)
        assertEquals(
            // The reader's tasks were queued first. The text decodes as windows-1252, which the label names.
            listOf("log: state 1, loadstart 0/9, progress 9/9, load 9/9, loadend 9/9: \u00C3\u00BEsoup \u00C3\u00A9", "log: then text"),
            console,
        )
    }

    @Test
    fun a_book_polyfills_and_patches_the_built_in_objects_as_in_a_browser(): TestResult = scriptTest {
        // A bundled script polyfills a method the engine lacks and wraps one it has, as core-js and
        // its like do, and a test of web-platform-tests adds an iterator to a primitive's prototype.
        // A chapter used to run on sealed built-ins, where the first line threw (#537).
        val console = ArrayList<String>()
        val book = ScriptBooks.chapter(
            "<p>Polyfills.</p><script src=\"poly.js\"></script>",
            extraFiles = mapOf(
                "poly.js" to """
                    String.prototype.shout = function () { return this.toUpperCase() + '!'; };
                    var join = Array.prototype.join, joins = 0;
                    Array.prototype.join = function (separator) { joins++; return join.call(this, separator); };
                    Number.prototype[Symbol.iterator] = function () { return [1, 2][Symbol.iterator](); };
                    var spread = Array.from(7);
                    delete Number.prototype[Symbol.iterator];
                    Math.clamp = function (x, lo, hi) { return Math.min(Math.max(x, lo), hi); };
                    console.log('hi'.shout() + ' ' + [1, 2].join('-') + ' ' + joins + ' ' + spread.length + ' ' +
                        Math.clamp(9, 0, 5) + ' ' + (Symbol.iterator in Number.prototype));
                """.trimIndent(),
            ),
        )
        val scripts = runner(book, console = console)
        scripts.chapterOpened(0)
        assertEquals(emptyList(), scripts.failures.map { it.message })
        assertEquals(listOf("log: HI! 1-2 1 2 5 false"), console)
    }

    /**
     * A chapter that runs [WORKLOAD], a script that calls the DOM and no built-in of the engine,
     * after [POISON] when [poisoned], with a tap on its label. Answers what the chapter logged
     * and the failures of its scripts.
     */
    private fun runWorkload(poisoned: Boolean): Pair<List<String>, List<String?>> {
        val console = ArrayList<String>()
        val book = ScriptBooks.chapter(
            """<label id="tapme" for="check2" style="display: block">Tap here</label><input type="checkbox" id="check2"/>""" +
                """<div id="box"><p>Start</p></div><span id="target">x</span>""" +
                """<form id="form"><input type="radio" name="r" id="r1" checked="checked"/><input type="radio" name="r" id="r2"/>""" +
                """<select id="sel"><option value="a">A</option><option>B</option></select>""" +
                """<button id="submit" type="submit">S</button><button id="reset" type="reset">R</button>""" +
                """<button id="off" type="button" disabled="disabled">Off</button></form>""" +
                """<input type="checkbox" id="check"/><label id="label" for="check">Check</label>""" +
                """<details id="details"><summary id="summary">More</summary><p>Inside</p></details>""" +
                (if (poisoned) "<script src=\"poison.js\"></script>" else "") + "<script src=\"work.js\"></script>",
            extraFiles = mapOf("poison.js" to POISON, "work.js" to WORKLOAD),
        )
        val page = book.page(KiteLocation(0, 0))
        val scripts = runner(book, console = console)
        scripts.chapterOpened(0)
        drain(scripts)
        val (x, y) = page.centreOf("Tap here")
        scripts.tap(page, x, y)
        drain(scripts)
        return console to scripts.failures.map { it.message }
    }

    @Test
    fun the_dom_answers_alike_when_a_book_patches_every_built_in_it_calls(): TestResult = scriptTest {
        // A browser's DOM is native code, which a script that patches a built-in does not reach.
        // This DOM is JavaScript in the book's realm, so it takes the built-ins it calls before the
        // book runs (#540). The poison replaces every method and accessor of the built-ins and the
        // global constructors, adds keys to Object.prototype that its tables and dictionaries must
        // not inherit, and replaces the methods of the DOM's own objects that its algorithms used
        // to call, so any of them that the DOM still called would throw or answer otherwise.
        val (plain, plainFailures) = runWorkload(poisoned = false)
        assertEquals(emptyList(), plainFailures)
        assertEquals(
            listOf(
                "log: host undefined",
                "log: tree em p p 2 1 2",
                "log: after 2 One box",
                "log: attrs 1 2 y z true background-color: red; margin-top: .5em !important; 0.5em important 2 true",
                "log: events capture target:2 bubble object:3 capture bubble object:3 ",
                "log: event false false false",
                "log: stop first false true",
                "log: label click:true change true",
                "log: radio false true",
                "log: select a B 1",
                "log: form submit reset off:false",
                "log: details toggle true",
                "log: url https://example.com/base/a/b?x=1&y=2&z=3#h /base/a/b x=1;y=2;z=3; x1 y2 z3 a=1&b=2 c=3 false",
                "log: text 104,195,169,undefined hé 2/2 97,98 windows-1252 aGk= hi",
                "log: blob 3 text/plain 2 x/y f.txt 5 3 |",
                "log: exception SyntaxError 12 true 8 0",
                "log: doc New title|a=1; b=2|v|block|loading|2",
                "log: features true undefined false",
                "log: micro",
                "log: loaded interactive true",
                "log: window load",
                "log: reader start:false load:2/2:true bc",
                "log: timer x",
                "log: tap focus click change true",
            ),
            plain,
        )
        val (poisoned, poisonedFailures) = runWorkload(poisoned = true)
        assertEquals(emptyList(), poisonedFailures)
        assertEquals(plain, poisoned)
    }

    private companion object {
        /** A script that calls the DOM and no method of the engine's built-ins, only its syntax. */
        val WORKLOAD = """
            function log(s) { console.log(s); }
            log('host ' + typeof __kite);
            var box = document.getElementById('box');
            box.innerHTML = '<p class="a b">One</p><p class="b">Two</p>';
            var em = document.createElementNS('http://www.w3.org/1999/xhtml', 'em');
            em.textContent = 'new';
            box.prepend(em);
            var kids = box.children, names = '';
            for (var i = 0; i < kids.length; i++) names += kids[i].tagName + ' ';
            log('tree ' + names + box.getElementsByClassName('b').length + ' ' + box.getElementsByClassName(' a  b ').length + ' ' +
                box.getElementsByTagName('p').length);
            em.remove();
            log('after ' + box.childElementCount + ' ' + box.firstElementChild.textContent + ' ' + kids[1].parentElement.id);

            var t = document.getElementById('target');
            t.setAttribute('data-foo-bar', '1');
            t.dataset.bazQux = '2';
            t.classList.add('x', 'y');
            t.classList.remove('x');
            t.classList.toggle('z');
            t.style.backgroundColor = 'red';
            t.style.setProperty('margin-top', '.5em', 'important');
            log('attrs ' + t.dataset.fooBar + ' ' + t.getAttribute('data-baz-qux') + ' ' + t.className + ' ' + t.classList.contains('y') + ' ' +
                t.getAttribute('style') + ' ' + t.style.marginTop + ' ' + t.style.getPropertyPriority('margin-top') + ' ' + t.style.length + ' ' +
                ('color' in t.style));

            var order = '', p = box.firstElementChild;
            box.addEventListener('ping', function () { order += 'capture '; }, true);
            box.addEventListener('ping', function () { order += 'bubble '; });
            box.addEventListener('ping', { handleEvent: function (e) { order += 'object:' + e.eventPhase + ' '; } });
            p.addEventListener('ping', function (e) { order += 'target:' + e.eventPhase + ' '; }, { once: true });
            p.dispatchEvent(new CustomEvent('ping', { bubbles: true, detail: 3 }));
            p.dispatchEvent(new CustomEvent('ping', { bubbles: true }));
            log('events ' + order);
            var plain = new Event('plain');
            log('event ' + plain.bubbles + ' ' + plain.cancelable + ' ' + plain.composed);
            var stopped = '';
            box.addEventListener('halt', function () { stopped += 'outer '; });
            p.addEventListener('halt', function (e) { stopped += 'first '; e.stopImmediatePropagation(); });
            p.addEventListener('halt', function () { stopped += 'second '; });
            var halt = new Event('halt', { bubbles: true, cancelable: true });
            halt.preventDefault();
            var notPrevented = p.dispatchEvent(halt);
            log('stop ' + stopped + notPrevented + ' ' + halt.defaultPrevented);

            var check = document.getElementById('check'), changes = '';
            check.addEventListener('change', function () { changes += 'change '; });
            check.addEventListener('click', function () { changes += 'click:' + check.hasAttribute('checked') + ' '; });
            document.getElementById('label').click();
            log('label ' + changes + check.hasAttribute('checked'));
            document.getElementById('r2').click();
            log('radio ' + document.getElementById('r1').hasAttribute('checked') + ' ' + document.getElementById('r2').hasAttribute('checked'));
            var sel = document.getElementById('sel');
            var before = sel.value;
            sel.value = 'B';
            log('select ' + before + ' ' + sel.value + ' ' + sel.selectedIndex);
            var form = document.getElementById('form'), formLog = '', offClicked = false;
            form.addEventListener('submit', function (e) { formLog += 'submit '; e.preventDefault(); });
            form.addEventListener('reset', function () { formLog += 'reset '; });
            document.getElementById('off').addEventListener('click', function () { offClicked = true; });
            document.getElementById('submit').click();
            document.getElementById('reset').click();
            document.getElementById('off').click();
            log('form ' + formLog + 'off:' + offClicked);
            var details = document.getElementById('details'), toggled = '';
            details.addEventListener('toggle', function () { toggled += 'toggle '; });
            document.getElementById('summary').click();
            log('details ' + toggled + details.hasAttribute('open'));

            var u = new URL('a/b?x=1&y=2#h', 'https://example.com/base/');
            u.searchParams.append('z', '3');
            u.searchParams.sort();
            var pairs = '';
            u.searchParams.forEach(function (v, k) { pairs += k + '=' + v + ';'; });
            var it = u.searchParams.entries(), step, entries = '';
            while (!(step = it.next()).done) entries += step.value[0] + step.value[1] + ' ';
            log('url ' + u.href + ' ' + u.pathname + ' ' + pairs + ' ' + entries + new URLSearchParams([['a', '1'], ['b', '2']]).toString() + ' ' +
                new URLSearchParams({ c: '3' }).toString() + ' ' + URL.canParse('no scheme'));

            var enc = new TextEncoder().encode('hé');
            var dst = new Uint8Array(2), into = new TextEncoder().encodeInto('abc', dst);
            log('text ' + enc[0] + ',' + enc[1] + ',' + enc[2] + ',' + enc[3] + ' ' + new TextDecoder('utf-8').decode(enc) + ' ' + into.read + '/' + into.written +
                ' ' + dst[0] + ',' + dst[1] + ' ' + new TextDecoder('latin1').encoding + ' ' + btoa('hi') + ' ' + atob('aGk='));

            var blob = new Blob(['ab', new Uint8Array([99])], { type: 'Text/Plain' });
            var part = blob.slice(1, 3, 'X/Y');
            var file = new File([blob], 'f.txt', { lastModified: 5 });
            log('blob ' + blob.size + ' ' + blob.type + ' ' + part.size + ' ' + part.type + ' ' + file.name + ' ' + file.lastModified + ' ' + file.size + ' ' +
                new Blob(['x']).type + '|');

            var name = '', code = 0;
            try { box.matches(''); } catch (e) { name = e.name; code = e.code; log('exception ' + name + ' ' + code + ' ' + (e instanceof DOMException) + ' ' +
                new DOMException('m', 'NotFoundError').code + ' ' + new DOMException('m', 'toString').code); }

            document.title = ' New   title ';
            document.cookie = 'a=1; path=/';
            document.cookie = 'b = 2';
            localStorage.k = 'v';
            log('doc ' + document.title + '|' + document.cookie + '|' + localStorage.k + '|' + getComputedStyle(box).display + '|' + document.readyState + '|' +
                document.getElementsByName('r').length);

            queueMicrotask(function () { log('micro'); });
            document.addEventListener('DOMContentLoaded', function () { log('loaded ' + document.readyState + ' ' + (document.getElementById('written') !== null)); });
            window.addEventListener('load', function () { log('window load'); });
            document.writeln('<i id="written">w</i>');

            var reader = new FileReader(), events = '';
            reader.addEventListener('loadstart', function (e) { events += 'start:' + e.bubbles + ' '; });
            reader.onload = function (e) { events += 'load:' + e.loaded + '/' + e.total + ':' + e.lengthComputable + ' '; };
            reader.addEventListener('loadend', function () { log('reader ' + events + reader.result); });
            reader.readAsText(part.slice(0, 2));
            setTimeout(function (x) { log('timer ' + x); }, 0, 'x');

            var tapme = document.getElementById('tapme'), check2 = document.getElementById('check2'), tapped = '';
            tapme.addEventListener('focus', function () { tapped += 'focus '; });
            check2.addEventListener('click', function () { tapped += 'click '; });
            check2.addEventListener('change', function () { tapped += 'change '; log('tap ' + tapped + check2.hasAttribute('checked')); });
            var rs = navigator.epubReadingSystem;
            log('features ' + rs.hasFeature('dom-manipulation') + ' ' + rs.hasFeature('toString') + ' ' + rs.hasFeature('touch-events'));
            try { window.__kite_step = null; } catch (e) {}
            try { delete window.__kite_tap; } catch (e) {}
        """.trimIndent()

        /**
         * A script that patches what a book can of the engine's built-ins, so each throws when it
         * runs, but the iterator of an array, which Web IDL's own algorithms call.
         */
        val POISON = """
            (function () {
              var ownKeys = Reflect.ownKeys, describe = Object.getOwnPropertyDescriptor, define = Object.defineProperty, protoOf = Object.getPrototypeOf;
              var NativeError = Error, NativeString = String;
              // What ran, in the message of what it throws.
              function poisonedAs(what) { return function () { throw new NativeError('a patched built-in ran: ' + what); }; }
              var poisoned = poisonedAs('a DOM method or a key of Object.prototype');
              var arrayIterator = protoOf([][Symbol.iterator]()), iterator = Symbol.iterator;
              // What the engine itself calls, so a book that patches it breaks the engine and not the DOM alone.
              var engineCalls = { __proto__: null, 'String.prototype': { __proto__: null, toString: 1, valueOf: 1 } };
              function poison(name, o, keep) {
                var keys = ownKeys(o), engine = engineCalls[name];
                for (var i = 0; i < keys.length; i++) {
                  var k = keys[i];
                  if (k === keep || (engine && typeof k === 'string' && engine[k])) continue;
                  var d, as = poisonedAs(name + '.' + NativeString(k));
                  // KiteJS 0.2.0 hands out a placeholder that no script can read as the value of a
                  // property it builds lazily, such as Array.prototype[Symbol.unscopables].
                  try { d = describe(o, k); if (d) typeof d.value; } catch (e) { continue; }
                  // KiteJS 0.2.0 lists the symbol methods of RegExp.prototype and Date.prototype
                  // as strings that name no property (kitejs#76), so it cannot reach them.
                  if (d === undefined) continue;
                  if (!d.configurable) continue;
                  if (d.get || d.set) define(o, k, { __proto__: null, get: as, set: as, configurable: true });
                  else if (typeof d.value === 'function') define(o, k, { __proto__: null, value: as, writable: true, configurable: true });
                }
              }
              var targets = [['Object', Object], ['Object.prototype', Object.prototype], ['Function.prototype', Function.prototype],
                ['Array', Array], ['String', String], ['String.prototype', String.prototype], ['Number', Number],
                ['Number.prototype', Number.prototype], ['Boolean.prototype', Boolean.prototype], ['Symbol', Symbol],
                ['Symbol.prototype', Symbol.prototype], ['RegExp', RegExp], ['RegExp.prototype', RegExp.prototype], ['Map', Map],
                ['Map.prototype', Map.prototype], ['WeakMap', WeakMap], ['WeakMap.prototype', WeakMap.prototype], ['Set.prototype', Set.prototype],
                ['WeakSet.prototype', WeakSet.prototype], ['Promise', Promise], ['Promise.prototype', Promise.prototype], ['Math', Math],
                ['JSON', JSON], ['Reflect', Reflect], ['Date', Date], ['Date.prototype', Date.prototype], ['Error', Error],
                ['Error.prototype', Error.prototype], ['ArrayBuffer', ArrayBuffer], ['ArrayBuffer.prototype', ArrayBuffer.prototype],
                ['DataView.prototype', DataView.prototype], ['%TypedArray%', protoOf(Uint8Array)], ['%TypedArray%.prototype', protoOf(Uint8Array.prototype)],
                ['Uint8Array.prototype', Uint8Array.prototype], ['%GeneratorPrototype%', protoOf(protoOf((function* () {})()))],
                ['%MapIteratorPrototype%', protoOf(new Map().entries())], ['%StringIteratorPrototype%', protoOf(''[Symbol.iterator]())]];
              if (typeof SharedArrayBuffer === 'function') targets[targets.length] = ['SharedArrayBuffer.prototype', SharedArrayBuffer.prototype];
              // What the DOM's algorithms called by name on its own objects: a script may replace these.
              var storage = protoOf(localStorage);
              var dom = [
                [Element.prototype, ['closest', 'querySelector', 'querySelectorAll', 'localName', 'insertAdjacentHTML']],
                [Document.prototype, ['querySelector', 'querySelectorAll', 'createElement', 'write', 'body', 'head', 'documentElement']],
                [DocumentFragment.prototype, ['querySelector', 'querySelectorAll']],
                [Node.prototype, ['contains', 'appendChild', 'insertBefore', 'removeChild', 'parentNode', 'nodeType', 'firstChild']],
                [HTMLElement.prototype, ['focus', 'blur']],
                [HTMLInputElement.prototype, ['checked', 'type', 'disabled', 'form']],
                [HTMLButtonElement.prototype, ['type', 'form', 'disabled']],
                [HTMLSelectElement.prototype, ['options', 'multiple']],
                [HTMLOptionElement.prototype, ['selected', 'value']],
                [HTMLFormElement.prototype, ['elements']],
                [HTMLLabelElement.prototype, ['control']],
                [HTMLDetailsElement.prototype, ['open']],
                [location, ['href', 'toString']],
                [storage, ['getItem', 'setItem', 'removeItem']]
              ];
              for (var j = 0; j < dom.length; j++) {
                var o = dom[j][0], keys = dom[j][1];
                for (var n = 0; n < keys.length; n++) {
                  var d = describe(o, keys[n]);
                  if (d && (d.get || d.set)) define(o, keys[n], { __proto__: null, get: poisoned, set: poisoned, configurable: true });
                  else define(o, keys[n], { __proto__: null, value: poisoned, writable: true, configurable: true });
                }
              }
              // The controls that the DOM clicks or focuses itself, for a label or a tap.
              var controls = ['check', 'check2', 'tapme'];
              for (var c = 0; c < controls.length; c++) {
                var el = document.getElementById(controls[c]);
                el.click = poisoned;
                el.focus = poisoned;
              }
              // Keys that the DOM's own tables and dictionaries must not inherit.
              var junk = { __proto__: null, bubbles: true, cancelable: true, composed: true, detail: 'junk', view: 'junk', relatedTarget: 'junk',
                lengthComputable: false, loaded: 7, total: 7, fatal: true, ignoreBOM: true, stream: true, get: poisoned, set: poisoned,
                has: poisoned, ownKeys: poisoned, getOwnPropertyDescriptor: poisoned, deleteProperty: poisoned, value: 'junk',
                writable: true, enumerable: true, configurable: true, p: poisoned, div: poisoned, label: poisoned, ping: ['junk'],
                'dom-manipulation': false, NotFoundError: 99, load: 0, onclick: poisoned, onping: poisoned, next: poisoned };
              var junkKeys = ownKeys(junk);
              for (var p = 0; p < targets.length; p++) poison(targets[p][0], targets[p][1]);
              poison('Array.prototype', Array.prototype, iterator);
              poison('%ArrayIteratorPrototype%', arrayIterator, 'next');
              for (var q = 0; q < junkKeys.length; q++) {
                define(Object.prototype, junkKeys[q], { __proto__: null, value: junk[junkKeys[q]], writable: true, enumerable: true, configurable: true });
              }
              define(Array.prototype, 'junk', { __proto__: null, value: 'junk', writable: true, enumerable: true, configurable: true });
              // The global bindings of the constructors stay: KiteJS 0.2.0 takes the prototype of a literal and of a
              // primitive from them. The DOM shadows them, which DomPreludeSourceTest checks.
            })();
        """.trimIndent()
    }
}
