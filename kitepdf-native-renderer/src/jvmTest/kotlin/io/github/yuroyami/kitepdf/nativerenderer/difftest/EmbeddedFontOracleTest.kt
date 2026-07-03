package io.github.yuroyami.kitepdf.nativerenderer.difftest

import io.github.yuroyami.kitepdf.KitePDF
import io.github.yuroyami.kitepdf.writer.EmbeddedFont
import io.github.yuroyami.kitepdf.writer.PdfBuilder
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.TimeUnit
import org.junit.Assume.assumeTrue
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Validates custom TrueType font embedding (Milestone A) end to end.
 *
 * Uses the in-repo `DroidSansFallback.ttf` (a TrueType/`glyf` CJK font shipped
 * with MuPDF) to build a PDF whose text is drawn with an embedded Type0 font,
 * then checks two independent consumers:
 *
 *  - **KitePDF's own reader** must recover the original Unicode through the
 *    emitted `/ToUnicode` map (proves Identity-H encoding + ToUnicode are sound).
 *  - **`mutool`** must render the page from the embedded `/FontFile2` program
 *    (proves the font dictionaries and embedded program are well-formed to an
 *    external engine, not just to our own reader).
 *
 * Both halves skip cleanly when their dependency (the font / `mutool`) is absent.
 */
class EmbeddedFontOracleTest {

    // Simplified-Chinese ideographs DroidSansFallback covers.
    private val cjk = "中文测试"
    private val latin = "Hello"

    /** Walk up to the repo root and locate MuPDF's bundled CJK TrueType font. */
    private fun fontFile(): File? {
        var dir: File? = File(System.getProperty("user.dir")).absoluteFile
        while (dir != null) {
            val f = File(dir, "mupdf-master/resources/fonts/droid/DroidSansFallback.ttf")
            if (f.isFile) return f
            dir = dir.parentFile
        }
        return null
    }

    @Test
    fun embeds_truetype_and_round_trips_unicode_through_reader() {
        val ttf = fontFile()
        assumeTrue("DroidSansFallback.ttf not found — skipping.", ttf != null)
        val font = EmbeddedFont.load(ttf!!.readBytes())

        val bytes = PdfBuilder()
            .page { text(font, 24.0, 72.0, 700.0, cjk) }
            .build()

        // Our reader must recover the original text via the /ToUnicode map.
        val doc = KitePDF.open(bytes)
        assertEquals(1, doc.pageCount)
        assertContains(doc.pages[0].extractText(), cjk)

        // The font program must actually be embedded as a composite Type0 font.
        val raw = bytes.toString(Charsets.ISO_8859_1)
        assertTrue(raw.contains("/FontFile2"), "no embedded FontFile2 program")
        assertTrue(raw.contains("/CIDFontType2"), "no CIDFontType2 descendant font")
        assertTrue(raw.contains("/Identity-H"), "font is not Identity-H encoded")
        assertTrue(raw.contains("/ToUnicode"), "no ToUnicode map for extraction")
    }

    @Test
    fun mutool_renders_embedded_truetype_cjk() {
        assumeTrue("mutool not found — skipping oracle validation.", MuPdfOracle.binary != null)
        val tool = MuPdfOracle.binary!!
        val ttf = fontFile()
        assumeTrue("DroidSansFallback.ttf not found — skipping.", ttf != null)
        val font = EmbeddedFont.load(ttf!!.readBytes())

        val bytes = PdfBuilder()
            .setInfo(title = "Embedded CJK 77")
            .page {
                text(font, 28.0, 72.0, 700.0, cjk)
                text(font, 18.0, 72.0, 650.0, latin)
            }
            .build()

        val pdf = File.createTempFile("kite-embed-", ".pdf").apply { writeBytes(bytes) }
        val png = File.createTempFile("kite-embed-", ".png")
        try {
            // mutool must rasterise the page — a malformed FontFile2 or font dict
            // would fail here, not silently substitute.
            val draw = runMutool(
                tool, "draw", "-r", "72", "-F", "png", "-o", png.absolutePath, pdf.absolutePath, "1",
            )
            assertEquals(0, draw.exitCode, "mutool draw failed:\n${draw.output}")
            assertTrue(png.exists() && png.length() > 0L, "mutool produced no PNG")

            // mutool must report our composite Type0 / Identity-H font.
            val info = runMutool(tool, "info", pdf.absolutePath)
            assertEquals(0, info.exitCode, "mutool info failed:\n${info.output}")
            assertTrue(
                info.output.contains("Type0") && info.output.contains("Identity-H"),
                "mutool didn't report a Type0 Identity-H font:\n${info.output}",
            )
        } finally {
            pdf.delete()
            png.delete()
        }
    }

    @Test
    fun subset_embed_is_small_renders_and_round_trips() {
        val ttf = fontFile()
        assumeTrue("DroidSansFallback.ttf not found — skipping.", ttf != null)
        val bytes = ttf!!.readBytes()

        // Same text, subset (default) vs full embed.
        val subsetPdf = PdfBuilder()
            .page { text(EmbeddedFont.load(bytes), 24.0, 72.0, 700.0, cjk) }
            .build()
        val fullPdf = PdfBuilder()
            .page { text(EmbeddedFont.load(bytes, subset = false), 24.0, 72.0, 700.0, cjk) }
            .build()

        // Subsetting only a few glyphs out of a CJK font must shrink the file by orders
        // of magnitude (the whole program is ~3.5 MB).
        assertTrue(
            subsetPdf.size < fullPdf.size / 10 && subsetPdf.size < 100_000,
            "subset not small enough: subset=${subsetPdf.size} full=${fullPdf.size}",
        )

        // Reader still recovers the text (ToUnicode is keyed by the original gid/CID).
        assertContains(KitePDF.open(subsetPdf).pages[0].extractText(), cjk)

        // BaseFont carries a 6-uppercase-letter subset tag (ABCDEF+Name).
        val raw = subsetPdf.toString(Charsets.ISO_8859_1)
        assertTrue(Regex("/BaseFont\\s*/[A-Z]{6}\\+").containsMatchIn(raw), "no subset tag on /BaseFont")
        assertTrue(raw.contains("/CIDToGIDMap"), "subset must use a /CIDToGIDMap stream")

        // The decisive correctness check: the subset must render PIXEL-IDENTICAL to
        // the full embed. Same text + same font, so any difference means the glyf/loca
        // renumber or the /CIDToGIDMap is wrong (wrong glyph drawn, or .notdef boxes) —
        // which a mere "did it produce a PNG?" check would miss.
        assumeTrue("mutool not found — skipping render half.", MuPdfOracle.binary != null)
        val subFile = File.createTempFile("kite-subset-", ".pdf").apply { writeBytes(subsetPdf) }
        val fullFile = File.createTempFile("kite-full-", ".pdf").apply { writeBytes(fullPdf) }
        try {
            val subImg = MuPdfOracle.render(subFile, 1, 144)
            val fullImg = MuPdfOracle.render(fullFile, 1, 144)
            assertNotNull(subImg, "mutool failed to render the subset")
            assertNotNull(fullImg, "mutool failed to render the full embed")
            assertEquals(0.0, meanAbsErr(subImg, fullImg), 0.001, "subset render differs from full embed")
        } finally {
            subFile.delete()
            fullFile.delete()
        }
    }

    /** Mean absolute per-channel difference (0..1) between two equally-sized renders. */
    private fun meanAbsErr(a: BufferedImage, b: BufferedImage): Double {
        assertEquals(a.width to a.height, b.width to b.height, "render sizes differ")
        var sum = 0L
        for (y in 0 until a.height) for (x in 0 until a.width) {
            val pa = a.getRGB(x, y)
            val pb = b.getRGB(x, y)
            sum += kotlin.math.abs(((pa ushr 16) and 0xFF) - ((pb ushr 16) and 0xFF))
            sum += kotlin.math.abs(((pa ushr 8) and 0xFF) - ((pb ushr 8) and 0xFF))
            sum += kotlin.math.abs((pa and 0xFF) - (pb and 0xFF))
        }
        return sum.toDouble() / (a.width.toLong() * a.height * 3 * 255)
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
}
