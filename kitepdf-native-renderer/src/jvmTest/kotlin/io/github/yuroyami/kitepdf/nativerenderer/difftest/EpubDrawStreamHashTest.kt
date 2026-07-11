package io.github.yuroyami.kitepdf.nativerenderer.difftest

import io.github.yuroyami.kitepdf.epub.EpubDocument
import io.github.yuroyami.kitepdf.core.render.Matrix
import io.github.yuroyami.kitepdf.core.render.RecordingCanvas
import java.io.File
import java.security.MessageDigest
import kotlin.test.Test

/**
 * T-72 invariance harness: records a per-book hash of the full draw stream
 * (every RecordingCanvas call of every page) for the synthetic fixtures and
 * the real corpus. Run once before a layout change and once after; identical
 * files prove horizontal books are untouched.
 *
 *   -Dkitepdf.epub.hashout=/path/to/hashes.txt   (default build/epub-hashes.txt)
 */
class EpubDrawStreamHashTest {

    @Test
    fun record_draw_stream_hashes() {
        val out = File(System.getProperty("kitepdf.epub.hashout") ?: "build/epub-hashes.txt")
        out.parentFile?.mkdirs()
        val corpus = ArrayList<Pair<String, ByteArray>>().apply {
            addAll(EpubCorpus.synthetic())
            val dir = Corpus.repoCorpus("epub")
            dir?.listFiles { f -> f.extension == "epub" }?.sortedBy { it.name }?.forEach {
                add(it.name to it.readBytes())
            }
        }
        val lines = ArrayList<String>()
        for ((name, bytes) in corpus) {
            val doc = runCatching { EpubDocument.open(bytes) }.getOrNull()
            if (doc == null) { lines.add("$name OPEN-FAILED"); continue }
            val md = MessageDigest.getInstance("MD5")
            var calls = 0
            for (page in doc.pages) {
                val rec = RecordingCanvas()
                runCatching { page.renderTo(rec, Matrix(1.0, 0.0, 0.0, 1.0, 0.0, 0.0)) }
                for (call in rec.calls) {
                    md.update(stable(call).encodeToByteArray())
                    calls++
                }
            }
            val hex = md.digest().joinToString("") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') }
            lines.add("$name pages=${doc.pages.size} calls=$calls md5=$hex")
        }
        out.writeText(lines.joinToString("\n") + "\n")
        println("[T-72] wrote ${lines.size} hashes to ${out.absolutePath}")
    }

    /**
     * A run-stable serialization of a call: structural fields only, no
     * identity hash codes (ImageXObject and friends are reference types).
     */
    private fun stable(c: RecordingCanvas.Call): String = when (c) {
        is RecordingCanvas.Call.BeginPage -> "BP ${c.w} ${c.h} ${c.ctm}"
        RecordingCanvas.Call.EndPage -> "EP"
        is RecordingCanvas.Call.Fill -> "F ${c.path} ${c.ctm} ${c.color} ${c.evenOdd} ${c.alpha} ${c.blendMode}"
        is RecordingCanvas.Call.Stroke ->
            "S ${c.path} ${c.ctm} ${c.color} ${c.lineWidth} ${c.alpha} ${c.blendMode} ${c.lineCap} ${c.lineJoin} ${c.miterLimit}"
        is RecordingCanvas.Call.Glyphs ->
            "G ${c.text} ${c.fontSize} ${c.unitsPerEm} ${c.hasOutlines} ${c.fontSpec} ${c.textToDevice} ${c.color} ${c.alpha} ${c.blendMode}"
        is RecordingCanvas.Call.PushClip -> "PC ${c.path} ${c.ctm} ${c.evenOdd}"
        RecordingCanvas.Call.PopClip -> "pc"
        is RecordingCanvas.Call.Image -> "I ${c.image.width}x${c.image.height} ${c.ctm} ${c.alpha}"
        is RecordingCanvas.Call.PushGroup -> "PG ${c.bbox} ${c.ctm} ${c.isolated} ${c.knockout} ${c.alpha} ${c.blendMode}"
        RecordingCanvas.Call.PopGroup -> "pg"
    }
}
