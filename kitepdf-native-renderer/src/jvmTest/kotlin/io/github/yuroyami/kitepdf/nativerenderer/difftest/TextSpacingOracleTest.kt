package io.github.yuroyami.kitepdf.nativerenderer.difftest

import io.github.yuroyami.kitepdf.PdfDocument
import io.github.yuroyami.kitepdf.difftest.MuPdfOracle
import io.github.yuroyami.kitepdf.difftest.TextSpacingFixture
import io.github.yuroyami.kitepdf.nativerenderer.AwtPdfRasterizer
import org.junit.Assume.assumeTrue
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * ISO 32000-1, 9.4.4 on AWT: each line of the [TextSpacingFixture] page ends where
 * mutool ends it. Before `TextGlyph.advanceAdjust` existed, spacing reached only the
 * advance between runs, so glyphs bunched at the start of each run. Skips without mutool.
 */
class TextSpacingOracleTest {

    @Test
    fun char_and_word_spacing_reach_as_far_as_mutool() {
        assumeTrue("mutool not found, skipping.", MuPdfOracle.binary != null)
        val failures = TextSpacingFixture.check("awt") { AwtPdfRasterizer.renderToImage(PdfDocument.open(it).pages[0]) }
        assertTrue(failures.isEmpty(), failures.joinToString("\n"))
    }
}
