package io.github.yuroyami.kitepdf.epub

import io.github.yuroyami.kitepdf.core.KiteLocation
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A background loader prepares chapters while the reader scrolls, so
 * [EpubDocument.prepareChapter] gets called from several threads at once,
 * sometimes for the same chapter. That must lay each chapter out once and
 * produce exactly what a single thread would have.
 */
class ChapterConcurrencyTest {

    private val settings = EpubSettings(pageWidth = 400.0, pageHeight = 640.0)

    private fun bytes(chapters: Int) = EpubFixtures.epubMultiSpine(
        List(chapters) { c ->
            "<h1>Chapter ${c + 1}</h1>" +
                (0 until 30).joinToString("") {
                    "<p>Chapter ${c + 1} paragraph $it, long enough to wrap and make real work.</p>"
                }
        },
    )

    private fun textOf(doc: EpubDocument): List<String> = buildList {
        for (c in 0 until doc.chapterCount) {
            for (p in 0 until doc.pageCountIn(c)) {
                add(doc.page(KiteLocation(c, p)).textContent().plainText)
            }
        }
    }

    @Test
    fun eight_threads_preparing_every_chapter_agree_with_one() {
        val source = bytes(10)
        val serial = textOf(EpubDocument.open(source, settings))

        repeat(5) { round ->
            val doc = EpubDocument.open(source, settings)
            val errors = ConcurrentLinkedQueue<Throwable>()
            val start = CountDownLatch(1)
            val threads = (0 until 8).map { t ->
                thread {
                    start.await()
                    try {
                        // Overlapping orders, so several threads hit one chapter.
                        val order = if (t % 2 == 0) 0 until doc.chapterCount else (doc.chapterCount - 1) downTo 0
                        for (c in order) doc.prepareChapter(c)
                    } catch (e: Throwable) {
                        errors.add(e)
                    }
                }
            }
            start.countDown()
            threads.forEach { it.join() }
            assertTrue(errors.isEmpty(), "round $round threw ${errors.firstOrNull()}")
            assertTrue(doc.isComplete, "round $round did not finish every chapter")
            assertEquals(serial, textOf(doc), "round $round produced different pages")
        }
    }

    /** Racing on ONE chapter must still lay it out once and hand back one list. */
    @Test
    fun racing_on_a_single_chapter_yields_one_stable_result() {
        val doc = EpubDocument.open(bytes(4), settings)
        val seen = ConcurrentLinkedQueue<List<String>>()
        val start = CountDownLatch(1)
        val threads = (0 until 8).map {
            thread {
                start.await()
                doc.prepareChapter(2)
                seen.add((0 until doc.pageCountIn(2)).map { p -> doc.page(KiteLocation(2, p)).textContent().plainText })
            }
        }
        start.countDown()
        threads.forEach { it.join() }
        assertEquals(8, seen.size)
        assertEquals(1, seen.distinct().size, "threads disagreed about chapter 2's pages")
        for (c in listOf(0, 1, 3)) assertTrue(!doc.isChapterReady(c), "chapter $c was laid out by the race")
    }
}
