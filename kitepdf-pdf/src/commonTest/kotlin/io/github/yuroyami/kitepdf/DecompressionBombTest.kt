package io.github.yuroyami.kitepdf

import io.github.yuroyami.kitepdf.compression.Inflate
import io.github.yuroyami.kitepdf.compression.InflateException
import io.github.yuroyami.kitepdf.core.ByteArrayBuilder
import io.github.yuroyami.kitepdf.filters.FilterChain
import io.github.yuroyami.kitepdf.render.Matrix
import io.github.yuroyami.kitepdf.render.NoopCanvas
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Decompression-bomb guard (T-01): a kilobyte-scale FlateDecode stream that
 * expands past [FilterChain.MAX_DECODED_STREAM] must be rejected by the
 * inflater, and the rejection must surface through the lenient salvage path —
 * the document still opens and the page renders (blank), never an OOM or an
 * escaping exception.
 *
 * The bomb is hand-assembled DEFLATE: one fixed-Huffman block holding a single
 * zero literal followed by back-to-back length-258/distance-1 matches, so a
 * few megabytes of input expand to whatever size the test asks for.
 */
class DecompressionBombTest {

    /** LSB-first DEFLATE bit packer; canonical codes are written bit-reversed. */
    private class BitWriter {
        val out = ByteArrayBuilder(1 shl 20)
        private var buf = 0
        private var n = 0

        fun writeBitsLsb(value: Int, bits: Int) {
            buf = buf or (value shl n)
            n += bits
            while (n >= 8) {
                out.append((buf and 0xFF).toByte())
                buf = buf ushr 8
                n -= 8
            }
        }

        fun writeCode(code: Int, bits: Int) {
            var rev = 0
            var c = code
            repeat(bits) { rev = (rev shl 1) or (c and 1); c = c ushr 1 }
            writeBitsLsb(rev, bits)
        }

        fun finish(): ByteArray {
            if (n > 0) out.append((buf and 0xFF).toByte())
            return out.toByteArray()
        }
    }

    /**
     * Raw DEFLATE data inflating to exactly `1 + matches * 258` zero bytes.
     * Fixed-Huffman codes per RFC 1951 §3.2.6: literal 0 -> 8-bit 0x30,
     * length-258 -> symbol 285 (8-bit 0xC5, no extra), distance 1 -> symbol 0
     * (5 bits), end-of-block -> 7-bit zero code.
     */
    private fun deflateBomb(matches: Int): ByteArray {
        val w = BitWriter()
        w.writeBitsLsb(1, 1)          // BFINAL
        w.writeBitsLsb(1, 2)          // BTYPE = fixed Huffman
        w.writeCode(0x30, 8)          // literal 0x00
        repeat(matches) {
            w.writeCode(0xC5, 8)      // length 258
            w.writeCode(0, 5)         // distance 1
        }
        w.writeCode(0, 7)             // end of block
        return w.finish()
    }

    /** zlib-wrap raw DEFLATE data; Adler-32 trailer is a dummy (we never verify it here). */
    private fun zlibWrap(deflate: ByteArray): ByteArray {
        val out = ByteArrayBuilder(deflate.size + 6)
        out.append(0x78.toByte())
        out.append(0x9C.toByte())
        out.append(deflate)
        repeat(4) { out.append(0) }
        return out.toByteArray()
    }

    @Test
    fun inflate_respects_the_output_cap() {
        // Expands to 1 + 40 * 258 = 10321 bytes; cap it far below that.
        val bomb = deflateBomb(matches = 40)
        val ex = assertFailsWith<InflateException> {
            Inflate.decode(bomb, maxOutputBytes = 1_000)
        }
        assertTrue("exceeds cap" in ex.message.orEmpty(), "unexpected message: ${ex.message}")
    }

    @Test
    fun inflate_without_cap_still_works() {
        val data = deflateBomb(matches = 40)
        val decoded = Inflate.decode(data)
        assertEquals(1 + 40 * 258, decoded.size)
        assertTrue(decoded.all { it == 0.toByte() })
    }

    @Test
    fun bomb_pdf_opens_and_renders_leniently() {
        // Expands to ~537 MB, past the 512 MiB MAX_DECODED_STREAM cap, from a
        // ~3.4 MB file. Must open, must render (blank), must not throw or OOM.
        val matches = (FilterChain.MAX_DECODED_STREAM / 258) + 100_000
        val bomb = zlibWrap(deflateBomb(matches))
        val pdf = pdfWithFlateContent(bomb)

        val doc = KitePDF.open(pdf)
        assertEquals(1, doc.pageCount)
        doc.pages[0].renderTo(NoopCanvas, Matrix.IDENTITY)
        assertEquals("", doc.pages[0].extractText())
    }

    @Test
    fun bomb_in_contents_array_skips_only_the_bad_chunk() {
        val matches = (FilterChain.MAX_DECODED_STREAM / 258) + 100_000
        val bomb = zlibWrap(deflateBomb(matches))
        val pdf = pdfWithContentsArray(
            good = "BT /F1 18 Tf 100 700 Td (Alive) Tj ET".encodeToByteArray(),
            bombStream = bomb,
        )

        val doc = KitePDF.open(pdf)
        assertEquals(1, doc.pageCount)
        doc.pages[0].renderTo(NoopCanvas, Matrix.IDENTITY)
        assertTrue("Alive" in doc.pages[0].extractText())
    }

    /* ─── in-memory PDF builders ─────────────────────────────────────────── */

    private fun pdfWithFlateContent(compressed: ByteArray): ByteArray {
        val buf = ByteArrayBuilder(compressed.size + 1024)
        val offsets = mutableListOf<Int>()
        fun write(s: String) = buf.append(s.encodeToByteArray())

        write("%PDF-1.4\n")
        offsets.add(buf.size())
        write("1 0 obj\n<< /Type /Catalog /Pages 2 0 R >>\nendobj\n")
        offsets.add(buf.size())
        write("2 0 obj\n<< /Type /Pages /Kids [3 0 R] /Count 1 >>\nendobj\n")
        offsets.add(buf.size())
        write("3 0 obj\n<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] /Contents 4 0 R >>\nendobj\n")
        offsets.add(buf.size())
        write("4 0 obj\n<< /Length ${compressed.size} /Filter /FlateDecode >>\nstream\n")
        buf.append(compressed)
        write("\nendstream\nendobj\n")
        val xref = buf.size()
        write("xref\n0 5\n0000000000 65535 f \n")
        for (off in offsets) write("${off.toString().padStart(10, '0')} 00000 n \n")
        write("trailer\n<< /Size 5 /Root 1 0 R >>\nstartxref\n$xref\n%%EOF\n")
        return buf.toByteArray()
    }

    private fun pdfWithContentsArray(good: ByteArray, bombStream: ByteArray): ByteArray {
        val buf = ByteArrayBuilder(bombStream.size + 1024)
        val offsets = mutableListOf<Int>()
        fun write(s: String) = buf.append(s.encodeToByteArray())

        write("%PDF-1.4\n")
        offsets.add(buf.size())
        write("1 0 obj\n<< /Type /Catalog /Pages 2 0 R >>\nendobj\n")
        offsets.add(buf.size())
        write("2 0 obj\n<< /Type /Pages /Kids [3 0 R] /Count 1 >>\nendobj\n")
        offsets.add(buf.size())
        write(
            "3 0 obj\n<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] " +
                "/Resources << /Font << /F1 6 0 R >> >> /Contents [4 0 R 5 0 R] >>\nendobj\n",
        )
        offsets.add(buf.size())
        write("4 0 obj\n<< /Length ${bombStream.size} /Filter /FlateDecode >>\nstream\n")
        buf.append(bombStream)
        write("\nendstream\nendobj\n")
        offsets.add(buf.size())
        write("5 0 obj\n<< /Length ${good.size} >>\nstream\n")
        buf.append(good)
        write("\nendstream\nendobj\n")
        offsets.add(buf.size())
        write("6 0 obj\n<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>\nendobj\n")
        val xref = buf.size()
        write("xref\n0 7\n0000000000 65535 f \n")
        for (off in offsets) write("${off.toString().padStart(10, '0')} 00000 n \n")
        write("trailer\n<< /Size 7 /Root 1 0 R >>\nstartxref\n$xref\n%%EOF\n")
        return buf.toByteArray()
    }
}
