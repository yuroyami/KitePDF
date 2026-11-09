package io.github.yuroyami.kitepdf.nativerenderer.difftest

import org.junit.Assume.assumeTrue

/**
 * Skips the test when [present] is false, with [message], or fails it when KITEPDF_REQUIRE_ORACLES=1:
 * CI provides the oracle fonts and tools, so a run that lost them does not go green with the
 * oracle tests skipped (#688).
 */
internal fun assumeOracle(message: String, present: Boolean) {
    if (!present && System.getenv("KITEPDF_REQUIRE_ORACLES") == "1") {
        throw AssertionError("$message KITEPDF_REQUIRE_ORACLES=1 requires it.")
    }
    assumeTrue(message, present)
}
