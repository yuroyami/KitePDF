package io.github.yuroyami.kitepdf.compose

import io.github.yuroyami.kitepdf.difftest.GroupFixtures
import io.github.yuroyami.kitepdf.difftest.MuPdfOracle
import io.github.yuroyami.kitepdf.difftest.scoreAgainstMutool
import org.junit.Assume.assumeTrue
import kotlin.test.Test
import kotlin.test.assertTrue

/** Scores the Compose canvas on every [GroupFixtures] page against mutool. Skips without mutool. */
class ComposeGroupOracleTest {

    @Test
    fun every_group_fixture_is_within_budget() {
        assumeTrue("mutool not found, skipping.", MuPdfOracle.binary != null)
        val failures = scoreAgainstMutool("compose", GroupFixtures.all(), ::renderWithCompose)
        assertTrue(failures.isEmpty(), failures.joinToString("\n"))
    }
}
