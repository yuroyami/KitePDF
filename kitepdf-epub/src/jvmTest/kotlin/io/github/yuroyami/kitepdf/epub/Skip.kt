package io.github.yuroyami.kitepdf.epub

import org.junit.Assume.assumeTrue

/** This value, or a skipped test when it is missing: an absent fixture reads SKIPPED, not PASSED (#44). */
internal fun <T : Any> T?.orSkip(what: String): T {
    assumeTrue("$what is not available here, skipping.", this != null)
    return this!!
}
