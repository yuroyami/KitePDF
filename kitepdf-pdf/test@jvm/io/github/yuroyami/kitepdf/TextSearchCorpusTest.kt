package io.github.yuroyami.kitepdf

import io.github.yuroyami.kitepdf.text.search
import org.junit.Assume.assumeTrue
import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Corpus smoke: searching a word in the first page of a real PDF returns hits, inside the page,
 * without throwing. The PDF is in the public corpus, so the test runs on CI and every clean
 * checkout; a checkout without it reports SKIPPED, never a pass (#730).
 */
class TextSearchCorpusTest {

    private fun corpusPdf(name: String): File? {
        var d: File? = File(System.getProperty("user.dir")).absoluteFile
        while (d != null && !File(d, "project.yaml").exists()) d = d.parentFile
        return d?.let { File(it, "corpus/pdf/$name") }?.takeIf { it.exists() }
    }

    @Test
    fun searching_a_known_word_in_a_real_pdf_page_returns_hits() {
        val file = corpusPdf("public-joss-10076.pdf")
        assumeTrue("corpus/pdf/public-joss-10076.pdf is not here; run ./kotlin check corpus", file != null)
        val doc = KitePDF.open(file!!.readBytes())
        val page = doc.pages[0]
        // Every JOSS paper names its journal on the first page, which the search folds to lower case.
        val hits = page.search("software")
        assertTrue(hits.isNotEmpty(), "page 0 names the Journal of Open Source Software")
        for (h in hits) {
            assertTrue(h.quads.isNotEmpty())
            for (q in h.quads) {
                assertTrue(
                    q.left >= -1.0 && q.right <= page.displayWidth + 1.0 &&
                        q.bottom >= -1.0 && q.top <= page.displayHeight + 1.0,
                    "quad within the display box: $q",
                )
            }
        }
    }
}
