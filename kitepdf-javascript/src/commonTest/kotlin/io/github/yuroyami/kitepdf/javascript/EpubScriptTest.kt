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
}
