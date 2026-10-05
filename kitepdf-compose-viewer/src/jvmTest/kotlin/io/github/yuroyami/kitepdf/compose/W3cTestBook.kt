package io.github.yuroyami.kitepdf.compose

import io.github.yuroyami.kitepdf.core.KiteLocation
import io.github.yuroyami.kitepdf.core.render.RecordingCanvas
import io.github.yuroyami.kitepdf.epub.EpubDocument
import io.github.yuroyami.kitepdf.epub.EpubPage
import io.github.yuroyami.kitepdf.javascript.EpubScriptRunner
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * One test of the W3C EPUB test suite, packed from its folder as the suite's own script packs it,
 * and opened the way a reader opens it (#497): what a check of [EpubConformanceChecks] asks of it.
 */
internal class W3cTestBook(val folder: File) {

    val id: String = folder.name

    /** The folder as an EPUB: `mimetype` first and stored, then every other file. */
    val bytes: ByteArray by lazy { pack(folder) }

    /** The book, or the failure that opening it threw. */
    val opened: Result<EpubDocument> by lazy { runCatching { EpubDocument.open(bytes) } }

    val doc: EpubDocument get() = opened.getOrThrow()

    private var runner: EpubScriptRunner? = null

    /**
     * Runs the scripts of every scripted chapter, as a reader opening them does, and then their
     * timers for [millis] of a clock the check moves. A check that asks after the scripts calls it
     * before it reads the pages.
     */
    fun runScripts(millis: Long = 2_000): EpubScriptRunner {
        runner?.let { return it }
        var now = 0L
        val scripts = EpubScriptRunner(doc, onConsole = { level, message -> console += "$level: $message" }, clock = { now })
        runner = scripts
        for (chapter in doc.scriptedChapters) scripts.chapterOpened(chapter)
        while (scripts.hasTimers && now < millis) {
            now += 16
            scripts.pumpTimers(now)
        }
        return scripts
    }

    /** What the scripts printed, and what `alert` and its kin would have shown. */
    val console = ArrayList<String>()

    fun close() {
        runner?.close()
    }

    val chapters: Int get() = doc.chapterCount

    fun pages(chapter: Int): Int = doc.pageCountIn(chapter)

    fun page(chapter: Int, page: Int = 0): EpubPage = doc.page(KiteLocation(chapter, page))

    /** The text of a page, with its white space folded. */
    fun text(chapter: Int, page: Int = 0): String = page(chapter, page).textContent().plainText.replace(Regex("\\s+"), " ").trim()

    /** The text of every page of a chapter. */
    fun chapterText(chapter: Int): String = (0 until pages(chapter)).joinToString(" ") { text(chapter, it) }

    /** Every call a page makes to a canvas. */
    fun calls(chapter: Int, page: Int = 0): List<RecordingCanvas.Call> = RecordingCanvas().also { page(chapter, page).renderTo(it) }.calls

    fun glyphRuns(chapter: Int, page: Int = 0): List<RecordingCanvas.Call.Glyphs> = calls(chapter, page).filterIsInstance<RecordingCanvas.Call.Glyphs>()

    fun fills(chapter: Int, page: Int = 0): List<RecordingCanvas.Call.Fill> = calls(chapter, page).filterIsInstance<RecordingCanvas.Call.Fill>()

    fun images(chapter: Int, page: Int = 0): List<RecordingCanvas.Call.Image> = calls(chapter, page).filterIsInstance<RecordingCanvas.Call.Image>()

    /**
     * The spreads the viewer pairs the book's pages into, in a landscape viewport: each spread
     * is its pages as (chapter, page) pairs, left to right in reading order.
     */
    fun spreads(landscape: Boolean = true): List<List<Pair<Int, Int>>> {
        val (slots, plan) = plan(landscape)
        return plan.spreads.map { spread -> spread.map { slots[it] } }
    }

    /** For each of [spreads], the side its one page sits on, or null for a page in the middle and for a pair (#504). */
    fun spreadSides(landscape: Boolean = true): List<SpreadSide?> {
        val plan = plan(landscape).second
        return plan.spreads.indices.map(plan::sideOf)
    }

    private fun plan(landscape: Boolean): Pair<List<Pair<Int, Int>>, SpreadPlan> {
        val slots = (0 until chapters).flatMap { c -> (0 until pages(c)).map { c to it } }
        return slots to pairSpreads(slots.size, rightToLeft = doc.epubMetadata.rightToLeft) { slot ->
            val (c, p) = slots[slot]
            epubSide(doc, c, p, landscape)
        }
    }

    private companion object {
        fun pack(folder: File): ByteArray {
            val out = ByteArrayOutputStream()
            ZipOutputStream(out).use { zip ->
                val mimetype = File(folder, "mimetype").readBytes()
                zip.putNextEntry(
                    ZipEntry("mimetype").apply {
                        method = ZipEntry.STORED
                        size = mimetype.size.toLong()
                        compressedSize = mimetype.size.toLong()
                        crc = CRC32().apply { update(mimetype) }.value
                    },
                )
                zip.write(mimetype)
                zip.closeEntry()
                folder.walkTopDown()
                    .filter { it.isFile && it.name != "mimetype" && it.name != ".DS_Store" }
                    .map { it.relativeTo(folder).invariantSeparatorsPath to it }
                    .sortedBy { it.first }
                    .forEach { (name, file) ->
                        zip.putNextEntry(ZipEntry(name))
                        zip.write(file.readBytes())
                        zip.closeEntry()
                    }
            }
            return out.toByteArray()
        }
    }
}
