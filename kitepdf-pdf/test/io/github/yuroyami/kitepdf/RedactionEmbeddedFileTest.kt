package io.github.yuroyami.kitepdf

import io.github.yuroyami.kitepdf.core.KiteRectangle
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** A redacted file attachment's file leaves the catalog's /EmbeddedFiles tree too (ISO 32000-1, 7.7.4; #632). */
class RedactionEmbeddedFileTest {

    private fun pdf(names: String) = TestPdf.build(
        listOf(
            "<< /Type /Catalog /Pages 2 0 R /Names << /EmbeddedFiles $names >> >>",
            "<< /Type /Pages /Kids [3 0 R] /Count 1 >>",
            "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 300 300] /Contents 4 0 R /Annots [5 0 R] >>",
            TestPdf.stream("q Q"),
            "<< /Type /Annot /Subtype /FileAttachment /Rect [100 100 120 120] /FS 6 0 R >>",
            "<< /Type /Filespec /F (secret.txt) /UF (secret.txt) /EF << /F 7 0 R >> >>",
            TestPdf.stream("TOP SECRET DATA", "/Type /EmbeddedFile"),
            TestPdf.stream("PUBLIC DATA", "/Type /EmbeddedFile"),
            "<< /Type /Filespec /F (public.txt) /EF << /F 8 0 R >> >>",
            "<< /Names [(public.txt) 9 0 R (secret.txt) 6 0 R] >>",
        ),
    )

    private fun redacted(names: String): ByteArray {
        val doc = PdfDocument.open(pdf(names))
        return doc.edit().apply { redactRegion(doc.pages[0], KiteRectangle(90.0, 90.0, 130.0, 130.0)) }.saveRewritten()
    }

    @Test
    fun the_file_goes_and_the_other_attachments_stay() {
        for (tree in listOf("<< /Names [(public.txt) 9 0 R (secret.txt) 6 0 R] >>", "<< /Kids [10 0 R] >>")) {
            val out = redacted(tree)
            assertTrue("TOP SECRET DATA" !in out.decodeToString(), "the file's bytes are gone with $tree")
            assertEquals(listOf("public.txt"), PdfDocument.open(out).attachments.map { it.name }, tree)
        }
    }
}
