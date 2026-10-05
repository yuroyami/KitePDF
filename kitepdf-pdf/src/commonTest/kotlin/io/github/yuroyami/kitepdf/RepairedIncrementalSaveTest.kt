package io.github.yuroyami.kitepdf

import io.github.yuroyami.kitepdf.core.ByteReader
import io.github.yuroyami.kitepdf.core.KiteRawApi
import io.github.yuroyami.kitepdf.core.render.KiteMatrix
import io.github.yuroyami.kitepdf.core.render.RecordingCanvas
import io.github.yuroyami.kitepdf.parser.XrefEntry
import io.github.yuroyami.kitepdf.parser.XrefParser
import io.github.yuroyami.kitepdf.writer.PdfBuilder
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * An incremental save of a file that opened through repair stands on its own: the table it
 * appends lists every object and names no older table, because the table the file had is
 * the broken part (#586).
 */
@OptIn(KiteRawApi::class)
class RepairedIncrementalSaveTest {

    /** [pdf] with junk after its header, so every offset its table gives is ten bytes short. */
    private fun shifted(pdf: ByteArray): ByteArray =
        pdf.copyOfRange(0, 9) + "% junk  \n".encodeToByteArray() + pdf.copyOfRange(9, pdf.size)

    /** [pdf] cut before its last `startxref`, so no table can be found. */
    private fun truncated(pdf: ByteArray): ByteArray {
        val key = "startxref".encodeToByteArray()
        val at = (pdf.size - key.size downTo 0).first { i -> key.indices.all { pdf[i + it] == key[it] } }
        return pdf.copyOfRange(0, at)
    }

    private fun fills(doc: PdfDocument): Int {
        val canvas = RecordingCanvas()
        doc.pages[0].renderTo(canvas, KiteMatrix.IDENTITY)
        return canvas.calls.filterIsInstance<RecordingCanvas.Call.Fill>().size
    }

    /** Rotates the first page of the repaired [damaged] file, saves it, and checks the result. */
    private fun assertSavedAlone(damaged: ByteArray, what: String, password: String = "") {
        val doc = PdfDocument.open(damaged, password)
        val saved = doc.edit(Random(1)).apply { rotatePage(doc.pages[0], 90) }.saveIncremental()

        val table = XrefParser(ByteReader(saved)).parse()
        assertNull(table.trailer["Prev"], what)
        val listed = table.entries.values.filterIsInstance<XrefEntry.InUse>()
        assertEquals(
            doc.xref.values.filter { it !is XrefEntry.Free }.map { it.objectNumber }.toSet(),
            listed.map { it.objectNumber }.toSet(),
            what,
        )
        for (entry in listed) {
            val header = "${entry.objectNumber} ${entry.generation} obj"
            assertEquals(header, saved.copyOfRange(entry.byteOffset, entry.byteOffset + header.length).decodeToString(), what)
        }

        val reopened = PdfDocument.open(saved, password)
        assertEquals(90, reopened.pages[0].rotation, what)
        assertEquals(fills(doc), fills(reopened), what)
    }

    @Test
    fun a_file_whose_table_is_off_or_gone_saves_a_table_of_its_own() {
        val plain = TestPdf.onePage("0 0 1 rg 0 0 10 10 re f")
        assertSavedAlone(shifted(plain), "offsets off")
        assertSavedAlone(truncated(plain), "no startxref")
    }

    @Test
    fun objects_inside_an_object_stream_are_written_out() {
        val packed = PdfDocument.open(TestPdf.onePage("0 0 1 rg 0 0 10 10 re f")).edit().saveRewritten(useObjectStreams = true)
        assertTrue(PdfDocument.open(shifted(packed)).xref.values.any { it is XrefEntry.Compressed })
        assertSavedAlone(shifted(packed), "offsets off")
        assertSavedAlone(truncated(packed), "no startxref")
    }

    @Test
    fun an_encrypted_file_stays_encrypted() {
        val locked = PdfBuilder().encrypt("pw", random = Random(7)).page { }.build()
        assertSavedAlone(shifted(locked), "offsets off", password = "pw")
        assertSavedAlone(truncated(locked), "no startxref", password = "pw")
    }
}
