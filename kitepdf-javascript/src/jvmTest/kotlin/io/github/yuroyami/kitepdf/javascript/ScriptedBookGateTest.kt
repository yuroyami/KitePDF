package io.github.yuroyami.kitepdf.javascript

import io.github.yuroyami.kitepdf.core.KiteLocation
import io.github.yuroyami.kitepdf.core.render.RecordingCanvas
import io.github.yuroyami.kitepdf.epub.EpubDocument
import io.github.yuroyami.kitepdf.epub.EpubPage
import java.io.File
import kotlin.math.roundToLong
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * The scripted books of the public corpus, run as a reader runs them (#496).
 *
 * Every other script test builds its book in memory, so it only shows what its author thought to
 * write. These are books someone else made: the W3C tests of spine-level scripting, and an IDPF
 * sample that drives its pages with jQuery 1.7.1. Each scripted chapter opens through
 * [EpubScriptRunner], gets a tap, and runs its timers on a clock the test moves, and the page
 * then shows what the scripts did.
 *
 * A failure a book is known to hit is listed in [KNOWN_FAILURES] with its issue. Any other
 * failure fails the gate, and so does a known one that stops happening, so the list stays true.
 */
class ScriptedBookGateTest {

    private val runners = ArrayList<EpubScriptRunner>()

    @AfterTest
    fun closeRunners() {
        runners.forEach { it.close() }
    }

    /** The public EPUBs, from the repository root, so any working directory works. */
    private val corpus: File = run {
        var dir: File? = File(System.getProperty("user.dir")).absoluteFile
        while (dir != null && !File(dir, "settings.gradle.kts").exists()) dir = dir.parentFile
        File(checkNotNull(dir) { "no repository root above ${System.getProperty("user.dir")}" }, "corpus/epub")
    }

    private fun book(name: String): EpubDocument {
        val file = File(corpus, "$name.epub")
        assertTrue(file.isFile, "$file is tracked; run `python3 tools/corpus.py verify`")
        return EpubDocument.open(file.readBytes())
    }

    /** A book's scripts after each scripted chapter opened, was tapped in its middle and ran its timers for [millis]. */
    private inner class Run(val name: String, val millis: Long = 2_000) {
        var now = 0L
        val book = book(name)
        val console = ArrayList<String>()
        val scripts = EpubScriptRunner(book, onConsole = { level, message -> console += "$level: $message" }, clock = { now })
            .also { runners += it }

        init {
            for (chapter in book.scriptedChapters) scripts.chapterOpened(chapter)
        }

        fun page(chapter: Int): EpubPage = book.page(KiteLocation(chapter, 0))

        fun tapAndWait(chapter: Int) {
            val page = page(chapter)
            scripts.tap(page, page.width / 2, page.height / 2)
            val until = now + millis
            while (scripts.hasTimers && now < until) {
                now += 16
                scripts.pumpTimers(now)
            }
        }

        fun text(chapter: Int): String = page(chapter).textContent().plainText.replace(Regex("\\s+"), " ")

        /** Fails on a failure [KNOWN_FAILURES] does not list for this book, and on a listed one that did not happen. */
        fun assertOnlyKnownFailures() {
            val known = KNOWN_FAILURES[name].orEmpty()
            val messages = scripts.failures.map { it.message.orEmpty() }
            val unknown = messages.filter { message -> known.none { it.first in message } }
            assertEquals(emptyList(), unknown, "$name: failures no issue explains")
            for ((part, issue) in known) {
                assertTrue(messages.any { part in it }, "$name no longer fails with \"$part\" ($issue): take it off KNOWN_FAILURES")
            }
        }
    }

    /** The text a page draws where a reader can see it: glyphs whose alpha, with their groups', is above zero. */
    private fun visibleText(page: EpubPage): String {
        val canvas = RecordingCanvas().also { page.renderTo(it) }
        val alphas = ArrayList<Double>()
        val out = StringBuilder()
        for (call in canvas.calls) when (call) {
            is RecordingCanvas.Call.PushGroup -> alphas += (alphas.lastOrNull() ?: 1.0) * call.alpha
            is RecordingCanvas.Call.PopGroup -> alphas.removeAt(alphas.lastIndex)
            is RecordingCanvas.Call.Glyphs -> if ((alphas.lastOrNull() ?: 1.0) * call.alpha > 0.01) out.append(call.text).append(' ')
            else -> {}
        }
        return out.toString().replace(Regex("\\s+"), " ")
    }

    /** The alpha of each image a page draws, with its groups'. */
    private fun imageAlphas(page: EpubPage): List<Double> {
        val canvas = RecordingCanvas().also { page.renderTo(it) }
        val alphas = ArrayList<Double>()
        val out = ArrayList<Double>()
        for (call in canvas.calls) when (call) {
            is RecordingCanvas.Call.PushGroup -> alphas += (alphas.lastOrNull() ?: 1.0) * call.alpha
            is RecordingCanvas.Call.PopGroup -> alphas.removeAt(alphas.lastIndex)
            is RecordingCanvas.Call.Image -> out += (alphas.lastOrNull() ?: 1.0) * call.alpha
            else -> {}
        }
        return out
    }

    /** The books that `corpus/manifest.json` lists: the public corpus, without a developer's own books. */
    private val listed: Set<String> = Regex("\"path\": \"corpus/epub/([^\"]+)\\.epub\"")
        .findAll(File(corpus.parentFile, "manifest.json").readText())
        .map { it.groupValues[1] }
        .toSet()

    @Test
    fun every_scripted_book_of_the_corpus_is_in_the_gate() {
        val scripted = corpus.listFiles().orEmpty()
            .filter { it.extension == "epub" && it.nameWithoutExtension in listed }
            .filter { EpubDocument.open(it.readBytes()).scriptedChapters.isNotEmpty() }
            .map { it.nameWithoutExtension }
            .toSortedSet()
        assertEquals(GATED.toSortedSet(), scripted, "a scripted book in corpus/epub needs its checks here")
    }

    @Test
    fun scr_support_shows_that_scripts_run() {
        val run = Run("w3c-scr-support")
        assertTrue("This reading system supports scripting. Test passes." in run.text(0), run.text(0))
        run.assertOnlyKnownFailures()
    }

    @Test
    fun scr_readingsystem_support_finds_the_reading_system() {
        val run = Run("w3c-scr-readingsystem-support")
        assertTrue("implements the epubReadingSystem object. Test passes." in run.text(0), run.text(0))
        run.assertOnlyKnownFailures()
    }

    @Test
    fun scr_readingsystem_features_lists_each_feature_where_its_script_parses() {
        val run = Run("w3c-scr-readingsystem-features")
        val text = run.text(0)
        assertTrue("implements the epubReadingSystem object with the following features" in text, text)
        assertFalse("does not implement" in text, "the failure text is hidden: $text")
        val features = listOf(
            "dom-manipulation: true", "layout-changes: true", "touch-events: false",
            "mouse-events: true", "keyboard-events: false", "spine-scripting: true",
        )
        for (feature in features) assertTrue(feature in text, "the page lists $feature: $text")
        assertEquals(emptyList(), run.scripts.failures.map { it.message }, "no script fails")
    }

    @Test
    fun the_chapters_of_one_book_share_an_origin_and_another_book_has_another() {
        val run = Run("w3c-scr-support_origin")
        val origin = Regex("Origin URL: (\\S+)")
        val first = origin.find(run.text(0))?.groupValues?.get(1)
        val second = origin.find(run.text(1))?.groupValues?.get(1)
        assertTrue(first != null && Regex("epub://[0-9a-f]{16}").matches(first), run.text(0))
        assertEquals(first, second, "both chapters of scr-support_origin show the same origin")
        run.assertOnlyKnownFailures()

        val other = Run("w3c-scr-support_origin_unique")
        val unique = origin.find(other.text(0))?.groupValues?.get(1)
        assertTrue(unique != null && unique.startsWith("epub://"), other.text(0))
        assertNotEquals(first, unique, "scr-support_origin_unique has an origin of its own")
        other.assertOnlyKnownFailures()
    }

    @Test
    fun the_voyage_of_life_runs_jquery_and_a_tap_fades_the_caption_in() {
        val run = Run("idpf-cole-voyage-of-life-tol")
        val paintings = run.book.scriptedChapters
        assertEquals(4, paintings.size, "the four paintings carry the scripts: $paintings")
        for (chapter in paintings) {
            val page = run.page(chapter)
            fun captionLetters() = visibleText(page).count { it.isLetter() }
            // jQuery's ready handler asked the reading system for its features, and hid the caption.
            assertEquals(0, captionLetters(), "chapter $chapter hides its caption: ${visibleText(page)}")
            // The full painting, and the detail inside the hidden figure.
            assertEquals(listOf(1.0, 0.0), imageAlphas(page).map { (it * 100).roundToLong() / 100.0 }, "the painting shows in full")

            run.tapAndWait(chapter)

            assertTrue(captionLetters() > 200, "the tap faded the caption in: ${visibleText(page)}")
            assertEquals(listOf(0.6, 1.0), imageAlphas(page).map { (it * 100).roundToLong() / 100.0 }, "and the painting down to 0.6, over a second of animation")
        }
        run.assertOnlyKnownFailures()
    }

    private companion object {
        /** The scripted books of corpus/epub, each checked by a test above. */
        val GATED = listOf(
            "idpf-cole-voyage-of-life-tol",
            "w3c-scr-readingsystem-features",
            "w3c-scr-readingsystem-support",
            "w3c-scr-support",
            "w3c-scr-support_origin",
            "w3c-scr-support_origin_unique",
        )

        /** Failures a book is known to hit: a part of the message, and the issue that tracks it. */
        val KNOWN_FAILURES: Map<String, List<Pair<String, String>>> = emptyMap()
    }
}
