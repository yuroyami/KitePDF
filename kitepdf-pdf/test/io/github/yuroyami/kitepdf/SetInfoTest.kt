package io.github.yuroyami.kitepdf

import io.github.yuroyami.kitepdf.writer.PdfBuilder
import kotlin.test.Test
import kotlin.test.assertEquals

/** Each setInfo call changes only what it is given, an earlier call's values included (ISO 32000-1, 14.3.3; #686). */
class SetInfoTest {

    private fun built(withInfo: Boolean): ByteArray {
        val b = PdfBuilder().page(width = 100.0, height = 100.0) {}
        if (withInfo) b.setInfo(title = "Old", subject = "Kept")
        return b.build(compress = false)
    }

    @Test
    fun twoCallsKeepBothValues() {
        for (withInfo in listOf(false, true)) {
            for (incremental in listOf(true, false)) {
                val editor = PdfDocument.open(built(withInfo)).edit()
                editor.setInfo(title = "Title A")
                editor.setInfo(author = "Author B")
                val out = PdfDocument.open(if (incremental) editor.saveIncremental() else editor.saveRewritten())
                val label = "info=$withInfo incremental=$incremental"
                assertEquals("Title A", out.info.title, label)
                assertEquals("Author B", out.info.author, label)
                if (withInfo) assertEquals("Kept", out.info.subject, label)
            }
        }
    }

    @Test
    fun anEditKeepsTheFirstIdAndChangesTheSecond() {
        // ISO 32000-1, 14.4 (#738).
        val original = PdfDocument.open(built(true))
        val before = original.trailer["ID"] as io.github.yuroyami.kitepdf.core.parser.PdfArray
        for (incremental in listOf(true, false)) {
            val editor = PdfDocument.open(built(true)).edit()
            editor.setInfo(title = "New Title")
            val out = PdfDocument.open(if (incremental) editor.saveIncremental() else editor.saveRewritten())
            val after = out.trailer["ID"] as io.github.yuroyami.kitepdf.core.parser.PdfArray
            assertEquals(before[0], after[0], "the permanent string stays")
            kotlin.test.assertNotEquals(before[1], after[1], "the changing string changes")
        }
    }

    /** Two pages that inherit /MediaBox and /CropBox from their /Pages root. */
    private fun inheritedCropBox(): ByteArray {
        val sb = StringBuilder("%PDF-1.5\n")
        val offsets = IntArray(5)
        fun obj(n: Int, body: String) { offsets[n] = sb.length; sb.append("$n 0 obj\n$body\nendobj\n") }
        obj(1, "<< /Type /Catalog /Pages 2 0 R >>")
        obj(2, "<< /Type /Pages /Kids [3 0 R 4 0 R] /Count 2 /MediaBox [0 0 612 792] /CropBox [50 100 300 500] >>")
        obj(3, "<< /Type /Page /Parent 2 0 R >>")
        obj(4, "<< /Type /Page /Parent 2 0 R >>")
        val xref = sb.length
        sb.append("xref\n0 5\n0000000000 65535 f \n")
        for (n in 1..4) sb.append(offsets[n].toString().padStart(10, '0') + " 00000 n \n")
        sb.append("trailer\n<< /Size 5 /Root 1 0 R >>\nstartxref\n$xref\n%%EOF\n")
        return sb.toString().encodeToByteArray()
    }

    @Test
    fun aMovedPageKeepsTheCropBoxItInherited() {
        // ISO 32000-1, 7.7.3.4 and Table 30: /CropBox inherits like /MediaBox (#685).
        val crop = io.github.yuroyami.kitepdf.core.KiteRectangle(50.0, 100.0, 300.0, 500.0)
        assertEquals(crop, PdfDocument.open(inheritedCropBox()).pages[1].cropBox)
        val doc = PdfDocument.open(inheritedCropBox())
        val removed = doc.edit().apply { removePage(doc.pages[0]) }.saveRewritten()
        assertEquals(crop, PdfDocument.open(removed).pages[0].cropBox)
    }
}
