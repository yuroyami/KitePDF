package io.github.yuroyami.kitepdf.javascript

import io.github.yuroyami.kitepdf.epub.EpubDocument
import org.junit.Assume.assumeTrue
import java.io.File
import kotlin.time.TimeSource
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Interfaces that a book's scripts see, against their JavaScript tests in web-platform-tests, run
 * in a chapter as a browser runs them in a page: the `URL` and `URLSearchParams` of the URL
 * Standard (#520), the `DOMException` of Web IDL (#530), the `TextEncoder` and `TextDecoder` of
 * the Encoding Standard and the `atob` and `btoa` of HTML (#532), the `Blob`, `File`,
 * `FileReader` and blob URLs of the File API (#533), and the selector queries of the DOM
 * Standard (#549).
 *
 * Each test file runs in a book of its own, after `harness.js`, a small stand-in for
 * testharness.js whose `fetch` answers from the test data that the URL parser's own test reads
 * in kitepdf-epub, and after the scripts its META lines name. The chapter's timers run on a clock
 * that skips the waits, until the harness logs the count once the page has loaded and every test
 * has completed. A file named with a query runs as that variant of it, the query in
 * `location.search`. A page whose markup the tests query runs as the chapter itself, and one
 * whose tests query the document of a frame it makes runs as that document.
 *
 * A test that a known gap fails is listed in [gaps] with the issue that tracks it, and a probe
 * that is true while the gap is there, as is a file that a gap keeps from running at all. While
 * the gap is there, the test has to fail; once it is not, the test has to pass, so the list stays
 * true.
 */
class WebPlatformTest {

    private val runners = ArrayList<EpubScriptRunner>()

    @AfterTest
    fun closeRunners() {
        runners.forEach { it.close() }
    }

    /**
     * A known gap: [probe] is a script expression that is true while it is there, and [tests] are
     * the names of the tests it fails and the paths of the files it keeps from running, as are the
     * names that [names] finds a match in.
     */
    private class Gap(val issue: String, val probe: String, val tests: Set<String> = emptySet(), val names: Regex? = null) {
        fun covers(test: String): Boolean = test in tests || names?.containsMatchIn(test) == true
    }

    private val gaps = listOf(
        Gap(
            "kitejs#68, an accessor with set: undefined ignores a strict write",
            "(function () { 'use strict'; var o = Object.defineProperty({}, 'z', { get: function () { return 1; }, set: undefined }); " +
                "try { o.z = 2; return true; } catch (e) { return false; } })()",
            setOf("URL.searchParams setter, invalid values"),
        ),
        // The test is strict and the harness that calls it is not, as there. The second function is
        // the sloppy caller: one written inside the strict function would be strict as well.
        Gap(
            "kitejs#69, strict code called by sloppy code runs as sloppy",
            "(function (call) { 'use strict'; var o = Object.freeze({ z: 1 }); return call(function () { o.z = 2; }); })" +
                "(function (fn) { try { fn(); return true; } catch (e) { return false; } })",
            setOf("URL.searchParams setter, invalid values"),
        ),
        Gap("#531, FormData is missing", "typeof FormData === 'undefined'", setOf("URLSearchParams constructor, FormData.")),
        // Each test of the encoding folder that takes a buffer runs once over each kind of buffer.
        Gap("kitejs#72, SharedArrayBuffer is missing", "typeof SharedArrayBuffer === 'undefined'", names = Regex("SharedArrayBuffer")),
        Gap(
            "kitejs#73, Float16Array is missing",
            "typeof Float16Array === 'undefined'",
            setOf(
                "Invalid encodeInto() destination: Float16Array, backed by: ArrayBuffer",
                "Passing a Float16Array as element of the blobParts array should work.",
            ),
        ),
        // The tests detach a buffer by transferring it through a port.
        Gap(
            "#534, MessageChannel is missing",
            "typeof MessageChannel === 'undefined'",
            setOf(
                "encodeInto() and a detached output buffer",
                "Blob from a detached ArrayBufferView should be empty",
                "Blob from a detached ArrayBufferView with offset should be empty",
                "Blob from a detached ArrayBuffer should be empty",
                "Blob from a detached ArrayBufferView mixed with string, detached part ignored",
                "Passing a FrozenArray as the blobParts array should work (FrozenArray<MessagePort>).",
            ),
        ),
        Gap(
            "kitejs#12, async functions are missing",
            "(function () { try { Function('return async function () {}'); return false; } catch (e) { return true; } })()",
            setOf(
                "FileAPI/blob/Blob-array-buffer.any.js", "FileAPI/blob/Blob-bytes.any.js", "FileAPI/blob/Blob-stream.any.js",
                "FileAPI/blob/Blob-text.any.js", "FileAPI/blob/Blob-textStream.any.js", "FileAPI/unicode.any.js",
                "FileAPI/reading-data-section/FileReader-multiple-reads.any.js", "FileAPI/reading-data-section/filereader_events.any.js",
                "FileAPI/reading-data-section/filereader_result.any.js",
            ),
        ),
        // By test name only: the files that test streams are async functions too, and kitejs#12 keeps them from running.
        Gap(
            "#536, ReadableStream is missing",
            "typeof ReadableStream === 'undefined'",
            names = Regex("^Blob\\.(stream|textStream)\\(\\)|^Reading Blob\\.stream|^textStream method existence"),
        ),
        // Each element is made twice, by createElement and by parsing a document; the second needs DOMParser.
        Gap("#543, DOMParser is missing", "typeof DOMParser === 'undefined'", names = Regex(": useParser$")),
        // The frame loads its document at #target, and a chapter opens at no fragment. A fragment or a
        // detached element has no target in a browser either, so those tests pass.
        Gap(
            "#550, a chapter has no fragment and no target",
            "typeof HashChangeEvent === 'undefined'",
            names = Regex("^(Document|In-document Element)\\.[A-Za-z]+: :target pseudo-class"),
        ),
        // An element's attributes are a proxy: the tests of its own properties ask hasOwnProperty of it, and a
        // for-in over it reaches a key that its ownKeys trap built at run time, which crashes the engine.
        Gap(
            "KiteJS 0.2.0 asks a proxy's has trap for hasOwnProperty and crashes on a key its ownKeys trap built (D-91, fixed after it)",
            "Object.prototype.hasOwnProperty.call(new Proxy({}, { has: function () { return true; }, " +
                "getOwnPropertyDescriptor: function () { return undefined; } }), 'a')",
            setOf(
                "dom/nodes/attributes.html",
                "Own property correctness with basic attributes",
                "Own property correctness with non-namespaced attribute before same-name namespaced one",
                "Own property correctness with namespaced attribute before same-name non-namespaced one",
                "Own property correctness with two namespaced attributes with the same name-with-prefix",
            ),
        ),
        // Setting style sets cssText, whose setter sits on the prototype of the style's proxy target.
        Gap(
            "KiteJS 0.2.0 makes Reflect.set write an own property past an inherited setter (D-89, fixed after it)",
            "(function () { var o = Object.create({ set x(v) { this.y = v; } }); Reflect.set(o, 'x', 1); return o.y !== 1; })()",
            setOf("Toggling element with inline style should make inline style disappear"),
        ),
        Gap(
            "kitejs#11, a const in a for head",
            "(function () { try { Function('for (const x of []) {}'); return false; } catch (e) { return true; } })()",
            setOf("dom/nodes/Element-matches-namespaced-elements.html", "dom/nodes/Element-setAttributeNodeNS.html", "dom/nodes/name-validation.html"),
        ),
        // The page has no body element, and an HTML parser makes one. The probe asks for the head and body that
        // the fragment parser makes for an html element of an HTML document.
        Gap(
            "#547, a page without a body element has no document.body",
            "(function () { var h = document.implementation.createHTMLDocument('').documentElement; h.innerHTML = '<p>x</p>'; " +
                "return h.firstChild.localName !== 'head'; })()",
            setOf("First set attribute is returned with mapped attribute set first"),
        ),
        Gap(
            "kitejs#71, an arrow function cannot take a rest parameter",
            "(function () { try { Function('return (...a) => a'); return false; } catch (e) { return true; } })()",
            setOf("encoding/textdecoder-mistakes.any.js"),
        ),
    )

    /** The test files that KiteJS 0.2.0 cannot parse, as each has a const in a for head (kitejs#11, fixed after it). */
    private val dataDriven = listOf("url-constructor.any.js", "url-origin.any.js", "url-setters.any.js", "urlsearchparams-foreach.any.js")
        .map { "url/$it" }

    private val urlFiles = listOf(
        "url-searchparams.any.js", "url-statics-canparse.any.js", "url-statics-parse.any.js", "url-tojson.any.js",
        "urlsearchparams-append.any.js", "urlsearchparams-constructor.any.js", "urlsearchparams-delete.any.js",
        "urlsearchparams-get.any.js", "urlsearchparams-getall.any.js", "urlsearchparams-has.any.js",
        "urlsearchparams-set.any.js", "urlsearchparams-size.any.js", "urlsearchparams-sort.any.js",
        "urlsearchparams-stringifier.any.js",
    ).map { "url/$it" }

    private val domExceptionFiles = listOf(
        "DOMException-constants.any.js", "DOMException-constructor-and-prototype.any.js",
        "DOMException-constructor-behavior.any.js", "DOMException-custom-bindings.any.js",
    ).map { "webidl/$it" }

    private val encodingFiles = listOf(
        "api-basics.any.js", "api-invalid-label.any.js", "api-replacement-encodings.any.js", "api-surrogates-utf8.any.js",
        "encodeInto.any.js", "iso-2022-jp-decoder.any.js", "single-byte-decoder.any.js?TextDecoder", "textdecoder-arguments.any.js",
        "textdecoder-byte-order-marks.any.js", "textdecoder-copy.any.js", "textdecoder-eof.any.js", "textdecoder-fatal-single-byte.any.js",
        "textdecoder-fatal-streaming.any.js", "textdecoder-fatal.any.js", "textdecoder-ignorebom.any.js", "textdecoder-labels.any.js",
        "textdecoder-mistakes.any.js", "textdecoder-streaming.any.js", "textdecoder-utf16-surrogates.any.js",
        "textencoder-constructor-non-utf.any.js", "textencoder-utf16-surrogates.any.js",
    ).map { "encoding/$it" }

    private val fileApiFiles = listOf(
        "blob/Blob-array-buffer.any.js", "blob/Blob-bytes.any.js", "blob/Blob-constructor-detached-buffer.any.js",
        "blob/Blob-constructor-endings.any.js", "blob/Blob-constructor.any.js", "blob/Blob-newobject.any.js",
        "blob/Blob-slice-overflow.any.js", "blob/Blob-slice.any.js", "blob/Blob-stream.any.js", "blob/Blob-text.any.js",
        "blob/Blob-textStream.any.js", "file/File-constructor-endings.any.js", "file/File-constructor.any.js",
        "fileReader.any.js", "unicode.any.js", "url/url-format.any.js",
        "reading-data-section/Determining-Encoding.any.js", "reading-data-section/FileReader-event-handler-attributes.any.js",
        "reading-data-section/FileReader-multiple-reads.any.js", "reading-data-section/filereader_abort.any.js",
        "reading-data-section/filereader_error.any.js", "reading-data-section/filereader_events.any.js",
        "reading-data-section/filereader_readAsArrayBuffer.any.js", "reading-data-section/filereader_readAsBinaryString.any.js",
        "reading-data-section/filereader_readAsDataURL.any.js", "reading-data-section/filereader_readAsText.any.js",
        "reading-data-section/filereader_readAsText_blob_type_charset.any.js", "reading-data-section/filereader_readystate.any.js",
        "reading-data-section/filereader_result.any.js",
    ).map { "FileAPI/$it" }

    private val reflectionFiles = listOf("embedded", "forms", "grouping", "metadata", "misc", "obsolete", "sections", "tabular", "text")
        .map { "html/dom/reflection-$it.html" }

    /** The tests of `querySelector`, `querySelectorAll`, `matches` and `closest` of the DOM Standard, with Selectors 4 (#549). */
    private val selectorFiles = listOf(
        "ParentNode-querySelector-All.html", "ParentNode-querySelector-All-xht.xht", "Element-matches.html",
        "Element-webkitMatchesSelector.html", "Element-closest.html", "Element-matches-namespaced-elements.html",
        "ParentNode-querySelector-case-insensitive.html", "ParentNode-querySelector-escapes.html", "ParentNode-querySelector-scope.html",
        "ParentNode-querySelectors-exclusive.html", "ParentNode-querySelectors-namespaces.html",
        "ParentNode-querySelectors-space-and-dash-attribute-value.html",
    ).map { "dom/nodes/$it" }

    /**
     * The tests of attributes of the DOM Standard: their names, namespaces and prefixes, `Attr`,
     * `NamedNodeMap`, and the methods of `Element` and `Document` that read and set them (#545).
     */
    private val attributeFiles = listOf(
        "attributes.html", "Attr-prefix.html", "Attr-prefix-xhtml.xhtml", "Document-createAttribute.html",
        "Element-hasAttribute.html", "Element-hasAttributes.html", "Element-removeAttribute.html",
        "Element-removeAttributeNS.html", "Element-setAttribute.html", "Element-setAttribute-crbug-1138487.html",
        "Element-setAttributeNodeNS.html", "attributes-namednodemap.html", "name-validation.html",
    ).map { "dom/nodes/$it" }

    /**
     * The pages whose markup is part of the test, which run as the chapter's own document. The
     * others run their scripts in a chapter of their own, as an HTML page that leaves out `<body>`
     * has no `document.body` until #547.
     */
    private val markupPages: Set<String> = (selectorFiles + attributeFiles).toSet()

    /**
     * A page whose tests run in a frame it makes, as [document], the page the frame loads; [start]
     * is the call the frame's `load` makes, with the frame or its event standing in as an object
     * that has the chapter's document.
     */
    private class Frame(val document: String, val start: String)

    private val frames = mapOf(
        "dom/nodes/ParentNode-querySelector-All.html" to Frame("dom/nodes/ParentNode-querySelector-All-content.html", "init({ contentDocument: document })"),
        "dom/nodes/ParentNode-querySelector-All-xht.xht" to Frame("dom/nodes/ParentNode-querySelector-All-content.xht", "init({ contentDocument: document })"),
        "dom/nodes/Element-matches.html" to
            Frame("dom/nodes/ParentNode-querySelector-All-content.html", "init({ target: { contentDocument: document } }, 'matches')"),
        "dom/nodes/Element-webkitMatchesSelector.html" to
            Frame("dom/nodes/ParentNode-querySelector-All-content.html", "init({ target: { contentDocument: document } }, 'webkitMatchesSelector')"),
    )

    /** The files of this folder that stand in for scripts of web-platform-tests that a test names, null where `harness.js` does. */
    private val standIns = mapOf(
        "/common/sab.js" to "sab.js",
        "/common/subset-tests-by-key.js" to null,
        "/FileAPI/support/Blob.js" to "blob-support.js",
    )

    /**
     * The scripts that the META lines of [source] name, as paths of `resources/wpt`: a path from
     * the root of web-platform-tests, or from the folder of the file at [path].
     */
    private fun metaScripts(path: String, source: String): List<String> = source.lines()
        .mapNotNull { Regex("^// META: script=(\\S+)").find(it)?.groupValues?.get(1) }
        .map { if (it.startsWith("/")) it else normalized("/" + path.substringBeforeLast('/', "") + "/" + it) }
        .mapNotNull { if (it in standIns) standIns[it] else it.removePrefix("/") }

    /** [path] without its `.` and `..` segments. */
    private fun normalized(path: String): String {
        val segments = ArrayList<String>()
        for (segment in path.split('/')) {
            when (segment) {
                "." -> {}
                ".." -> segments.removeLast()
                else -> segments += segment
            }
        }
        return segments.joinToString("/")
    }

    /** A file of `resources/wpt`, by its path there. */
    private fun resource(path: String): String =
        checkNotNull(javaClass.getResourceAsStream("/wpt/$path")) { "no resource $path" }.readBytes().decodeToString()

    /** The test data of the URL parser, in kitepdf-epub, from the repository root, so any working directory works. */
    private val data: File = run {
        var dir: File? = File(System.getProperty("user.dir")).absoluteFile
        while (dir != null && !File(dir, "settings.gradle.kts").exists()) dir = dir.parentFile
        File(checkNotNull(dir) { "no repository root above ${System.getProperty("user.dir")}" }, "kitepdf-epub/src/jvmTest/resources/wpt-url")
    }

    /** What the harness's `fetch` answers: each JSON file the tests load, as a JavaScript literal. */
    private val harnessData: String by lazy {
        val urlData = listOf("urltestdata.json", "urltestdata-javascript-only.json", "setters_tests.json").map {
            "\"resources/$it\": " + File(data, it).readText()
        }
        val base64 = "\"../../../fetch/data-urls/resources/base64.json\": " + resource("fetch/data-urls/resources/base64.json")
        (urlData + base64).joinToString(",\n", "var harnessData = {\n", "\n};")
    }

    /** The console of a chapter whose scripts are [files], in order, and the failures of its scripts, as [open] gives them. */
    private fun chapter(files: Map<String, String>, html: Boolean = false): Pair<List<String>, List<String>> =
        open(ScriptBooks.chapter(files.keys.joinToString("") { "<script src=\"$it\"></script>" }, extraFiles = files, html = html))

    /**
     * The console of the one chapter of [book], and the failures of its scripts, once the harness
     * logged its count or nothing is left to wait for. The chapter's clock runs as the real one
     * does and jumps over each wait for a timer, so a test that waits seconds takes none. A call
     * has a minute, as a reflection page runs thousands of tests in the one call that loads it.
     */
    private fun open(book: EpubDocument): Pair<List<String>, List<String>> {
        val console = ArrayList<String>()
        val started = TimeSource.Monotonic.markNow()
        var skipped = 0L
        val clock = { started.elapsedNow().inWholeMilliseconds + skipped }
        val runner = EpubScriptRunner(book, EpubScriptPolicy(budgetMillis = 60_000), onConsole = { level, message -> console += "$level: $message" }, clock = clock).also { runners += it }
        runner.chapterOpened(0)
        while (runner.hasTimers && console.none { it.startsWith("log: DONE ") } && skipped < MAX_WAIT_MILLIS) {
            skipped += runner.pumpTimers(clock()) ?: break
        }
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

    /** What a file did: the names of its tests that fail, the names of those that pass, and the failures of its scripts that did not run. */
    private class FileRun(val failing: List<String>, val passing: List<String>, val broken: List<String>)

    /** A `<script>` element of a page: its `src` in one of the first three groups, as the attribute is quoted, and its text in the fourth. */
    private val scriptElement = Regex("<script(?:\\s+src=(?:\"([^\"]*)\"|'([^']*)'|([^\\s>]+)))?[^>]*>(.*?)</script>", RegexOption.DOT_MATCHES_ALL)

    private fun srcOf(m: MatchResult): String? = m.groupValues.drop(1).take(3).firstOrNull { it.isNotEmpty() }

    /** The path in `resources/wpt` of the script [src] that the page at [path] names, from the page's folder or the root of web-platform-tests. */
    private fun scriptPath(path: String, src: String): String =
        if (src.startsWith("/")) src.removePrefix("/") else normalized("/" + path.substringBeforeLast('/', "") + "/" + src).removePrefix("/")

    /**
     * The scripts of the page [source] at [path], in order, as names and sources: each `<script>`
     * element's file, from the folder of the page or the root of web-platform-tests, or its own
     * text, but for those of testharness.js, which `harness.js` stands in for.
     */
    private fun pageScripts(path: String, source: String): Map<String, String> {
        val scripts = LinkedHashMap<String, String>()
        for ((i, m) in scriptElement.findAll(source).withIndex()) {
            val src = srcOf(m)
            when {
                src == null -> scripts["inline-$i.js"] = m.groupValues[4].removeCData()
                src.startsWith("/resources/testharness") -> {}
                else -> scriptPath(path, src).let { scripts[it.replace('/', '-')] = resource(it) }
            }
        }
        return scripts
    }

    /** The text of an XHTML page's inline script, without the CDATA section that wraps it. */
    private fun String.removeCData(): String = trim().removePrefix("<![CDATA[").removeSuffix("]]>")

    /**
     * Runs the page [source] at [path] as the chapter's own document, served as text/html, or as
     * XHTML for a `.xht` page. The element that loads testharness.js loads `harness.js` and the
     * harness's data instead, the one that loads testharnessreport.js goes, and each other `src`
     * names its file as [scriptPath] finds it. A page of [frames] runs as the document its frame
     * loads instead: the page's scripts run at the end of that document's body, with `async_test`
     * a stub while they do, so the frame is never made, and then they leave the document, which
     * has none in the frame, and the frame's call starts the tests in an `async_test` of its own.
     */
    private fun runPage(path: String, source: String): Pair<List<String>, List<String>> {
        val files = linkedMapOf("harness.js" to resource("harness.js"), "data.js" to harnessData)
        val frame = frames[path]
        if (frame == null) {
            val markup = scriptElement.replace(source) { m ->
                val src = srcOf(m)
                when {
                    src == null -> m.value
                    src == "/resources/testharness.js" -> files.keys.joinToString("") { "<script src=\"$it\"></script>" }
                    src.startsWith("/resources/testharness") -> ""
                    else -> scriptPath(path, src).replace('/', '-').also { files[it] = resource(scriptPath(path, src)) }
                        .let { "<script src=\"$it\"></script>" }
                }
            }
            return open(ScriptBooks.page(markup, files, html = !isXhtml(path)))
        }
        files["frame-before.js"] = "var harnessAsyncTest = async_test; async_test = function () {};"
        files += pageScripts(path, source)
        files["frame-after.js"] = """
            async_test = harnessAsyncTest;
            var harnessScripts = document.getElementsByTagName('script');
            while (harnessScripts.length) harnessScripts[0].parentNode.removeChild(harnessScripts[0]);
            async_test(function (t) { t.step_func_done(function () { ${frame.start}; })(); }, 'the frame loads');
        """.trimIndent()
        val document = resource(frame.document)
        val scripts = files.keys.joinToString("") { "<script src=\"$it\"></script>" }
        val end = document.lastIndexOf("</body>")
        return open(ScriptBooks.page(document.substring(0, end) + scripts + document.substring(end), files, html = !isXhtml(frame.document)))
    }

    /** Whether the page at [path] is served as XHTML, as web-platform-tests serves a `.xht` or `.xhtml` file. */
    private fun isXhtml(path: String): Boolean = path.endsWith(".xht") || path.endsWith(".xhtml")

    /** Runs the file at [path], a query after it naming the variant. A page, a `.html` file, runs in an HTML chapter with its scripts. */
    private fun run(path: String): FileRun {
        val file = path.substringBefore('?')
        val variant = path.substringAfter('?', "")
        val name = file.substringAfterLast('/')
        val source = resource(file)
        val page = file.endsWith(".html") || isXhtml(file)
        val scripts = linkedMapOf("harness.js" to resource("harness.js"), "data.js" to harnessData)
        if (variant.isNotEmpty()) scripts["variant.js"] = "Object.defineProperty(location, 'search', { value: '?$variant', configurable: true });"
        if (page) {
            scripts += pageScripts(file, source)
        } else {
            for (script in metaScripts(file, source)) scripts[script.replace('/', '-')] = resource(script)
            scripts[name] = source
        }
        val (console, failures) = if (file in markupPages) runPage(file, source) else chapter(scripts, html = page)
        val done = console.singleOrNull { it.startsWith("log: DONE ") }
        assertTrue(done != null, "$name did not finish: $failures\n" + console.takeLast(20).joinToString("\n"))
        val (passed, failed) = done.removePrefix("log: DONE ").split(' ').map { it.toInt() }
        val failing = console.filter { it.startsWith("log: FAIL ") }.map { it.removePrefix("log: FAIL ") }
        val passing = console.filter { it.startsWith("log: PASS ") }.map { it.removePrefix("log: PASS ") }
        assertEquals(failed, failing.size)
        assertEquals(passed, passing.size)
        return FileRun(failing, passing, failures)
    }

    /** Runs [paths], and fails on a test that fails without an open gap, or that passes while its gap is open. */
    private fun check(paths: List<String>, atLeast: Int) {
        fun expected(test: String) = openGaps.any { it.covers(test) }
        val unexpected = ArrayList<String>()
        val stale = ArrayList<String>()
        var passed = 0
        for (path in paths) {
            val run = run(path)
            passed += run.passing.size
            if (run.broken.isNotEmpty()) {
                if (!expected(path)) unexpected += "$path does not run: ${run.broken}"
            } else if (expected(path)) {
                stale += path
            }
            for (failure in run.failing) {
                if (!expected(failure.substringBefore(" :: "))) unexpected += "$path: $failure"
            }
            stale += run.passing.filter { expected(it) }
        }
        assertTrue(passed >= atLeast, "only $passed tests passed")
        assertTrue(unexpected.isEmpty(), "${unexpected.size} failures, $passed passed:\n" + unexpected.take(60).joinToString("\n"))
        assertTrue(stale.isEmpty(), "these pass although their gap is open, so its probe is wrong: $stale")
    }

    @Test
    fun the_url_tests_pass_but_for_known_gaps() = check(urlFiles, atLeast = 90)

    @Test
    fun the_data_driven_url_tests_pass() {
        val probe = "console.log('PROBE ' + (function () { try { Function('for (const x of []) {}'); return 'yes'; } catch (e) { return 'no'; } })());"
        val (console, _) = chapter(mapOf("probe.js" to probe))
        assumeTrue("The KiteJS in use cannot parse a const in a for head (kitejs#11, fixed after 0.2.0).", "log: PROBE yes" in console)
        check(dataDriven, atLeast = 1500)
    }

    @Test
    fun the_dom_exception_tests_pass() = check(domExceptionFiles, atLeast = 100)

    @Test
    fun the_atob_and_btoa_tests_pass() = check(listOf("html/webappapis/atob/base64.any.js"), atLeast = 300)

    @Test
    fun the_encoding_tests_pass_but_for_known_gaps() = check(encodingFiles, atLeast = 1000)

    @Test
    fun the_file_api_tests_pass_but_for_known_gaps() = check(fileApiFiles, atLeast = 300)

    @Test
    fun the_reflection_tests_pass_but_for_known_gaps() = check(reflectionFiles, atLeast = 59_000)

    @Test
    fun the_interface_tests_pass_but_for_known_gaps() = check(listOf("html/semantics/interfaces.html"), atLeast = 300)

    @Test
    fun the_selector_tests_pass_but_for_known_gaps() = check(selectorFiles, atLeast = 5_300)

    @Test
    fun the_attribute_tests_pass_but_for_known_gaps() = check(attributeFiles, atLeast = 120)

    private companion object {
        /** How long a chapter may wait on its timers in all, on its clock: past the harness's own ten seconds. */
        const val MAX_WAIT_MILLIS = 60_000L
    }
}
