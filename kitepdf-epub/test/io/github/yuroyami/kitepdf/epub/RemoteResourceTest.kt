package io.github.yuroyami.kitepdf.epub

import io.github.yuroyami.kitepdf.core.KiteLocation
import io.github.yuroyami.kitepdf.core.KiteLock
import io.github.yuroyami.kitepdf.core.render.RecordingCanvas
import io.github.yuroyami.kitepdf.core.withLock
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * A resource that a book names by an https URL instead of a file in its container (EPUB 3.3, 3.6)
 * goes through the fetcher of the settings, and never through it for plain http (EPUB Reading
 * Systems 3.3, 3.3). Such a resource used to load nothing at all (#38).
 */
class RemoteResourceTest {

    private val pictureUrl = "https://images.example.com/pic.bmp"
    private val fontUrl = "https://fonts.example.com/square.ttf"

    /**
     * A fetcher that serves [files] by URL, and records each URL it is asked for. The book runs
     * its fetches on several threads at once, so the record takes a lock, or one of two URLs
     * asked at the same moment can go missing from it.
     */
    private class FakeFetcher(
        private val files: Map<String, ByteArray>,
        private val gate: CompletableDeferred<Unit>? = null,
    ) : EpubResourceFetcher {
        private val lock = KiteLock()
        private val record = ArrayList<String>()

        /** The URLs asked for so far, in the order they were asked. */
        val asked: List<String> get() = lock.withLock { record.toList() }

        override suspend fun fetch(url: String): ByteArray? {
            lock.withLock { record += url }
            gate?.await()
            return files[url]
        }
    }

    /** A book of one chapter per entry of [bodies], with [manifest] items besides them and [files] under OEBPS. */
    private fun book(
        bodies: List<String>,
        fetcher: EpubResourceFetcher? = null,
        manifest: String = "",
        properties: List<String?> = bodies.map { null },
        files: List<Pair<String, ByteArray>> = emptyList(),
        layoutCacheBytes: Long = EpubSettings().layoutCacheBytes,
    ): EpubDocument {
        val container = """<?xml version="1.0"?><container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container"><rootfiles><rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/></rootfiles></container>"""
        val items = bodies.indices.joinToString("") { i ->
            val props = properties[i]?.let { """ properties="$it"""" }.orEmpty()
            """<item id="c$i" href="c$i.xhtml" media-type="application/xhtml+xml"$props/>"""
        }
        // The book lists each of its files, as every resource it uses must be (#516).
        val listed = EpubFixtures.manifestItems(files.filter { (name, _) -> "href=\"$name\"" !in manifest }.map { (name, bytes) -> "OEBPS/$name" to bytes })
        val opf = """<?xml version="1.0"?><package xmlns="http://www.idpf.org/2007/opf" version="3.0">
            <manifest>$items$manifest$listed</manifest><spine>${bodies.indices.joinToString("") { """<itemref idref="c$it"/>""" }}</spine></package>"""
        val chapters = bodies.mapIndexed { i, body ->
            "OEBPS/c$i.xhtml" to """<?xml version="1.0"?><html xmlns="http://www.w3.org/1999/xhtml"><body>$body</body></html>""".encodeToByteArray()
        }
        return EpubDocument.open(
            EpubFixtures.storedZip(
                listOf(
                    "mimetype" to "application/epub+zip".encodeToByteArray(),
                    "META-INF/container.xml" to container.encodeToByteArray(),
                    "OEBPS/content.opf" to opf.encodeToByteArray(),
                ) + chapters + files.map { (name, bytes) -> "OEBPS/$name" to bytes },
            ),
            EpubSettings(pageWidth = 400.0, pageHeight = 600.0, layoutCacheBytes = layoutCacheBytes, resourceFetcher = fetcher),
        ) ?: error("the book did not open")
    }

    private fun render(doc: EpubDocument, chapter: Int = 0): RecordingCanvas =
        RecordingCanvas().also { doc.page(KiteLocation(chapter, 0)).renderTo(it) }

    private fun RecordingCanvas.images() = calls.filterIsInstance<RecordingCanvas.Call.Image>()

    /** Each text run of the page with where it sits, to show that a box kept its room. */
    private fun RecordingCanvas.textPlaces() = calls.filterIsInstance<RecordingCanvas.Call.Glyphs>()
        .map { it.text to it.textToDevice.f }

    private fun RecordingCanvas.outlinedText() = calls.filterIsInstance<RecordingCanvas.Call.Glyphs>()
        .filter { it.hasOutlines }.joinToString("") { it.text }

    private fun sized(src: String, style: String = "") =
        """<p>Before</p><p><img src="$src" width="40" height="30" alt="A picture"$style/></p><p>After</p>"""

    @Test
    fun without_a_fetcher_an_image_with_a_declared_size_keeps_its_room_and_draws_nothing() {
        for (style in listOf("", """ style="display:block"""")) {
            val remote = render(book(listOf(sized(pictureUrl, style))))
            val local = render(book(listOf(sized("pic.bmp", style)), files = listOf("pic.bmp" to EpubFixtures.bmp2x1())))
            assertTrue(remote.images().isEmpty(), "nothing was fetched, so nothing is drawn")
            assertEquals(1, local.images().size)
            assertEquals(local.textPlaces(), remote.textPlaces(), "the remote box holds the room of the local one ($style)")
        }
    }

    @Test
    fun a_fetcher_draws_the_image_once_its_bytes_land_and_the_page_says_so() = runTest {
        for (style in listOf("", """ style="display:block"""")) {
            val gate = CompletableDeferred<Unit>()
            val fetcher = FakeFetcher(mapOf(pictureUrl to EpubFixtures.bmp2x1()), gate)
            val doc = book(listOf(sized(pictureUrl, style)), fetcher)
            val page = doc.page(KiteLocation(0, 0))
            doc.prepareChapter(0)
            val pagesBefore = doc.pageCountIn(0)

            assertTrue(render(doc).images().isEmpty(), "the bytes have not landed ($style)")
            val before = page.remoteVersion
            gate.complete(Unit)
            doc.fetchRemoteResources(0)

            assertEquals(listOf(pictureUrl), fetcher.asked, "one fetch, however many paints asked")
            assertTrue(page.remoteVersion > before, "a page that painted without the bytes must paint again ($style)")
            assertEquals(1, doc.remoteArrivals.value)
            val local = book(listOf(sized("pic.bmp", style)), files = listOf("pic.bmp" to EpubFixtures.bmp2x1()))
            assertEquals(render(local).images().single().ctm, render(doc).images().single().ctm, "the picture fills its box as a local one does ($style)")
            assertEquals(pagesBefore, doc.pageCountIn(0), "the page count holds")
        }
    }

    @Test
    fun a_contained_remote_picture_is_letterboxed_in_its_declared_box() = runTest {
        // Its layout had no picture to fit the box to, so the box keeps its size and the picture
        // sits in the middle of it (CSS Images 3, 5.5 and 5.6).
        val fetcher = FakeFetcher(mapOf(pictureUrl to EpubFixtures.bmp2x1()))
        val doc = book(listOf(sized(pictureUrl, """ style="display:block;object-fit:contain"""")), fetcher)
        val filled = book(listOf(sized(pictureUrl, """ style="display:block"""")), fetcher)
        doc.fetchRemoteResources(0)
        filled.fetchRemoteResources(0)
        val fill = render(filled).images().single().ctm
        val contained = render(doc).images().single().ctm
        assertEquals(fill.a, contained.a, 1e-9, "the 2:1 picture spans the 4:3 box")
        assertEquals(fill.a / 2.0, contained.d, 1e-9, "and keeps its aspect")
        assertEquals(fill.e, contained.e, 1e-9)
        assertEquals(fill.f + (fill.d - contained.d) / 2.0, contained.f, 1e-9, "centred on the block axis")
    }

    @Test
    fun a_plain_http_url_is_never_fetched() = runTest {
        val http = "http://images.example.com/pic.bmp"
        val fetcher = FakeFetcher(mapOf(http to EpubFixtures.bmp2x1()))
        val doc = book(
            listOf(
                sized(http) + """<div style="background-image:url($http);height:20px">Bg</div>""" +
                    """<style>@font-face{font-family:'Remote';src:url(http://fonts.example.com/square.ttf)}</style>""" +
                    """<p style="font-family:'Remote'">AAA</p>""",
            ),
            fetcher,
        )
        assertTrue(doc.hasRemoteResources(0))
        doc.fetchRemoteResources(0)
        val canvas = render(doc)
        assertTrue(canvas.images().isEmpty())
        assertEquals("", canvas.outlinedText())
        assertEquals(emptyList(), fetcher.asked, "http goes nowhere")
    }

    @Test
    fun a_remotely_fetched_font_shapes_text() = runTest {
        val face = """<style>@font-face{font-family:'Remote';src:url($fontUrl)}</style><p style="font-family:'Remote'">AAA</p>"""
        val fetcher = FakeFetcher(mapOf(fontUrl to EpubFixtures.squareTtf()))
        val doc = book(listOf(face), fetcher)
        doc.fetchRemoteResources(0, layoutOnly = true)
        assertEquals(listOf(fontUrl), fetcher.asked)
        assertEquals("AAA", render(doc).outlinedText(), "the fetched face shapes the text")

        assertEquals("", render(book(listOf(face))).outlinedText(), "no fetcher, no face")
    }

    @Test
    fun a_remote_font_in_a_manifest_style_sheet_shapes_text() = runTest {
        val fetcher = FakeFetcher(mapOf(fontUrl to EpubFixtures.squareTtf()))
        val doc = book(
            listOf("""<link rel="stylesheet" href="book.css"/><p class="r">AAA</p>"""),
            fetcher,
            manifest = """<item id="css" href="book.css" media-type="text/css"/>""",
            files = listOf("book.css" to "@font-face{font-family:'Remote';src:url($fontUrl)} .r{font-family:'Remote'}".encodeToByteArray()),
        )
        assertTrue(doc.hasRemoteResources(0))
        doc.fetchRemoteResources(0, layoutOnly = true)
        assertEquals("AAA", render(doc).outlinedText())
    }

    @Test
    fun a_remote_font_without_bytes_yields_to_the_next_source() {
        val face = """<style>@font-face{font-family:'Remote';src:url($fontUrl), url(square.ttf)}</style><p style="font-family:'Remote'">AAA</p>"""
        val doc = book(listOf(face), files = listOf("square.ttf" to EpubFixtures.squareTtf()))
        assertEquals("AAA", render(doc).outlinedText(), "the source in the book stands in (CSS Fonts 4, 4.3)")
    }

    @Test
    fun a_chapter_keeps_what_its_first_layout_found_and_the_next_document_finds_the_rest() = runTest {
        val unsized = """<p>Before</p><p><img src="$pictureUrl" alt=""/></p><p>After</p>"""
        val fetcher = FakeFetcher(mapOf(pictureUrl to EpubFixtures.bmp2x1()), CompletableDeferred())
        val doc = book(listOf(unsized), fetcher)
        val first = render(doc)
        assertTrue(first.images().isEmpty(), "laid out before the bytes, so no box")

        val landed = book(listOf(unsized), FakeFetcher(mapOf(pictureUrl to EpubFixtures.bmp2x1())))
        landed.fetchRemoteResources(0, layoutOnly = true)
        assertEquals(1, render(landed).images().size, "a chapter laid out after its bytes landed sizes the box by them")

        // The same document lays the chapter out the same way while the bytes stay out.
        assertEquals(first.textPlaces(), render(doc).textPlaces())
    }

    @Test
    fun a_chapter_laid_out_again_keeps_the_font_its_first_layout_lacked() = runTest {
        // Chapter 0 lays out before the font lands, chapter 1 after, and a budget of nothing
        // drops chapter 0, so its next render lays it out again (#38).
        val gate = CompletableDeferred<Unit>()
        val fetcher = FakeFetcher(mapOf(fontUrl to EpubFixtures.squareTtf()), gate)
        val doc = book(
            List(2) { """<link rel="stylesheet" href="book.css"/><p class="r">AAA</p>""" },
            fetcher,
            manifest = """<item id="css" href="book.css" media-type="text/css"/>""",
            files = listOf("book.css" to "@font-face{font-family:'Remote';src:url($fontUrl)} .r{font-family:'Remote'}".encodeToByteArray()),
            layoutCacheBytes = 0L,
        )
        val first = render(doc, chapter = 0)
        assertEquals("", first.outlinedText(), "laid out before the font landed")
        gate.complete(Unit)
        doc.fetchRemoteResources(1, layoutOnly = true)
        assertEquals("AAA", render(doc, chapter = 1).outlinedText(), "laid out after it landed")
        assertFalse(doc.isChapterLive(0), "chapter 0 was dropped")
        assertEquals(first.calls, render(doc, chapter = 0).calls, "and lays out as it first did")
    }

    @Test
    fun a_document_over_the_same_book_finds_the_bytes_that_landed() = runTest {
        val unsized = """<p>Before</p><p><img src="$pictureUrl" alt=""/></p><p>After</p>"""
        val gate = CompletableDeferred<Unit>()
        val fetcher = FakeFetcher(mapOf(pictureUrl to EpubFixtures.bmp2x1()), gate)
        val doc = book(listOf(unsized), fetcher)
        val first = render(doc)
        assertTrue(first.images().isEmpty())
        gate.complete(Unit)
        doc.fetchRemoteResources(0)

        assertEquals(first.textPlaces(), render(doc).textPlaces(), "this document keeps its first layout, and its page count")
        val next = doc.withSettings(doc.settings.copy(fontSize = doc.settings.fontSize + 1.0))
        assertEquals(1, render(next).images().size, "a document laid out after the bytes landed sizes the box by them")
        assertEquals(listOf(pictureUrl), fetcher.asked)
    }

    @Test
    fun layout_only_fetches_what_sizes_the_layout() = runTest {
        val sizedUrl = "https://images.example.com/sized.bmp"
        val unsizedUrl = "https://images.example.com/unsized.bmp"
        val backgroundUrl = "https://images.example.com/bg.bmp"
        val bytes = EpubFixtures.bmp2x1()
        val fetcher = FakeFetcher(mapOf(sizedUrl to bytes, unsizedUrl to bytes, backgroundUrl to bytes))
        val doc = book(
            listOf(
                """<p><img src="$sizedUrl" width="10" height="10" alt=""/><img src="$unsizedUrl" alt=""/></p>""" +
                    """<div style="background-image:url($backgroundUrl);background-repeat:no-repeat;height:20px">Bg</div>""",
            ),
            fetcher,
        )
        doc.fetchRemoteResources(0, layoutOnly = true)
        assertEquals(listOf(unsizedUrl), fetcher.asked)
        doc.fetchRemoteResources(0)
        assertEquals(setOf(sizedUrl, unsizedUrl, backgroundUrl), fetcher.asked.toSet())
        assertEquals(3, fetcher.asked.size, "a URL is fetched once")
        assertEquals(3, render(doc).images().size, "both pictures and the background")
    }

    @Test
    fun a_layout_waits_for_a_stalled_url_once_per_book() = runTest {
        // A picture that sizes every chapter, on a server that never answers, delays the first
        // chapter's layout by the wait and none after it; another URL gets a wait of its own (#492).
        val otherUrl = "https://images.example.com/other.bmp"
        val gate = CompletableDeferred<Unit>()
        val fetcher = FakeFetcher(mapOf(pictureUrl to EpubFixtures.bmp2x1(), otherUrl to EpubFixtures.bmp2x1()), gate)
        val unsized = """<p><img src="$pictureUrl" alt=""/></p>"""
        val doc = book(listOf(unsized, unsized, unsized + """<p><img src="$otherUrl" alt=""/></p>"""), fetcher)
        val start = testScheduler.currentTime
        fun waited() = testScheduler.currentTime - start

        doc.awaitLayoutResources(0, 2.seconds)
        assertEquals(2000L, waited(), "the first chapter waits its time")
        doc.awaitLayoutResources(1, 2.seconds)
        doc.withSettings(doc.settings.copy(fontSize = doc.settings.fontSize + 1.0)).awaitLayoutResources(0, 2.seconds)
        assertEquals(2000L, waited(), "no later wait, of this document or the next, waits for it again")
        doc.awaitLayoutResources(2, 2.seconds)
        assertEquals(4000L, waited(), "a URL that no wait gave up on gets one of its own")

        gate.complete(Unit)
        doc.fetchRemoteResources(2)
        // The two fetches can start together on two threads, so either can be recorded first (#559).
        assertEquals(setOf(pictureUrl, otherUrl), fetcher.asked.toSet())
        assertEquals(2, fetcher.asked.size, "each URL is fetched once")
        assertEquals(2, doc.remoteArrivals.value, "the fetches went on after the waits gave up, and landed")
    }

    @Test
    fun a_layout_wait_ends_when_the_bytes_land() = runTest {
        val doc = book(listOf("""<p><img src="$pictureUrl" alt=""/></p>"""), FakeFetcher(mapOf(pictureUrl to EpubFixtures.bmp2x1())))
        // On a real clock: the test's own clock skips ahead while a fetch runs on another thread.
        withContext(Dispatchers.Default) { doc.awaitLayoutResources(0, 60.seconds) }
        assertEquals(1, render(doc).images().size, "laid out after the wait, the picture sizes its box")
    }

    @Test
    fun a_failed_fetch_is_not_repeated() = runTest {
        var calls = 0
        val doc = book(listOf(sized(pictureUrl)), { _ -> calls++; error("the network is down") })
        doc.fetchRemoteResources(0)
        doc.fetchRemoteResources(0)
        assertTrue(render(doc).images().isEmpty())
        assertEquals(1, calls)
        assertEquals(0, doc.remoteArrivals.value)
    }

    @Test
    fun the_manifest_property_marks_a_chapter_remote() {
        val doc = book(listOf("<p>Plain.</p>", "<p>Plain too.</p>"), properties = listOf("remote-resources", null))
        assertTrue(doc.hasRemoteResources(0), "EPUB 3.3, D.6.4")
        assertFalse(doc.hasRemoteResources(1))
        assertTrue(book(listOf(sized(pictureUrl))).hasRemoteResources(0), "a URL in the markup counts without the property")
    }

    @Test
    fun without_a_fetcher_a_remote_image_takes_its_manifest_fallback() {
        val manifest = """<item id="pic" href="https://images.example.com/pic.jxl" media-type="image/jxl" fallback="bmp"/>
            <item id="bmp" href="pic.bmp" media-type="image/bmp"/>"""
        for (body in listOf(sized("https://images.example.com/pic.jxl"), """<p><img src="https://images.example.com/pic.jxl" alt=""/></p>""")) {
            val doc = book(listOf(body), manifest = manifest, files = listOf("pic.bmp" to EpubFixtures.bmp2x1()))
            assertEquals(1, render(doc).images().size, "the fallback is drawn: $body")
        }
    }
}
