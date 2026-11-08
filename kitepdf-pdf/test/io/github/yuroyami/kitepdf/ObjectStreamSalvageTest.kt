package io.github.yuroyami.kitepdf

import io.github.yuroyami.kitepdf.core.KiteRawApi
import io.github.yuroyami.kitepdf.core.parser.PdfReference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.TimeSource

/**
 * An object stream and a cross-reference stream state counts that a damaged file can make up. The
 * counts are checked against the data before anything is sized by them, and one bad member of an
 * object stream costs only that member (#804, #968, #969).
 */
@OptIn(KiteRawApi::class)
class ObjectStreamSalvageTest {

    /**
     * Catalog 2, page tree 3, page 4 and a font 5 are the four members of object stream 1, whose
     * header pairs are [header] (four "number offset" pairs by default) and whose /N is [n]. A
     * cross-reference stream 6 with widths [w] lists them.
     */
    private fun pdf(
        n: String = "4",
        member5: String = "<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>",
        offset5: Int? = null,
        w: String = "[1 2 1]",
    ): ByteArray {
        val members = listOf(
            "<< /Type /Catalog /Pages 3 0 R >>",
            "<< /Type /Pages /Kids [4 0 R] /Count 1 >>",
            "<< /Type /Page /Parent 3 0 R /MediaBox [0 0 100 100] /Resources << /Font << /F1 5 0 R >> >> >>",
            member5,
        )
        var at = 0
        val offsets = members.map { m -> at.also { at += m.length + 1 } }
        val header = (0 until 4).joinToString(" ") { i -> "${i + 2} ${if (i == 3 && offset5 != null) offset5 else offsets[i]}" } + " "
        val body = header + members.joinToString("\n") + "\n"
        val sb = StringBuilder("%PDF-1.5\n")
        val o1 = sb.length
        sb.append("1 0 obj\n<< /Type /ObjStm /N $n /First ${header.length} /Length ${body.length} >>\nstream\n$body\nendstream\nendobj\n")
        val o6 = sb.length
        val rows = ByteArray(7 * 4)
        fun row(i: Int, type: Int, mid: Int, last: Int) {
            rows[i * 4] = type.toByte()
            rows[i * 4 + 1] = (mid ushr 8).toByte()
            rows[i * 4 + 2] = mid.toByte()
            rows[i * 4 + 3] = last.toByte()
        }
        row(0, 0, 0, 0xFF)
        row(1, 1, o1, 0)
        for (i in 0 until 4) row(2 + i, 2, 1, i)
        row(6, 1, o6, 0)
        sb.append("6 0 obj\n<< /Type /XRef /Size 7 /W $w /Root 2 0 R /Length ${rows.size} >>\nstream\n")
        return sb.toString().encodeToByteArray() + rows + "\nendstream\nendobj\nstartxref\n$o6\n%%EOF\n".encodeToByteArray()
    }

    @Test
    fun theControlFileOpens() {
        val doc = PdfDocument.open(pdf())
        assertEquals(1, doc.pageCount)
        assertTrue(doc.resolve(PdfReference(5, 0)) != null)
    }

    @Test
    fun oneBadMemberCostsOnlyThatMember() {
        // An offset past the end of the stream, and a body that does not parse (#804).
        for (doc in listOf(PdfDocument.open(pdf(offset5 = 100_000)), PdfDocument.open(pdf(member5 = "]")))) {
            assertEquals(1, doc.pageCount)
            assertNull(doc.resolve(PdfReference(5, 0)))
        }
    }

    @Test
    fun aHugeObjectCountIsRefusedBeforeAnythingIsSizedByIt() {
        val start = TimeSource.Monotonic.markNow()
        for (n in listOf("300000000", "2147483647", "-1")) {
            // The stream is refused, and repair still finds the objects it can.
            runCatching { PdfDocument.open(pdf(n = n)) }
        }
        assertTrue(start.elapsedNow().inWholeSeconds < 10, "took ${start.elapsedNow()}")
    }

    @Test
    fun aNegativeFieldWidthIsRefused() {
        // /W [0 0 -1] made the row size negative, and /Index [0 N] then made N entries from no data (#969).
        val start = TimeSource.Monotonic.markNow()
        val bytes = pdf(w = "[0 0 -1] /Index [0 2147483647]")
        runCatching { PdfDocument.open(bytes) }
        assertTrue(start.elapsedNow().inWholeSeconds < 10, "took ${start.elapsedNow()}")
    }
}
