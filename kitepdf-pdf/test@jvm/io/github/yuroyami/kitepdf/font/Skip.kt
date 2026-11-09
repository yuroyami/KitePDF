package io.github.yuroyami.kitepdf.font

import org.junit.Assume.assumeTrue

/** This value, or a skipped test when it is missing: an absent fixture reads SKIPPED, not PASSED (#44). */
internal fun <T : Any> T?.orSkip(what: String): T {
    assumeTrue("$what is not available here, skipping.", this != null)
    return this!!
}

/**
 * This oracle font or tool, or a skipped test when it is missing. CI provides them and sets
 * KITEPDF_REQUIRE_ORACLES=1, which makes a missing one fail the test instead, so a run that lost
 * its fonts or tools does not go green with the oracle tests skipped (#688).
 */
internal fun <T : Any> T?.orOracle(what: String): T {
    if (this == null && System.getenv("KITEPDF_REQUIRE_ORACLES") == "1") {
        throw AssertionError("$what is missing, and KITEPDF_REQUIRE_ORACLES=1 requires it")
    }
    return orSkip(what)
}
