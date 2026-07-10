package io.github.yuroyami.kitepdf

import io.github.yuroyami.kitepdf.compression.Deflate
import io.github.yuroyami.kitepdf.compression.Zlib
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * T-11 acceptance on real data: the pure-Kotlin dynamic-Huffman encoder's
 * ratio on an actual corpus content stream, and `mutool` accepting a PDF
 * whose content stream was compressed by it (bypassing the T-10 platform
 * fast path, which would otherwise hide the pure encoder on the JVM).
 * Corpus/mutool-dependent parts skip silently when absent.
 */
class DeflateWriterOracleTest {

    private fun corpusPdf(name: String): File? {
        var d: File? = File(System.getProperty("user.dir")).absoluteFile
        while (d != null && !File(d, "settings.gradle.kts").exists()) d = d.parentFile
        return d?.let { File(it, "corpus/pdf/$name") }?.takeIf { it.exists() }
    }

    private fun mutool(): String? =
        listOf("/opt/homebrew/bin/mutool", "/usr/local/bin/mutool", "mutool")
            .firstOrNull { runCatching { ProcessBuilder(it, "-v").start().waitFor() }.getOrNull() == 0 }

    /** Manual zlib wrapper over the PURE encoder (Zlib.encode fast-paths on JVM). */
    private fun pureZlib(data: ByteArray): ByteArray {
        val body = Deflate.encode(data)
        val adler = Zlib.adler32(data)
        return byteArrayOf(0x78, 0x9C.toByte()) + body + byteArrayOf(
            ((adler ushr 24) and 0xFF).toByte(),
            ((adler ushr 16) and 0xFF).toByte(),
            ((adler ushr 8) and 0xFF).toByte(),
            (adler and 0xFF).toByte(),
        )
    }

    @Test
    fun real_content_stream_ratio_within_budget() {
        val file = corpusPdf("GoldenHour-byIOS.pdf") ?: return
        val doc = KitePDF.open(file.readBytes())
        val content = doc.pages[0].contentBytes
        assertTrue(content.size > 1000, "page 0 has a real content stream (${content.size} B)")

        val ours = Deflate.encode(content)
        val jdk = run {
            val d = java.util.zip.Deflater(6, true)
            d.setInput(content)
            d.finish()
            val buf = ByteArray(content.size + 1024)
            var n = 0
            while (!d.finished()) n += d.deflate(buf, n, buf.size - n)
            d.end()
            buf.copyOf(n)
        }
        val ratio = ours.size.toDouble() / jdk.size
        println("[T-11 bench] GoldenHour p0 content (${content.size} B): ours=${ours.size} zlib6=${jdk.size} ratio=${(ratio * 1000).toInt() / 1000.0}")
        assertTrue(ratio <= 1.25, "pure encoder is ${ratio}x of zlib level 6 on a real stream (budget 1.25x)")
    }

    @Test
    fun mutool_accepts_a_pdf_with_a_pure_encoded_stream() {
        val mutool = mutool() ?: return
        val content = "1 0 0 RG 0.9 0.2 0.1 rg 72 72 468 648 re f BT /F1 24 Tf 100 400 Td (pure dynamic huffman) Tj ET\n"
            .repeat(50).encodeToByteArray()
        val stream = pureZlib(content)

        val sb = StringBuilder()
        val offsets = ArrayList<Int>()
        val head = StringBuilder("%PDF-1.4\n")
        fun add(s: String) {
            offsets.add(head.length + sb.length)
            sb.append(s)
        }
        add("1 0 obj\n<< /Type /Catalog /Pages 2 0 R >>\nendobj\n")
        add("2 0 obj\n<< /Type /Pages /Kids [3 0 R] /Count 1 >>\nendobj\n")
        add(
            "3 0 obj\n<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] " +
                "/Resources << /Font << /F1 4 0 R >> >> /Contents 5 0 R >>\nendobj\n",
        )
        add("4 0 obj\n<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>\nendobj\n")
        val streamHeader = "5 0 obj\n<< /Length ${stream.size} /Filter /FlateDecode >>\nstream\n"
        offsets.add(head.length + sb.length)
        val pre = (head.toString() + sb.toString() + streamHeader).encodeToByteArray()
        val post = StringBuilder("\nendstream\nendobj\n")
        val xref = pre.size + stream.size + post.length
        post.append("xref\n0 6\n0000000000 65535 f \n")
        for (o in offsets) post.append("${o.toString().padStart(10, '0')} 00000 n \n")
        post.append("trailer\n<< /Size 6 /Root 1 0 R >>\nstartxref\n$xref\n%%EOF\n")
        val pdf = pre + stream + post.toString().encodeToByteArray()

        // Sanity: our own reader inflates it back byte-identically.
        val doc = KitePDF.open(pdf)
        assertEquals(content.decodeToString(), doc.pages[0].contentBytes.decodeToString())

        val tmp = File.createTempFile("kitepdf-t11", ".pdf").apply {
            deleteOnExit()
            writeBytes(pdf)
        }
        val png = File.createTempFile("kitepdf-t11", ".png").apply { deleteOnExit() }
        val p = ProcessBuilder(mutool, "draw", "-o", png.absolutePath, tmp.absolutePath)
            .redirectErrorStream(true)
            .start()
        val outText = p.inputStream.readBytes().decodeToString()
        assertEquals(0, p.waitFor(), "mutool draw accepts the pure-encoded PDF: $outText")
    }
}
