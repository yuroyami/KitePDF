package io.github.yuroyami.kitepdf.javascript

import org.junit.Assume.assumeTrue
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The `URL` and `URLSearchParams` that a book's scripts see (#520), against the JavaScript tests
 * of the URL Standard in web-platform-tests, run in a chapter as a browser runs them in a page.
 *
 * Each test file runs in a book of its own, after `harness.js`, a small stand-in for
 * testharness.js whose `fetch` answers from the test data that the parser's own test reads in
 * kitepdf-epub, and before `report.js`, which logs the count.
 *
 * A test that a known gap fails is listed in [gaps] with the issue that tracks it, and a probe
 * that is true while the gap is there. While it is, the test has to fail; once it is not, the
 * test has to pass, so the list stays true.
 */
class UrlWptTest {

    private val runners = ArrayList<EpubScriptRunner>()

    @AfterTest
    fun closeRunners() {
        runners.forEach { it.close() }
    }

    /** A known gap: [probe] is a script expression that is true while it is there, and [tests] are the names it fails. */
    private class Gap(val issue: String, val probe: String, val tests: Set<String>)

    private val gaps = listOf(
        Gap(
            "kitejs#67, a generator function has a null prototype",
            "Object.getPrototypeOf(function* () {}) === null",
            setOf("Custom [Symbol.iterator]"),
        ),
        Gap(
            "kitejs#68, an accessor with set: undefined ignores a strict write",
            "(function () { 'use strict'; var o = Object.defineProperty({}, 'z', { get: function () { return 1; }, set: undefined }); " +
                "try { o.z = 2; return true; } catch (e) { return false; } })()",
            setOf("URL.searchParams setter, invalid values"),
        ),
        Gap("#530, DOMException has no legacy constants", "DOMException.INDEX_SIZE_ERR !== 1", setOf("URLSearchParams constructor, DOMException as argument")),
        Gap("#531, FormData is missing", "typeof FormData === 'undefined'", setOf("URLSearchParams constructor, FormData.")),
    )

    /** The test files that KiteJS 0.2.0 cannot parse, as each has a const in a for head (kitejs#11, fixed after it). */
    private val dataDriven = listOf("url-constructor.any.js", "url-origin.any.js", "url-setters.any.js", "urlsearchparams-foreach.any.js")

    private val files = listOf(
        "url-searchparams.any.js", "url-statics-canparse.any.js", "url-statics-parse.any.js", "url-tojson.any.js",
        "urlsearchparams-append.any.js", "urlsearchparams-constructor.any.js", "urlsearchparams-delete.any.js",
        "urlsearchparams-get.any.js", "urlsearchparams-getall.any.js", "urlsearchparams-has.any.js",
        "urlsearchparams-set.any.js", "urlsearchparams-size.any.js", "urlsearchparams-sort.any.js",
        "urlsearchparams-stringifier.any.js",
    )

    private fun resource(name: String): String =
        checkNotNull(javaClass.getResourceAsStream("/wpt-url/$name")) { "no resource $name" }.readBytes().decodeToString()

    /** The test data of the URL parser, in kitepdf-epub, from the repository root, so any working directory works. */
    private val data: File = run {
        var dir: File? = File(System.getProperty("user.dir")).absoluteFile
        while (dir != null && !File(dir, "settings.gradle.kts").exists()) dir = dir.parentFile
        File(checkNotNull(dir) { "no repository root above ${System.getProperty("user.dir")}" }, "kitepdf-epub/src/jvmTest/resources/wpt-url")
    }

    /** What the harness's `fetch` answers: each JSON file the tests load, as a JavaScript literal. */
    private val harnessData: String by lazy {
        listOf("urltestdata.json", "urltestdata-javascript-only.json", "setters_tests.json").joinToString(",\n", "var harnessData = {\n", "\n};") {
            "\"resources/$it\": " + File(data, it).readText()
        }
    }

    /** The console of a chapter whose scripts are [files], in order, and the failures of its scripts. */
    private fun chapter(files: Map<String, String>): Pair<List<String>, List<String>> {
        val console = ArrayList<String>()
        val book = ScriptBooks.chapter(files.keys.joinToString("") { "<script src=\"$it\"></script>" }, extraFiles = files)
        val runner = EpubScriptRunner(book, onConsole = { level, message -> console += "$level: $message" }).also { runners += it }
        runner.chapterOpened(0)
        return console to runner.failures.map { it.message.orEmpty() }
    }

    /** Which of [gaps] the engine and the prelude still have. */
    private val openGaps: List<Gap> by lazy {
        val probe = gaps.joinToString(", ", "console.log('PROBE ' + JSON.stringify([", "]));") { "!!(${it.probe})" }
        val (console, failures) = chapter(mapOf("probe.js" to probe))
        assertTrue(failures.isEmpty(), "the probes fail: $failures")
        val answers = console.single { it.startsWith("log: PROBE ") }.removePrefix("log: PROBE [").removeSuffix("]").split(',')
        assertEquals(gaps.size, answers.size)
        gaps.filterIndexed { i, _ -> answers[i] == "true" }
    }

    /** The names of the tests of [name] that fail, and how many pass. A script that does not run at all fails the file. */
    private fun run(name: String): Pair<List<String>, Int> {
        val (console, failures) = chapter(
            linkedMapOf("harness.js" to resource("harness.js"), "data.js" to harnessData, name to resource(name), "report.js" to resource("report.js")),
        )
        assertTrue(failures.isEmpty(), "$name does not run: $failures")
        val done = console.singleOrNull { it.startsWith("log: DONE ") }
        assertTrue(done != null, "$name did not finish:\n" + console.joinToString("\n"))
        val (passed, failed) = done.removePrefix("log: DONE ").split(' ').map { it.toInt() }
        val failing = console.filter { it.startsWith("log: FAIL ") }.map { it.removePrefix("log: FAIL ") }
        assertEquals(failed, failing.size)
        return failing to passed
    }

    /** Runs [names], and fails on a test that fails without an open gap, or that passes while its gap is open. */
    private fun check(names: List<String>, atLeast: Int) {
        val expected = openGaps.flatMap { gap -> gap.tests.map { it to gap.issue } }.toMap()
        val unexpected = ArrayList<String>()
        val seen = HashSet<String>()
        var passed = 0
        for (name in names) {
            val (failing, ok) = run(name)
            passed += ok
            for (failure in failing) {
                val test = failure.substringBefore(" :: ")
                if (test in expected) seen += test else unexpected += "$name: $failure"
            }
        }
        val stale = expected.filterKeys { test -> test !in seen && names.any { test in resource(it) } }
        assertTrue(passed >= atLeast, "only $passed tests passed")
        assertTrue(unexpected.isEmpty(), "${unexpected.size} failures, $passed passed:\n" + unexpected.take(60).joinToString("\n"))
        assertTrue(stale.isEmpty(), "these pass although their gap is open, so its probe is wrong: $stale")
    }

    @Test
    fun the_url_tests_of_web_platform_tests_pass_but_for_known_gaps() = check(files, atLeast = 90)

    @Test
    fun the_data_driven_url_tests_pass() {
        val probe = "console.log('PROBE ' + (function () { try { Function('for (const x of []) {}'); return 'yes'; } catch (e) { return 'no'; } })());"
        val (console, _) = chapter(mapOf("probe.js" to probe))
        assumeTrue("The KiteJS in use cannot parse a const in a for head (kitejs#11, fixed after 0.2.0).", "log: PROBE yes" in console)
        check(dataDriven, atLeast = 1500)
    }
}
