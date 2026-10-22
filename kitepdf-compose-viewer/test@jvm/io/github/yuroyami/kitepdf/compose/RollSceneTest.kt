package io.github.yuroyami.kitepdf.compose

import androidx.compose.foundation.layout.fillMaxSize
import io.github.yuroyami.kitepdf.compose.EdtImageComposeScene as ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Density
import io.github.yuroyami.kitepdf.epub.EpubDocument
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * A roll reads as one strip whose pages fill the width and touch, with no gap between them
 * (EPUB Reading Systems 3.4, roll layouts, #506).
 */
class RollSceneTest {

    /** A short plate, 400 by 100 CSS pixels, so several fit in the view at once. */
    private val plate = """<?xml version="1.0"?><html xmlns="http://www.w3.org/1999/xhtml">
        <head><meta name="viewport" content="width=400, height=100"/></head>
        <body style="margin:0"><div style="height:100px;background-color:#ff0000"></div></body></html>"""

    private fun book(layout: String): EpubDocument {
        val container = """<?xml version="1.0"?><container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">""" +
            """<rootfiles><rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/></rootfiles></container>"""
        val opf = """<?xml version="1.0"?><package xmlns="http://www.idpf.org/2007/opf" version="3.0" unique-identifier="uid">""" +
            """<metadata xmlns:dc="http://purl.org/dc/elements/1.1/"><dc:identifier id="uid">x</dc:identifier><meta property="rendition:layout">$layout</meta></metadata>""" +
            """<manifest>${(0..2).joinToString("") { """<item id="c$it" href="c$it.xhtml" media-type="application/xhtml+xml"/>""" }}</manifest>""" +
            """<spine>${(0..2).joinToString("") { """<itemref idref="c$it"/>""" }}</spine></package>"""
        return EpubDocument.open(
            storedZipOf(
                listOf(
                    "mimetype" to "application/epub+zip".encodeToByteArray(),
                    "META-INF/container.xml" to container.encodeToByteArray(),
                    "OEBPS/content.opf" to opf.encodeToByteArray(),
                ) + (0..2).map { "OEBPS/c$it.xhtml" to plate.encodeToByteArray() },
            ),
        )
    }

    /** The top of each page in the strip, and its width, once three pages are placed. */
    private fun strip(doc: EpubDocument, layout: KiteDocLayout = KiteDocLayout.forDocument(doc)): List<Pair<Float, Float>> {
        val state = KiteDocViewState(doc)
        ImageComposeScene(width = 200, height = 300, density = Density(1f)) {
            KiteDocView(state = state, modifier = Modifier.fillMaxSize(), layout = layout)
        }.use { scene ->
            val driver = SceneTestDriver(scene)
            driver.pumpUntilState { state.pageGeometry.size >= 3 }
            driver.pumpFrames(3)
            return onTestUiThread { (0..2).map { state.pageGeometry.getValue(it).let { r -> r.top to r.width } } }
        }
    }

    @Test
    fun the_pages_of_a_roll_fill_the_width_and_touch() {
        val doc = book("roll")
        assertEquals(KiteDocLayout.Continuous(), KiteDocLayout.forDocument(doc))
        val pages = strip(doc)
        for ((_, width) in pages) assertEquals(200f, width)
        // Each plate is 50 px tall at 200 px wide, and the next one starts where it ends.
        assertEquals(listOf(0f, 50f, 100f), pages.map { it.first })
    }

    @Test
    fun the_pages_of_another_fixed_book_keep_their_gap() {
        val doc = book("pre-paginated")
        assertEquals(KiteDocLayout.Paged(), KiteDocLayout.forDocument(doc))
        assertEquals(listOf(0f, 58f, 116f), strip(doc, KiteDocLayout.Continuous()).map { it.first })
    }
}
