package io.github.yuroyami.kitepdf.epub

import io.github.yuroyami.kitepdf.core.KiteLocation
import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.resume
import kotlin.coroutines.startCoroutine
import kotlin.coroutines.suspendCoroutine
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * A chapter laid out a step at a time, with a checkpoint between two steps, ends as the same
 * pages as a chapter laid out at once, however long it waits at each checkpoint and whatever
 * runs meanwhile (#389). The checkpoints here really suspend, as a browser's wait for a frame
 * does, and the test resumes them one at a time.
 */
class SteppedChapterLayoutTest {

    private val settings = EpubSettings(pageWidth = 400.0, pageHeight = 600.0, margin = 30.0)

    private val para = "Text that wraps over a few lines, with <em>inline</em> runs and a <a href=\"#x\">link</a>."

    /** Floats, out-of-flow and positioned boxes, flex, grid, columns, tables, lists and deep nesting. */
    private fun chapter(c: Int): String = """<style>.p{position:relative;top:3px}.a{position:absolute;right:0;top:8px;width:40px}
        .fl{float:left;width:80px;height:40px}.cl{clear:both}.flex{display:flex}.grid{display:grid;grid-template-columns:1fr 2fr}
        .cols{columns:2}.box{margin:10px 4px;padding:3px;border:2px solid blue}</style><h1>Chapter $c</h1>""" +
        (0 until 8).joinToString("") { i ->
            "<div class=\"box p\"><p>$i $para</p><div class=\"a\">abs</div><div class=\"fl\">float</div><p>$para $para</p>" +
                "<p class=\"cl\">$para</p><div class=\"flex\"><div>$para</div><div><p>$para</p></div></div>" +
                "<div class=\"grid\"><div>$para</div><div class=\"box\">$para</div></div><div class=\"cols\"><p>$para</p><p>$para</p></div>" +
                "<table><tr><td>$para</td><td><div class=\"box\">$para</div></td></tr></table><ul><li>$para</li><li>$para</li></ul>" +
                "<div class=\"box\">".repeat(12) + "deep $para" + "</div>".repeat(12) + "</div>"
        }

    private val book = EpubFixtures.epubMultiSpine(listOf(chapter(1), chapter(2)))

    /** Every line, run, image and decorated box of [chapter]'s pages, where it lies. */
    private fun pagesOf(doc: EpubDocument, chapter: Int): String = buildString {
        for (p in 0 until doc.pageCountIn(chapter)) {
            val page = doc.render(chapter, p)
            append("page ").append(p).append(' ').append(page.startY).append('\n')
            for (line in page.lines) {
                append(line.yTop).append(' ').append(line.height)
                for (run in line.runs) append(" [").append(run.x).append(' ').append(run.glyphs.joinToString("") { it.text }).append(']')
                append('\n')
            }
            for (box in page.decoBoxes) append("deco ").append(box.x).append(' ').append(box.y).append(' ').append(box.borderBoxHeight).append('\n')
        }
    }

    /** A layout whose checkpoints suspend until [resume] is called, as a wait for a frame does. */
    private class Stepped(doc: EpubDocument, chapter: Int) {
        var checkpoints = 0
        var done = false
        private var waiting: Continuation<Unit>? = null

        init {
            suspend { doc.prepareChapter(chapter) { suspendCoroutine { waiting = it; checkpoints++ } } }
                .startCoroutine(Continuation(EmptyCoroutineContext) { it.getOrThrow(); done = true })
        }

        /** Runs the layout to its next checkpoint, and returns false once it is done. */
        fun resume(): Boolean {
            val next = waiting ?: return false
            waiting = null
            next.resume(Unit)
            return !done
        }
    }

    @Test
    fun a_chapter_laid_out_in_steps_has_the_pages_of_one_laid_out_at_once() {
        val atOnce = EpubDocument.open(book, settings).let { doc -> doc.prepareChapter(0); pagesOf(doc, 0) }
        val doc = EpubDocument.open(book, settings)
        val stepped = Stepped(doc, 0)
        assertTrue(!doc.isChapterReady(0), "the layout did not stop at its first checkpoint")
        while (stepped.resume()) assertTrue(!doc.isChapterReady(0), "the chapter was ready before its last step")
        assertTrue(doc.isChapterReady(0))
        assertEquals(atOnce, pagesOf(doc, 0))
        // A step is at most one block: the chapter has more than 300 blocks in its flow.
        assertTrue(stepped.checkpoints > 300, "only ${stepped.checkpoints} checkpoints")
    }

    @Test
    fun two_chapters_laid_out_in_turns_have_the_pages_of_each_laid_out_at_once() {
        val atOnce = EpubDocument.open(book, settings).let { doc -> listOf(0, 1).map { doc.prepareChapter(it); pagesOf(doc, it) } }
        val doc = EpubDocument.open(book, settings)
        val first = Stepped(doc, 0)
        val second = Stepped(doc, 1)
        var going = true
        while (going) going = first.resume() or second.resume()
        assertEquals(atOnce, listOf(pagesOf(doc, 0), pagesOf(doc, 1)))
    }

    @Test
    fun a_chapter_laid_out_at_once_meanwhile_keeps_its_pages() {
        val doc = EpubDocument.open(book, settings)
        val stepped = Stepped(doc, 0)
        repeat(20) { stepped.resume() }
        // A draw or a lookup that needs the chapter now lays it out at once.
        doc.prepareChapter(0)
        val page = doc.page(KiteLocation(0, 0))
        val pages = pagesOf(doc, 0)
        while (stepped.resume()) Unit
        assertSame(page, doc.page(KiteLocation(0, 0)), "the stepped layout replaced the chapter's pages")
        assertEquals(pages, pagesOf(doc, 0))
    }
}
