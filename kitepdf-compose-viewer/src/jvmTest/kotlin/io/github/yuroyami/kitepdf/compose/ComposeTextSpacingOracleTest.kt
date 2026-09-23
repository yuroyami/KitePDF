package io.github.yuroyami.kitepdf.compose

import io.github.yuroyami.kitepdf.difftest.MuPdfOracle
import io.github.yuroyami.kitepdf.difftest.TextSpacingFixture
import org.junit.Assume.assumeTrue
import kotlin.test.Test
import kotlin.test.assertTrue

/** ISO 32000-1, 9.4.4 on Compose: each line of the [TextSpacingFixture] page ends where mutool ends it (#121). */
class ComposeTextSpacingOracleTest {

    @Test
    fun char_and_word_spacing_and_widths_reach_as_far_as_mutool() {
        assumeTrue("mutool not found, skipping.", MuPdfOracle.binary != null)
        val failures = TextSpacingFixture.check("compose", ::renderWithCompose)
        assertTrue(failures.isEmpty(), failures.joinToString("\n"))
    }
}
