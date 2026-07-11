package io.github.yuroyami.kitepdf.nativerenderer.difftest

import io.github.yuroyami.kitepdf.KitePDF
import io.github.yuroyami.kitepdf.Rectangle
import io.github.yuroyami.kitepdf.parser.PdfReference
import io.github.yuroyami.kitepdf.writer.PdfBuilder
import io.github.yuroyami.kitepdf.writer.PdfStreams
import io.github.yuroyami.kitepdf.writer.StandardFont
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.TimeUnit
import org.junit.Assume.assumeTrue
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Validates the incremental-update writer against the MuPDF oracle: an external,
 * independent reader must accept what KitePDF writes.
 *
 * KitePDF's own reader following its own writer's output can hide a shared bug;
 * `mutool` cannot. We edit a PDF, save incrementally, and require that mutool
 * (a) renders the page — which exercises the whole resolve chain (xref → /Prev →
 * Root → Pages → Page → Contents/Font) through the appended section — and
 * (b) reports our appended trailer (with /Prev and the edited /Info).
 *
 * Skips cleanly when no mutool is on the system (mirrors the differential harness).
 */
class WriterOracleTest {

    @Test
    fun mutool_accepts_incrementally_edited_pdf() {
        assumeTrue("mutool not found — skipping oracle validation.", MuPdfOracle.binary != null)
        val tool = MuPdfOracle.binary!!

        // Build → open → edit (/Info title + a new object) → save incrementally.
        val original = buildMinimalPdf()
        val doc = KitePDF.open(original)
        @OptIn(io.github.yuroyami.kitepdf.core.KiteRawApi::class)
        val edited = doc.edit().apply {
            setInfo(title = "KiteEditedMarker", author = "KitePDF Writer")
            addObject(
                io.github.yuroyami.kitepdf.parser.PdfDictionary(
                    linkedMapOf(
                        "Type" to io.github.yuroyami.kitepdf.parser.PdfName("KiteMarker"),
                        "Value" to io.github.yuroyami.kitepdf.parser.PdfInt(7),
                    ),
                ),
            )
        }.saveIncremental()

        // The original must remain a verbatim prefix (the invariant signing relies on).
        assertTrue(original.contentEquals(edited.copyOf(original.size)), "original bytes not preserved")

        val pdf = File.createTempFile("kite-edited-", ".pdf").apply { writeBytes(edited) }
        val png = File.createTempFile("kite-edited-", ".png")
        try {
            // (a) Render the page. A broken xref/Prev/offsets would fail this.
            val draw = runMutool(
                tool, "draw", "-r", "72", "-F", "png", "-o", png.absolutePath,
                pdf.absolutePath, "1",
            )
            assertEquals(0, draw.exitCode, "mutool draw failed:\n${draw.output}")
            assertTrue(png.exists() && png.length() > 0L, "mutool produced no PNG output")

            // (b) Dump the active trailer — must be OUR appended one.
            val trailer = runMutool(tool, "show", pdf.absolutePath, "trailer")
            assertEquals(0, trailer.exitCode, "mutool show trailer failed:\n${trailer.output}")
            assertTrue(
                trailer.output.contains("/Prev"),
                "mutool didn't see the appended incremental trailer:\n${trailer.output}",
            )

            // (c) Confirm mutool resolved the edited /Info title.
            val info = runMutool(tool, "info", pdf.absolutePath)
            assertEquals(0, info.exitCode, "mutool info failed:\n${info.output}")
            assertTrue(
                info.output.contains("KiteEditedMarker"),
                "mutool didn't resolve the edited /Info title:\n${info.output}",
            )
        } finally {
            pdf.delete()
            png.delete()
        }
    }

    @Test
    fun mutool_decodes_our_flate_compressed_content_stream() {
        assumeTrue("mutool not found — skipping flate oracle validation.", MuPdfOracle.binary != null)
        val tool = MuPdfOracle.binary!!

        // A content stream that draws a unique marker, padded with identical
        // lines so it compresses substantially (and exercises LZ77 matching).
        val content = buildString {
            append("BT /F1 18 Tf 72 720 Td (Flate Works 123) Tj ET\n")
            repeat(500) { append("BT /F1 8 Tf 72 700 Td (padding) Tj ET\n") }
        }.toByteArray(Charsets.ISO_8859_1)

        val flateStream = PdfStreams.flate(content)
        assertTrue(
            flateStream.rawBytes.size < content.size / 4,
            "flate didn't compress: ${flateStream.rawBytes.size} vs ${content.size}",
        )

        val original = buildMinimalPdf()
        val doc = KitePDF.open(original)
        // Replace the page's content stream (5 0 R) with the compressed one.
        @OptIn(io.github.yuroyami.kitepdf.core.KiteRawApi::class)
        val edited = doc.edit().apply { updateObject(PdfReference(5, 0), flateStream) }.saveIncremental()

        // (0) Our own reader must decode our flate through the real filter chain.
        val viaKite = KitePDF.open(edited)
        assertContains(viaKite.pages[0].extractText(), "Flate Works 123")

        val pdf = File.createTempFile("kite-flate-", ".pdf").apply { writeBytes(edited) }
        val png = File.createTempFile("kite-flate-", ".png")
        val cleaned = File.createTempFile("kite-flate-clean-", ".pdf")
        try {
            // (a) mutool renders the page — it must decode our FlateDecode stream.
            val draw = runMutool(
                tool, "draw", "-r", "72", "-F", "png", "-o", png.absolutePath,
                pdf.absolutePath, "1",
            )
            assertEquals(0, draw.exitCode, "mutool draw failed:\n${draw.output}")
            assertTrue(png.exists() && png.length() > 0L, "mutool produced no PNG")

            // (b) mutool clean -d decompresses all streams; the marker text must
            // appear verbatim in the cleaned (now-uncompressed) output.
            val clean = runMutool(tool, "clean", "-d", pdf.absolutePath, cleaned.absolutePath)
            assertEquals(0, clean.exitCode, "mutool clean failed:\n${clean.output}")
            val cleanedText = cleaned.readBytes().toString(Charsets.ISO_8859_1)
            assertTrue(
                cleanedText.contains("Flate Works 123"),
                "mutool didn't decompress our flate stream to the expected content",
            )
        } finally {
            pdf.delete()
            png.delete()
            cleaned.delete()
        }
    }

    @Test
    fun mutool_accepts_from_scratch_pdfbuilder_document() {
        assumeTrue("mutool not found — skipping PdfBuilder oracle validation.", MuPdfOracle.binary != null)
        val tool = MuPdfOracle.binary!!

        val bytes = PdfBuilder()
            .setInfo(title = "Built From Scratch 42", author = "KitePDF Writer")
            .page { text(StandardFont.Helvetica, 24.0, 72.0, 700.0, "Generated by KitePDF") }
            .page {
                text(StandardFont.TimesBold, 18.0, 72.0, 700.0, "Second page")
                setFillRgb(0.1, 0.5, 0.9)
                rectangle(72.0, 500.0, 200.0, 100.0)
                fill()
            }
            .build()

        // Our own reader sees both pages and their text.
        val doc = KitePDF.open(bytes)
        assertEquals(2, doc.pageCount)
        assertContains(doc.pages[0].extractText(), "Generated by KitePDF")

        val pdf = File.createTempFile("kite-scratch-", ".pdf").apply { writeBytes(bytes) }
        val png1 = File.createTempFile("kite-scratch-1-", ".png")
        val png2 = File.createTempFile("kite-scratch-2-", ".png")
        try {
            // mutool must render BOTH pages (proves a fully valid from-scratch file:
            // header, xref, catalog, page tree, fonts, and flate content streams).
            for ((page, png) in listOf(1 to png1, 2 to png2)) {
                val draw = runMutool(
                    tool, "draw", "-r", "72", "-F", "png", "-o", png.absolutePath,
                    pdf.absolutePath, page.toString(),
                )
                assertEquals(0, draw.exitCode, "mutool draw page $page failed:\n${draw.output}")
                assertTrue(png.exists() && png.length() > 0L, "mutool produced no PNG for page $page")
            }

            // mutool resolves our /Info metadata.
            val info = runMutool(tool, "info", pdf.absolutePath)
            assertEquals(0, info.exitCode, "mutool info failed:\n${info.output}")
            assertTrue(
                info.output.contains("Built From Scratch 42"),
                "mutool didn't resolve the from-scratch /Info title:\n${info.output}",
            )
        } finally {
            pdf.delete()
            png1.delete()
            png2.delete()
        }
    }

    @Test
    fun mutool_renders_stamped_page_with_merged_resources() {
        assumeTrue("mutool not found — skipping stamp oracle validation.", MuPdfOracle.binary != null)
        val tool = MuPdfOracle.binary!!

        // Create a base doc, then overlay a stamp that uses a NEW font (Courier),
        // forcing a /Resources merge on top of the page's Helvetica.
        val base = PdfBuilder()
            .page { text(StandardFont.Helvetica, 18.0, 72.0, 700.0, "Base body content") }
            .build()
        val doc = KitePDF.open(base)
        val stamped = doc.edit().apply {
            stampPage(doc.pages[0]) {
                setFillRgb(1.0, 0.0, 0.0)
                text(StandardFont.CourierBold, 36.0, 150.0, 400.0, "CONFIDENTIAL")
            }
        }.saveIncremental()

        // Our reader sees both the original and the stamp.
        val reopened = KitePDF.open(stamped)
        assertContains(reopened.pages[0].extractText(), "Base body content")
        assertContains(reopened.pages[0].extractText(), "CONFIDENTIAL")

        val pdf = File.createTempFile("kite-stamp-", ".pdf").apply { writeBytes(stamped) }
        val png = File.createTempFile("kite-stamp-", ".png")
        val cleaned = File.createTempFile("kite-stamp-clean-", ".pdf")
        try {
            val draw = runMutool(
                tool, "draw", "-r", "72", "-F", "png", "-o", png.absolutePath, pdf.absolutePath, "1",
            )
            assertEquals(0, draw.exitCode, "mutool draw failed:\n${draw.output}")
            assertTrue(png.exists() && png.length() > 0L, "mutool produced no PNG")

            // The decompressed content must contain BOTH the original and stamp text.
            val clean = runMutool(tool, "clean", "-d", pdf.absolutePath, cleaned.absolutePath)
            assertEquals(0, clean.exitCode, "mutool clean failed:\n${clean.output}")
            val cleanedText = cleaned.readBytes().toString(Charsets.ISO_8859_1)
            assertTrue(cleanedText.contains("Base body content"), "original content missing after clean")
            assertTrue(cleanedText.contains("CONFIDENTIAL"), "stamp content missing after clean")
        } finally {
            pdf.delete()
            png.delete()
            cleaned.delete()
        }
    }

    @Test
    fun mutool_renders_filled_form_field_appearance() {
        assumeTrue("mutool not found — skipping form-fill oracle validation.", MuPdfOracle.binary != null)
        val tool = MuPdfOracle.binary!!

        val original = buildFormPdf()
        val doc = KitePDF.open(original)
        val filled = doc.edit().apply {
            setTextFieldValue(doc.formField("FullName")!!, "Filled By KitePDF")
        }.saveIncremental()

        val reopened = KitePDF.open(filled)
        assertEquals("Filled By KitePDF", reopened.formField("FullName")?.value)

        val pdf = File.createTempFile("kite-form-", ".pdf").apply { writeBytes(filled) }
        val png = File.createTempFile("kite-form-", ".png")
        val cleaned = File.createTempFile("kite-form-clean-", ".pdf")
        try {
            val draw = runMutool(
                tool, "draw", "-r", "72", "-F", "png", "-o", png.absolutePath, pdf.absolutePath, "1",
            )
            assertEquals(0, draw.exitCode, "mutool draw failed:\n${draw.output}")
            assertTrue(png.exists() && png.length() > 0L, "mutool produced no PNG")

            // The generated appearance stream must decompress to the field value.
            val clean = runMutool(tool, "clean", "-d", pdf.absolutePath, cleaned.absolutePath)
            assertEquals(0, clean.exitCode, "mutool clean failed:\n${clean.output}")
            assertTrue(
                cleaned.readBytes().toString(Charsets.ISO_8859_1).contains("Filled By KitePDF"),
                "mutool didn't find the value in the generated appearance",
            )
        } finally {
            pdf.delete()
            png.delete()
            cleaned.delete()
        }
    }

    @Test
    fun mutool_accepts_redacted_document_and_secret_is_gone() {
        assumeTrue("mutool not found — skipping redaction oracle validation.", MuPdfOracle.binary != null)
        val tool = MuPdfOracle.binary!!

        // Uncompressed so a redaction leak would show as plaintext to mutool.
        val base = PdfBuilder()
            .page {
                text(StandardFont.Helvetica, 24.0, 72.0, 700.0, "TOPSECRET-42")
                text(StandardFont.Helvetica, 12.0, 72.0, 100.0, "visible footer")
            }
            .build(compress = false)

        val doc = KitePDF.open(base)
        val redacted = doc.edit().apply {
            redactRegion(doc.pages[0], Rectangle(60.0, 690.0, 470.0, 726.0))
        }.saveRewritten()

        // Our reader: secret gone, footer kept.
        val reopened = KitePDF.open(redacted)
        assertFalse(reopened.pages[0].extractText().contains("TOPSECRET"), "secret survived in our reader")
        assertContains(reopened.pages[0].extractText(), "visible footer")

        val pdf = File.createTempFile("kite-redact-", ".pdf").apply { writeBytes(redacted) }
        val png = File.createTempFile("kite-redact-", ".png")
        val cleaned = File.createTempFile("kite-redact-clean-", ".pdf")
        try {
            val draw = runMutool(
                tool, "draw", "-r", "72", "-F", "png", "-o", png.absolutePath, pdf.absolutePath, "1",
            )
            assertEquals(0, draw.exitCode, "mutool draw failed:\n${draw.output}")
            assertTrue(png.exists() && png.length() > 0L, "mutool produced no PNG")

            // Decompress all streams; the secret must not appear anywhere, the footer must.
            val clean = runMutool(tool, "clean", "-d", pdf.absolutePath, cleaned.absolutePath)
            assertEquals(0, clean.exitCode, "mutool clean failed:\n${clean.output}")
            val cleanedText = cleaned.readBytes().toString(Charsets.ISO_8859_1)
            assertFalse(cleanedText.contains("TOPSECRET"), "mutool recovered the redacted text")
            assertTrue(cleanedText.contains("visible footer"), "surviving text lost")
        } finally {
            pdf.delete()
            png.delete()
            cleaned.delete()
        }
    }

    /** Minimal one-text-field AcroForm (merged field+widget "FullName"). */
    private fun buildFormPdf(): ByteArray {
        val buf = ByteArrayOutputStream()
        val offsets = mutableListOf<Int>()
        fun write(s: String) = buf.write(s.toByteArray(Charsets.ISO_8859_1))

        write("%PDF-1.7\n%âãÏÓ\n")
        offsets.add(buf.size())
        write("1 0 obj\n<< /Type /Catalog /Pages 2 0 R /AcroForm 6 0 R >>\nendobj\n")
        offsets.add(buf.size())
        write("2 0 obj\n<< /Type /Pages /Kids [3 0 R] /Count 1 >>\nendobj\n")
        offsets.add(buf.size())
        write("3 0 obj\n<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] /Annots [5 0 R] /Resources << >> >>\nendobj\n")
        offsets.add(buf.size())
        write("4 0 obj\n<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>\nendobj\n")
        offsets.add(buf.size())
        write("5 0 obj\n<< /Type /Annot /Subtype /Widget /FT /Tx /T (FullName) /Rect [100 700 400 720] /DA (/Helv 12 Tf 0 g) /P 3 0 R /F 4 >>\nendobj\n")
        offsets.add(buf.size())
        write("6 0 obj\n<< /Fields [5 0 R] /DA (/Helv 12 Tf 0 g) /DR << /Font << /Helv 4 0 R >> >> >>\nendobj\n")

        val size = offsets.size + 1
        val xref = buf.size()
        write("xref\n0 $size\n0000000000 65535 f \n")
        for (off in offsets) write("${off.toString().padStart(10, '0')} 00000 n \n")
        write("trailer\n<< /Size $size /Root 1 0 R >>\nstartxref\n$xref\n%%EOF\n")
        return buf.toByteArray()
    }

    @Test
    fun mutool_accepts_object_stream_and_xref_stream_output() {
        assumeTrue("mutool not found — skipping object-stream oracle validation.", MuPdfOracle.binary != null)
        val tool = MuPdfOracle.binary!!

        // From-scratch doc → rewrite with object streams + a cross-reference stream.
        val src = PdfBuilder()
            .setInfo(title = "ObjStm Oracle 99", author = "KitePDF Writer")
            .page { text(StandardFont.Helvetica, 20.0, 72.0, 700.0, "Compressed by KitePDF") }
            .page { text(StandardFont.TimesRoman, 16.0, 72.0, 700.0, "Second compact page") }
            .build()
        val compact = KitePDF.open(src).edit().saveRewritten(useObjectStreams = true)

        // Our reader round-trips the compact form.
        val viaKite = KitePDF.open(compact)
        assertEquals(2, viaKite.pageCount)
        assertContains(viaKite.pages[0].extractText(), "Compressed by KitePDF")

        val pdf = File.createTempFile("kite-objstm-", ".pdf").apply { writeBytes(compact) }
        val png = File.createTempFile("kite-objstm-", ".png")
        try {
            // mutool must render the page — proving it walks our /XRef stream and
            // resolves objects out of our /ObjStm.
            val draw = runMutool(
                tool, "draw", "-r", "72", "-F", "png", "-o", png.absolutePath, pdf.absolutePath, "1",
            )
            assertEquals(0, draw.exitCode, "mutool draw failed on object-stream output:\n${draw.output}")
            assertTrue(png.exists() && png.length() > 0L, "mutool produced no PNG")

            // mutool resolves /Info — which lives inside the object stream.
            val info = runMutool(tool, "info", pdf.absolutePath)
            assertEquals(0, info.exitCode, "mutool info failed:\n${info.output}")
            assertTrue(
                info.output.contains("ObjStm Oracle 99"),
                "mutool didn't resolve /Info from the object stream:\n${info.output}",
            )
        } finally {
            pdf.delete()
            png.delete()
        }
    }

    @Test
    fun mutool_renders_merged_and_grafted_document() {
        assumeTrue("mutool not found — skipping page-ops oracle validation.", MuPdfOracle.binary != null)
        val tool = MuPdfOracle.binary!!

        val docA = PdfBuilder()
            .page { text(StandardFont.Helvetica, 20.0, 72.0, 700.0, "Merge Source A page 1") }
            .page { text(StandardFont.Helvetica, 20.0, 72.0, 700.0, "Merge Source A page 2") }
            .build()
        val docB = PdfBuilder()
            .page { text(StandardFont.TimesRoman, 20.0, 72.0, 700.0, "Merge Source B page 1") }
            .build()

        // Merge B into A → 3 pages, then write a compact (object-stream) file.
        val a = KitePDF.open(docA)
        val merged = a.edit().apply { mergeDocument(KitePDF.open(docB)) }.saveRewritten(useObjectStreams = true)

        val viaKite = KitePDF.open(merged)
        assertEquals(3, viaKite.pageCount)
        assertContains(viaKite.pages[2].extractText(), "Merge Source B")

        val pdf = File.createTempFile("kite-merge-", ".pdf").apply { writeBytes(merged) }
        val pngs = (1..3).map { File.createTempFile("kite-merge-$it-", ".png") }
        try {
            for ((page, png) in (1..3).zip(pngs)) {
                val draw = runMutool(
                    tool, "draw", "-r", "72", "-F", "png", "-o", png.absolutePath, pdf.absolutePath, page.toString(),
                )
                assertEquals(0, draw.exitCode, "mutool draw page $page failed:\n${draw.output}")
                assertTrue(png.exists() && png.length() > 0L, "mutool produced no PNG for page $page")
            }
        } finally {
            pdf.delete()
            pngs.forEach { it.delete() }
        }
    }

    private data class MutoolResult(val exitCode: Int, val output: String)

    private fun runMutool(tool: File, vararg args: String): MutoolResult {
        val proc = ProcessBuilder(listOf(tool.absolutePath) + args)
            .redirectErrorStream(true)
            .start()
        val out = ByteArrayOutputStream()
        proc.inputStream.copyTo(out)
        if (!proc.waitFor(60, TimeUnit.SECONDS)) {
            proc.destroyForcibly()
            return MutoolResult(-1, "timed out")
        }
        return MutoolResult(proc.exitValue(), out.toString(Charsets.UTF_8))
    }

    /** Minimal one-page PDF with a trailer-referenced /Info dict and real text content. */
    private fun buildMinimalPdf(): ByteArray {
        val buf = ByteArrayOutputStream()
        val offsets = mutableListOf<Int>()
        fun write(s: String) = buf.write(s.toByteArray(Charsets.ISO_8859_1))

        write("%PDF-1.4\n%Äå\n")
        offsets.add(buf.size())
        write("1 0 obj\n<< /Type /Catalog /Pages 2 0 R >>\nendobj\n")
        offsets.add(buf.size())
        write("2 0 obj\n<< /Type /Pages /Kids [3 0 R] /Count 1 >>\nendobj\n")
        offsets.add(buf.size())
        write("3 0 obj\n<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] /Resources << /Font << /F1 4 0 R >> >> /Contents 5 0 R >>\nendobj\n")
        offsets.add(buf.size())
        write("4 0 obj\n<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>\nendobj\n")
        offsets.add(buf.size())
        val payload = "BT /F1 18 Tf 72 720 Td (Hello, KitePDF!) Tj ET".toByteArray(Charsets.ISO_8859_1)
        write("5 0 obj\n<< /Length ${payload.size} >>\nstream\n")
        buf.write(payload)
        write("\nendstream\nendobj\n")
        offsets.add(buf.size())
        write("6 0 obj\n<< /Producer (Original Producer) >>\nendobj\n")

        val size = offsets.size + 1
        val xref = buf.size()
        write("xref\n0 $size\n0000000000 65535 f \n")
        for (off in offsets) write("${off.toString().padStart(10, '0')} 00000 n \n")
        write("trailer\n<< /Size $size /Root 1 0 R /Info 6 0 R >>\nstartxref\n$xref\n%%EOF\n")
        return buf.toByteArray()
    }
}
