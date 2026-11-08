package io.github.yuroyami.kitepdf.epub

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.TimeSource

/** The `url()` and `@import` rewrites match what their patterns matched, in linear time (#989). */
class CssScanTest {

    private val urlPattern = Regex("""url\(\s*(["']?)([^"')]*)\1\s*\)""", RegexOption.IGNORE_CASE)
    private val importPattern = Regex("""@import\s+(?:url\(\s*)?["']?([^"')\s;]+)["']?\s*\)?[^;{]*;""", RegexOption.IGNORE_CASE)

    private val samples = listOf(
        "a { background: url(img/a.png) }",
        "a { background: URL( 'b c.png' ) } b { x: url(\"d.png\")}",
        "url( url(\"x\") url(y url('z) url() url(  )",
        "@import url(\"a.css\"); @import 'b.css' screen; @IMPORT c.css; @import url( d.css ) print;",
        "@import a.css { } @import ; @import\turl(e.css);@importf.css; @import 'g.css'",
        "p{}@import url(h.css) x y z;",
    )

    @Test
    fun theScansMatchWhatThePatternsMatched() {
        for (css in samples) {
            val viaRegex = urlPattern.replace(css) { m -> "<${m.groupValues[2]}|${m.value}>" }
            assertEquals(viaRegex, CssScan.replaceUrls(css) { whole, content -> "<$content|$whole>" }, css)
            val importsViaRegex = importPattern.replace(css) { m -> "[${m.groupValues[1]}]" }
            assertEquals(importsViaRegex, CssScan.replaceImports(css) { "[$it]" }, css)
        }
    }

    @Test
    fun aSheetThatAlmostMatchesTakesLinearTime() {
        val start = TimeSource.Monotonic.markNow()
        CssScan.replaceUrls("url(".repeat(200_000)) { w, _ -> w }
        CssScan.replaceImports("@import " + "a".repeat(200_000)) { it }
        CssScan.replaceImports("@import a ".repeat(50_000)) { it }
        assertTrue(start.elapsedNow().inWholeSeconds < 5, "took ${start.elapsedNow()}")
    }
}
