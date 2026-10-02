package io.github.yuroyami.kitepdf.compose

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import io.github.yuroyami.kitepdf.compose.EdtImageComposeScene as ImageComposeScene
import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerType
import io.github.yuroyami.kitepdf.PdfDocument
import io.github.yuroyami.kitepdf.core.KiteDocument
import io.github.yuroyami.kitepdf.epub.EpubDocument
import java.io.ByteArrayOutputStream
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The spread layout pairs pages as the document declares: an EPUB's page-spread and
 * `rendition:spread` properties, a PDF's `/PageLayout`, and the host's `firstPageAlone` (#37).
 */
@OptIn(InternalComposeUiApi::class, ExperimentalComposeUiApi::class)
class SpreadPairingSceneTest {

    private fun spreadsOf(plan: SpreadPlan) = plan.spreads.map { it.toList() }

    @Test
    fun pages_pair_in_order_unless_they_ask_for_a_side() {
        val sides = mapOf(0 to SpreadSide.ALONE, 3 to SpreadSide.LEFT)
        assertEquals(listOf(listOf(0, 1), listOf(2, 3), listOf(4)), spreadsOf(pairSpreads(5, false) { null }))
        assertEquals(listOf(listOf(0), listOf(1, 2), listOf(3, 4)), spreadsOf(pairSpreads(5, false) { sides[it] }))
        // A page on the right with nothing open before it shows alone, left to right.
        assertEquals(listOf(listOf(0), listOf(1, 2), listOf(3)), spreadsOf(pairSpreads(4, false) { if (it == 0) SpreadSide.RIGHT else null }))
        // Right to left, the right side comes first, so the same page opens a spread.
        assertEquals(listOf(listOf(0, 1), listOf(2, 3)), spreadsOf(pairSpreads(4, true) { if (it == 0) SpreadSide.RIGHT else null }))
        // A page on the first side closes the spread before it.
        assertEquals(listOf(listOf(0), listOf(1, 2)), spreadsOf(pairSpreads(3, false) { if (it == 1) SpreadSide.LEFT else null }))
        val plan = pairSpreads(5, false) { sides[it] }
        assertEquals(listOf(0, 1, 1, 2, 2), (0 until 5).map(plan::spreadOf))
        assertEquals(listOf(0, 1, 3), (0 until 3).map(plan::firstPageOf))
    }

    /** A fixed-layout book of one 100 x 200 pixel page per entry of [properties], with [metadata]. */
    private fun fixedBook(properties: List<String?>, metadata: String = ""): EpubDocument {
        val container = """<?xml version="1.0"?><container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container"><rootfiles><rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/></rootfiles></container>"""
        val opf = """<?xml version="1.0"?>
            <package xmlns="http://www.idpf.org/2007/opf" version="3.0" unique-identifier="id">
              <metadata xmlns:dc="http://purl.org/dc/elements/1.1/"><dc:identifier id="id">spreads</dc:identifier>
                <meta property="rendition:layout">pre-paginated</meta>$metadata</metadata>
              <manifest>${properties.indices.joinToString("") { """<item id="c$it" href="c$it.xhtml" media-type="application/xhtml+xml"/>""" }}</manifest>
              <spine>${properties.indices.joinToString("") { i -> """<itemref idref="c$i"${properties[i]?.let { """ properties="$it"""" }.orEmpty()}/>""" }}</spine>
            </package>"""
        val files = properties.indices.map { i ->
            "OEBPS/c$i.xhtml" to """<?xml version="1.0"?><html xmlns="http://www.w3.org/1999/xhtml">
                <head><meta name="viewport" content="width=100, height=200"/></head><body><p>Page $i</p></body></html>""".encodeToByteArray()
        }
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            zip.setMethod(ZipOutputStream.STORED)
            for ((name, data) in listOf(
                "mimetype" to "application/epub+zip".encodeToByteArray(),
                "META-INF/container.xml" to container.encodeToByteArray(),
                "OEBPS/content.opf" to opf.encodeToByteArray(),
            ) + files) {
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
        return EpubDocument.open(out.toByteArray())
    }

    /** A PDF of [count] empty 100 x 200 pages, with [pageLayout] as its `/PageLayout` when set. */
    private fun pagesPdf(count: Int, pageLayout: String? = null): PdfDocument {
        val sb = StringBuilder("%PDF-1.7\n")
        val offsets = ArrayList<Int>()
        fun add(body: String) {
            offsets.add(sb.length)
            sb.append("${offsets.size} 0 obj\n$body\nendobj\n")
        }
        add("<< /Type /Catalog /Pages 2 0 R${pageLayout?.let { " /PageLayout /$it" }.orEmpty()} >>")
        add("<< /Type /Pages /Kids [${(0 until count).joinToString(" ") { "${it + 3} 0 R" }}] /Count $count /MediaBox [0 0 100 200] >>")
        repeat(count) { add("<< /Type /Page /Parent 2 0 R /Resources << >> >>") }
        val xref = sb.length
        sb.append("xref\n0 ${offsets.size + 1}\n0000000000 65535 f \n")
        for (offset in offsets) sb.append("${offset.toString().padStart(10, '0')} 00000 n \n")
        sb.append("trailer\n<< /Size ${offsets.size + 1} /Root 1 0 R >>\nstartxref\n$xref\n%%EOF\n")
        return PdfDocument.open(sb.toString().encodeToByteArray())
    }

    /**
     * Shows [document] in a [width] x [height] spread layout, walks it a page at a time with
     * `nextPage`, and asserts the pages on screen at each spread, left to right.
     */
    private fun assertSpreads(
        expected: List<List<Int>>,
        document: KiteDocument,
        layout: KiteDocLayout.Spread = KiteDocLayout.Spread(),
        width: Int = 400,
        height: Int = 200,
    ) {
        forBothEffectOrders { queued ->
            val shown = ArrayList<List<Int>>()
            lateinit var state: KiteDocViewState
            var step by mutableIntStateOf(0)
            val (scene, driver) = drivenScene(width, height, queued) {
                state = rememberKiteDocViewState(document)
                KiteDocView(state = state, modifier = Modifier.fillMaxSize(), layout = layout)
                LaunchedEffect(step) { if (step > 0) state.nextPage() }
            }
            scene.use {
                driver.pumpUntilState { state.stripSettled && state.pageGeometry.isNotEmpty() }
                var last = -1
                while (true) {
                    driver.pumpUntilState { state.currentPage != last && state.pageGeometry.keys.contains(state.currentPage) }
                    driver.pumpFrames(2)
                    val onScreen = state.pageGeometry.entries.sortedBy { it.value.left }.map { it.key }
                    if (shown.lastOrNull() != onScreen) shown += onScreen
                    last = state.currentPage
                    if (last == state.itemCount - 1) break
                    step++
                }
            }
            assertEquals(expected, shown, "the spreads shown, ${if (queued) "queued" else "default"} effect order")
        }
    }

    @Test
    fun a_centred_cover_shows_alone_and_the_pages_after_it_pair_as_declared() {
        val book = fixedBook(listOf("rendition:page-spread-center", "page-spread-left", "page-spread-right", "page-spread-left", "page-spread-right"))
        assertSpreads(listOf(listOf(0), listOf(1, 2), listOf(3, 4)), book)
    }

    @Test
    fun a_pdf_that_puts_its_first_page_on_the_right_shows_it_alone_left_to_right() {
        assertSpreads(listOf(listOf(0), listOf(1, 2), listOf(3)), pagesPdf(4, "TwoPageRight"))
        assertSpreads(listOf(listOf(0, 1), listOf(2, 3)), pagesPdf(4, "TwoPageLeft"))
        // Right to left, the first page on the right opens the first spread, shown on the right.
        assertSpreads(listOf(listOf(1, 0), listOf(3, 2)), pagesPdf(4, "TwoPageRight"), KiteDocLayout.Spread(reverseLayout = true))
    }

    @Test
    fun a_book_that_asks_for_no_spreads_shows_each_page_alone() {
        assertSpreads(listOf(listOf(0), listOf(1), listOf(2)), fixedBook(listOf(null, null, null), """<meta property="rendition:spread">none</meta>"""))
        // One chapter that asks for none shows alone, and the others pair around it.
        assertSpreads(listOf(listOf(0, 1), listOf(2), listOf(3, 4)), fixedBook(listOf(null, null, "rendition:spread-none", null, null)))
    }

    @Test
    fun a_book_that_pairs_in_landscape_only_shows_single_pages_in_a_portrait_view() {
        val landscapeOnly = """<meta property="rendition:spread">landscape</meta>"""
        assertSpreads(listOf(listOf(0, 1), listOf(2)), fixedBook(listOf(null, null, null), landscapeOnly))
        assertSpreads(listOf(listOf(0), listOf(1), listOf(2)), fixedBook(listOf(null, null, null), landscapeOnly), width = 200, height = 400)
    }

    @Test
    fun the_first_page_alone_pairs_the_rest_and_a_new_pairing_keeps_the_page() = forBothEffectOrders { queued ->
        val document = pagesPdf(5)
        lateinit var state: KiteDocViewState
        var alone by mutableStateOf(false)
        var target by mutableIntStateOf(0)
        val (scene, driver) = drivenScene(400, 200, queued) {
            state = rememberKiteDocViewState(document)
            KiteDocView(state = state, modifier = Modifier.fillMaxSize(), layout = KiteDocLayout.Spread(firstPageAlone = alone))
            LaunchedEffect(target) { if (target > 0) state.scrollToPage(target) }
        }
        scene.use {
            driver.pumpUntilState { state.pageGeometry.isNotEmpty() }
            target = 3
            // The snapshot map's key set does not compare equal to a plain set, so it is copied first.
            driver.pumpUntilState { state.currentPage == 3 && state.pageGeometry.keys.toSet() == setOf(2, 3) }
            alone = true
            driver.pumpUntilState { state.pageGeometry.keys.toSet() == setOf(3, 4) }
            assertEquals(3, state.currentPage, "the new pairing moved the reader")
        }
    }

    @Test
    fun the_page_keys_turn_to_the_next_spread_as_declared() {
        val book = fixedBook(listOf("rendition:page-spread-center", "page-spread-left", "page-spread-right", "page-spread-left", "page-spread-right"))
        val state = KiteDocViewState(book)
        ImageComposeScene(width = 400, height = 200, density = androidx.compose.ui.unit.Density(1f)) {
            KiteDocView(state = state, modifier = Modifier.fillMaxSize(), layout = KiteDocLayout.Spread())
        }.use { scene ->
            val driver = SceneTestDriver(scene)
            driver.pumpUntilState { state.stripSettled && state.pageGeometry.isNotEmpty() }
            scene.sendPointerEvent(PointerEventType.Press, Offset(200f, 100f), type = PointerType.Mouse)
            scene.sendPointerEvent(PointerEventType.Release, Offset(200f, 100f), type = PointerType.Mouse)
            driver.pumpFrames(30)
            for ((key, page) in listOf(Key.PageDown to 1, Key.PageDown to 3, Key.PageUp to 1, Key.PageUp to 0)) {
                scene.sendKeyEvent(KeyEvent(key, KeyEventType.KeyDown))
                scene.sendKeyEvent(KeyEvent(key, KeyEventType.KeyUp))
                driver.pumpUntilState { state.currentPage == page }
            }
        }
    }
}
