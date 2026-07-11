package io.github.yuroyami.kitepdf.nativerenderer.difftest

import io.github.yuroyami.kitepdf.KitePDF
import io.github.yuroyami.kitepdf.nativerenderer.AwtPdfRasterizer
import java.io.File
import org.junit.Assume.assumeTrue
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * T-40 acceptance: each shading fixture against mutool with its own budget —
 * types 1/4/5 within 0.05 MAE, the patch types 6/7 within 0.12 (tessellation
 * approximation tolerated by the audit). All five numbers print for the
 * ledger. Skips without mutool.
 */
class ShadingOracleTest {

    private fun diffFor(name: String, bytes: ByteArray): Double {
        val doc = KitePDF.open(bytes)
        val kite = AwtPdfRasterizer.renderToImage(doc.pages[0])
        assertTrue(ImageDiff.nonBackgroundPixels(kite) > 0, "$name painted something")
        val pdf = File.createTempFile("kite-$name", ".pdf").apply {
            deleteOnExit()
            writeBytes(bytes)
        }
        val reference = MuPdfOracle.render(pdf, page = 1, dpi = 72)
        assertNotNull(reference, "mutool rendered $name")
        return ImageDiff.compare(kite, reference).score
    }

    @Test
    fun all_five_shading_types_within_budget() {
        assumeTrue("mutool not found — skipping.", MuPdfOracle.binary != null)
        val fixtures = SyntheticPdfs.all().filter { it.name.startsWith("syn-shading") }
        assertTrue(fixtures.size == 5, "all five shading fixtures registered")

        val budgets = mapOf(
            "syn-shading1-function" to 0.05,
            "syn-shading4-freeform" to 0.05,
            "syn-shading5-lattice" to 0.05,
            "syn-shading6-coons" to 0.12,
            "syn-shading7-tensor" to 0.12,
        )
        val failures = ArrayList<String>()
        for (f in fixtures) {
            val mae = diffFor(f.name, f.bytes)
            val budget = budgets.getValue(f.name)
            println("[T-40] ${f.name}: MAE=${(mae * 10000).toInt() / 10000.0} (budget $budget)")
            if (mae > budget) failures.add("${f.name}: $mae > $budget")
        }
        assertTrue(failures.isEmpty(), failures.joinToString("\n"))
    }
}
