package io.github.yuroyami.kitepdf.nativerenderer.difftest

import io.github.yuroyami.kitepdf.PdfDocument
import io.github.yuroyami.kitepdf.difftest.GroupFixtures
import io.github.yuroyami.kitepdf.difftest.MuPdfOracle
import io.github.yuroyami.kitepdf.difftest.scoreAgainstMutool
import io.github.yuroyami.kitepdf.nativerenderer.AwtPdfRasterizer
import org.junit.Assume.assumeTrue
import kotlin.test.Test
import kotlin.test.assertTrue

/** Scores the AWT canvas on every [GroupFixtures] page against mutool. Skips without mutool. */
class GroupOracleTest {

    @Test
    fun every_group_fixture_is_within_budget() {
        assumeTrue("mutool not found, skipping.", MuPdfOracle.binary != null)
        val failures = scoreAgainstMutool("awt", GroupFixtures.all()) { AwtPdfRasterizer.renderToImage(PdfDocument.open(it).pages[0]) }
        assertTrue(failures.isEmpty(), failures.joinToString("\n"))
    }
}
