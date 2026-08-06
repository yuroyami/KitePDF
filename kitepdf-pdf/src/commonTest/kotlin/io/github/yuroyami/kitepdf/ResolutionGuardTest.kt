package io.github.yuroyami.kitepdf

import io.github.yuroyami.kitepdf.core.KiteRawApi
import io.github.yuroyami.kitepdf.core.parser.PdfReference
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Regression tests for the resolution cycle/depth guards: crafted files whose
 * reference graphs would previously recurse without bound. On Kotlin/Native a
 * stack overflow is an uncatchable crash, so these guards are the only thing
 * between such a file and a dead process.
 */
@OptIn(KiteRawApi::class)
class ResolutionGuardTest {

    /**
     * Builds a PDF whose ObjStm 4 declares `/Length 5 0 R`, where object 5 is
     * itself a member of ObjStm 4. Resolving 5 hits the object-stream miss
     * path, which must decode ObjStm 4, whose /Length resolves 5 again: the
     * container claim breaks that loop, the parser falls back to the
     * endstream scan, and the resolve salvages instead of overflowing.
     */
    private fun selfReferentialObjStmPdf(): ByteArray {
        val sb = StringBuilder()
        fun obj(num: Int, body: String): Int {
            val at = sb.length
            sb.append("$num 0 obj\n$body\nendobj\n")
            return at
        }
        sb.append("%PDF-1.5\n")
        val o1 = obj(1, "<< /Type /Catalog /Pages 2 0 R >>")
        val o2 = obj(2, "<< /Type /Pages /Kids [3 0 R] /Count 1 >>")
        val o3 = obj(3, "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 100 100] >>")
        // ObjStm body: header "5 0 " (objNum 5 at offset 0), then the object.
        val stmBody = "5 0 <<>>"
        val o4 = sb.length
        sb.append("4 0 obj\n<< /Type /ObjStm /N 1 /First 4 /Length 5 0 R >>\nstream\n")
        sb.append(stmBody)
        sb.append("\nendstream\nendobj\n")

        // Uncompressed cross-reference stream, /W [1 2 1], objects 0..6.
        val o6 = sb.length
        val rows = ByteArray(7 * 4)
        fun row(i: Int, type: Int, mid: Int, last: Int) {
            rows[i * 4] = type.toByte()
            rows[i * 4 + 1] = ((mid ushr 8) and 0xFF).toByte()
            rows[i * 4 + 2] = (mid and 0xFF).toByte()
            rows[i * 4 + 3] = (last and 0xFF).toByte()
        }
        row(0, 0, 0, 0xFF)
        row(1, 1, o1, 0)
        row(2, 1, o2, 0)
        row(3, 1, o3, 0)
        row(4, 1, o4, 0)
        row(5, 2, 4, 0) // compressed: in ObjStm 4, index 0
        row(6, 1, o6, 0)
        sb.append("6 0 obj\n<< /Type /XRef /Size 7 /W [1 2 1] /Root 1 0 R /Length ${rows.size} >>\nstream\n")
        val head = sb.toString().encodeToByteArray()
        val tail = "\nendstream\nendobj\nstartxref\n$o6\n%%EOF\n".encodeToByteArray()
        return head + rows + tail
    }

    @Test fun self_referential_objstm_length_terminates() {
        val doc = PdfDocument.open(selfReferentialObjStmPdf())
        assertEquals(1, doc.pageCount)
        // Must terminate without a stack overflow. The salvaged value may be
        // the member (endstream-scan fallback) or null (cached failure); both
        // are acceptable, the crash is not.
        doc.resolve(PdfReference(5, 0))
        doc.resolve(PdfReference(5, 0)) // second call: cached path also sane
    }

    /**
     * A deep (non-cyclic) /Length indirection chain: object N's /Length points
     * at N+1, thousands of levels. The depth cap must cut it off with a
     * salvage instead of unbounded recursion.
     */
    private fun deepLengthChainPdf(depth: Int): ByteArray {
        val sb = StringBuilder()
        val offsets = IntArray(depth + 4)
        fun obj(num: Int, body: String) {
            offsets[num] = sb.length
            sb.append("$num 0 obj\n$body\nendobj\n")
        }
        sb.append("%PDF-1.4\n")
        obj(1, "<< /Type /Catalog /Pages 2 0 R >>")
        obj(2, "<< /Type /Pages /Kids [3 0 R] /Count 1 >>")
        obj(3, "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 100 100] >>")
        for (i in 4 until 4 + depth) {
            val next = if (i == 3 + depth) "0" else "/Length ${i + 1} 0 R"
            offsets[i] = sb.length
            sb.append("$i 0 obj\n<< $next >>\nstream\nx\nendstream\nendobj\n")
        }
        val xrefAt = sb.length
        sb.append("xref\n0 ${4 + depth}\n")
        sb.append("0000000000 65535 f \n")
        for (i in 1 until 4 + depth) {
            sb.append(offsets[i].toString().padStart(10, '0')).append(" 00000 n \n")
        }
        sb.append("trailer\n<< /Size ${4 + depth} /Root 1 0 R >>\nstartxref\n$xrefAt\n%%EOF\n")
        return sb.toString().encodeToByteArray()
    }

    @Test fun deep_length_chain_hits_depth_cap_not_stack() {
        val doc = PdfDocument.open(deepLengthChainPdf(depth = 2000))
        assertEquals(1, doc.pageCount)
        doc.resolve(PdfReference(4, 0)) // must terminate, salvaged or not
    }
}
