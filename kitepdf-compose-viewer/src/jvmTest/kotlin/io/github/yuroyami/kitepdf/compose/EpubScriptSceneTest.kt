package io.github.yuroyami.kitepdf.compose

import androidx.compose.foundation.layout.fillMaxSize
import io.github.yuroyami.kitepdf.compose.EdtImageComposeScene as ImageComposeScene
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.PixelMap
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.unit.Density
import io.github.yuroyami.kitepdf.core.KiteLocation
import io.github.yuroyami.kitepdf.core.KiteRectangle
import io.github.yuroyami.kitepdf.epub.EpubDocument
import io.github.yuroyami.kitepdf.epub.EpubPage
import io.github.yuroyami.kitepdf.epub.EpubSettings
import io.github.yuroyami.kitepdf.javascript.EpubScriptRunner
import java.io.ByteArrayOutputStream
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * A book's scripts in `KiteDocView` (#41): a tap on a scripted fixed-layout page runs its
 * button's script and the screen shows the change, a script that shows a hidden section gives
 * the view more pages, a link that a script prevents is not followed, and a timer runs by itself.
 */
@OptIn(ExperimentalComposeUiApi::class)
class EpubScriptSceneTest {

    private val runners = ArrayList<EpubScriptRunner>()

    @AfterTest
    fun closeRunners() {
        runners.forEach { it.close() }
    }

    private fun runner(book: EpubDocument) = EpubScriptRunner(book).also { runners += it }

    /**
     * A fixed-layout page of 300 by 200 CSS pixels: a red band 60 pixels tall across the top, and
     * a button at (10, 100), 120 by 40, whose click runs `paint()` from `page.js`, which turns the
     * band blue. [script] runs at the end of the body.
     */
    private fun buttonBook(script: String = ""): EpubDocument {
        val page = """<?xml version="1.0"?><html xmlns="http://www.w3.org/1999/xhtml"><head>
            <meta name="viewport" content="width=300, height=200"/><script type="text/javascript" src="page.js"></script>
            <style type="text/css">body { margin: 0; } h1 { margin: 0; height: 60px; background: rgb(255, 0, 0); color: rgb(255, 0, 0); }
            #go { display: block; position: absolute; left: 10px; top: 100px; width: 120px; height: 40px; margin: 0; }</style></head>
            <body><h1 id="band">Band</h1><button id="go" type="button" onclick="paint()">Go</button>${if (script.isEmpty()) "" else "<script>$script</script>"}</body></html>"""
        return book(
            fixed = true,
            items = listOf("page.xhtml" to "application/xhtml+xml", "page.js" to "text/javascript"),
            files = mapOf(
                "page.xhtml" to page,
                "page.js" to "function paint() { document.getElementById('band').style.background = 'rgb(0, 0, 255)'; }",
            ),
        )
    }

    private fun book(fixed: Boolean, items: List<Pair<String, String>>, files: Map<String, String>, settings: EpubSettings = EpubSettings()): EpubDocument {
        val container = """<?xml version="1.0"?><container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container"><rootfiles><rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/></rootfiles></container>"""
        val layout = if (fixed) """<meta property="rendition:layout">pre-paginated</meta>""" else ""
        val manifest = items.mapIndexed { i, (href, type) ->
            val scripted = if (type == "application/xhtml+xml") """ properties="scripted"""" else ""
            """<item id="i$i" href="$href" media-type="$type"$scripted/>"""
        }.joinToString("")
        val spine = items.mapIndexedNotNull { i, (_, type) -> if (type == "application/xhtml+xml") """<itemref idref="i$i"/>""" else null }.joinToString("")
        val opf = """<?xml version="1.0"?><package xmlns="http://www.idpf.org/2007/opf" version="3.0" unique-identifier="id">
            <metadata xmlns:dc="http://purl.org/dc/elements/1.1/"><dc:identifier id="id">scripted</dc:identifier>$layout</metadata>
            <manifest>$manifest</manifest><spine>$spine</spine></package>"""
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            zip.setMethod(ZipOutputStream.STORED)
            val entries = listOf("mimetype" to "application/epub+zip", "META-INF/container.xml" to container, "OEBPS/content.opf" to opf) +
                files.map { (name, text) -> "OEBPS/$name" to text }
            for ((name, text) in entries) {
                val data = text.encodeToByteArray()
                zip.putNextEntry(
                    ZipEntry(name).apply {
                        method = ZipEntry.STORED
                        size = data.size.toLong()
                        compressedSize = data.size.toLong()
                        crc = CRC32().apply { update(data) }.value
                    },
                )
                zip.write(data)
                zip.closeEntry()
            }
        }
        return EpubDocument.open(out.toByteArray(), settings)
    }

    private fun tap(scene: ImageComposeScene, at: Offset) {
        scene.sendPointerEvent(PointerEventType.Press, at, type = PointerType.Touch)
        scene.sendPointerEvent(PointerEventType.Release, at, type = PointerType.Touch)
    }

    private fun isRed(pixels: PixelMap, at: Offset): Boolean = pixels[at.x.toInt(), at.y.toInt()].let { it.red > 0.8f && it.green < 0.2f && it.blue < 0.2f }
    private fun isBlue(pixels: PixelMap, at: Offset): Boolean = pixels[at.x.toInt(), at.y.toInt()].let { it.blue > 0.8f && it.red < 0.2f && it.green < 0.2f }

    @Test
    fun a_tap_on_a_scripted_fixed_layout_page_runs_its_script_on_screen() = forBothEffectOrders { queued ->
        val doc = buttonBook()
        val scripts = runner(doc)
        lateinit var state: KiteDocViewState
        val (scene, driver) = drivenScene(300, 400, queued) {
            state = rememberKiteDocViewState(doc)
            KiteDocView(state = state, modifier = Modifier.fillMaxSize(), zoomSpec = KiteZoomSpec(doubleTapEnabled = false), epubScripts = scripts)
        }
        scene.use {
            driver.pumpUntilState { state.pageGeometry.isNotEmpty() }
            // The band is the top 45 of the page's 150 points, the button 7.5 to 97.5 across and 75 to 105 down.
            val band = assertNotNull(state.displayRectToViewport(0, KiteRectangle(0.0, 0.0, 225.0, 45.0))).center
            val button = assertNotNull(state.displayRectToViewport(0, KiteRectangle(7.5, 75.0, 97.5, 105.0))).center
            driver.pumpUntil { isRed(it, band) }

            tap(scene, button)

            driver.pumpUntil { isBlue(it, band) }
            assertEquals(emptyList(), scripts.failures.map { it.message })
        }
    }

    @Test
    fun a_script_that_shows_a_hidden_section_gives_the_view_more_pages() = forBothEffectOrders { queued ->
        val extra = (0 until 40).joinToString("") { "<p>Extra paragraph $it, which the button shows.</p>" }
        val doc = book(
            fixed = false,
            items = listOf("chapter.xhtml" to "application/xhtml+xml"),
            files = mapOf(
                "chapter.xhtml" to """<?xml version="1.0"?><html xmlns="http://www.w3.org/1999/xhtml"><body>
                    <p>Intro.</p><p><button id="more" type="button">More</button></p><div id="extra" hidden="hidden">$extra</div>
                    <script>document.getElementById('more').onclick = function () { document.getElementById('extra').hidden = false; };</script>
                    </body></html>""",
            ),
            settings = EpubSettings(pageWidth = 300.0, pageHeight = 400.0, margin = 20.0),
        )
        val scripts = runner(doc)
        lateinit var state: KiteDocViewState
        val (scene, driver) = drivenScene(300, 400, queued) {
            state = rememberKiteDocViewState(doc)
            KiteDocView(state = state, modifier = Modifier.fillMaxSize(), zoomSpec = KiteZoomSpec(doubleTapEnabled = false), epubScripts = scripts)
        }
        scene.use {
            driver.pumpUntilState { state.pageGeometry.isNotEmpty() && state.isComplete }
            assertEquals(1, state.itemCount, "the hidden section takes no room")
            val page = doc.page(KiteLocation(0, 0))
            val line = page.textContent().blocks.flatMap { it.lines }.first { "More" in it.text }
            val more = assertNotNull(state.displayRectToViewport(0, line.bounds)).center

            tap(scene, more)

            driver.pumpUntilState { state.itemCount > 1 }
            assertEquals(doc.pageCountIn(0), state.itemCount, "the view shows every page the chapter has now")
        }
    }

    @Test
    fun a_link_that_a_script_prevents_is_not_followed_and_a_plain_one_is() {
        val doc = book(
            fixed = false,
            items = listOf("chapter.xhtml" to "application/xhtml+xml", "next.xhtml" to "application/xhtml+xml"),
            files = mapOf(
                "chapter.xhtml" to """<?xml version="1.0"?><html xmlns="http://www.w3.org/1999/xhtml"><body>
                    <p><a href="next.xhtml" onclick="return false;">Stay here</a></p><p><a href="next.xhtml">Go on</a></p>
                    <script>var ready = true;</script></body></html>""",
                "next.xhtml" to """<?xml version="1.0"?><html xmlns="http://www.w3.org/1999/xhtml"><body><p>Next.</p></body></html>""",
            ),
            settings = EpubSettings(pageWidth = 300.0, pageHeight = 400.0, margin = 20.0),
        )
        val scripts = runner(doc)
        val offered = ArrayList<String>()
        lateinit var state: KiteDocViewState
        ImageComposeScene(width = 300, height = 400, density = Density(1f)) {
            state = rememberKiteDocViewState(doc)
            KiteDocView(
                state = state, modifier = Modifier.fillMaxSize(), zoomSpec = KiteZoomSpec(doubleTapEnabled = false),
                onLinkTap = { offered += (it as KiteLinkAction.Epub).link.href; true },
                epubScripts = scripts,
            )
        }.use { scene ->
            val driver = SceneTestDriver(scene)
            driver.pumpUntilState { state.pageGeometry.isNotEmpty() }
            val page = doc.page(KiteLocation(0, 0)) as EpubPage
            fun centreOf(text: String) = assertNotNull(
                state.displayRectToViewport(0, page.textContent().blocks.flatMap { it.lines }.first { text in it.text }.bounds),
            ).center

            tap(scene, centreOf("Stay here"))
            driver.pumpFrames(20)
            assertEquals(emptyList(), offered, "the script took the tap")

            tap(scene, centreOf("Go on"))
            driver.pumpUntilState { offered.isNotEmpty() }
            assertEquals(listOf("OEBPS/next.xhtml"), offered)
        }
    }

    /** A first chapter, then a chapter of notes that styles and logs its target. */
    private fun notesBook(): EpubDocument = book(
        fixed = false,
        items = listOf("first.xhtml" to "application/xhtml+xml", "notes.xhtml" to "application/xhtml+xml"),
        files = mapOf(
            "first.xhtml" to """<?xml version="1.0"?><html xmlns="http://www.w3.org/1999/xhtml"><body><p>First.</p></body></html>""",
            "notes.xhtml" to """<?xml version="1.0"?><html xmlns="http://www.w3.org/1999/xhtml"><head>
                <style type="text/css">:target { background: rgb(255, 0, 0); }</style></head><body>
                <p id="n">Note n.</p><p id="m">Note m.</p>
                <script>window.onload = function () { console.log('load ' + location.hash + ' ' + document.querySelector(':target').id); };
                window.onhashchange = function () { console.log('hashchange ' + location.hash + ' ' + document.querySelector(':target').id); };</script>
                </body></html>""",
        ),
        settings = EpubSettings(pageWidth = 300.0, pageHeight = 400.0, margin = 20.0),
    )

    @Test
    fun the_view_gives_a_chapter_and_its_scripts_the_fragment_the_reader_goes_to() = forBothEffectOrders { queued ->
        val doc = notesBook()
        val log = ArrayList<String>()
        val scripts = EpubScriptRunner(doc, onConsole = { _, message -> synchronized(log) { log += message } }).also { runners += it }
        lateinit var state: KiteDocViewState
        val (scene, driver) = drivenScene(300, 400, queued) {
            state = rememberKiteDocViewState(doc)
            KiteDocView(state = state, modifier = Modifier.fillMaxSize(), epubScripts = scripts)
        }
        scene.use {
            driver.pumpUntilState { state.pageGeometry.isNotEmpty() }
            driver.runOnUi { state.scrollTo(assertNotNull(doc.bookmarkOf("OEBPS/notes.xhtml#n"))) }
            driver.pumpUntilState { synchronized(log) { "load #n n" in log } }
            assertEquals("n", doc.fragmentOf(1))

            driver.runOnUi { state.scrollTo(assertNotNull(doc.bookmarkOf("OEBPS/notes.xhtml#m"))) }
            driver.pumpUntilState { synchronized(log) { "hashchange #m m" in log } }
            assertEquals(emptyList(), scripts.failures.map { it.message })
        }
    }

    @Test
    fun a_view_without_scripts_gives_the_book_the_fragment_the_reader_goes_to() = forBothEffectOrders { queued ->
        val doc = notesBook()
        lateinit var state: KiteDocViewState
        val (scene, driver) = drivenScene(300, 400, queued) {
            state = rememberKiteDocViewState(doc)
            KiteDocView(state = state, modifier = Modifier.fillMaxSize())
        }
        scene.use {
            driver.pumpUntilState { state.pageGeometry.isNotEmpty() }
            driver.runOnUi { state.scrollTo(assertNotNull(doc.bookmarkOf("OEBPS/notes.xhtml#m"))) }
            driver.pumpUntilState { doc.fragmentOf(1) == "m" }
        }
    }

    @Test
    fun a_timer_that_a_script_set_runs_and_shows() {
        val doc = buttonBook(script = "setTimeout(paint, 200);")
        val scripts = runner(doc)
        lateinit var state: KiteDocViewState
        ImageComposeScene(width = 300, height = 400, density = Density(1f)) {
            state = rememberKiteDocViewState(doc)
            KiteDocView(state = state, modifier = Modifier.fillMaxSize(), epubScripts = scripts)
        }.use { scene ->
            val driver = SceneTestDriver(scene)
            driver.pumpUntilState { state.pageGeometry.isNotEmpty() }
            val band = assertNotNull(state.displayRectToViewport(0, KiteRectangle(0.0, 0.0, 225.0, 45.0))).center
            driver.pumpUntil { isBlue(it, band) }
            assertTrue(!scripts.hasTimers, "nothing waits once the timer ran")
        }
    }
}
