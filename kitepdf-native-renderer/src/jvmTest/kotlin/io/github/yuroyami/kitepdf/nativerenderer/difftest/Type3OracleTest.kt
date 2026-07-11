package io.github.yuroyami.kitepdf.nativerenderer.difftest

import io.github.yuroyami.kitepdf.KitePDF
import io.github.yuroyami.kitepdf.nativerenderer.AwtPdfRasterizer
import java.io.File
import org.junit.Assume.assumeTrue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * T-42 acceptance: the hand-written Type3 fixture (square + triangle glyph
 * procs, d1-uncolored) renders within budget of mutool, and text extraction
 * still returns the /Encoding-mapped characters.
 */
class Type3OracleTest {

    private fun fixture() = SyntheticPdfs.all().first { it.name == "syn-type3-font" }

    @Test
    fun type3_render_matches_mutool() {
        assumeTrue("mutool not found — skipping.", MuPdfOracle.binary != null)
        val bytes = fixture().bytes
        val doc = KitePDF.open(bytes)
        val kite = AwtPdfRasterizer.renderToImage(doc.pages[0])
        assertTrue(ImageDiff.nonBackgroundPixels(kite) > 0, "Type3 glyphs painted")

        val pdf = File.createTempFile("kite-type3", ".pdf").apply {
            deleteOnExit()
            writeBytes(bytes)
        }
        val reference = MuPdfOracle.render(pdf, page = 1, dpi = 72)
        assertNotNull(reference)
        val mae = ImageDiff.compare(kite, reference).score
        println("[T-42] type3 vs mutool: MAE=${(mae * 10000).toInt() / 10000.0}")
        assertTrue(mae <= 0.03, "Type3 MAE $mae must be <= 0.03")
    }

    @Test
    fun type3_text_extracts_through_the_encoding() {
        val doc = KitePDF.open(fixture().bytes)
        val text = doc.pages[0].extractText()
        assertTrue("abab" in text, "Differences-mapped chars extract: [$text]")
        assertEquals("ba", text.trim().lines().last().trim(), "second run extracts too")
    }
}
