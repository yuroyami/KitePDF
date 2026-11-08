package io.github.yuroyami.kitepdf

import io.github.yuroyami.kitepdf.core.KiteWarning
import io.github.yuroyami.kitepdf.core.KiteWarningListener
import io.github.yuroyami.kitepdf.core.KiteWarnings
import io.github.yuroyami.kitepdf.core.render.KiteMatrix
import io.github.yuroyami.kitepdf.core.render.KiteRasterCanvas
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** A document's salvage warnings go to its own listener, with the page they came from (#899). */
class DocumentWarningsTest {

    /** Two pages; the second draws an image whose Flate data is broken. */
    private fun pdf(): ByteArray = TestPdf.build(listOf(
        "<< /Type /Catalog /Pages 2 0 R >>",
        "<< /Type /Pages /Kids [3 0 R 4 0 R] /Count 2 >>",
        "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 100 100] /Contents 5 0 R >>",
        "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 100 100] /Resources << /XObject << /Im 7 0 R >> >> /Contents 6 0 R >>",
        TestPdf.stream("0 0 1 rg 0 0 10 10 re f"),
        TestPdf.stream("q 100 0 0 100 0 0 cm /Im Do Q"),
        TestPdf.stream("this is not deflate data at all", "/Type /XObject /Subtype /Image /Width 4 /Height 4 /ColorSpace /DeviceGray /BitsPerComponent 8 /Filter /FlateDecode"),
    ))

    private class Collect : KiteWarningListener {
        val warnings = ArrayList<KiteWarning>()
        override fun warn(warning: KiteWarning) { warnings += warning }
    }

    private fun draw(doc: PdfDocument, page: Int) = doc.pages[page].renderTo(KiteRasterCanvas(100, 100), KiteMatrix.IDENTITY)

    @Test
    fun each_document_tells_its_own_listener_and_the_page() {
        val a = Collect(); val b = Collect()
        val first = PdfDocument.open(pdf()).also { it.warnings = a }
        val second = PdfDocument.open(pdf(), "", b)
        draw(first, 0); draw(second, 0)
        assertEquals(0, a.warnings.size + b.warnings.size, "page 1 is sound")
        draw(first, 1)
        assertTrue(a.warnings.isNotEmpty() && b.warnings.isEmpty(), "first: ${a.warnings}, second: ${b.warnings}")
        assertTrue(a.warnings.all { it.pageIndex == 1 && it.document === first }, "${a.warnings}")
        val before = a.warnings.size
        draw(second, 1)
        assertEquals(before, a.warnings.size, "the second document's warning stays with it")
        assertTrue(b.warnings.isNotEmpty() && b.warnings.all { it.pageIndex == 1 && it.document === second }, "${b.warnings}")
    }

    @Test
    fun a_document_without_a_listener_still_reports_to_the_global_sink() {
        val seen = ArrayList<String>()
        val saved = KiteWarnings.sink
        KiteWarnings.sink = io.github.yuroyami.kitepdf.core.KiteWarningSink { seen += it }
        try {
            draw(PdfDocument.open(pdf()), 1)
        } finally {
            KiteWarnings.sink = saved
        }
        assertTrue(seen.isNotEmpty())
    }
}
