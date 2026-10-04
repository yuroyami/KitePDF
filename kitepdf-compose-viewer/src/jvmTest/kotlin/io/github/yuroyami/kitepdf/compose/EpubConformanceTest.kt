package io.github.yuroyami.kitepdf.compose

import org.junit.Assume.assumeTrue
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * KitePDF's implementation report against the W3C EPUB 3 test suite (#497).
 *
 * The suite, w3c/epub-tests at [SUITE_COMMIT], has a test book for each normative statement of
 * EPUB 3 and EPUB Reading Systems 3, each marked must, should, may or deprecated. The report,
 * `docs/epub-conformance.json`, gives KitePDF's result for each in the suite's own format:
 * `true`, `false` or `"n/a"`. `docs/epub-conformance.md` shows the same results by section,
 * with how each is known and the issue that tracks each failure.
 *
 * Where KitePDF's API can decide a result, [EpubConformanceChecks] decides it, and this test
 * fails when that decision and the report part: a fix that makes a test pass moves the report in
 * the same commit, and so does a change that makes one fail. The other results were judged by
 * looking at the pages, and the page says what was seen.
 *
 * The suite is not part of this repository. CI checks it out under `.epub-tests`; elsewhere set
 * `KITEPDF_EPUB_TESTS` to the `tests` folder of a checkout at [SUITE_COMMIT], or the checks skip.
 */
class EpubConformanceTest {

    private val repo: File = run {
        var dir: File? = File(System.getProperty("user.dir")).absoluteFile
        while (dir != null && !File(dir, "settings.gradle.kts").exists()) dir = dir.parentFile
        checkNotNull(dir) { "no repository root above ${System.getProperty("user.dir")}" }
    }

    /** Each test's result in the report: `true`, `false` or `"n/a"`. */
    private val report: Map<String, Any> by lazy { parseReport(File(repo, "docs/epub-conformance.json").readText()) }

    /** Each test's row on the docs page. */
    private val rows: Map<String, Row> by lazy { parseRows(File(repo, "docs/epub-conformance.md").readText()) }

    /** The suite's tests, by folder name, with the level each is marked; skips when the suite is not here. */
    private fun suite(): Map<String, Pair<File, String>> {
        val dir = File(System.getProperty("kitepdf.epubTests").orEmpty())
        if (!dir.isDirectory) {
            if (!System.getenv("CI").isNullOrEmpty()) fail("CI checks out w3c/epub-tests at $SUITE_COMMIT under .epub-tests, and $dir is not there")
            assumeTrue("the W3C EPUB test suite is not checked out at $dir; see EpubConformanceTest", false)
        }
        return dir.listFiles().orEmpty()
            .filter { it.isDirectory && !it.name.startsWith("xx-") && File(it, "mimetype").isFile }
            .associate { it.name to (it to levelOf(it)) }
    }

    @Test
    fun the_report_answers_every_test_of_the_suite_and_no_other() {
        val tests = suite()
        assertEquals(tests.keys.sorted(), report.keys.sorted(), "the report's tests are the suite's")
        val levels = tests.mapValues { it.value.second }
        val wrong = rows.filter { (id, row) -> levels[id] != null && levels[id] != row.level }.map { (id, row) -> "$id: ${row.level}, the suite says ${levels[id]}" }
        assertEquals(emptyList(), wrong, "the docs page gives each test the suite's level")
    }

    @Test
    fun the_docs_page_shows_the_report_and_names_an_issue_for_each_failure() {
        assertEquals(report.keys.sorted(), rows.keys.sorted(), "the docs page has a row for each test of the report")
        val disagree = rows.filter { (id, row) -> row.result != report[id] }.map { (id, row) -> "$id: page ${row.result}, report ${report[id]}" }
        assertEquals(emptyList(), disagree, "the docs page and the report agree")
        val untracked = rows.filter { (_, row) -> row.result == false && row.level in setOf("must", "should") && row.issue == null }.keys
        assertEquals(emptyList(), untracked.sorted(), "each failure of a must or should names its issue")
        val unchecked = EpubConformanceChecks.ids.filter { it !in rows }
        assertEquals(emptyList(), unchecked, "each automated check is for a test of the report")
        val wrongHow = rows.filter { (id, row) -> row.checked != (id in EpubConformanceChecks.ids) }.keys
        assertEquals(emptyList(), wrongHow.sorted(), "a row says Checked exactly when the harness checks it")
    }

    @Test
    fun the_automated_checks_agree_with_the_report() {
        val tests = suite()
        val disagree = ArrayList<String>()
        for (id in EpubConformanceChecks.ids.sorted()) {
            val folder = tests[id]?.first ?: fail("$id has a check and is not in the suite")
            val book = W3cTestBook(folder)
            val found: Any = try {
                EpubConformanceChecks.check(id, book)
            } catch (e: Throwable) {
                "threw ${e::class.simpleName}: ${e.message}"
            } finally {
                book.close()
            }
            if (found != report[id]) disagree += "$id: the check finds $found, the report says ${report[id]}"
        }
        assertEquals(emptyList(), disagree, "move the report with the change that moved a result")
    }

    /** A row of the docs page: `| [`id`](link) | level | result | how |`. */
    private class Row(val level: String, val result: Any, val issue: Int?, val checked: Boolean)

    private companion object {
        const val SUITE_COMMIT = "54092b4233253e9aac80e93ec4782b380b4b3403"

        fun parseReport(json: String): Map<String, Any> {
            val tests = json.substringAfter("\"tests\"").substringAfter('{').substringBefore('}')
            return Regex("\"([^\"]+)\"\\s*:\\s*(true|false|\"n/a\")").findAll(tests)
                .associate { it.groupValues[1] to resultOf(it.groupValues[2].trim('"')) }
        }

        fun parseRows(markdown: String): Map<String, Row> {
            val row = Regex("^\\| \\[`([^`]+)`]\\([^)]*\\) \\| (\\w+) \\| ([^|]+) \\| ([^|]+) \\|$")
            return markdown.lines().mapNotNull { row.find(it.trim()) }.associate { m ->
                val result = m.groupValues[3].trim()
                m.groupValues[1] to Row(
                    level = m.groupValues[2],
                    result = when {
                        result.startsWith("Passes") -> true
                        result.startsWith("Fails") -> false
                        result.startsWith("Not applicable") -> "n/a"
                        else -> error("${m.groupValues[1]}: not a result: $result")
                    },
                    issue = Regex("\\[(?:KiteJS)?#(\\d+)]").find(result)?.groupValues?.get(1)?.toInt(),
                    checked = m.groupValues[4].trim().startsWith("Checked"),
                )
            }
        }

        fun resultOf(value: String): Any = when (value) {
            "true" -> true
            "false" -> false
            else -> value
        }

        /** The level a test's package marks it with: an empty `belongs-to-collection` is must. */
        fun levelOf(folder: File): String {
            val opf = folder.walkTopDown().first { it.extension == "opf" }.readText()
            val level = Regex("<meta property=\"belongs-to-collection\"[^>]*>([^<]*)</meta>").find(opf)?.groupValues?.get(1)?.trim()
            return if (level.isNullOrEmpty()) "must" else level
        }
    }
}
