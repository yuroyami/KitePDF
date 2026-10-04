package io.github.yuroyami.kitepdf.javascript

import io.github.yuroyami.kitepdf.core.KiteLocation
import io.github.yuroyami.kitepdf.core.render.RecordingCanvas
import io.github.yuroyami.kitepdf.epub.EpubDocument
import io.github.yuroyami.kitepdf.epub.EpubPage
import io.github.yuroyami.kitepdf.epub.EpubScriptSession
import kotlin.test.AfterTest
import kotlin.test.Test
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

    /** The middle of the first line of [page]'s text that holds [text], in display space. */
    private fun EpubPage.centreOf(text: String): Pair<Double, Double> {
        val line = textContent().blocks.flatMap { it.lines }.first { text in it.text }
        return (line.bounds.left + line.bounds.right) / 2 to (line.bounds.bottom + line.bounds.top) / 2
    }

    @Test
    fun a_tap_on_the_button_runs_its_script_and_the_band_turns_blue() {
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
    fun a_listener_toggles_a_class_that_the_books_stylesheet_paints() {
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
    fun a_reflowable_chapter_gains_pages_when_a_script_shows_a_hidden_section() {
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
    fun a_script_that_prevents_a_link_keeps_the_viewer_from_following_it() {
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
    fun a_timer_runs_when_the_viewer_pumps_it_at_its_time() {
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
    fun a_script_that_never_returns_stops_at_the_budget_and_the_page_still_works() {
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
    fun a_script_outside_the_book_does_not_run() {
        val book = ScriptBooks.buttonPage(head = """<script src="https://example.org/tracker.js"></script>""")
        val scripts = runner(book)
        scripts.chapterOpened(0)
        assertTrue(scripts.failures.any { "outside the book" in it.message.orEmpty() }, "${scripts.failures.map { it.message }}")
    }

    @Test
    fun a_script_that_sets_the_location_goes_through_the_viewer() {
        val book = ScriptBooks.buttonPage(button = """<button id="go" type="button" onclick="location.href = 'next.xhtml#n'">Go</button>""")
        val scripts = runner(book)
        val asked = ArrayList<String>()
        scripts.onNavigate { asked += it }
        scripts.tap(book.page(KiteLocation(0, 0)), 52.5, 90.0)
        assertEquals(listOf("OEBPS/next.xhtml#n"), asked)
    }

    @Test
    fun a_script_cannot_open_a_data_url_as_a_page() {
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
    fun noscript_content_does_not_show_once_scripts_run() {
        val book = ScriptBooks.chapter("""<p>Always.</p><noscript><p>Only without scripts.</p></noscript><script>var x = 1;</script>""")
        val page = book.page(KiteLocation(0, 0))
        assertTrue("Only without scripts" in page.textContent().plainText)
        runner(book).chapterOpened(0)
        assertFalse("Only without scripts" in page.textContent().plainText)
        assertTrue("Always" in page.textContent().plainText)
    }

    @Test
    fun the_dom_answers_as_a_browser_does() {
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
            "\"html\":\"<b>bold</b> text\"",
            "\"first\":\"B\"",
            "\"order\":\"EM,B,#text\"",
            "\"after\":2",
            "\"dataAttr\":\"1\"",
            "\"classes\":\"x y\"",
            "\"style\":\"background-color: red;\"",
            "\"display\":\"block\"",
            "\"weight\":\"700\"",
            "\"closest\":\"BODY\"",
            "\"matches\":true",
            "\"events\":\"body capture,target,body bubble\"",
            "\"stored\":\"v\"",
            "\"ready\":\"loading\"",
        )
        for (part in expected) assertTrue(part in logged, "$part in $logged")
    }

    @Test
    fun a_script_parses_urls_as_the_url_standard_does() {
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
    fun every_scripted_chapter_of_a_book_runs_its_own_scripts() {
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
    fun a_chapter_whose_engine_closed_starts_over_from_its_markup() {
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
    fun an_engine_that_will_not_open_is_a_failure_of_its_chapter() {
        val book = ScriptBooks.chapter("""<p>Plain.</p><script>var x = 1;</script>""")
        val session = EpubScriptSession(book, engineFor = { throw IllegalStateException("no engine here") })
        session.chapterOpened(0)
        assertFalse(session.tap(book.page(KiteLocation(0, 0)), 10.0, 10.0))
        assertTrue(session.failures.any { "no engine here" in it.message.orEmpty() }, "${session.failures.map { it.message }}")
        assertTrue("Plain." in book.page(KiteLocation(0, 0)).textContent().plainText)
        session.close()
    }

    @Test
    fun a_script_finds_the_reading_system_and_what_it_supports() {
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
    fun a_script_measures_an_element_it_just_added_and_restyled() {
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
    fun the_chapters_of_a_book_share_its_origin_and_another_book_has_another() {
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
    fun an_address_under_the_books_origin_is_a_place_in_the_book() {
        val book = ScriptBooks.buttonPage(button = """<button id="go" type="button" onclick="location.href = location.origin + '/OEBPS/next.xhtml#n'">Go</button>""")
        val scripts = runner(book)
        val asked = ArrayList<String>()
        scripts.onNavigate { asked += it }
        scripts.tap(book.page(KiteLocation(0, 0)), 52.5, 90.0)
        assertEquals(listOf("OEBPS/next.xhtml#n"), asked)
    }

    @Test
    fun a_timer_that_throws_each_time_does_not_grow_the_failures_for_ever() {
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
}
