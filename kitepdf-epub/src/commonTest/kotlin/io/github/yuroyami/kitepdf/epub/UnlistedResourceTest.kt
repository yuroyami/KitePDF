package io.github.yuroyami.kitepdf.epub

import io.github.yuroyami.kitepdf.core.KiteWarningSink
import io.github.yuroyami.kitepdf.core.KiteWarnings
import io.github.yuroyami.kitepdf.core.render.RecordingCanvas
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A file in the zip that the manifest does not list is not a resource of the book, so nothing
 * of the book loads it (EPUB Reading Systems 3.3, 3.1; W3C test pkg-manifest-unlisted-resource, #516).
 */
class UnlistedResourceTest {

    private fun images(doc: EpubDocument) =
        doc.pages.flatMap { page -> RecordingCanvas().also { page.renderTo(it) }.calls }.filterIsInstance<RecordingCanvas.Call.Image>()

    @Test
    fun a_listed_image_draws_and_an_unlisted_one_does_not() {
        val bmp = EpubFixtures.bmp2x1()
        val listed = EpubDocument.open(EpubFixtures.epub("""<img src="red.bmp" alt="red"/>""", extraEntries = listOf("OEBPS/red.bmp" to bmp)))
        assertEquals(1, images(listed).size)
        val unlisted = EpubDocument.open(EpubFixtures.epub("""<img src="red.bmp" alt="red"/>""", unlisted = listOf("OEBPS/red.bmp" to bmp)))
        assertEquals(0, images(unlisted).size)
        assertNull(unlisted.resource("OEBPS/red.bmp"))
        assertContentEquals(bmp, listed.resource("OEBPS/red.bmp"))
    }

    @Test
    fun an_unlisted_style_sheet_does_not_apply() {
        val css = "p { font-size: 40px }".encodeToByteArray()
        fun sizes(doc: EpubDocument) = doc.pages.flatMap { page -> RecordingCanvas().also { page.renderTo(it) }.calls }
            .filterIsInstance<RecordingCanvas.Call.Glyphs>().map { it.fontSize * it.textToDevice.d }.distinct()
        val body = """<body><link rel="stylesheet" href="big.css"/><p>Text</p></body>"""
        val listed = sizes(EpubDocument.open(EpubFixtures.epub(body, extraEntries = listOf("OEBPS/big.css" to css))))
        val unlisted = sizes(EpubDocument.open(EpubFixtures.epub(body, unlisted = listOf("OEBPS/big.css" to css))))
        assertTrue(listed.single() > unlisted.single() * 2, "listed $listed, unlisted $unlisted")
    }

    @Test
    fun a_refusal_is_reported_and_a_missing_file_stays_a_plain_miss() {
        val warnings = ArrayList<String>()
        val previous = KiteWarnings.sink
        KiteWarnings.sink = KiteWarningSink { warnings += it }
        try {
            val doc = EpubDocument.open(EpubFixtures.epub("""<img src="red.bmp" alt="red"/><img src="gone.bmp" alt="gone"/>""", unlisted = listOf("OEBPS/red.bmp" to EpubFixtures.bmp2x1())))
            images(doc)
            assertNull(doc.resource("OEBPS/red.bmp"))
            assertTrue(warnings.any { "'OEBPS/red.bmp' is not in the manifest" in it }, "$warnings")
            assertTrue(warnings.none { "gone.bmp" in it && "manifest" in it }, "$warnings")
        } finally {
            KiteWarnings.sink = previous
        }
    }
}
