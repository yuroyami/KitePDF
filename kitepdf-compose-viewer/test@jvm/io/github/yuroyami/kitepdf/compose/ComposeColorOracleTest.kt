package io.github.yuroyami.kitepdf.compose

import io.github.yuroyami.kitepdf.difftest.ColorFixtures
import io.github.yuroyami.kitepdf.difftest.MuPdfOracle
import io.github.yuroyami.kitepdf.difftest.scoreAgainstMutool
import org.junit.Assume.assumeTrue
import kotlin.test.Test
import kotlin.test.assertTrue

/** Scores the Compose canvas on every [ColorFixtures] page against mutool. Skips without mutool. */
class ComposeColorOracleTest {

    @Test
    fun every_colour_fixture_is_within_budget() {
        assumeTrue("mutool not found, skipping.", MuPdfOracle.binary != null)
        val failures = scoreAgainstMutool("compose", ColorFixtures.all(), ::renderWithCompose)
        assertTrue(failures.isEmpty(), failures.joinToString("\n"))
    }
}
