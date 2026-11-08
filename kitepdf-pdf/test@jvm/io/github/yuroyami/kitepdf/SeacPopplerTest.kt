package io.github.yuroyami.kitepdf

import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertTrue
import org.junit.Assume.assumeTrue

/** Poppler draws the accented letter of a seac glyph that the writer embedded (#902). */
class SeacPopplerTest {

    @Test
    fun poppler_draws_the_e_acute_whole_and_subset() {
        val pdftoppm = listOf("/opt/homebrew/bin/pdftoppm", "/usr/local/bin/pdftoppm", "/usr/bin/pdftoppm").firstOrNull { File(it).canExecute() }
        assumeTrue("Poppler's pdftoppm", pdftoppm != null)
        for (subset in listOf(true, false)) {
            val file = File.createTempFile("seac", ".pdf").apply { deleteOnExit(); writeBytes(SeacEmbedTest.pdf(subset)) }
            val out = File.createTempFile("seac", "").apply { deleteOnExit() }
            val p = ProcessBuilder(pdftoppm!!, "-r", "72", "-gray", "-png", "-singlefile", file.path, out.path).redirectErrorStream(true).start()
            p.waitFor(60, TimeUnit.SECONDS)
            val image = javax.imageio.ImageIO.read(File(out.path + ".png"))
            // Each glyph is 24 points wide at 48 points; the fourth, é, starts at 10 + 3 x 24 = 82.
            var ink = 0
            for (y in 0 until image.height) for (x in 82 until 106) if ((image.getRGB(x, y) and 0xFF) < 128) ink++
            assertTrue(ink > 300, "subset = $subset: é has $ink dark pixels")
        }
    }
}
