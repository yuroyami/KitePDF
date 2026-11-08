package io.github.yuroyami.kitepdf

import io.github.yuroyami.kitepdf.difftest.MutoolAcceptance
import io.github.yuroyami.kitepdf.writer.EmbeddedFont
import io.github.yuroyami.kitepdf.writer.PdfBuilder
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Assume.assumeTrue

/** Poppler, and mutool where it is installed, read a page that a CFF2 font drew (#917). */
class Cff2PopplerTest {

    private val text = "Hello, CFF2 world! éÆß"

    private fun tool(name: String): String? =
        listOf("/opt/homebrew/bin/$name", "/usr/local/bin/$name", "/usr/bin/$name").firstOrNull { File(it).canExecute() }

    private fun run(vararg command: String): Pair<Int, String> {
        val p = ProcessBuilder(*command).redirectErrorStream(true).start()
        val out = p.inputStream.readBytes().decodeToString()
        p.waitFor(60, TimeUnit.SECONDS)
        return p.exitValue() to out
    }

    private fun pdf(): File {
        val font = EmbeddedFont.load(Cff2Fixture.bytes)
        val bytes = PdfBuilder().page(width = 400.0, height = 100.0) { text(font, 20.0, 10.0, 40.0, text) }.build()
        return File.createTempFile("cff2", ".pdf").apply { deleteOnExit(); writeBytes(bytes) }
    }

    @Test
    fun poppler_reads_the_text_and_the_cff_font() {
        val pdftotext = tool("pdftotext")
        val pdffonts = tool("pdffonts")
        assumeTrue("Poppler's pdftotext and pdffonts", pdftotext != null && pdffonts != null)
        val file = pdf()
        val (code, out) = run(pdftotext!!, "-enc", "UTF-8", file.path, "-")
        assertEquals(0, code, out)
        assertEquals(text, out.trim().trim('\u000C').trim())
        val fonts = run(pdffonts!!, file.path).second
        assertContains(fonts, "CID Type 0C")
        assertTrue("yes yes yes" in fonts.replace(Regex(" +"), " "), "embedded, subset and with a ToUnicode map: $fonts")
    }

    @Test
    fun poppler_draws_the_glyphs() {
        val pdftoppm = tool("pdftoppm")
        assumeTrue("Poppler's pdftoppm", pdftoppm != null)
        val file = pdf()
        val out = File.createTempFile("cff2", "").apply { deleteOnExit() }
        val (code, log) = run(pdftoppm!!, "-r", "72", "-gray", "-png", "-singlefile", file.path, out.path)
        assertEquals(0, code, log)
        assertTrue(log.isBlank(), "Poppler warns: $log")
        val image = javax.imageio.ImageIO.read(File(out.path + ".png"))
        var ink = 0
        for (y in 0 until image.height) for (x in 0 until image.width) if ((image.getRGB(x, y) and 0xFF) < 128) ink++
        assertTrue(ink > 300, "the page has $ink dark pixels")
    }

    @Test
    fun mutool_accepts_the_file() {
        val mutool = tool("mutool")
        assumeTrue("mutool", mutool != null)
        val file = pdf()
        val png = File.createTempFile("cff2", ".png").apply { deleteOnExit() }
        MutoolAcceptance.assertAccepted(MutoolAcceptance.run(File(mutool!!), "draw", "-o", png.absolutePath, file.absolutePath), "the PDF with a CFF2 font")
    }
}
